package pl.meshcore.observerwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Śledzenie mapowania w zasięgu Observera na podstawie publicznej bazy mapera MeshCore South.
 *
 * Co cykl odświeżania widgetu odpytujemy API o punkty nowsze niż ostatnio widziany ID.
 * Ping mapowania odebrany przez naszego Observera = punkt z rpt równym pierwszym 16 znakom
 * jego klucza publicznego. Z punktów składamy sesję mapowania (przerwa > 5 min kończy sesję);
 * stan trzymamy w pamięci, a pingi i nicki dodatkowo zapisujemy na dysk – dzięki temu karta
 * „ostatniego mapowania” przeżywa restart procesu/telefonu i widnieje w zakładce OBSERVER
 * przez pełne 24 h od ostatniego pingu, jak obiecano.
 */
object MapperMonitor {
    private const val MAX_PAGES = 5

    /** Osobne prefs na stan mapowania (pingi + nicki), przeżywające restart procesu. */
    private const val PREFS_NAME = "mapper_state"
    private const val KEY_PINGS = "pings_json"
    private const val KEY_NICKS = "nicks_json"

    /** Jak długo trzymamy pozycję Observera w pamięci/prefs (tydzień). */
    private const val POS_TTL_MS = 7 * 24 * 60 * 60_000L

    /** Jak często ponawiamy sprawdzanie, gdy Observera nie ma w bazie repeaterów mapera. */
    private const val POS_RETRY_MS = 24 * 60 * 60_000L

    /** Nasze pomiary (pingi odebrane przez Observera), posortowane po czasie. */
    private val pings = ArrayList<MapperPing>()

    /** Cache nicków mapujących (ID konta → nick), żeby nick przetrwał kolejne odpytania. */
    private val nicks = HashMap<Long, String>()

    /** Pozycja Observera z bazy repeaterów mapera (pamięć między cyklami). */
    private var obsLat: Double? = null
    private var obsLon: Double? = null

    fun nick(userId: Long): String? = synchronized(nicks) { nicks[userId] }

    /** Czy stan został już wczytany z dysku w tym życiu procesu. */
    private var loaded = false

    /** Czyszczenie stanu (np. po zmianie Observera w ustawieniach). Kasuje też zapis na dysku. */
    fun reset(ctx: Context? = null) {
        synchronized(pings) { pings.clear() }
        synchronized(nicks) { nicks.clear() }
        synchronized(this) {
            obsLat = null
            obsLon = null
        }
        loaded = true
        ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.remove(KEY_PINGS)?.remove(KEY_NICKS)?.apply()
    }

