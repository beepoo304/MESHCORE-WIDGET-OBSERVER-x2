package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTest {
    private val min = 60_000L

    @Test fun greenBelowThreshold() = assertEquals(Level.GREEN, Status.level(2 * min + 59_000, 3, 5))
    @Test fun orangeFromThreeMinutes() = assertEquals(Level.ORANGE, Status.level(3 * min, 3, 5))
    @Test fun orangeAtExactlyFive() = assertEquals(Level.ORANGE, Status.level(5 * min, 3, 5))
    @Test fun redAboveFive() = assertEquals(Level.RED, Status.level(5 * min + 1, 3, 5))
    @Test fun unknownWithoutTimestamp() = assertEquals(Level.UNKNOWN, Status.level(null, 3, 5))
    @Test fun customThresholds() = assertEquals(Level.RED, Status.level(11 * min, 10, 10))

    @Test fun formatAge() {
        assertEquals("43 s", Status.formatAge(43_000))
        assertEquals("1m01s", Status.formatAge(61_000))
        assertEquals("1h05m", Status.formatAge(65 * min))
        assertEquals("2d", Status.formatAge(49 * 3600_000L))
    }

    @Test fun formatCount() = assertEquals("2 867 455", Status.formatCount(2_867_455))
}
