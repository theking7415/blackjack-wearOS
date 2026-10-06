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

/** House pool + settlement rules. Deal order: seat 1, dealer up, seat 2, dealer hole, then draws. */
class HouseBankrollTest {
    private fun card(code: String): Card {
        val suit = when (code.last()) {
            'S' -> Suit.SPADES; 'H' -> Suit.HEARTS; 'D' -> Suit.DIAMONDS; else -> Suit.CLUBS
        }
        return Card(Rank.entries.first { it.label == code.dropLast(1) }, suit)
    }

    private fun engine(vararg codes: String, stack: Long = 100, house: Long = 1000): TableEngine =
        TableEngine(
            TableConfig(listOf(SeatConfig(SeatKind.HUMAN, stack)), reshuffleBelow = 0, houseBankroll = house),
            initialShoe = Shoe.of(codes.map(::card))
        )

    private val TableEngine.hand get() = state.humanSeat.hands[0]
    private val TableEngine.stack get() = state.humanSeat.stack

    // 4-card shoes in deal order: WIN = player 18 vs dealer 17, LOSS = 17 vs 19, PUSH = 18 vs 18.
    private val WIN = arrayOf("10S", "10H", "8D", "7C")
    private val LOSS = arrayOf("10S", "10H", "7D", "9C")
    private val PUSH = arrayOf("10S", "10H", "8D", "8C")

    @Test
    fun `the house starts with the configured pool`() {
        assertEquals(1000L, engine(*WIN).state.house)
        assertFalse(engine(*WIN).state.houseBroke)
    }

    @Test
    fun `a losing bet moves to the house`() {
        val e = engine(*LOSS)
        e.placeBet(10); e.stand()
        assertEquals(90L, e.stack)
        assertEquals(1010L, e.state.house)
        assertEquals(0L, e.hand.paid)
    }

    @Test
    fun `a win is paid from the house, stake returned from the seat's own money`() {
        val e = engine(*WIN)
        e.placeBet(10); e.stand()
        assertEquals(110L, e.stack)
        assertEquals(990L, e.state.house)
        assertEquals(20L, e.hand.paid)
        assertEquals(e.hand.fullPayout, e.hand.paid)
    }

    @Test
    fun `a push leaves the house untouched`() {
        val e = engine(*PUSH)
        e.placeBet(10); e.stand()
        assertEquals(100L, e.stack)
        assertEquals(1000L, e.state.house)
        assertEquals(10L, e.hand.paid)
    }

    @Test
    fun `natural blackjack costs the house 3 to 2 truncated`() {
        val e = engine("AS", "9H", "KD", "8C")
        e.placeBet(5)
        assertEquals(1000L - 7, e.state.house)
        assertEquals(100L - 5 + 5 + 7, e.stack)
    }

    @Test
    fun `a short house pays what it has and is then broke`() {
        val e = engine(*WIN, house = 4)
        e.placeBet(10); e.stand()
        assertEquals(RoundResult.PLAYER_WIN, e.hand.result)
        assertEquals(14L, e.hand.paid) // 10 stake + only 4 of the 10 owed
        assertTrue(e.hand.paid < e.hand.fullPayout)
        assertEquals(90L + 14L, e.stack)
        assertEquals(0L, e.state.house)
        assertTrue(e.state.houseBroke)
    }

    @Test
    fun `a house that exactly covers the payout pays in full and is broke`() {
        val e = engine(*WIN, house = 10)
        e.placeBet(10); e.stand()
        assertEquals(e.hand.fullPayout, e.hand.paid)
        assertEquals(0L, e.state.house)
        assertTrue(e.state.houseBroke)
    }

    @Test
    fun `a push is paid in full even when the house is nearly empty`() {
        val e = engine(*PUSH, house = 1)
        e.placeBet(10); e.stand()
        assertEquals(10L, e.hand.paid)
        assertEquals(1L, e.state.house)
    }

    @Test
    fun `losing bets are collected before winners are paid`() {
        // Split 8s: hand 0 (8+3=11) loses to dealer 17, hand 1 (8+10=18) wins. House starts with
        // 5 but has 15 after collecting the loss, so the 10 owed is paid in full.
        val e = engine("8S", "10H", "8D", "7C", "3S", "10C", house = 5)
        e.placeBet(10)
        e.split()
        e.stand(); e.stand()
        val hands = e.state.humanSeat.hands
        assertEquals(RoundResult.DEALER_WIN, hands[0].result)
        assertEquals(RoundResult.PLAYER_WIN, hands[1].result)
        assertEquals(hands[1].fullPayout, hands[1].paid)
        assertEquals(5L, e.state.house)
        assertEquals(100L - 20 + 20, e.stack)
        assertFalse(e.state.houseBroke)
    }

    @Test(expected = IllegalStateException::class)
    fun `betting at a broke table is rejected`() {
        val e = engine(*WIN, house = 10)
        e.placeBet(10); e.stand()
        e.startNextRound()
        e.placeBet(10)
    }

    @Test
    fun `an unlimited-house table never goes broke from one round`() {
        // reshuffleBelow = 0 keeps the rigged 4-card shoe (the default would swap it for a random one).
        val e = TableEngine(
            TableConfig.singlePlayer(100).copy(reshuffleBelow = 0),
            initialShoe = Shoe.of(WIN.map(::card))
        )
        e.placeBet(10); e.stand()
        assertFalse(e.state.houseBroke)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero house bankroll is rejected`() {
        TableConfig.singlePlayer(100, houseBankroll = 0)
    }

    @Test
    fun `settlement order puts the human first then the rest in seat order`() {
        val state = TableState(
            seats = listOf(Seat(SeatKind.AI, 10), Seat(SeatKind.HUMAN, 10), Seat(SeatKind.AI, 10), Seat(SeatKind.AI, 10)),
            shoe = Shoe.of(emptyList()),
            house = 100
        )
        assertEquals(listOf(1, 0, 2, 3), state.settlementOrder)
    }

    @Test
    fun `stack plus house is conserved across many random rounds`() {
        val random = Random(42)
        val stack = 500L
        val house = 800L
        val e = TableEngine(
            TableConfig.singlePlayer(stack, house),
            random = random
        )
        var rounds = 0
        while (rounds < 300 && e.state.humanSeat.stack > 0 && !e.state.houseBroke) {
            e.placeBet(minOf(10L, e.state.humanSeat.stack))
            while (e.state.phase == TablePhase.SEAT_TURN) {
                val hand = e.state.activeHand!!
                when {
                    e.state.canSplit && random.nextBoolean() -> e.split()
                    e.state.canDoubleDown && random.nextInt(4) == 0 -> e.doubleDown()
                    hand.total < 15 -> e.hit()
                    else -> e.stand()
                }
            }
            assertEquals(stack + house, e.state.humanSeat.stack + e.state.house)
            if (e.state.humanSeat.hands.any { it.status == HandStatus.PLAYING }) throw AssertionError("round left a hand playing")
            e.startNextRound()
            rounds++
        }
        assertTrue(rounds > 0)
    }
}
