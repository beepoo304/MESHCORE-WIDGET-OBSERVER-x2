package pl.meshcore.observerwidget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast

/** Ikona kopiowania na widgecie: kopiuje do schowka PEŁNY klucz Observera (64 znaki), choć widget pokazuje skrót. */
class CopyKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = fullKey(Config(context).observerId) ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(tr("Klucz Observera", "Observer key"), key))
        // Od Androida 13 system sam pokazuje potwierdzenie skopiowania.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, tr("Skopiowano klucz Observera", "Observer key copied"), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_COPY_KEY = "pl.meshcore.observerwidget.COPY_KEY"

        /** Pełny klucz w zapisie widocznym na widgecie (wielkie litery) albo null, gdy brak klucza. */
        fun fullKey(observerId: String): String? = observerId.trim().takeIf { it.isNotEmpty() }?.uppercase()

        fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 3, Intent(ctx, CopyKeyReceiver::class.java).setAction(ACTION_COPY_KEY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
