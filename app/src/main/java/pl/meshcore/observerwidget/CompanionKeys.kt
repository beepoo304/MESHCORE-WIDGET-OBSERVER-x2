package pl.meshcore.observerwidget

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Klucze publiczne mapujących po nicku: baza mapera ma tylko nick, a klucz companiona bierzemy
 * z list węzłów CoreScope (live.meshcorekk.xyz i mc.inside.net.pl), dopasowując nazwę bez względu
 * na wielkość liter. Nie każdy mapujący jest w tych listach – wtedy „klucz nieznany”.
 */
object CompanionKeys {
    val HOSTS = listOf("live.meshcorekk.xyz", "mc.inside.net.pl")
    private const val FILE = "companion_keys.json"
    private const val PREFS = "companion_keys"
    private const val TTL_MS = 6 * 60 * 60_000L
    private const val TIMEOUT_MS = 20_000

    @Volatile private var keys: Map<String, String>? = null

    fun normName(name: String): String = name.trim().lowercase()

    /** Klucz (64 hex, wielkie litery) dla nicku mapującego albo null. */
    fun keyFor(ctx: Context, nick: String?): String? {
        if (nick.isNullOrBlank()) return null
        return load(ctx)[normName(nick)]
    }

    private fun load(ctx: Context): Map<String, String> {
        keys?.let { return it }
        val f = File(ctx.applicationContext.filesDir, FILE)
        val m = try { if (f.exists()) fromJson(f.readText()) else emptyMap() } catch (_: Exception) { emptyMap() }
        keys = m
        return m
    }

    /** Odświeżenie listy (co [TTL_MS]); błąd jednego API nie kasuje danych z drugiego. */
    fun refreshIfStale(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("at", 0L) < TTL_MS && load(app).isNotEmpty()) return
        val merged = HashMap<String, Pair<String, String>>()
        var anyOk = false
        for (h in HOSTS) {
            val body = get("https://$h/api/nodes?role=companion&limit=5000") ?: continue
            val parsed = parse(body)
            if (parsed.isEmpty()) continue
            anyOk = true
            for ((name, v) in parsed) {
                val cur = merged[name]
                if (cur == null || v.second > cur.second) merged[name] = v
            }
        }
        if (!anyOk) return
        val out = merged.mapValues { it.value.first }
        File(app.filesDir, FILE).writeText(toJson(out))
        prefs.edit().putLong("at", now).apply()
        keys = out
    }

    /** nodes[]: nazwa → (klucz, last_heard). Przy powtórzonej nazwie wygrywa ostatnio słyszany. */
    fun parse(body: String): Map<String, Pair<String, String>> {
        val root = try { JSONObject(body) } catch (_: Exception) { return emptyMap() }
        val arr = root.optJSONArray("nodes") ?: return emptyMap()
        val out = HashMap<String, Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").let { if (it == "null") "" else normName(it) }
            val key = o.optString("public_key", "").trim()
            if (name.isEmpty() || !Config.isValidId(key)) continue
            val heard = o.optString("last_heard", "")
            val cur = out[name]
            if (cur == null || heard > cur.second) out[name] = Config.normalizeId(key) to heard
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
