package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutTest {
    @Test fun unknownSizeUsesDefaultCompact() {
        assertEquals(WidgetLayout.DEFAULT, WidgetLayout.choose(0, 0))
        assertTrue(WidgetLayout.DEFAULT.compact)
        assertTrue(WidgetLayout.DEFAULT.showTotal)
    }

    @Test fun defaultFourByTwoIsCompactWithTotal() {
        val l = WidgetLayout.choose(250, 110)
        assertTrue(l.compact)
        assertTrue(l.showTotal)
    }

    @Test fun tallWidgetUsesFullLayout() {
        val l = WidgetLayout.choose(280, 280)
        assertFalse(l.compact)
        assertTrue(l.showTotal)
    }

    @Test fun heightThresholdIsExact() {
        assertTrue(WidgetLayout.choose(300, WidgetLayout.FULL_MIN_HEIGHT_DP - 1).compact)
        assertFalse(WidgetLayout.choose(300, WidgetLayout.FULL_MIN_HEIGHT_DP).compact)
    }

    @Test fun narrowWidgetHidesTotalColumn() {
        assertFalse(WidgetLayout.choose(WidgetLayout.TOTAL_MIN_WIDTH_DP - 1, 120).showTotal)
        assertTrue(WidgetLayout.choose(WidgetLayout.TOTAL_MIN_WIDTH_DP, 120).showTotal)
        assertFalse(WidgetLayout.choose(180, 300).showTotal)
    }

    @Test fun minimumResizeSizeStillGetsAUsableLayout() {
        // minResizeWidth/Height z observer_widget_info.xml
        val l = WidgetLayout.choose(180, 110)
        assertTrue(l.compact)
        assertFalse(l.showTotal)
    }
}
