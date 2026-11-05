package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Czysta logika sesji mapowania (bez Androida i sieci). */
class MapperMonitorTest {
    private val t0 = 1_789_928_000_000L // dowolny czas bazowy
    private val gap = MapperApi.SESSION_GAP_MS

    private fun ping(id: Long, tsMs: Long, user: Long = 2) = MapperPing(
        id = id, tsMs = tsMs, lat = 50.0, lon = 19.0, rptId = "f4809c7817bc7771",
        snrDb = 10.0, observerSnrDb = 9.5, rssiDbm = -48.0, userId = user, accM = 6.0, noiseFloorDb = -115.0
    )

    @Test fun emptyMeansInactive() {
        val s = MapperMonitor.stateOf(emptyList(), t0)
        assertFalse(s.active)
        assertTrue(s.pings.isEmpty())
    }

    @Test fun freshPingIsActiveSession() {
        val now = t0 + 60_000L
        val s = MapperMonitor.stateOf(listOf(ping(1, t0), ping(2, t0 + 20_000)), now)
        assertTrue(s.active)
        assertEquals(2, s.pings.size)
        assertTrue(s.mapperNicks.isEmpty()) // bez podanego resolvera nicków
    }

    @Test fun gapEndsSessionButKeepsLastPing() {
        // Ostatni ping 10 min temu: sesja wygasła, ale panel ma co pokazać.
        val now = t0 + 10 * 60_000L
        val s = MapperMonitor.stateOf(listOf(ping(1, t0), ping(2, t0 + 20_000)), now)
        assertFalse(s.active)
        assertEquals(1, s.pings.size)
        assertEquals(t0 + 20_000, s.pings[0].tsMs)
    }

    @Test fun sessionOnlyIncludesPingsConnectedByShortGaps() {
        // Dwie sesje: t0..t0+40s oraz t0+30min. Bieżąca = tylko druga.
        val second = t0 + 30 * 60_000L
        val now = second + 60_000L
        val s = MapperMonitor.stateOf(
            listOf(ping(1, t0), ping(2, t0 + 20_000), ping(3, t0 + 40_000), ping(4, second), ping(5, second + 20_000)),
            now
        )
        assertTrue(s.active)
        assertEquals(listOf(4L, 5L), s.pings.map { it.id })
    }

    @Test fun multipleMappersInOneSession() {
        val now = t0 + 60_000L
        val s = MapperMonitor.stateOf(
            listOf(ping(1, t0, user = 2), ping(2, t0 + 20_000, user = 5)), now,
            nickOf = { id -> mapOf(2L to "Pawlo", 5L to "Piotr M5")[id] }
        )
        assertTrue(s.active)
        assertEquals(listOf("Pawlo", "Piotr M5"), s.mapperNicks)
    }

    @Test fun observerPositionPassedToState() {
        val now = t0 + 60_000L
        val s = MapperMonitor.stateOf(listOf(ping(1, t0)), now, observerLat = 50.255352, observerLon = 19.037188)
        assertEquals(50.255352, s.observerLat ?: Double.NaN, 1e-9)
        assertEquals(19.037188, s.observerLon ?: Double.NaN, 1e-9)
    }
}
