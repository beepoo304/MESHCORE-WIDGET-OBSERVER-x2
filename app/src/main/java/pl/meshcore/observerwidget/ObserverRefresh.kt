package pl.meshcore.observerwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pobieranie danych i rysowanie widgetu.
 *
 * Tykanie co sekundę robi [MonitorService] (usługa pierwszoplanowa). AlarmManager nie nadaje się
 * do tego: Android nie dostarcza alarmów aplikacji częściej niż co ~5 s (MIN_FUTURITY), a bez
 * uprawnienia „Dokładne alarmy” (domyślnie wyłączone od Androida 14) – jeszcze rzadziej.
 * Alarm zostaje tylko jako rezerwa, gdy systemowi nie uda się uruchomić usługi.
 */
object ObserverRefresh {
    const val ACTION_REFRESH = "pl.meshcore.observerwidget.ACTION_REFRESH"
    const val ACTION_TICK = "pl.meshcore.observerwidget.ACTION_TICK"
    private const val TAG = "ObserverWidget"
    private const val HARD_TIMEOUT_MS = 8_500L
    private const val FALLBACK_TICK_MS = 1_000L
    private const val MIN_SPIN_MS = 900L

    private val gate = RefreshGate()

    @Volatile
    private var lastData: WidgetData? = null

    /** Czas (zegar ścienny, ms) następnego odświeżenia; wyrównany do pełnej sekundy. */
    @Volatile
    private var nextRefreshAtMs: Long = 0L

    @Volatile
    var refreshInProgress: Boolean = false
        private set

    /**
     * Ostatnia znana wartość wieku pakietu dla każdego brokera – aktualizowana przy udanym
     * pobraniu. Gdy API brokera padnie, widget pokazuje tę wartość zamrożoną zamiast „API ✕”.
     */
    @Volatile
    private var lastKnownBroker1AgeMs: Long? = null

    @Volatile
    private var lastKnownBroker2AgeMs: Long? = null

    @Volatile
    private var lastOkBroker1: ObserverSnapshot? = null

    @Volatile
    private var lastOkBroker2: ObserverSnapshot? = null

    fun latest(): WidgetData? = lastData

