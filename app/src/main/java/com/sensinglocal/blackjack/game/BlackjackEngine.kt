package com.sensinglocal.blackjack.game

enum class RoundPhase { BETTING, PLAYER_TURN, DEALER_TURN, ROUND_OVER }

enum class RoundResult { PLAYER_BLACKJACK, PLAYER_WIN, DEALER_WIN, PUSH, PLAYER_BUST, DEALER_BUST }

data class BlackjackState(
    val bankroll: Int,
    val bet: Int = 0,
    val deck: Deck = Deck(shoeCount = 4),
    val playerCards: List<Card> = emptyList(),
    val dealerCards: List<Card> = emptyList(),
    val phase: RoundPhase = RoundPhase.BETTING,
    val result: RoundResult? = null
) {
    val playerTotal: Int get() = handValue(playerCards).first
    val dealerTotal: Int get() = handValue(dealerCards).first
    val playerSoft: Boolean get() = handValue(playerCards).second
}

/**
 * Standard casino rules: dealer stands on soft 17, blackjack pays 3:2.
 * Reshuffles a fresh 4-deck shoe once fewer than 15 cards remain.
 */
class BlackjackEngine(startingBankroll: Int) {
    var state = BlackjackState(bankroll = startingBankroll)
        private set

    fun placeBet(amount: Int) {
        require(amount in 1..state.bankroll) { "Bet must be between 1 and current bankroll" }
        var deck = state.deck
        if (deck.remaining() < 15) deck = Deck(shoeCount = 4)

        val player = listOf(deck.draw(), deck.draw())
        val dealer = listOf(deck.draw(), deck.draw())

        state = state.copy(
            bet = amount,
            deck = deck,
            playerCards = player,
            dealerCards = dealer,
            phase = RoundPhase.PLAYER_TURN,
            result = null
        )

        if (isBlackjack(player)) resolveRound()
    }

    fun hit() {
        check(state.phase == RoundPhase.PLAYER_TURN) { "Not player's turn" }
        val deck = state.deck
        val newPlayerCards = state.playerCards + deck.draw()
        state = state.copy(deck = deck, playerCards = newPlayerCards)

        if (isBust(newPlayerCards)) {
            state = state.copy(phase = RoundPhase.ROUND_OVER, result = RoundResult.PLAYER_BUST)
            payout()
        }
    }

    fun stand() {
        check(state.phase == RoundPhase.PLAYER_TURN) { "Not player's turn" }
        state = state.copy(phase = RoundPhase.DEALER_TURN)
        playDealerHand()
        resolveRound()
    }

    private fun playDealerHand() {
        var deck = state.deck
        var dealerCards = state.dealerCards
        while (true) {
            val (total, soft) = handValue(dealerCards)
            if (total > 21) break
            if (total > 17 || (total == 17 && !soft)) break
            dealerCards = dealerCards + deck.draw()
        }
        state = state.copy(deck = deck, dealerCards = dealerCards)
    }

    private fun resolveRound() {
        if (state.result != null) {
            payout()
            return
        }

        val playerBlackjack = isBlackjack(state.playerCards)
        val dealerBlackjack = isBlackjack(state.dealerCards)
        val playerTotal = state.playerTotal
        val dealerTotal = state.dealerTotal

        val result = when {
            isBust(state.playerCards) -> RoundResult.PLAYER_BUST
            playerBlackjack && dealerBlackjack -> RoundResult.PUSH
            playerBlackjack -> RoundResult.PLAYER_BLACKJACK
            dealerBlackjack -> RoundResult.DEALER_WIN
            isBust(state.dealerCards) -> RoundResult.DEALER_BUST
            playerTotal > dealerTotal -> RoundResult.PLAYER_WIN
            playerTotal < dealerTotal -> RoundResult.DEALER_WIN
            else -> RoundResult.PUSH
        }

        state = state.copy(phase = RoundPhase.ROUND_OVER, result = result)
        payout()
    }

    private fun payout() {
        val winnings = when (state.result) {
            RoundResult.PLAYER_BLACKJACK -> (state.bet * 3) / 2
            RoundResult.PLAYER_WIN, RoundResult.DEALER_BUST -> state.bet
            RoundResult.PUSH -> 0
            RoundResult.DEALER_WIN, RoundResult.PLAYER_BUST -> -state.bet
            null -> 0
        }
        state = state.copy(bankroll = state.bankroll + winnings)
    }

    fun startNextRound() {
        check(state.phase == RoundPhase.ROUND_OVER) { "Round is not over" }
        state = state.copy(
            bet = 0,
            playerCards = emptyList(),
            dealerCards = emptyList(),
            phase = RoundPhase.BETTING,
            result = null
        )
    }
}
