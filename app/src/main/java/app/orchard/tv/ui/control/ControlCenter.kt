package app.orchard.tv.ui.control

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orchard.tv.CcPage
import app.orchard.tv.BuildConfig
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.R
import kotlinx.coroutines.launch
import app.orchard.tv.update.Updates
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.HomeStatus
import app.orchard.tv.system.NetKind
import app.orchard.tv.system.SourceKind
import app.orchard.tv.system.Sounds
import app.orchard.tv.system.SystemProbe
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.home.MenuRow
import app.orchard.tv.ui.home.dateText
import app.orchard.tv.ui.home.timeText
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.wallpaper.PhoneServer
import java.time.LocalDateTime

// Temporarily hide upstream updates while this customized home screen is in use.
private const val SHOW_UPDATE_ENTRY = false

/**
 * tvOS Control Center: a glass panel that drops in from the top-right corner over a blurred Home.
 * Inputs open as a second page inside the same panel, the way tvOS shows audio routes.
 */
@Composable
fun ControlCenter(
    visible: Boolean,
    page: CcPage,
    vm: HomeViewModel,
    now: LocalDateTime,
    onOpen: (android.content.Intent) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = visible) {
        when (page) {
            CcPage.Main -> onClose()
            CcPage.Inputs -> vm.overlay = Overlay.ControlCenter(CcPage.Main)
            CcPage.InputSettings -> vm.overlay = Overlay.ControlCenter(CcPage.Inputs)
            CcPage.City -> vm.overlay = Overlay.ControlCenter(CcPage.Main)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(180)),
        modifier = Modifier.fillMaxSize(),
    ) {
        // A tap outside the panel closes Control Center.
        Box(Modifier.fillMaxSize().dismissOnTap(onClose).background(Color.Black.copy(alpha = 0.28f)))
    }

    Box(Modifier.fillMaxSize().padding(top = 24.dp, end = 32.dp), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visible = visible,
            // Grows out of the corner where the status pill lives; leaves faster than it came.
            enter = fadeIn(tween(200)) + scaleIn(tween(340, easing = Motion.Drawer), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(160)) + scaleOut(tween(160, easing = Motion.EaseOut), targetScale = 0.95f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            Column(
                Modifier
                    .width(360.dp)
                    .consumeTaps()
                    .liquidGlass(30.dp)
                    .padding(22.dp),
            ) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val forward = targetState != CcPage.Main
                        (fadeIn(tween(200, delayMillis = 40)) + slideInHorizontally(tween(260, easing = Motion.EaseOut)) { if (forward) it / 6 else -it / 6 })
                            .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(200, easing = Motion.EaseOut)) { if (forward) -it / 8 else it / 8 })
                    },
                    label = "cc-page",
                ) { p ->
                    // Closing sets the page back to Main while the panel fades out; that page must
                    // not take focus from the tile Home has just put it back on.
                    when (p) {
                        CcPage.Main -> MainPage(vm, now, visible, onOpen)
                        CcPage.Inputs -> InputsPage(vm, visible, onOpen)
                        CcPage.InputSettings -> InputSettingsPage(vm, visible)
                        CcPage.City -> CityPage(vm, visible)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainPage(vm: HomeViewModel, now: LocalDateTime, takeFocus: Boolean, onOpen: (android.content.Intent) -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { Updates.checkIfStale(soon = true) }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                BasicText(timeText(now), style = Type.LargeTitle.copy(fontSize = Type.LargeTitle.fontSize * 1.25f))
                BasicText(dateText(now), style = Type.Callout)
            }
            val net = vm.network
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlyphIcon(
                    when (net.kind) { NetKind.Wifi -> Glyph.Wifi; NetKind.Ethernet -> Glyph.Ethernet; NetKind.Offline -> Glyph.Offline },
                    Palette.SecondaryLabel, 14.dp, level = net.level,
                )
                BasicText(
                    when (net.kind) {
                        NetKind.Wifi -> net.name ?: stringResource(R.string.net_wifi)
                        NetKind.Ethernet -> stringResource(R.string.net_ethernet)
                        NetKind.Offline -> stringResource(R.string.net_offline)
                    },
                    style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 130.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundButton(Glyph.Input, stringResource(R.string.cc_inputs), focusRequester = first) {
                // TCL lists no inputs to Android; its own input panel is the next best thing.
                vm.overlay = if (vm.sources.isEmpty() && SystemProbe.openInputPicker(context)) Overlay.None
                else Overlay.ControlCenter(CcPage.Inputs)
            }
            RoundButton(
                when (vm.network.kind) { NetKind.Ethernet -> Glyph.Ethernet; NetKind.Offline -> Glyph.Offline; else -> Glyph.Wifi },
                stringResource(R.string.cc_network),
            ) { vm.whenUnlocked { onOpen(SystemProbe.networkSettingsIntent(context)) } }
            RoundButton(Glyph.Gear, stringResource(R.string.cc_settings)) { vm.whenUnlocked { onOpen(SystemProbe.settingsIntent(context)) } }
        }

        Spacer(Modifier.height(16.dp))
        VolumeRow(vm)
        Spacer(Modifier.height(8.dp))
        BrightnessRow(vm)
        Spacer(Modifier.height(8.dp))
        MenuRow(Glyph.Switches, stringResource(R.string.wp_title), onClick = {
            vm.whenUnlocked { vm.overlay = Overlay.Wallpaper }
        })


        // The weather's city: guessed from the network, which in China often lands a city off.
        MenuRow(
            Glyph.CloudSun,
            stringResource(R.string.cc_city),
            trailing = if (app.orchard.tv.weather.Weather.chosen) app.orchard.tv.weather.Weather.place else stringResource(R.string.cc_city_auto),
            onClick = { vm.overlay = Overlay.ControlCenter(CcPage.City) },
        )
        Updates.available?.takeIf { SHOW_UPDATE_ENTRY }?.let { latest ->
            Spacer(Modifier.height(8.dp))
            // Downloads and opens the system installer, which asks on screen.
            val progress = Updates.downloading
            MenuRow(
                Glyph.Info,
                when {
                    progress != null -> stringResource(R.string.cc_update_downloading, (progress * 100).toInt())
                    Updates.failed -> stringResource(R.string.cc_update_failed)
                    else -> stringResource(R.string.cc_update_available, latest)
                },
                onClick = { Updates.install(context) },
            )
        }

        Spacer(Modifier.height(12.dp))
        val (dot, text) = when (vm.homeStatus) {
            HomeStatus.Default -> Palette.Green to stringResource(R.string.cc_home_default)
            HomeStatus.Guarded -> Palette.Green to stringResource(R.string.cc_home_guarded)
            HomeStatus.NotDefault -> Palette.Orange to stringResource(R.string.cc_home_not_default)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            BasicText(text, style = Type.Caption, modifier = Modifier.weight(1f))
            BasicText(app.orchard.tv.BuildConfig.VERSION_NAME.substringBefore("-"), style = Type.Caption.copy(color = Palette.TertiaryLabel), modifier = Modifier.padding(end = 14.dp))
        }
    }
}

