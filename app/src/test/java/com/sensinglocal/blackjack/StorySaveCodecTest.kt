package com.sensinglocal.blackjack

import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.Rank
import com.sensinglocal.blackjack.game.Suit
import com.sensinglocal.blackjack.game.table.BasicStrategy
import com.sensinglocal.blackjack.game.table.RoundScript
import com.sensinglocal.blackjack.game.table.SeatConfig
import com.sensinglocal.blackjack.game.table.SeatKind
import com.sensinglocal.blackjack.game.table.Shoe
import com.sensinglocal.blackjack.game.table.StyleProfile
import com.sensinglocal.blackjack.game.table.TableAction
import com.sensinglocal.blackjack.game.table.TableConfig
import com.sensinglocal.blackjack.game.table.TableEngine
import com.sensinglocal.blackjack.game.table.TableEvent
import com.sensinglocal.blackjack.game.table.TablePhase
import com.sensinglocal.blackjack.game.table.TableState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StorySaveCodecTest {
    private fun roundTrip(save: StorySave) = StorySaveCodec.decode(StorySaveCodec.encode(save))

    private fun atTable(state: TableState) = StorySave.newGame().copy(checkpoint = Checkpoint.AtTable(state))

    private fun tableAfterRoundTrip(state: TableState): TableState =
        (roundTrip(atTable(state))!!.checkpoint as Checkpoint.AtTable).table

    // --- plain save fields -----------------------------------------------------------------

    @Test
    fun `new game round-trips`() {
        val save = StorySave.newGame()
        assertEquals(save, roundTrip(save))
        assertEquals(50L, save.bankroll)
        assertEquals(Checkpoint.BeforeCutscene(OPENING_CUTSCENE_ID), save.checkpoint)
    }

    @Test
    fun `bankroll beyond Int range survives`() {
        val save = StorySave.newGame().copy(bankroll = 3_000_000_000L)
        assertEquals(3_000_000_000L, roundTrip(save)?.bankroll)
    }

    @Test
    fun `simple checkpoints and completed cutscenes round-trip`() {
        for (cp in listOf(Checkpoint.BuyIn, Checkpoint.Street, Checkpoint.BeforeCutscene("x"))) {
            val save = StorySave.newGame().copy(
                currencyTier = CurrencyTier.SILVER_COINS,
                casino = 4, table = 2,
                completedCutscenes = setOf("opening", "x"),
                checkpoint = cp
            )
            assertEquals(save, roundTrip(save))
        }
    }

    @Test
    fun `corrupt or unknown-version data decodes to null`() {
        assertNull(StorySaveCodec.decode("not json"))
        assertNull(StorySaveCodec.decode("{}"))
        val future = StorySaveCodec.encode(StorySave.newGame()).replace("\"version\":2", "\"version\":99")
        assertNull(StorySaveCodec.decode(future))
    }

    @Test
    fun `version 1 saves still load`() {
        val save = StorySave.newGame().copy(checkpoint = Checkpoint.Street, completedCutscenes = setOf("opening"))
        val v1 = StorySaveCodec.encode(save).replace("\"version\":2", "\"version\":1")
        assertEquals(save, StorySaveCodec.decode(v1))
    }

    @Test
    fun `a version 1 in-hand checkpoint is unreadable rather than guessed at`() {
        val v1InHand = """{"version":1,"currencyTier":"CREDITS","bankroll":50,"casino":1,"table":1,""" +
            """"completedCutscenes":[],"checkpoint":{"type":"inHand","hand":{}}}"""
        assertNull(StorySaveCodec.decode(v1InHand))
    }

    @Test
    fun `legacy migrated save resumes at the street with the opening seen`() {
        val save = StorySave.legacyMigrated()
        assertEquals(Checkpoint.Street, save.checkpoint)
        assertTrue(OPENING_CUTSCENE_ID in save.completedCutscenes)
    }

    // --- saving a table --------------------------------------------------------------------

    private val seats = listOf(
        SeatConfig(SeatKind.AI, 300, StyleProfile.RECKLESS),
        SeatConfig(SeatKind.HUMAN, 400),
        SeatConfig(SeatKind.AI, 300, StyleProfile.STEADY),
        SeatConfig(SeatKind.AI, 300, StyleProfile.CAUTIOUS)
    )

    /** Big seeded shoe + no reshuffle, so the engine itself never consumes randomness. */
    private val config = TableConfig(
        seats, reshuffleBelow = 0, houseBankroll = 5_000,
        // Round 0: seat 0 is dealt 10+6 (so it can't have a natural and must act) and is forced to hit.
        scripts = mapOf(
            0 to RoundScript(
                seatCards = mapOf(0 to listOf(Card(Rank.TEN, Suit.SPADES), Card(Rank.SIX, Suit.DIAMONDS))),
                actions = mapOf(0 to listOf(TableAction.HIT))
            )
        )
    )

    private fun newEngine() = TableEngine(config, Shoe.fresh(8, Random(3)))

    /** One engine action appropriate to the current phase; returns the events it produced. */
    private fun advanceOnce(e: TableEngine): List<TableEvent> = when (e.state.phase) {
        TablePhase.BETTING -> e.placeBet(minOf(10L, e.state.humanSeat.stack))
        TablePhase.ROUND_OVER -> { e.startNextRound(); emptyList() }
        TablePhase.SEAT_TURN -> e.step().ifEmpty {
            val hand = e.state.activeHand!!
            when (BasicStrategy.decide(hand, e.state.dealerCards.first(), e.state.canDoubleDown, e.state.canSplit)) {
                TableAction.HIT -> e.hit()
                TableAction.STAND -> e.stand()
                TableAction.DOUBLE -> e.doubleDown()
                TableAction.SPLIT -> e.split()
            }
        }
        TablePhase.DEALER_TURN -> error("the engine never rests in DEALER_TURN")
    }

    private fun playable(e: TableEngine, rounds: Int) =
        rounds < 12 && e.state.shoe.remaining > 100 && e.state.humanSeat.stack > 0 && !e.state.houseBroke

    @Test
    fun `a table state round-trips exactly at every point of a multi-round game`() {
        val e = newEngine()
        var rounds = 0
        var sawCursor = false
        while (playable(e, rounds)) {
            assertEquals(e.state, tableAfterRoundTrip(e.state))
            if (e.state.scriptCursor.isNotEmpty()) sawCursor = true
            if (e.state.phase == TablePhase.ROUND_OVER) rounds++
            advanceOnce(e)
        }
        assertTrue(rounds >= 3)
        assertTrue("the script cursor should have been in play", sawCursor)
    }

    @Test
    fun `a grabbed table round-trips`() {
        val e = TableEngine(TableConfig(seats, houseBankroll = 5_000))
        e.grabAll()
        val back = tableAfterRoundTrip(e.state)
        assertEquals(e.state, back)
        assertTrue(back.grabbed)
        assertEquals(0L, back.house)
    }

    @Test
    fun `a table with money beyond Int range round-trips`() {
        val big = TableConfig(
            listOf(SeatConfig(SeatKind.HUMAN, 5_000_000_000L)),
            houseBankroll = 9_000_000_000L
        )
        val e = TableEngine(big)
        e.placeBet(4_000_000_000L)
        assertEquals(e.state, tableAfterRoundTrip(e.state))
    }

    @Test
    fun `resuming from a save continues exactly as the original would have`() {
        val original = newEngine()
        var rounds = 0
        while (playable(original, rounds)) {
            // Save, load into a brand-new engine, and require both to behave identically from here.
            val resumed = TableEngine(config)
            resumed.restore(tableAfterRoundTrip(original.state))
            assertEquals(original.state, resumed.state)

            if (original.state.phase == TablePhase.ROUND_OVER) rounds++
            assertEquals(advanceOnce(original), advanceOnce(resumed))
            assertEquals(original.state, resumed.state)
        }
        assertTrue(rounds >= 3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a saved table for different seats is refused`() {
        val other = TableEngine(TableConfig.singlePlayer(100))
        newEngine().restore(other.state)
    }
}
