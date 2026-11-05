package pl.meshcore.observerwidget

import android.content.Context

/**
 * Śledzenie mapowania w zasięgu Observera na podstawie publicznej bazy mapera MeshCore South.
 *
 * Co cykl odświeżania widgetu odpytujemy API o punkty nowsze niż ostatnio widziany ID.
 * Ping mapowania odebrany przez naszego Observera = punkt z rpt równym pierwszym 16 znakom
 * jego klucza publicznego. Z punktów składamy sesję mapowania (przerwa > 5 min kończy sesję);
 * stan trzymamy w pamięci, żeby widget i aplikacja mogły go rysować bez sieci.
 */
object MapperMonitor {
    private const val MAX_PAGES = 5

    /** Nasze pomiary (pingi odebrane przez Observera), posortowane po czasie. */
    private val pings = ArrayList<MapperPing>()

    /** Cache nicków mapujących (ID konta → nick), żeby nick przetrwał kolejne odpytania. */
    private val nicks = HashMap<Long, String>()

    fun nick(userId: Long): String? = synchronized(nicks) { nicks[userId] }

    /** Czyszczenie stanu (np. po zmianie Observera w ustawieniach). */
    fun reset() {
        synchronized(pings) { pings.clear() }
        synchronized(nicks) { nicks.clear() }
    }

    /**
     * Jedno odpytanie mapera. Zwraca stan mapowania oraz pomiary nowo dodane w tej turze
     * (do powiadomienia). Pierwsza tura ustawia tylko punkt startowy w bazie – historia
     * nie wywoła powiadomienia, ale wchodzi do stanu, żeby aplikacja od razu pokazała sesję.
     */
    fun poll(ctx: Context, observerId: String, timeoutMs: Int): Pair<MappingState?, List<MapperPing>> {
        val config = Config(ctx.applicationContext)
        val short = MapperApi.shortId(observerId)
        if (short.length != 16) return state() to emptyList()

        val firstRun = config.mapperLastId <= 0L
        var since = if (firstRun) 0L else config.mapperLastId
        var lastId = since
        val fresh = mutableListOf<MapperPing>()
        var pages = 0
        while (pages < MAX_PAGES) {
            pages++
            val result = MapperApi.poll(sinceId = since, timeoutMs = timeoutMs)
                ?: return state() to emptyList() // serwer mapera nie odpowiada: zostaje stan z pamięci
            synchronized(nicks) { nicks.putAll(result.users) }
            if (result.rev != 0 && result.rev != config.mapperRev) {
                // Zmiana rewizji bazy (np. moderator usunął dane): przesuwamy punkt startowy,
                // żeby nie odtwarzać starych punktów jako nowych pingów.
                config.mapperRev = result.rev
                config.mapperLastId = result.lastId
                return state() to emptyList()
            }
            if (result.rev != 0) config.mapperRev = result.rev
            for (p in result.points) if (p.rptId == short && add(p)) fresh.add(p)
            lastId = maxOf(lastId, result.lastId)
            since = result.lastId
            if (!result.more) break
        }
        config.mapperLastId = lastId
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
        return stateOf(all, nowMs) { userId -> nick(userId) }
    }

    /** Czysta logika sesji mapowania (testowalna bez Androida i sieci). */
    fun stateOf(all: List<MapperPing>, nowMs: Long, nickOf: (Long) -> String? = { null }): MappingState {
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
        return MappingState(active = active, pings = shown, mapperNicks = mapperNicks)
    }
}
