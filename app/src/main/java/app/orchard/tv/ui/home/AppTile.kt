package app.orchard.tv.ui.home

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.AppNameMode
import app.orchard.tv.settings.FocusStyle
import app.orchard.tv.system.Sounds
import app.orchard.tv.ui.components.isSelect
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// D-pad remotes give no tilt to follow, so the lift itself has to read from across the room.
private const val FOCUSED_SCALE = 1.15f
private const val PRESSED_SCALE = 1.05f
private val Corner = 12.dp

// High-contrast focus frame: a white ring this far outside the tile, as tvOS draws it.
private val RingGap = 5.dp
private val RingWidth = 3.dp
internal val AppLabelGap = 18.dp
internal val appLabelHeight: Dp get() = (AerialSettings.appNameSize + 8).dp

/**
 * One app on the home screen, drawn the tvOS way: it lifts toward you when focused, a soft light
 * lies across its top edge, its name appears beneath it, and it leans and springs back when you
 * push against the edge of the grid. Adaptive icons keep their two layers, so the logo shifts
 * against its background when the tile leans.
 */
@Composable
fun AppTile(
    app: AppEntry,
    width: Dp,
    height: Dp,
    focusRequester: FocusRequester,
    enabled: Boolean,
    moving: Boolean,
    appearDelayMs: Int,
    onFocused: () -> Unit,
    onClick: (Rect?) -> Unit,
    onLongPress: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var longFired by remember { mutableStateOf(false) }
    var menuPressed by remember { mutableStateOf(false) }
    val click by rememberUpdatedState(onClick)
    val longPress by rememberUpdatedState(onLongPress)

    // Lean (degrees) and push (px) used by the edge nudge.
    val leanY = remember { Animatable(0f) }
    val leanX = remember { Animatable(0f) }
    val push = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    val scale by animateFloatAsState(
        targetValue = when {
            reduced -> 1f
            pressed -> PRESSED_SCALE
            focused || moving -> FOCUSED_SCALE
            else -> 1f
        },
        // Press answers immediately; focus settles on a spring so rapid D-pad presses retarget
        // smoothly. Critically damped, as Android TV's focus zoom (Leanback, Compose for TV) is: an
        // overshoot reads as a wobble on a lit tile and makes the white frame shimmer.
        animationSpec = if (pressed) tween(110, easing = Motion.EaseOut) else spring(dampingRatio = 1f, stiffness = 520f),
        label = "tile-scale",
    )
    val lift by animateFloatAsState(
        targetValue = if (focused || moving) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 380f),
        label = "tile-lift",
    )
    val ring by animateFloatAsState(
        targetValue = if (AerialSettings.focusStyle == FocusStyle.HighContrast) 1f else 0f,
        animationSpec = tween(200, easing = Motion.EaseOut),
        label = "tile-ring",
    )
    // The frame comes on quickly with the zoom and goes off faster still, so moving along a row
    // never shows two frames at once. It grows out from the tile's edge to its place.
    val ringIn by animateFloatAsState(
        targetValue = if (focused || moving) 1f else 0f,
        animationSpec = if (focused || moving) spring(dampingRatio = 1f, stiffness = 700f) else spring(dampingRatio = 1f, stiffness = 2400f),
        label = "tile-ring-in",
    )

    // tvOS "jiggle" while the app is being moved.
    val wobble = if (moving && !reduced) {
        rememberInfiniteTransition(label = "jiggle").animateFloat(
            initialValue = -1.3f, targetValue = 1.3f,
            animationSpec = infiniteRepeatable(tween(130, easing = LinearEasing), RepeatMode.Reverse),
            label = "jiggle-angle",
        )
    } else null

    // First appearance: a short stagger, so the grid settles in rather than popping on.
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (appear.value < 1f) {
            delay(appearDelayMs.toLong())
            appear.animateTo(1f, tween(420, easing = Motion.EaseOut))
        }
    }

    fun nudge(dx: Float, dy: Float) {
        if (reduced) return
        scope.launch {
            push.animateTo(Offset(dx * 14f, dy * 14f), tween(90, easing = Motion.EaseOut))
            push.animateTo(Offset.Zero, spring(dampingRatio = 0.45f, stiffness = 520f))
        }
        scope.launch {
            leanY.animateTo(dx * 7f, tween(90, easing = Motion.EaseOut))
            leanY.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 420f))
        }
        scope.launch {
            leanX.animateTo(-dy * 7f, tween(90, easing = Motion.EaseOut))
            leanX.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 420f))
        }
    }

    Box(
        modifier
            .size(width, height)
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 18.dp.toPx()
            },
    ) {
        Box(
            Modifier
                .size(width, height)
                .onGloballyPositioned { bounds = it.boundsInWindow() }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationY = leanY.value
                    rotationX = leanX.value
                    rotationZ = wobble?.value ?: 0f
                    translationX = push.value.x
                    translationY = push.value.y
                    cameraDistance = 14f * density
                    shadowElevation = 32.dp.toPx() * lift
                    shape = RoundedCornerShape(Corner)
                    clip = true
                }
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (it.isFocused && !focused) Sounds.focusMoved()
                    focused = it.isFocused
                    if (it.isFocused) onFocused() else {
                        pressed = false
                        longPressJob?.cancel()
                    }
                }
                .onKeyEvent { e ->
                    val code = e.nativeKeyEvent.keyCode
                    val down = e.type == KeyEventType.KeyDown
                    val dir = when (code) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
                        NativeKeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                        else -> null
                    }
                    if (dir != null) {
                        if (down && !focusManager.moveFocus(dir)) {
                            Sounds.limit()
                            when (dir) {
                                FocusDirection.Left -> nudge(-1f, 0f)
                                FocusDirection.Right -> nudge(1f, 0f)
                                FocusDirection.Up -> nudge(0f, -1f)
                                else -> nudge(0f, 1f)
                            }
                        }
                        return@onKeyEvent true
                    }
                    // The Menu key opens the same menu: old remotes (Phicomm boxes) rarely report a
                    // held OK as held (issue #28). On key-up, and only for a press that began on
                    // this tile, so no release is left over to choose the menu's first row.
                    if (e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_MENU) {
                        if (down && e.nativeKeyEvent.repeatCount == 0) menuPressed = true
                        else if (e.type == KeyEventType.KeyUp && menuPressed) {
                            menuPressed = false
                            Sounds.select()
                            onLongPress(bounds)
                        }
                        return@onKeyEvent true
                    }
                    if (!e.isSelect()) return@onKeyEvent false
                    if (down) {
                        if (e.nativeKeyEvent.repeatCount > 0 && pressed && !longFired) {
                            // Remotes that auto-repeat a held key report the long press themselves.
                            longPressJob?.cancel()
                            longFired = true
                            pressed = false
                            Sounds.select()
                            onLongPress(bounds)
                        } else if (e.nativeKeyEvent.repeatCount == 0) {
                            pressed = true
                            longFired = false
                            longPressJob?.cancel()
                            longPressJob = scope.launch {
                                delay(550)
                                if (pressed) {
                                    longFired = true
                                    pressed = false
                                    Sounds.select()
                                    onLongPress(bounds)
                                }
                            }
                        }
                    } else if (e.type == KeyEventType.KeyUp) {
                        longPressJob?.cancel()
                        if (pressed && !longFired) {
                            Sounds.select()
                            onClick(bounds)
                        }
                        pressed = false
                    }
                    true
                }
                // Touch (issue #60): a tap opens the app and a long press opens its menu, both
                // focusing the tile first. The lift that ends a long press belongs to this tile's
                // gesture, so it cannot choose the menu's first row. A drag that scrolls the grid
                // cancels both.
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = {
                            runCatching { focusRequester.requestFocus() }
                            Sounds.select()
                            click(bounds)
                        },
                        onLongPress = {
                            pressed = false
                            runCatching { focusRequester.requestFocus() }
                            Sounds.select()
                            longPress(bounds)
                        },
                    )
                }
                .focusable(enabled),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val art = app.art
                drawImage(
                    art.back,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
                art.fore?.let { fore ->
                    val at = art.foreAt
                    // Parallax: the logo travels further than the plate when the tile leans.
                    val px = leanY.value / 7f * size.width * 0.035f
                    val py = -leanX.value / 7f * size.height * 0.035f
                    drawImage(
                        fore,
                        dstOffset = IntOffset((at.left * size.width + px).roundToInt(), (at.top * size.height + py).roundToInt()),
                        dstSize = IntSize((at.width * size.width).roundToInt(), (at.height * size.height).roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
                if (lift > 0.01f) {
                    // Light from above: a soft sheen across the top, brighter where the tile leans toward it.
                    val glintX = size.width * (0.3f + leanY.value / 7f * 0.25f)
                    drawRect(
                        Brush.radialGradient(
                            0f to Color.White.copy(alpha = 0.28f * lift),
                            1f to Color.Transparent,
                            center = Offset(glintX, -size.height * 0.25f),
                            radius = size.width * 0.75f,
                        ),
                        blendMode = BlendMode.Screen,
                    )
                }
                // A hairline on the rim, as on tvOS icons, so dark artwork still reads against black.
                drawRoundRect(
                    Color.White.copy(alpha = 0.07f + 0.08f * lift),
                    cornerRadius = CornerRadius(Corner.toPx()),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }

        if (ring > 0.01f && ringIn > 0.01f) {
            // The frame sits outside the tile, so it is drawn on its own unclipped layer that moves
            // exactly as the tile does: same lift, lean, push and jiggle.
            Canvas(
                Modifier
                    .size(width, height)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        rotationY = leanY.value
                        rotationX = leanX.value
                        rotationZ = wobble?.value ?: 0f
                        translationX = push.value.x
                        translationY = push.value.y
                        cameraDistance = 14f * density
                        alpha = ring * ringIn
                    },
            ) {
                val gap = RingGap.toPx() * (0.4f + 0.6f * ringIn)
                val w = RingWidth.toPx()
                val out = gap + w / 2
                // A faint dark edge outside the white one keeps the frame visible over bright sky or snow.
                val halo = gap + w + 1.dp.toPx()
                drawRoundRect(
                    Color.Black.copy(alpha = 0.3f),
                    topLeft = Offset(-halo, -halo),
                    size = Size(size.width + halo * 2, size.height + halo * 2),
                    cornerRadius = CornerRadius(Corner.toPx() + halo),
                    style = Stroke(width = 2.dp.toPx()),
                )
                // Lit like the glass it sits on: brightest where light falls from the top left and
                // at the far corner, still near white everywhere so it reads from across the room.
                drawRoundRect(
                    Brush.linearGradient(
                        0f to Color.White,
                        0.45f to Color.White.copy(alpha = 0.78f),
                        1f to Color.White.copy(alpha = 0.94f),
                        start = Offset(-out, -out),
                        end = Offset(size.width + out, size.height + out),
                    ),
                    topLeft = Offset(-out, -out),
                    size = Size(size.width + out * 2, size.height + out * 2),
                    cornerRadius = CornerRadius(Corner.toPx() + out),
                    style = Stroke(width = w),
                )
                // A thin inner line of light, the glass's thickness.
                drawRoundRect(
                    Color.White.copy(alpha = 0.35f),
                    topLeft = Offset(-out + w / 2 + 0.75.dp.toPx(), -out + w / 2 + 0.75.dp.toPx()),
                    size = Size(size.width + (out - w / 2 - 0.75.dp.toPx()) * 2, size.height + (out - w / 2 - 0.75.dp.toPx()) * 2),
                    cornerRadius = CornerRadius(Corner.toPx() + out - w / 2 - 0.75.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }

        BasicText(
            text = app.label,
            style = Type.Headline.copy(
                textAlign = TextAlign.Center,
                fontSize = AerialSettings.appNameSize.sp,
                lineHeight = (AerialSettings.appNameSize + 6).sp,
                fontWeight = if (focused || moving) FontWeight.W600 else FontWeight.W500,
                color = Color.White.copy(alpha = if (focused || moving) 1f else 0.76f),
                // Names sit on the wallpaper; a soft shadow keeps them readable over bright photos.
                shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 8f),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopCenter)
                // A fixed baseline leaves room for the focus frame without moving either row.
                .offset(y = height + AppLabelGap)
                .requiredSize(width + 12.dp, appLabelHeight)
                .graphicsLayer {
                    alpha = when (AerialSettings.appNameMode) {
                        AppNameMode.Always -> 1f
                        AppNameMode.Focused -> lift
                        AppNameMode.Hidden -> 0f
                    }
                },
        )
    }
}
