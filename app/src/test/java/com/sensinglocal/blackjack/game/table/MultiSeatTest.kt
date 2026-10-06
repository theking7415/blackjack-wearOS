package com.sensinglocal.blackjack.game.table

import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.RoundResult
import com.sensinglocal.blackjack.game.Rank
import com.sensinglocal.blackjack.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * AI seats. Deal order with N betting seats: seat 0 card 1, ..., seat N-1 card 1, dealer up,
 * then the same again for card 2 and the dealer's hole card, then draws.
 */
class MultiSeatTest {
    private fun card(code: String): Card {
        val suit = when (code.last()) {
            'S' -> Suit.SPADES; 'H' -> Suit.HEARTS; 'D' -> Suit.DIAMONDS; else -> Suit.CLUBS
        }
        return Card(Rank.entries.first { it.label == code.dropLast(1) }, suit)
    }

    private fun ai(stack: Long = 100, style: StyleProfile = StyleProfile.STEADY) = SeatConfig(SeatKind.AI, stack, style)
    private fun human(stack: Long = 100) = SeatConfig(SeatKind.HUMAN, stack)

    private fun engine(
        seats: List<SeatConfig>,
        vararg codes: String,
        house: Long = 10_000,
        minBet: Long = 1
    ) = TableEngine(
        TableConfig(seats, reshuffleBelow = 0, houseBankroll = house, minBet = minBet),
        initialShoe = Shoe.of(codes.map(::card))
    )

    private fun TableEngine.seat(i: Int) = state.seats[i]

