package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {
    @Test fun countdownIsTwoDigitsWithSuffix() {
        assertEquals("15s", TimeFormat.countdown(15))
        assertEquals("09s", TimeFormat.countdown(9))
        assertEquals("00s", TimeFormat.countdown(0))
        assertEquals("60s", TimeFormat.countdown(60))
        assertEquals("00s", TimeFormat.countdown(12, enabled = false))
    }

    @Test fun remainingSecondsRoundsUp() {
        assertEquals(15L, TimeFormat.remainingSeconds(15_000, 0))
        assertEquals(15L, TimeFormat.remainingSeconds(15_000, 15))
        assertEquals(14L, TimeFormat.remainingSeconds(15_000, 1_015))
        assertEquals(0L, TimeFormat.remainingSeconds(15_000, 16_000))
    }

    @Test fun ageCountsEverySecond() {
        val seen = (0L until 125L).map { TimeFormat.packetAge(it * 1000) }
        assertEquals(seen.size, seen.toSet().size)
        assertEquals("02:04s", seen.last())
    }
}