    fun pendingIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, ObserverWidgetProvider::class.java).setAction(ACTION_REFRESH)
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun alarmIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, ObserverWidgetProvider::class.java).setAction(ACTION_REFRESH)
        return PendingIntent.getBroadcast(ctx, 2, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun tickIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, ObserverWidgetProvider::class.java).setAction(ACTION_TICK)
        return PendingIntent.getBroadcast(ctx, 3, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    // ---------- harmonogram ----------

    private fun alignUp(ms: Long): Long = ((ms + 999L) / 1_000L) * 1_000L

    /** Ustawia kolejne odświeżenie na „teraz + interwał”, wyrównane do pełnej sekundy. */
    private fun planNext(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
        nextRefreshAtMs = alignUp(nowMs) + Config(ctx).intervalSec * 1_000L
    }

    fun nextRefreshAtMs(): Long = nextRefreshAtMs

    fun isRefreshDue(nowMs: Long = System.currentTimeMillis()): Boolean =
        !refreshInProgress && (nextRefreshAtMs == 0L || nowMs >= nextRefreshAtMs)

    /** Sekundy do odświeżenia pokazywane w widgecie. */
    fun remainingSeconds(ctx: Context, nowMs: Long = System.currentTimeMillis()): Long {
        if (refreshInProgress) return 0L
        val target = nextRefreshAtMs
        if (target == 0L) return Config(ctx).intervalSec.toLong()
        return TimeFormat.remainingSeconds(target, nowMs).coerceAtMost(Config(ctx).intervalSec.toLong())
    }

    /** Rezerwowe tykanie przez AlarmManager – tylko gdy usługa nie działa. */
    fun scheduleTick(ctx: Context) {
        val app = ctx.applicationContext
        if (MonitorService.isRunning || !Config(app).monitoringEnabled || widgetIds(app).isEmpty()) return
        try {
            app.getSystemService(AlarmManager::class.java).set(
                AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + FALLBACK_TICK_MS, tickIntent(app)
            )
        } catch (e: Exception) {
            Log.w(TAG, "scheduleTick failed", e)
        }
    }

    /** Rezerwowe tyknięcie (alarm): rysuje i ewentualnie odświeża. */
    fun tick(ctx: Context) {
        val app = ctx.applicationContext
        if (!Config(app).monitoringEnabled) return
        if (MonitorService.isRunning) return
        renderAll(app)
        if (isRefreshDue()) refreshAll(app) else scheduleTick(app)
    }

    /** Rezerwowy alarm odświeżenia (gdy usługa nie działa). */
    fun scheduleNext(ctx: Context) {
        val app = ctx.applicationContext
        if (MonitorService.isRunning || !Config(app).monitoringEnabled || widgetIds(app).isEmpty()) return
        try {
            val delay = (nextRefreshAtMs - System.currentTimeMillis()).coerceAtLeast(1_000L)
            app.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, alarmIntent(app)
            )
        } catch (e: Exception) {
            Log.w(TAG, "scheduleNext failed", e)
        }
    }

    fun cancelAlarm(ctx: Context) {
        val app = ctx.applicationContext
        try {
            val manager = app.getSystemService(AlarmManager::class.java)
            manager.cancel(alarmIntent(app))
            manager.cancel(tickIntent(app))
        } catch (e: Exception) {
            Log.w(TAG, "cancelAlarm failed", e)
        }
    }

    // ---------- start / stop ----------

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        val config = Config(app)
        if (config.monitoringStoppedAtMs <= 0L) config.monitoringStoppedAtMs = System.currentTimeMillis()
        config.monitoringEnabled = false
        cancelAlarm(app)
        nextRefreshAtMs = 0L
        MonitorService.stop(app)
        RefreshWorker.sync(app)
        Alerts.clearAll(app)
        Outages.closeAll(app, System.currentTimeMillis(), tr("monitoring zatrzymany", "monitoring stopped"))
        lastData = lastData?.copy(busy = false)
        val ids = widgetIds(app)
        if (ids.isNotEmpty()) pushAll(app, ids, lastData ?: emptyData(app))
    }

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        val config = Config(app)
        config.monitoringStoppedAtMs = 0L
        config.monitoringEnabled = true
        RefreshWorker.sync(app)
        ensureTicking(app)
        refreshAll(app)
    }

    /** Uruchamia usługę tykania; jeśli Android na to nie pozwala, włącza rezerwę z alarmów. */
    fun ensureTicking(ctx: Context) {
        val app = ctx.applicationContext
        if (!Config(app).monitoringEnabled || widgetIds(app).isEmpty()) {
            MonitorService.stop(app)
            return
        }
        if (!MonitorService.start(app)) {
            scheduleTick(app)
            scheduleNext(app)
        }
    }

    fun hasWidgets(ctx: Context): Boolean = widgetIds(ctx.applicationContext).isNotEmpty()

    private fun widgetIds(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, ObserverWidgetProvider::class.java))

    private fun layoutFor(mgr: AppWidgetManager, id: Int): WidgetLayout {
        val options = try {
            mgr.getAppWidgetOptions(id)
        } catch (_: Exception) {
            null
        }
        return WidgetLayout.choose(
            options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0,
            options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        )
    }

    // ---------- rysowanie ----------

    /** Przerysowuje wszystkie widgety z danych w pamięci (liczniki sekund). */
    fun renderAll(ctx: Context) {
        val app = ctx.applicationContext
        val ids = widgetIds(app)
        if (ids.isEmpty()) return
        val data = lastData ?: emptyData(app, busy = refreshInProgress)
        pushAll(app, ids, data.copy(busy = refreshInProgress))
    }

    fun renderNow(ctx: Context, ids: IntArray? = null): Boolean {
        val app = ctx.applicationContext
        val targets = ids ?: widgetIds(app)
        if (targets.isEmpty()) return false
        val cached = lastData
        pushAll(app, targets, cached?.copy(busy = refreshInProgress) ?: emptyData(app, busy = true))
        return cached != null
    }

    // ---------- odświeżanie ----------

    fun refreshAll(ctx: Context, pending: BroadcastReceiver.PendingResult? = null) {
        if (!Config(ctx).monitoringEnabled) {
            cancelAlarm(ctx)
            pending?.finish()
            return
        }
        if (!gate.request()) {
            pending?.finish()
            return
        }
        val app = ctx.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                do {
                    try {
                        cycle(app)
                    } catch (e: Exception) {
                        Log.e(TAG, "Refresh cycle failed", e)
                    }
                } while (gate.finish())
            } finally {
                refreshInProgress = false
                renderAll(app)
                scheduleNext(app)
                scheduleTick(app)
                pending?.finish()
            }
        }
    }

    private suspend fun cycle(app: Context) {
        refreshInProgress = true
        // Kolejne odświeżenie liczymy od początku pobierania – stały rytm, niezależny od czasu odpowiedzi.
        planNext(app)
        renderAll(app)
        val startedAt = SystemClock.elapsedRealtime()
        val job = CoroutineScope(Dispatchers.IO).async { collect(app) }
        val data = withTimeoutOrNull(HARD_TIMEOUT_MS) { job.await() }
        // Ikona ma zrobić przynajmniej jeden pełny obrót, nawet gdy serwery odpowiedzą błyskawicznie.
        val spent = SystemClock.elapsedRealtime() - startedAt
        if (spent < MIN_SPIN_MS) delay(MIN_SPIN_MS - spent)
        if (data == null) {
            job.cancel()
            Log.w(TAG, "Refresh timed out after $HARD_TIMEOUT_MS ms; keeping last data")
        }
        if (!Config(app).monitoringEnabled) return
        if (data != null) lastData = data
        val result = (data ?: lastData ?: emptyData(app)).copy(busy = false)
        evaluateAlerts(app, result, fresh = data != null)
    }

    suspend fun refreshOnce(ctx: Context) {
        val app = ctx.applicationContext
        if (!Config(app).monitoringEnabled) return
        val data = collect(app)
        if (!Config(app).monitoringEnabled) return
        lastData = data
        renderAll(app)
        evaluateAlerts(app, data, fresh = true)
        ensureTicking(app)
    }

    private fun evaluateAlerts(app: Context, data: WidgetData, fresh: Boolean) {
        MonitorService.updateNotification(app, data)
        try {
            Alerts.evaluate(app, data)
            // Powiadomienie o mapowaniu tylko przy świeżych danych (nie po czasie od ostatka).
            if (fresh && data.mappingNew.isNotEmpty()) {
                Alerts.evaluateMapping(app, data.mapping, data.mappingNew)
            }
            if (fresh) for ((slot, pings) in data.rptNew) {
                val rpt = data.rptMappings.firstOrNull { it.slot == slot } ?: continue
                if (pings.isNotEmpty()) Alerts.evaluateRptMapping(app, rpt, pings)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Alert evaluation failed", e)
        }
    }

    private fun pushAll(ctx: Context, ids: IntArray, data: WidgetData) {
        val manager = AppWidgetManager.getInstance(ctx)
        for (id in ids) {
            val views: RemoteViews = try {
                WidgetRenderer.render(ctx, data, layoutFor(manager, id))
            } catch (e: Exception) {
                Log.e(TAG, "Render failed for widget $id", e)
                WidgetRenderer.safe(ctx, "Błąd widgetu")
            }
            try {
                manager.updateAppWidget(id, views)
            } catch (e: Exception) {
                Log.e(TAG, "updateAppWidget failed for widget $id", e)
                try {
                    manager.updateAppWidget(id, WidgetRenderer.safe(ctx, "Błąd widgetu"))
                } catch (fallback: Exception) {
                    Log.e(TAG, "Fallback widget update failed for widget $id", fallback)
                }
            }
        }
    }

    fun emptyData(ctx: Context, busy: Boolean = false): WidgetData {
        val config = Config(ctx)
        return WidgetData(
            configured = Config.isValidId(config.observerId),
            observer = null,
            broker1 = null,
            broker2 = null,
            intervalSec = config.intervalSec,
            orangeMin = config.orangeMin,
            redMin = config.redMin,
            mqtt1Host = config.mqtt1Host,
            mqtt2Host = config.mqtt2Host,
            observerId = config.observerId,
            busy = busy
        )
    }

    /**
     * Jedno odświeżenie: z każdego brokera pobieramy (równolegle)
     *  1) /api/observers/{id} – ostatni pakiet wysłany przez Observera,
     *  2) /api/mqtt/status    – czy broker żyje jako strona agregacyjna.
     */
    suspend fun collect(ctx: Context): WidgetData {
        val config = Config(ctx)
        val base = emptyData(ctx)
        if (!base.configured) return base
        val timeout = config.timeoutSec * 1_000
        val statusTimeout = minOf(timeout, 5_000)
        return coroutineScope {
            val first = async(Dispatchers.IO) { ObserverApi.fetch(config.mqtt1Host, config.observerId, timeout) }
            val second = async(Dispatchers.IO) { ObserverApi.fetch(config.mqtt2Host, config.observerId, timeout) }
            val firstHealth = async(Dispatchers.IO) { MqttStatusApi.probe(config.mqtt1Host, statusTimeout) }
            val secondHealth = async(Dispatchers.IO) { MqttStatusApi.probe(config.mqtt2Host, statusTimeout) }
            val fallbackHealth = async(Dispatchers.IO) {
                if (config.fallbackHost.isBlank()) MqttStatusApi.Probe(Health.UNKNOWN, false)
                else MqttStatusApi.probe(config.fallbackHost, statusTimeout)
            }
            // Baza mapera MeshCore South: czy ktoś mapuje w zasięgu naszego Observera.
            val mapping = async(Dispatchers.IO) { MapperMonitor.poll(ctx, config.observerId, minOf(timeout, 5_000), config.rptIds) }
            val firstResult = first.await()
            val secondResult = second.await()
            var health1 = firstHealth.await()
            var health2 = secondHealth.await()
            val healthFallback = fallbackHealth.await()

            // Telefon bez internetu: wszystko pada na poziomie sieci – to nie awaria brokerów.
            val phoneOffline = health1.networkError && health2.networkError &&
                (config.fallbackHost.isBlank() || healthFallback.networkError)
            if (phoneOffline) {
                health1 = MqttStatusApi.Probe(Health.UNKNOWN, true)
                health2 = MqttStatusApi.Probe(Health.UNKNOWN, true)
            }

            var observer = (firstResult as? FetchResult.Ok)?.snapshot
                ?: (secondResult as? FetchResult.Ok)?.snapshot
            if (observer == null && config.fallbackHost.isNotBlank() && !phoneOffline) {
                observer = (ObserverApi.fetch(
                    config.fallbackHost,
                    config.observerId,
                    minOf(timeout, 2_000)
                ) as? FetchResult.Ok)?.snapshot
            }
            // Zamrożony wiek pakietu: zapamiętujemy ostatnią znaną wartość przy udanym pobraniu,
            // żeby przy padnięciu API brokera licznik nie uciekał w nieskończoność.
            rememberBrokerAges(firstResult, secondResult)
            val mappingResult = mapping.await()
            base.copy(
                observer = observer,
                broker1 = firstResult,
                broker2 = secondResult,
                mqtt1Health = health1.health,
                mqtt2Health = health2.health,
                fallbackHealth = healthFallback.health,
                broker1FrozenAgeMs = if (firstResult is FetchResult.Ok) null else lastKnownBroker1AgeMs,
                broker2FrozenAgeMs = if (secondResult is FetchResult.Ok) null else lastKnownBroker2AgeMs,
                broker1LastOk = lastOkBroker1,
                broker2LastOk = lastOkBroker2,
                mapping = mappingResult.state,
                mappingNew = mappingResult.fresh,
                rptMappings = mappingResult.rpt,
                rptNew = mappingResult.rptFresh
            )
        }
    }

    /** Wiek pakietu liczony w chwili udanego pobrania – ta wartość zostanie pokazana po padnięciu API. */
    private fun rememberBrokerAges(first: FetchResult, second: FetchResult) {
        (first as? FetchResult.Ok)?.snapshot?.lastPacketAgeMs(System.currentTimeMillis())?.let { lastKnownBroker1AgeMs = it }
        (second as? FetchResult.Ok)?.snapshot?.lastPacketAgeMs(System.currentTimeMillis())?.let { lastKnownBroker2AgeMs = it }
        (first as? FetchResult.Ok)?.let { lastOkBroker1 = it.snapshot }
        (second as? FetchResult.Ok)?.let { lastOkBroker2 = it.snapshot }
    }
}
