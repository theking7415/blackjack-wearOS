package com.sensinglocal.blackjack

import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sensinglocal.blackjack.game.Card

private val CardWidth = 34.dp
private val CardHeight = 48.dp
private const val BORDER_WIDTH = 2f
private const val SHADOW_OFFSET = 2f

/**
 * Renders one card as a chunky pixel-art rectangle: cream face, black border, rank in the
 * top-left corner and a small pixel suit icon centered below it. Face-down cards (or a null
 * card, used for the dealer's hidden hole card) show a gold-on-red pixel diamond back instead.
 *
 * The card face is drawn once into a cached [ImageBitmap] (keyed on card/faceDown/density) and
 * the live `Canvas` just does one `drawImage` per frame — a hand of cards was previously
 * re-issuing dozens of `drawRect`/`drawText` calls per card on every redraw (e.g. during
 * ScalingLazyColumn scroll), which is exactly the per-frame CPU cost that caused jank/battery
 * drain on this hardware before (see the main-menu icon-ring bitmap-caching fix).
 */
@Composable
fun PixelCard(card: Card?, faceDown: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val typeface = remember { vt323Typeface(context) }
    val boldTypeface = remember(typeface) { Typeface.create(typeface, Typeface.BOLD) }
    val widthPx = with(density) { CardWidth.toPx() }
    val heightPx = with(density) { CardHeight.toPx() }
    val bitmap = remember(card, faceDown, density) {
        renderCardBitmap(widthPx, heightPx, card, faceDown, density, boldTypeface)
    }
    Canvas(modifier = modifier.size(CardWidth, CardHeight)) {
        drawImage(bitmap)
    }
}

private fun renderCardBitmap(
    widthPx: Float,
    heightPx: Float,
    card: Card?,
    faceDown: Boolean,
    density: Density,
    boldTypeface: Typeface
): ImageBitmap {
    val w = widthPx.toInt().coerceAtLeast(1)
    val h = heightPx.toInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(w, h)
    val canvasSize = Size(w.toFloat(), h.toFloat())
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, ComposeCanvas(bitmap), canvasSize) {
        val bodySize = Size(canvasSize.width - SHADOW_OFFSET, canvasSize.height - SHADOW_OFFSET)

        // Fixed dark base layer peeking out bottom-right — same chunky-3D language as the buttons.
        drawRect(
            color = MenuBlack,
            topLeft = Offset(SHADOW_OFFSET, SHADOW_OFFSET),
            size = bodySize
        )

        if (faceDown || card == null) {
            drawRect(color = MenuRed, size = bodySize)
            drawRect(color = MenuGold, size = bodySize, style = Stroke(width = BORDER_WIDTH))
            drawPixelIcon(
                grid = diamondGrid,
                color = MenuGold,
                center = Offset(bodySize.width / 2f, bodySize.height / 2f),
                cellSize = 1.9f
            )
        } else {
            drawRect(color = CardCream, size = bodySize)
            drawRect(color = MenuBlack, size = bodySize, style = Stroke(width = BORDER_WIDTH))

            val suitColor = if (card.suit.isRed()) MenuRed else MenuBlack
            drawPixelRankText(card.rank.label, suitColor, boldTypeface)
            drawPixelIcon(
                grid = gridForSuit(card.suit),
                color = suitColor,
                center = Offset(bodySize.width / 2f, bodySize.height * 0.66f),
                cellSize = 1.4f
            )
        }
    }
    return bitmap
}

private fun DrawScope.drawPixelRankText(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    boldTypeface: Typeface
) {
    val paint = AndroidPaint().apply {
        this.color = android.graphics.Color.rgb(
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
        textSize = with(this@drawPixelRankText) { 16.sp.toPx() }
        this.typeface = boldTypeface
        isAntiAlias = false
        textAlign = AndroidPaint.Align.LEFT
    }
    val canvas = drawContext.canvas.nativeCanvas
    canvas.drawText(text, 3f, paint.textSize, paint)
}