    /**
     * Jednorazowe przywrócenie pingów i nicków z dysku (okno 24 h). Uruchamiane na początku
     * każdego poll() – jeśli proces padł (aktualizacja APK, restart telefonu, Android ubił
     * usługę w tle), karta mapowania w zakładce OBSERVER od razu ma z powrotem swoją historię.
     */
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(pings) {
            if (loaded) return
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cut = System.currentTimeMillis() - MapperApi.KEEP_MS
            val restored = pingsFromJson(prefs.getString(KEY_PINGS, null))
                .filter { it.tsMs >= cut }
                .sortedBy { it.tsMs }
            if (restored.isNotEmpty()) {
                pings.clear()
                pings.addAll(restored)
            }
            val restoredNicks = nicksFromJson(prefs.getString(KEY_NICKS, null))
            if (restoredNicks.isNotEmpty()) {
                nicks.clear()
                nicks.putAll(restoredNicks)
            }
            loaded = true
        }
    }

    /** Zapis okna 24 h pingów + nicków na dysk (asynchronicznie). */
    private fun persist(ctx: Context) {
        val cut = System.currentTimeMillis() - MapperApi.KEEP_MS
        val pingJson = synchronized(pings) { pingsToJson(pings.filter { it.tsMs >= cut }) }
        val nickJson = synchronized(nicks) { nicksToJson(nicks) }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PINGS, pingJson)
            .putString(KEY_NICKS, nickJson)
            .apply()
    }

    /**
     * Jedno odpytanie mapera. Zwraca stan mapowania oraz pomiary nowo dodane w tej turze
     * (do powiadomienia). Pierwsza tura ustawia tylko punkt startowy w bazie – historia
     * nie wywoła powiadomienia, ale wchodzi do stanu, żeby aplikacja od razu pokazała sesję.
     */
    fun poll(ctx: Context, observerId: String, timeoutMs: Int): Pair<MappingState?, List<MapperPing>> {
        val config = Config(ctx.applicationContext)
        ensureLoaded(ctx.applicationContext)
        val short = MapperApi.shortId(observerId)
        if (short.length != 16) return state() to emptyList()
        ensurePosition(ctx.applicationContext, short, timeoutMs)

        val firstRun = config.mapperLastId <= 0L
        var since = if (firstRun) 0L else config.mapperLastId
        var lastId = since
        val fresh = mutableListOf<MapperPing>()
        var dirty = firstRun // pierwsza tura zapisuje (nawet pusty) stan na dysk
        var pages = 0
        while (pages < MAX_PAGES) {
            pages++
            val result = MapperApi.poll(sinceId = since, timeoutMs = timeoutMs)
                ?: return state() to emptyList() // serwer mapera nie odpowiada: zostaje stan z pamięci
            if (result.users.isNotEmpty()) dirty = true
            synchronized(nicks) { nicks.putAll(result.users) }
            if (result.rev != 0 && result.rev != config.mapperRev) {
                // Zmiana rewizji bazy (np. moderator usunął dane): przesuwamy punkt startowy,
                // żeby nie odtwarzać starych punktów jako nowych pingów.
                config.mapperRev = result.rev
                config.mapperLastId = result.lastId
                return state() to emptyList()
            }
            if (result.rev != 0) config.mapperRev = result.rev
            for (p in result.points) if (p.rptId == short && add(p)) { fresh.add(p); dirty = true }
            lastId = maxOf(lastId, result.lastId)
            since = result.lastId
            if (!result.more) break
        }
        config.mapperLastId = lastId
        if (dirty) persist(ctx.applicationContext)
        // Pierwsza tura: punkt startowy ustawiony, ale nie powiadamiamy o historii.
        return state() to if (firstRun) emptyList() else fresh
    }

    /** Dodaje pomiar, jeśli jeszcze go nie mamy. true = był nowy. */
    private fun add(p: MapperPing): Boolean = synchronized(pings) {
        if (pings.any { it.id == p.id }) return false
        pings.add(p)
        pings.sortBy { it.tsMs }
        val cut = System.currentTimeMillis() - MapperApi.KEEP_MS
        while (pings.isNotEmpty() && pings.first().tsMs < cut) pings.removeAt(0)
        true
    }

    /** Stan mapowania: sesja = pomiary połączone przerwami krótszymi niż [MapperApi.SESSION_GAP_MS]. */
    fun state(nowMs: Long = System.currentTimeMillis()): MappingState {
        val all: List<MapperPing> = synchronized(pings) { pings.toList() }
        val pos = synchronized(this) { if (obsLat != null && obsLon != null) obsLat to obsLon else null }
        return stateOf(all, nowMs, { userId -> nick(userId) }, pos?.first, pos?.second)
    }

    /**
     * Pozycja Observera (naszego RPT) z bazy repeaterów mapera – potrzebna do strzałki
     * kierunku i odległości mapera. Cache w prefs: tydzień dla znalezionej pozycji,
     * doba ponawiania, gdy Observera nie ma w bazie. Błąd sieci = spróbujemy w następnym cyklu.
     */
    private fun ensurePosition(ctx: Context, short: String, timeoutMs: Int) {
        val config = Config(ctx)
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (obsLat != null && obsLon != null) return
            val lat = config.mapperObsLat.toDouble()
            val lon = config.mapperObsLon.toDouble()
            if (!lat.isNaN() && !lon.isNaN() && config.mapperObsPosOk && now - config.mapperObsPosAtMs < POS_TTL_MS) {
                obsLat = lat; obsLon = lon; return
            }
            if (!config.mapperObsPosOk && config.mapperObsPosAtMs > 0L && now - config.mapperObsPosAtMs < POS_RETRY_MS) return
        }
        val pos = MapperApi.repeaterPosition(short, timeoutMs) ?: return // serwer nie odpowiada: spróbujemy znowu
        if (pos.found) {
            config.mapperObsLat = pos.lat.toFloat()
            config.mapperObsLon = pos.lon.toFloat()
            config.mapperObsPosOk = true
            config.mapperObsPosAtMs = now
            synchronized(this) { obsLat = pos.lat; obsLon = pos.lon }
        } else {
            // Observera nie ma w bazie repeaterów: zapisujemy czas sprawdzenia, żeby nie pytać co cykl.
            config.mapperObsPosOk = false
            config.mapperObsPosAtMs = now
        }
    }

    /** Czysta logika sesji mapowania (testowalna bez Androida i sieci). */
    fun stateOf(
        all: List<MapperPing>, nowMs: Long, nickOf: (Long) -> String? = { null },
        observerLat: Double? = null, observerLon: Double? = null
    ): MappingState {
        if (all.isEmpty()) return MappingState(active = false)
        val last = all.last()
        // Ikona MAPPER znika już po 3 min ciszy (użytkownik kończy mapowanie i chce natychmiastowego feedbacku);
        // sesja (do karty w aplikacji) łączy pomiary do 5 min przerwy.
        val active = nowMs - last.tsMs <= MapperApi.ACTIVE_MS
        // Sesja: idziemy od najnowszego wstecz, dopóki przerwy między pomiarami są krótkie.
        val session = mutableListOf(last)
        for (i in all.indices.last - 1 downTo 0) {
            if (session.first().tsMs - all[i].tsMs <= MapperApi.SESSION_GAP_MS) session.add(0, all[i]) else break
        }
        // Po wygaśnięciu sesji zostaje ostatni pomiar – do panelu „ostatnie mapowanie” w aplikacji.
        val shown = if (active) session else listOf(last)
        val mapperNicks = shown.map { it.userId }.distinct().mapNotNull(nickOf)
        return MappingState(
            active = active, pings = shown, mapperNicks = mapperNicks,
            observerLat = observerLat, observerLon = observerLon
        )
    }
}

