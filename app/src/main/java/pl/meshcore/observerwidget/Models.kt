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
    val fetchedAtMs: Long
)

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
