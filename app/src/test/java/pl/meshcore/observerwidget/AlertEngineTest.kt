package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertEngineTest {
    private val min = 60_000L
    private val now = 10_000 * min

    @Test fun observerDeadDoesNotBlameBrokers() {
        val a = AlertEngine.assess(now - 8 * min, now, 5, Health.GOOD, Health.GOOD)
        assertEquals(Health.BAD, a.observer)
        assertEquals(Health.GOOD, a.mqtt1)
        assertEquals(Health.GOOD, a.mqtt2)
    }

    @Test fun brokerDeadWhileObserverAlive() {
        val a = AlertEngine.assess(now - 20_000, now, 5, Health.BAD, Health.GOOD)
        assertEquals(Health.GOOD, a.observer)
        assertEquals(Health.BAD, a.mqtt1)
        assertEquals(Health.GOOD, a.mqtt2)
    }

    @Test fun apiDownIsUnknownNotFailure() {
        val a = AlertEngine.assess(null, now, 5, Health.UNKNOWN, Health.UNKNOWN)
        assertEquals(Health.UNKNOWN, a.observer)
        assertEquals(Health.UNKNOWN, a.mqtt1)
    }

    @Test fun mqttHealthIsIndependentFromObserverSilence() {
        val result = AlertEngine.assess(now - 10 * min, now, 5, Health.GOOD, Health.BAD)
        assertEquals(Health.BAD, result.observer)
        assertEquals(Health.GOOD, result.mqtt1)
        assertEquals(Health.BAD, result.mqtt2)
    }

    @Test fun observerAlertRequiresBothMqttServicesHealthy() {
        val mqtt1Down = AlertEngine.assess(now - 10 * min, now, 5, Health.BAD, Health.GOOD)
        val mqtt2Down = AlertEngine.assess(now - 10 * min, now, 5, Health.GOOD, Health.BAD)
        val bothUnknown = AlertEngine.assess(now - 10 * min, now, 5, Health.UNKNOWN, Health.UNKNOWN)
        assertEquals(Health.BAD, mqtt1Down.observer)
        assertEquals(Health.BAD, mqtt2Down.observer)
        assertEquals(Health.BAD, bothUnknown.observer)
    }

    @Test fun notifiesOnlyAfterDelayAndOnlyOnce() {
        var s = KeyState()
        var r = AlertEngine.step(s, Health.BAD, true, now, 2 * min, 15 * min); s = r.first
        assertEquals(AlertAction.NONE, r.second)
        r = AlertEngine.step(s, Health.BAD, true, now + 2 * min, 2 * min, 15 * min); s = r.first
        assertEquals(AlertAction.NOTIFY, r.second)
        r = AlertEngine.step(s, Health.BAD, true, now + 30 * min, 2 * min, 15 * min); s = r.first
        assertEquals(AlertAction.NONE, r.second)
    }

    @Test fun recoveryClearsNotification() {
        var s = KeyState(bad = true, badSinceMs = now, notified = true, lastNotifiedMs = now)
        val r = AlertEngine.step(s, Health.GOOD, true, now + 5 * min, 2 * min, 15 * min)
        assertEquals(AlertAction.CLEAR, r.second)
        assertEquals(false, r.first.bad)
    }

    @Test fun cooldownBlocksFlapping() {
        val s = KeyState(bad = false, notified = false, lastNotifiedMs = now)
        var r = AlertEngine.step(s, Health.BAD, true, now + 3 * min, 0, 15 * min)
        assertEquals(AlertAction.NONE, r.second)
        r = AlertEngine.step(r.first, Health.BAD, true, now + 16 * min, 0, 15 * min)
        assertEquals(AlertAction.NOTIFY, r.second)
    }

    @Test fun unknownKeepsState() {
        val s = KeyState(bad = true, badSinceMs = now, notified = true, lastNotifiedMs = now)
        val r = AlertEngine.step(s, Health.UNKNOWN, true, now + min, 0, 15 * min)
        assertEquals(s, r.first)
        assertEquals(AlertAction.NONE, r.second)
    }

    @Test fun disabledNeverNotifies() {
        val r = AlertEngine.step(KeyState(), Health.BAD, false, now, 0, min)
        assertEquals(AlertAction.NONE, r.second)
    }
}
