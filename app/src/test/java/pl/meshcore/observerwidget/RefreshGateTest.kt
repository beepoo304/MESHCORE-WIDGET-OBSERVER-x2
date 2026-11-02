package pl.meshcore.observerwidget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshGateTest {
    @Test fun firstRequestStarts() {
        assertTrue(RefreshGate().request())
    }

    @Test fun requestDuringRefreshIsRememberedNotLost() {
        val g = RefreshGate()
        assertTrue(g.request())
        assertFalse(g.request())      // nakłada się na trwający cykl
        assertTrue(g.finish())        // trzeba wykonać kolejny cykl
        assertFalse(g.finish())       // kolejnych żądań nie było
    }

    @Test fun manyOverlappingRequestsCollapseToOneRerun() {
        val g = RefreshGate()
        assertTrue(g.request())
        repeat(5) { assertFalse(g.request()) }
        assertTrue(g.finish())
        assertFalse(g.finish())
    }

    @Test fun gateIsReleasedAfterFinish() {
        val g = RefreshGate()
        assertTrue(g.request())
        assertFalse(g.finish())
        assertTrue(g.request())
    }
}
