package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test czystej logiki MapperHistory: grupowanie hitów i podział na sesje (bez Androida i sieci). */
class MapperHistoryTest {

    private fun ping(id: Long, ts: Long, lat: Double?, lon: Double?, rpt: String?, user: Long = 1L,
                     snr: Double? = null, rssi: Double? = null) = MapperPing(
        id = id, tsMs = ts, lat = lat, lon = lon, rptId = rpt, snrDb = snr,
        observerSnrDb = null, rssiDbm = rssi, userId = user, accM = null, noiseFloorDb = null
    )

    @Test
    fun `te same wspolrzedne i czas = jeden punkt trasy z dwoma hitami repeaterow`() {
        val pts = listOf(
            ping(1, 1000L, 50.0, 19.0, "aaaabbbbccccdddd", snr = 5.0),
            ping(2, 1000L, 50.0, 19.0, "eeeeffffgggghhhh", snr = -2.0),
            ping(3, 1000L, 50.0, 19.0, "aaaabbbbccccdddd", snr = 6.0) // duplikat RPT: ignorowany
        )
        val sessions = MapperHistory.buildSessions(pts, emptyMap())
        assertEquals(1L, sessions.size.toLong())
        assertEquals(1L, sessions[0].points.size.toLong())
        assertEquals(2L, sessions[0].points[0].hits.size.toLong()) // dwa różne RPT, trzeci wpis pominięty
        assertEquals(5.0, sessions[0].points[0].hits[0].snr!!, 0.0)
        assertEquals(-2.0, sessions[0].points[0].hits[1].snr!!, 0.0)
    }

    @Test
    fun `przerwa wieksza niz 5 min dzieli sesje`() {
        val gap = MapperApi.SESSION_GAP_MS
        val pts = listOf(
            ping(1, 0L, 50.0, 19.0, "aaaabbbbccccdddd"),
            ping(2, 60_000L, 50.01, 19.01, "aaaabbbbccccdddd"),
            ping(3, 60_000L + gap + 1, 50.02, 19.02, "aaaabbbbccccdddd"),
            ping(4, 60_000L + gap + 61_000, 50.03, 19.03, "aaaabbbbccccdddd")
        )
        val sessions = MapperHistory.buildSessions(pts, emptyMap())
        assertEquals(2L, sessions.size.toLong())
        // posortowane od najnowszej
        assertTrue(sessions[0].startMs > sessions[1].startMs)
        assertEquals(2L, sessions[0].points.size.toLong())
        assertEquals(2L, sessions[1].points.size.toLong())
    }

    @Test
    fun `punkty bez GPS sa pomijane, ping bez odpowiedzi zostaje bez hitow`() {
        val pts = listOf(
            ping(1, 0L, null, 19.0, "aaaabbbbccccdddd"), // brak GPS
            ping(2, 1000L, 50.0, 19.0, null)             // ping bez odpowiedzi RPT
        )
        val sessions = MapperHistory.buildSessions(pts, emptyMap())
        assertEquals(1L, sessions.size.toLong())
        assertEquals(1L, sessions[0].points.size.toLong())
        assertEquals(0L, sessions[0].points[0].hits.size.toLong())
    }

    @Test
    fun `sesje dwoch uzytkownikow sa rozdzielone`() {
        val pts = listOf(
            ping(1, 0L, 50.0, 19.0, "aaaabbbbccccdddd", user = 1L),
            ping(2, 1000L, 50.0, 19.0, "aaaabbbbccccdddd", user = 2L)
        )
        val sessions = MapperHistory.buildSessions(pts, mapOf(1L to "Ala", 2L to "Bala"))
        assertEquals(2L, sessions.size.toLong())
        assertTrue(sessions.any { it.nick == "Ala" })
        assertTrue(sessions.any { it.nick == "Bala" })
    }

    @Test
    fun `parsowanie listy repeaterow`() {
        val body = """
            {"ok":true,"repeaters":[
              {"id":"AAAABBBBCCCCDDDD","name":"Rpt Jeden","lat":50.1,"lon":19.1},
              {"id":"eeeeffffgggghhhhiiiijjjjkkkkllll","name":"Rpt Dwa","lat":50.2,"lon":19.2},
              {"id":"za krotkie","name":"Zle","lat":1.0,"lon":1.0},
              {"id":"mmbbbbbbccccdddd","name":"Bez pozycji","lat":null,"lon":null}
            ]}
        """.trimIndent()
        val reps = MapperHistory.parseRepeaters(body)
        assertEquals(3L, reps.size.toLong()) // 'za krotkie' odrzucone; brak pozycji zostaje (bez lat/lon)
        assertEquals("Rpt Jeden", reps["aaaabbbbccccdddd"]?.name)
        // długie ID przycinane do 16 znaków (jak rpt w punktach mapera)
        assertEquals("eeeeffffgggghhhh", reps.keys.first { it.startsWith("eeee") })
        assertEquals(null, reps["mmbbbbbbccccdddd"]?.lat)
    }
}