/** A round Control Center button: frosted at rest, white when focused. */
@Composable
private fun RoundButton(glyph: Glyph, label: String, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.1f) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "round-bg")
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
            Box(Modifier.size(58.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                GlyphIcon(glyph, if (state.focused) Palette.OnFocus else Palette.Label, 24.dp)
            }
            Spacer(Modifier.height(8.dp))
            BasicText(
                label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = Type.Caption.copy(color = if (state.focused) Palette.Label else Palette.SecondaryLabel, textAlign = TextAlign.Center),
            )
        }
    }
}

/**
 * A Control Center slider: focus it, then Left and Right change the level, OK does [onClick].
 * Shared by volume and brightness so both feel identical. On a touch screen a tap does [onClick]
 * and a sideways drag steps the level, [steps] steps across the width of the row.
 */
@Composable
private fun SliderRow(
    glyph: Glyph,
    level: Float,
    value: String,
    steps: Int,
    onStep: (Int) -> Unit,
    onClick: () -> Unit,
) {
    // Each step ticks, as focus does; a step past either end bumps instead. Read through state:
    // the drag handler below is made once and outlives this composition's values.
    val atLevel by rememberUpdatedState(level)
    val doStep by rememberUpdatedState(onStep)
    val step = { by: Int ->
        if ((by > 0 && atLevel >= 0.999f) || (by < 0 && atLevel <= 0.001f)) Sounds.limit() else Sounds.step()
        doStep(by)
    }
    val stepCount by rememberUpdatedState(steps.coerceAtLeast(1))
    Pressable(
        onClick = onClick,
        focusedScale = 1.03f,
        modifier = Modifier.fillMaxWidth(),
        onKey = { e ->
            val code = e.nativeKeyEvent.keyCode
            if (code != NativeKeyEvent.KEYCODE_DPAD_LEFT && code != NativeKeyEvent.KEYCODE_DPAD_RIGHT) return@Pressable false
            if (e.type == KeyEventType.KeyDown) step(if (code == NativeKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
            true
        },
    ) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "slider-bg")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        val fraction by animateFloatAsState(level, spring(dampingRatio = 1f, stiffness = 700f), label = "slider-level")
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    // The level only moves in the steps the system takes, so the drag is counted
                    // out in steps rather than set to an exact point.
                    var travelled = 0f
                    detectHorizontalDragGestures(onDragStart = { travelled = 0f }) { change, dx ->
                        change.consume()
                        travelled += dx
                        val pitch = size.width.toFloat() / stepCount
                        while (travelled >= pitch) { step(1); travelled -= pitch }
                        while (travelled <= -pitch) { step(-1); travelled += pitch }
                    }
                }
                .clip(RoundedCornerShape(16.dp)).background(bg).height(48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlyphIcon(glyph, fg, 20.dp, level = level)
            Canvas(Modifier.weight(1f).height(6.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(fg.copy(alpha = 0.2f), cornerRadius = r)
                drawRoundRect(fg, size = Size(size.width * fraction, size.height), cornerRadius = r)
            }
            BasicText(
                value,
                style = Type.Headline.copy(color = fg, textAlign = TextAlign.End),
                modifier = Modifier.widthIn(min = 28.dp),
            )
        }
    }
}

