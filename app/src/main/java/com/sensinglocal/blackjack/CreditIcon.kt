package com.sensinglocal.blackjack

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text

/**
 * The app's currency icon, replacing the old "₹" symbol: a gold coin with an open "C" ring
 * crossed by a vertical bar — reads as a currency glyph the way "$" does, rather than a plain
 * letter. Drawn with Canvas primitives (not a pixel grid like the suit icons) since a coin is
 * inherently round.
 */
@Composable
fun CreditIcon(modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier = modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        drawCircle(color = MenuGold, radius = radius, center = center)
        drawCircle(color = MenuBlack, radius = radius * 0.92f, center = center, style = Stroke(width = radius * 0.2f))
        val glyphRadius = radius * 0.5f
        // Open "C" ring...
        drawArc(
            color = MenuBlack,
            startAngle = 35f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(center.x - glyphRadius, center.y - glyphRadius),
            size = Size(glyphRadius * 2f, glyphRadius * 2f),
            style = Stroke(width = radius * 0.24f, cap = StrokeCap.Square)
        )
        // ...crossed by a vertical bar, like a "$"/cedi-style currency mark.
        drawLine(
            color = MenuBlack,
            start = Offset(center.x, center.y - glyphRadius * 1.1f),
            end = Offset(center.x, center.y + glyphRadius * 1.1f),
            strokeWidth = radius * 0.22f,
            cap = StrokeCap.Square
        )
    }
}

/** A label + coin icon + amount row, e.g. "Bankroll: 🪙500" — used everywhere a credit amount
 * is displayed instead of embedding a currency symbol character in the string. */
@Composable
fun CreditAmountRow(
    amount: Int,
    modifier: Modifier = Modifier,
    prefix: String = "",
    fontSize: TextUnit = 16.sp,
    color: Color = MenuGold
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (prefix.isNotEmpty()) {
            Text(text = prefix, color = color, fontWeight = FontWeight.Bold, fontSize = fontSize)
        }
        CreditIcon(size = (fontSize.value * 0.85f).dp)
        Text(text = amount.toString(), color = color, fontWeight = FontWeight.Bold, fontSize = fontSize)
    }
}
