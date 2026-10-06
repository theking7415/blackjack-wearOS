package com.sensinglocal.blackjack.game.table

import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.HandStatus
import com.sensinglocal.blackjack.game.Rank
import com.sensinglocal.blackjack.game.RoundResult
import com.sensinglocal.blackjack.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Every test rigs the shoe, so each scenario is exact (no "loop until it comes up by chance").
 * Deal order is: seat card 1, dealer up-card, seat card 2, dealer hole card, then draws.
 */
class TableEngineTest {
    /** "AS", "10H", "KD", "7C" -> Card. */
    private fun card(code: String): Card {
        val suit = when (code.last()) {
            'S' -> Suit.SPADES; 'H' -> Suit.HEARTS; 'D' -> Suit.DIAMONDS; else -> Suit.CLUBS
        }
        val label = code.dropLast(1)
        return Card(Rank.entries.first { it.label == label }, suit)
    }

    private fun engine(vararg codes: String, stack: Long = 100): TableEngine =
        TableEngine(
            TableConfig(listOf(SeatConfig(SeatKind.HUMAN, stack)), reshuffleBelow = 0),
            initialShoe = Shoe.of(codes.map(::card))
        )

    private val TableEngine.hand get() = state.humanSeat.hands[0]
    private val TableEngine.stack get() = state.humanSeat.stack

    // --- setup / betting -------------------------------------------------------------------

