package pl.meshcore.observerwidget

enum class Health { GOOD, BAD, UNKNOWN }

data class Assessment(val observer: Health, val mqtt1: Health, val mqtt2: Health)

data class KeyState(
    val bad: Boolean = false,
    val badSinceMs: Long = 0,
    val notified: Boolean = false,
    val lastNotifiedMs: Long = 0
)

enum class AlertAction { NONE, NOTIFY, CLEAR }

/** Czysta logika alarmów: brak zależności od Androida, w pełni testowalna. */
object AlertEngine {

    /**
     * Rozróżnia nieaktywnego Observera od padniętego brokera:
     *  - Observer BAD, gdy NAJŚWIEŻSZY pakiet ze wszystkich źródeł jest starszy niż redMin.
     *  - Broker BAD tylko wtedy, gdy jego pakiet jest stary, a Observer jest aktywny (inne źródło ma świeże dane).
     *  - Brak danych z API = UNKNOWN (niedostępność API to nie awaria Observera ani brokera).
     */
    fun assess(last1: Long?, last2: Long?, observerLast: Long?, nowMs: Long, redMin: Int): Assessment {
        val redMs = redMin * 60_000L
        fun stale(t: Long) = (nowMs - t) > redMs
        val obs = when {
            observerLast == null -> Health.UNKNOWN
            stale(observerLast) -> Health.BAD
            else -> Health.GOOD
        }
        fun broker(last: Long?) = when {
            last == null -> Health.UNKNOWN
            obs == Health.BAD -> Health.UNKNOWN   // winny Observer, nie broker
            obs == Health.UNKNOWN -> Health.UNKNOWN
            stale(last) -> Health.BAD
            else -> Health.GOOD
        }
        return Assessment(obs, broker(last1), broker(last2))
    }

    /**
     * Jeden krok automatu dla jednego rodzaju alarmu.
     * Powiadomienie tylko po realnej zmianie stanu (GOOD→BAD) i upływie opóźnienia,
     * maksymalnie raz na epizod awarii, z cooldownem względem poprzedniego powiadomienia.
     * Powrót do GOOD kasuje powiadomienie (CLEAR). UNKNOWN nie zmienia niczego.
     */
    fun step(
        prev: KeyState, health: Health, enabled: Boolean,
        nowMs: Long, delayMs: Long, cooldownMs: Long
    ): Pair<KeyState, AlertAction> {
        if (!enabled || health == Health.GOOD) {
            val action = if (prev.notified) AlertAction.CLEAR else AlertAction.NONE
            return prev.copy(bad = false, badSinceMs = 0, notified = false) to action
        }
        if (health == Health.UNKNOWN) return prev to AlertAction.NONE

        val since = if (prev.bad) prev.badSinceMs else nowMs
        val s = prev.copy(bad = true, badSinceMs = since)
        val due = !prev.notified &&
            (nowMs - since) >= delayMs &&
            (prev.lastNotifiedMs == 0L || (nowMs - prev.lastNotifiedMs) >= cooldownMs)
        return if (due) s.copy(notified = true, lastNotifiedMs = nowMs) to AlertAction.NOTIFY
        else s to AlertAction.NONE
    }
}
