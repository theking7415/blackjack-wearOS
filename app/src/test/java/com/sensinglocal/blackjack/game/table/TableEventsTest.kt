package com.sensinglocal.blackjack.game.table

import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.Rank
import com.sensinglocal.blackjack.game.RoundResult
import com.sensinglocal.blackjack.game.Suit
import com.sensinglocal.blackjack.game.table.TableEvent.ActionTaken
import com.sensinglocal.blackjack.game.table.TableEvent.BetPlaced
import com.sensinglocal.blackjack.game.table.TableEvent.Bust
import com.sensinglocal.blackjack.game.table.TableEvent.DealerBust
import com.sensinglocal.blackjack.game.table.TableEvent.DealerCardDealt
import com.sensinglocal.blackjack.game.table.TableEvent.HandSettled
import com.sensinglocal.blackjack.game.table.TableEvent.HoleCardRevealed
import com.sensinglocal.blackjack.game.table.TableEvent.HouseBroke
import com.sensinglocal.blackjack.game.table.TableEvent.Natural
import com.sensinglocal.blackjack.game.table.TableEvent.SeatBrokeOut
import com.sensinglocal.blackjack.game.table.TableEvent.SeatCardDealt
import com.sensinglocal.blackjack.game.table.TableEvent.ShoeReshuffled
import com.sensinglocal.blackjack.game.table.TableEvent.TurnStarted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Events are exact descriptions of what happened, in order (rigged shoes; see MultiSeatTest for deal order). */
class TableEventsTest {
    private fun card(code: String): Card {
        val suit = when (code.last()) {
            'S' -> Suit.SPADES; 'H' -> Suit.HEARTS; 'D' -> Suit.DIAMONDS; else -> Suit.CLUBS
        }
        return Card(Rank.entries.first { it.label == code.dropLast(1) }, suit)
    }

    private fun solo(vararg codes: String, stack: Long = 100, house: Long = 10_000) = engine(
        listOf(SeatConfig(SeatKind.HUMAN, stack)), *codes, house = house
    )

    private fun engine(seats: List<SeatConfig>, vararg codes: String, house: Long = 10_000) = TableEngine(
        TableConfig(seats, reshuffleBelow = 0, houseBankroll = house),
        initialShoe = Shoe.of(codes.map(::card))
    )

    @Test
    fun `placeBet reports bets, the deal in table order, then whose turn it is`() {
        val e = engine(
            listOf(SeatConfig(SeatKind.AI, 100), SeatConfig(SeatKind.HUMAN, 100)),
            "10S", "9H", "10C", "7S", "8H", "7C"
        )
        assertEquals(
            listOf(
                BetPlaced(0, 10), BetPlaced(1, 10),
                SeatCardDealt(0, 0, card("10S")), SeatCardDealt(1, 0, card("9H")), DealerCardDealt(card("10C"), false),
                SeatCardDealt(0, 0, card("7S")), SeatCardDealt(1, 0, card("8H")), DealerCardDealt(card("7C"), true),
                TurnStarted(0, 0)
            ),
            e.placeBet(10)
        )
    }

    @Test
    fun `a natural is announced and the round settles straight away`() {
        val e = solo("AS", "9H", "KD", "8C")
        val events = e.placeBet(10)
        assertEquals(
            listOf(
                BetPlaced(0, 10),
                SeatCardDealt(0, 0, card("AS")), DealerCardDealt(card("9H"), false),
                SeatCardDealt(0, 0, card("KD")), DealerCardDealt(card("8C"), true),
                Natural(0),
                HoleCardRevealed(card("8C")),
                HandSettled(0, 0, RoundResult.PLAYER_BLACKJACK, bet = 10, paid = 25, fullPayout = 25)
            ),
            events
        )
    }

    @Test
    fun `a hit that busts reports the action, the card, the bust and the settlement`() {
        val e = solo("10S", "9H", "6D", "8C", "10H")
        e.placeBet(10)
        assertEquals(
            listOf(
                ActionTaken(0, 0, TableAction.HIT), SeatCardDealt(0, 0, card("10H")), Bust(0, 0),
                HoleCardRevealed(card("8C")),
                HandSettled(0, 0, RoundResult.PLAYER_BUST, bet = 10, paid = 0, fullPayout = 0)
            ),
            e.hit()
        )
    }

