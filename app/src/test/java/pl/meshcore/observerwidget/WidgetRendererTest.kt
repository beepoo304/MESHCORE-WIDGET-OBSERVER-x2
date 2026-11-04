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

    @Test fun lastPacketAgeSwitchesFromSecondsToMinutes() {
        assertEquals("59s", WidgetRenderer.formatLastPacketAge(59_999L))
        assertEquals("01m", WidgetRenderer.formatLastPacketAge(60_000L))
        assertEquals("03m", WidgetRenderer.formatLastPacketAge(3 * 60_000L))
    }

    @Test fun unknownBrokerStatusDoesNotGrayTheBrokerTitle() {
        assertEquals(
            WidgetRenderer.WHITE,
            WidgetRenderer.brokerHealthColor(Health.UNKNOWN, FetchResult.Fail("mqtt2", "404"))
        )
    }

    @Test fun confirmedBrokerFailureRemainsRed() {
        assertEquals(
            WidgetRenderer.RED,
            WidgetRenderer.brokerHealthColor(Health.BAD, FetchResult.Ok(snap(1_000L)))
        )
    }
}
