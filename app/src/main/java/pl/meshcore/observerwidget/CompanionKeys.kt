package pl.meshcore.observerwidget

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Klucze publiczne mapujących po nicku: baza mapera ma tylko nick, a klucz companiona bierzemy
 * z list węzłów CoreScope (live.meshcorekk.xyz i mc.inside.net.pl). Dopasowanie ŚCISŁE: nazwa
 * węzła musi być identyczna z nickiem (wielkość liter i emoji też). Nazwa z dwoma różnymi
 * kluczami = niejednoznaczna → brak klucza (nie zgadujemy). Brak w listach → „brak klucza”.
 */
object CompanionKeys {
    val HOSTS = listOf("live.meshcorekk.xyz", "mc.inside.net.pl")
    private const val FILE = "companion_keys.json"
    private const val PREFS = "companion_keys"
    private const val TTL_MS = 6 * 60 * 60_000L
    private const val RETRY_MS = 10 * 60_000L
    private const val TIMEOUT_MS = 20_000

    /** Wartość dla nazwy niejednoznacznej (ta sama nazwa, różne klucze). */
    private const val AMBIGUOUS = ""

    @Volatile private var keys: Map<String, String>? = null
    @Volatile private var lastAttemptMs = 0L
    @Volatile private var running = false

    /** Klucz (64 hex, wielkie litery) dla nicku mapującego albo null. */
    fun keyFor(ctx: Context, nick: String?): String? {
        if (nick.isNullOrBlank()) return null
        return load(ctx)[nick.trim()]?.takeIf { it != AMBIGUOUS }
    }

    private fun load(ctx: Context): Map<String, String> {
        keys?.let { return it }
        val f = File(ctx.applicationContext.filesDir, FILE)
        val m = try { if (f.exists()) fromJson(f.readText()) else emptyMap() } catch (_: Exception) { emptyMap() }
        keys = m
        return m
    }

    /** Odświeżenie listy co [TTL_MS] (po błędzie ponowna próba po [RETRY_MS]); wołać poza wątkiem UI. */
    fun refreshIfStale(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (running || now - lastAttemptMs < RETRY_MS) return false
        if (now - prefs.getLong("at", 0L) < TTL_MS && load(app).isNotEmpty()) return false
        running = true
        lastAttemptMs = now
        try {
            val lists = HOSTS.mapNotNull { h -> get("https://$h/api/nodes?role=companion&limit=5000")?.let { parse(it) } }
                .filter { it.isNotEmpty() }
            if (lists.isEmpty()) return false
            val out = merge(lists)
            File(app.filesDir, FILE).writeText(toJson(out))
            prefs.edit().putLong("at", now).apply()
            keys = out
            return true
        } finally {
            running = false
        }
    }

    /** nodes[]: nazwa (dokładna, bez spacji na brzegach) → klucz; nazwa z różnymi kluczami → [AMBIGUOUS]. */
    fun parse(body: String): Map<String, String> {
        val root = try { JSONObject(body) } catch (_: Exception) { return emptyMap() }
        val arr = root.optJSONArray("nodes") ?: return emptyMap()
        val pairs = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.isNull("name")) continue
            val name = o.optString("name", "").trim()
            val key = o.optString("public_key", "").trim()
            if (name.isEmpty() || !Config.isValidId(key)) continue
            pairs.add(name to Config.normalizeId(key))
        }
        return merge(pairs.map { mapOf(it) })
    }

    /** Scalenie list z kilku źródeł: ten sam klucz = zgodnie; różne klucze = [AMBIGUOUS]. */
    fun merge(lists: List<Map<String, String>>): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in lists) for ((name, key) in m) {
            val cur = out[name]
            out[name] = when {
                cur == null -> key
                cur == key -> cur
                else -> AMBIGUOUS
            }
        }
        return out
    }

    internal fun toJson(m: Map<String, String>): String {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        return o.toString()
    }

    internal fun fromJson(s: String): Map<String, String> {
        val o = JSONObject(s)
        val out = HashMap<String, String>()
        for (k in o.keys()) out[k] = o.optString(k)
        return out
    }

    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    } catch (_: IOException) {
        null
    } catch (_: Exception) {
        null
    }
}
