package pl.meshcore.observerwidget

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private lateinit var dash: LinearLayout
    private var dashboardData: WidgetData? = null
    private var showMapping = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showMapping = intent?.getBooleanExtra(EXTRA_SHOW_MAPPING, false) == true
        Alerts.ensureChannels(this)
        RefreshWorker.sync(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111315.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(0xFF171A1C.toInt())
            setBackgroundResource(R.drawable.card_outline)
            minimumHeight = dp(56)
        }

        toolbar.addView(TextView(this).apply {
            text = "Observer"
            textSize = 20f
            setTextColor(WidgetRenderer.GREEN)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        toolbar.addView(Button(this).apply {
            text = "MAP"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF1F282B.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { startActivity(Intent(this@MainActivity, MapTrackActivity::class.java)) }
        })

        toolbar.addView(Button(this).apply {
            text = "Settings"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF1F282B.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })

        root.addView(toolbar)

        dash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFF111315.toInt())
        }

        root.addView(
            ScrollView(this).apply {
                setBackgroundColor(0xFF111315.toInt())
                addView(dash)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(root)
        renderDashboard(ObserverRefresh.latest())

        handler.postDelayed(object : Runnable {
            override fun run() {
                renderDashboard(ObserverRefresh.latest())
                handler.postDelayed(this, 1_000L)
            }
        }, 1_000L)
    }

    private fun renderDashboard(data: WidgetData?) {
        val d = data ?: return
        dash.removeAllViews()

        dash.addView(line("Monitoring ${if (Config(this).monitoringEnabled) "enabled" else "disabled"}", 16f, WidgetRenderer.GREEN, true))

        if (d.mapping != null) {
            val state = if (d.mapping.active) "active" else "inactive"
            dash.addView(card("MAP", "Mapping $state", if (d.mapping.active) WidgetRenderer.GREEN else WidgetRenderer.GRAY))
        }

        dash.addView(card("Observer", d.observer?.name ?: "Observer", Color.WHITE))
        dash.addView(card("Status", if (d.observer == null) "API ✕" else "OK", if (d.observer == null) WidgetRenderer.RED else WidgetRenderer.GREEN))
        dash.addView(card("MQTT1", Config.displayHost(d.mqtt1Host), WidgetRenderer.GREEN))
        dash.addView(card("MQTT2", Config.displayHost(d.mqtt2Host), WidgetRenderer.GREEN))

        val fetchedAt = d.observer?.fetchedAtMs ?: System.currentTimeMillis()
        dash.addView(
            line(
                "Pobrano ${DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(fetchedAt).atZone(ZoneId.systemDefault()))}",
                11f,
                WidgetRenderer.GRAY
            )
        )
    }

    private fun card(title: String, value: String, color: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_outline)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(WidgetRenderer.GREEN)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = value
                setTextColor(color)
            })
        }

    private fun line(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            this.textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SHOW_MAPPING = "pl.meshcore.observerwidget.EXTRA_SHOW_MAPPING"

        fun mappingIntent(ctx: Context): PendingIntent =
            PendingIntent.getActivity(
                ctx,
                4,
                Intent(ctx, MainActivity::class.java)
                    .putExtra(EXTRA_SHOW_MAPPING, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}

