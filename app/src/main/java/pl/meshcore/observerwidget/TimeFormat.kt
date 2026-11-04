package pl.meshcore.observerwidget

/**
 * Formaty czasu widgetu.
 *
 * Licznik odświeżania: zawsze dwie cyfry + "s" (np. "15s", "09s", "00s").
 *
 * Wiek ostatniego pakietu:
 *  - poniżej 60 s:   "05s"
 *  - poniżej 1 h:    "01:00s", "59:59s"
 *  - poniżej 24 h:   "01:55:00s", "23:59:59s"
 *  - od 24 h:        "1d16h05m" (bez sekund)
 */
object TimeFormat {
    private fun two(v: Long) = v.toString().padStart(2, '0')

    fun countdown(remainingSeconds: Long, enabled: Boolean = true): String =
        if (!enabled) "00s" else "${two(remainingSeconds.coerceAtLeast(0L))}s"

    fun packetAge(ageMs: Long): String {
        val total = ageMs.coerceAtLeast(0L) / 1_000L
        val days = total / 86_400L
        val hours = (total % 86_400L) / 3_600L
        val minutes = (total % 3_600L) / 60L
        val seconds = total % 60L
        return when {
            total < 60L -> "${two(seconds)}s"
            total < 3_600L -> "${two(minutes)}:${two(seconds)}s"
            total < 86_400L -> "${two(hours)}:${two(minutes)}:${two(seconds)}s"
            else -> "${days}d${two(hours)}h${two(minutes)}m"
        }
    }

    /** Pozostałe pełne sekundy do [targetMs] (zaokrąglone w górę, nigdy ujemne). */
    fun remainingSeconds(targetMs: Long, nowMs: Long): Long =
        ((targetMs - nowMs).coerceAtLeast(0L) + 999L) / 1_000L
}
