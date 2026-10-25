package app.orchard.tv.ui.control

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.home.MenuRow
import app.orchard.tv.ui.home.MiniTile
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.launch

@Composable
private fun SettingsSheet(visible: Boolean, title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(140)), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().dismissOnTap(onDismiss).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(760.dp).height(480.dp).consumeTaps().liquidGlass(28.dp).padding(24.dp)) {
                BasicText(title, style = Type.LargeTitle)
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

/** Stage moves locally; only Save changes the owner's stored arrangement. */
@Composable
fun AppOrderPanel(visible: Boolean, apps: List<AppEntry>, initialKey: String?, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var staged by remember { mutableStateOf<List<String>>(emptyList()) }
    var moving by remember { mutableStateOf<String?>(null) }
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val byKey = apps.associateBy { it.key }
    val shown = staged.mapNotNull(byKey::get)
    LaunchedEffect(visible) {
        if (visible) {
            staged = apps.map { it.key }
            moving = initialKey?.takeIf { it in staged }
            val index = staged.indexOf(initialKey).coerceAtLeast(0)
            listState.scrollToItem(index)
            withFrameNanos { }
            staged.getOrNull(index)?.let { runCatching { requester(it).requestFocus() } }
        }
    }
    fun back() { if (moving != null) moving = null else onDismiss() }
    SettingsSheet(visible, stringResource(R.string.lumen_app_order), ::back) {
        BasicText(stringResource(if (moving == null) R.string.lumen_order_hint else R.string.lumen_order_moving), style = Type.Callout)
        Spacer(Modifier.height(10.dp))
        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            itemsIndexed(shown, key = { _, app -> app.key }) { index, app ->
                Pressable(
                    onClick = { moving = if (moving == app.key) null else app.key },
                    focusRequester = requester(app.key), focusedScale = 1.015f,
                    onKey = { e ->
                        val code = e.nativeKeyEvent.keyCode
                        if (moving != app.key || code !in listOf(NativeKeyEvent.KEYCODE_DPAD_UP, NativeKeyEvent.KEYCODE_DPAD_DOWN, NativeKeyEvent.KEYCODE_DPAD_LEFT, NativeKeyEvent.KEYCODE_DPAD_RIGHT)) false
                        else {
                            if (e.type == KeyEventType.KeyDown && (code == NativeKeyEvent.KEYCODE_DPAD_UP || code == NativeKeyEvent.KEYCODE_DPAD_DOWN)) {
                                val to = (index + if (code == NativeKeyEvent.KEYCODE_DPAD_UP) -1 else 1).coerceIn(0, shown.lastIndex)
                                if (to != index) {
                                    val keys = shown.map { it.key }.toMutableList()
                                    keys.add(to, keys.removeAt(index)); staged = keys
                                    scope.launch {
                                        val visibleItems = listState.layoutInfo.visibleItemsInfo
                                        if (to !in (visibleItems.firstOrNull()?.index ?: 0)..(visibleItems.lastOrNull()?.index ?: 0)) listState.scrollToItem(to)
                                        withFrameNanos { }; runCatching { requester(app.key).requestFocus() }
                                    }
                                }
                            }
                            true
                        }
                    },
                ) { state ->
                    val fg = if (state.focused) Palette.OnFocus else Palette.Label
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (state.focused) Palette.Focus else Palette.Fill).height(52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        BasicText((index + 1).toString(), style = Type.Callout.copy(color = fg), modifier = Modifier.width(22.dp))
                        MiniTile(app, false, Modifier.size(64.dp, 36.dp))
                        BasicText(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = Type.Body.copy(color = fg), modifier = Modifier.weight(1f))
                        if (moving == app.key) GlyphIcon(Glyph.Move, fg, 20.dp)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        MenuRow(Glyph.Check, stringResource(R.string.lumen_order_save), onClick = { moving = null; onSave(shown.map { it.key }) })
    }
}
