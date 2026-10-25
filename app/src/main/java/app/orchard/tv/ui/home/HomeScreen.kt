package app.orchard.tv.ui.home

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.orchard.tv.CcPage
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.apps.ALL_APPS_KEY
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.ui.control.ControlCenter
import app.orchard.tv.ui.wallpaper.WallpaperSheet
import app.orchard.tv.HOME_COLUMNS
import app.orchard.tv.settings.AppNameMode
import app.orchard.tv.ui.control.AppOrderPanel
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val COLUMNS = HOME_COLUMNS

/** Grid geometry for the screen at hand. On a 1080p TV this is a 960×540 dp canvas. */
private class GridMetrics(screenW: Dp, screenH: Dp, private val homeRows: Int, nameHeight: Dp, namesShown: Boolean, private val scrolling: Boolean) {
    val margin = 56.dp
    val gap = 22.dp
    val tileW = (screenW - margin * 2 - gap * (COLUMNS - 1)) / COLUMNS
    val tileH = tileW * 9f / 16f
    val pitchX = tileW + gap
    val labelSpace = if (namesShown) AppLabelGap + nameHeight else 0.dp
    val pitchY = tileH + if (namesShown) labelSpace + 10.dp else 32.dp

    /**
     * The top row sits a little past the middle, under the shelf, as on tvOS. In one-row mode it
     * sits low, with only the top of the second row showing under the focused app's name.
     */
    val topRowY = screenH - 40.dp - labelSpace - tileH - pitchY * (homeRows - 1)

    /** Top of the shelf clock, in the standard layout. */
    val clockY = screenH * 0.555f - 196.dp

    /** Where a row rests once you go down into the grid: the row above stays in view. */
    val restY = screenH * 0.37f
    val screenH = screenH

    fun slot(index: Int) = if (scrolling) Pair(margin + pitchX * index, topRowY)
        else Pair(margin + pitchX * (index % COLUMNS), topRowY + pitchY * (index / COLUMNS))

