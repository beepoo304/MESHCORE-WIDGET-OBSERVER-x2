package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Klucze mapujących z list companionów: dopasowanie ŚCISŁE po nazwie, bez zgadywania. */
class CompanionKeysTest {
    private val k1 = "a".repeat(64)
    private val k2 = "b".repeat(64)

    @Test fun exactNameOnly() {
        val m = CompanionKeys.parse("""{"nodes":[
            {"name":"Pawlo_Bielsko 🚒","public_key":"$k1"},
            {"name":"zly","public_key":"xyz"},
            {"name":null,"public_key":"$k2"}]}""")
        assertEquals(k1.uppercase(), m["Pawlo_Bielsko 🚒"])
        assertNull(m["pawlo_bielsko 🚒"])
        assertNull(m["Pawlo_Bielsko"])
        assertFalse(m.containsKey("zly"))
        assertFalse(m.containsKey("null"))
    }

    @Test fun sameNameDifferentKeysIsAmbiguous() {
        val m = CompanionKeys.parse("""{"nodes":[
            {"name":"Jan","public_key":"$k1"},
            {"name":"Jan","public_key":"$k2"},
            {"name":"Ola","public_key":"$k1"},
            {"name":"Ola","public_key":"$k1"}]}""")
        assertEquals("", m["Jan"])
        assertEquals(k1.uppercase(), m["Ola"])
    }

    @Test fun mergeAcrossSources() {
        val a = mapOf("Jan" to "K1", "Ola" to "K2")
        val b = mapOf("Jan" to "K1", "Ola" to "K3", "Ewa" to "K4")
        val m = CompanionKeys.merge(listOf(a, b))
        assertEquals("K1", m["Jan"])
        assertEquals("", m["Ola"])
        assertEquals("K4", m["Ewa"])
    }

    @Test fun jsonRoundTrip() {
        val m = mapOf("Jan" to "K1", "Ola" to "")
        assertEquals(m, CompanionKeys.fromJson(CompanionKeys.toJson(m)))
    }
}
