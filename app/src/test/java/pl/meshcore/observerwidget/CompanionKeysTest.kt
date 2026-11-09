package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionKeysTest {
    private val k1 = "a".repeat(64)
    private val k2 = "b".repeat(64)

    @Test fun parseKeepsNewestByName() {
        val body = """{"nodes":[
            {"name":" Jan ","public_key":"$k1","last_heard":"2026-11-01T10:00:00Z"},
            {"name":"jan","public_key":"$k2","last_heard":"2026-11-09T10:00:00Z"},
            {"name":"zly","public_key":"xyz","last_heard":"2026-11-09T10:00:00Z"},
            {"name":null,"public_key":"$k1"}],"total":4}"""
        val m = CompanionKeys.parse(body)
        assertEquals(setOf("jan"), m.keys)
        assertEquals(k2.uppercase(), m["jan"]!!.first)
        assertEquals(emptyMap<String, Pair<String, String>>(), CompanionKeys.parse("nie json"))
    }

    @Test fun jsonRoundTrip() {
        val m = mapOf("jan" to k1.uppercase())
        assertEquals(m, CompanionKeys.fromJson(CompanionKeys.toJson(m)))
    }
}
