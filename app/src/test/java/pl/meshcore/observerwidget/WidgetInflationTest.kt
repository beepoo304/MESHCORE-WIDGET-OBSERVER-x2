package pl.meshcore.observerwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.RemoteViews
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Prawdziwe pompowanie RemoteViews (jak w launcherze): wyjątek tutaj = widget nie załaduje się na telefonie. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetInflationTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val id = "9B250340D918846EFB9140F6243E9253FA0DB9DD3B1B8C0AD8A53337CE5D1234"
    private val now = System.currentTimeMillis()

    @Before fun initWorkManager() {
        try { WorkManager.initialize(ctx, Configuration.Builder().build()) } catch (_: IllegalStateException) { }
    }

    private fun inflate(v: RemoteViews) = v.apply(ctx, FrameLayout(ctx))

    private fun snap(lastAgoMs: Long?) = ObserverSnapshot(
        "h", id, "Ro69ot", "ProMicro", "v1", 171_554, 143, lastAgoMs?.let { now - it }, now
    )

    private fun data(configured: Boolean, b1: FetchResult?, b2: FetchResult?, busy: Boolean = false) = WidgetData(
        configured, (b1 as? FetchResult.Ok)?.snapshot, b1, b2, 15, 3, 5,
        "live.meshcorekk.xyz", "analyzer.marwoj.net", if (configured) id else "", busy
    )

    private val layouts = listOf(
        WidgetLayout(compact = true, showTotal = true), WidgetLayout(compact = true, showTotal = false),
        WidgetLayout(compact = false, showTotal = true), WidgetLayout(compact = false, showTotal = false)
    )

    private val states = listOf(
        data(false, null, null),
        data(true, null, null, busy = true),
        data(true, FetchResult.Ok(snap(30_000)), FetchResult.Fail("h", "HTTP 404")),
        data(true, FetchResult.Ok(snap(4 * 60_000L)), FetchResult.Ok(snap(10 * 60_000L))),
        data(true, FetchResult.Ok(snap(null)), FetchResult.Fail("h", "Timeout")),
        data(true, FetchResult.Fail("h", "Timeout"), FetchResult.Fail("h", "Brak połączenia")),
        // Mapowanie w toku: ikona M widoczna, puls (alpha) ustawiany w render.
        data(true, FetchResult.Ok(snap(30_000)), FetchResult.Ok(snap(30_000))).copy(
            mapping = MappingState(
                active = true,
                pings = listOf(
                    MapperPing(1, now - 40_000, 50.0, 19.0, id.lowercase().take(16), 10.0, 9.5, -48.0, 2, 6.0, -115.0)
                ),
                mapperNicks = listOf("MapperTest")
            ),
            mappingNew = listOf(
                MapperPing(1, now - 40_000, 50.0, 19.0, id.lowercase().take(16), 10.0, 9.5, -48.0, 2, 6.0, -115.0)
            )
        )
    )

    @Test fun everyLayoutAndStateInflates() {
        for (l in layouts) for ((i, d) in states.withIndex()) {
            try {
                assertNotNull(inflate(WidgetRenderer.render(ctx, d, l)))
            } catch (e: Throwable) {
                throw AssertionError("render failed: layout=$l state=#$i: ${e}", e)
            }
        }
    }

    @Test fun compactBrokerCellsKeepTheirSeparateTitles() {
        val root = android.view.LayoutInflater.from(ctx)
            .inflate(R.layout.widget_observer_compact, FrameLayout(ctx), false)
        assertEquals("MQTT1", root.findViewById<TextView>(R.id.m1_title).text.toString())
        assertEquals("MQTT2", root.findViewById<TextView>(R.id.m2_title).text.toString())
    }

    @Test fun compactBrokerColumnsAreAligned() {
        assertBrokerColumnsAligned(R.layout.widget_observer_compact, 180)
    }

    @Test fun fullBrokerColumnsAreAligned() {
        assertBrokerColumnsAligned(R.layout.widget_observer, 320)
    }

    private fun assertBrokerColumnsAligned(layoutRes: Int, heightDp: Int) {
        val density = ctx.resources.displayMetrics.density
        val width = (300 * density).toInt()
        val height = (heightDp * density).toInt()
        val root = android.view.LayoutInflater.from(ctx).inflate(layoutRes, FrameLayout(ctx), false)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, width, height)

        fun left(view: View): Int {
            var current: View? = view
            var x = 0
            while (current != null) {
                x += current.left
                current = current.parent as? View
            }
            return x
        }

        val pairs = listOf(
            R.id.m1_title to R.id.m2_title,
            R.id.m1_hour to R.id.m2_hour,
            R.id.m1_total_col to R.id.m2_total_col,
            R.id.m1_last to R.id.m2_last
        )
        pairs.forEach { (firstId, secondId) ->
            val first = root.findViewById<View>(firstId)
            val second = root.findViewById<View>(secondId)
            assertEquals("MQTT columns have different left edges: $firstId/$secondId", left(first), left(second))
            assertEquals("MQTT columns have different widths: $firstId/$secondId", first.width, second.width)
        }
    }

    /** Napis MQTTx stoi w jednej linii (ten sam środek w pionie) z liczbą w polu Last packet. */
    @Test fun fullBrokerTitleIsLevelWithLastPacket() {
        val density = ctx.resources.displayMetrics.density
        val width = (300 * density).toInt()
        val height = (320 * density).toInt()
        val root = android.view.LayoutInflater.from(ctx).inflate(R.layout.widget_observer, FrameLayout(ctx), false)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, width, height)
        fun centerY(view: View): Int {
            var current: View? = view
            var y = view.height / 2
            while (current != null && current !== root) {
                y += current.top
                current = current.parent as? View
            }
            return y
        }
        for ((title, last) in listOf(R.id.m1_title to R.id.m1_last, R.id.m2_title to R.id.m2_last)) {
            val t = centerY(root.findViewById(title))
            val l = centerY(root.findViewById(last))
            assertTrue("MQTT title not level with Last packet: $t vs $l", kotlin.math.abs(t - l) <= (1 * density).toInt())
        }
    }

    @Test fun safeLayoutInflates() = assertNotNull(inflate(WidgetRenderer.safe(ctx, "Błąd")))

    @Test fun initialLayoutsInflate() {
        for (res in listOf(R.layout.widget_observer, R.layout.widget_observer_compact, R.layout.widget_observer_safe)) {
            assertNotNull(inflate(RemoteViews(ctx.packageName, res)))
        }
    }

    /** Cykl życia: onUpdate musi od razu (synchronicznie) dać widgetowi widok. */
    @Test fun onUpdateGivesInstantView() {
        val mgr = AppWidgetManager.getInstance(ctx)
        val widgetId = shadowOf(mgr).createWidget(ObserverWidgetProvider::class.java, R.layout.widget_observer_compact)
        ObserverRefresh.renderNow(ctx, intArrayOf(widgetId))
        assertNotNull(shadowOf(mgr).getViewFor(widgetId))
    }
}
