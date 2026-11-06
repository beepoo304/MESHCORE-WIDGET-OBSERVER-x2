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
        assertEquals("#F480 · ● LIVE", t.title)
        assertEquals("M1 1273/h 12s · M2 1190/h 15s", t.summary)
        // Zwinięte powiadomienie mieści ok. 36–45 znaków w linii (Edge 40, Roboto 14 sp).
        assertTrue(t.summary.length <= 36)
        assertEquals("M1 1273/h · 12s · 482019\nM2 1190/h · 15s · 482019", t.expanded)
    }

    @Test fun staleBrokerShowsDashPerHourAndError() {
        // API żyje, ale ostatni pakiet 6 min temu (czerwony próg 5 min).
        val stale = FetchResult.Ok(snap("n", 738, 370_000))
        val d = data(stale, FetchResult.Ok(snap("n", 738, 17_000)))
        val t = LockScreenText.build(d, now)
        assertEquals("#F480 · ● ERROR", t.title)
        assertEquals("M1 -/h · 6m10s · 482019\nM2 738/h · 17s · 482019", t.expanded)
    }

    @Test fun orangeThresholdIsAlreadyError() {
        val d = data(FetchResult.Ok(snap("n", 738, 200_000)), FetchResult.Ok(snap("n", 738, 17_000)))
        val t = LockScreenText.build(d, now)
        assertEquals("#F480 · ● ERROR", t.title)
        assertEquals("M1 738/h · 3m20s · 482019", t.expanded.lines()[0])
    }

    @Test fun deadBrokerShowsLastKnownValuesWithGrowingAge() {
        val fail = FetchResult.Fail("h", "HTTP 500")
        // Ostatni udany odczyt: pakiet 17 s przed chwilą „now”; teraz minęło kolejne 60 s.
        val lastOk = snap("n", 738, 17_000).copy(totalPackets = 120_222)
        val d = data(FetchResult.Ok(snap("n", 738, 17_000)), fail).copy(broker2LastOk = lastOk)
        assertEquals("D_M2 738/h · 1m17s · 120222", LockScreenText.brokerLine("M2", fail, lastOk, d, now + 60_000))
        assertEquals("#F480 · ● ERROR", LockScreenText.build(d, now).title)
        assertEquals("D_M2 -/h · - · -", LockScreenText.brokerLine("M2", fail, null, d, now))
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
