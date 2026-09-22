package com.sensinglocal.blackjack

import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// One full lap every 20s (3 RPM = 3 revolutions / 60s).
private const val ORBIT_PERIOD_MS = 20000
// Angular half-width (degrees) of the "no-fly zone" over the BLACKJACK title, measured
// from top-dead-center. A background-colored wedge is painted over this arc (see
// drawTitleWedgeMask) so orbiting icons appear to duck behind the title and re-emerge on
// the far side, without needing to skip drawing individual icons every frame.
private const val TITLE_EXCLUSION_HALF_DEG = 50f
private const val TITLE_SPAN_HALF_DEG = 42f
// Total icons spaced evenly around the ring, cycling through the four suits.
private const val ORBIT_ICON_COUNT = 36

@Composable
fun MainMenuScreen(onPlay: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "menuOrbit")
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ORBIT_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbitAngle"
    )
    val density = LocalDensity.current
    val context = LocalContext.current
    val titleTypeface = remember { vt323Typeface(context) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // The icon ring and the title/mask overlay never change once the screen size is known —
    // rendering ~36 icons and 9 curved letters is far too much per-frame CPU work for this
    // hardware (it's what was causing the open-screen lag/battery drain). Instead they're
    // rasterized once into bitmaps here, and the live Canvas below just composites those
    // bitmaps every frame (one drawImage call each, plus a cheap rotation transform for the
    // ring) rather than re-issuing hundreds of draw calls per frame.
    val ringBitmap = remember(canvasSize) {
        if (canvasSize.width > 0 && canvasSize.height > 0) {
            renderIconRingBitmap(canvasSize, density)
        } else null
    }
    val titleOverlayBitmap = remember(canvasSize) {
        if (canvasSize.width > 0 && canvasSize.height > 0) {
            renderTitleOverlayBitmap(canvasSize, density, titleTypeface)
        } else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MenuRedDark),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val edgeRadius = size.minDimension / 2f

            // Gold trim ring just inside the physical bezel.
            drawCircle(
                color = MenuGold,
                radius = edgeRadius - 10f,
                center = center,
                style = Stroke(width = 4f)
            )

            ringBitmap?.let { bitmap ->
                rotate(degrees = -orbitAngle, pivot = center) {
                    drawImage(bitmap)
                }
            }
            titleOverlayBitmap?.let { bitmap -> drawImage(bitmap) }
        }

        PixelButton(text = "PLAY", onClick = onPlay, width = 140.dp, height = 56.dp, fontSize = 20.sp)
    }
}

private fun renderIconRingBitmap(sizePx: IntSize, density: Density): ImageBitmap {
    val bitmap = ImageBitmap(sizePx.width, sizePx.height)
    val canvasSize = Size(sizePx.width.toFloat(), sizePx.height.toFloat())
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, ComposeCanvas(bitmap), canvasSize) {
        val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
        val edgeRadius = canvasSize.minDimension / 2f
        val radius = edgeRadius - 30f
        val iconSpanDeg = 360f / ORBIT_ICON_COUNT
        val suits = SuitIcon.entries
        for (index in 0 until ORBIT_ICON_COUNT) {
            val angleDeg = index * iconSpanDeg
            val rad = Math.toRadians(angleDeg.toDouble())
            val iconCenter = Offset(
                x = center.x + radius * sin(rad).toFloat(),
                y = center.y - radius * cos(rad).toFloat()
            )
            val icon = suits[index % suits.size]
            drawPixelIcon(icon.grid, MenuGold, iconCenter, cellSize = 1.9f)
        }
    }
    return bitmap
}

private fun renderTitleOverlayBitmap(sizePx: IntSize, density: Density, typeface: Typeface): ImageBitmap {
    val bitmap = ImageBitmap(sizePx.width, sizePx.height)
    val canvasSize = Size(sizePx.width.toFloat(), sizePx.height.toFloat())
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, ComposeCanvas(bitmap), canvasSize) {
        val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
        val edgeRadius = canvasSize.minDimension / 2f
        drawTitleWedgeMask(center, edgeRadius)
        drawCurvedTitle(center, edgeRadius, density, typeface)
    }
    return bitmap
}

// Paints a background-colored wedge over the arc behind the title, from the center out to
// just inside the gold ring, so the orbiting icon ring (composited underneath this overlay)
// is hidden wherever it passes behind "BLACKJACK".
private fun DrawScope.drawTitleWedgeMask(center: Offset, edgeRadius: Float) {
    val wedgeRadius = edgeRadius - 16f
    val path = Path().apply {
        moveTo(center.x, center.y)
        arcTo(
            rect = Rect(
                center.x - wedgeRadius,
                center.y - wedgeRadius,
                center.x + wedgeRadius,
                center.y + wedgeRadius
            ),
            startAngleDegrees = -90f - TITLE_EXCLUSION_HALF_DEG,
            sweepAngleDegrees = TITLE_EXCLUSION_HALF_DEG * 2f,
            forceMoveTo = false
        )
        close()
    }
    drawPath(path, color = MenuRedDark)
}

private fun DrawScope.drawCurvedTitle(center: Offset, edgeRadius: Float, density: Density, typeface: Typeface) {
    val text = "BLACKJACK"
    val titleRadius = edgeRadius - 46f
    val titlePaint = AndroidPaint().apply {
        color = android.graphics.Color.BLACK
        textSize = with(density) { 24.sp.toPx() }
        this.typeface = Typeface.create(typeface, Typeface.BOLD)
        isAntiAlias = false
        textAlign = AndroidPaint.Align.CENTER
    }

    val step = (TITLE_SPAN_HALF_DEG * 2f) / (text.length - 1)
    val canvas = drawContext.canvas.nativeCanvas
    text.forEachIndexed { i, char ->
        val angleDeg = -TITLE_SPAN_HALF_DEG + i * step
        canvas.save()
        canvas.translate(center.x, center.y)
        canvas.rotate(angleDeg)
        canvas.translate(0f, -titleRadius)
        canvas.drawText(char.toString(), 0f, 0f, titlePaint)
        canvas.restore()
    }
}
