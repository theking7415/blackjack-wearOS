package com.sensinglocal.blackjack

import com.sensinglocal.blackjack.game.BlackjackEngine
import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.Deck
import com.sensinglocal.blackjack.game.HandStatus
import com.sensinglocal.blackjack.game.PlayerHand
import com.sensinglocal.blackjack.game.Rank
import com.sensinglocal.blackjack.game.RoundPhase
import com.sensinglocal.blackjack.game.RoundResult
import com.sensinglocal.blackjack.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorySaveCodecTest {
    private fun roundTrip(save: StorySave) = StorySaveCodec.decode(StorySaveCodec.encode(save))

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
    fun `in-hand checkpoint round-trips including deck order and split hands`() {
        val hand = HandSnapshot(
            phase = RoundPhase.PLAYER_TURN,
            hands = listOf(
                PlayerHand(listOf(Card(Rank.EIGHT, Suit.CLUBS), Card(Rank.FIVE, Suit.HEARTS)), bet = 10,
                    status = HandStatus.STOOD, fromSplit = true, result = RoundResult.PUSH),
                PlayerHand(listOf(Card(Rank.EIGHT, Suit.SPADES), Card(Rank.ACE, Suit.DIAMONDS)), bet = 10)
            ),
            activeHandIndex = 1,
            dealerCards = listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SIX, Suit.CLUBS)),
            deck = listOf(Card(Rank.TWO, Suit.HEARTS), Card(Rank.QUEEN, Suit.DIAMONDS), Card(Rank.TEN, Suit.CLUBS))
        )
        val save = StorySave.newGame().copy(checkpoint = Checkpoint.InHand(hand))
        assertEquals(save, roundTrip(save))
    }

    @Test
    fun `restored engine hand draws the same next cards as the original`() {
        val engine = BlackjackEngine(500)
        engine.placeBet(20)
        val snapshot = HandSnapshot.from(engine.state)
        val restored = BlackjackEngine(0).apply {
            restore(roundTrip(StorySave.newGame().copy(bankroll = engine.state.bankroll.toLong(),
                checkpoint = Checkpoint.InHand(snapshot)))!!.let {
                (it.checkpoint as Checkpoint.InHand).hand.toState(it.bankroll)
            })
        }
        assertEquals(engine.state.bankroll, restored.state.bankroll)
        assertEquals(engine.state.hands, restored.state.hands)
        assertEquals(engine.state.dealerCards, restored.state.dealerCards)
        assertEquals(engine.state.deck.snapshot(), restored.state.deck.snapshot())
    }

    @Test
    fun `corrupt or unknown-version data decodes to null`() {
        assertNull(StorySaveCodec.decode("not json"))
        assertNull(StorySaveCodec.decode("{}"))
        val future = StorySaveCodec.encode(StorySave.newGame()).replace("\"version\":1", "\"version\":99")
        assertNull(StorySaveCodec.decode(future))
    }

    @Test
    fun `deck snapshot preserves draw order`() {
        val deck = Deck(shoeCount = 1)
        val order = deck.snapshot()
        val rebuilt = Deck.fromSnapshot(order)
        assertEquals(52, rebuilt.remaining())
        assertTrue(order.all { it == rebuilt.draw() })
    }

    @Test
    fun `legacy migrated save resumes at the street with the opening seen`() {
        val save = StorySave.legacyMigrated()
        assertEquals(Checkpoint.Street, save.checkpoint)
        assertTrue(OPENING_CUTSCENE_ID in save.completedCutscenes)
    }
}
