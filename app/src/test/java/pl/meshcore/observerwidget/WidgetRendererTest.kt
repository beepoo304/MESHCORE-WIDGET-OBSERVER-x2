package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logika stanu widgetu (napis + kolor), bez klas Androida. */
class WidgetRendererTest {
    private val now = 1_000_000_000_000L

    private fun snap(lastAgoMs: Long?) = ObserverSnapshot(
        host = "h", id = "ID", name = "n", model = null, firmware = null,
        totalPackets = 1, packetsPerHour = 1,
        lastPacketMs = lastAgoMs?.let { now - it }, fetchedAtMs = now
    )

    private fun data(configured: Boolean = true, obs: ObserverSnapshot? = null, busy: Boolean = false) = WidgetData(
        configured = configured, observer = obs, broker1 = null, broker2 = null, intervalSec = 15,
        orangeMin = 3, redMin = 5, mqtt1Host = "a", mqtt2Host = "b", observerId = "ID", busy = busy
    )

    @Test fun notConfigured() =
        assertEquals("SETUP" to WidgetRenderer.ORANGE, WidgetRenderer.liveState(data(configured = false), now))

    @Test fun apiFailureIsRed() =
        assertEquals("API ✕" to WidgetRenderer.RED, WidgetRenderer.liveState(data(), now))

    @Test fun loadingIsNeutralNotError() =
        assertEquals("…" to WidgetRenderer.GRAY, WidgetRenderer.liveState(data(busy = true), now))

    @Test fun freshPacketIsLive() =
        assertEquals("● LIVE" to WidgetRenderer.GREEN, WidgetRenderer.liveState(data(obs = snap(30_000)), now))

    @Test fun staleIsIdleThenNoPacket() {
        assertEquals("● IDLE" to WidgetRenderer.ORANGE, WidgetRenderer.liveState(data(obs = snap(4 * 60_000L)), now))
        assertEquals("● NO PKT" to WidgetRenderer.RED, WidgetRenderer.liveState(data(obs = snap(6 * 60_000L)), now))
    }

    @Test fun noTimestampIsNoData() =
        assertEquals("NO DATA" to WidgetRenderer.GRAY, WidgetRenderer.liveState(data(obs = snap(null)), now))

    @Test fun refreshCountdownUsesSecondsSuffixAndClampsAtZero() {
        assertEquals("09s", WidgetRenderer.formatRefreshCountdown(9L, true))
        assertEquals("00s", WidgetRenderer.formatRefreshCountdown(-4L, true))
        assertEquals("00s", WidgetRenderer.formatRefreshCountdown(30L, false))
    }

    @Test fun lastPacketAgeUsesSecondsMinutesHoursAndDays() {
        assertEquals("00s", WidgetRenderer.formatLastPacketAge(0L))
        assertEquals("59s", WidgetRenderer.formatLastPacketAge(59_999L))
        assertEquals("01:00s", WidgetRenderer.formatLastPacketAge(60_000L))
        assertEquals("59:59s", WidgetRenderer.formatLastPacketAge(3_599_000L))
        assertEquals("01:55:00s", WidgetRenderer.formatLastPacketAge((3600L + 55 * 60) * 1000))
        assertEquals("23:59:59s", WidgetRenderer.formatLastPacketAge(86_399_000L))
        assertEquals("1d16h05m", WidgetRenderer.formatLastPacketAge((86_400L + 16 * 3600 + 5 * 60 + 42) * 1000))
    }

    @Test fun mappingIconPulsesWithTwoSecondPeriod() {
        assertTrue(WidgetRenderer.mappingIconOn(1_000L))     // pierwsza faza: jasna
        assertTrue(WidgetRenderer.mappingIconOn(1_199L))
        assertFalse(WidgetRenderer.mappingIconOn(1_200L))    // druga faza: przyciemniona
        assertFalse(WidgetRenderer.mappingIconOn(1_999L))
        assertTrue(WidgetRenderer.mappingIconOn(2_000L))     // nowy okres
    }

    @Test fun aliveBrokerIsGreenDeadIsRed() {
        assertEquals(WidgetRenderer.GREEN, WidgetRenderer.brokerHealthColor(Health.GOOD, FetchResult.Fail("m", "404")))
        assertEquals(WidgetRenderer.RED, WidgetRenderer.brokerHealthColor(Health.BAD, FetchResult.Ok(snap(1_000L))))
        assertEquals(WidgetRenderer.GRAY, WidgetRenderer.brokerHealthColor(Health.UNKNOWN, null))
    }

