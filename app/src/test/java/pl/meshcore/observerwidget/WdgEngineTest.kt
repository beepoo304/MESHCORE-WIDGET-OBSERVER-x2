package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Test

class WdgEngineTest {
    private val interval = 10 * 60_000L

    @Test fun initialSuccessSetsOnlineAndSchedulesInterval() {
        val r = WdgEngine.onCheck(WdgEngine.State(), true, 1_000L, interval)
        assertEquals(WdgEngine.Status.ONLINE, r.state.status)
        assertEquals(1_000L + interval, r.state.nextCheckAtMs)
        assertEquals(WdgEngine.Action.NONE, r.action)
    }

    @Test fun failureRequiresSecondFailureAfter15Seconds() {
        val first = WdgEngine.onCheck(WdgEngine.State(), false, 1_000L, interval)
        assertEquals(WdgEngine.Status.REFRESH, first.state.status)
        assertEquals(16_000L, first.state.nextCheckAtMs)
        val second = WdgEngine.onCheck(first.state, false, 16_000L, interval)
        assertEquals(WdgEngine.Status.ERROR, second.state.status)
        assertEquals(WdgEngine.Action.ERROR, second.action)
        assertEquals(316_000L, second.state.nextCheckAtMs)
    }

    @Test fun transientFailureReturnsOnlineWithoutPush() {
        val online = WdgEngine.State(status = WdgEngine.Status.ONLINE, onlineSinceMs = 500L)
        val first = WdgEngine.onCheck(online, false, 1_000L, interval)
        val second = WdgEngine.onCheck(first.state, true, 16_000L, interval)
        assertEquals(WdgEngine.Status.ONLINE, second.state.status)
        assertEquals(WdgEngine.Action.NONE, second.action)
        assertEquals(500L, second.state.onlineSinceMs)
    }

    @Test fun restoreRequiresTwoSuccessesAndResetsUptime() {
        val error = WdgEngine.State(status = WdgEngine.Status.ERROR, outageStartedAtMs = 10L)
        val first = WdgEngine.onCheck(error, true, 1_000L, interval)
        assertEquals(WdgEngine.Status.REFRESH, first.state.status)
        val second = WdgEngine.onCheck(first.state, true, 16_000L, interval)
        assertEquals(WdgEngine.Status.ONLINE, second.state.status)
        assertEquals(WdgEngine.Action.RESTORE, second.action)
        assertEquals(16_000L, second.state.onlineSinceMs)
    }

    @Test fun failedRestoreConfirmationReturnsToErrorAndRetriesInFiveMinutes() {
        val error = WdgEngine.State(status = WdgEngine.Status.ERROR)
        val first = WdgEngine.onCheck(error, true, 1_000L, interval)
        val second = WdgEngine.onCheck(first.state, false, 16_000L, interval)
        assertEquals(WdgEngine.Status.ERROR, second.state.status)
        assertEquals(WdgEngine.Action.NONE, second.action)
        assertEquals(316_000L, second.state.nextCheckAtMs)
    }
}
