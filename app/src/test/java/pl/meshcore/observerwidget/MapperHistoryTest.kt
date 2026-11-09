package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapperHistoryTest {
    @Test fun pointLineRoundTrip() {
        val p = MapperPing(7, 1_700_000_000_000, 50.1, 19.9, "abcdabcdabcdabcd", 5.5, -2.0, null, 42, null, null)
        assertEquals(p, MapperHistory.parseLine(MapperHistory.pointLine(p)))
        val empty = p.copy(lat = null, lon = null, rptId = null, snrDb = null, observerSnrDb = null)
        assertEquals(empty, MapperHistory.parseLine(MapperHistory.pointLine(empty)))
        assertNull(MapperHistory.parseLine("zepsute"))
    }

    @Test fun parseRepeatersAndCenter() {
        val body = """{"ok":true,"repeaters":[
            {"id":"AAAAAAAAAAAAAAAA1111","name":"Obs","lat":null,"lon":null},
            {"id":"bbbbbbbbbbbbbbbb","name":"R1","lat":50.5,"lon":20.5}]}"""
        val m = MapperHistory.parseRepeaters(body)
        assertEquals(2, m.size)
        assertEquals("R1", m["bbbbbbbbbbbbbbbb"]!!.name)
        assertEquals(50.5 to 20.5, MapperHistory.centerOf(listOf("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb"), m))
        assertEquals(m, MapperHistory.repeatersFromJson(MapperHistory.repeatersToJson(m)))
    }
}
