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
 * Long money, a finite house pool, and dealing in real table order (one card per seat, dealer
 * up-card, repeat).
 *
 * Up to [MAX_SEATS] seats, exactly one HUMAN; the rest are AI (or EMPTY). Seats act in table order.
 * After [placeBet] (or any human action) the engine may be waiting on an AI seat: call [step]
 * repeatedly — each call plays exactly one AI action, so the UI can pace them — until it returns
 * false (the human's turn, or the round is over). AI seats play [BasicStrategy] and bet a
 * percentage of their stack ([StyleProfile.betPercent]); one under the table minimum sits out.
 *
 * Every action returns the [TableEvent]s it caused, in order, for paced playback and speech
 * bubbles ([step] returns an empty list when there was no AI turn to play). The state is still
 * the source of truth; events only describe what happened.
 *
 * Story hooks: [TableConfig.scripts] rig a round's cards and force AI actions ([RoundScript]);
 * [grabAll] is the "shoot the dealer and run" money grab, followed by [TableConfig.policeHand].
 */
class TableEngine(
    private val config: TableConfig,
    initialShoe: Shoe? = null,
    private val random: Random = Random.Default
) {
    var state: TableState = TableState(
        seats = config.seats.map { Seat(it.kind, it.stack, style = it.style) },
        shoe = initialShoe ?: Shoe.fresh(config.shoeCount, random),
        house = config.houseBankroll
    )
        private set

    /**
     * Resumes a saved table. [saved] must be for this config's table (same seats, in order); the
     * script and everything else static still comes from the config.
     */
    fun restore(saved: TableState) {
        require(saved.seats.map { it.kind } == config.seats.map { it.kind }) {
            "Saved table doesn't match this table's seats"
        }
        state = saved
    }

    private val log = mutableListOf<TableEvent>()

    private fun emit(event: TableEvent) {
        log.add(event)
    }

    /** Runs [block], returning every event it emitted. */
    private fun recording(block: () -> Unit): List<TableEvent> {
        log.clear()
        block()
        return log.toList()
    }

    fun placeBet(amount: Long): List<TableEvent> = recording { doPlaceBet(amount) }

    private fun doPlaceBet(amount: Long) {
        check(state.phase == TablePhase.BETTING) { "Not the betting phase" }
        check(!state.houseBroke) { "The house is broke; this table is over" }
        val seatIndex = state.humanSeatIndex
        val stack = state.seats[seatIndex].stack
        require(amount in 1..stack) { "Bet must be between 1 and current stack" }
        require(amount >= minOf(config.minBet, stack)) { "Bet is below the table minimum" }

        var shoe = state.shoe
        if (shoe.remaining < config.reshuffleBelow) {
            shoe = Shoe.fresh(config.shoeCount, random)
            emit(TableEvent.ShoeReshuffled)
        }

        // Only seats with a bet get cards: the human, plus every AI seat that can afford the minimum.
        val bets = buildMap<Int, Long> {
            state.seats.forEachIndexed { i, seat ->
                val bet = if (i == seatIndex) amount else aiBet(seat)
                if (bet != null) put(i, bet)
            }
        }
        config.scripts[state.round]?.let { shoe = shoe.withScriptedTop(scriptedSlots(it, bets.keys.sorted())) }

        for (i in bets.keys.sorted()) emit(TableEvent.BetPlaced(i, bets.getValue(i)))
        val dealt = bets.keys.associateWith { mutableListOf<Card>() }
        val dealer = mutableListOf<Card>()
        repeat(2) { round ->
            for (i in bets.keys.sorted()) {
                val (card, next) = shoe.draw(); shoe = next
                dealt.getValue(i).add(card)
                emit(TableEvent.SeatCardDealt(i, 0, card))
            }
            val (card, next) = shoe.draw(); shoe = next
            dealer.add(card)
            emit(TableEvent.DealerCardDealt(card, faceDown = round == 1))
        }

        val seats = state.seats.mapIndexed { i, seat ->
            val bet = bets[i] ?: return@mapIndexed seat
            val cards = dealt.getValue(i)
            val status = if (isBlackjack(cards)) HandStatus.STOOD else HandStatus.PLAYING
            if (status == HandStatus.STOOD) emit(TableEvent.Natural(i))
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

    fun hit(): List<TableEvent> = recording { applyHit(SeatKind.HUMAN) }
    fun stand(): List<TableEvent> = recording { applyStand(SeatKind.HUMAN) }
    fun doubleDown(): List<TableEvent> = recording { applyDoubleDown(SeatKind.HUMAN) }
    fun split(): List<TableEvent> = recording { applySplit(SeatKind.HUMAN) }

    /**
     * Plays one action for the AI seat whose turn it is. Returns what happened, or an empty list
     * if it isn't an AI's turn (the human's turn, or no round in progress).
     */
    fun step(): List<TableEvent> = recording {
        if (state.phase != TablePhase.SEAT_TURN) return@recording
        if (state.seats[state.activeSeat].kind != SeatKind.AI) return@recording
        val hand = checkNotNull(state.activeHand) { "No active hand" }
        val seatIndex = state.activeSeat
        val used = state.scriptCursor[seatIndex] ?: 0
        val forced = config.scripts[state.round]?.actions?.get(seatIndex)?.getOrNull(used)
        state = state.copy(scriptCursor = state.scriptCursor + (seatIndex to used + 1))
        val legalForced = forced?.takeIf {
            when (it) {
                TableAction.HIT, TableAction.STAND -> true
                TableAction.DOUBLE -> state.canDoubleDown
                TableAction.SPLIT -> state.canSplit
            }
        }
        when (legalForced ?: BasicStrategy.decide(hand, state.dealerCards.first(), state.canDoubleDown, state.canSplit)) {
            TableAction.HIT -> applyHit(SeatKind.AI)
            TableAction.STAND -> applyStand(SeatKind.AI)
            TableAction.DOUBLE -> applyDoubleDown(SeatKind.AI)
            TableAction.SPLIT -> applySplit(SeatKind.AI)
        }
    }

    /** The opening deal as shoe slots, in real deal order (each betting seat, then the dealer, twice), then the scripted draws. */
    private fun scriptedSlots(script: RoundScript, bettors: List<Int>): List<Card?> {
        val slots = mutableListOf<Card?>()
        repeat(2) { r ->
            for (i in bettors) slots.add(script.seatCards[i]?.getOrNull(r))
            slots.add(if (r == 0) script.dealerUp else script.dealerHole)
        }
        slots.addAll(script.draws)
        return slots
    }

    /**
     * Moves every other seat's stack and the whole house pool to the human and ends the table
     * ("shoot the dealer and run"). Only between hands, and only on a finite house. The caller
     * (story layer) then runs the police hand ([TableConfig.policeHand]) with the human's stack.
     */
    fun grabAll(): List<TableEvent> = recording {
        check(state.phase == TablePhase.BETTING) { "Can only grab the money between hands" }
        check(!state.houseBroke) { "The table is already over" }
        check(config.houseBankroll < UNLIMITED_HOUSE) { "An unlimited house can't be grabbed" }

        val human = state.humanSeatIndex
        val fromSeats = state.seats.withIndex()
            .filter { (i, seat) -> i != human && seat.stack > 0 }
            .associate { (i, seat) -> i to seat.stack }
        val fromHouse = state.house
        val total = fromSeats.values.sum() + fromHouse
        state = state.copy(
            seats = state.seats.mapIndexed { i, seat ->
                when {
                    i == human -> seat.copy(stack = seat.stack + total)
                    i in fromSeats -> seat.copy(stack = 0)
                    else -> seat
                }
            },
            house = 0,
            grabbed = true
        )
        emit(TableEvent.MoneyGrabbed(fromSeats, fromHouse))
        emit(TableEvent.HouseBroke)
    }

    private fun aiBet(seat: Seat): Long? {
        if (seat.kind != SeatKind.AI || seat.stack <= 0 || seat.stack < config.minBet) return null
        return (seat.stack * seat.style.betPercent / 100).coerceIn(config.minBet, seat.stack)
    }

    private fun applyHit(kind: SeatKind) {
        val (seatIndex, hand) = activeHand(kind)
        emit(TableEvent.ActionTaken(seatIndex, state.activeHandIndex, TableAction.HIT))
        val (card, shoe) = state.shoe.draw()
        emit(TableEvent.SeatCardDealt(seatIndex, state.activeHandIndex, card))
        val cards = hand.cards + card
        val status = if (isBust(cards)) HandStatus.BUST else hand.status
        state = state.copy(shoe = shoe, seats = replaceActiveHand(seatIndex, hand.copy(cards = cards, status = status)))
        if (status == HandStatus.BUST) {
            emit(TableEvent.Bust(seatIndex, state.activeHandIndex))
            advance()
        }
    }

    private fun applyStand(kind: SeatKind) {
        val (seatIndex, hand) = activeHand(kind)
        emit(TableEvent.ActionTaken(seatIndex, state.activeHandIndex, TableAction.STAND))
        state = state.copy(seats = replaceActiveHand(seatIndex, hand.copy(status = HandStatus.STOOD)))
        advance()
    }

    private fun applyDoubleDown(kind: SeatKind) {
        val (seatIndex, hand) = activeHand(kind)
        require(hand.cards.size == 2) { "Can only double down on the first two cards" }
        require(state.seats[seatIndex].stack >= hand.bet) { "Not enough stack to double down" }

        emit(TableEvent.ActionTaken(seatIndex, state.activeHandIndex, TableAction.DOUBLE))
        emit(TableEvent.BetPlaced(seatIndex, hand.bet))
        val (card, shoe) = state.shoe.draw()
        emit(TableEvent.SeatCardDealt(seatIndex, state.activeHandIndex, card))
        val cards = hand.cards + card
        val status = if (isBust(cards)) HandStatus.BUST else HandStatus.STOOD
        if (status == HandStatus.BUST) emit(TableEvent.Bust(seatIndex, state.activeHandIndex))
        val doubled = hand.copy(cards = cards, bet = hand.bet * 2, status = status)
        val seats = replaceActiveHand(seatIndex, doubled).mapIndexed { i, seat ->
            if (i == seatIndex) seat.copy(stack = seat.stack - hand.bet) else seat
        }
        state = state.copy(shoe = shoe, seats = seats)
        advance()
    }

    private fun applySplit(kind: SeatKind) {
        val (seatIndex, hand) = activeHand(kind)
        val seat = state.seats[seatIndex]
        require(seat.hands.size == 1) { "Only one split per round is supported" }
        require(hand.cards.size == 2 && hand.cards[0].rank == hand.cards[1].rank) { "Can only split a pair" }
        require(seat.stack >= hand.bet) { "Not enough stack to split" }

        emit(TableEvent.ActionTaken(seatIndex, state.activeHandIndex, TableAction.SPLIT))
        emit(TableEvent.BetPlaced(seatIndex, hand.bet))
        val (card1, shoe1) = state.shoe.draw()
        val (card2, shoe2) = shoe1.draw()
        emit(TableEvent.SeatCardDealt(seatIndex, 0, card1))
        emit(TableEvent.SeatCardDealt(seatIndex, 1, card2))
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
            round = state.round + 1,
            scriptCursor = emptyMap()
        )
    }

    /** The active seat's active hand, provided that seat is of [kind] (humans can't act for AIs and vice versa). */
    private fun activeHand(kind: SeatKind): Pair<Int, TableHand> {
        check(state.phase == TablePhase.SEAT_TURN) { "Not a seat's turn" }
        val seatIndex = state.activeSeat
        check(state.seats[seatIndex].kind == kind) { "Not the ${kind.name.lowercase()} seat's turn" }
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
                emit(TableEvent.TurnStarted(seatIndex, handIndex))
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
        emit(TableEvent.HoleCardRevealed(dealer[1]))
        if (needsDealerPlay) {
            while (true) {
                val (total, soft) = handValue(dealer)
                if (total > 21) break
                if (total > 17 || (total == 17 && !soft)) break // hits soft 17
                val (card, next) = shoe.draw(); shoe = next
                dealer = dealer + card
                emit(TableEvent.DealerCardDealt(card, faceDown = false))
            }
        }

        val dealerBlackjack = dealer.size == 2 && isBlackjack(dealer)
        val dealerTotal = handValue(dealer).first
        val dealerBust = isBust(dealer)
        if (dealerBust) emit(TableEvent.DealerBust(dealerTotal))

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

        for ((i, seat) in seats.withIndex()) {
            for ((h, hand) in seat.hands.withIndex()) {
                emit(TableEvent.HandSettled(i, h, checkNotNull(hand.result), hand.bet, hand.paid, hand.fullPayout))
            }
        }
        for ((i, seat) in seats.withIndex()) {
            if (seat.hands.isNotEmpty() && seat.stack == 0L) emit(TableEvent.SeatBrokeOut(i))
        }
        if (state.houseBroke) emit(TableEvent.HouseBroke)
    }
}
