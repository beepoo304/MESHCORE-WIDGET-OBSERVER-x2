package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Moje repeatery RPT1–RPT5: belka widgetu, zapis na dysk, walidacja klucza. */
class RptMappingTest {
    private fun mp(id: Long, userId: Long, rpt: String = "a1b2c3d4e5f60718") = MapperPing(
        id = id, tsMs = id * 1000, lat = 50.26, lon = 19.04, rptId = rpt, snrDb = 1.0, observerSnrDb = 2.0,
        rssiDbm = -90.0, userId = userId, accM = 5.0, noiseFloorDb = -110.0
    )

    private fun data(obs: MappingState?, rpts: List<RptMapping>) = WidgetData(
        configured = true, observer = null, broker1 = null, broker2 = null, intervalSec = 15,
        orangeMin = 3, redMin = 5, mqtt1Host = "a", mqtt2Host = "b", observerId = "ID",
        mapping = obs, rptMappings = rpts
    )

    @Test fun onlyObserverBehavesAsBefore() {
        val m = MappingState(active = true, pings = listOf(mp(1, 2), mp(2, 5)))
        val d = data(m, emptyList())
        for (t in listOf(0L, 3_000L, 6_000L, 9_000L)) {
            val hm = WidgetRenderer.headerMapping(d, t)!!
            val old = WidgetRenderer.mappingArrow(m, t)!!
            assertEquals("MAPPER", hm.label)
            assertEquals(old.first, hm.ping)
            assertEquals(old.second, hm.mapperIndex)
        }
    }

    @Test fun rotatesBetweenObserverAndRpt() {
        val obs = MappingState(active = true, pings = listOf(mp(1, 2)))
        val rpt = RptMapping(2, "a1b2c3d4e5f60718", "Dach", MappingState(active = true, pings = listOf(mp(3, 7)), observerLat = 50.0, observerLon = 19.0))
        val d = data(obs, listOf(rpt))
        assertEquals("MAPPER", WidgetRenderer.headerMapping(d, 0L)!!.label)
        val second = WidgetRenderer.headerMapping(d, 3_000L)!!
        assertEquals("RPT2", second.label)
        assertTrue(second.rpt)
        assertEquals(50.0, second.state.observerLat!!, 1e-9)
        assertEquals("MAPPER", WidgetRenderer.headerMapping(d, 6_000L)!!.label)
    }

    @Test fun inactiveSourcesHidden() {
        val rpt = RptMapping(1, "a1b2c3d4e5f60718", null, MappingState(active = false, pings = listOf(mp(3, 7))))
        assertNull(WidgetRenderer.headerMapping(data(MappingState(active = false), listOf(rpt)), 0L))
        val onlyRpt = rpt.copy(state = rpt.state.copy(active = true))
        assertEquals("RPT1", WidgetRenderer.headerMapping(data(null, listOf(onlyRpt)), 0L)!!.label)
    }

    @Test fun rptPingsRoundtrip() {
        val map = mapOf("a1b2c3d4e5f60718" to listOf(mp(1, 2), mp(2, 3)))
        assertEquals(map, rptPingsFromJson(rptPingsToJson(map)))
        assertTrue(rptPingsFromJson("zepsute").isEmpty())
    }

    @Test fun rptInfoRoundtrip() {
        val map = mapOf(
            "a1b2c3d4e5f60718" to MapperMonitor.RptInfo(50.1, 19.2, true, "Dach RPT", 123L),
            "0011223344556677" to MapperMonitor.RptInfo(null, null, false, null, 456L)
        )
        assertEquals(map, rptInfoFromJson(rptInfoToJson(map)))
    }

    @Test fun rptKeyValidation() {
        assertTrue(Config.isValidRptId("A1B2C3D4E5F60718"))
        assertTrue(Config.isValidRptId("F4809C7817BC777124F638D32008AF197415BA1240C36C186F6EC80619775C68"))
        assertEquals(false, Config.isValidRptId("A1B2C3"))
        assertEquals(false, Config.isValidRptId("ZZZZC3D4E5F60718"))
    }

    @Test fun repeaterNameParsed() {
        val body = """{"ok":true,"repeaters":[{"id":"7d21f848ced7219d","name":"Kato AWF","lat":50.24,"lon":19.0}]}"""
        assertEquals("Kato AWF", MapperApi.parseRepeaterPosition(body, "7d21f848ced7219d")?.name)
    }
}
