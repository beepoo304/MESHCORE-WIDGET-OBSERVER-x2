package pl.meshcore.observerwidget

import java.util.Locale

object Status {
    /** Zielony: poniżej progu pomarańczowego; pomarańczowy: >= orangeMin; czerwony: > redMin. */
    fun level(ageMs: Long?, orangeMin: Int, redMin: Int): Level {
        if (ageMs == null) return Level.UNKNOWN
        val age = ageMs.coerceAtLeast(0)
        return when {
            age > redMin * 60_000L -> Level.RED
            age >= orangeMin * 60_000L -> Level.ORANGE
            else -> Level.GREEN
        }
    }

    fun formatAge(ageMs: Long): String {
        val s = (ageMs.coerceAtLeast(0)) / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> String.format(Locale.US, "%dm%02ds", s / 60, s % 60)
            s < 86_400 -> String.format(Locale.US, "%dh%02dm", s / 3600, (s % 3600) / 60)
            else -> "${s / 86_400}d"
        }
    }

    fun formatCount(n: Long): String =
        String.format(Locale.US, "%,d", n).replace(',', ' ')
}
