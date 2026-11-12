package pl.meshcore.observerwidget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Alerts {
    private enum class Kind(val key: String, val channel: String, private val titlePl: String, private val titleEn: String, val id: Int) {
        OBSERVER("obs", "alerts_observer", "Observer nie wysyła pakietów", "Observer is not sending packets", 1001),
        MQTT1("m1", "alerts_mqtt1", "MQTT1 nie działa", "MQTT1 is down", 1002),
        MQTT2("m2", "alerts_mqtt2", "MQTT2 nie działa", "MQTT2 is down", 1003),
        MAPPING("map", "alerts_mapping", "Ktoś mapuje w zasięgu Observera", "Someone is mapping in Observer range", 1004);

        val title: String get() = tr(titlePl, titleEn)
    }

    private val lock = Any()

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("alerts_observer", tr("Brak pakietów Observera", "Observer: no packets"), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("alerts_mqtt1", tr("Awaria MQTT1", "MQTT1 failure"), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("alerts_mqtt2", tr("Awaria MQTT2", "MQTT2 failure"), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("alerts_mapping", tr("Mapowanie w zasięgu Observera", "Mapping in Observer range"), NotificationManager.IMPORTANCE_HIGH))
    }

    fun evaluate(ctx: Context, data: WidgetData, nowMs: Long = System.currentTimeMillis()) {
        if (!Config(ctx).monitoringEnabled || !data.configured) return
        val cfg = Config(ctx)
        val assessment = AlertEngine.assess(data.observer?.let { o -> o.lastPacketMs?.minus(o.serverOffsetMs) }, nowMs, cfg.redMin, data.mqtt1Health, data.mqtt2Health)
        // Rejestr przestojów (karta Przestoje) – niezależny od włączenia powiadomień.
        val observerLast = data.observer?.let { o -> o.lastPacketMs?.minus(o.serverOffsetMs) }
        Outages.record(ctx, OutageKind.OBSERVER, assessment.observer, nowMs, startHintMs = observerLast)
        Outages.record(ctx, OutageKind.MQTT1, data.mqtt1Health, nowMs)
        Outages.record(ctx, OutageKind.MQTT2, data.mqtt2Health, nowMs)
        // Bez opóźnienia i cooldownu: alarm = natychmiastowy PUSH (raz na epizod awarii).
        val delayMs = 0L
        val cooldownMs = 0L

        // Alarm Observera: natychmiast po przekroczeniu progu czerwonego (bez opóźnienia).
        // Gdy żaden API nie odpowiada, też alarmujemy – wtedy w treści podajemy oba.
        val bothApisDown = data.observer == null &&
            data.broker1 is FetchResult.Fail && data.broker2 is FetchResult.Fail
        val observerHealth = if (bothApisDown) Health.BAD else assessment.observer
        val observerText: ((Long) -> String)? = when {
            bothApisDown -> ({ minutes ->
                tr(
                    "Brak odpowiedzi API: ${Config.displayHost(cfg.mqtt1Host)}, ${Config.displayHost(cfg.mqtt2Host)} (od ok. $minutes min).",
                    "No API response: ${Config.displayHost(cfg.mqtt1Host)}, ${Config.displayHost(cfg.mqtt2Host)} (for ~$minutes min)."
                )
            })
            data.observer != null -> ({ minutes ->
                val host = Config.displayHost(data.observer!!.host)
                tr("$host: brak pakietów od ok. $minutes min.", "$host: no packets for ~$minutes min.")
            })
            else -> null
        }
        step(ctx, Kind.OBSERVER, observerHealth, cfg.alertObserver, nowMs, delayMs = 0L, cooldownMs, observerText)
        step(ctx, Kind.MQTT1, data.mqtt1Health, cfg.alertMqtt1, nowMs, delayMs, cooldownMs, mqttText(cfg.mqtt1Host))
        step(ctx, Kind.MQTT2, data.mqtt2Health, cfg.alertMqtt2, nowMs, delayMs, cooldownMs, mqttText(cfg.mqtt2Host))
    }

    /**
     * Powiadomienie o mapowaniu: przychodzi z nazwą mapującego, gdy ktoś zacznie mapować
     * w zasięgu Observera – raz na sesję dla każdego mapującego (kolejna osoba w tej samej
     * sesji też dostaje osobny push). Nie powiadamiamy o każdym pingu (maper pinguje co 20–120 s).
     */
    fun evaluateMapping(ctx: Context, state: MappingState?, fresh: List<MapperPing>, nowMs: Long = System.currentTimeMillis()) {
        val cfg = Config(ctx)
        if (!cfg.monitoringEnabled || !cfg.alertMapping || fresh.isEmpty()) return
        synchronized(lock) {
            val prefs = ctx.applicationContext.getSharedPreferences("alert_state", Context.MODE_PRIVATE)
            val lastAt = prefs.getLong("map_last", 0L)
            // Nowa sesja = cisza dłuższa niż przerwa sesji od ostatniego pingu.
            val newSession = lastAt == 0L || nowMs - lastAt > MapperApi.SESSION_GAP_MS
            val notified = if (newSession) emptySet()
                else prefs.getString("map_notified", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()
            val newcomers = fresh.map { it.userId }.distinct().filter { it !in notified }
            // Czas ostatniego pingu zawsze odświeżamy – to granica sesji.
            prefs.edit().putLong("map_last", nowMs).apply()
            if (newcomers.isEmpty()) return
            val userId = newcomers.last()
            val nick = MapperMonitor.nick(userId) ?: tr("nieznany mapujący", "unknown mapper")
            val sessionCount = state?.pings?.size ?: 1
            val content = buildString {
                append(nick)
                append(tr(" mapuje w zasięgu Observera", " is mapping in Observer range"))
                state?.pings?.firstOrNull()?.let { first ->
                    val minutes = ((nowMs - first.tsMs) / 60_000L).coerceAtLeast(0L)
                    if (minutes > 0L) append(tr(" (sesja od ok. $minutes min, $sessionCount pingów)", " (session for ~$minutes min, $sessionCount pings)"))
                }
                state?.mapperNicks?.takeIf { it.size > 1 }?.let {
                    append(tr(". Mapują teraz: ", ". Mapping now: ") + it.joinToString(", "))
                }
                fresh.lastOrNull { it.userId == userId }?.let { ping ->
                    ping.observerSnrDb?.let { append(tr(". SNR u Observera: ${fmt(it)} dB", ". SNR at Observer: ${fmt(it)} dB")) }
                    ping.rssiDbm?.let { append(", RSSI: ${fmt(it)} dBm") }
                }
            }
            if (notifyMapping(ctx, Kind.MAPPING.id, tr("Mapowanie: $nick", "Mapping: $nick"), content)) {
                // Zapamiętujemy wszystkich nowych naraz, żeby nie spamić przy kolejnych pingach.
                prefs.edit().putString("map_notified", (notified + newcomers).joinToString(",")).apply()
            }
        }
    }

    /** Identyfikator powiadomienia o mapowaniu przy RPTn (osobne dla każdego slotu). */
    private fun rptNotificationId(slot: Int) = 1010 + slot

    /**
     * Powiadomienie o mapowaniu przy moim repeaterze RPTn: jak dla Observera – raz na sesję
     * dla każdego mapującego, osobny stan sesji dla każdego slotu.
     * Treść: „Iron mesh mapuje 1,2 km SW od „Paderewa Dach RPT”.”
     */
    fun evaluateRptMapping(ctx: Context, rpt: RptMapping, fresh: List<MapperPing>, nowMs: Long = System.currentTimeMillis()) {
        val cfg = Config(ctx)
        if (!cfg.monitoringEnabled || !cfg.alertMapping || fresh.isEmpty()) return
        synchronized(lock) {
            val prefs = ctx.applicationContext.getSharedPreferences("alert_state", Context.MODE_PRIVATE)
            val key = "rpt${rpt.slot}"
            val lastAt = prefs.getLong("${key}_map_last", 0L)
            val sameRpt = prefs.getString("${key}_map_id", null) == rpt.shortId
            val newSession = !sameRpt || lastAt == 0L || nowMs - lastAt > MapperApi.SESSION_GAP_MS
            val notified = if (newSession) emptySet()
                else prefs.getString("${key}_map_notified", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()
            val newcomers = fresh.map { it.userId }.distinct().filter { it !in notified }
            prefs.edit().putLong("${key}_map_last", nowMs).putString("${key}_map_id", rpt.shortId).apply()
            if (newcomers.isEmpty()) return
            val userId = newcomers.last()
            val nick = MapperMonitor.nick(userId) ?: tr("nieznany mapujący", "unknown mapper")
            val where = rpt.name?.let { "„$it”" } ?: "${rpt.label} (${rpt.shortId.take(4).uppercase()}…)"
            val content = buildString {
                append(nick)
                append(tr(" mapuje", " is mapping"))
                val ping = fresh.lastOrNull { it.userId == userId && it.lat != null && it.lon != null }
                val lat = rpt.state.observerLat
                val lon = rpt.state.observerLon
                if (ping != null && lat != null && lon != null) {
                    val bearing = GeoMath.bearingDeg(lat, lon, ping.lat!!, ping.lon!!)
                    val dist = GeoMath.distanceKm(lat, lon, ping.lat, ping.lon!!)
                    append(tr(" ${GeoMath.km(dist)} ${GeoMath.compassLabel(bearing)} od $where", " ${GeoMath.km(dist)} ${GeoMath.compassLabel(bearing)} from $where"))
                } else {
                    append(tr(" przy $where", " near $where"))
                }
                fresh.lastOrNull { it.userId == userId }?.observerSnrDb?.let { append(tr(". SNR u RPT: ${fmt(it)} dB", ". SNR at RPT: ${fmt(it)} dB")) }
                append('.')
            }
            if (notifyMapping(ctx, rptNotificationId(rpt.slot), tr("Mapowanie przy ${rpt.label}", "Mapping near ${rpt.label}"), content)) {
                prefs.edit().putString("${key}_map_notified", (notified + newcomers).joinToString(",")).apply()
            }
        }
    }

    private fun fmt(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)

    private fun notifyMapping(ctx: Context, id: Int, title: String, content: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        ensureChannels(ctx)
        val openIntent = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, Kind.MAPPING.channel)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        return try {
            NotificationManagerCompat.from(ctx).notify(id, notification)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Treść dla alarmów MQTT: nazwa hosta i od jak dawna brak połączenia. */
    private fun mqttText(host: String): (Long) -> String = { minutes ->
        tr(
            "${Config.displayHost(host)}: brak połączenia MQTT od ok. $minutes min.",
            "${Config.displayHost(host)}: no MQTT connection for ~$minutes min."
        )
    }

    fun clearAll(ctx: Context) {
        val app = ctx.applicationContext
        app.getSharedPreferences("alert_state", Context.MODE_PRIVATE).edit().clear().apply()
        NotificationManagerCompat.from(app).apply {
            cancel(1001)
            cancel(1002)
            cancel(1003)
            cancel(1004)
            for (slot in 1..Config.RPT_SLOTS) cancel(rptNotificationId(slot))
        }
    }

    private fun step(ctx: Context, kind: Kind, health: Health, enabled: Boolean, nowMs: Long, delayMs: Long, cooldownMs: Long, text: ((Long) -> String)? = null) {
        synchronized(lock) {
            val prefs = ctx.applicationContext.getSharedPreferences("alert_state", Context.MODE_PRIVATE)
            val key = kind.key
            val previous = KeyState(
                bad = prefs.getBoolean("${key}_bad", false),
                badSinceMs = prefs.getLong("${key}_since", 0),
                notified = prefs.getBoolean("${key}_notified", false),
                lastNotifiedMs = prefs.getLong("${key}_last", 0)
            )
            val (next, action) = AlertEngine.step(previous, health, enabled, nowMs, delayMs, cooldownMs)

            var stateToPersist = next
            when (action) {
                AlertAction.NOTIFY -> {
                    if (!notify(ctx, kind, next.badSinceMs, nowMs, text)) {
                        // Keep the alert pending; a later refresh can retry after permission is granted.
                        stateToPersist = next.copy(notified = false, lastNotifiedMs = previous.lastNotifiedMs)
                    }
                }
                AlertAction.CLEAR -> NotificationManagerCompat.from(ctx).cancel(kind.id)
                AlertAction.NONE -> Unit
            }

            prefs.edit()
                .putBoolean("${key}_bad", stateToPersist.bad)
                .putLong("${key}_since", stateToPersist.badSinceMs)
                .putBoolean("${key}_notified", stateToPersist.notified)
                .putLong("${key}_last", stateToPersist.lastNotifiedMs)
                .apply()
        }
    }

    private fun notify(ctx: Context, kind: Kind, sinceMs: Long, nowMs: Long, text: ((Long) -> String)?): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        ensureChannels(ctx)
        val openIntent = PendingIntent.getActivity(
            ctx, kind.id, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val elapsedMinutes = ((nowMs - sinceMs) / 60_000L).coerceAtLeast(0)
        val content = text?.invoke(elapsedMinutes) ?: tr("Brak pakietów od ok. $elapsedMinutes min.", "No packets for ~$elapsedMinutes min.")
        val notification = NotificationCompat.Builder(ctx, kind.channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(kind.title)
            .setContentText(content)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        return try {
            NotificationManagerCompat.from(ctx).notify(kind.id, notification)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}
