package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kształt JSON-a wzięty z kodu źródłowego CoreScope (ObserverResp), nie z żywej odpowiedzi serwera. */
class ObserverApiTest {
    private val id = "f4809c7817bc777124f638d32008af197415ba1240c36c186f6ec80619775c68"

    @Test fun parsesFullObserver() {
        val body = """{"id":"$id","name":"Test obs","model":"Heltec V3","firmware":"v1.17.0",
            "packet_count":2867455,"packetsLastHour":624,"last_packet_at":"2026-11-01T12:00:00Z","last_seen":"2026-11-01T12:00:05Z"}"""
        val r = ObserverApi.parse("example.test", body, nowMs = 0)
        assertTrue(r is FetchResult.Ok)
        val s = (r as FetchResult.Ok).snapshot
        assertEquals("Test obs", s.name)
        assertEquals(2_867_455L, s.totalPackets)
        assertEquals(624L, s.packetsPerHour)
        assertEquals(1_793_534_400_000L, s.lastPacketMs)
    }

    @Test fun nullFieldsAreNotFakeData() {
        val r = ObserverApi.parse("h", """{"id":"$id","name":null,"last_packet_at":null,"packet_count":0,"packetsLastHour":0}""")
        val s = (r as FetchResult.Ok).snapshot
        assertNull(s.name)
        assertNull(s.lastPacketMs)
    }

    @Test fun invalidJson() = assertTrue(ObserverApi.parse("h", "<html>") is FetchResult.Fail)
    @Test fun arrayInsteadOfObject() = assertTrue(ObserverApi.parse("h", "[]") is FetchResult.Fail)
    @Test fun missingId() = assertTrue(ObserverApi.parse("h", "{}") is FetchResult.Fail)

    @Test fun timeFormats() {
        assertEquals(ObserverApi.parseTime("2026-11-01T12:00:00Z"), ObserverApi.parseTime("2026-11-01 12:00:00"))
        assertEquals(ObserverApi.parseTime("2026-11-01T12:00:00Z"), ObserverApi.parseTime("2026-11-01T13:00:00+01:00"))
        assertNull(ObserverApi.parseTime("nie-data"))
    }

    @Test fun hashValidation() {
        assertTrue(Config.isValidId(id))
        assertTrue(!Config.isValidId(id.take(63)))
        assertTrue(!Config.isValidId(id.replace('f', 'z')))
    }
}
