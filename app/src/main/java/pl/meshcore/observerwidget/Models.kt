package pl.meshcore.observerwidget

data class ObserverSnapshot(
    val host: String,
    val id: String,
    val name: String?,
    val model: String?,
    val firmware: String?,
    val totalPackets: Long,
    val packetsPerHour: Long,
    /** Czas ostatniego pakietu (epoch ms) albo null, gdy serwer nie ma takiej informacji. */
    val lastPacketMs: Long?,
    val fetchedAtMs: Long,
    /**
     * Różnica zegara serwera względem telefonu (ms), z nagłówka HTTP Date.
     * Dzięki temu wiek pakietu jest poprawny nawet przy źle ustawionym zegarze telefonu.
     */
    val serverOffsetMs: Long = 0L
) {
    /** Wiek ostatniego pakietu liczony w czasie serwera; null, gdy brak znacznika. */
    fun lastPacketAgeMs(nowMs: Long): Long? =
        lastPacketMs?.let { (nowMs + serverOffsetMs - it).coerceAtLeast(0L) }
}

sealed class FetchResult {
    data class Ok(val snapshot: ObserverSnapshot) : FetchResult()
    data class Fail(val host: String, val reason: String) : FetchResult()
}

data class WidgetData(
    val configured: Boolean,
    val observer: ObserverSnapshot?,
    val broker1: FetchResult?,
    val broker2: FetchResult?,
    val intervalSec: Int,
    val orangeMin: Int,
    val redMin: Int,
    val mqtt1Host: String,
    val mqtt2Host: String,
    val observerId: String,
    val busy: Boolean = false,
    val mqtt1Health: Health = Health.UNKNOWN,
    val mqtt2Health: Health = Health.UNKNOWN,
    val fallbackHealth: Health = Health.UNKNOWN
)

enum class Level { GREEN, ORANGE, RED, UNKNOWN }
