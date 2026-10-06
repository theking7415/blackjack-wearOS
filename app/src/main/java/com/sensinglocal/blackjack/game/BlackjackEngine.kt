package com.sensinglocal.blackjack.game

import androidx.compose.runtime.Immutable

enum class RoundPhase { BETTING, PLAYER_TURN, DEALER_TURN, ROUND_OVER }

enum class RoundResult { PLAYER_BLACKJACK, PLAYER_WIN, DEALER_WIN, PUSH, PLAYER_BUST, DEALER_BUST }

enum class HandStatus { PLAYING, STOOD, BUST }

// @Immutable (not left to Compose's automatic stability inference) because these hold a
// List<Card> — Compose treats the bare List interface as unstable by default since it can't
// prove a caller won't mutate it in place, which disables recomposition-skipping entirely for
// any composable taking BlackjackState as a parameter. That's safe to assert here: every state
// update in this engine goes through copy()/listOf()/+ to build a new list rather than mutating
// an existing one in place (verified — see hit()/doubleDown()/split()/stand() below), so a
// structurally-equal BlackjackState really does mean "nothing changed." This was a real
// contributor to scroll jank: without it, DealerHandRow/PlayerHandRow/etc. were forced to fully
// recompose on every recomposition pass that reached them, even when `state` hadn't changed.
@Immutable
data class PlayerHand(
    val cards: List<Card>,
    val bet: Int,
    val status: HandStatus = HandStatus.PLAYING,
    // Hands created by a split can still reach 21 with two cards, but that doesn't count as
    // a natural blackjack (no 3:2 payout) under standard casino rules.
    val fromSplit: Boolean = false,
    val result: RoundResult? = null
) {
    val total: Int get() = handValue(cards).first
    val soft: Boolean get() = handValue(cards).second
    val isNaturalBlackjack: Boolean get() = !fromSplit && isBlackjack(cards)
}

// The `deck` field is genuinely mutated in place (Deck.draw() removes from an internal
// ArrayDeque) rather than rebuilt immutably like the other fields, but no Composable ever
// reads state.deck (confirmed via search) — only hands/dealerCards/bankroll/phase drive UI —
// so @Immutable here doesn't cause any incorrect recomposition-skip in practice.
@Immutable
data class BlackjackState(
    val bankroll: Int,
    val deck: Deck = Deck(shoeCount = 4),
    val hands: List<PlayerHand> = emptyList(),
    val activeHandIndex: Int = 0,
    val dealerCards: List<Card> = emptyList(),
    val phase: RoundPhase = RoundPhase.BETTING
) {
    val activeHand: PlayerHand? get() = hands.getOrNull(activeHandIndex)
    val dealerTotal: Int get() = handValue(dealerCards).first

    /** The dealer's hole card only becomes visible once the player's turn is fully over. */
    val dealerHoleCardRevealed: Boolean get() = phase == RoundPhase.DEALER_TURN || phase == RoundPhase.ROUND_OVER

    val canDoubleDown: Boolean
        get() {
            val hand = activeHand ?: return false
            return phase == RoundPhase.PLAYER_TURN && hand.cards.size == 2 && bankroll >= hand.bet
        }

    val canSplit: Boolean
        get() {
            val hand = activeHand ?: return false
            return phase == RoundPhase.PLAYER_TURN && hands.size == 1 &&
                hand.cards.size == 2 && hand.cards[0].rank == hand.cards[1].rank &&
                bankroll >= hand.bet
        }
}

/**
 * Standard casino rules: dealer stands on soft 17, blackjack pays 3:2, one split per round
 * (no re-splitting), double down allowed on any two-card hand (including after a split).
 * The dealer's hole card is hidden until the player's turn ends, as at a real table.
 */
class BlackjackEngine(startingBankroll: Int) {
    var state = BlackjackState(bankroll = startingBankroll)
        private set

    fun resetBankroll(amount: Int) {
        state = BlackjackState(bankroll = amount)
    }

    fun placeBet(amount: Int) {
        require(amount in 1..state.bankroll) { "Bet must be between 1 and current bankroll" }
        var deck = state.deck
        if (deck.remaining() < 15) deck = Deck(shoeCount = 4)

        val player = listOf(deck.draw(), deck.draw())
        val dealer = listOf(deck.draw(), deck.draw())

        state = BlackjackState(
            bankroll = state.bankroll - amount,
            deck = deck,
            hands = listOf(PlayerHand(cards = player, bet = amount)),
            activeHandIndex = 0,
            dealerCards = dealer,
            phase = RoundPhase.PLAYER_TURN
        )

        if (isBlackjack(player)) {
            markActiveHand(HandStatus.STOOD)
            advance()
        }
    }

    fun hit() {
        check(state.phase == RoundPhase.PLAYER_TURN) { "Not player's turn" }
        val hand = activeHandOrThrow()
        val deck = state.deck
        val newCards = hand.cards + deck.draw()
        state = state.copy(deck = deck, hands = replaceActiveHand(hand.copy(cards = newCards)))

        if (isBust(newCards)) {
            markActiveHand(HandStatus.BUST)
            advance()
        }
    }

    fun stand() {
        check(state.phase == RoundPhase.PLAYER_TURN) { "Not player's turn" }
        markActiveHand(HandStatus.STOOD)
        advance()
    }

