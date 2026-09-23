package com.sensinglocal.blackjack.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BlackjackEngine deals from a shuffled deck with no way to inject specific cards, so several
 * tests here loop over fresh engines until a scenario of interest (a pair, a split-ace hand,
 * a stood non-blackjack round, etc.) shows up by chance rather than asserting on one fixed deal.
 * Attempt caps are generous relative to the true odds so flakes are effectively impossible.
 */
class BlackjackEngineTest {

    private fun newEngine(bankroll: Int = 1000) = BlackjackEngine(bankroll)

    // -- placeBet --------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `placeBet rejects a zero bet`() {
        newEngine().placeBet(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `placeBet rejects a bet larger than the bankroll`() {
        newEngine(bankroll = 100).placeBet(101)
    }

    @Test
    fun `placeBet deducts the bet and deals two cards each`() {
        val engine = newEngine(bankroll = 500)
        engine.placeBet(50)

        assertEquals(450, engine.state.bankroll)
        assertEquals(1, engine.state.hands.size)
        assertEquals(2, engine.state.hands[0].cards.size)
        assertEquals(50, engine.state.hands[0].bet)
        assertEquals(2, engine.state.dealerCards.size)
        assertTrue(engine.state.phase == RoundPhase.PLAYER_TURN || engine.state.phase == RoundPhase.ROUND_OVER)
    }

    @Test
    fun `dealer hole card is hidden until the player's turn ends`() {
        val engine = newEngine()
        engine.placeBet(10)
        if (engine.state.phase == RoundPhase.PLAYER_TURN) {
            assertFalse(engine.state.dealerHoleCardRevealed)
        }
        while (engine.state.phase == RoundPhase.PLAYER_TURN) engine.stand()
        assertTrue(engine.state.dealerHoleCardRevealed)
    }

    // -- hit ---------------------------------------------------------------

    @Test(expected = IllegalStateException::class)
    fun `hit outside the player's turn throws`() {
        val engine = newEngine()
        engine.placeBet(10)
        while (engine.state.phase == RoundPhase.PLAYER_TURN) engine.stand()
        engine.hit()
    }

    @Test
    fun `hitting to a bust resolves the round as a loss`() {
        repeat(200) {
            val engine = newEngine()
            engine.placeBet(10)
            var attempts = 0
            while (engine.state.phase == RoundPhase.PLAYER_TURN && attempts < 15) {
                engine.hit()
                attempts++
            }
            val hand = engine.state.hands[0]
            if (hand.status == HandStatus.BUST) {
                assertEquals(RoundPhase.ROUND_OVER, engine.state.phase)
                assertEquals(RoundResult.PLAYER_BUST, hand.result)
                assertEquals(990, engine.state.bankroll) // bet lost, nothing returned
                return
            }
        }
    }

    // -- stand ---------------------------------------------------------------

    @Test(expected = IllegalStateException::class)
    fun `stand outside the player's turn throws`() {
        val engine = newEngine()
        engine.placeBet(10)
        while (engine.state.phase == RoundPhase.PLAYER_TURN) engine.stand()
        engine.stand()
    }

    @Test
    fun `standing on a single hand resolves the round immediately`() {
        val engine = newEngine()
        engine.placeBet(10)
        if (engine.state.phase == RoundPhase.PLAYER_TURN) {
            engine.stand()
        }
        assertEquals(RoundPhase.ROUND_OVER, engine.state.phase)
        assertNotNull(engine.state.hands[0].result)
    }

    // -- doubleDown --------------------------------------------------------

    /** Deals a fresh hand, retrying (a natural blackjack skips straight to ROUND_OVER) until it lands in PLAYER_TURN. */
    private fun engineMidPlayerTurn(bankroll: Int = 1000, bet: Int = 10, maxAttempts: Int = 2000): BlackjackEngine {
        repeat(maxAttempts) {
            val engine = newEngine(bankroll)
            engine.placeBet(bet)
            if (engine.state.phase == RoundPhase.PLAYER_TURN) return engine
        }
        throw AssertionError("Could not deal a non-blackjack hand in $maxAttempts attempts")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `doubleDown requires enough bankroll to match the bet`() {
        val engine = engineMidPlayerTurn(bankroll = 10, bet = 10) // bankroll now 0
        engine.doubleDown()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `doubleDown is rejected after the first two cards`() {
        var engine = engineMidPlayerTurn()
        engine.hit()
        var attempts = 0
        while (engine.state.phase != RoundPhase.PLAYER_TURN && attempts < 2000) {
            engine = engineMidPlayerTurn()
            engine.hit()
            attempts++
        }
        engine.doubleDown()
    }

    @Test
    fun `doubleDown draws exactly one card, doubles the bet, and forces a stand`() {
        repeat(50) {
            val engine = newEngine()
            engine.placeBet(10)
            if (engine.state.phase != RoundPhase.PLAYER_TURN) return@repeat
            engine.doubleDown()

            val hand = engine.state.hands[0]
            assertEquals(3, hand.cards.size)
            assertEquals(20, hand.bet)
            assertEquals(RoundPhase.ROUND_OVER, engine.state.phase)
            assertTrue(hand.status == HandStatus.STOOD || hand.status == HandStatus.BUST)
        }
    }

    // -- split ---------------------------------------------------------------

    private fun engineWithStartingPair(rank: Rank? = null, maxAttempts: Int = 2000): BlackjackEngine {
        repeat(maxAttempts) {
            val engine = newEngine(bankroll = 1000)
            engine.placeBet(10)
            val hand = engine.state.hands.getOrNull(0) ?: return@repeat
            if (hand.cards.size == 2 && hand.cards[0].rank == hand.cards[1].rank &&
                (rank == null || hand.cards[0].rank == rank)
            ) {
                return engine
            }
        }
        throw AssertionError("Could not deal a matching pair in $maxAttempts attempts")
    }

    @Test
    fun `split creates two independent hands from a pair`() {
        val engine = engineWithStartingPair()
        engine.split()

        assertEquals(2, engine.state.hands.size)
        assertTrue(engine.state.hands.all { it.fromSplit && it.bet == 10 && it.cards.size == 2 })
        assertEquals(980, engine.state.bankroll) // 1000 - 10 (bet) - 10 (matching split bet)
        assertFalse(engine.state.canSplit) // only one split per round
    }

    @Test(expected = IllegalArgumentException::class)
    fun `re-splitting is rejected`() {
        // Pinned to a non-ace rank: splitting aces forces an immediate stand and resolves the
        // round right away, which would make the second split() throw IllegalStateException
        // ("not player's turn") instead of the IllegalArgumentException this test expects.
        val engine = engineWithStartingPair(rank = Rank.EIGHT)
        engine.split()
        engine.split()
    }

    @Test
    fun `splitting aces deals one card each and forces an immediate stand`() {
        val engine = engineWithStartingPair(rank = Rank.ACE)
        engine.split()

        assertTrue(engine.state.hands.all { it.status == HandStatus.STOOD })
        assertEquals(RoundPhase.ROUND_OVER, engine.state.phase)
    }

    @Test
    fun `a split hand reaching 21 is never treated as a natural blackjack`() {
        val engine = engineWithStartingPair()
        engine.split()
        assertFalse(engine.state.hands.any { it.total == 21 && it.isNaturalBlackjack })
        assertTrue(engine.state.hands.none { it.result == RoundResult.PLAYER_BLACKJACK })
    }

    // -- resetBankroll / startNextRound --------------------------------------

    @Test
    fun `resetBankroll starts a clean state at the given amount`() {
        val engine = newEngine(bankroll = 0)
        engine.resetBankroll(500)

        assertEquals(500, engine.state.bankroll)
        assertTrue(engine.state.hands.isEmpty())
        assertEquals(RoundPhase.BETTING, engine.state.phase)
    }

    @Test(expected = IllegalStateException::class)
    fun `startNextRound requires the round to be over`() {
        var engine = newEngine()
        engine.placeBet(10)
        var attempts = 0
        while (engine.state.phase != RoundPhase.PLAYER_TURN && attempts < 2000) {
            engine = newEngine()
            engine.placeBet(10)
            attempts++
        }
        engine.startNextRound()
    }

    @Test
    fun `startNextRound clears hands and dealer cards but keeps the bankroll`() {
        val engine = newEngine()
        engine.placeBet(10)
        while (engine.state.phase == RoundPhase.PLAYER_TURN) engine.stand()
        val bankrollAfterRound = engine.state.bankroll

        engine.startNextRound()

        assertEquals(bankrollAfterRound, engine.state.bankroll)
        assertTrue(engine.state.hands.isEmpty())
        assertTrue(engine.state.dealerCards.isEmpty())
        assertEquals(RoundPhase.BETTING, engine.state.phase)
    }

    // -- payouts -------------------------------------------------------------

    @Test
    fun `push returns exactly the wagered bet`() {
        var checked = false
        repeat(500) {
            val engine = newEngine()
            val before = engine.state.bankroll
            engine.placeBet(10)
            while (engine.state.phase == RoundPhase.PLAYER_TURN) engine.stand()
            val hand = engine.state.hands[0]
            if (hand.result == RoundResult.PUSH) {
                assertEquals(before, engine.state.bankroll)
                checked = true
            }
        }
        assertTrue("Never observed a push in 500 rounds", checked)
    }

    @Test
    fun `a win or dealer bust pays even money`() {
        var checked = false
        repeat(500) {
            val engine = newEngine()
            val before = engine.state.bankroll
            engine.placeBet(10)
            while (engine.state.phase == RoundPhase.PLAYER_TURN) engine.stand()
            val hand = engine.state.hands[0]
            if (hand.result == RoundResult.PLAYER_WIN || hand.result == RoundResult.DEALER_BUST) {
                assertEquals(before + 10, engine.state.bankroll)
                checked = true
            }
        }
        assertTrue("Never observed a player win in 500 rounds", checked)
    }

    @Test
    fun `natural blackjack pays 3 to 2, truncated`() {
        var checked = false
        repeat(2000) {
            val engine = newEngine()
            val before = engine.state.bankroll
            engine.placeBet(15) // odd bet to exercise integer-division truncation: 15*3/2 = 22
            val hand = engine.state.hands[0]
            if (hand.result == RoundResult.PLAYER_BLACKJACK) {
                assertEquals(before - 15 + 15 + 22, engine.state.bankroll)
                checked = true
            }
        }
        assertTrue("Never observed a natural blackjack in 2000 rounds", checked)
    }

    @Test
    fun `losing to the dealer forfeits the bet`() {
        var checked = false
        repeat(500) {
            val engine = newEngine()
            val before = engine.state.bankroll
            engine.placeBet(10)
            while (engine.state.phase == RoundPhase.PLAYER_TURN) engine.stand()
            val hand = engine.state.hands[0]
            if (hand.result == RoundResult.DEALER_WIN) {
                assertEquals(before - 10, engine.state.bankroll)
                checked = true
            }
        }
        assertTrue("Never observed a dealer win in 500 rounds", checked)
    }

    // -- dealer AI -------------------------------------------------------------

    @Test
    fun `dealer never stands on a soft 17`() {
        repeat(500) {
            val engine = newEngine()
            engine.placeBet(10)
            while (engine.state.phase == RoundPhase.PLAYER_TURN) engine.stand()

            val hand = engine.state.hands[0]
            // Only a genuine stand forces the dealer to actually play out their hand; a hand
            // resolved via natural blackjack lets the dealer's original two cards stand as-is.
            if (hand.status == HandStatus.STOOD && !hand.isNaturalBlackjack) {
                val (total, soft) = handValue(engine.state.dealerCards)
                assertFalse("Dealer stood on a soft 17: ${engine.state.dealerCards}", total == 17 && soft)
            }
        }
    }

    @Test
    fun `dealer always finishes at 17 or higher, or busts`() {
        repeat(500) {
            val engine = newEngine()
            engine.placeBet(10)
            while (engine.state.phase == RoundPhase.PLAYER_TURN) engine.stand()

            val hand = engine.state.hands[0]
            if (hand.status == HandStatus.STOOD && !hand.isNaturalBlackjack) {
                val (total, _) = handValue(engine.state.dealerCards)
                assertTrue("Dealer stopped short at $total: ${engine.state.dealerCards}", total >= 17)
            }
        }
    }
}
