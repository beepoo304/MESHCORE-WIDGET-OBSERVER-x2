package pl.meshcore.observerwidget

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Zakładka MAP: trasa wybranej sesji mapowania na jasnej mapie jak w meshcoresouth.pl/mapper —
 * heksy (kratki pomiarów) kolorowane SNR-u-RPT, linia przerywana trasy, przerywane linie
 * do repeaterów, które usłyszały pomiary, i wszystkie repeatery z bazy mapera.
 *
 * Rysowanie robi WebView (Leaflet 1.9.4 w assetach, kafelki OpenStreetMap z filtrem
 * jasnym jak w mapperze), a Kotlin podaje dane przez most JS (`Android.*`).
 */
class MapTrackActivity : Activity() {
    private lateinit var webView: WebView
    private val main = Handler(Looper.getMainLooper())
    private var repeaters: Map<String, MapperHistory.Repeater> = emptyMap()
    private var history: MapperHistory.History? = null
    @Volatile private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.rgb(0xdf, 0xe7, 0xec))
        }
        setContentView(webView)
        @SuppressLint("SetJavaScriptEnabled")
        val s = webView.settings
        s.javaScriptEnabled = true
        s.allowFileAccess = true
        s.domStorageEnabled = true
        s.builtInZoomControls = false          // pinch-zoom Leaflet, nie natywny WebView
        s.displayZoomControls = false
        webView.addJavascriptInterface(Bridge(), "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                loadData()
            }
        }
        webView.applySystemBarsPadding()
        webView.loadUrl("file:///android_asset/map_track.html")
    }

    /** W tle: repeaters + pełna baza punktów mapera; potem JS dociąga dane przez most. */
    private fun loadData() {
        if (loading) return
        loading = true
        thread(name = "mapper-history") {
            val timeout = 15_000
            val reps = MapperHistory.repeaters(timeout)
            val hist = MapperHistory.fetch(timeout)
            repeaters = reps
            history = hist
            loading = false
            main.post { webView.evaluateJavascript("window.__dataReady && __dataReady()", null) }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    /** Most JS→Kotlin: wywoływany z WebView na osobnym wątku, zwraca gotowy JSON. */
    private inner class Bridge {
        @JavascriptInterface
        fun close() {
            main.post { finish() }
        }

        /** Repeatery: {shortId: {name, lat, lon}} — tylko te z pozycją. */
        @JavascriptInterface
        fun getReps(): String {
            val o = JSONObject()
            for ((id, r) in repeaters) {
                if (r.lat == null || r.lon == null) continue
                o.put(id, JSONObject()
                    .put("name", r.name ?: id)
                    .put("lat", r.lat)
                    .put("lon", r.lon))
            }
            return o.toString()
        }

        /** Stacje do wyboru: OBSERVER i dodane RPT1–RPT5: [{id, label}]. */
        @JavascriptInterface
        fun getSources(): String {
            val cfg = Config(applicationContext)
            val arr = JSONArray()
            fun name(short: String) = repeaters[short]?.name ?: MapperMonitor.rptInfo(short)?.name
            val obs = cfg.observerId
            if (obs.isNotBlank()) {
                val sh = MapperApi.shortId(obs)
                arr.put(JSONObject().put("id", sh).put("label", "OBSERVER" + (name(sh)?.let { " · $it" } ?: "")))
            }
            for ((slot, id) in cfg.rptIds.toSortedMap()) {
                val sh = MapperApi.shortId(id)
                arr.put(JSONObject().put("id", sh).put("label", "RPT$slot" + (name(sh)?.let { " · $it" } ?: "")))
            }
            return arr.toString()
        }

        /** Lista mapujących, których usłyszała stacja: jeden wpis na mapującego (jego najnowsza sesja). */
        @JavascriptInterface
        fun getSessions(srcShort: String): String {
            val arr = JSONArray()
            val all = history?.sessions ?: return arr.toString()
            val cut = System.currentTimeMillis() - Config(applicationContext).mapperKeepEffectiveMs
            val seen = HashSet<Long>()
            for (s in all) {                       // posortowane: najnowsze najpierw
                if (s.endMs < cut) continue
                if (s.points.none { p -> p.hits.any { it.rptShortId == srcShort } }) continue
                if (!seen.add(s.userId)) continue
                val label = "${s.nick ?: "?"} · ${TimeFormat.dayMonth(s.startMs)} ${TimeFormat.hm(s.startMs)}–${TimeFormat.hm(s.endMs)}"
                arr.put(JSONObject().put("id", "${s.userId}_${s.startMs}").put("label", label))
            }
            return arr.toString()
        }

        /** Trasa jednej sesji: {nick, pts: [{lat, lon, t, h: [{n, snr, rssi}]}]}. */
        @JavascriptInterface
        fun getSession(id: String): String {
            val all = history?.sessions ?: return "{}"
            val s = all.firstOrNull { "${it.userId}_${it.startMs}" == id } ?: return "{}"
            val pts = JSONArray()
            for (p in s.points) {
                val h = JSONArray()
                for (hit in p.hits) {
                    h.put(JSONObject()
                        .put("n", hit.rptShortId)
                        .putOpt("snr", hit.snr)
                        .putOpt("rssi", hit.rssi))
                }
                pts.put(JSONObject()
                    .put("lat", p.lat)
                    .put("lon", p.lon)
                    .put("t", p.tsMs)
                    .put("h", h))
            }
            return JSONObject()
                .put("nick", s.nick ?: "?")
                .put("pts", pts)
                .toString()
        }
    }
}
