package pl.meshcore.observerwidget

import android.content.Context

/** Wszystkie ustawienia aplikacji. Brak jakichkolwiek wartości Observera wpisanych na stałe. */
class Config(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("config", Context.MODE_PRIVATE)

    /**
     * Pełny hash Observera (64 znaki hex), zapisywany WIELKIMI literami: tak zwracają go serwery CoreScope,
     * a wyszukiwanie po ID rozróżnia wielkość liter (małe litery dają 404 / pustą listę pakietów).
     */
    var monitoringEnabled: Boolean
        get() = prefs.getBoolean("monitoring_enabled", true)
        set(value) = prefs.edit().putBoolean("monitoring_enabled", value).apply()

    var monitoringStoppedAtMs: Long
        get() = prefs.getLong("monitoring_stopped_at_ms", 0L)
        set(value) = prefs.edit().putLong("monitoring_stopped_at_ms", value.coerceAtLeast(0L)).apply()

    var observerId: String
        get() = normalizeId(prefs.getString("observer_id", "").orEmpty())
        set(v) = prefs.edit().putString("observer_id", normalizeId(v)).apply()

    var mqtt1Host: String
        get() = prefs.getString("mqtt1_host", DEFAULT_MQTT1).orEmpty().ifBlank { DEFAULT_MQTT1 }
        set(v) = prefs.edit().putString("mqtt1_host", v.trim()).apply()

    var mqtt2Host: String
        get() = prefs.getString("mqtt2_host", DEFAULT_MQTT2).orEmpty().ifBlank { DEFAULT_MQTT2 }
        set(v) = prefs.edit().putString("mqtt2_host", v.trim()).apply()

    /** API zapasowe, używane do nagłówka (nazwa, model), gdy oba serwery MQTT są niedostępne. */
    var fallbackHost: String
        get() = prefs.getString("fallback_host", DEFAULT_FALLBACK).orEmpty()
        set(v) = prefs.edit().putString("fallback_host", v.trim()).apply()

    var timeoutSec: Int
        get() = prefs.getInt("timeout_sec", 6).coerceIn(2, 30)
        set(v) = prefs.edit().putInt("timeout_sec", v.coerceIn(2, 30)).apply()

    var intervalSec: Int
        get() = prefs.getInt("interval_sec", 15).coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        set(v) = prefs.edit().putInt("interval_sec", v.coerceIn(MIN_INTERVAL, MAX_INTERVAL)).apply()

    /** Po tylu minutach bez pakietu czas robi się pomarańczowy. */
    var orangeMin: Int
        get() = prefs.getInt("orange_min", 3).coerceAtLeast(1)
        set(v) = prefs.edit().putInt("orange_min", v.coerceAtLeast(1)).apply()

    /** Po tylu minutach bez pakietu czas robi się czerwony. */
    var redMin: Int
        get() = prefs.getInt("red_min", 5).coerceAtLeast(1)
        set(v) = prefs.edit().putInt("red_min", v.coerceAtLeast(1)).apply()

    var alertObserver: Boolean
        get() = prefs.getBoolean("alert_observer", false)
        set(v) = prefs.edit().putBoolean("alert_observer", v).apply()

    var alertMqtt1: Boolean
        get() = prefs.getBoolean("alert_mqtt1", false)
        set(v) = prefs.edit().putBoolean("alert_mqtt1", v).apply()

    var alertMqtt2: Boolean
        get() = prefs.getBoolean("alert_mqtt2", false)
        set(v) = prefs.edit().putBoolean("alert_mqtt2", v).apply()

    /** Stan awarii musi trwać tyle minut, zanim wyślemy powiadomienie. */
    var alertDelayMin: Int
        get() = prefs.getInt("alert_delay_min", 2).coerceAtLeast(0)
        set(v) = prefs.edit().putInt("alert_delay_min", v.coerceAtLeast(0)).apply()

    /** Minimalny odstęp między kolejnymi powiadomieniami tego samego rodzaju. */
    var alertCooldownMin: Int
        get() = prefs.getInt("alert_cooldown_min", 15).coerceAtLeast(1)
        set(v) = prefs.edit().putInt("alert_cooldown_min", v.coerceAtLeast(1)).apply()

    /** Powiadomienie, gdy ktoś mapuje w zasięgu Observera (pingi mapera MeshCore South). */
    var alertMapping: Boolean
        get() = prefs.getBoolean("alert_mapping", true)
        set(v) = prefs.edit().putBoolean("alert_mapping", v).apply()

    /** Dane Observera (status, pakiety/h, wiek pakietu, MAPPER) w stałym powiadomieniu – widoczne na ekranie blokady. */
    var lockScreenStatus: Boolean
        get() = prefs.getBoolean("lock_screen_status", true)
        set(v) = prefs.edit().putBoolean("lock_screen_status", v).apply()

    /** Promień „okolicy” Observera (km, 1–50) – do „Mapował obok · nie słyszałeś”. */
    var nearbyRadiusKm: Int
        get() = prefs.getInt("nearby_radius_km", 10).coerceIn(1, MAX_RADIUS_KM)
        set(v) = prefs.edit().putInt("nearby_radius_km", v.coerceIn(1, MAX_RADIUS_KM)).apply()

    /** Dzienny raport o 8:00: wczorajsi mapujący przy moich węzłach. */
    var alertDaily: Boolean
        get() = prefs.getBoolean("alert_daily", true)
        set(v) = prefs.edit().putBoolean("alert_daily", v).apply()

    /** Push, gdy ktoś mapował w okolicy, a żaden z moich węzłów go nie usłyszał. */
    var alertNearby: Boolean
        get() = prefs.getBoolean("alert_nearby", true)
        set(v) = prefs.edit().putBoolean("alert_nearby", v).apply()

    /** Punkt startowy odpytań bazy mapera (najnowszy widziany ID punktu). */
    var mapperLastId: Long
        get() = prefs.getLong("mapper_last_id", 0L)
        set(v) = prefs.edit().putLong("mapper_last_id", v.coerceAtLeast(0L)).apply()

    /** Rewizja bazy mapera – zmiana oznacza usunięcie danych i reset punktu startowego. */
    var mapperRev: Int
        get() = prefs.getInt("mapper_rev", 0)
        set(v) = prefs.edit().putInt("mapper_rev", v).apply()

    /** Pozycja Observera z bazy repeaterów mapera (do strzałki kierunku); NaN = brak. */
    var mapperObsLat: Float
        get() = prefs.getFloat("mapper_obs_lat", Float.NaN)
        set(v) = prefs.edit().putFloat("mapper_obs_lat", v).apply()

    var mapperObsLon: Float
        get() = prefs.getFloat("mapper_obs_lon", Float.NaN)
        set(v) = prefs.edit().putFloat("mapper_obs_lon", v).apply()

    /** Czy pozycja Observera jest w bazie mapera (false = sprawdzono, nie ma; nie pytamy ponownie przez dobę). */
    var mapperObsPosOk: Boolean
        get() = prefs.getBoolean("mapper_obs_pos_ok", false)
        set(v) = prefs.edit().putBoolean("mapper_obs_pos_ok", v).apply()

    /** Kiedy ostatnio pobrano/sprawdzono pozycję Observera (epoch ms). */
    var mapperObsPosAtMs: Long
        get() = prefs.getLong("mapper_obs_pos_at", 0L)
        set(v) = prefs.edit().putLong("mapper_obs_pos_at", v).apply()

    /**
     * Klucze „moich repeaterów” RPT1–RPT5 (sloty stałe: usunięcie RPT1 nie przenumerowuje RPT2).
     * Pusty tekst = slot wolny. Zapis WIELKIMI literami jak klucz Observera.
     */
    fun rptId(slot: Int): String =
        if (slot !in 1..RPT_SLOTS) "" else normalizeId(prefs.getString("rpt_id_$slot", "").orEmpty())

    fun setRptId(slot: Int, id: String) {
        if (slot !in 1..RPT_SLOTS) return
        prefs.edit().putString("rpt_id_$slot", normalizeId(id)).apply()
    }

    /** Zajęte sloty: numer slotu → klucz. */
    val rptIds: Map<Int, String>
        get() = (1..RPT_SLOTS).associateWith { rptId(it) }.filterValues { it.isNotBlank() }

    val anyAlertEnabled: Boolean get() = alertObserver || alertMqtt1 || alertMqtt2 || alertMapping || alertDaily || alertNearby

    companion object {
        const val DEFAULT_MQTT1 = "live.meshcorekk.xyz"
        const val DEFAULT_MQTT2 = "analyzer.marwoj.net"
        const val DEFAULT_FALLBACK = "mc.inside.net.pl"
        const val MIN_INTERVAL = 2
        const val MAX_INTERVAL = 60

        const val RPT_SLOTS = 5
        const val MAX_RADIUS_KM = 50

        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
        private val HEX_RPT = Regex("^[0-9a-fA-F]{16,64}$")

        /** Klucz repeatera: pełny (64 znaki) albo co najmniej 16 pierwszych znaków – tyle używa maper. */
        fun isValidRptId(id: String) = HEX_RPT.matches(id.trim())
        fun isValidId(id: String) = HEX64.matches(id.trim())

        fun normalizeId(id: String) = id.trim().uppercase()

        /** Warianty ID do wypróbowania: najpierw wielkie litery (format serwerów), potem małe. */
        fun idVariants(id: String): List<String> = listOf(normalizeId(id), id.trim().lowercase()).distinct()

        fun baseUrl(host: String): String {
            val h = host.trim().trimEnd('/')
            return if (h.startsWith("http://") || h.startsWith("https://")) h else "https://$h"
        }

        fun displayHost(host: String): String =
            host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    }
}