    @Test
    fun `a hit that does not bust leaves it the same hand's turn`() {
        val e = solo("5S", "9H", "4D", "8C", "2H")
        e.placeBet(10)
        assertEquals(listOf(ActionTaken(0, 0, TableAction.HIT), SeatCardDealt(0, 0, card("2H"))), e.hit())
    }

    @Test
    fun `standing reveals the hole card and shows each dealer draw`() {
        // Dealer A+6 (soft 17) draws a 4 for soft 21.
        val e = solo("10S", "AH", "10D", "6C", "4S")
        e.placeBet(10)
        assertEquals(
            listOf(
                ActionTaken(0, 0, TableAction.STAND),
                HoleCardRevealed(card("6C")), DealerCardDealt(card("4S"), false),
                HandSettled(0, 0, RoundResult.DEALER_WIN, bet = 10, paid = 0, fullPayout = 0)
            ),
            e.stand()
        )
    }

    @Test
    fun `a dealer bust is announced`() {
        val e = solo("10S", "10H", "8D", "6C", "10D")
        e.placeBet(10)
        assertEquals(
            listOf(
                ActionTaken(0, 0, TableAction.STAND),
                HoleCardRevealed(card("6C")), DealerCardDealt(card("10D"), false), DealerBust(26),
                HandSettled(0, 0, RoundResult.DEALER_BUST, bet = 10, paid = 20, fullPayout = 20)
            ),
            e.stand()
        )
    }

    @Test
    fun `double down reports the extra bet, the one card and the doubled settlement`() {
        val e = solo("5S", "10H", "6D", "7C", "10S")
        e.placeBet(10)
        assertEquals(
            listOf(
                ActionTaken(0, 0, TableAction.DOUBLE), BetPlaced(0, 10), SeatCardDealt(0, 0, card("10S")),
                HoleCardRevealed(card("7C")),
                HandSettled(0, 0, RoundResult.PLAYER_WIN, bet = 20, paid = 40, fullPayout = 40)
            ),
            e.doubleDown()
        )
    }

    @Test
    fun `split reports the extra bet, a card to each new hand, and the first hand's turn`() {
        val e = solo("8S", "10H", "8D", "7C", "3S", "4H")
        e.placeBet(10)
        assertEquals(
            listOf(
                ActionTaken(0, 0, TableAction.SPLIT), BetPlaced(0, 10),
                SeatCardDealt(0, 0, card("3S")), SeatCardDealt(0, 1, card("4H")),
                TurnStarted(0, 0)
            ),
            e.split()
        )
    }

    @Test
    fun `standing on the first split hand hands the turn to the second`() {
        val e = solo("8S", "10H", "8D", "7C", "3S", "4H")
        e.placeBet(10)
        e.split()
        assertEquals(listOf(ActionTaken(0, 0, TableAction.STAND), TurnStarted(0, 1)), e.stand())
    }

    @Test
    fun `step reports one AI action at a time and then the next turn`() {
        val e = engine(
            listOf(SeatConfig(SeatKind.AI, 100), SeatConfig(SeatKind.HUMAN, 100)),
            "10S", "9H", "10C", "6S", "8H", "7C", "4D"
        )
        e.placeBet(10)
        assertEquals(listOf(ActionTaken(0, 0, TableAction.HIT), SeatCardDealt(0, 0, card("4D"))), e.step())
        assertEquals(listOf(ActionTaken(0, 0, TableAction.STAND), TurnStarted(1, 0)), e.step())
        assertTrue(e.step().isEmpty())
    }

    @Test
    fun `a seat that played and is left with nothing is reported broke`() {
        val e = solo("10S", "10H", "7D", "9C", stack = 10)
        e.placeBet(10)
        val events = e.stand()
        assertTrue(SeatBrokeOut(0) in events)
        assertFalse(HouseBroke in events)
    }

