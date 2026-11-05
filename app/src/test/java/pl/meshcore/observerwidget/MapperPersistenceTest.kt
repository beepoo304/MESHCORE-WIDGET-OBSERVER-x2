package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serializacja stanu mapowania (pingi + nicki) na dysk – karta „ostatniego mapowania"
 * w zakładce OBSERVER ma przeżyć restart procesu i widnieć przez 24 h od ostatniego pingu.
 */
class MapperPersistenceTest {

    private fun ping(
        id: Long, tsMs: Long, user: Long = 2,
        lat: Double? = 50.2, lon: Double? = 19.0,
        rptId: String? = "abcdef0123456789",
        snrDb: Double? = -8.5, obsSnr: Double? = -12.0,
        rssi: Double? = -101.0, accM: Double? = 4.2, nf: Double? = -110.0
    ) = MapperPing(
        id = id, tsMs = tsMs, lat = lat, lon = lon, rptId = rptId,
        snrDb = snrDb, observerSnrDb = obsSnr, rssiDbm = rssi,
        userId = user, accM = accM, noiseFloorDb = nf
    )

    @Test fun pingsRoundtripKeepsAllFields() {
        val pings = listOf(
            ping(10, 1_700_000_000_000, user = 42, accM = null, nf = null),
            ping(11, 1_700_000_030_000, user = 7, lat = null, lon = null, rptId = null, snrDb = null)
        )
        val restored = pingsFromJson(pingsToJson(pings))
        assertEquals(pings, restored)
    }

    @Test fun emptyAndNullInputsGiveEmptyList() {
        assertTrue(pingsFromJson(null).isEmpty())
        assertTrue(pingsFromJson("").isEmpty())
        assertTrue(pingsFromJson(pingsToJson(emptyList())).isEmpty())
    }

    @Test fun corruptedRecordsAreSkippedNotFatal() {
        val good = ping(1, 100)
        val json = pingsToJson(listOf(good))
        val damaged = json.replaceFirst("\\{".toRegex(), "{broken")
        // uszkodzony pierwszy rekord wypada, reszta (tu: pusta) nie wybucha
        assertTrue(pingsFromJson(damaged).size <= 1)
        // totalny śmieć zamiast JSON-a = pusta historia
        assertTrue(pingsFromJson("not-json-at-all").isEmpty())
    }

    @Test fun nicksRoundtrip() {
        val nicks = mapOf(42L to "franek", 7L to "heniek")
        assertEquals(nicks, nicksFromJson(nicksToJson(nicks)))
        assertTrue(nicksFromJson(null).isEmpty())
        assertTrue(nicksFromJson("{}").isEmpty())
    }
}
