package com.sensinglocal.blackjack

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where each seat sits on the table screen. Seats are spread evenly (360/n degrees: 90 apart for a
 * full 4-seat table) around an ellipse centred on the dealer, and the whole ring turns so the seat
 * whose turn it is lands at the bottom ("front") at full size. Everything is a fraction of the
 * screen's side length so it holds on any round display; tune the constants here, not in the UI.
 */
object SeatRing {
    /** Ellipse centre = where the dealer sits (above screen centre, to leave room for the controls). */
    const val CENTER_Y = -0.1235f
    const val RADIUS_X = 0.36f
    const val RADIUS_Y = 0.2465f
    const val MINI_SCALE = 0.5f
    const val DEALER_SCALE = 0.75f

    /** [x]/[y] are offsets from the screen centre as fractions of the screen side. */
    data class Slot(val x: Float, val y: Float, val closeness: Float) {
        /** 1.0 for the front seat, [MINI_SCALE] once a full step away, blended in between. */
        val scale: Float get() = MINI_SCALE + (1f - MINI_SCALE) * closeness
    }

    fun spacingDegrees(seatCount: Int): Float = 360f / seatCount

    /**
     * [front] is the (animated, possibly fractional) seat index currently at the bottom. A seat one
     * step after it sits at the left, then the top, then the right, so play goes round the table.
     */
    fun slot(seatIndex: Int, front: Float, seatCount: Int): Slot {
        val spacing = spacingDegrees(seatCount)
        val theta = 90f + (seatIndex - front) * spacing
        val radians = Math.toRadians(theta.toDouble())
        var offFront = (theta - 90f) % 360f
        if (offFront > 180f) offFront -= 360f
        if (offFront < -180f) offFront += 360f
        return Slot(
            x = (RADIUS_X * cos(radians)).toFloat(),
            y = (CENTER_Y + RADIUS_Y * sin(radians)).toFloat(),
            closeness = (1f - abs(offFront) / spacing).coerceIn(0f, 1f)
        )
    }

    /**
     * The value to animate [current] to so seat [target] ends up in front by the shortest way round
     * (seats are cyclic, so going from the last seat back to the first should step forward, not spin back).
     */
    fun unwrapTarget(current: Float, target: Int, seatCount: Int): Float {
        val n = seatCount.toFloat()
        var delta = (target - current) % n
        if (delta > n / 2f) delta -= n
        if (delta < -n / 2f) delta += n
        return current + delta
    }
}

/**
 * Gap between cards in a hand: [normalSpacing] normally, or negative (cards overlap, like a fanned
 * hand) when [count] cards would otherwise be wider than [maxWidth]. All values in the same unit.
 */
fun cardSpacing(count: Int, cardWidth: Float, maxWidth: Float, normalSpacing: Float): Float {
    if (count <= 1) return normalSpacing
    val needed = count * cardWidth + (count - 1) * normalSpacing
    return if (needed <= maxWidth) normalSpacing else (maxWidth - count * cardWidth) / (count - 1)
}

/** Short money text for tiny labels: 950, 1.2K, 12K, 1.5M, 2B... (truncates, never rounds up). */
fun compactAmount(value: Long): String {
    if (value < 0) return "-" + compactAmount(-value)
    if (value < 1000) return value.toString()
    val units = listOf("K", "M", "B", "T")
    // Integer math throughout: doubles turn 1900 into 1.8999... and print "1.8K".
    var unit = 0
    var divisor = 1000L
    while (value / divisor >= 1000 && unit < units.lastIndex) {
        divisor *= 1000
        unit++
    }
    val whole = value / divisor
    val tenth = (value % divisor) * 10 / divisor
    val number = if (whole >= 10 || tenth == 0L) whole.toString() else "$whole.$tenth"
    return number + units[unit]
}