    @Test
    fun `the house running dry is reported`() {
        val e = solo("10S", "10H", "8D", "7C", house = 10)
        e.placeBet(10)
        val events = e.stand()
        assertEquals(HouseBroke, events.last())
    }

    @Test
    fun `a house short on a payout shows it in the settlement event`() {
        val e = solo("10S", "10H", "8D", "7C", house = 4)
        e.placeBet(10)
        val settled = e.stand().filterIsInstance<HandSettled>().single()
        assertEquals(14L, settled.paid)
        assertEquals(20L, settled.fullPayout)
    }

    @Test
    fun `a reshuffle is announced before anything is dealt`() {
        val e = TableEngine(TableConfig.singlePlayer(100), random = Random(5))
        // A threshold above the shoe size forces a reshuffle; the default one doesn't on a full shoe.
        val low = TableEngine(
            TableConfig(listOf(SeatConfig(SeatKind.HUMAN, 100)), reshuffleBelow = 300),
            random = Random(5)
        )
        assertEquals(ShoeReshuffled, low.placeBet(10).first())
        assertFalse(ShoeReshuffled in e.placeBet(10))
    }

    @Test
    fun `an action that fails part-way leaves no stale events in the next one`() {
        // The 4-card shoe is empty after the deal, so the double down fails at its draw — after it
        // already started reporting ActionTaken/BetPlaced. The next action must start clean.
        val e = solo("10S", "10H", "8D", "8C")
        e.placeBet(10)
        try {
            e.doubleDown()
        } catch (_: IllegalStateException) {
        }
        val events = e.stand()
        assertEquals(ActionTaken(0, 0, TableAction.STAND), events.first())
        assertTrue(events.none { it is BetPlaced })
    }

    @Test
    fun `events agree with the state across many random full-table rounds`() {
        val random = Random(11)
        val seats = listOf(
            SeatConfig(SeatKind.AI, 300, StyleProfile.RECKLESS), SeatConfig(SeatKind.HUMAN, 400),
            SeatConfig(SeatKind.AI, 300), SeatConfig(SeatKind.AI, 300, StyleProfile.CAUTIOUS)
        )
        val e = TableEngine(TableConfig(seats, houseBankroll = 5_000L), random = random)
        var rounds = 0
        while (rounds < 150 && e.state.humanSeat.stack > 0 && !e.state.houseBroke) {
            val shoeBefore = e.state.shoe.remaining
            val events = mutableListOf<TableEvent>()
            events += e.placeBet(minOf(10L, e.state.humanSeat.stack))
            while (e.state.phase == TablePhase.SEAT_TURN) {
                val stepped = e.step()
                if (stepped.isNotEmpty()) { events += stepped; continue }
                val hand = e.state.activeHand!!
                events += when (BasicStrategy.decide(hand, e.state.dealerCards.first(), e.state.canDoubleDown, e.state.canSplit)) {
                    TableAction.HIT -> e.hit()
                    TableAction.STAND -> e.stand()
                    TableAction.DOUBLE -> e.doubleDown()
                    TableAction.SPLIT -> e.split()
                }
            }

            // Every card that left the shoe was reported exactly once.
            val dealt = events.count { it is SeatCardDealt || it is DealerCardDealt }
            val start = if (ShoeReshuffled in events) 208 else shoeBefore
            assertEquals(start - dealt, e.state.shoe.remaining)

            // One settlement per hand, matching the state, and exactly one hole-card reveal.
            val settled = events.filterIsInstance<HandSettled>()
            assertEquals(e.state.seats.sumOf { it.hands.size }, settled.size)
            for (s in settled) {
                val hand = e.state.seats[s.seat].hands[s.hand]
                assertEquals(hand.result, s.result)
                assertEquals(hand.paid, s.paid)
                assertEquals(hand.bet, s.bet)
            }
            assertEquals(1, events.count { it is HoleCardRevealed })
            assertEquals(e.state.houseBroke, HouseBroke in events)

            e.startNextRound()
            rounds++
        }
        assertTrue(rounds > 5)
    }
}
