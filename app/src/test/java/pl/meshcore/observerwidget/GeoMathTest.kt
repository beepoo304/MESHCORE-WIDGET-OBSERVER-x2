package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Geografia: odległość i azymut do mapera (strzałka w widgecie, kierunek w aplikacji). */
class GeoMathTest {
    // K-ce Paderewa 2 obsvr (Katowice) i SPS-PSZCZYNA-BAZA – realne pozycje z bazy mapera.
    private val obsLat = 50.255352
    private val obsLon = 19.037188

    @Test fun distanceKatowiceToPszczyna() {
        assertEquals(28.9, GeoMath.distanceKm(obsLat, obsLon, 50.00188, 18.95046), 0.1)
    }

    @Test fun bearingCardinalDirections() {
        assertEquals(0.0, GeoMath.bearingDeg(obsLat, obsLon, obsLat + 1, obsLon), 0.5)
        assertEquals(90.0, GeoMath.bearingDeg(obsLat, obsLon, obsLat, obsLon + 1), 0.5)
        assertEquals(180.0, GeoMath.bearingDeg(obsLat, obsLon, obsLat - 1, obsLon), 0.5)
        assertEquals(270.0, GeoMath.bearingDeg(obsLat, obsLon, obsLat, obsLon - 1), 0.5)
    }

    @Test fun compassLabelSectors() {
        assertEquals("N", GeoMath.compassLabel(0.0))
        assertEquals("NE", GeoMath.compassLabel(45.0))
        assertEquals("E", GeoMath.compassLabel(90.0))
        assertEquals("S", GeoMath.compassLabel(180.0))
        assertEquals("W", GeoMath.compassLabel(270.0))
        assertEquals("S", GeoMath.compassLabel(192.0)) // Katowice→Pszczyna
    }

    @Test fun kmFormatsPolishComma() {
        assertEquals("850 m", GeoMath.km(0.85))
        assertEquals("1,2 km", GeoMath.km(1.23))
        assertEquals("12 km", GeoMath.km(12.3))
        assertEquals("29 km", GeoMath.km(28.9))
    }

    @Test fun kmShortNoSpaceForWidget() {
        assertEquals("850m", GeoMath.kmShort(0.85))
        assertEquals("1,6km", GeoMath.kmShort(1.6))
        assertEquals("12km", GeoMath.kmShort(12.0))
    }

    @Test fun samePointIsZero() {
        assertEquals(0.0, GeoMath.distanceKm(obsLat, obsLon, obsLat, obsLon), 1e-9)
        assertTrue(GeoMath.bearingDeg(obsLat, obsLon, obsLat, obsLon) in 0.0..360.0)
    }
}
