package com.sensinglocal.blackjack.game.table

import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.HandStatus
import com.sensinglocal.blackjack.game.Rank
import com.sensinglocal.blackjack.game.RoundResult
import com.sensinglocal.blackjack.game.handValue
import com.sensinglocal.blackjack.game.isBlackjack
import com.sensinglocal.blackjack.game.isBust
import kotlin.random.Random

/**
 * Story Mode's table engine. Same rules as Quick Play's `BlackjackEngine` — dealer hits soft 17,
 * blackjack pays 3:2 (truncated), one split per round with no re-split, split aces get one card
 * each, double down on any two cards — but built on immutable state ([TableState], [Shoe]) with
 * Long money, and dealing in real table order (one card per seat, dealer up-card, repeat).
 *
 * **Step 2 scope:** exactly one HUMAN seat. AI seats, house bankroll, events and scripting land in
 * later steps (see CLAUDE.md, "Multiplayer table engine design"); the dealing, turn-advance and
 * settlement code is already written over seats so those steps extend it rather than rewrite it.
 */
class TableEngine(
    private val config: TableConfig,
    initialShoe: Shoe? = null,
    private val random: Random = Random.Default
) {
    init {
        require(config.seats.size == 1 && config.seats[0].kind == SeatKind.HUMAN) {
            "TableEngine currently supports a single human seat; AI seats arrive in a later step"
        }
    }

    var state: TableState = TableState(
        seats = config.seats.map { Seat(it.kind, it.stack) },
        shoe = initialShoe ?: Shoe.fresh(config.shoeCount, random),
        house = config.houseBankroll
    )
        private set

    fun placeBet(amount: Long) {
        check(state.phase == TablePhase.BETTING) { "Not the betting phase" }
        check(!state.houseBroke) { "The house is broke; this table is over" }
        val seatIndex = state.humanSeatIndex
        require(amount in 1..state.seats[seatIndex].stack) { "Bet must be between 1 and current stack" }

        var shoe = state.shoe
        if (shoe.remaining < config.reshuffleBelow) shoe = Shoe.fresh(config.shoeCount, random)

        // Only seats with a bet get cards. Step 4 adds AI bets here.
        val bets = mapOf(seatIndex to amount)
        val dealt = bets.keys.associateWith { mutableListOf<Card>() }
        val dealer = mutableListOf<Card>()
        repeat(2) {
            for (i in bets.keys.sorted()) {
                val (card, next) = shoe.draw(); shoe = next
                dealt.getValue(i).add(card)
            }
            val (card, next) = shoe.draw(); shoe = next
            dealer.add(card)
        }

        val seats = state.seats.mapIndexed { i, seat ->
            val bet = bets[i] ?: return@mapIndexed seat
            val cards = dealt.getValue(i)
            val status = if (isBlackjack(cards)) HandStatus.STOOD else HandStatus.PLAYING
            seat.copy(stack = seat.stack - bet, hands = listOf(TableHand(cards, bet, status)))
        }
        state = state.copy(
            seats = seats,
            shoe = shoe,
            dealerCards = dealer,
            phase = TablePhase.SEAT_TURN,
            activeSeat = seatIndex,
            activeHandIndex = 0
        )
        advance()
    }

    fun hit() {
        val (seatIndex, hand) = activeHumanHand()
        val (card, shoe) = state.shoe.draw()
        val cards = hand.cards + card
        val status = if (isBust(cards)) HandStatus.BUST else hand.status
        state = state.copy(shoe = shoe, seats = replaceActiveHand(seatIndex, hand.copy(cards = cards, status = status)))
        if (status == HandStatus.BUST) advance()
    }

    fun stand() {
        val (seatIndex, hand) = activeHumanHand()
        state = state.copy(seats = replaceActiveHand(seatIndex, hand.copy(status = HandStatus.STOOD)))
        advance()
    }

    fun doubleDown() {
        val (seatIndex, hand) = activeHumanHand()
        require(hand.cards.size == 2) { "Can only double down on the first two cards" }
        require(state.seats[seatIndex].stack >= hand.bet) { "Not enough stack to double down" }

        val (card, shoe) = state.shoe.draw()
        val cards = hand.cards + card
        val status = if (isBust(cards)) HandStatus.BUST else HandStatus.STOOD
        val doubled = hand.copy(cards = cards, bet = hand.bet * 2, status = status)
        val seats = replaceActiveHand(seatIndex, doubled).mapIndexed { i, seat ->
            if (i == seatIndex) seat.copy(stack = seat.stack - hand.bet) else seat
        }
        state = state.copy(shoe = shoe, seats = seats)
        advance()
    }

    fun split() {
        val (seatIndex, hand) = activeHumanHand()
        val seat = state.seats[seatIndex]
        require(seat.hands.size == 1) { "Only one split per round is supported" }
        require(hand.cards.size == 2 && hand.cards[0].rank == hand.cards[1].rank) { "Can only split a pair" }
        require(seat.stack >= hand.bet) { "Not enough stack to split" }

        val (card1, shoe1) = state.shoe.draw()
        val (card2, shoe2) = shoe1.draw()
        // Split aces: one more card each, then they must stand (standard casino rule).
        val status = if (hand.cards[0].rank == Rank.ACE) HandStatus.STOOD else HandStatus.PLAYING
        val hands = listOf(
            TableHand(listOf(hand.cards[0], card1), hand.bet, status, fromSplit = true),
            TableHand(listOf(hand.cards[1], card2), hand.bet, status, fromSplit = true)
        )
        val seats = state.seats.mapIndexed { i, s ->
            if (i == seatIndex) s.copy(stack = s.stack - hand.bet, hands = hands) else s
        }
        state = state.copy(shoe = shoe2, seats = seats, activeHandIndex = 0)
        advance()
    }

    fun startNextRound() {
        check(state.phase == TablePhase.ROUND_OVER) { "Round is not over" }
        state = state.copy(
            seats = state.seats.map { it.copy(hands = emptyList()) },
            dealerCards = emptyList(),
            phase = TablePhase.BETTING,
            activeSeat = 0,
            activeHandIndex = 0,
            round = state.round + 1
        )
    }

    private fun activeHumanHand(): Pair<Int, TableHand> {
        check(state.phase == TablePhase.SEAT_TURN) { "Not a seat's turn" }
        val seatIndex = state.activeSeat
        check(state.seats[seatIndex].kind == SeatKind.HUMAN) { "Not the human seat's turn" }
        return seatIndex to checkNotNull(state.activeHand) { "No active hand" }
    }

    private fun replaceActiveHand(seatIndex: Int, newHand: TableHand): List<Seat> =
        state.seats.mapIndexed { i, seat ->
            if (i != seatIndex) {
                seat
            } else {
                seat.copy(hands = seat.hands.toMutableList().apply { set(state.activeHandIndex, newHand) })
            }
        }

    /** Moves to the next hand still awaiting play (seats in table order), or settles the round. */
    private fun advance() {
        for ((seatIndex, seat) in state.seats.withIndex()) {
            val handIndex = seat.hands.indexOfFirst { it.status == HandStatus.PLAYING }
            if (handIndex >= 0) {
                state = state.copy(activeSeat = seatIndex, activeHandIndex = handIndex)
                return
            }
        }
        resolveRound()
    }

    private fun resolveRound() {
        val allHands = state.seats.flatMap { it.hands }
        // The dealer only draws if some hand's outcome still depends on the dealer's total
        // (i.e. it isn't already decided by a bust or a natural blackjack).
        val needsDealerPlay = allHands.any { it.status != HandStatus.BUST && !it.isNaturalBlackjack }
        var dealer = state.dealerCards
        var shoe = state.shoe
        if (needsDealerPlay) {
            while (true) {
                val (total, soft) = handValue(dealer)
                if (total > 21) break
                if (total > 17 || (total == 17 && !soft)) break // hits soft 17
                val (card, next) = shoe.draw(); shoe = next
                dealer = dealer + card
            }
        }

        val dealerBlackjack = dealer.size == 2 && isBlackjack(dealer)
        val dealerTotal = handValue(dealer).first
        val dealerBust = isBust(dealer)

        val judged = state.seats.map { seat ->
            seat.hands.map { hand ->
                hand.copy(
                    result = when {
                        hand.status == HandStatus.BUST -> RoundResult.PLAYER_BUST
                        hand.isNaturalBlackjack && dealerBlackjack -> RoundResult.PUSH
                        hand.isNaturalBlackjack -> RoundResult.PLAYER_BLACKJACK
                        dealerBlackjack -> RoundResult.DEALER_WIN
                        dealerBust -> RoundResult.DEALER_BUST
                        hand.total > dealerTotal -> RoundResult.PLAYER_WIN
                        hand.total < dealerTotal -> RoundResult.DEALER_WIN
                        else -> RoundResult.PUSH
                    }
                )
            }
        }

        // The house collects every losing bet first, then pays winnings out of that pool. If it
        // runs dry it pays what it has, in settlement order (human seat first). A returned stake
        // is the seat's own money and never touches the pool.
        var house = state.house + judged.sumOf { hands -> hands.filter { it.lost }.sumOf { it.bet } }
        val settled = judged.toMutableList()
        for (seatIndex in state.settlementOrder) {
            settled[seatIndex] = judged[seatIndex].map { hand ->
                val winnings = minOf(hand.winnings, house)
                house -= winnings
                hand.copy(paid = hand.stakeReturned + winnings)
            }
        }

        val seats = state.seats.mapIndexed { i, seat ->
            seat.copy(stack = seat.stack + settled[i].sumOf { it.paid }, hands = settled[i])
        }
        state = state.copy(
            seats = seats, shoe = shoe, house = house,
            dealerCards = dealer, phase = TablePhase.ROUND_OVER
        )
    }
}