    fun scrollFor(row: Int, rows: Int): Dp {
        if (row < homeRows) return 0.dp
        val wanted = topRowY + pitchY * row - restY
        val bottom = topRowY + pitchY * rows + 36.dp - screenH
        return minOf(wanted, maxOf(bottom, topRowY + pitchY - restY))
    }
}

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onOpen: (Intent) -> Unit,
    onAppInfo: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onStartScreensaver: () -> Unit,
    onResume: (app.orchard.tv.history.HistoryItem) -> Unit,
) {
    // Preserve the configured rows; the upstream scrolling dock is an optional layout.
    val scrollingHome = AerialSettings.scrollingHome
    val apps = vm.dock
    val history = vm.history
    val hero = history.getOrNull(vm.heroIndex.coerceIn(0, (history.size - 1).coerceAtLeast(0)))
    val overlay = vm.overlay
    val moving = vm.movingKey
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val now by rememberNow()
    val scope = rememberCoroutineScope()

    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val pillRequester = remember { FocusRequester() }
    val heroRequester = remember { FocusRequester() }
    var heroFocused by remember { mutableStateOf(false) }

    var focusedKey by remember { mutableStateOf<String?>(null) }
    var pillFocused by remember { mutableStateOf(false) }
    var lastFocusedIndex by remember { mutableStateOf(0) }
    val focusedIndex = apps.indexOfFirst { it.key == focusedKey }
    val focusedRow = if (scrollingHome || pillFocused || focusedIndex < 0) 0 else focusedIndex / COLUMNS
    val gridEnabled = overlay == Overlay.None

    // Grid scroll offset (px). The remote sets it from the focused row; a finger drags it freely
    // (issue #60), and then it stays where the finger left it until the next key press.
    val scroll = remember { Animatable(0f) }
    var dragged by remember { mutableStateOf(false) }
    val movingNow by rememberUpdatedState(moving)

    fun focusApp(index: Int) {
        val app = apps.getOrNull(index.coerceIn(0, (apps.size - 1).coerceAtLeast(0))) ?: return
        runCatching { requester(app.key).requestFocus() }
    }

    // First focus once the apps are in; afterwards, put focus back wherever it was when an overlay closes.
    LaunchedEffect(vm.loaded, overlay) {
        if (!vm.loaded || overlay != Overlay.None) return@LaunchedEffect
        withFrameNanos { }
        val index = apps.indexOfFirst { it.key == focusedKey }
        if (pillFocused && focusedKey != null) runCatching { pillRequester.requestFocus() }
        else if (focusedKey == null && history.isNotEmpty()) runCatching { heroRequester.requestFocus() }
        else if (heroFocused && history.isNotEmpty()) runCatching { heroRequester.requestFocus() }
        else focusApp(if (index >= 0) index else 0)
    }
    // History arrives after the apps: the first time it does, Home starts on the title to resume.
    var heroGreeted by remember { mutableStateOf(false) }
    LaunchedEffect(history.isNotEmpty(), vm.loaded) {
        if (heroGreeted || history.isEmpty() || !vm.loaded || overlay != Overlay.None) return@LaunchedEffect
        heroGreeted = true
        withFrameNanos { }
        runCatching { heroRequester.requestFocus() }
    }
    // An app vanished from under the focus (uninstalled or hidden): take its neighbour.
    LaunchedEffect(apps) {
        if (focusedKey != null && apps.none { it.key == focusedKey } && overlay == Overlay.None) {
            withFrameNanos { }
            focusApp(lastFocusedIndex)
        }
        // While moving, focus follows the app to its new slot.
        moving?.let { withFrameNanos { }; runCatching { requester(it).requestFocus() } }
    }
    // Moving starts from the menu, which is still on screen for a frame: focus the app once it is gone.
    LaunchedEffect(moving) {
        val key = moving ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester(key).requestFocus() }
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    // A toast or a system dialog takes the window's focus for a moment; when it comes back, Android
    // hands it to the top-most control (the status pill). Put it back where it was instead.
    // Where focus was when the window lost it: "hero", "pill", or an app's key.
    var focusBeforeBlur by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(windowFocused) {
        if (!windowFocused) {
            focusBeforeBlur = when {
                heroFocused -> "hero"
                pillFocused -> "pill"
                else -> focusedKey
            }
            return@LaunchedEffect
        }
        val was = focusBeforeBlur ?: return@LaunchedEffect
        focusBeforeBlur = null
        if (overlay != Overlay.None || moving != null || vm.homeResetPending) return@LaunchedEffect
        withFrameNanos { }
        when (was) {
            "pill" -> Unit
            "hero" -> if (history.isNotEmpty()) runCatching { heroRequester.requestFocus() }
            else -> focusApp(apps.indexOfFirst { it.key == was }.coerceAtLeast(0))
        }
    }
    LaunchedEffect(windowFocused, vm.homeResetPending, vm.loaded) {
        if (windowFocused && vm.homeResetPending && vm.loaded) {
            withFrameNanos { }
            dragged = false
            if (history.isNotEmpty()) runCatching { heroRequester.requestFocus() } else focusApp(0)
            vm.homeResetPending = false
        }
    }

    BackHandler(enabled = overlay == Overlay.None && moving == null) {
        // tvOS: Back from inside the grid returns to the top row; Back on the top row starts the
        // screensaver. A grid scrolled by touch counts as being inside it.
        val scrolled = dragged && scroll.value > 1f
        dragged = false
        // Handled even when that is turned off: unhandled, Back would close Home itself.
        if (focusedRow > 0 || scrolled) focusApp(focusedIndex.coerceAtLeast(0) % COLUMNS)
        else if (AerialSettings.backStartsScreensaver) onStartScreensaver()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                // The remote takes the grid back from the finger: scroll follows focus again.
                // Back leaves it to BackHandler, which needs to know the grid was dragged
                // (gesture navigation sends Back as a key too).
                if (e.nativeKeyEvent.keyCode != NativeKeyEvent.KEYCODE_BACK) dragged = false
                // Moving takes the remote here rather than on the moving tile: the tile does not
                // always get focus back when the menu closes, and focus then landed on the first
                // tile, which moved focus instead of the app (#58).
                if (moving != null) {
                    val code = e.nativeKeyEvent.keyCode
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent code != NativeKeyEvent.KEYCODE_HOME
                    when (code) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> vm.moveBy(-1)
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> vm.moveBy(1)
                        NativeKeyEvent.KEYCODE_DPAD_UP -> if (!scrollingHome) vm.moveBy(-COLUMNS)
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> if (!scrollingHome) vm.moveBy(COLUMNS)
                        NativeKeyEvent.KEYCODE_BACK, NativeKeyEvent.KEYCODE_DPAD_CENTER,
                        NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> vm.endMove()
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    NativeKeyEvent.KEYCODE_MENU -> {
                        // On an app tile, Menu opens that app's menu (AppTile): remotes whose held
                        // OK never reads as held had no other way to it (issue #28). Control
                        // Center stays one press up, on the status pill, where Menu still opens it.
                        if (overlay == Overlay.None && !pillFocused && focusedIndex >= 0) return@onPreviewKeyEvent false
                        vm.overlay = if (overlay is Overlay.ControlCenter) Overlay.None else Overlay.ControlCenter()
                        true
                    }
                    NativeKeyEvent.KEYCODE_TV_INPUT -> {
                        vm.overlay = Overlay.ControlCenter(CcPage.Inputs)
                        true
                    }
                    else -> false
                }
            },
    ) {
        val rows = if (scrollingHome) 1 else (apps.size + COLUMNS - 1) / COLUMNS
        val nameHeight = appLabelHeight
        val namesShown = AerialSettings.appNameMode != AppNameMode.Hidden
        val metrics = remember(maxWidth, maxHeight, rows, nameHeight, namesShown, scrollingHome) {
            GridMetrics(maxWidth, maxHeight, rows.coerceAtLeast(1), nameHeight, namesShown, scrollingHome)
        }
        val dockStartIndex = if (scrollingHome) {
            ((if (focusedIndex >= 0) focusedIndex else lastFocusedIndex.coerceIn(0, apps.lastIndex)) - (COLUMNS - 1)).coerceAtLeast(0)
        } else 0
        val dockShift by animateDpAsState(
            metrics.pitchX * dockStartIndex,
            if (reduced) tween(0) else spring(dampingRatio = 1f, stiffness = 300f), label = "dock-shift",
        )
        val collapsePx = with(density) { (metrics.topRowY - metrics.restY).coerceAtLeast(1.dp).toPx() }

        val target = with(density) { metrics.scrollFor(focusedRow, rows).toPx() }
        val maxScroll = with(density) { metrics.scrollFor(rows - 1, rows).toPx() }.coerceAtLeast(0f)
        LaunchedEffect(maxScroll) { scroll.updateBounds(0f, maxScroll) }
        val pastShelf by remember(collapsePx) { derivedStateOf { scroll.value > collapsePx / 2 } }
        val decay = rememberSplineBasedDecay<Float>()
        LaunchedEffect(target, dragged) {
            if (dragged) return@LaunchedEffect
            if (reduced) scroll.snapTo(target)
            // A spring keeps its velocity when a quick second press retargets it mid-flight.
            else scroll.animateTo(target, spring(dampingRatio = 1f, stiffness = 170f))
        }

        val backdropBlur by animateDpAsState(
            if (overlay is Overlay.ControlCenter || overlay is Overlay.HiddenApps) 30.dp else 0.dp,
            tween(320, easing = Motion.EaseOut), label = "backdrop-blur",
        )

        val ambientApp = apps.getOrNull(focusedIndex) ?: apps.firstOrNull()

        val dragState = rememberDraggableState { delta ->
            dragged = true
            scope.launch(start = CoroutineStart.UNDISPATCHED) { scroll.snapTo(scroll.value - delta) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .draggable(
                    dragState,
                    Orientation.Vertical,
                    enabled = gridEnabled && rows > 1,
                    onDragStopped = { velocity ->
                        if (!reduced) scroll.animateDecay(-velocity, decay)
                    },
                )
                // While moving an app, a tap on empty space ends the move, as OK does.
                .pointerInput(Unit) { detectTapGestures { if (movingNow != null) vm.endMove() } }
                .graphicsLayer {
                    val r = backdropBlur.toPx()
                    renderEffect = if (r > 0.5f && Build.VERSION.SDK_INT >= 31) BlurEffect(r, r, TileMode.Clamp) else null
                },
        ) {
            // The glass plate under the top row, as wide as the apps in it (tvOS 26).
            val topCount = apps.size.coerceAtMost(COLUMNS)
            val plate = if (topCount == 0) null else with(density) {
                Rect(
                    (metrics.margin - PlatePadH).toPx(),
                    (metrics.topRowY - PlatePadV).toPx(),
                    (metrics.margin + metrics.pitchX * (topCount - 1) + metrics.tileW + PlatePadH).toPx(),
                    (metrics.topRowY + metrics.pitchY * (rows - 1) + metrics.tileH + metrics.labelSpace + PlatePadV).toPx(),
                )
            }
            val cover = heroCover(hero)
            // tvOS lets the featured picture drift closer while it waits; once, then it rests.
            val push = remember { Animatable(1f) }
            LaunchedEffect(cover) {
                push.snapTo(1f)
                if (cover != null && !reduced) push.animateTo(1.06f, tween(14_000, easing = Motion.EaseInOut))
            }
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = push.value; scaleY = push.value }) {
            HomeBackground(
                // The title being resumed fills the screen; without one, the chosen wallpaper (in
                // "Follow App" mode, light from the focused app).
                backdrop = cover ?: Wallpapers.backdrop ?: ambientApp?.art?.backdrop,
                frosted = Wallpapers.frosted.takeIf { cover == null && Wallpapers.backdrop != null },
                plate = plate,
                scroll = { scroll.value },
                collapse = collapsePx,
                plateOpacity = 1f - AerialSettings.appBarTransparency / 100f,
            )
            }

            Box(Modifier.fillMaxSize().offset { IntOffset(0, -scroll.value.roundToInt()) }) {
                if (cover != null) HeroShade()
                if (hero != null) {
                    HeroInfo(
                        item = hero,
                        enabled = gridEnabled && moving == null,
                        focusRequester = heroRequester,
                        onFocused = { heroFocused = it; if (it) { pillFocused = false } },
                        onStep = { d -> vm.heroIndex = (vm.heroIndex + d + history.size) % history.size },
                        onDown = { focusApp(lastFocusedIndex.coerceIn(0, apps.size - 1)) },
                        onUp = { runCatching { pillRequester.requestFocus() } },
                        onClick = { onResume(hero) },
                        modifier = Modifier.padding(start = metrics.margin, top = 150.dp),
                    )
                }
                if (history.size > 1) {
                    HeroPager(
                        count = history.size,
                        index = vm.heroIndex,
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset { IntOffset(0, (metrics.topRowY - 66.dp).roundToPx()) },
                    )
                }

                // The scrolling dock shares the glass plate's bounds, including its rounded corners.
                // Draw only six apps, but keep all tiles composed so directional focus can advance.
                val viewport = plate.takeIf { scrollingHome }
                Box(
                    if (viewport == null) Modifier.fillMaxSize() else with(density) {
                        Modifier
                            .offset { IntOffset(viewport.left.roundToInt(), viewport.top.roundToInt()) }
                            .size(viewport.width.toDp(), viewport.height.toDp())
                            .clip(RoundedCornerShape(PlateCorner))
                    },
                ) {
                    apps.forEachIndexed { index, app ->
                        key(app.key) {
                            val (x, y) = metrics.slot(index)
                            val slotPx = with(density) {
                                IntOffset(
                                    x.roundToPx() - (viewport?.left?.roundToInt() ?: 0),
                                    y.roundToPx() - (viewport?.top?.roundToInt() ?: 0),
                                )
                            }
                            val slot = animateIntOffsetAsState(
                                slotPx,
                                spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                                label = "slot",
                            )
                            AppTile(
                                app = app,
                                width = metrics.tileW,
                                height = metrics.tileH,
                                focusRequester = requester(app.key),
                                enabled = gridEnabled,
                                moving = moving == app.key,
                                appearDelayMs = (index * 28).coerceAtMost(320),
                                onFocused = {
                                    heroFocused = false
                                    focusedKey = app.key
                                    lastFocusedIndex = index
                                    pillFocused = false
                                },
                                onClick = { bounds ->
                                    val mover = moving
                                    if (mover == null) {
                                        if (app.key == ALL_APPS_KEY) vm.overlay = Overlay.AllApps else onLaunch(app, bounds)
                                    }
                                    // While moving, tapping a tile puts the app there; tapping the app
                                    // itself is done.
                                    else if (mover == app.key) vm.endMove()
                                    else {
                                        vm.moveBy(index - apps.indexOfFirst { it.key == mover })
                                        runCatching { requester(mover).requestFocus() }
                                    }
                                },
                                onLongPress = { bounds ->
                                    if (moving == null && app.key != ALL_APPS_KEY) vm.overlay = Overlay.AppMenu(app.key, bounds ?: Rect.Zero)
                                },
                                modifier = Modifier
                                    .offset { IntOffset(slot.value.x - dockShift.roundToPx(), slot.value.y) }
                                    .drawWithContent {
                                        if (!scrollingHome || index in dockStartIndex until dockStartIndex + COLUMNS) drawContent()
                                    }
                                    .focusProperties {
                                        // Follow app order across rows; wrap at either end of Home.
                                        left = requester(apps[(index + apps.lastIndex) % apps.size].key)
                                        right = requester(apps[(index + 1) % apps.size].key)
                                        up = if (!scrollingHome && index >= COLUMNS) requester(apps[index - COLUMNS].key)
                                            else if (hero != null) heroRequester else pillRequester
                                        down = if (scrollingHome) FocusRequester.Cancel else apps.getOrNull(index + COLUMNS)?.let { requester(it.key) } ?: FocusRequester.Cancel
                                    },
                            )
                        }
                    }
                }
            }
        }

        // Rows that scroll up under the status pill fade out instead of colliding with it.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .graphicsLayer { alpha = (scroll.value / collapsePx).coerceIn(0f, 1f) },
        ) {
            drawRect(Brush.verticalGradient(0f to Color.Black, 0.5f to Color.Black.copy(alpha = 0.82f), 1f to Color.Transparent))
        }

        // The pill holds only the network and Control Center. The time joins it once the clock
        // above the top row has scrolled away, so the screen never shows the time twice. One-row
        // Home with the clock on the right puts the time and weather just left of the pill.
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 28.dp, end = metrics.margin - 14.dp)
                .graphicsLayer { alpha = if (overlay is Overlay.ControlCenter) 0f else 1f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(
                now = now,
                network = vm.network,
                showTime = false,
                enabled = gridEnabled && moving == null,
                focusRequester = pillRequester,
                modifier = Modifier.focusProperties {
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                    up = FocusRequester.Cancel
                    down = if (hero != null) heroRequester else apps.getOrNull(if (scrollingHome) lastFocusedIndex else lastFocusedIndex % COLUMNS)
                        ?.let { requester(it.key) } ?: FocusRequester.Cancel
                },
                onFocused = { gained ->
                    // The window hands its first focus to the top-most control; Home starts below it.
                    if (gained) {
                        if (focusedKey == null && !heroFocused) {
                            // Home starts on the title to resume when there is one, else the first app.
                            if (history.isNotEmpty()) runCatching { heroRequester.requestFocus() }
                            else if (apps.isNotEmpty()) focusApp(0)
                        } else {
                            pillFocused = true
                        }
                    }
                },
                onClick = {
                    // A tap focuses the pill and opens Control Center in the same frame, before
                    // onFocused hears of it: note it here so focus comes back to the pill.
                    pillFocused = true
                    vm.overlay = Overlay.ControlCenter()
                },
            )
        }

        TimeWeatherChip(
            now = now,
            modifier = Modifier
                .align(if (AerialSettings.clockRight) Alignment.TopEnd else Alignment.TopStart)
                .padding(start = if (AerialSettings.clockRight) 0.dp else metrics.margin - 14.dp,
                    end = if (AerialSettings.clockRight) 170.dp else 0.dp, top = 28.dp),
        )

        AllAppsSheet(
            visible = overlay is Overlay.AllApps,
            apps = vm.moreApps,
            onLaunch = { app, bounds -> vm.overlay = Overlay.None; onLaunch(app, bounds) },
            onDismiss = {
                vm.overlay = Overlay.None
                scope.launch { withFrameNanos { }; runCatching { requester(ALL_APPS_KEY).requestFocus() } }
            },
        )

        AnimatedVisibility(
            visible = moving != null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(260, easing = Motion.EaseOut)) { it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150, easing = Motion.EaseOut)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        ) {
            // Screens that can be touched also say how to move with a finger.
            val touch = LocalConfiguration.current.touchscreen != Configuration.TOUCHSCREEN_NOTOUCH
            BasicText(
                stringResource(if (touch) R.string.move_hint_touch else R.string.move_hint),
                style = Type.Body,
                modifier = Modifier
                    .liquidGlass(20.dp)
                    .height(40.dp)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        ControlCenter(
            visible = overlay is Overlay.ControlCenter,
            page = (overlay as? Overlay.ControlCenter)?.page ?: CcPage.Main,
            vm = vm,
            now = now,
            onOpen = { vm.overlay = Overlay.None; onOpen(it) },
            onClose = { vm.overlay = Overlay.None },
        )

        val menu = overlay as? Overlay.AppMenu
        AppMenu(
            app = menu?.let { m -> apps.firstOrNull { it.key == m.key } },
            anchor = menu?.anchor,
            onMove = { vm.overlay = Overlay.AppOrder(it) },
            onHide = { key ->
                vm.overlay = Overlay.None
                vm.hide(key)
            },
            onInfo = { vm.overlay = Overlay.None; onAppInfo(it) },
            onUninstall = { vm.overlay = Overlay.None; onUninstall(it) },
            onUnpin = { key ->
                vm.overlay = Overlay.None
                apps.firstOrNull { it.key == key }?.source?.let { vm.setSourcePinned(it, false) }
            },
            onResetIcon = { key ->
                vm.overlay = Overlay.None
                vm.resetIcon(key)
            },
            onDismiss = { vm.overlay = Overlay.None },
        )

        AppOrderPanel(
            visible = overlay is Overlay.AppOrder,
            apps = vm.apps,
            initialKey = (overlay as? Overlay.AppOrder)?.initialKey,
            onSave = { vm.applyAppOrder(it); vm.overlay = Overlay.Wallpaper },
            onDismiss = { vm.overlay = Overlay.Wallpaper },
        )

        HiddenAppsSheet(
            visible = overlay is Overlay.HiddenApps,
            apps = vm.hiddenApps,
            onUnhide = { key ->
                vm.unhide(key)
                if (vm.hiddenApps.isEmpty()) vm.overlay = Overlay.Wallpaper
            },
            // Hidden apps are reached from Personalization, and Back returns there.
            onDismiss = { vm.overlay = Overlay.Wallpaper },
        )

        WallpaperSheet(
            visible = overlay is Overlay.Wallpaper,
            onDismiss = { vm.overlay = Overlay.ControlCenter() },
            hiddenCount = vm.hiddenApps.size,
            onOrder = { vm.overlay = Overlay.AppOrder() },
            onHiddenApps = { vm.whenUnlocked { vm.overlay = Overlay.HiddenApps } },
            onStartScreensaver = {
                vm.overlay = Overlay.None
                onStartScreensaver()
            },
            onPin = { on ->
                if (on) vm.overlay = Overlay.Pin(PinMode.Create) { vm.overlay = Overlay.Wallpaper }
                else vm.whenUnlocked { AerialSettings.choosePin(null); vm.overlay = Overlay.Wallpaper }
            },
        )

        val pin = overlay as? Overlay.Pin
        PinPad(
            visible = pin != null,
            mode = pin?.mode,
            onDone = { pin?.onDone?.invoke() },
            onCancel = { vm.overlay = Overlay.None },
        )

        Screensaver(visible = vm.screensaverVisible, apps = vm.apps, now = now, skips = vm.screensaverSkips)
    }
}
