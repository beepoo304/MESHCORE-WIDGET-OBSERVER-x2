package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Statystyki repeatera jak na stronie mapera (rstatCompute) — czysta logika, bez sieci. */
class RepeaterStatsTest {
    private val a = "aaaabbbbccccdddd"
    private val b = "eeeeffffgggghhhh"
    private val day = 24 * 3600_000L
    private val t0 = 1_700_000_000_000L

    private fun ping(id: Long, ts: Long, lat: Double, rpt: String?, user: Long = 1L,
                     snr: Double? = 0.0, usnr: Double? = null, rssi: Double? = null) = MapperPing(
        id = id, tsMs = ts, lat = lat, lon = 19.0, rptId = rpt, snrDb = snr,
        observerSnrDb = usnr, rssiDbm = rssi, userId = user, accM = null, noiseFloorDb = null
    )

    private fun compute(pts: List<MapperPing>, users: Map<Long, String> = emptyMap(), from: Long = 0L, rpt: String = a) =
        RepeaterStats.compute(pts, users, rpt, 50.0, 19.0, from, ZoneOffset.UTC)

    @Test
    fun `brak odpowiedzi repeatera = null`() {
        assertNull(compute(listOf(ping(1, t0, 50.01, b), ping(2, t0, 50.02, null))))
    }

    @Test
    fun `pingi to rozne pary mapujacy+czas, pomiary to wiersze`() {
        val r = compute(listOf(
            ping(1, t0, 50.01, a), ping(2, t0, 50.01, a),          // ten sam ping, dwa pomiary
            ping(3, t0 + 60_000, 50.02, a, user = 2),
            ping(4, t0 + day, 50.03, a, user = 2),
            ping(5, t0 + day + 60_000, 50.04, a, user = 2)
        ), mapOf(2L to "SP9XYZ"))!!
        assertEquals(4, r.pings)
        assertEquals(5, r.measurements)
        assertEquals(2, r.users)
        assertEquals(2, r.days)
        assertEquals("SP9XYZ" to 3, r.topUsers[0])
        assertEquals("#1" to 2, r.topUsers[1])
    }

    @Test
    fun `maks i praktyczny zasieg`() {
        // 8 odpowiedzi z dobrym SNR co ~1,1 km + jedna daleka ze słabym SNR
        val pts = (1..8).map { ping(it.toLong(), t0 + it * 60_000L, 50.0 + it * 0.01, a, snr = 2.0) } +
            ping(9, t0 + 9 * 60_000L, 50.2, a, snr = -10.0)
        val r = compute(pts)!!
        assertEquals(22.24, r.far!!.km, 0.02)
        assertEquals(-10.0, r.far!!.snr!!, 0.0)
        // floor(8 * 0,75) = 6 → siódma odległość ≈ 0,07° szerokości
        assertEquals(7.78, r.practicalKm!!, 0.02)
    }

    @Test
    fun `za malo dobrych odpowiedzi = brak praktycznego zasiegu`() {
        val pts = (1..4).map { ping(it.toLong(), t0 + it * 60_000L, 50.0 + it * 0.01, a, snr = 2.0) }
        assertNull(compute(pts)!!.practicalKm)
    }

    @Test
    fun `okres odcina starsze pomiary`() {
        val r = compute(listOf(ping(1, t0, 50.01, a), ping(2, t0 + 3 * day, 50.02, a)), from = t0 + day)!!
        assertEquals(1, r.measurements)
    }

    @Test
    fun `SNR w obie strony i bilans`() {
        val pts = (1..5).map { ping(it.toLong(), t0 + it * 60_000L, 50.01, a, snr = 4.0 + it, usnr = 1.0, rssi = -90.0) }
        val r = compute(pts)!!
        assertEquals(7.0, r.downAvg!!, 1e-9)
        assertEquals(9.0, r.downBest!!, 1e-9)
        assertEquals(1.0, r.upAvg!!, 1e-9)
        assertEquals(-90.0, r.rssiAvg!!, 1e-9)
        assertTrue(r.hasBalance)
        assertEquals(6.0, r.balanceDiff!!, 1e-9)
    }

    @Test
    fun `pokrycie i kratki tylko on`() {
        val r = compute(listOf(
            ping(1, t0, 50.01, a),
            ping(2, t0, 50.01, b),                 // ten sam ping słyszał też B
            ping(3, t0 + 60_000, 50.05, a)
        ))!!
        assertEquals(2, r.hexes)
        assertEquals(1, r.onlyHexes)
        assertEquals(2 * RepeaterStats.HEX_KM2, r.coverKm2, 1e-9)
    }

    @Test
    fun `milczenie liczy pingi w jego kratkach po ostatniej odpowiedzi`() {
        val r = compute(listOf(
            ping(1, t0, 50.01, a),
            ping(2, t0 + day, 50.01, null),
            ping(3, t0 + 2 * day, 50.01, b),
            ping(4, t0 + 2 * day, 50.5, null, user = 2)   // inna kratka
        ))!!
        assertEquals(2, r.silentPings)
        assertEquals(t0, r.lastMs)
    }

    @Test
    fun `kratki heksagonalne jak na stronie mapera`() {
        assertEquals(RepeaterStats.hexKey(50.0, 19.0), RepeaterStats.hexKey(50.0005, 19.0005))
        assertNotEquals(RepeaterStats.hexKey(50.0, 19.0), RepeaterStats.hexKey(50.01, 19.0))
    }

    @Test
    fun `dociagniecie nowych punktow bez duplikatow`() {
        val old = MapperHistory.Raw(listOf(ping(1, t0, 50.0, a), ping(2, t0, 50.0, b)), mapOf(1L to "x"), 2)
        val new = MapperHistory.Raw(listOf(ping(2, t0, 50.0, b), ping(3, t0, 50.0, a)), mapOf(2L to "y"), 3)
        val m = old + new
        assertEquals(listOf(1L, 2L, 3L), m.points.map { it.id })
        assertEquals(3L, m.lastId)
        assertEquals(setOf(1L, 2L), m.users.keys)
    }
}
