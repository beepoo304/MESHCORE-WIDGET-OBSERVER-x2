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

    /** Observer packet age and MQTT service availability are separate signals. */
    fun assess(observerLast: Long?, nowMs: Long, redMin: Int, mqtt1: Health, mqtt2: Health): Assessment {
        val observer = when {
            mqtt1 != Health.GOOD || mqtt2 != Health.GOOD -> Health.UNKNOWN
            observerLast == null -> Health.UNKNOWN
            nowMs - observerLast > redMin * 60_000L -> Health.BAD
            else -> Health.GOOD
        }
        return Assessment(observer, mqtt1, mqtt2)
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
