package pl.meshcore.observerwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

object ObserverRefresh {
    const val ACTION_REFRESH = "pl.meshcore.observerwidget.ACTION_REFRESH"

    fun pendingIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, ObserverWidgetProvider::class.java).setAction(ACTION_REFRESH)
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun widgetIds(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, ObserverWidgetProvider::class.java))

    /** Odświeża wszystkie widgety. Sieć na Dispatchers.IO, główny wątek nie jest blokowany. */
    fun refreshAll(ctx: Context, pending: BroadcastReceiver.PendingResult? = null) {
        val app = ctx.applicationContext
        val ids = widgetIds(app)
        if (ids.isEmpty()) {
            pending?.finish()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                push(app, ids, WidgetRenderer.render(app, emptyData(app, busy = true)))
                val data = collect(app)
                push(app, ids, safeRender(app, data))
                Alerts.evaluate(app, data)
            } catch (e: Exception) {
                push(app, ids, WidgetRenderer.safe(app, "Błąd odświeżania"))
            } finally {
                pending?.finish()
            }
        }
    }

    /** Wersja dla WorkManagera: wykonuje się synchronicznie w korutynie workera. */
    suspend fun refreshOnce(ctx: Context) {
        val app = ctx.applicationContext
        val ids = widgetIds(app)
        val data = collect(app)
        if (ids.isNotEmpty()) push(app, ids, safeRender(app, data))
        Alerts.evaluate(app, data)
    }

    private fun safeRender(ctx: Context, data: WidgetData) =
        try {
            WidgetRenderer.render(ctx, data)
        } catch (e: Exception) {
            WidgetRenderer.safe(ctx, "Błąd widgetu")
        }

    private fun push(ctx: Context, ids: IntArray, views: android.widget.RemoteViews) {
        val mgr = AppWidgetManager.getInstance(ctx)
        try {
            mgr.updateAppWidget(ids, views)
        } catch (e: Exception) {
            mgr.updateAppWidget(ids, WidgetRenderer.safe(ctx, "Błąd widgetu"))
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
            val r1 = a1.await()
            val r2 = a2.await()
            var obs = (r1 as? FetchResult.Ok)?.snapshot ?: (r2 as? FetchResult.Ok)?.snapshot
            if (obs == null && c.fallbackHost.isNotBlank()) {
                obs = (ObserverApi.fetch(c.fallbackHost, c.observerId, timeout) as? FetchResult.Ok)?.snapshot
            }
            base.copy(observer = obs, broker1 = r1, broker2 = r2)
        }
    }
}
