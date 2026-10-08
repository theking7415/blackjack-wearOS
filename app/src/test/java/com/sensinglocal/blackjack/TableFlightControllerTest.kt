package com.sensinglocal.blackjack

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.Rank
import com.sensinglocal.blackjack.game.Suit
import com.sensinglocal.blackjack.game.table.SeatConfig
import com.sensinglocal.blackjack.game.table.SeatKind
import com.sensinglocal.blackjack.game.table.Shoe
import com.sensinglocal.blackjack.game.table.TableConfig
import com.sensinglocal.blackjack.game.table.TableEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which slot each card flies to, and when, for real engine event batches (rigged shoes). */
class TableFlightControllerTest {
    private fun card(code: String): Card {
        val suit = when (code.last()) {
            'S' -> Suit.SPADES; 'H' -> Suit.HEARTS; 'D' -> Suit.DIAMONDS; else -> Suit.CLUBS
        }
        return Card(Rank.entries.first { it.label == code.dropLast(1) }, suit)
    }

    private data class Flight(val row: String, val index: Int, val card: Card, val faceDown: Boolean, val delayMs: Long)

    private fun TableFlightController.summary() =
        flights.map { Flight(it.key.row, it.key.index, it.card, it.faceDown, it.startDelayMs) }

    private fun engine(seats: List<SeatConfig>, vararg codes: String) = TableEngine(
        TableConfig(seats, reshuffleBelow = 0),
        initialShoe = Shoe.of(codes.map(::card))
    )

    private fun solo(vararg codes: String) = engine(listOf(SeatConfig(SeatKind.HUMAN, 100)), *codes)

    @Test
    fun `the opening deal flies out in real table order, one after another`() {
        val e = engine(
            listOf(SeatConfig(SeatKind.AI, 100), SeatConfig(SeatKind.HUMAN, 100)),
            "10S", "9H", "10C", "7S", "8H", "7C"
        )
        val controller = TableFlightController()
        controller.onEvents(e.placeBet(10), e.state)
        assertEquals(
            listOf(
                Flight("seat-0-hand-0", 0, card("10S"), false, 0),
                Flight("seat-1-hand-0", 0, card("9H"), false, 110),
                Flight("dealer", 0, card("10C"), false, 220),
                Flight("seat-0-hand-0", 1, card("7S"), false, 330),
                Flight("seat-1-hand-0", 1, card("8H"), false, 440),
                Flight("dealer", 1, card("7C"), true, 550) // the hole card flies face-down
            ),
            controller.summary()
        )
    }

    @Test
    fun `a hit flies to the next slot of that hand`() {
        val e = solo("5S", "9H", "4D", "8C", "2H")
        e.placeBet(10)
        val controller = TableFlightController()
        controller.onEvents(e.hit(), e.state)
        assertEquals(listOf(Flight("seat-0-hand-0", 2, card("2H"), false, 0)), controller.summary())
    }

    @Test
    fun `a split sends one new card to each new hand`() {
        val e = solo("8S", "10H", "8D", "7C", "3S", "4H")
        e.placeBet(10)
        val controller = TableFlightController()
        controller.onEvents(e.split(), e.state)
        assertEquals(
            listOf(
                Flight("seat-0-hand-0", 1, card("3S"), false, 0),
                Flight("seat-0-hand-1", 1, card("4H"), false, 110)
            ),
            controller.summary()
        )
    }

    @Test
    fun `dealer draws after the hole card is turned come one at a time`() {
        // Dealer 10+2 = 12 draws a 3 (15) then a 4 (19).
        val e = solo("10S", "10H", "8D", "2C", "3S", "4D", "5H")
        e.placeBet(10)
        val controller = TableFlightController()
        controller.onEvents(e.stand(), e.state)
        assertEquals(
            listOf(
                Flight("dealer", 2, card("3S"), false, 300),
                Flight("dealer", 3, card("4D"), false, 650)
            ),
            controller.summary()
        )
    }

    @Test
    fun `a natural that settles the round in the same batch keeps the dealer's up-card in the deal`() {
        val e = solo("AS", "9H", "KD", "8C")
        val controller = TableFlightController()
        controller.onEvents(e.placeBet(10), e.state)
        assertEquals(
            listOf(
                Flight("seat-0-hand-0", 0, card("AS"), false, 0),
                Flight("dealer", 0, card("9H"), false, 110),
                Flight("seat-0-hand-0", 1, card("KD"), false, 220),
                Flight("dealer", 1, card("8C"), true, 330)
            ),
            controller.summary()
        )
    }

    @Test
    fun `events with no dealt cards start no flights`() {
        val e = solo("10S", "10H", "8D", "8C")
        e.placeBet(10)
        val controller = TableFlightController()
        controller.onEvents(e.stand(), e.state) // push: just the reveal and settlement
        assertTrue(controller.flights.isEmpty())
        controller.onEvents(emptyList(), e.state)
        assertTrue(controller.flights.isEmpty())
    }

    @Test
    fun `a starting flight forgets the slot measurement left from an earlier round`() {
        val e = solo("5S", "9H", "4D", "8C", "2H")
        e.placeBet(10)
        val controller = TableFlightController()
        val key = TableSlotKey("seat-0-hand-0", 2)
        controller.slots[key] = SlotRect(Offset(10f, 10f), Size(34f, 48f))
        controller.onEvents(e.hit(), e.state)
        assertFalse(key in controller.slots)
        assertTrue(controller.isFlying(key))
        assertTrue(controller.isFlyingIn("seat-0-hand-0"))
        assertFalse(controller.isFlyingIn("dealer"))
    }

    @Test
    fun `events that do not match the state are skipped rather than crashing`() {
        val dealt = engine(listOf(SeatConfig(SeatKind.HUMAN, 100)), "10S", "9H", "7D", "8C")
        val events = dealt.placeBet(10)
        val empty = engine(listOf(SeatConfig(SeatKind.HUMAN, 100)), "10S", "9H", "7D", "8C")
        val controller = TableFlightController()
        controller.onEvents(events, empty.state) // a table that hasn't dealt anything
        assertTrue(controller.flights.isEmpty())
    }
}