@Composable
private fun VolumeRow(vm: HomeViewModel) {
    val volume = vm.volume
    SliderRow(
        glyph = if (volume.muted) Glyph.SpeakerMuted else Glyph.Speaker,
        level = if (volume.muted) 0f else volume.fraction,
        value = if (volume.muted) stringResource(R.string.cc_muted) else volume.level.toString(),
        steps = volume.max,
        onStep = vm::adjustVolume,
        onClick = vm::toggleMute,
    )
}

/** Brightness where Aerial may change it; otherwise a row that opens the TV's picture settings. */
@Composable
private fun BrightnessRow(vm: HomeViewModel) {
    val context = LocalContext.current
    val level = vm.brightness
    if (level == null) {
        MenuRow(Glyph.Sun, stringResource(R.string.cc_brightness), onClick = {
            vm.overlay = Overlay.None
            SystemProbe.openPictureSettings(context)
        })
    } else {
        SliderRow(
            glyph = Glyph.Sun,
            level = level,
            value = Math.round(level * 100).toString(),
            // Brightness.adjust moves 5% a step.
            steps = 20,
            onStep = vm::adjustBrightness,
            onClick = {},
        )
    }
}

@Composable
private fun InputsPage(vm: HomeViewModel, takeFocus: Boolean, onOpen: (android.content.Intent) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.cc_inputs), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Main) })
        Spacer(Modifier.height(8.dp))
        if (vm.sources.isEmpty()) {
            BasicText(stringResource(R.string.cc_no_inputs), style = Type.Callout, modifier = Modifier.padding(14.dp))
        }
        val connected = stringResource(R.string.input_connected)
        vm.sources.forEachIndexed { i, source ->
            MenuRow(
                glyph = when (source.kind) {
                    SourceKind.Hdmi, SourceKind.DisplayPort -> Glyph.Hdmi
                    SourceKind.Tuner -> Glyph.Antenna
                    SourceKind.Av -> Glyph.Av
                    SourceKind.Other -> Glyph.Input
                },
                text = source.label,
                trailing = source.deviceName ?: if (source.connected) connected else null,
                focusRequester = if (i == 0) first else null,
                onClick = { onOpen(source.intent) },
            )
        }
        // Switching is this page's one job; what Aerial does with the inputs is a page further in.
        if (vm.sources.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            MenuRow(Glyph.Gear, stringResource(R.string.cc_inputs_settings), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.InputSettings) })
        }
    }
}

