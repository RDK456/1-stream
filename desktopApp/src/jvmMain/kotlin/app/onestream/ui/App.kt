package app.onestream.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowState
import app.onestream.APP_NAME
import app.onestream.AppState
import app.onestream.download.DlState
import app.onestream.Screen
import app.onestream.Tab

@Composable
fun App(app: AppState, windowState: WindowState, onQuit: () -> Unit = {}) {
    val data by app.store.data.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = (maxWidth * 0.04f).coerceIn(14.dp, 56.dp)
        CompositionLocalProvider(LocalSide provides side, LocalCompact provides (maxWidth < 720.dp)) {
            Content(app, windowState, data.noticeAccepted, onQuit)
        }
    }
}

@Composable
private fun Content(app: AppState, windowState: WindowState, noticeAccepted: Boolean, onQuit: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            // Ctrl+1 to Ctrl+6 jump between the main tabs; the player has its own keys
            val n = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six).indexOf(e.key)
            if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && n in Tab.entries.indices && app.stack.isEmpty()) { app.tab = Tab.entries[n]; true } else false
        }.focusRequester(focus).focusable(),
        color = MaterialTheme.colorScheme.background,
    ) {
        AnimatedContent(
            app.stack.lastOrNull(),
            transitionSpec = {
                // Fade only: sliding a whole screen is what made navigation feel heavy
                fadeIn(Motion.fade(Motion.Ms.fast)) togetherWith fadeOut(Motion.fade(Motion.Ms.instant))
            },
            label = "screen",
        ) { top ->
            when (top) {
                is Screen.Details -> DetailsScreen(app, top)
                is Screen.Play -> PlayerScreen(app, top, windowState)
                is Screen.Shelf -> ShelfScreen(app, top)
                null -> Column(Modifier.fillMaxSize()) {
                    TopBar(app)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AnimatedContent(
                            app.tab,
                            transitionSpec = { fadeIn(Motion.fade(Motion.Ms.normal)) togetherWith fadeOut(Motion.fade(Motion.Ms.instant)) },
                            label = "tab",
                        ) { t ->
                            when (t) {
                                Tab.Search -> SearchScreen(app)
                                Tab.Library -> LibraryScreen(app)
                                Tab.History -> HistoryScreen(app)
                                Tab.Downloads -> DownloadsScreen(app)
                                Tab.Plugins -> PluginsScreen(app)
                                Tab.About -> AboutScreen(app)
                            }
                        }
                    }
                }
            }
        }
        if (app.confirmClose) {
            val running = app.downloader.jobs.count { it.state == DlState.Running }
            AlertDialog(
                onDismissRequest = { app.confirmClose = false },
                containerColor = Brand.Panel,
                shape = RoundedCornerShape(8.dp),
                title = { Text("Quit 1-stream?", fontWeight = FontWeight.SemiBold) },
                text = {
                    Text(
                        if (running > 0) "$running download${if (running > 1) "s are" else " is"} still running and will stop. You can retry them next time."
                        else "Your library and history are saved.",
                        color = Brand.Muted,
                    )
                },
                confirmButton = { GradientButton("Quit", onQuit) },
                dismissButton = { GhostButton("Stay", { app.confirmClose = false }) },
            )
        }
        if (!noticeAccepted) {
            AlertDialog(
                onDismissRequest = {},
                containerColor = Brand.Panel,
                shape = RoundedCornerShape(8.dp),
                title = { Text("Before you start", fontWeight = FontWeight.SemiBold) },
                text = {
                    Text(
                        "1-stream does not include or host any content. It plays what the plugins you install provide. " +
                            "Only install plugins from sources you trust, and only watch content you have the right to access.",
                        color = Brand.Muted,
                    )
                },
                confirmButton = { GradientButton("I understand", { app.store.acceptNotice() }) },
            )
        }
    }
}

/** Read inside AnimatedContent's transitionSpec, which cannot read composition locals directly. */
internal object LocalReduceMotionSnapshot { @Volatile var value = false }

/** Wordmark on the left, text tabs beside it; fades into the page like the Netflix header. */
@Composable
private fun TopBar(app: AppState) {
    val compact = LocalCompact.current
    Row(
        Modifier.fillMaxWidth().height(if (compact) 52.dp else 64.dp).background(Brand.Ink).padding(horizontal = LocalSide.current),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 22.dp),
    ) {
        Text(
            APP_NAME.uppercase(), color = Brand.Red, fontSize = if (compact) 20.sp else 26.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, maxLines = 1,
            modifier = Modifier.padding(end = 6.dp).clickable { app.tab = Tab.Search }.pointerHoverIcon(PointerIcon.Hand),
        )
        // The tabs scroll sideways when the window is too narrow to show all of them
        Spacer(Modifier.weight(1f))
        // Glass pill holding the tabs, at the right like Movy's header
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f)).horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 22.dp),
        ) {
            Tab.entries.forEach { t ->
                val active = if (t == Tab.Downloads) app.downloader.jobs.size else 0
                NavItem(if (active > 0) "${t.label} ($active)" else t.label, app.tab == t) { app.tab = t }
            }
        }
        app.defaultInstall?.let { Text(if (it.total > 0) "${it.phase} ${it.done}/${it.total}" else it.phase, color = Brand.Muted, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
        // One click: download (a small patch when possible) and restart into the new version
        val update = when (val st = app.updates.status) {
            is app.onestream.update.UpdateStatus.Ready -> "Restart to update"
            is app.onestream.update.UpdateStatus.Available -> "Update to ${st.feed.version}"
            else -> null
        }
        if (update != null) {
            Text(
                update, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Brand.Red)
                    .clickable { app.updates.updateNow() }
                    .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        (app.updates.status as? app.onestream.update.UpdateStatus.Downloading)?.let {
            Text("Updating ${(it.progress * 100).toInt()}%", color = Brand.Muted, maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
        // Profile square at the far right, like the Netflix header; opens Settings
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)).background(Brand.Gradient)
                .clickable { app.tab = Tab.About }.pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) { Text("1", color = Color.White, fontWeight = FontWeight.Black) }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hover by src.hoverAmount()
    Text(
        label, maxLines = 1, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = lerp(Color(0xFFB3B3B3), Color.White, if (selected) 1f else hover),
        modifier = Modifier.clickable(src, null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(vertical = 8.dp),
    )
}
