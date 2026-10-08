package com.sensinglocal.blackjack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TableLayoutMathTest {
    private val eps = 0.0001f

    // --- SeatRing --------------------------------------------------------------------------

    @Test
    fun `the front seat is bottom centre at full size`() {
        val s = SeatRing.slot(2, front = 2f, seatCount = 4)
        assertEquals(0f, s.x, eps)
        assertEquals(SeatRing.CENTER_Y + SeatRing.RADIUS_Y, s.y, eps)
        assertEquals(1f, s.closeness, eps)
        assertEquals(1f, s.scale, eps)
    }

    @Test
    fun `a four-seat table puts the other seats left, top and right, 90 degrees apart, at mini size`() {
        val left = SeatRing.slot(1, front = 0f, seatCount = 4)
        val top = SeatRing.slot(2, front = 0f, seatCount = 4)
        val right = SeatRing.slot(3, front = 0f, seatCount = 4)
        assertEquals(-SeatRing.RADIUS_X, left.x, eps)
        assertEquals(SeatRing.CENTER_Y, left.y, eps)
        assertEquals(0f, top.x, eps)
        assertEquals(SeatRing.CENTER_Y - SeatRing.RADIUS_Y, top.y, eps)
        assertEquals(SeatRing.RADIUS_X, right.x, eps)
        assertEquals(SeatRing.CENTER_Y, right.y, eps)
        for (s in listOf(left, top, right)) {
            assertEquals(0f, s.closeness, eps)
            assertEquals(SeatRing.MINI_SCALE, s.scale, eps)
        }
    }

    @Test
    fun `halfway through a turn two seats are half-way between mini and full`() {
        val leaving = SeatRing.slot(0, front = 0.5f, seatCount = 4)
        val arriving = SeatRing.slot(1, front = 0.5f, seatCount = 4)
        assertEquals(0.5f, leaving.closeness, eps)
        assertEquals(0.5f, arriving.closeness, eps)
        assertEquals(0.75f, leaving.scale, eps)
    }

    @Test
    fun `positions repeat after a full lap`() {
        for (i in 0 until 4) {
            val a = SeatRing.slot(i, front = 1.3f, seatCount = 4)
            val b = SeatRing.slot(i, front = 5.3f, seatCount = 4)
            assertEquals(a.x, b.x, eps)
            assertEquals(a.y, b.y, eps)
            assertEquals(a.closeness, b.closeness, eps)
        }
    }

    @Test
    fun `smaller tables spread seats evenly`() {
        assertEquals(180f, SeatRing.spacingDegrees(2), eps)
        assertEquals(120f, SeatRing.spacingDegrees(3), eps)
        val other = SeatRing.slot(1, front = 0f, seatCount = 2)
        assertEquals(0f, other.x, eps)
        assertEquals(SeatRing.CENTER_Y - SeatRing.RADIUS_Y, other.y, eps) // opposite the front: top
        assertEquals(0f, other.closeness, eps)
        assertEquals(1f, SeatRing.slot(0, front = 0f, seatCount = 1).closeness, eps)
    }

    @Test
    fun `the ring turns the short way round, wrapping from the last seat to the first`() {
        assertEquals(4f, SeatRing.unwrapTarget(3f, 0, 4), eps)  // 3 -> 0 steps forward, not back three
        assertEquals(-1f, SeatRing.unwrapTarget(0f, 3, 4), eps) // 0 -> 3 steps back one
        assertEquals(1f, SeatRing.unwrapTarget(1.2f, 1, 4), eps)
        assertEquals(2f, SeatRing.unwrapTarget(1f, 2, 4), eps)
    }

    @Test
    fun `seat ends up in front after unwrapping from anywhere`() {
        for (current in listOf(0f, 0.7f, 2.9f, 3.0f, 7.5f, -2.2f)) {
            for (target in 0 until 4) {
                val unwrapped = SeatRing.unwrapTarget(current, target, 4)
                assertEquals(1f, SeatRing.slot(target, unwrapped, 4).closeness, eps)
                assertTrue(Math.abs(unwrapped - current) <= 2f + eps)
            }
        }
    }

    // --- cardSpacing -----------------------------------------------------------------------

    @Test
    fun `cards keep their normal spacing while they fit`() {
        assertEquals(3f, cardSpacing(1, 34f, 113f, 3f), eps)
        assertEquals(3f, cardSpacing(2, 34f, 113f, 3f), eps)
        assertEquals(3f, cardSpacing(3, 34f, 113f, 3f), eps) // 3*34 + 2*3 = 108 <= 113
    }

    @Test
    fun `a wide hand overlaps so it never exceeds the max width`() {
        val spacing = cardSpacing(5, 34f, 113f, 3f)
        assertTrue(spacing < 0f)
        assertEquals(113f, 5 * 34f + 4 * spacing, eps)
    }

    // --- compactAmount ---------------------------------------------------------------------

    @Test
    fun `small amounts are shown in full`() {
        assertEquals("0", compactAmount(0))
        assertEquals("950", compactAmount(950))
        assertEquals("999", compactAmount(999))
    }

    @Test
    fun `thousands millions and billions are abbreviated`() {
        assertEquals("1K", compactAmount(1000))
        assertEquals("1.2K", compactAmount(1234))
        assertEquals("1.9K", compactAmount(1900))
        assertEquals("12K", compactAmount(12_345))
        assertEquals("999K", compactAmount(999_999))
        assertEquals("1M", compactAmount(1_000_000))
        assertEquals("1.5M", compactAmount(1_500_000))
        assertEquals("2B", compactAmount(2_000_000_000))
    }

    @Test
    fun `negative amounts keep their sign`() {
        assertEquals("-1.5K", compactAmount(-1500))
        assertEquals("-25", compactAmount(-25))
    }
}
