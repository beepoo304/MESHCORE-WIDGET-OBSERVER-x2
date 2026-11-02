package pl.meshcore.observerwidget

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zapobiega nakładaniu się odświeżeń bez gubienia żądań: gdy odświeżenie trwa, kolejne żądanie
 * zostaje zapamiętane i wykonane zaraz po zakończeniu bieżącego cyklu.
 */
class RefreshGate {
    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)

    /** true = wywołujący ma uruchomić cykl; false = cykl już trwa, a żądanie zostało zapamiętane. */
    fun request(): Boolean {
        if (running.compareAndSet(false, true)) return true
        again.set(true)
        // Cykl mógł się właśnie zakończyć: wtedy przejmujemy pracę sami.
        return if (running.compareAndSet(false, true)) {
            again.set(false)
            true
        } else {
            false
        }
    }

    /** Wywołać po cyklu. true = przyszło nowe żądanie, trzeba wykonać kolejny cykl (bramka nadal zajęta). */
    fun finish(): Boolean {
        if (again.getAndSet(false)) return true
        running.set(false)
        if (again.get() && running.compareAndSet(false, true)) {
            again.set(false)
            return true
        }
        return false
    }
}
