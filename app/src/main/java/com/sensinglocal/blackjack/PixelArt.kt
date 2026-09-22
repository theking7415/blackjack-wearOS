package com.sensinglocal.blackjack

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.sensinglocal.blackjack.game.Suit

// Notched top, single point at the bottom, no stem.
val heartGrid = arrayOf(
    ".XX..XX.",
    "XXXXXXXX",
    "XXXXXXXX",
    "XXXXXXXX",
    ".XXXXXX.",
    "..XXXX..",
    "...XX...",
    "........",
)
// Single point top and bottom, symmetric, no stem — distinct from the heart's notch.
val diamondGrid = arrayOf(
    "...XX...",
    "..XXXX..",
    ".XXXXXX.",
    "XXXXXXXX",
    ".XXXXXX.",
    "..XXXX..",
    "...XX...",
    "........",
)
// An upside-down heart (pointed top, flared shoulders, notched feet) over a stem — the
// classic spade silhouette, distinct from the diamond's plain symmetric point.
val spadeGrid = arrayOf(
    "...XX...",
    "..XXXX..",
    ".XXXXXX.",
    "XXXXXXXX",
    "XXXXXXXX",
    ".XX..XX.",
    "...XX...",
    "...XX...",
    "...XX...",
)
// Three overlapping round lobes (built from three circles) over a stem — wider than the
// other suits so it reads as genuinely three-bulbed rather than a single blob.
val clubGrid = arrayOf(
    "....XXX....",
    "...XXXXX...",
    "...XXXXX...",
    ".XXXXXXXXX.",
    "XXXXXXXXXXX",
    "XXXXX.XXXXX",
    "XXXXX.XXXXX",
    "....XXX....",
    "....XXX....",
    "....XXX....",
    "....XXX....",
)

enum class SuitIcon(val grid: Array<String>) {
    CLUB(clubGrid),
    SPADE(spadeGrid),
    HEART(heartGrid),
    DIAMOND(diamondGrid)
}

fun gridForSuit(suit: Suit): Array<String> = when (suit) {
    Suit.HEARTS -> heartGrid
    Suit.DIAMONDS -> diamondGrid
    Suit.SPADES -> spadeGrid
    Suit.CLUBS -> clubGrid
}

/** True for the two red suits — used to color card ranks/pips. */
fun Suit.isRed(): Boolean = this == Suit.HEARTS || this == Suit.DIAMONDS

fun DrawScope.drawPixelIcon(grid: Array<String>, color: Color, center: Offset, cellSize: Float) {
    val rows = grid.size
    val cols = grid.maxOf { it.length }
    val topLeft = Offset(
        x = center.x - (cols * cellSize) / 2f,
        y = center.y - (rows * cellSize) / 2f
    )
    grid.forEachIndexed { row, line ->
        line.forEachIndexed { col, ch ->
            if (ch == 'X') {
                drawRect(
                    color = color,
                    topLeft = Offset(topLeft.x + col * cellSize, topLeft.y + row * cellSize),
                    size = Size(cellSize, cellSize)
                )
            }
        }
    }
}
