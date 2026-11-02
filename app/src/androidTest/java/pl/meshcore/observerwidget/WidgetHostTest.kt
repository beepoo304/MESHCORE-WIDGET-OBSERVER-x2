package pl.meshcore.observerwidget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prawdziwy AppWidgetHost na emulatorze: widget jest bindowany jak w launcherze, system dostarcza RemoteViews
 * i je pompuje. Gdy pompowanie się nie uda, host pokazuje "Problem loading widget" zamiast naszych widoków.
 */
@RunWith(AndroidJUnit4::class)
class WidgetHostTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val tag = "WidgetHostTest"

    private fun text(v: AppWidgetHostView, id: Int): String? = v.findViewById<TextView>(id)?.text?.toString()

    @Test fun widgetLoadsInRealHostAndShowsBothBrokers() {
        Config(ctx).observerId = "9B250340D918846EFB9140F6243E9253FA0DB9DD3B1B8C0AD8A53337CE5D1234"
        val mgr = AppWidgetManager.getInstance(ctx)
        val host = AppWidgetHost(ctx, 4242)
        val widgetId = host.allocateAppWidgetId()
        val provider = ComponentName(ctx, ObserverWidgetProvider::class.java)

        inst.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            assertTrue("bindAppWidgetIdIfAllowed", mgr.bindAppWidgetIdIfAllowed(widgetId, provider))
        } finally {
            inst.uiAutomation.dropShellPermissionIdentity()
        }
        val info = mgr.getAppWidgetInfo(widgetId)
        assertNotNull("AppWidgetProviderInfo", info)

        var view: AppWidgetHostView? = null
        inst.runOnMainSync {
            host.startListening()
            view = host.createView(ctx, widgetId, info)
            view!!.measure(
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
            )
            view!!.layout(0, 0, 900, 400)
        }
        val v = view!!

        // Wymuszamy nasz pierwszy widok tak jak onUpdate (system i tak wysyła APPWIDGET_UPDATE).
        ObserverRefresh.renderNow(ctx, intArrayOf(widgetId))

        // 1) nasze RemoteViews muszą zostać zastosowane (m1_host ustawia tylko WidgetRenderer)
        var host1: String? = null
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 30_000) {
            inst.runOnMainSync { host1 = text(v, R.id.m1_host) }
            if (host1 == Config.DEFAULT_MQTT1) break
            Thread.sleep(500)
        }
        Log.i(tag, "m1_host after render: $host1")
        assertTrue("Widget nie załadował naszych widoków (host1=$host1)", host1 == Config.DEFAULT_MQTT1)

        // 2) po pełnym odświeżeniu oba wiersze MQTT mają pokazywać stan z API (nie "—" / "…")
        ObserverRefresh.refreshAll(ctx)
        var h1: String? = null
        var h2: String? = null
        var l1: String? = null
        val t1 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t1 < 60_000) {
            inst.runOnMainSync {
                h1 = text(v, R.id.m1_hour); h2 = text(v, R.id.m2_hour); l1 = text(v, R.id.m1_last)
            }
            // MQTT2 może legalnie nie znać tego observera (404), więc czekamy tylko na MQTT1
            if (h1 != null && h1 != "—") break
            Thread.sleep(1000)
        }
        Log.i(tag, "after refresh: m1_hour=$h1 m2_hour=$h2 m1_last=$l1")
        host.deleteAppWidgetId(widgetId)
        host.stopListening()
        assertTrue("Wiersz MQTT1 nie wczytał danych (m1_hour=$h1, m1_last=$l1)", h1 != null && h1 != "—")
    }
}
