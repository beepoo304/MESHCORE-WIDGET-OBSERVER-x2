package pl.meshcore.observerwidget

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RConfig

@RunWith(RobolectricTestRunner::class)
@RConfig(sdk = [34])
class CopyKeyReceiverTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val key = "f4809c7817bc777124f638d32008af197415ba1240c36c186f6ec80619775c68"

    @Test fun copiesWholeKeyNotTheShortenedText() {
        Config(ctx).observerId = key
        CopyKeyReceiver().onReceive(ctx, Intent(CopyKeyReceiver.ACTION_COPY_KEY))
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = cm.primaryClip!!.getItemAt(0).text.toString()
        assertEquals(64, copied.length)
        assertEquals(key.uppercase(), copied)
    }

    @Test fun noKeyNothingToCopy() = assertNull(CopyKeyReceiver.fullKey("  "))
}
