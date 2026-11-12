package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Rejestr przestojów: otwieranie/zamykanie, próg 1 min, retencja, suma 24 h, JSON. */
class OutageEngineTest {
    private val min = 60_000L

    @Test fun badOpensGoodCloses() {
        val open = OutageEngine.step(OutageKind.MQTT1, null, Health.BAD, 10 * min)
        assertEquals(10 * min, open.openStartMs)
        assertNull(open.closed)
        val still = OutageEngine.step(OutageKind.MQTT1, open.openStartMs, Health.BAD, 20 * min)
        assertEquals(10 * min, still.openStartMs)
        val closed = OutageEngine.step(OutageKind.MQTT1, still.openStartMs, Health.GOOD, 36 * min)
        assertNull(closed.openStartMs)
        assertEquals(Outage(OutageKind.MQTT1, 10 * min, 36 * min), closed.closed)
    }

    @Test fun unknownChangesNothing() {
        assertEquals(OutageEngine.Step(5L * min, null), OutageEngine.step(OutageKind.MQTT2, 5L * min, Health.UNKNOWN, 9 * min))
        assertEquals(OutageEngine.Step(null, null), OutageEngine.step(OutageKind.MQTT2, null, Health.UNKNOWN, 9 * min))
    }

    @Test fun observerStartBackdatedToLastPacket() {
        val r = OutageEngine.step(OutageKind.OBSERVER, null, Health.BAD, 30 * min, startHintMs = 12 * min)
        assertEquals(12 * min, r.openStartMs)
        // Podpowiedź z przyszłości (zegar serwera) jest ignorowana.
        assertEquals(30 * min, OutageEngine.step(OutageKind.OBSERVER, null, Health.BAD, 30 * min, startHintMs = 31 * min).openStartMs)
    }

    @Test fun shorterThanMinuteIsSkipped() {
        assertNull(OutageEngine.step(OutageKind.MQTT1, 10 * min, Health.GOOD, 10 * min + 59_000L).closed)
        assertEquals(Outage(OutageKind.MQTT1, 0L, min, "x"), OutageEngine.close(OutageKind.MQTT1, 0L, min, "x"))
    }

    @Test fun pruneKeepsNewestWithin30Days() {
        val now = 40L * 24 * 60 * min
        val old = Outage(OutageKind.MQTT1, 1 * min, 2 * min)
        val fresh = Outage(OutageKind.MQTT2, now - 10 * min, now - 5 * min)
        val ongoing = Outage(OutageKind.OBSERVER, now - 3 * min)
        assertEquals(listOf(ongoing, fresh), OutageEngine.prune(listOf(old, fresh, ongoing), now))
        val many = (1..400).map { Outage(OutageKind.MQTT1, now - it * min, now - it * min + min) }
        assertEquals(OutageEngine.MAX_ENTRIES, OutageEngine.prune(many, now).size)
    }

    @Test fun totalInWindowClipsToWindow() {
        val now = 48 * 60 * min
        val day = 24 * 60 * min
        val list = listOf(
            Outage(OutageKind.MQTT1, now - day - 30 * min, now - day + 30 * min), // 30 min w oknie
            Outage(OutageKind.MQTT1, now - 10 * min),                              // trwa 10 min
            Outage(OutageKind.MQTT2, now - 60 * min, now - 50 * min)
        )
        assertEquals(40 * min, OutageEngine.totalInWindow(list, OutageKind.MQTT1, now, day))
        assertEquals(10 * min, OutageEngine.totalInWindow(list, OutageKind.MQTT2, now, day))
        assertEquals(0L, OutageEngine.totalInWindow(list, OutageKind.OBSERVER, now, day))
    }

    @Test fun jsonRoundTrip() {
        val list = listOf(
            Outage(OutageKind.OBSERVER, 100L, 200_000L, "monitoring zatrzymany"),
            Outage(OutageKind.MQTT2, 5L)
        )
        assertEquals(list, OutageEngine.fromJson(OutageEngine.toJson(list)))
        assertEquals(emptyList<Outage>(), OutageEngine.fromJson("zepsute"))
    }
}
