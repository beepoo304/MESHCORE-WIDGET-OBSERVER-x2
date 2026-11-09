package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapperStatsTest {
    private val obs = MapperStats.Node("aaaaaaaaaaaaaaaa", "OBSERVER", "Obs", 50.0, 20.0)
    private val rpt = MapperStats.Node("bbbbbbbbbbbbbbbb", "RPT1", "R1", 50.1, 20.0)

    private fun p(id: Long, ts: Long, lat: Double, lon: Double, rpt: String?, user: Long, snr: Double? = null) =
        MapperPing(id, ts, lat, lon, rpt, null, snr, null, user, null, null)

    @Test fun recordPicksFarthestPingOfNode() {
        val pts = listOf(
            p(1, 1000, 50.01, 20.0, obs.shortId, 1),
            p(2, 2000, 50.10, 20.0, obs.shortId, 2),
            p(3, 3000, 50.50, 20.0, rpt.shortId, 3)
        )
        val r = MapperStats.record(pts, obs)
        assertNotNull(r)
        assertEquals(2L, r!!.ping.id)
        assertEquals(11.1, r.km, 0.2)
        assertEquals(0.0, r.bearingDeg, 1.0)
        assertNull(MapperStats.record(pts, obs.copy(lat = null)))
    }

    @Test fun nodeStatsCountsAndAveragesSnr() {
        val pts = listOf(
            p(1, 1000, 50.01, 20.0, obs.shortId, 1, snr = 4.0),
            p(2, 2000, 50.02, 20.0, obs.shortId, 1, snr = 8.0),
            p(3, 3000, 50.02, 20.0, rpt.shortId, 1, snr = 1.0)
        )
        val s = MapperStats.nodeStats(pts, obs)
        assertEquals(2, s.pings)
        assertEquals(6.0, s.avgSnr!!, 0.001)
    }

    @Test fun rankingCountsDistinctPingsPerUser() {
        val mine = setOf(obs.shortId, rpt.shortId)
        val pts = listOf(
            p(1, 1000, 50.0, 20.0, obs.shortId, 1),
            p(2, 1000, 50.0, 20.0, rpt.shortId, 1),
            p(3, 2000, 50.0, 20.0, obs.shortId, 1),
            p(4, 3000, 50.0, 20.0, obs.shortId, 2),
            p(5, 4000, 50.0, 20.0, "cccccccccccccccc", 3),
            p(6, 10, 50.0, 20.0, obs.shortId, 4)
        )
        val r = MapperStats.ranking(pts, mine, sinceMs = 500)
        assertEquals(listOf(1L, 2L), r.map { it.userId })
        assertEquals(2, r[0].pings)
        assertEquals(mapOf(obs.shortId to 2, rpt.shortId to 1), r[0].byNode)
        assertEquals(mapOf(obs.shortId to 1), r[1].byNode)
    }

    @Test fun nearbyUnheardFindsSessionsWithoutMyReception() {
        val mine = setOf(obs.shortId)
        val other = "cccccccccccccccc"
        val pts = listOf(
            // user 1: blisko, słyszany tylko przez obcy repeater
            p(1, 1_000_000, 50.03, 20.0, other, 1),
            p(2, 1_060_000, 50.03, 20.01, other, 1),
            // user 2: blisko, słyszany przez Observera
            p(3, 1_000_000, 50.02, 20.0, obs.shortId, 2),
            // user 3: daleko
            p(4, 1_000_000, 51.0, 20.0, other, 3)
        )
        val s = MapperStats.nearbyUnheard(pts, 50.0, 20.0, 10.0, mine, 0)
        assertEquals(1, s.size)
        assertEquals(1L, s[0].userId)
        assertEquals(2, s[0].pings)
        assertEquals(setOf(other), s[0].heardBy)
        assertEquals(mapOf(other to 2), s[0].heardByCounts)
        assertTrue(MapperStats.nearbyUnheard(pts, 50.0, 20.0, 10.0, mine, 0, finishedBeforeMs = 1_000_000).isEmpty())
    }

    @Test fun splitSessionsByGap() {
        val gap = MapperApi.SESSION_GAP_MS
        val pts = listOf(p(1, 0, 0.0, 0.0, null, 1), p(2, gap, 0.0, 0.0, null, 1), p(3, 2 * gap + 1, 0.0, 0.0, null, 1))
        assertEquals(listOf(2, 1), MapperStats.splitSessions(pts).map { it.size })
    }

    @Test fun polishPlurals() {
        assertEquals("1 ping", MapperStats.pingsLabel(1))
        assertEquals("3 pingi", MapperStats.pingsLabel(3))
        assertEquals("12 pingów", MapperStats.pingsLabel(12))
        assertEquals("22 pingi", MapperStats.pingsLabel(22))
        assertEquals("5 pingów", MapperStats.pingsLabel(5))
    }

    @Test fun dailyTextSummary() {
        val pts = listOf(
            p(1, 1000, 50.05, 20.0, obs.shortId, 1),
            p(2, 1000, 50.05, 20.0, rpt.shortId, 1),
            p(3, 2000, 50.2, 20.0, rpt.shortId, 2)
        )
        val d = MapperStats.daily(pts, listOf(obs, rpt), 50.0 to 20.0, 10.0, 0, 10_000)
        assertEquals(2, d.mappers)
        assertEquals(2, d.pings)
        assertEquals("RPT1", d.maxNode!!.label)
        assertTrue(MapperStats.dailyText(d).startsWith("Wczoraj przy Twoich węzłach: 2 mapujących · 2 pingi · max"))
    }
}
