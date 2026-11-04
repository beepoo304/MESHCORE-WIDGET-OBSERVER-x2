package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Teksty kart brokerów widgetu: dane poprawne, niekompletne i błędy API. */
class BrokerTextsTest {
    private fun ok(lastPacketMs: Long?) = FetchResult.Ok(
        ObserverSnapshot(
            host = "h", id = "ID", name = "n", model = null, firmware = null,
            totalPackets = 171_554, packetsPerHour = 143, lastPacketMs = lastPacketMs, fetchedAtMs = 0
        )
    )

    @Test fun fullLayoutShowsPlainNumbers() {
        val t = WidgetRenderer.brokerTexts(ok(1_000), compact = false)
        assertEquals("143", t.hour)
        assertEquals("171 554", t.total)
        assertNull(t.last) // czas pokazuje Chronometer
    }

    @Test fun compactLayoutAddsUnits() {
        val t = WidgetRenderer.brokerTexts(ok(1_000), compact = true)
        assertEquals("143/h", t.hour)
        assertEquals("171 554 pkt", t.total)
    }

    @Test fun missingTimestampShowsNoneNotFakeTime() =
        assertEquals("brak", WidgetRenderer.brokerTexts(ok(null), compact = false).last)

    @Test fun apiErrorShowsDashesAndError() {
        val t = WidgetRenderer.brokerTexts(FetchResult.Fail("h", "HTTP 500"), compact = true)
        assertEquals("—", t.hour)
        assertEquals("—", t.total)
        assertEquals("API ✕", t.last)
        assertNull(t.lastLevel)
    }

    @Test fun apiDownFreezesLastKnownAge() {
        val t = WidgetRenderer.brokerTexts(FetchResult.Fail("h", "Timeout"), compact = false, frozenAgeMs = 4 * 60_000L, orangeMin = 3, redMin = 5)
        assertEquals("—", t.hour)
        assertEquals("—", t.total)
        assertEquals("04:00s", t.last)
        assertEquals(Level.ORANGE, t.lastLevel)
    }

    @Test fun apiDownWithoutKnownAgeShowsApiError() {
        val t = WidgetRenderer.brokerTexts(FetchResult.Fail("h", "Timeout"), compact = false, frozenAgeMs = null)
        assertEquals("API ✕", t.last)
        assertNull(t.lastLevel)
    }

    @Test fun apiDownFreezesRedAgeToo() {
        val t = WidgetRenderer.brokerTexts(FetchResult.Fail("h", "Timeout"), compact = false, frozenAgeMs = 6 * 60_000L, orangeMin = 3, redMin = 5)
        assertEquals("06:00s", t.last)
        assertEquals(Level.RED, t.lastLevel)
    }

    @Test fun notFetchedYetShowsLoading() {
        val t = WidgetRenderer.brokerTexts(null, compact = false)
        assertEquals("—", t.hour)
        assertEquals("…", t.last)
    }
}