    // --- config ----------------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `more than four seats is rejected`() {
        TableConfig(listOf(human(), ai(), ai(), ai(), ai()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a table needs a human seat`() {
        TableConfig(listOf(ai(), ai()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a table cannot have two human seats`() {
        TableConfig(listOf(human(), human()))
    }

    @Test
    fun `a four-seat table is valid and reshuffles earlier by default`() {
        val config = TableConfig(listOf(ai(), human(), ai(), ai()))
        assertEquals(60, config.reshuffleBelow)
        assertEquals(15, TableConfig.singlePlayer(10).reshuffleBelow)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bet percent must be a sane percentage`() {
        StyleProfile(0)
    }

    // --- dealing ---------------------------------------------------------------------------

    @Test
    fun `cards go out one per seat in table order, then the dealer, twice`() {
        val e = engine(
            listOf(ai(), human(), ai()),
            "10S", "9H", "6D", "10C", "7S", "8H", "5D", "7C"
        )
        e.placeBet(10)
        assertEquals(listOf(card("10S"), card("7S")), e.seat(0).hands[0].cards)
        assertEquals(listOf(card("9H"), card("8H")), e.seat(1).hands[0].cards)
        assertEquals(listOf(card("6D"), card("5D")), e.seat(2).hands[0].cards)
        assertEquals(listOf(card("10C"), card("7C")), e.state.dealerCards)
    }

    @Test
    fun `AI bets are a percentage of their stack`() {
        val e = engine(
            listOf(ai(100, StyleProfile.RECKLESS), human(), ai(200, StyleProfile.CAUTIOUS)),
            "10S", "9H", "6D", "10C", "7S", "8H", "5D", "7C"
        )
        e.placeBet(10)
        assertEquals(25L, e.seat(0).hands[0].bet)
        assertEquals(10L, e.seat(1).hands[0].bet)
        assertEquals(10L, e.seat(2).hands[0].bet)
        assertEquals(75L, e.seat(0).stack)
        assertEquals(190L, e.seat(2).stack)
    }

    @Test
    fun `an AI bet is raised to the table minimum and never exceeds its stack`() {
        val e = engine(
            listOf(ai(100, StyleProfile(1)), human(), ai(8, StyleProfile(100))),
            "10S", "9H", "6D", "10C", "7S", "8H", "5D", "7C",
            minBet = 5
        )
        e.placeBet(10)
        assertEquals(5L, e.seat(0).hands[0].bet) // 1% = 1, raised to the minimum
        assertEquals(8L, e.seat(2).hands[0].bet) // 100% of 8, all-in
        assertEquals(0L, e.seat(2).stack)
    }

    @Test
    fun `an AI that cannot afford the minimum sits out the round`() {
        val e = engine(
            listOf(ai(4), human()),
            // Only the human and the dealer are dealt cards: human 10S+10C, dealer 9H+8H.
            "10S", "9H", "10C", "8H", "7C",
            minBet = 5
        )
        e.placeBet(10)
        assertTrue(e.seat(0).hands.isEmpty())
        assertEquals(4L, e.seat(0).stack)
        assertEquals(listOf(card("10S"), card("10C")), e.seat(1).hands[0].cards)
        assertEquals(listOf(card("9H"), card("8H")), e.state.dealerCards)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the human cannot bet below the table minimum`() {
        engine(listOf(human()), "10S", "9H", "7D", "8C", minBet = 10).placeBet(5)
    }

    // --- turn order ------------------------------------------------------------------------

    @Test
    fun `an earlier AI seat acts before the human and step plays one action at a time`() {
        // AI: 10+7 = 17 stands. Human: 9+8 = 17.
        val e = engine(listOf(ai(), human()), "10S", "9H", "10C", "7S", "8H", "7C")
        e.placeBet(10)
        assertEquals(0, e.state.activeSeat)
        assertTrue(e.step()) // AI stands
        assertEquals(1, e.state.activeSeat)
        assertFalse(e.step()) // human's turn: nothing to do
        assertEquals(1, e.state.activeSeat)
    }

    @Test(expected = IllegalStateException::class)
    fun `the human cannot act while an AI seat is up`() {
        val e = engine(listOf(ai(), human()), "10S", "9H", "10C", "7S", "8H", "7C")
        e.placeBet(10)
        e.stand()
    }

    @Test(expected = IllegalStateException::class)
    fun `step cannot make the human's move`() {
        val e = engine(listOf(human()), "10S", "9H", "7D", "8C")
        e.placeBet(10)
        assertFalse(e.step())
        e.stand(); e.stand()
    }

    @Test
    fun `step does nothing outside a seat turn`() {
        val e = engine(listOf(ai(), human()), "10S", "9H", "10C", "7S", "8H", "7C")
        assertFalse(e.step()) // betting
    }

    @Test
    fun `an AI follows basic strategy, hitting 16 against a 10`() {
        // AI: 10+6 = 16 vs dealer 10 -> hit, draws the 4 -> 20, then stands.
        val e = engine(listOf(ai(), human()), "10S", "9H", "10C", "6S", "8H", "7C", "4D")
        e.placeBet(10)
        assertTrue(e.step())
        assertEquals(3, e.seat(0).hands[0].cards.size)
        assertEquals(20, e.seat(0).hands[0].total)
        assertTrue(e.step()) // 20 -> stand
        assertEquals(1, e.state.activeSeat)
    }

    @Test
    fun `an AI can double down and split`() {
        // AI: 8+8 splits (always), each draws a card. Human then just stands.
        val e = engine(listOf(ai(), human()), "8S", "9H", "10C", "8D", "8H", "7C", "3S", "10D")
        e.placeBet(10)
        assertTrue(e.step())
        assertEquals(2, e.seat(0).hands.size)
        assertTrue(e.seat(0).hands.all { it.fromSplit })
        assertEquals(80L, e.seat(0).stack) // bet 10 + split bet 10 from 100
    }

    @Test
    fun `a natural for an AI seat skips its turn and hands over to the human`() {
        val e = engine(listOf(ai(), human()), "AS", "9H", "10C", "KS", "8H", "7C")
        e.placeBet(10)
        assertEquals(1, e.state.activeSeat) // AI seat 0 already has a natural
        assertFalse(e.step())
    }

    // --- settlement across seats ------------------------------------------------------------

    @Test
    fun `every seat is settled against the dealer`() {
        // AI 10+8 = 18 stands and wins; human 10+6 = 16 stands and loses; dealer 10+7 = 17.
        val e = engine(listOf(ai(), human()), "10S", "10H", "10C", "8S", "6H", "7C")
        e.placeBet(10)
        while (e.step()) Unit
        e.stand()
        assertEquals(RoundResult.PLAYER_WIN, e.seat(0).hands[0].result)
        assertEquals(RoundResult.DEALER_WIN, e.seat(1).hands[0].result)
        assertEquals(110L, e.seat(0).stack)
        assertEquals(90L, e.seat(1).stack)
        assertEquals(10_000L, e.state.house + e.seat(0).stack + e.seat(1).stack - 200)
    }

    @Test
    fun `a short house pays the human before the AI seats`() {
        // Both win with 18 vs dealer 17, but the house only has 10 to pay out.
        val e = engine(listOf(ai(), human()), "10S", "10H", "10C", "8S", "8H", "7C", house = 10)
        e.placeBet(10)
        while (e.step()) Unit
        e.stand()
        assertEquals(20L, e.seat(1).hands[0].paid) // human: stake + full 10
        assertEquals(10L, e.seat(0).hands[0].paid) // AI: stake only, the house is empty
        assertTrue(e.state.houseBroke)
    }

    @Test
    fun `an AI seat that busts out sits out later rounds`() {
        val e = engine(
            listOf(ai(10, StyleProfile(100)), human()),
            // Round 1: AI bets its whole 10 with 16 vs dealer 10, hits, draws a 10 and busts.
            // The human stands on 19.
            "10S", "9H", "10C", "6S", "10H", "7C", "10D",
            // Round 2: only the human and dealer are dealt cards.
            "10S", "9H", "10C", "8H"
        )
        e.placeBet(10)
        while (e.step()) Unit
        e.stand()
        assertEquals(0L, e.seat(0).stack)
        e.startNextRound()
        e.placeBet(10)
        assertTrue(e.seat(0).hands.isEmpty())
        assertEquals(0L, e.seat(0).stack)
        assertEquals(listOf(card("10S"), card("10C")), e.seat(1).hands[0].cards)
        assertEquals(listOf(card("9H"), card("8H")), e.state.dealerCards)
    }

    // --- property: money is conserved with a full table ------------------------------------

    @Test
    fun `stacks plus house are conserved across a full table of random rounds`() {
        val random = Random(7)
        val seats = listOf(
            ai(300, StyleProfile.RECKLESS), human(400), ai(300, StyleProfile.STEADY), ai(300, StyleProfile.CAUTIOUS)
        )
        val total = seats.sumOf { it.stack } + 2_000L
        val e = TableEngine(TableConfig(seats, houseBankroll = 2_000L), random = random)
        var rounds = 0
        while (rounds < 300 && e.state.humanSeat.stack > 0 && !e.state.houseBroke) {
            e.placeBet(minOf(10L, e.state.humanSeat.stack))
            while (e.state.phase == TablePhase.SEAT_TURN) {
                if (e.step()) continue
                val hand = e.state.activeHand!!
                when (BasicStrategy.decide(hand, e.state.dealerCards.first(), e.state.canDoubleDown, e.state.canSplit)) {
                    TableAction.HIT -> e.hit()
                    TableAction.STAND -> e.stand()
                    TableAction.DOUBLE -> e.doubleDown()
                    TableAction.SPLIT -> e.split()
                }
            }
            assertEquals(total, e.state.seats.sumOf { it.stack } + e.state.house)
            e.startNextRound()
            rounds++
        }
        assertTrue(rounds > 5)
    }
}
