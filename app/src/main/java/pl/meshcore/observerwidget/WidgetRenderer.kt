package pl.meshcore.observerwidget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.widget.RemoteViews
import kotlin.math.roundToInt

object WidgetRenderer {
    const val GREEN = 0xFF6EF015.toInt()
    const val ORANGE = 0xFFFF9500.toInt()
    const val RED = 0xFFFF3B30.toInt()
    const val GRAY = 0xFF6B6B6B.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    /** Napis i kolor statusu Observera. Wspólne dla widgetu i Dashboardu. */
    fun liveState(d: WidgetData, nowMs: Long): Pair<String, Int> {
        val obs = d.observer
        return when {
            !d.configured -> "SETUP" to ORANGE
            obs == null && d.busy -> "…" to GRAY
            obs == null -> "API ✕" to RED
            obs.lastPacketMs == null -> "NO DATA" to GRAY
            else -> when (Status.level(nowMs - obs.lastPacketMs, d.orangeMin, d.redMin)) {
                Level.GREEN -> "● LIVE" to GREEN
                Level.ORANGE -> "● IDLE" to ORANGE
                else -> "● NO PKT" to RED
            }
        }
    }

    private fun settingsIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 1, Intent(ctx, SettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Kolor słowa MAPPER w nagłówku widgetu (puls: sekunda jasno, sekunda przyciemnione). */
    const val MAPPER_BLUE = 0xFF4FC3F7.toInt()
    private const val MAPPER_BLUE_DIM = 0xFF1F6E8F.toInt()

    fun mappingIconColor(on: Boolean): Int = if (on) MAPPER_BLUE else MAPPER_BLUE_DIM

    /**
     * Słowo „MAPPER” w nagłówku widgetu (między MESHCORE a licznikiem odświeżania):
     * widoczne tylko wtedy, gdy ktoś mapuje w zasięgu Observera; pulsuje,
     * bo RemoteViews nie obsługują prawdziwej animacji. Okres 2 s.
     */
    fun mappingIconOn(nowMs: Long): Boolean = nowMs % 2_000L < 1_200L

    /**
     * Ping, którego ma pokazać strzałka kierunku: ostatni ping każdego mapującego
     * (z pozycją GPS), a przy kilku mapujących zmiana co 3 s (cykl [nowMs]).
     */
    fun mappingArrowPing(m: MappingState?, nowMs: Long): MapperPing? {
        if (m == null) return null
        val latest = m.pings
            .filter { it.lat != null && it.lon != null }
            .groupBy { it.userId }
            .map { (_, v) -> v.last() }
            .sortedBy { it.userId }
        if (latest.isEmpty()) return null
        val idx = ((nowMs / 3_000L) % latest.size).toInt()
        return latest[idx]
    }

    /** Strzałka kierunku mapera (czerwona, 8 kierunków = obrót o azymut) + odległość, np. „1,2km”. */
    fun mappingArrowBitmap(ctx: Context, bearingDeg: Double, distKm: Double): Bitmap {
        val density = ctx.resources.displayMetrics.density
        val h = (16 * density).roundToInt().coerceAtLeast(16)
        val arrow = (13 * density).roundToInt().coerceAtLeast(13)
        val gap = (3 * density).roundToInt().coerceAtLeast(3)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE
            textSize = 10 * density * 1.1f
            isFakeBoldText = true
        }
        val text = GeoMath.kmShort(distKm)
        val tw = textPaint.measureText(text).roundToInt()
        val bmp = Bitmap.createBitmap(arrow + gap + tw, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Strzałka: grot skierowany na północ, obrót o azymut (0° = N = góra).
        c.save()
        c.translate(arrow / 2f, h / 2f)
        c.rotate(bearingDeg.toFloat())
        val a = arrow / 2f
        val p = Path().apply {
            moveTo(0f, -a)                    // czubek
            lineTo(a * 0.60f, 0f)             // prawe skrzydło grotu
            lineTo(a * 0.24f, 0f)
            lineTo(a * 0.24f, a)              // trzon
            lineTo(-a * 0.24f, a)
            lineTo(-a * 0.24f, 0f)
            lineTo(-a * 0.60f, 0f)            // lewe skrzydło grotu
            close()
        }
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED })
        c.restore()
        // Odległość obok strzałki, wyśrodkowana w pionie.
        val ty = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        c.drawText(text, (arrow + gap).toFloat(), ty, textPaint)
        return bmp
    }

    fun render(
        ctx: Context,
        d: WidgetData,
        layout: WidgetLayout = WidgetLayout.DEFAULT,
        nowMs: Long = System.currentTimeMillis()
    ): RemoteViews {
        val v = RemoteViews(ctx.packageName, if (layout.compact) R.layout.widget_observer_compact else R.layout.widget_observer)
        val totalVisibility = if (layout.showTotal) android.view.View.VISIBLE else android.view.View.GONE
        v.setViewVisibility(R.id.m1_total_col, totalVisibility)
        v.setViewVisibility(R.id.m2_total_col, totalVisibility)
        v.setViewVisibility(R.id.hdr_total_col, totalVisibility)
        v.setOnClickPendingIntent(R.id.widget_root, ObserverRefresh.pendingIntent(ctx))
        v.setOnClickPendingIntent(R.id.obs_name, settingsIntent(ctx))
        // Słowo MAPPER w nagłówku: tylko gdy ktoś mapuje; puls = naprzemienny kolor, klik otwiera panel w aplikacji.
        val mappingActive = d.mapping?.active == true
        v.setViewVisibility(R.id.mapping_icon, if (mappingActive) android.view.View.VISIBLE else android.view.View.GONE)
        if (mappingActive) v.setTextColor(R.id.mapping_icon, mappingIconColor(mappingIconOn(nowMs)))
        v.setOnClickPendingIntent(R.id.mapping_icon, MainActivity.mappingIntent(ctx))
        // Strzałka kierunku: czerwona, obrócona w stronę mapera + odległość; przy kilku mapujących zmiana co 3 s.
        val m = d.mapping
        val arrowPing = mappingArrowPing(m, nowMs)
        val obsLat = m?.observerLat
        val obsLon = m?.observerLon
        val showArrow = mappingActive && arrowPing?.lat != null && arrowPing.lon != null && obsLat != null && obsLon != null
        v.setViewVisibility(R.id.mapping_arrow, if (showArrow) android.view.View.VISIBLE else android.view.View.GONE)
        if (showArrow && arrowPing != null) {
            val bearing = GeoMath.bearingDeg(obsLat!!, obsLon!!, arrowPing.lat!!, arrowPing.lon!!)
            val dist = GeoMath.distanceKm(obsLat, obsLon, arrowPing.lat!!, arrowPing.lon!!)
            v.setImageViewBitmap(R.id.mapping_arrow, mappingArrowBitmap(ctx, bearing, dist))
        }

        val config = Config(ctx)
        val monitoringEnabled = config.monitoringEnabled
        v.setTextViewText(
            R.id.interval_text,
            formatRefreshCountdown(ObserverRefresh.remainingSeconds(ctx, nowMs), monitoringEnabled)
        )
        // Podczas pobierania: statyczna ikona znika, w jej miejscu kręci się ProgressBar z tą samą ikoną.
        v.setViewVisibility(R.id.refresh_icon, if (d.busy) android.view.View.GONE else android.view.View.VISIBLE)
        v.setViewVisibility(R.id.refresh_spinner, if (d.busy) android.view.View.VISIBLE else android.view.View.GONE)

        // Wiersz Observera
        val obs = d.observer
        v.setTextViewText(R.id.obs_hash, d.observerId.ifBlank { "----" }.uppercase())
        // Ikona kopiowania: cały klucz trafia do schowka, choć na widgecie jest skrócony (…).
        v.setViewVisibility(R.id.copy_key, if (d.configured && d.observerId.isNotBlank()) android.view.View.VISIBLE else android.view.View.GONE)
        v.setOnClickPendingIntent(R.id.copy_key, CopyKeyReceiver.pendingIntent(ctx))
        v.setTextViewText(R.id.obs_name, when {
            !d.configured -> "Ustaw hash w aplikacji"
            obs?.name != null -> obs.name
            else -> "—"
        })
        v.setTextColor(R.id.m1_title, brokerHealthColor(d.mqtt1Health, d.broker1))
        v.setTextColor(R.id.m2_title, brokerHealthColor(d.mqtt2Health, d.broker2))

        val effectiveNowMs = if (monitoringEnabled) nowMs
            else config.monitoringStoppedAtMs.takeIf { it > 0L } ?: 0L
        // Progi kolorów zawsze z bieżących ustawień (zmiana w Settings działa od razu).
        val live = d.copy(orangeMin = config.orangeMin, redMin = config.redMin)
        broker(v, live, d.broker1, effectiveNowMs, monitoringEnabled, layout, d.broker1FrozenAgeMs, R.id.m1_hour, R.id.m1_total, R.id.m1_last)
        broker(v, live, d.broker2, effectiveNowMs, monitoringEnabled, layout, d.broker2FrozenAgeMs, R.id.m2_hour, R.id.m2_total, R.id.m2_last)
        return v
    }

    /** Teksty karty brokera. */
    data class BrokerTexts(val hour: String, val total: String, val last: String?, val lastLevel: Level? = null)

    fun brokerTexts(
        r: FetchResult?, compact: Boolean, frozenAgeMs: Long? = null, orangeMin: Int = 3, redMin: Int = 5
    ): BrokerTexts = when (r) {
        is FetchResult.Ok -> {
            val s = r.snapshot
            BrokerTexts(
                hour = if (compact) "${s.packetsPerHour}/h" else s.packetsPerHour.toString(),
                total = Status.formatCount(s.totalPackets).let { if (compact) "$it pkt" else it },
                last = if (s.lastPacketMs == null) "brak" else null
            )
        }
        // API niedostępne: nigdy nie udajemy LIVE. Gdy znamy ostatni wiek pakietu, pokazujemy go zamrożonego.
        else -> {
            if (r is FetchResult.Fail && frozenAgeMs != null) {
                BrokerTexts(
                    "—",
                    "—",
                    formatLastPacketAge(frozenAgeMs),
                    Status.level(frozenAgeMs, orangeMin, redMin)
                )
            } else BrokerTexts("—", "—", if (r == null) "…" else "API ✕")
        }
    }

    private fun broker(
        v: RemoteViews, d: WidgetData, r: FetchResult?, nowMs: Long, monitoringEnabled: Boolean, layout: WidgetLayout,
        frozenAgeMs: Long?, hourId: Int, totalId: Int, lastId: Int
    ) {
        val t = brokerTexts(r, compact = false, frozenAgeMs = frozenAgeMs, orangeMin = d.orangeMin, redMin = d.redMin)
        v.setTextViewText(hourId, t.hour)
        v.setTextViewText(totalId, t.total)
        when {
            r is FetchResult.Ok && r.snapshot.lastPacketMs != null -> {
                val age = r.snapshot.lastPacketAgeMs(nowMs) ?: 0L
                v.setTextViewText(lastId, formatLastPacketAge(age))
                v.setInt(lastId, "setBackgroundResource", when (Status.level(age, d.orangeMin, d.redMin)) {
                    Level.GREEN -> R.drawable.pill_green
                    Level.ORANGE -> R.drawable.pill_orange
                    else -> R.drawable.pill_red
                })
            }
            t.last != null && t.lastLevel != null -> {
                // API brokera padnięte: pokazujemy zamrożony, ostatni znany wiek pakietu.
                v.setTextViewText(lastId, t.last)
                v.setInt(lastId, "setBackgroundResource", when (t.lastLevel) {
                    Level.GREEN -> R.drawable.pill_green
                    Level.ORANGE -> R.drawable.pill_orange
                    else -> R.drawable.pill_red
                })
            }
            else -> {
                v.setTextViewText(lastId, t.last)
                v.setInt(lastId, "setBackgroundResource", when {
                    r is FetchResult.Ok -> R.drawable.pill_gray
                    r == null -> R.drawable.pill_gray
                    else -> R.drawable.pill_red
                })
            }
        }
    }

    internal fun formatRefreshCountdown(remainingSeconds: Long, enabled: Boolean): String =
        TimeFormat.countdown(remainingSeconds, enabled)

    internal fun formatLastPacketAge(ageMs: Long): String = TimeFormat.packetAge(ageMs)

    /** Tytuł MQTT: zielony = broker żyje, czerwony = broker martwy, szary = nie da się ocenić (np. brak internetu). */
    internal fun brokerHealthColor(health: Health, result: FetchResult?): Int = when (health) {
        Health.GOOD -> GREEN
        Health.BAD -> RED
        Health.UNKNOWN -> GRAY
    }

    private fun healthColor(health: Health): Int = when (health) {
        Health.GOOD -> GREEN
        Health.BAD -> RED
        Health.UNKNOWN -> GRAY
    }

    private fun liveBg(color: Int) = when (color) {
        GREEN -> R.drawable.live_green
        ORANGE -> R.drawable.live_orange
        RED -> R.drawable.live_red
        else -> R.drawable.live_gray
    }

    /** Uproszczony layout, który realnie zastępuje główny, gdy jego zbudowanie się nie powiedzie. */
    fun safe(ctx: Context, message: String): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_observer_safe)
        v.setTextViewText(R.id.safe_text, message)
        v.setOnClickPendingIntent(R.id.safe_root, ObserverRefresh.pendingIntent(ctx))
        return v
    }
}
