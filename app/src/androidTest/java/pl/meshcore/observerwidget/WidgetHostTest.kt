package pl.meshcore.observerwidget

import android.appwidget.AppWidgetHostView
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator test: inflate the production RemoteViews inside a real AppWidgetHostView. */
@RunWith(AndroidJUnit4::class)
class WidgetHostTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    private fun text(view: AppWidgetHostView, id: Int): String? =
        view.findViewById<TextView>(id)?.text?.toString()

    @Test
    fun widgetLoadsInRealHostAndShowsBothBrokers() {
        val observerId = "9B250340D918846EFB9140F6243E9253FA0DB9DD3B1B8C0AD8A53337CE5D1234"
        val now = System.currentTimeMillis()
        val snapshot = ObserverSnapshot(
            "hash",
            observerId,
            "Ro69ot",
            "ProMicro",
            "v1",
            171_554,
            143,
            now - 30_000L,
            now
        )
        val result = FetchResult.Ok(snapshot)
        val data = WidgetData(
            true,
            snapshot,
            result,
            result,
            15,
            3,
            5,
            "live.meshcorekk.xyz",
            "analyzer.marwoj.net",
            observerId,
            false
        )

        lateinit var hostView: AppWidgetHostView
        inst.runOnMainSync {
            hostView = AppWidgetHostView(ctx)
            hostView.updateAppWidget(WidgetRenderer.render(ctx, data))
            hostView.measure(
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
            )
            hostView.layout(0, 0, 900, 400)
        }

        assertNotNull("MQTT1 packets/h view", hostView.findViewById<View>(R.id.m1_hour))
        assertNotNull("MQTT1 last-packet chronometer", hostView.findViewById<View>(R.id.m1_last))
        assertNotNull("MQTT2 packets/h view", hostView.findViewById<View>(R.id.m2_hour))
        assertNotNull("MQTT2 last-packet chronometer", hostView.findViewById<View>(R.id.m2_last))
        assertTrue("MQTT1 packet rate missing", text(hostView, R.id.m1_hour).orEmpty().contains("143"))
        assertTrue("MQTT2 packet rate missing", text(hostView, R.id.m2_hour).orEmpty().contains("143"))
        assertTrue("MQTT1 total packets missing", !text(hostView, R.id.m1_total).isNullOrBlank())
        assertTrue("MQTT2 total packets missing", !text(hostView, R.id.m2_total).isNullOrBlank())
    }
}
