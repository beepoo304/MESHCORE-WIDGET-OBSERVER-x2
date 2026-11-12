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
    private const val KEY_RPT_PINGS = "rpt_pings_json"
    private const val KEY_RPT_INFO = "rpt_info_json"
    /** Krótkie ID repeaterów RPT, dla których historia 24 h została już wczytana. */
    private const val KEY_RPT_KNOWN = "rpt_known"

    /** Ponowne sprawdzenie RPT, którego nie ma w bazie mapera (użytkownik może go właśnie dodać). */
    private const val RPT_RETRY_MS = 60 * 60_000L

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

    /** Pingi przy moich repeaterach RPT1–RPT5: krótkie ID repeatera → pomiary posortowane po czasie. */
    private val rptPings = HashMap<String, ArrayList<MapperPing>>()

    /** Pozycja i nazwa RPT z bazy repeaterów mapera (krótkie ID → info). */
    data class RptInfo(val lat: Double?, val lon: Double?, val found: Boolean, val name: String?, val atMs: Long)

    private val rptInfo = HashMap<String, RptInfo>()

    fun nick(userId: Long): String? = synchronized(nicks) { nicks[userId] }

    /**
     * Mapujący zapisani w LOG (Observer + RPT1–RPT5): ID konta → nick, z całej trzymanej
     * historii (nie tylko bieżącej sesji). Zakładka MAP pokazuje trasy tylko tych mapujących.
     */
    fun logUsers(): Map<Long, String> {
        val ids = LinkedHashSet<Long>()
        synchronized(pings) { pings.forEach { ids.add(it.userId) } }
        synchronized(rptPings) { rptPings.values.forEach { l -> l.forEach { ids.add(it.userId) } } }
        val out = LinkedHashMap<Long, String>()
        for (id in ids) out[id] = nick(id) ?: "?"
        return out
    }

    /** Co wiemy o repeaterze z bazy mapera (null = jeszcze nie sprawdzano). */
    fun rptInfo(shortId: String): RptInfo? = synchronized(rptInfo) { rptInfo[shortId] }

    /** Czy stan został już wczytany z dysku w tym życiu procesu. */
    private var loaded = false

    /**
     * Jak długo trzymamy pingi (okno LOG) — z ustawień użytkownika (Config.mapperKeepMs).
     * Domyślnie 24 h; zmiana w zakładce LOG od razu przycina historię przy następnym cyklu.
     */
    @Volatile
    private var keepMs: Long = MapperApi.KEEP_MS

    /** Przeczytaj retencję z ustawień (np. po zmianie w zakładce LOG). */
    fun refreshKeepMs(ctx: Context) {
        keepMs = Config(ctx.applicationContext).mapperKeepEffectiveMs
    }

    /** Czyszczenie stanu (np. po zmianie Observera w ustawieniach). Kasuje też zapis na dysku. */
    fun reset(ctx: Context? = null) {
        // Moje repeatery RPT1–RPT5 nie zależą od Observera: ich historia zostaje.
        ctx?.let { ensureLoaded(it.applicationContext) }
        val keepNicks = synchronized(rptPings) { rptPings.values.any { it.isNotEmpty() } }
        synchronized(pings) { pings.clear() }
        if (!keepNicks) synchronized(nicks) { nicks.clear() }
        synchronized(this) {
            obsLat = null
            obsLon = null
        }
        loaded = true
        if (ctx != null) {
            val editor = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_PINGS)
            if (!keepNicks) editor.remove(KEY_NICKS)
            editor.apply()
        }
    }

    /**
     * Jednorazowe przywrócenie pingów i nicków z dysku (okno 24 h). Uruchamiane na początku
     * każdego poll() – jeśli proces padł (aktualizacja APK, restart telefonu, Android ubił
     * usługę w tle), karta mapowania w zakładce OBSERVER od razu ma z powrotem swoją historię.
     */
    private fun ensureLoaded(ctx: Context) {
        refreshKeepMs(ctx)
        if (loaded) return
        synchronized(pings) {
            if (loaded) return
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cut = System.currentTimeMillis() - keepMs
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
            synchronized(rptPings) {
                rptPings.clear()
                for ((id, list) in rptPingsFromJson(prefs.getString(KEY_RPT_PINGS, null))) {
                    val kept = list.filter { it.tsMs >= cut }.sortedBy { it.tsMs }
                    if (kept.isNotEmpty()) rptPings[id] = ArrayList(kept)
                }
            }
            synchronized(rptInfo) {
                rptInfo.clear()
                rptInfo.putAll(rptInfoFromJson(prefs.getString(KEY_RPT_INFO, null)))
            }
            loaded = true
        }
    }

    /** Zapis okna 24 h pingów + nicków na dysk (asynchronicznie). */
    private fun persist(ctx: Context) {
        val cut = System.currentTimeMillis() - keepMs
        val pingJson = synchronized(pings) { pingsToJson(pings.filter { it.tsMs >= cut }) }
        val nickJson = synchronized(nicks) { nicksToJson(nicks) }
        val rptJson = synchronized(rptPings) {
            rptPingsToJson(rptPings.mapValues { (_, v) -> v.filter { it.tsMs >= cut } })
        }
        val infoJson = synchronized(rptInfo) { rptInfoToJson(rptInfo) }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PINGS, pingJson)
            .putString(KEY_NICKS, nickJson)
            .putString(KEY_RPT_PINGS, rptJson)
            .putString(KEY_RPT_INFO, infoJson)
            .apply()
    }

    /**
     * Jedno odpytanie mapera. Zwraca stan mapowania oraz pomiary nowo dodane w tej turze
     * (do powiadomienia). Pierwsza tura ustawia tylko punkt startowy w bazie – historia
     * nie wywoła powiadomienia, ale wchodzi do stanu, żeby aplikacja od razu pokazała sesję.
     */
    /** Wynik jednego odpytania: Observer (jak dotąd) + moje repeatery RPT1–RPT5. */
    data class PollResult(
        val state: MappingState?,
        val fresh: List<MapperPing>,
        val rpt: List<RptMapping> = emptyList(),
        val rptFresh: Map<Int, List<MapperPing>> = emptyMap()
    )

    fun poll(ctx: Context, observerId: String, timeoutMs: Int, rptIds: Map<Int, String> = emptyMap()): PollResult {
        val config = Config(ctx.applicationContext)
        MapperApi.configure(ctx)
        refreshKeepMs(ctx.applicationContext)
        ensureLoaded(ctx.applicationContext)
        val short = MapperApi.shortId(observerId)
        // Slot → krótkie ID; repeater wpisany drugi raz albo równy Observerowi jest pomijany.
        val rptShorts = LinkedHashMap<Int, String>()
        for ((slot, id) in rptIds.toSortedMap()) {
            val rs = MapperApi.shortId(id)
            if (rs.length == 16 && rs != short && rs !in rptShorts.values) rptShorts[slot] = rs
        }
        pruneRpt(rptShorts.values.toSet())
        if (short.length != 16) return PollResult(state(), emptyList(), rptStates(rptShorts))
        ensurePosition(ctx.applicationContext, short, timeoutMs)
        ensureRptInfo(ctx.applicationContext, rptShorts.values, timeoutMs)
        val bySlot = rptShorts.entries.associate { (slot, rs) -> rs to slot }
        val rptFresh = HashMap<Int, MutableList<MapperPing>>()

        val firstRun = config.mapperLastId <= 0L
        var since = if (firstRun) 0L else config.mapperLastId
        var lastId = since
        val fresh = mutableListOf<MapperPing>()
        var dirty = firstRun // pierwsza tura zapisuje (nawet pusty) stan na dysk
        var pages = 0
        while (pages < MAX_PAGES) {
            pages++
            val result = MapperApi.poll(sinceId = since, timeoutMs = timeoutMs)
                ?: return PollResult(state(), emptyList(), rptStates(rptShorts)) // serwer mapera nie odpowiada: zostaje stan z pamięci
            if (result.users.isNotEmpty()) dirty = true
            synchronized(nicks) { nicks.putAll(result.users) }
            if (result.rev != 0 && result.rev != config.mapperRev) {
                // Zmiana rewizji bazy (np. moderator usunął dane): przesuwamy punkt startowy,
                // żeby nie odtwarzać starych punktów jako nowych pingów.
                config.mapperRev = result.rev
                config.mapperLastId = result.lastId
                return PollResult(state(), emptyList(), rptStates(rptShorts))
            }
            if (result.rev != 0) config.mapperRev = result.rev
            for (p in result.points) {
                if (p.rptId == short) {
                    if (add(p)) { fresh.add(p); dirty = true }
                } else {
                    val slot = p.rptId?.let { bySlot[it] } ?: continue
                    if (addRpt(p.rptId!!, p)) { rptFresh.getOrPut(slot) { mutableListOf() }.add(p); dirty = true }
                }
            }
            lastId = maxOf(lastId, result.lastId)
            since = result.lastId
            if (!result.more) break
        }
        config.mapperLastId = lastId
        // Nowo dodany RPT: jednorazowo dociągamy jego historię z ostatnich 24 h (bez powiadomień).
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val known = prefs.getString(KEY_RPT_KNOWN, "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        val newcomers = rptShorts.values.filter { it !in known }.toSet()
        if (newcomers.isNotEmpty()) {
            if (firstRun || backfillRpt(newcomers, timeoutMs)) {
                prefs.edit().putString(KEY_RPT_KNOWN, (known.intersect(rptShorts.values.toSet()) + newcomers).joinToString(",")).apply()
            }
            for (rs in newcomers) bySlot[rs]?.let { rptFresh.remove(it) }
            dirty = true
        }
        if (dirty) persist(ctx.applicationContext)
        // Pierwsza tura: punkt startowy ustawiony, ale nie powiadamiamy o historii.
        return PollResult(
            state = state(),
            fresh = if (firstRun) emptyList() else fresh,
            rpt = rptStates(rptShorts),
            rptFresh = if (firstRun) emptyMap() else rptFresh
        )
    }

    /** Historia 24 h dla nowo dodanych RPT (od początku bazy, max [MAX_PAGES] stron). true = udało się. */
    private fun backfillRpt(shorts: Set<String>, timeoutMs: Int): Boolean {
        var since = 0L
        var pages = 0
        while (pages < MAX_PAGES) {
            pages++
            val result = MapperApi.poll(sinceId = since, timeoutMs = timeoutMs) ?: return false
            synchronized(nicks) { nicks.putAll(result.users) }
            for (p in result.points) if (p.rptId != null && p.rptId in shorts) addRpt(p.rptId, p)
            since = result.lastId
            if (!result.more) break
        }
        return true
    }

    /** Usunięty z ustawień RPT: kasujemy jego pingi, żeby karta nie wisiała w aplikacji. */
    private fun pruneRpt(keep: Set<String>) {
        synchronized(rptPings) { rptPings.keys.retainAll(keep) }
    }

    private fun addRpt(shortId: String, p: MapperPing): Boolean = synchronized(rptPings) {
        val list = rptPings.getOrPut(shortId) { ArrayList() }
        if (list.any { it.id == p.id }) return false
        list.add(p)
        list.sortBy { it.tsMs }
        val cut = System.currentTimeMillis() - keepMs
        while (list.isNotEmpty() && list.first().tsMs < cut) list.removeAt(0)
        true
    }

    /** Stan mapowania przy każdym wpisanym RPT (także bez pingów – wtedy active=false i pusta lista). */
    fun rptStates(rptShorts: Map<Int, String>, nowMs: Long = System.currentTimeMillis()): List<RptMapping> =
        rptShorts.map { (slot, rs) ->
            val list = synchronized(rptPings) { rptPings[rs]?.toList().orEmpty() }
            val info = rptInfo(rs)
            val lat = info?.lat?.takeIf { info.found }
            val lon = info?.lon?.takeIf { info.found }
            RptMapping(slot, rs, info?.name, stateOf(list, nowMs, { nick(it) }, lat, lon))
        }

    /**
     * Pozycje i nazwy RPT z bazy repeaterów mapera: jedno pobranie listy na wszystkie brakujące.
     * Znaleziony – cache na tydzień; brak w bazie – ponawiamy co godzinę.
     */
    private fun ensureRptInfo(ctx: Context, shorts: Collection<String>, timeoutMs: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val missing = shorts.filter { rs ->
            val info = rptInfo(rs)
            force || info == null || now - info.atMs > (if (info.found) POS_TTL_MS else RPT_RETRY_MS)
        }
        if (missing.isEmpty()) return
        val body = MapperApi.repeatersBody(timeoutMs) ?: return
        synchronized(rptInfo) {
            for (rs in missing) {
                val pos = MapperApi.parseRepeaterPosition(body, rs) ?: continue
                rptInfo[rs] = RptInfo(
                    lat = if (pos.found) pos.lat else null, lon = if (pos.found) pos.lon else null,
                    found = pos.found, name = pos.name, atMs = now
                )
            }
        }
        persist(ctx)
    }

    /** Sprawdzenie RPT w bazie mapera na żądanie (np. zaraz po wpisaniu klucza w aplikacji). */
    fun lookupRpt(ctx: Context, id: String, timeoutMs: Int): RptInfo? {
        val rs = MapperApi.shortId(id)
        if (rs.length != 16) return null
        ensureLoaded(ctx.applicationContext)
        ensureRptInfo(ctx.applicationContext, listOf(rs), timeoutMs, force = true)
        return rptInfo(rs)
    }

    /** Dodaje pomiar, jeśli jeszcze go nie mamy. true = był nowy. */
    private fun add(p: MapperPing): Boolean = synchronized(pings) {
        if (pings.any { it.id == p.id }) return false
        pings.add(p)
        pings.sortBy { it.tsMs }
        val cut = System.currentTimeMillis() - keepMs
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

/** Pingi RPT na dysk: {krótkie ID: [pingi]}. */
internal fun rptPingsToJson(map: Map<String, List<MapperPing>>): String {
    val o = JSONObject()
    for ((id, list) in map) if (list.isNotEmpty()) o.put(id, JSONArray(pingsToJson(list)))
    return o.toString()
}

internal fun rptPingsFromJson(json: String?): Map<String, List<MapperPing>> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        val o = JSONObject(json)
        val out = HashMap<String, List<MapperPing>>()
        for (key in o.keys()) {
            val arr = o.optJSONArray(key) ?: continue
            out[key] = pingsFromJson(arr.toString())
        }
        out
    } catch (_: Exception) {
        emptyMap()
    }
}

internal fun rptInfoToJson(map: Map<String, MapperMonitor.RptInfo>): String {
    val o = JSONObject()
    for ((id, i) in map) {
        val r = JSONObject()
        i.lat?.let { r.put("la", it) }
        i.lon?.let { r.put("lo", it) }
        r.put("f", i.found)
        i.name?.let { r.put("n", it) }
        r.put("t", i.atMs)
        o.put(id, r)
    }
    return o.toString()
}

internal fun rptInfoFromJson(json: String?): Map<String, MapperMonitor.RptInfo> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        val o = JSONObject(json)
        val out = HashMap<String, MapperMonitor.RptInfo>()
        for (key in o.keys()) {
            val r = o.optJSONObject(key) ?: continue
            out[key] = MapperMonitor.RptInfo(
                lat = r.optNullableDouble("la"), lon = r.optNullableDouble("lo"),
                found = r.optBoolean("f", false),
                name = if (r.has("n") && !r.isNull("n")) r.getString("n") else null,
                atMs = r.optLong("t", 0L)
            )
        }
        out
    } catch (_: Exception) {
        emptyMap()
    }
}
