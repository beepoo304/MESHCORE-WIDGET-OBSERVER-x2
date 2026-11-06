package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Teksty powiadomienia na ekranie blokady: mieszczą się w zwiniętym widoku, poprawne przy błędach API. */
class LockScreenTextTest {
    private val now = 1_800_000_000_000L

    private fun snap(name: String?, perHour: Long, ageMs: Long?) = ObserverSnapshot(
        host = "h", id = "ID", name = name, model = null, firmware = null,
        totalPackets = 482_019, packetsPerHour = perHour,
        lastPacketMs = ageMs?.let { now - it }, fetchedAtMs = now
    )

    private fun data(
        b1: FetchResult?, b2: FetchResult?, obs: ObserverSnapshot? = (b1 as? FetchResult.Ok)?.snapshot,
        mapping: MappingState? = null, frozen1: Long? = null
    ) = WidgetData(
        configured = true, observer = obs, broker1 = b1, broker2 = b2, intervalSec = 15,
        orangeMin = 3, redMin = 5, mqtt1Host = "m1", mqtt2Host = "m2", observerId = "F4809C78",
        broker1FrozenAgeMs = frozen1, mapping = mapping
    )

    @Test fun collapsedTextsFitInOneLine() {
        val t = LockScreenText.build(
            data(FetchResult.Ok(snap("K-ce Paderewa 2 obsvr", 1273, 12_000)), FetchResult.Ok(snap("x", 1190, 15_000))),
            now
        )
        assertEquals("K-ce Paderewa 2 obsvr · ● LIVE", t.title)
        assertEquals("M1 1273/h 12s · M2 1190/h 15s", t.summary)
        // Zwinięte powiadomienie mieści ok. 36–45 znaków w linii (Edge 40, Roboto 14 sp).
        assertTrue(t.title.length <= 36)
        assertTrue(t.summary.length <= 36)
        assertEquals("MQTT1 1273/h · ostatni 12s temu\nMQTT2 1190/h · ostatni 15s temu", t.expanded)
    }

    @Test fun longNameIsShortened() {
        val t = LockScreenText.build(
            data(FetchResult.Ok(snap("Bardzo długa nazwa observera na dachu", 1, 1_000)), null), now
        )
        assertTrue(t.title.startsWith("Bardzo długa nazwa ob…"))
        assertTrue(t.title.length <= 36)
    }

    @Test fun apiErrorsAndFrozenAge() {
        assertEquals("— 3m05s", LockScreenText.brokerShort(FetchResult.Fail("h", "HTTP 500"), now, 185_000))
        assertEquals("API ✕", LockScreenText.brokerShort(FetchResult.Fail("h", "HTTP 500"), now, null))
        assertEquals("…", LockScreenText.brokerShort(null, now, null))
        assertEquals("1/h brak", LockScreenText.brokerShort(FetchResult.Ok(snap("n", 1, null)), now, null))
    }

    @Test fun mapperLineOnlyWhenActive() {
        val ping = MapperPing(
            id = 1, tsMs = now, lat = 50.27, lon = 19.04, rptId = "f4809c7817bc7771", snrDb = 5.0,
            observerSnrDb = 3.0, rssiDbm = -90.0, userId = 7, accM = 5.0, noiseFloorDb = -110.0
        )
        val active = MappingState(true, listOf(ping), listOf("Robert"), 50.255352, 19.037188)
        val d = data(FetchResult.Ok(snap("n", 1, 1_000)), null, mapping = active)
        assertEquals("MAPPER N 1,6 km (Robert)", LockScreenText.mapperLine(d, now) { _, i -> active.mapperNicks.getOrNull(i) })
        assertNull(LockScreenText.mapperLine(d.copy(mapping = active.copy(active = false)), now) { _, _ -> null })
    }
}
