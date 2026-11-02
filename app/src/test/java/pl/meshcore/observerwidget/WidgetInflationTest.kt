package pl.meshcore.observerwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.FrameLayout
import android.widget.RemoteViews
import org.junit.Assert.assertNotNull
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
        data(true, FetchResult.Fail("h", "Timeout"), FetchResult.Fail("h", "Brak połączenia"))
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