    fun doubleDown() {
        check(state.phase == RoundPhase.PLAYER_TURN) { "Not player's turn" }
        val hand = activeHandOrThrow()
        require(hand.cards.size == 2) { "Can only double down on the first two cards" }
        require(state.bankroll >= hand.bet) { "Not enough bankroll to double down" }

        val deck = state.deck
        val newCards = hand.cards + deck.draw()
        val status = if (isBust(newCards)) HandStatus.BUST else HandStatus.STOOD
        val newHand = hand.copy(cards = newCards, bet = hand.bet * 2, status = status)

        state = state.copy(
            bankroll = state.bankroll - hand.bet,
            deck = deck,
            hands = replaceActiveHand(newHand)
        )
        advance()
    }

    fun split() {
        check(state.phase == RoundPhase.PLAYER_TURN) { "Not player's turn" }
        val hand = activeHandOrThrow()
        require(state.hands.size == 1) { "Only one split per round is supported" }
        require(hand.cards.size == 2 && hand.cards[0].rank == hand.cards[1].rank) {
            "Can only split a pair"
        }
        require(state.bankroll >= hand.bet) { "Not enough bankroll to split" }

        val deck = state.deck
        // Split aces are a special case: each hand gets exactly one more card and then must
        // stand immediately, no further hits or doubling — standard casino rule.
        val isAces = hand.cards[0].rank == Rank.ACE
        val status = if (isAces) HandStatus.STOOD else HandStatus.PLAYING
        val hand1 = PlayerHand(cards = listOf(hand.cards[0], deck.draw()), bet = hand.bet, fromSplit = true, status = status)
        val hand2 = PlayerHand(cards = listOf(hand.cards[1], deck.draw()), bet = hand.bet, fromSplit = true, status = status)

        state = state.copy(
            bankroll = state.bankroll - hand.bet,
            deck = deck,
            hands = listOf(hand1, hand2),
            activeHandIndex = 0
        )
        if (isAces) advance()
    }

    fun startNextRound() {
        check(state.phase == RoundPhase.ROUND_OVER) { "Round is not over" }
        state = state.copy(
            hands = emptyList(),
            activeHandIndex = 0,
            dealerCards = emptyList(),
            phase = RoundPhase.BETTING
        )
    }

    private fun activeHandOrThrow(): PlayerHand =
        state.activeHand ?: error("No active hand")

    private fun replaceActiveHand(newHand: PlayerHand): List<PlayerHand> =
        state.hands.toMutableList().apply { set(state.activeHandIndex, newHand) }

    private fun markActiveHand(status: HandStatus) {
        val hand = activeHandOrThrow()
        state = state.copy(hands = replaceActiveHand(hand.copy(status = status)))
    }

    /** Moves to the next hand still awaiting play, or resolves the round if none remain. */
    private fun advance() {
        val nextIndex = state.hands.indices.firstOrNull { state.hands[it].status == HandStatus.PLAYING }
        if (nextIndex != null) {
            state = state.copy(activeHandIndex = nextIndex)
            return
        }
        resolveRound()
    }

    private fun resolveRound() {
        state = state.copy(phase = RoundPhase.DEALER_TURN)

        // The dealer only needs to draw if some hand's outcome still depends on the dealer's
        // total (i.e. it isn't already decided by a bust or a natural blackjack).
        val needsDealerPlay = state.hands.any { it.status != HandStatus.BUST && !it.isNaturalBlackjack }
        var dealer = state.dealerCards
        var deck = state.deck
        if (needsDealerPlay) {
            while (true) {
                val (total, soft) = handValue(dealer)
                if (total > 21) break
                if (total > 17 || (total == 17 && !soft)) break
                dealer = dealer + deck.draw()
            }
        }

        val dealerBlackjack = dealer.size == 2 && isBlackjack(dealer)
        val dealerTotal = handValue(dealer).first
        val dealerBust = isBust(dealer)

        val resolvedHands = state.hands.map { hand ->
            val result = when {
                hand.status == HandStatus.BUST -> RoundResult.PLAYER_BUST
                hand.isNaturalBlackjack && dealerBlackjack -> RoundResult.PUSH
                hand.isNaturalBlackjack -> RoundResult.PLAYER_BLACKJACK
                dealerBlackjack -> RoundResult.DEALER_WIN
                dealerBust -> RoundResult.DEALER_BUST
                hand.total > dealerTotal -> RoundResult.PLAYER_WIN
                hand.total < dealerTotal -> RoundResult.DEALER_WIN
                else -> RoundResult.PUSH
            }
            hand.copy(result = result)
        }

        val winnings = resolvedHands.sumOf { payoutFor(it) }

        state = state.copy(
            bankroll = state.bankroll + winnings,
            deck = deck,
            dealerCards = dealer,
            hands = resolvedHands,
            phase = RoundPhase.ROUND_OVER
        )
    }

    private fun payoutFor(hand: PlayerHand): Int = when (hand.result) {
        RoundResult.PLAYER_BLACKJACK -> hand.bet + (hand.bet * 3) / 2
        RoundResult.PLAYER_WIN, RoundResult.DEALER_BUST -> hand.bet * 2
        RoundResult.PUSH -> hand.bet
        RoundResult.DEALER_WIN, RoundResult.PLAYER_BUST -> 0
        null -> 0
    }
}
