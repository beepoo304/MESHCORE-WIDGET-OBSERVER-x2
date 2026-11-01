package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kształt JSON z kodu źródłowego CoreScope (TransmissionResp), nie z żywej odpowiedzi. */
class PacketApiTest {
    @Test fun parsesAndSortsNewestFirst() {
        val body = """{"packets":[
          {"hash":"aaaaaaaabbbb","timestamp":"2026-11-01T12:00:00Z","payload_type":4,"decoded_json":"{\"name\":\"Rep1\",\"pubKey\":\"1234567890abcdef\"}","snr":5.5,"rssi":-90},
          {"hash":"ccccccccdddd","timestamp":"2026-11-01T12:00:10Z","payload_type":5,"decoded_json":null,"snr":null,"rssi":null}
        ],"total":2}"""
        val r = PacketApi.parse("h", body) as PacketsResult.Ok
        assertEquals(2, r.packets.size)
        assertEquals("GRP_TXT", r.packets[0].type)
        assertEquals("ADVERT", r.packets[1].type)
        assertEquals("Rep1", r.packets[1].sender)
        assertEquals("—", r.packets[0].sender)
        assertEquals(5.5, r.packets[1].snr!!, 0.0)
    }

    @Test fun limitsTo250() {
        val items = (1..300).joinToString(",") { """{"hash":"h$it","timestamp":"2026-11-01T12:00:00Z","payload_type":2}""" }
        val r = PacketApi.parse("h", """{"packets":[$items],"total":300}""") as PacketsResult.Ok
        assertEquals(250, r.packets.size)
    }

    @Test fun errors() {
        assertTrue(PacketApi.parse("h", "<html>") is PacketsResult.Fail)
        assertTrue(PacketApi.parse("h", "{}") is PacketsResult.Fail)
    }
}