/** Cities for the weather, most populous first; the owner's own city is reached by its province's. */
private val CITIES = listOf(
    "北京", "上海", "广州", "深圳", "成都", "重庆", "杭州", "武汉", "西安", "南京", "天津", "苏州",
    "长沙", "郑州", "东莞", "青岛", "沈阳", "宁波", "昆明", "合肥", "佛山", "福州", "厦门", "济南",
    "大连", "哈尔滨", "长春", "石家庄", "南昌", "南宁", "贵阳", "太原", "兰州", "乌鲁木齐", "呼和浩特",
    "海口", "三亚", "银川", "西宁", "拉萨", "无锡", "温州", "珠海", "香港", "澳门", "台北",
)

/** Where the weather is for: found from the network, or one of [CITIES]. */
@Composable
private fun CityPage(vm: HomeViewModel, takeFocus: Boolean) {
    val first = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val back = { vm.overlay = Overlay.ControlCenter(CcPage.Main) }
    Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.cc_city), onClick = back)
        Spacer(Modifier.height(8.dp))
        MenuRow(
            if (!app.orchard.tv.weather.Weather.chosen) Glyph.Check else Glyph.Wifi,
            stringResource(R.string.cc_city_auto),
            focusRequester = first,
            onClick = { app.orchard.tv.weather.Weather.useNetworkLocation(); back() },
        )
        CITIES.forEach { city ->
            MenuRow(
                if (app.orchard.tv.weather.Weather.chosen && app.orchard.tv.weather.Weather.place == city) Glyph.Check else Glyph.CloudSun,
                city,
                onClick = { scope.launch { app.orchard.tv.weather.Weather.choose(city) }; back() },
            )
        }
    }
}

/**
 * What Aerial does with the inputs: which ones are tiles on Home (any number) and which one the
 * TV starts on (one, or none). Kept apart from the list that switches inputs.
 */
@Composable
private fun InputSettingsPage(vm: HomeViewModel, takeFocus: Boolean) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    fun name(source: app.orchard.tv.system.TvSource) = source.deviceName?.let { "${source.label} · $it" } ?: source.label
    Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.cc_inputs_settings), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Inputs) })

        Spacer(Modifier.height(10.dp))
        BasicText(stringResource(R.string.cc_inputs_pin), style = Type.Callout, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        vm.sources.forEachIndexed { i, source ->
            val pinned = source.id in AerialSettings.pinnedSources
            ChoiceRow(
                text = name(source),
                selected = pinned,
                focusRequester = if (i == 0) first else null,
                onClick = { vm.setSourcePinned(source, !pinned) },
            )
        }
        BasicText(
            stringResource(R.string.cc_inputs_pin_hint),
            style = Type.Caption,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
        )

        Spacer(Modifier.height(18.dp))
        BasicText(stringResource(R.string.cc_inputs_boot), style = Type.Callout, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        ChoiceRow(
            text = stringResource(R.string.cc_inputs_boot_none),
            selected = vm.sources.none { it.id == AerialSettings.bootSource },
            focusRequester = null,
            onClick = { AerialSettings.chooseBootSource(null) },
        )
        vm.sources.forEach { source ->
            ChoiceRow(
                text = name(source),
                selected = source.id == AerialSettings.bootSource,
                focusRequester = null,
                onClick = { AerialSettings.chooseBootSource(source.id) },
            )
        }
        BasicText(
            stringResource(R.string.cc_inputs_boot_hint),
            style = Type.Caption,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
        )
    }
}

/** A row in a list of choices: the chosen one carries a check, as in tvOS settings. */
@Composable
private fun ChoiceRow(text: String, selected: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.03f, modifier = Modifier.fillMaxWidth()) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Color.Transparent, tween(140, easing = Motion.EaseOut), label = "choice-bg")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).height(44.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(text, style = Type.Body.copy(color = fg), modifier = Modifier.weight(1f))
            if (selected) GlyphIcon(Glyph.Check, fg, 16.dp)
        }
    }
}
