package app.orchard.tv.ui.components

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.wallpaper.Wallpapers

/**
 * Liquid Glass, the material of tvOS 26's panels, without a live blur: the TVs here mostly run
 * Android 9–11 (no RenderEffect) and their GPUs could not afford one. The surface shows the part
 * of the frosted wallpaper (Wallpapers.frosted, baked small) that lies behind it, dimmed so white
 * text reads on any picture, and light catches its rim. The frosted picture is stretched over the
 * screen through a shader, so a panel costs one fill of its own area, as a flat color did. In
 * "Follow App" mode there is no frosted wallpaper and the panel is the plain dark glass color.
 *
 * Replaces clip + background + border; [bottomCorners] false for a sheet rising from the bottom.
 */
/**
 * What Lumen's glass shows through: the frosted copy of whatever fills the screen now, the cover
 * being resumed or else the wallpaper. Set by Home; every panel, the dock and the sheets use it,
 * so the glass is one material everywhere.
 */
object GlassSource {
    var frost by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
}

fun Modifier.liquidGlass(corner: Dp, bottomCorners: Boolean = true): Modifier = composed {
    val frost = GlassSource.frost ?: Wallpapers.frosted.takeIf { Wallpapers.backdrop != null }
    var at by remember { mutableStateOf(Offset.Zero) }
    var root by remember { mutableStateOf(IntSize.Zero) }
    val shape = if (bottomCorners) RoundedCornerShape(corner) else RoundedCornerShape(topStart = corner, topEnd = corner)
    this
        .onGloballyPositioned { c ->
            at = c.positionInRoot()
            var r = c
            while (true) r = r.parentLayoutCoordinates ?: break
            root = r.size
        }
        .clip(shape)
        .drawWithCache {
            val shader = frost?.let { BitmapShader(it.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            val brush = shader?.let { ShaderBrush(it) }
            val radius = corner.toPx()
            onDrawBehind {
                if (shader == null || brush == null || root == IntSize.Zero) {
                    drawRect(Palette.Glass)
                } else {
                    shader.setLocalMatrix(Matrix().apply {
                        setScale(root.width.toFloat() / frost.width, root.height.toFloat() / frost.height)
                        postTranslate(-at.x, -at.y)
                    })
                    drawRect(brush, colorFilter = PanelTint)
                }
                // Thick glass: light falls from the top, so the pane is brighter there.
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.14f), 1f to Color.White.copy(alpha = 0.04f)))
                // A sheet's rim runs off the bottom with its corners.
                val h = if (bottomCorners) size.height else size.height + radius
                drawGlassEdge(Offset.Zero, Size(size.width, h), radius, 1f)
            }
        }
}

/**
 * The lit edge of a piece of glass: a sheen inside the top, and a rim bright where light falls on
 * it from the top left, faint along the sides, and catching again at the far corner. [strength]
 * fades it (the top-row plate loses its edge as it grows into the grid's background).
 */
fun DrawScope.drawGlassEdge(topLeft: Offset, size: Size, radius: Float, strength: Float) {
    if (strength <= 0.01f) return
    drawRoundRect(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.18f * strength),
            1f to Color.Transparent,
            startY = topLeft.y,
            endY = topLeft.y + size.height * 0.5f,
        ),
        topLeft = topLeft,
        size = Size(size.width, size.height * 0.5f),
        cornerRadius = CornerRadius(radius),
    )
    val w = 1.2.dp.toPx()
    drawRoundRect(
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.7f * strength),
            0.35f to Color.White.copy(alpha = 0.10f * strength),
            0.7f to Color.White.copy(alpha = 0.06f * strength),
            1f to Color.White.copy(alpha = 0.30f * strength),
            start = topLeft,
            end = Offset(topLeft.x + size.width, topLeft.y + size.height),
        ),
        topLeft = topLeft + Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius - w / 2),
        style = Stroke(width = w),
    )
}

/** Panels: the frosted picture at half its light, lifted a little, so white text reads on snow. */
private val PanelTint = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    0.48f, 0f, 0f, 0f, 14f,
    0f, 0.48f, 0f, 0f, 14f,
    0f, 0f, 0.48f, 0f, 14f,
    0f, 0f, 0f, 1f, 0f,
)))
