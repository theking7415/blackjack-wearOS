package com.sensinglocal.blackjack.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTest {

    private fun cards(vararg ranks: Rank) = ranks.map { Card(it, Suit.SPADES) }

    @Test
    fun `hard total sums face values`() {
        val (total, soft) = handValue(cards(Rank.TEN, Rank.SEVEN))
        assertEquals(17, total)
        assertFalse(soft)
    }

    @Test
    fun `single ace counts as eleven when it fits`() {
        val (total, soft) = handValue(cards(Rank.ACE, Rank.SEVEN))
        assertEquals(18, total)
        assertTrue(soft)
    }

    @Test
    fun `ace downgrades to one to avoid busting`() {
        val (total, soft) = handValue(cards(Rank.ACE, Rank.SEVEN, Rank.NINE))
        assertEquals(17, total)
        assertFalse(soft)
    }

    @Test
    fun `two aces only count one as eleven`() {
        val (total, soft) = handValue(cards(Rank.ACE, Rank.ACE, Rank.NINE))
        assertEquals(21, total)
        assertTrue(soft)
    }

    @Test
    fun `three aces plus nine downgrades two aces`() {
        // 11 + 11 + 11 + 9 = 42 -> downgrade all the way to 1+1+11+9 = 22 -> bust check separately;
        // actually verify it settles at the lowest bust-avoiding total.
        val (total, soft) = handValue(cards(Rank.ACE, Rank.ACE, Rank.ACE, Rank.NINE))
        assertEquals(12, total)
        assertFalse(soft)
    }

    @Test
    fun `bust boundary is exactly past 21`() {
        assertFalse(isBust(cards(Rank.TEN, Rank.KING)))
        assertTrue(isBust(cards(Rank.TEN, Rank.KING, Rank.TWO)))
    }

    @Test
    fun `blackjack requires exactly two cards totalling 21`() {
        assertTrue(isBlackjack(cards(Rank.ACE, Rank.KING)))
        assertFalse(isBlackjack(cards(Rank.SEVEN, Rank.SEVEN, Rank.SEVEN)))
        assertFalse(isBlackjack(cards(Rank.TEN, Rank.SEVEN)))
    }

    @Test
    fun `deck deals every card in a single shoe with no duplicates`() {
        val deck = Deck(shoeCount = 1)
        val drawn = generateSequence { deck.draw() }.take(52).toList()
        assertEquals(52, drawn.toSet().size)
        assertEquals(0, deck.remaining())
    }

    @Test(expected = IllegalStateException::class)
    fun `drawing from an empty deck throws`() {
        val deck = Deck(shoeCount = 1)
        repeat(52) { deck.draw() }
        deck.draw()
    }
}
