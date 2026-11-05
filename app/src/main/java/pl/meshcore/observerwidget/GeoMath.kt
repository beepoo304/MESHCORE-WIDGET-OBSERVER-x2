package pl.meshcore.observerwidget

import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Geografia: odległość i azymut do mapera (do radaru w aplikacji). Czyste funkcje, testowalne. */
object GeoMath {
    private const val EARTH_R_KM = 6371.0088

    /** Odległość po wielkim kole (km). */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_R_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Azymut początkowy (0° = północ, rośnie zgodnie z ruchem wskazówek zegara). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private val COMPASS = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    )

    /** Kierunek 16-sektorowy (N, NNE, NE…). */
    fun compassLabel(bearingDeg: Double): String =
        COMPASS[Math.round(bearingDeg / 22.5).toInt() % 16]

    /** Odległość do czytelnego napisu (przecinek polski): „850 m”, „1,2 km”, „12 km”. */
    fun km(distanceKm: Double): String = when {
        distanceKm < 1.0 -> "${(distanceKm * 1000).roundToInt()} m"
        distanceKm < 10.0 -> String.format(Locale.US, "%.1f km", distanceKm).replace('.', ',')
        else -> String.format(Locale.US, "%.0f km", distanceKm)
    }

    /** Jak [km], ale bez spacji – pod bitmaę strzałki w widgecie: „850m”, „1,2km”. */
    fun kmShort(distanceKm: Double): String = km(distanceKm).replace(" ", "")

    private fun Double.roundToInt(): Int = Math.round(this).toInt()
}