    @Test
    fun `starts in betting with the full stack`() {
        val e = TableEngine(TableConfig.singlePlayer(500), random = Random(1))
        assertEquals(TablePhase.BETTING, e.state.phase)
        assertEquals(500L, e.stack)
        assertEquals(208, e.state.shoe.remaining)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bet of zero is rejected`() {
        engine("10S", "9H", "7D", "8C").placeBet(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bet above the stack is rejected`() {
        engine("10S", "9H", "7D", "8C", stack = 50).placeBet(51)
    }

    @Test(expected = IllegalStateException::class)
    fun `betting twice in one round is rejected`() {
        val e = engine("10S", "9H", "7D", "8C")
        e.placeBet(10)
        e.placeBet(10)
    }

    @Test
    fun `deal order alternates seat and dealer, deducts the bet, hides the hole card`() {
        val e = engine("10S", "9H", "7D", "8C", "2S")
        e.placeBet(10)
        assertEquals(listOf(card("10S"), card("7D")), e.hand.cards)
        assertEquals(listOf(card("9H"), card("8C")), e.state.dealerCards)
        assertEquals(90L, e.stack)
        assertEquals(10L, e.hand.bet)
        assertEquals(TablePhase.SEAT_TURN, e.state.phase)
        assertFalse(e.state.dealerHoleCardRevealed)
        assertEquals(1, e.state.shoe.remaining)
    }

    @Test
    fun `a shoe below the reshuffle threshold is replaced before dealing`() {
        val e = TableEngine(
            TableConfig(listOf(SeatConfig(SeatKind.HUMAN, 100)), shoeCount = 4, reshuffleBelow = 15),
            initialShoe = Shoe.of(listOf("10S", "9H", "7D", "8C").map(::card)),
            random = Random(3)
        )
        e.placeBet(10)
        assertEquals(204, e.state.shoe.remaining)
    }

    // --- hit / stand / dealer --------------------------------------------------------------

    @Test
    fun `hit to bust ends the round without the dealer drawing`() {
        val e = engine("10S", "9H", "6D", "8C", "10H", "5C")
        e.placeBet(10)
        e.hit()
        assertEquals(HandStatus.BUST, e.hand.status)
        assertEquals(RoundResult.PLAYER_BUST, e.hand.result)
        assertEquals(2, e.state.dealerCards.size)
        assertEquals(90L, e.stack)
        assertEquals(TablePhase.ROUND_OVER, e.state.phase)
        assertTrue(e.state.dealerHoleCardRevealed)
    }

    @Test
    fun `hit below 21 keeps the turn`() {
        val e = engine("5S", "9H", "4D", "8C", "2H")
        e.placeBet(10)
        e.hit()
        assertEquals(TablePhase.SEAT_TURN, e.state.phase)
        assertEquals(11, e.hand.total)
        assertEquals(HandStatus.PLAYING, e.hand.status)
    }

    @Test
    fun `stand against a hard 17 wins with 18`() {
        val e = engine("10S", "10H", "8D", "7C", "2S")
        e.placeBet(10)
        e.stand()
        assertEquals(2, e.state.dealerCards.size)
        assertEquals(RoundResult.PLAYER_WIN, e.hand.result)
        assertEquals(110L, e.stack)
    }

    @Test
    fun `dealer hits soft 17`() {
        // Dealer A+6 is soft 17: must draw. The 4 makes soft 21.
        val e = engine("10S", "AH", "10D", "6C", "4S")
        e.placeBet(10)
        e.stand()
        assertEquals(3, e.state.dealerCards.size)
        assertEquals(21, e.state.dealerTotal)
        assertEquals(RoundResult.DEALER_WIN, e.hand.result)
        assertEquals(90L, e.stack)
    }

    @Test
    fun `dealer draws to bust and the player is paid`() {
        val e = engine("10S", "10H", "8D", "6C", "10D")
        e.placeBet(10)
        e.stand()
        assertEquals(RoundResult.DEALER_BUST, e.hand.result)
        assertEquals(110L, e.stack)
    }

    @Test
    fun `equal totals push and return the bet`() {
        val e = engine("10S", "10H", "8D", "8C")
        e.placeBet(10)
        e.stand()
        assertEquals(RoundResult.PUSH, e.hand.result)
        assertEquals(100L, e.stack)
    }

    @Test
    fun `player loses when dealer has the higher total`() {
        val e = engine("10S", "10H", "7D", "9C")
        e.placeBet(10)
        e.stand()
        assertEquals(RoundResult.DEALER_WIN, e.hand.result)
        assertEquals(90L, e.stack)
    }

    // --- naturals --------------------------------------------------------------------------

    @Test
    fun `natural blackjack pays 3 to 2 and ends the round at once`() {
        val e = engine("AS", "9H", "KD", "8C")
        e.placeBet(10)
        assertEquals(TablePhase.ROUND_OVER, e.state.phase)
        assertEquals(RoundResult.PLAYER_BLACKJACK, e.hand.result)
        assertEquals(115L, e.stack)
        assertEquals(2, e.state.dealerCards.size)
    }

    @Test
    fun `blackjack payout truncates odd half-chips`() {
        val e = engine("AS", "9H", "KD", "8C")
        e.placeBet(5)
        assertEquals(100L - 5 + 5 + 7, e.stack)
    }

    @Test
    fun `player blackjack against dealer blackjack is a push`() {
        val e = engine("AS", "AH", "KD", "QC")
        e.placeBet(10)
        assertEquals(RoundResult.PUSH, e.hand.result)
        assertEquals(100L, e.stack)
    }

    @Test
    fun `dealer blackjack beats a non-natural 21`() {
        val e = engine("10S", "AH", "5D", "KC", "6S")
        e.placeBet(10)
        e.hit() // 21 on three cards
        e.stand()
        assertEquals(RoundResult.DEALER_WIN, e.hand.result)
        assertEquals(90L, e.stack)
    }

    // --- double down -----------------------------------------------------------------------

    @Test
    fun `double down takes one card, doubles the bet and settles`() {
        val e = engine("5S", "10H", "6D", "7C", "10S")
        e.placeBet(10)
        assertTrue(e.state.canDoubleDown)
        e.doubleDown()
        assertEquals(3, e.hand.cards.size)
        assertEquals(20L, e.hand.bet)
        assertEquals(RoundResult.PLAYER_WIN, e.hand.result) // 21 vs 17
        assertEquals(120L, e.stack)
        assertEquals(TablePhase.ROUND_OVER, e.state.phase)
    }

    @Test
    fun `double down that busts loses the doubled bet`() {
        val e = engine("10S", "9H", "6D", "8C", "10H")
        e.placeBet(10)
        e.doubleDown()
        assertEquals(HandStatus.BUST, e.hand.status)
        assertEquals(80L, e.stack)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `double down needs the stack to cover the extra bet`() {
        val e = engine("5S", "10H", "6D", "7C", "10S", stack = 15)
        e.placeBet(10)
        assertFalse(e.state.canDoubleDown)
        e.doubleDown()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `double down is rejected after the first two cards`() {
        val e = engine("2S", "10H", "3D", "7C", "2H", "10S")
        e.placeBet(10)
        e.hit()
        e.doubleDown()
    }

    // --- split -----------------------------------------------------------------------------

    @Test
    fun `split makes two hands, one new card each, and a second bet`() {
        val e = engine("8S", "10H", "8D", "7C", "3S", "4H")
        e.placeBet(10)
        assertTrue(e.state.canSplit)
        e.split()
        val hands = e.state.humanSeat.hands
        assertEquals(listOf(card("8S"), card("3S")), hands[0].cards)
        assertEquals(listOf(card("8D"), card("4H")), hands[1].cards)
        assertTrue(hands.all { it.fromSplit && it.bet == 10L })
        assertEquals(80L, e.stack)
        assertEquals(0, e.state.activeHandIndex)
        assertFalse(e.state.canSplit)
    }

    @Test
    fun `split hands play in order then settle independently`() {
        val e = engine("8S", "10H", "8D", "7C", "3S", "4H", "10C", "2D")
        e.placeBet(10)
        e.split()
        e.hit() // hand 0: 8+3+10 = 21
        e.stand()
        assertEquals(1, e.state.activeHandIndex)
        e.stand() // hand 1: 8+4 = 12 vs dealer 17
        assertEquals(RoundResult.PLAYER_WIN, e.state.humanSeat.hands[0].result)
        assertEquals(RoundResult.DEALER_WIN, e.state.humanSeat.hands[1].result)
        assertEquals(100L - 20 + 20, e.stack)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `re-splitting is rejected`() {
        val e = engine("8S", "10H", "8D", "7C", "8H", "4H")
        e.placeBet(10)
        e.split()
        e.split()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `only an equal-rank pair can split`() {
        val e = engine("KS", "10H", "QD", "7C")
        e.placeBet(10)
        e.split()
    }

    @Test
    fun `split aces get one card each and stand immediately`() {
        val e = engine("AS", "10H", "AD", "7C", "KS", "9H")
        e.placeBet(10)
        e.split()
        assertEquals(TablePhase.ROUND_OVER, e.state.phase)
        val hands = e.state.humanSeat.hands
        assertTrue(hands.all { it.cards.size == 2 })
        // A+K from a split is 21 but not a natural: paid 1:1, not 3:2.
        assertFalse(hands[0].isNaturalBlackjack)
        assertEquals(RoundResult.PLAYER_WIN, hands[0].result)
        assertEquals(RoundResult.PLAYER_WIN, hands[1].result) // 20 vs 17
        assertEquals(100L - 20 + 20 + 20, e.stack)
    }

    // --- rounds, money, immutability --------------------------------------------------------

    @Test
    fun `startNextRound clears the table and counts the round`() {
        val e = engine("10S", "10H", "8D", "8C")
        e.placeBet(10)
        e.stand()
        e.startNextRound()
        assertEquals(TablePhase.BETTING, e.state.phase)
        assertTrue(e.state.humanSeat.hands.isEmpty())
        assertTrue(e.state.dealerCards.isEmpty())
        assertEquals(1, e.state.round)
        assertEquals(100L, e.stack)
    }

    @Test(expected = IllegalStateException::class)
    fun `startNextRound is rejected mid-round`() {
        val e = engine("10S", "10H", "8D", "8C")
        e.placeBet(10)
        e.startNextRound()
    }

    @Test(expected = IllegalStateException::class)
    fun `actions are rejected outside a seat turn`() {
        engine("10S", "10H", "8D", "8C").hit()
    }

    @Test
    fun `money beyond Int range does not overflow`() {
        val e = engine("AS", "9H", "KD", "8C", stack = 3_000_000_000L)
        e.placeBet(2_000_000_000L)
        assertEquals(3_000_000_000L - 2_000_000_000L + 2_000_000_000L + 3_000_000_000L, e.stack)
    }

    @Test
    fun `earlier states are never mutated by later actions`() {
        val e = engine("5S", "9H", "4D", "8C", "2H", "3S")
        e.placeBet(10)
        val before = e.state
        e.hit()
        assertEquals(2, before.humanSeat.hands[0].cards.size)
        assertEquals(2, before.shoe.remaining)
        assertEquals(1, e.state.shoe.remaining)
    }
}
