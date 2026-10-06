package com.sensinglocal.blackjack.game.table

import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.Rank
import com.sensinglocal.blackjack.game.RoundResult
import com.sensinglocal.blackjack.game.Suit
import com.sensinglocal.blackjack.game.table.TableEvent.ActionTaken
import com.sensinglocal.blackjack.game.table.TableEvent.Bust
import com.sensinglocal.blackjack.game.table.TableEvent.DealerBust
import com.sensinglocal.blackjack.game.table.TableEvent.HouseBroke
import com.sensinglocal.blackjack.game.table.TableEvent.MoneyGrabbed
import com.sensinglocal.blackjack.game.table.TableEvent.SeatBrokeOut
import com.sensinglocal.blackjack.game.table.TableEvent.SeatCardDealt
import com.sensinglocal.blackjack.game.table.TableEvent.TurnStarted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScriptingTest {
    private fun card(code: String): Card {
        val suit = when (code.last()) {
            'S' -> Suit.SPADES; 'H' -> Suit.HEARTS; 'D' -> Suit.DIAMONDS; else -> Suit.CLUBS
        }
        return Card(Rank.entries.first { it.label == code.dropLast(1) }, suit)
    }

    private fun cards(vararg codes: String) = codes.map(::card)

    private fun ai(stack: Long = 100) = SeatConfig(SeatKind.AI, stack)
    private fun human(stack: Long = 100) = SeatConfig(SeatKind.HUMAN, stack)

    /** Table with a rigged opening shoe (no reshuffle) and scripts keyed by round. */
    private fun engine(
        seats: List<SeatConfig>,
        shoe: List<Card>? = null,
        scripts: Map<Int, RoundScript> = emptyMap(),
        house: Long = 10_000
    ) = TableEngine(
        TableConfig(seats, reshuffleBelow = if (shoe != null) 0 else 15 * seats.size, houseBankroll = house, scripts = scripts),
        initialShoe = shoe?.let { Shoe.of(it) },
        random = Random(1)
    )

    // --- Shoe.withScriptedTop --------------------------------------------------------------

    @Test
    fun `scripted slots are pinned, null slots take the real shoe in order, pinned cards leave the rest`() {
        val shoe = Shoe.of(cards("2S", "3S", "4S", "5S", "6S"))
        val result = shoe.withScriptedTop(listOf(card("4S"), null, card("2S")))
        assertEquals(cards("4S", "3S", "2S", "5S", "6S"), result.snapshot())
    }

    @Test
    fun `a pinned card the shoe does not hold is simply put on top`() {
        val result = Shoe.of(cards("2S")).withScriptedTop(listOf(card("9H")))
        assertEquals(cards("9H", "2S"), result.snapshot())
    }

    // --- rigged rounds ---------------------------------------------------------------------

    @Test
    fun `a round script can deal the player a natural`() {
        val script = RoundScript(
            seatCards = mapOf(0 to cards("AS", "KD")), dealerUp = card("9H"), dealerHole = card("8C")
        )
        val e = engine(listOf(human()), scripts = mapOf(0 to script))
        e.placeBet(10)
        assertEquals(cards("AS", "KD"), e.state.humanSeat.hands[0].cards)
        assertEquals(cards("9H", "8C"), e.state.dealerCards)
        assertEquals(RoundResult.PLAYER_BLACKJACK, e.state.humanSeat.hands[0].result)
        assertEquals(204, e.state.shoe.remaining) // 208 cards, 4 dealt: pinned cards came out of the rest
    }

    @Test
    fun `a script applies only to its own round`() {
        val script = RoundScript(
            seatCards = mapOf(0 to cards("AS", "KD")), dealerUp = card("9H"), dealerHole = card("8C")
        )
        val e = engine(listOf(human()), cards("10S", "10H", "8D", "8C"), scripts = mapOf(1 to script))
        e.placeBet(10)
        assertEquals(cards("10S", "8D"), e.state.humanSeat.hands[0].cards) // round 0: unscripted
        e.stand()
        e.startNextRound()
        e.placeBet(10)
        assertEquals(cards("AS", "KD"), e.state.humanSeat.hands[0].cards) // round 1: scripted
    }

    @Test
    fun `scripted draws feed the dealer's play in order`() {
        val script = RoundScript(
            seatCards = mapOf(0 to cards("10S", "8D")),
            dealerUp = card("10H"), dealerHole = card("6C"), draws = cards("10C")
        )
        val e = engine(listOf(human()), scripts = mapOf(0 to script))
        e.placeBet(10)
        val events = e.stand()
        assertEquals(cards("10H", "6C", "10C"), e.state.dealerCards)
        assertTrue(DealerBust(26) in events)
        assertEquals(RoundResult.DEALER_BUST, e.state.humanSeat.hands[0].result)
    }

    @Test
    fun `unpinned positions come from the real shoe`() {
        val script = RoundScript(dealerHole = card("5C"))
        val e = engine(
            listOf(human()), cards("10S", "9H", "8D", "7C", "2S", "3S"), scripts = mapOf(0 to script)
        )
        e.placeBet(10)
        assertEquals(cards("10S", "8D"), e.state.humanSeat.hands[0].cards)
        assertEquals(cards("9H", "5C"), e.state.dealerCards)
        assertEquals(3, e.state.shoe.remaining)
    }

    @Test
    fun `rigged opening cards follow the real deal order across seats`() {
        // Seats 0 (AI) and 1 (human): deal order is seat0, seat1, dealer up, seat0, seat1, hole.
        val script = RoundScript(
            seatCards = mapOf(0 to cards("10S", "7S"), 1 to cards("9H", "8H")),
            dealerUp = card("10C"), dealerHole = card("7C")
        )
        val e = engine(listOf(ai(), human()), scripts = mapOf(0 to script))
        e.placeBet(10)
        assertEquals(cards("10S", "7S"), e.state.seats[0].hands[0].cards)
        assertEquals(cards("9H", "8H"), e.state.seats[1].hands[0].cards)
        assertEquals(cards("10C", "7C"), e.state.dealerCards)
    }

    // --- forced actions --------------------------------------------------------------------

    private val aiHumanShoe = cards("10S", "9H", "10C", "7S", "8H", "7C", "2D", "5C")

    @Test
    fun `forced actions override basic strategy in order`() {
        // The AI holds 17 and would stand; the script makes it hit twice and bust.
        val script = RoundScript(actions = mapOf(0 to listOf(TableAction.HIT, TableAction.HIT)))
        val e = engine(listOf(ai(), human()), aiHumanShoe, mapOf(0 to script))
        e.placeBet(10)
        assertEquals(listOf(ActionTaken(0, 0, TableAction.HIT), SeatCardDealt(0, 0, card("2D"))), e.step())
        assertEquals(
            listOf(ActionTaken(0, 0, TableAction.HIT), SeatCardDealt(0, 0, card("5C")), Bust(0, 0), TurnStarted(1, 0)),
            e.step()
        )
    }

    @Test
    fun `once the forced list runs out the seat plays basic strategy again`() {
        val script = RoundScript(actions = mapOf(0 to listOf(TableAction.HIT)))
        val e = engine(listOf(ai(), human()), aiHumanShoe, mapOf(0 to script))
        e.placeBet(10)
        e.step() // forced hit: 17 -> 19
        assertEquals(listOf(ActionTaken(0, 0, TableAction.STAND), TurnStarted(1, 0)), e.step())
    }

    @Test
    fun `an illegal forced action falls back to basic strategy`() {
        val script = RoundScript(actions = mapOf(0 to listOf(TableAction.SPLIT))) // 10+7 isn't a pair
        val e = engine(listOf(ai(), human()), aiHumanShoe, mapOf(0 to script))
        e.placeBet(10)
        assertEquals(listOf(ActionTaken(0, 0, TableAction.STAND), TurnStarted(1, 0)), e.step())
    }

    @Test
    fun `the script cursor is part of the state and resets each round`() {
        val script = RoundScript(actions = mapOf(0 to listOf(TableAction.HIT)))
        val e = engine(listOf(ai(), human()), aiHumanShoe, mapOf(0 to script))
        e.placeBet(10)
        e.step()
        assertEquals(mapOf(0 to 1), e.state.scriptCursor)
        e.step()
        e.stand()
        e.startNextRound()
        assertTrue(e.state.scriptCursor.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `forced actions for the human seat are rejected`() {
        TableConfig(listOf(ai(), human()), scripts = mapOf(0 to RoundScript(actions = mapOf(1 to listOf(TableAction.HIT)))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a script for a seat that does not exist is rejected`() {
        TableConfig(listOf(human()), scripts = mapOf(0 to RoundScript(seatCards = mapOf(3 to cards("AS")))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a rigged opening is at most two cards`() {
        RoundScript(seatCards = mapOf(0 to cards("AS", "KD", "2C")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `script rounds cannot be negative`() {
        TableConfig(listOf(human()), scripts = mapOf(-1 to RoundScript()))
    }

    // --- grab all --------------------------------------------------------------------------

    @Test
    fun `grabAll moves every other stack and the house to the player and ends the table`() {
        val e = engine(listOf(ai(50), human(20), ai(30), ai(0)), house = 100)
        val events = e.grabAll()
        assertEquals(listOf(MoneyGrabbed(mapOf(0 to 50L, 2 to 30L), 100L), HouseBroke), events)
        assertEquals(180L, (events[0] as MoneyGrabbed).total)
        assertEquals(listOf(0L, 200L, 0L, 0L), e.state.seats.map { it.stack })
        assertEquals(0L, e.state.house)
        assertTrue(e.state.grabbed)
        assertTrue(e.state.houseBroke)
    }

    @Test(expected = IllegalStateException::class)
    fun `no more betting once the money has been grabbed`() {
        val e = engine(listOf(ai(50), human(20)), house = 100)
        e.grabAll()
        e.placeBet(10)
    }

    @Test(expected = IllegalStateException::class)
    fun `grabAll is rejected mid-round`() {
        val e = engine(listOf(human()), cards("10S", "10H", "8D", "8C"), house = 100)
        e.placeBet(10)
        e.grabAll()
    }

    @Test(expected = IllegalStateException::class)
    fun `grabAll cannot be done twice`() {
        val e = engine(listOf(ai(50), human(20)), house = 100)
        e.grabAll()
        e.grabAll()
    }

    @Test(expected = IllegalStateException::class)
    fun `an unlimited house cannot be grabbed`() {
        TableEngine(TableConfig.singlePlayer(100)).grabAll()
    }

    @Test
    fun `grabbing keeps total money unchanged`() {
        val e = engine(listOf(ai(50), human(20), ai(30)), house = 100)
        e.grabAll()
        assertEquals(200L, e.state.seats.sumOf { it.stack } + e.state.house)
    }

    // --- police hand -----------------------------------------------------------------------

    private fun police(stake: Long, vararg codes: String) = TableEngine(
        TableConfig.policeHand(stake).copy(reshuffleBelow = 0),
        initialShoe = Shoe.of(cards(*codes))
    )

    @Test(expected = IllegalArgumentException::class)
    fun `a police hand needs something to stake`() {
        TableConfig.policeHand(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the police hand must be all in`() {
        police(100, "10S", "10H", "8D", "7C").placeBet(50)
    }

    @Test
    fun `winning the police hand doubles the stack`() {
        val e = police(100, "10S", "10H", "8D", "7C")
        e.placeBet(100)
        e.stand()
        assertEquals(200L, e.state.humanSeat.stack)
    }

    @Test
    fun `losing the police hand costs everything`() {
        val e = police(100, "10S", "10H", "7D", "9C")
        e.placeBet(100)
        val events = e.stand()
        assertEquals(0L, e.state.humanSeat.stack)
        assertTrue(SeatBrokeOut(0) in events)
    }

    @Test
    fun `a natural against the police pays 3 to 2 in full`() {
        val e = police(100, "AS", "9H", "KD", "8C")
        e.placeBet(100)
        assertEquals(250L, e.state.humanSeat.stack)
        assertFalse(e.state.houseBroke)
    }

    @Test
    fun `there is no money left to double or split with in the police hand`() {
        val e = police(100, "5S", "10H", "6D", "7C")
        e.placeBet(100)
        assertFalse(e.state.canDoubleDown)
        assertFalse(e.state.canSplit)
    }
}
