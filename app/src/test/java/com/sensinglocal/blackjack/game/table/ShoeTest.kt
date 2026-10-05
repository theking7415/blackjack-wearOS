package com.sensinglocal.blackjack.game.table

import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.Rank
import com.sensinglocal.blackjack.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShoeTest {
    private val a = Card(Rank.ACE, Suit.SPADES)
    private val b = Card(Rank.TWO, Suit.HEARTS)

    @Test
    fun `draw returns the card and a new shoe without mutating the original`() {
        val shoe = Shoe.of(listOf(a, b))
        val (card, next) = shoe.draw()
        assertEquals(a, card)
        assertEquals(1, next.remaining)
        assertEquals(2, shoe.remaining)
        assertEquals(listOf(a, b), shoe.snapshot())
        assertEquals(listOf(b), next.snapshot())
    }

    @Test(expected = IllegalStateException::class)
    fun `drawing from an empty shoe throws`() {
        Shoe.of(emptyList()).draw()
    }

    @Test
    fun `fresh shoe holds every card shoeCount times`() {
        val cards = Shoe.fresh(4, Random(1)).snapshot()
        assertEquals(208, cards.size)
        assertTrue(cards.groupingBy { it }.eachCount().values.all { it == 4 })
    }

    @Test
    fun `same seed gives the same shuffle, different seed differs`() {
        assertEquals(Shoe.fresh(1, Random(7)), Shoe.fresh(1, Random(7)))
        assertNotEquals(Shoe.fresh(1, Random(7)), Shoe.fresh(1, Random(8)))
    }

    @Test
    fun `equality is by remaining cards, not position`() {
        val shoe = Shoe.of(listOf(a, b))
        assertEquals(Shoe.of(listOf(b)), shoe.draw().shoe)
    }
}
