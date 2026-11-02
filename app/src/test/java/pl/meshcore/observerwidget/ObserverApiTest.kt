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

    /** Odpowiedź skopiowana z żywego serwera (GET /api/observers/{ID}), z dodatkowymi polami, które ignorujemy. */
    @Test fun parsesRealServerResponse() {
        val body = """{"id":"9B250340D918846EFB9140F6243E9253FA0DB9DD3B1B8C0AD8A53337CE5D1234","name":"Ro69ot","iata":"KTW",
            "last_seen":"2026-11-02T10:19:37Z","first_seen":"2026-09-24T11:26:26Z","packet_count":171554,"model":"ProMicro DIY",
            "firmware":"v1.16.0-07a3ca9","battery_mv":4296,"noise_floor":-107,"last_packet_at":"2026-11-02T10:19:28Z",
            "packetsLastHour":143,"lat":null,"lon":null,"nodeRole":null,"clock_skew_seconds":null}"""
        val s = (ObserverApi.parse("live.meshcorekk.xyz", body) as FetchResult.Ok).snapshot
        assertEquals("Ro69ot", s.name)
        assertEquals("ProMicro DIY", s.model)
        assertEquals(171_554L, s.totalPackets)
        assertEquals(143L, s.packetsPerHour)
        assertEquals(ObserverApi.parseTime("2026-11-02T10:19:28Z"), s.lastPacketMs)
    }

    @Test fun errorBodyIsFailure() =
        assertTrue(ObserverApi.parse("h", """{"error":"Observer not found"}""") is FetchResult.Fail)

    /** Serwery rozróżniają wielkość liter: hash musi trafiać do API wielkimi literami. */
    @Test fun idIsNormalizedToUppercase() {
        assertEquals(id.uppercase(), Config.normalizeId("  $id "))
        assertEquals(listOf(id.uppercase(), id.lowercase()), Config.idVariants(id))
    }
}
