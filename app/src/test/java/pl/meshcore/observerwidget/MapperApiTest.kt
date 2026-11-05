package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parser odpowiedzi API mapera (format z meshcoresouth.pl/mapper). */
class MapperApiTest {

    private val body = """
        {"ok":true,"rev":2,"last":97,"more":false,
         "points":[
           [93,1789928572,49.819749,18.990096,null,null,null,null,2,6.4,-115,0],
           [94,1789928582,49.819749,18.990096,"f4809c7817bc7771",10.25,10,-48,5,6.1,-115,0],
           [95,1789928582,49.819749,18.990096,"d9009cf67f5a3f62",9.75,9.75,-85,2,6.1,-115,0]
         ],
         "users":{"1":"Pawlo","2":"Pawlo_Bielsko 🚒","5":"Piotr M5"},
         "car":[]}
    """.trimIndent()

    @Test fun parsesPointsUsersAndCursor() {
        val r = MapperApi.parse(body)!!
        assertEquals(97L, r.lastId)
        assertEquals(2, r.rev)
        assertFalse(r.more)
        assertEquals(3, r.points.size)
        val ours = r.points[1]
        assertEquals(94L, ours.id)
        assertEquals(1789928582L * 1000L, ours.tsMs)
        assertEquals("f4809c7817bc7771", ours.rptId)
        assertEquals(10.25, ours.snrDb!!, 1e-9)
        assertEquals(10.0, ours.observerSnrDb!!, 1e-9)
        assertEquals(-48.0, ours.rssiDbm!!, 1e-9)
        assertEquals(5L, ours.userId)
        assertEquals(6.1, ours.accM!!, 1e-9)
        assertEquals(-115.0, ours.noiseFloorDb!!, 1e-9)
        // Brak odpowiedzi repeatera: rpt null, metryki null.
        val miss = r.points[0]
        assertNull(miss.rptId); assertNull(miss.snrDb); assertNull(miss.rssiDbm)
        assertEquals("Piotr M5", r.users[5L])
        assertEquals("Pawlo_Bielsko 🚒", r.users[2L])
    }

    @Test fun rejectsBadResponses() {
        assertNull(MapperApi.parse("nie json"))
        assertNull(MapperApi.parse("""{"ok":false,"error":"x"}"""))
        assertNull(MapperApi.parse("""[]"""))
    }

    @Test fun missingPointsKeyYieldsEmptyList() {
        val r = MapperApi.parse("""{"ok":true,"last":5,"rev":1,"more":false,"users":{}}""")!!
        assertTrue(r.points.isEmpty())
        assertEquals(5L, r.lastId)
    }

    @Test fun shortIdIsFirst16CharsLowercase() {
        assertEquals(
            "f4809c7817bc7771",
            MapperApi.shortId("F4809C7817BC777124F638D32008AF197415BA1240C36C186F6EC80619775C68")
        )
        assertEquals("", MapperApi.shortId("krótki"))
    }

    @Test fun repeaterPositionFindsObserver() {
        val body = """
            {"ok":true,"repeaters":[
              {"id":"9bbc40925031246c","name":"SPS-PSZCZYNA-BAZA","lat":50.00188,"lon":18.95046,"city":"Pszczyna"},
              {"id":"f4809c7817bc7771","name":"K-ce Paderewa 2 obsvr","lat":50.255352,"lon":19.037188,"city":"Katowice"}
            ]}
        """.trimIndent()
        val pos = MapperApi.parseRepeaterPosition(body, "f4809c7817bc7771")
        assertEquals(50.255352, pos?.lat ?: Double.NaN, 1e-9)
        assertEquals(19.037188, pos?.lon ?: Double.NaN, 1e-9)
        assertEquals(true, pos?.found)
    }

    @Test fun repeaterPositionNotFoundIsNotError() {
        val body = """{"ok":true,"repeaters":[{"id":"9bbc40925031246c","lat":50.00188,"lon":18.95046}]}"""
        assertEquals(false, MapperApi.parseRepeaterPosition(body, "f4809c7817bc7771")?.found)
    }

    @Test fun repeaterPositionBadBodyIsNull() {
        assertNull(MapperApi.parseRepeaterPosition("nie-json", "f4809c7817bc7771"))
        assertNull(MapperApi.parseRepeaterPosition("""{"ok":false}""", "f4809c7817bc7771"))
    }
}