    @Test fun packetAgeUsesServerClockOffset() {
        val s = snap(10_000L).copy(serverOffsetMs = 120_000L)
        assertEquals(130_000L, s.lastPacketAgeMs(now))
    }

    private fun mp(id: Long, userId: Long, lat: Double? = 50.26, lon: Double? = 19.04) = MapperPing(
        id = id, tsMs = now + id, lat = lat, lon = lon, rptId = "f4809c7817bc7771",
        snrDb = 10.0, observerSnrDb = 9.5, rssiDbm = -48.0, userId = userId, accM = 6.0, noiseFloorDb = -115.0
    )

    @Test fun arrowPingSingleMapperIsAlwaysHim() {
        val m = MappingState(active = true, pings = listOf(mp(1, 7), mp(2, 7)), mapperNicks = listOf("X"))
        assertEquals(7L, WidgetRenderer.mappingArrowPing(m, 0L)?.userId)
        assertEquals(7L, WidgetRenderer.mappingArrowPing(m, 10_000L)?.userId)
    }

    @Test fun arrowPingCyclesMappersEvery3Seconds() {
        val m = MappingState(active = true, pings = listOf(mp(1, 2), mp(2, 5)), mapperNicks = listOf("A", "B"))
        assertEquals(2L, WidgetRenderer.mappingArrowPing(m, 0L)?.userId)       // slot 0
        assertEquals(2L, WidgetRenderer.mappingArrowPing(m, 2_999L)?.userId)   // wciąż slot 0
        assertEquals(5L, WidgetRenderer.mappingArrowPing(m, 3_000L)?.userId)   // slot 1
        assertEquals(5L, WidgetRenderer.mappingArrowPing(m, 5_999L)?.userId)
        assertEquals(2L, WidgetRenderer.mappingArrowPing(m, 6_000L)?.userId)   // wraca na slot 0
    }

    @Test fun mapperArrowColorsPerMapperIndex() {
        // 1. mapujący – czerwony, 2. – żółty, 3. – fioletowy, kolejni dalej z palety.
        assertEquals(WidgetRenderer.RED, WidgetRenderer.mapperArrowColor(0))
        assertEquals(0xFFFFD60A.toInt(), WidgetRenderer.mapperArrowColor(1))
        assertEquals(0xFFBF5AF2.toInt(), WidgetRenderer.mapperArrowColor(2))
        assertEquals(0xFF64D2FF.toInt(), WidgetRenderer.mapperArrowColor(3))
        assertEquals(0xFF30D158.toInt(), WidgetRenderer.mapperArrowColor(4))
        // Paleta cyklicznie: szósty mapujący znowu czerwony, ujemny indeks bezpieczny.
        assertEquals(WidgetRenderer.RED, WidgetRenderer.mapperArrowColor(5))
        assertEquals(WidgetRenderer.RED, WidgetRenderer.mapperArrowColor(-5))
    }

    @Test fun mappingArrowReturnsPingWithMapperIndexForColor() {
        val m = MappingState(active = true, pings = listOf(mp(1, 2), mp(2, 5)), mapperNicks = listOf("A", "B"))
        val a = WidgetRenderer.mappingArrow(m, 0L)!!
        assertEquals(2L, a.first.userId)
        assertEquals(0, a.second)
        val b = WidgetRenderer.mappingArrow(m, 3_000L)!!
        assertEquals(5L, b.first.userId)
        assertEquals(1, b.second)
    }

    @Test fun arrowPingTakesLatestPingPerMapperAndSkipsNoGps() {
        val m = MappingState(active = true, pings = listOf(mp(1, 2), mp(2, 2), mp(3, 5, lat = null)))
        assertEquals(2L, WidgetRenderer.mappingArrowPing(m, 0L)?.userId) // maper 5 bez GPS odpada
        assertEquals(2L, WidgetRenderer.mappingArrowPing(m, 0L)?.id)     // najnowszy ping mapera 2
        assertEquals(null, WidgetRenderer.mappingArrowPing(MappingState(active = true, pings = listOf(mp(3, 5, lat = null, lon = null))), 0L))
        assertEquals(null, WidgetRenderer.mappingArrowPing(null, 0L))
    }
}
