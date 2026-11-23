package com.kevxngg.monitorroot

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Short message at the bottom of the window after an action ("Task ended", "Values restored"), or
 * explaining why a button is disabled. Can be called from any thread.
 */
object Feedback {
    class Message(val text: String, val ok: Boolean, val id: Int)

    var current by mutableStateOf<Message?>(null)
        private set
    private var next by mutableIntStateOf(0)
    private val main = Handler(Looper.getMainLooper())

    /** [ok] = an action finished (check icon); false = information (why something cannot be done). */
    fun show(text: String, ok: Boolean = true) {
        main.post {
            next++
            current = Message(text, ok, next)
        }
    }

    fun dismiss(id: Int) {
        if (current?.id == id) current = null
    }
}

/** Shows [Feedback] messages in the style of the current interface. */
@Composable
fun FeedbackHost(modifier: Modifier = Modifier) {
    val win = LocalWin.current
    val msg = Feedback.current
    LaunchedEffect(msg?.id) {
        val m = msg ?: return@LaunchedEffect
        delay(if (m.ok) 2500 else 4000)
        Feedback.dismiss(m.id)
    }
    // The last message stays drawn while it fades out
    var shown by androidx.compose.runtime.remember { mutableStateOf<Feedback.Message?>(null) }
    if (msg != null) shown = msg
    AnimatedVisibility(
        visible = msg != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val m = shown ?: return@AnimatedVisibility
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .widthIn(max = 420.dp)
                .shadow(8.dp, corner(8))
                .clip(corner(8))
                .background(win.card)
                .border(1.dp, win.border, corner(8))
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (m.ok) Icons.Outlined.CheckCircle else Icons.Outlined.Info, null,
                tint = win.accent, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(m.text, fontSize = 13.sp, color = win.text)
        }
    }
}
