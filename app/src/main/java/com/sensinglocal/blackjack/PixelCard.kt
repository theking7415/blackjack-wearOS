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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
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
 */
@Composable
fun PixelCard(card: Card?, faceDown: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val typeface = remember { vt323Typeface(context) }
    Canvas(modifier = modifier.size(CardWidth, CardHeight)) {
        val bodySize = Size(size.width - SHADOW_OFFSET, size.height - SHADOW_OFFSET)

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
            drawPixelRankText(card.rank.label, suitColor, typeface)
            drawPixelIcon(
                grid = gridForSuit(card.suit),
                color = suitColor,
                center = Offset(bodySize.width / 2f, bodySize.height * 0.66f),
                cellSize = 1.4f
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPixelRankText(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    typeface: Typeface
) {
    val paint = AndroidPaint().apply {
        this.color = android.graphics.Color.rgb(
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
        textSize = with(this@drawPixelRankText) { 16.sp.toPx() }
        this.typeface = Typeface.create(typeface, Typeface.BOLD)
        isAntiAlias = false
        textAlign = AndroidPaint.Align.LEFT
    }
    val canvas = drawContext.canvas.nativeCanvas
    canvas.drawText(text, 3f, paint.textSize, paint)
}