/** Serializacja pingów mapowania do JSON (stan na dysk, przeżywa restart procesu). */
internal fun pingsToJson(pings: List<MapperPing>): String {
    val arr = JSONArray()
    for (p in pings) {
        val o = JSONObject()
        o.put("id", p.id)
        o.put("t", p.tsMs)
        p.lat?.let { o.put("la", it) }
        p.lon?.let { o.put("lo", it) }
        p.rptId?.let { o.put("r", it) }
        p.snrDb?.let { o.put("s", it) }
        p.observerSnrDb?.let { o.put("os", it) }
        p.rssiDbm?.let { o.put("rs", it) }
        o.put("u", p.userId)
        p.accM?.let { o.put("a", it) }
        p.noiseFloorDb?.let { o.put("n", it) }
        arr.put(o)
    }
    return arr.toString()
}

/** Odczyt pingów z JSON; uszkodzone/obce rekordy są po prostu pomijane. */
internal fun pingsFromJson(json: String?): List<MapperPing> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        val out = ArrayList<MapperPing>(arr.length())
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                out.add(
                    MapperPing(
                        id = o.getLong("id"),
                        tsMs = o.getLong("t"),
                        lat = o.optNullableDouble("la"),
                        lon = o.optNullableDouble("lo"),
                        rptId = if (o.has("r")) o.getString("r") else null,
                        snrDb = o.optNullableDouble("s"),
                        observerSnrDb = o.optNullableDouble("os"),
                        rssiDbm = o.optNullableDouble("rs"),
                        userId = o.getLong("u"),
                        accM = o.optNullableDouble("a"),
                        noiseFloorDb = o.optNullableDouble("n")
                    )
                )
            } catch (_: Exception) {
                // pojedynczy uszkodzony rekord nie wywala całej historii
            }
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** Null gdy klucza brak lub wartość to JSON null. */
internal fun JSONObject.optNullableDouble(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null

/** Serializacja cache nicków (ID konta → nick). */
internal fun nicksToJson(nicks: Map<Long, String>): String {
    val o = JSONObject()
    for ((id, nick) in nicks) o.put(id.toString(), nick)
    return o.toString()
}

internal fun nicksFromJson(json: String?): Map<Long, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        val o = JSONObject(json)
        val out = HashMap<Long, String>()
        for (key in o.keys()) {
            val id = key.toLongOrNull() ?: continue
            val nick = o.optString(key, "")
            if (nick.isNotBlank()) out[id] = nick
        }
        out
    } catch (_: Exception) {
        emptyMap()
    }
}
