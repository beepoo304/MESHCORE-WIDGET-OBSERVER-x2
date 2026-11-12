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

/**
 * Ping mapowania odebrany przez Observera: punkt z bazy publicznego mapera
 * MeshCore South, w którym repeaterem odpowiadającym jest nasz Observer
 * (ID = pierwsze 16 znaków klucza publicznego).
 */
data class MapperPing(
    val id: Long,
    /** Czas pomiaru wg zegara serwera mapera (epoch ms). */
    val tsMs: Long,
    val lat: Double?,
    val lon: Double?,
    /** ID repeatera odpowiadającego na ping (pierwsze 16 znaków klucza, małe litery). */
    val rptId: String?,
    /** SNR odbioru: jak companion mapera słyszy naszego Observera (dB). */
    val snrDb: Double?,
    /** SNR u Observera: jak nasz Observer słyszy companiona mapera (dB). */
    val observerSnrDb: Double?,
    val rssiDbm: Double?,
    val userId: Long,
    val accM: Double?,
    val noiseFloorDb: Double?
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
    val fallbackHealth: Health = Health.UNKNOWN,
    /**
     * Ostatnia znana wartość wieku pakietu danego brokera (ms), zamrożona w chwili,
     * gdy jego API przestało odpowiadać – licznik nie ucieka w nieskończoność.
     */
    val broker1FrozenAgeMs: Long? = null,
    val broker2FrozenAgeMs: Long? = null,
    /**
     * Ostatni udany odczyt danego brokera (do powiadomienia na ekranie blokady, gdy broker padnie);
     * null, gdy od startu procesu nie było udanego odczytu.
     */
    val broker1LastOk: ObserverSnapshot? = null,
    val broker2LastOk: ObserverSnapshot? = null,
    /** Stan mapowania w zasięgu Observera; null, gdy brak danych z mapera. */
    val mapping: MappingState? = null,
    /** Nowe pingi mapowania zauważone w tym cyklu (do powiadomienia). */
    val mappingNew: List<MapperPing> = emptyList(),
    /** Mapowanie przy „moich repeaterach” RPT1–RPT5 (tylko sloty z wpisanym kluczem). */
    val rptMappings: List<RptMapping> = emptyList(),
    /** Nowe pingi przy RPT w tym cyklu, wg numeru slotu (do powiadomień). */
    val rptNew: Map<Int, List<MapperPing>> = emptyMap()
)

/**
 * Mapowanie przy jednym z moich repeaterów (RPT1–RPT5). [state] liczony tak samo jak dla
 * Observera; jego observerLat/observerLon to pozycja tego repeatera z bazy mapera.
 */
data class RptMapping(
    /** Numer slotu 1–5 (etykieta „RPT1”…). */
    val slot: Int,
    /** Pierwsze 16 znaków klucza, małe litery (tak jak rpt w punktach mapera). */
    val shortId: String,
    /** Nazwa z bazy repeaterów mapera; null = jeszcze nie sprawdzono albo brak w bazie. */
    val name: String?,
    val state: MappingState
) {
    val label: String get() = "RPT$slot"
}

enum class Level { GREEN, ORANGE, RED, UNKNOWN }

/** Stan mapowania w zasięgu Observera, wyliczony z pingów mapera. */
data class MappingState(
    /** Ktoś mapuje teraz (ostatni ping nie dawniej niż przerwa sesji). */
    val active: Boolean,
    /** Pomiary bieżącej sesji (do panelu w aplikacji). */
    val pings: List<MapperPing> = emptyList(),
    /** Nicki mapujących w bieżącej sesji. */
    val mapperNicks: List<String> = emptyList(),
    /** Pozycja Observera z bazy repeaterów mapera (do strzałki kierunku i odległości). */
    val observerLat: Double? = null,
    val observerLon: Double? = null,
    /** Cała ostatnia sesja, także po jej wygaśnięciu (do licznika pingów w aplikacji, jak na mapie). */
    val session: List<MapperPing> = emptyList()
)
