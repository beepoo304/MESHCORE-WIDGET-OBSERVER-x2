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
    private const val HARD_TIMEOUT_MS = 8_500L
    private const val TICK_INTERVAL_MS = 1_000L

    private val gate = RefreshGate()

    @Volatile
    private var lastData: WidgetData? = null

    @Volatile
    private var nextRefreshElapsedRealtime: Long = 0L

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

    /**
     * UI tick updates only RemoteViews from cached data. It must not use an exact alarm:
     * Android throttles inexact alarms in Doze, while exact alarms require a special user grant.
     */
    fun scheduleTick(ctx: Context) {
        val app = ctx.applicationContext
        if (!Config(app).monitoringEnabled || widgetIds(app).isEmpty()) return
        try {
            val manager = app.getSystemService(AlarmManager::class.java)
            manager.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + TICK_INTERVAL_MS,
                tickIntent(app)
            )
        } catch (e: Exception) {
            Log.w(TAG, "scheduleTick failed", e)
        }
    }

    fun tick(ctx: Context) {
        val app = ctx.applicationContext
        if (!Config(app).monitoringEnabled) return
        val ids = widgetIds(app)
        if (ids.isNotEmpty()) {
            val data = lastData ?: emptyData(app)
            pushAll(app, ids, data.copy(busy = false))
            scheduleTick(app)
        }
    }

    fun nextRefreshElapsedRealtime(intervalSec: Int): Long {
        val scheduled = nextRefreshElapsedRealtime
        return if (scheduled > 0L) scheduled
        else SystemClock.elapsedRealtime() + intervalSec.coerceAtLeast(1) * 1_000L
    }

    fun scheduleNext(ctx: Context) {
        val app = ctx.applicationContext
        if (!Config(app).monitoringEnabled || widgetIds(app).isEmpty()) return
        try {
            val at = SystemClock.elapsedRealtime() + Config(app).intervalSec * 1_000L
            nextRefreshElapsedRealtime = at
            app.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent(app)
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
            nextRefreshElapsedRealtime = 0L
        } catch (e: Exception) {
            Log.w(TAG, "cancelAlarm failed", e)
        }
    }

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        val config = Config(app)
        if (config.monitoringEnabled) config.monitoringStoppedAtMs = System.currentTimeMillis()
        config.monitoringEnabled = false
        cancelAlarm(app)
        RefreshWorker.sync(app)
        Alerts.clearAll(app)
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
        refreshAll(app)
    }

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

    fun renderNow(ctx: Context, ids: IntArray? = null): Boolean {
        val app = ctx.applicationContext
        val targets = ids ?: widgetIds(app)
        if (targets.isEmpty()) return false
        val cached = lastData
        pushAll(app, targets, cached?.copy(busy = false) ?: emptyData(app, busy = true))
        return cached != null
    }

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
        pushAll(app, ids, (lastData ?: emptyData(app)).copy(busy = true))
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
        scheduleTick(app)
    }

    private fun evaluateAlerts(app: Context, data: WidgetData) {
        try {
            Alerts.evaluate(app, data)
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

    private fun pushSafe(ctx: Context, ids: IntArray, message: String) {
        val manager = AppWidgetManager.getInstance(ctx)
        for (id in ids) {
            try {
                manager.updateAppWidget(id, WidgetRenderer.safe(ctx, message))
            } catch (e: Exception) {
                Log.e(TAG, "Safe widget update failed for widget $id", e)
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

    suspend fun collect(ctx: Context): WidgetData {
        val config = Config(ctx)
        val base = emptyData(ctx)
        if (!base.configured) return base
        val timeout = config.timeoutSec * 1_000
        return coroutineScope {
            val first = async(Dispatchers.IO) { ObserverApi.fetch(config.mqtt1Host, config.observerId, timeout) }
            val second = async(Dispatchers.IO) { ObserverApi.fetch(config.mqtt2Host, config.observerId, timeout) }
            val websiteTimeout = minOf(config.intervalSec * 1_000, 5_000)
            val firstHealth = async(Dispatchers.IO) { MqttStatusApi.fetch(config.mqtt1Host, websiteTimeout) }
            val secondHealth = async(Dispatchers.IO) { MqttStatusApi.fetch(config.mqtt2Host, websiteTimeout) }
            val fallbackHealth = async(Dispatchers.IO) {
                if (config.fallbackHost.isBlank()) Health.UNKNOWN
                else MqttStatusApi.fetch(config.fallbackHost, timeout)
            }
            val firstResult = first.await()
            val secondResult = second.await()
            val health1 = firstHealth.await()
            val health2 = secondHealth.await()
            val healthFallback = fallbackHealth.await()
            var observer = (firstResult as? FetchResult.Ok)?.snapshot
                ?: (secondResult as? FetchResult.Ok)?.snapshot
            if (observer == null && config.fallbackHost.isNotBlank()) {
                observer = (ObserverApi.fetch(
                    config.fallbackHost,
                    config.observerId,
                    minOf(timeout, 2_000)
                ) as? FetchResult.Ok)?.snapshot
            }
            base.copy(
                observer = observer,
                broker1 = firstResult,
                broker2 = secondResult,
                mqtt1Health = health1,
                mqtt2Health = health2,
                fallbackHealth = healthFallback
            )
        }
    }
}
