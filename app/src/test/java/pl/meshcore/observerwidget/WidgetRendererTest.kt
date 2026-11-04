package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
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

    @Test fun aliveBrokerIsGreenDeadIsRed() {
        assertEquals(WidgetRenderer.GREEN, WidgetRenderer.brokerHealthColor(Health.GOOD, FetchResult.Fail("m", "404")))
        assertEquals(WidgetRenderer.RED, WidgetRenderer.brokerHealthColor(Health.BAD, FetchResult.Ok(snap(1_000L))))
        assertEquals(WidgetRenderer.GRAY, WidgetRenderer.brokerHealthColor(Health.UNKNOWN, null))
    }

    @Test fun packetAgeUsesServerClockOffset() {
        val s = snap(10_000L).copy(serverOffsetMs = 120_000L)
        assertEquals(130_000L, s.lastPacketAgeMs(now))
    }
}
