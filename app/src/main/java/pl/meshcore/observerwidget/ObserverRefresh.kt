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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object ObserverRefresh {
    const val ACTION_REFRESH = "pl.meshcore.observerwidget.ACTION_REFRESH"
    const val ACTION_TICK = "pl.meshcore.observerwidget.ACTION_TICK"
    private const val TAG = "ObserverWidget"

    /** Poniżej limitu ~10 s dla odbiorników z goAsync(), po którym system uznaje aplikację za zawieszoną. */
    private const val HARD_TIMEOUT_MS = 8_500L

    private val gate = RefreshGate()

    /** Ostatnie dane: pozwalają natychmiast narysować widget (też po zmianie rozmiaru) bez czekania na sieć. */
    @Volatile
    private var lastData: WidgetData? = null

    @Volatile
    private var nextRefreshElapsedRealtime: Long = SystemClock.elapsedRealtime()

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

    fun scheduleTick(ctx: Context) {
        val app = ctx.applicationContext
        if (!Config(app).monitoringEnabled || widgetIds(app).isEmpty()) return
        try {
            val at = SystemClock.elapsedRealtime() + 1_000L
            app.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, at, tickIntent(app)
            )
        } catch (e: Exception) {
            Log.w(TAG, "scheduleTick failed", e)
        }
    }

    fun tick(ctx: Context) {
        val app = ctx.applicationContext
        if (!Config(app).monitoringEnabled) return
        val data = lastData ?: emptyData(app)
        val ids = widgetIds(app)
        if (ids.isNotEmpty()) pushAll(app, ids, data.copy(busy = false))
        scheduleTick(app)
    }

    /**
     * WorkManager ma minimum 15 min, więc interwał z ustawień (np. 15 s) realizuje samoodnawialny alarm:
     * po każdym odświeżeniu planujemy następne. Nie wymaga uprawnień (inexact, działa też w Doze, choć rzadziej).
     * WorkManager zostaje jako zapas, który wznawia łańcuch po zabiciu procesu.
     */
    fun nextRefreshElapsedRealtime(intervalSec: Int): Long =
        nextRefreshElapsedRealtime.takeIf { it > 0L }
            ?: (SystemClock.elapsedRealtime() + intervalSec.coerceAtLeast(1) * 1_000L)

    fun scheduleNext(ctx: Context) {
        val app = ctx.applicationContext
        if (!Config(app).monitoringEnabled || widgetIds(app).isEmpty()) return
        try {
            val am = app.getSystemService(AlarmManager::class.java)
            val at = SystemClock.elapsedRealtime() + Config(app).intervalSec * 1000L
            nextRefreshElapsedRealtime = at
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent(app))
        } catch (e: Exception) {
            Log.w(TAG, "scheduleNext failed", e)
        }
    }

    fun cancelAlarm(ctx: Context) {
        try {
            val app = ctx.applicationContext
            val am = app.getSystemService(AlarmManager::class.java)
            am.cancel(alarmIntent(app))
            am.cancel(tickIntent(app))
        } catch (e: Exception) {
            Log.w(TAG, "cancelAlarm failed", e)
        }
    }

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        Config(app).monitoringEnabled = false
        cancelAlarm(app)
        RefreshWorker.sync(app)
        Alerts.clearAll(app)
        lastData = lastData?.copy(busy = false)
        val ids = widgetIds(app)
        if (ids.isNotEmpty()) pushAll(app, ids, lastData ?: emptyData(app))
    }

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        Config(app).monitoringEnabled = true
        RefreshWorker.sync(app)
        refreshAll(app)
    }

    private fun widgetIds(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, ObserverWidgetProvider::class.java))

    /** Wariant layoutu dla konkretnego widgetu, na podstawie rozmiaru z opcji launchera. */
    private fun layoutFor(mgr: AppWidgetManager, id: Int): WidgetLayout {
        val o = try {
            mgr.getAppWidgetOptions(id)
        } catch (e: Exception) {
            null
        }
        return WidgetLayout.choose(
            o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0,
            o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        )
    }

    /**
     * Natychmiastowy widok (bez sieci), wywoływany synchronicznie z onUpdate / po zmianie rozmiaru:
     * ostatnie znane dane albo stan "ładowanie" / "ustaw hash". Zwraca false, gdy nie ma jeszcze żadnych danych.
     */
    fun renderNow(ctx: Context, ids: IntArray? = null): Boolean {
        val app = ctx.applicationContext
        val targets = ids ?: widgetIds(app)
        if (targets.isEmpty()) return false
        val cached = lastData
        pushAll(app, targets, cached?.copy(busy = false) ?: emptyData(app, busy = true))
        return cached != null
    }

    /**
     * Odświeża wszystkie widgety. Sieć na Dispatchers.IO, główny wątek nie jest blokowany.
     * Gdy odświeżanie już trwa, żądanie jest zapamiętane i wykonane po bieżącym cyklu (nie ginie).
     */
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
                        val ids = widgetIds(app)
                        if (ids.isNotEmpty()) pushSafe(app, ids, "Błąd odświeżania")
                    }
                } while (gate.finish())
            } finally {
                scheduleNext(app)
                scheduleTick(app)
                pending?.finish()
            }
        }
    }

    private suspend fun cycle(app: Context) {
        nextRefreshElapsedRealtime = SystemClock.elapsedRealtime() + Config(app).intervalSec * 1_000L
        val ids = widgetIds(app)
        if (ids.isEmpty()) return
        // 1) od razu pokazujemy ostatnie dane (albo stan ładowania) ze znacznikiem "odświeżanie"
        pushAll(app, ids, (lastData ?: emptyData(app)).copy(busy = true))
        // 2) pobieranie z twardym limitem czasu; blokujące I/O nie jest anulowalne, więc czekamy na Deferred
        val job = CoroutineScope(Dispatchers.IO).async { collect(app) }
        val data = withTimeoutOrNull(HARD_TIMEOUT_MS) { job.await() }
        if (data == null) {
            job.cancel()
            Log.w(TAG, "Refresh timed out after ${HARD_TIMEOUT_MS} ms")
        }
        if (!Config(app).monitoringEnabled) return
        val result = data ?: lastData?.copy(busy = false) ?: emptyData(app)
        if (data != null) {
            lastData = data
            Log.i(TAG, "Refresh OK: mqtt1=${(data.broker1 as? FetchResult.Ok)?.snapshot?.totalPackets}, mqtt2=${(data.broker2 as? FetchResult.Ok)?.snapshot?.totalPackets}")
        } else {
            Log.w(TAG, "Refresh timed out; keeping last successful counters")
        }
        pushAll(app, widgetIds(app), result)
        evaluateAlerts(app, result)
    }

    /** Wersja dla WorkManagera: wykonuje się synchronicznie w korutynie workera. */
    suspend fun refreshOnce(ctx: Context) {
        val app = ctx.applicationContext
        if (!Config(app).monitoringEnabled) return
        val data = collect(app)
        if (!Config(app).monitoringEnabled) return
        lastData = data
        val ids = widgetIds(app)
        if (ids.isNotEmpty()) pushAll(app, ids, data)
        evaluateAlerts(app, data)
        scheduleNext(app)
    }

    /** Błąd alertów (np. powiadomienia) nie może zastąpić poprawnie narysowanego widgetu ekranem błędu. */
    private fun evaluateAlerts(app: Context, data: WidgetData) {
        try {
            Alerts.evaluate(app, data)
        } catch (e: Exception) {
            Log.w(TAG, "Alert evaluation failed", e)
        }
    }

    private fun pushAll(ctx: Context, ids: IntArray, data: WidgetData) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (id in ids) {
            val views: RemoteViews = try {
                WidgetRenderer.render(ctx, data, layoutFor(mgr, id))
            } catch (e: Exception) {
                Log.e(TAG, "Render failed for widget $id", e)
                WidgetRenderer.safe(ctx, "Błąd widgetu")
            }
            try {
                mgr.updateAppWidget(id, views)
            } catch (e: Exception) {
                Log.e(TAG, "updateAppWidget failed for widget $id", e)
                try {
                    mgr.updateAppWidget(id, WidgetRenderer.safe(ctx, "Błąd widgetu"))
                } catch (e2: Exception) {
                    Log.e(TAG, "Fallback widget update failed for widget $id", e2)
                }
            }
        }
    }

    private fun pushSafe(ctx: Context, ids: IntArray, message: String) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (id in ids) {
            try {
                mgr.updateAppWidget(id, WidgetRenderer.safe(ctx, message))
            } catch (e: Exception) {
                Log.e(TAG, "Safe widget update failed for widget $id", e)
            }
        }
    }

    fun emptyData(ctx: Context, busy: Boolean = false): WidgetData {
        val c = Config(ctx)
        return WidgetData(
            configured = Config.isValidId(c.observerId), observer = null, broker1 = null, broker2 = null,
            intervalSec = c.intervalSec, orangeMin = c.orangeMin, redMin = c.redMin,
            mqtt1Host = c.mqtt1Host, mqtt2Host = c.mqtt2Host, observerId = c.observerId, busy = busy
        )
    }

    suspend fun collect(ctx: Context): WidgetData {
        val c = Config(ctx)
        val base = emptyData(ctx)
        if (!base.configured) return base
        val timeout = c.timeoutSec * 1000
        return coroutineScope {
            val a1 = async(Dispatchers.IO) { ObserverApi.fetch(c.mqtt1Host, c.observerId, timeout) }
            val a2 = async(Dispatchers.IO) { ObserverApi.fetch(c.mqtt2Host, c.observerId, timeout) }
            // Website reachability is checked independently from MQTT telemetry.
            // Each probe is short; intervalSec controls when the next cycle starts,
            // not how long an HTTP request is allowed to run.
            val websiteTimeout = minOf(c.intervalSec * 1_000, 5_000)
            val h1 = async(Dispatchers.IO) { MqttStatusApi.checkWebsite(c.mqtt1Host, websiteTimeout) }
            val h2 = async(Dispatchers.IO) { MqttStatusApi.checkWebsite(c.mqtt2Host, websiteTimeout) }
            val hf = async(Dispatchers.IO) { if (c.fallbackHost.isBlank()) Health.UNKNOWN else MqttStatusApi.fetch(c.fallbackHost, timeout) }
            val r1 = a1.await()
            val r2 = a2.await()
            val health1 = h1.await()
            val health2 = h2.await()
            val fallbackHealth = hf.await()
            var obs = (r1 as? FetchResult.Ok)?.snapshot ?: (r2 as? FetchResult.Ok)?.snapshot
            if (obs == null && c.fallbackHost.isNotBlank()) {
                obs = (ObserverApi.fetch(c.fallbackHost, c.observerId, minOf(timeout, 2_000)) as? FetchResult.Ok)?.snapshot
            }
            base.copy(observer = obs, broker1 = r1, broker2 = r2, mqtt1Health = health1, mqtt2Health = health2, fallbackHealth = fallbackHealth)
        }
    }
}
