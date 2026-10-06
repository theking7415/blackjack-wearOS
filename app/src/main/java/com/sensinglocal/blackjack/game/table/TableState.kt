package com.sensinglocal.blackjack.game.table

import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.HandStatus
import com.sensinglocal.blackjack.game.RoundResult
import com.sensinglocal.blackjack.game.handValue
import com.sensinglocal.blackjack.game.isBlackjack

enum class SeatKind { HUMAN, AI, EMPTY }

/**
 * DEALER_TURN is reserved for step 5's paced event playback; until then the dealer plays and the
 * hands settle inside the single transition from SEAT_TURN to ROUND_OVER.
 */
enum class TablePhase { BETTING, SEAT_TURN, DEALER_TURN, ROUND_OVER }

/** Like `PlayerHand`, but with a Long bet: money is Long everywhere at the table (see CLAUDE.md). */
data class TableHand(
    val cards: List<Card>,
    val bet: Long,
    val status: HandStatus = HandStatus.PLAYING,
    /** A split hand can reach 21 on two cards but is never a natural (no 3:2 payout). */
    val fromSplit: Boolean = false,
    val result: RoundResult? = null,
    /** What the seat actually got back at settlement (stake + winnings); less than [fullPayout] if the house ran dry. */
    val paid: Long = 0
) {
    /** House money this hand wins when the result is a win (even money, or 3:2 truncated for a natural). */
    val winnings: Long get() = when (result) {
        RoundResult.PLAYER_BLACKJACK -> (bet * 3) / 2
        RoundResult.PLAYER_WIN, RoundResult.DEALER_BUST -> bet
        else -> 0L
    }

    /** The seat's own bet handed back: on any win or a push, never on a loss. Never drawn from the house. */
    val stakeReturned: Long get() = when (result) {
        RoundResult.PLAYER_BLACKJACK, RoundResult.PLAYER_WIN, RoundResult.DEALER_BUST, RoundResult.PUSH -> bet
        else -> 0L
    }

    val fullPayout: Long get() = stakeReturned + winnings
    val lost: Boolean get() = result == RoundResult.DEALER_WIN || result == RoundResult.PLAYER_BUST

    val total: Int get() = handValue(cards).first
    val soft: Boolean get() = handValue(cards).second
    val isNaturalBlackjack: Boolean get() = !fromSplit && isBlackjack(cards)
}

/**
 * How an AI character plays. For now only bet sizing: [betPercent] of its stack per round (clamped
 * to the table minimum). Play itself is plain basic strategy; a mistake/lean model can be added
 * here later without touching the engine's shape.
 */
data class StyleProfile(val betPercent: Int) {
    init {
        require(betPercent in 1..100) { "betPercent must be 1..100" }
    }

    companion object {
        val CAUTIOUS = StyleProfile(5)
        val STEADY = StyleProfile(10)
        val RECKLESS = StyleProfile(25)
    }
}

data class Seat(
    val kind: SeatKind,
    val stack: Long,
    val hands: List<TableHand> = emptyList(),
    val style: StyleProfile = StyleProfile.STEADY
)

data class SeatConfig(
    val kind: SeatKind,
    val stack: Long,
    val style: StyleProfile = StyleProfile.STEADY
)

/**
 * Stand-in for "the house can't run out" (Quick Play-shaped tables, tests). Half of Long.MAX_VALUE
 * so adding collected bets to it can never overflow.
 */
const val UNLIMITED_HOUSE = Long.MAX_VALUE / 2

const val MAX_SEATS = 4

data class TableConfig(
    /** In table order (index 0 acts first). 1..[MAX_SEATS] seats, exactly one HUMAN. */
    val seats: List<SeatConfig>,
    val shoeCount: Int = 4,
    /**
     * A fresh shuffled shoe replaces the current one at the start of a round below this many cards.
     * Defaults to 15 per seat (15 for a lone player, as in Quick Play) so a full table can't run dry mid-round.
     */
    val reshuffleBelow: Int = 15 * seats.size,
    /** The table's finite house pool. Story tables set this; when it hits zero the table is over. */
    val houseBankroll: Long = UNLIMITED_HOUSE,
    val minBet: Long = 1,
    /** Story scripts by round number (first round = 0); see [RoundScript]. */
    val scripts: Map<Int, RoundScript> = emptyMap()
) {
    init {
        require(seats.size in 1..MAX_SEATS) { "A table has 1..$MAX_SEATS seats" }
        require(seats.count { it.kind == SeatKind.HUMAN } == 1) { "A table needs exactly one human seat" }
        require(houseBankroll > 0) { "House bankroll must be positive" }
        require(minBet > 0) { "Minimum bet must be positive" }
        for ((round, script) in scripts) {
            require(round >= 0) { "Script rounds start at 0" }
            require(script.seatCards.keys.all { it in seats.indices }) { "Script rigs a seat that doesn't exist" }
            require(script.actions.keys.all { it in seats.indices && seats[it].kind == SeatKind.AI }) {
                "Forced actions are only for AI seats"
            }
        }
    }

    companion object {
        /** Player vs. dealer, the Quick Play shape. */
        fun singlePlayer(stack: Long, houseBankroll: Long = UNLIMITED_HOUSE) =
            TableConfig(listOf(SeatConfig(SeatKind.HUMAN, stack)), houseBankroll = houseBankroll)

        /**
         * The all-or-nothing hand against the police after "shoot the dealer and run": the whole
         * [stake] must be bet (minimum bet = stake), and the house can cover even a 3:2 natural.
         * Win = the stack doubles; lose = it hits zero.
         */
        fun policeHand(stake: Long): TableConfig {
            require(stake > 0) { "Nothing to stake" }
            return TableConfig(
                listOf(SeatConfig(SeatKind.HUMAN, stake)),
                houseBankroll = stake * 2,
                minBet = stake
            )
        }
    }
}

/** Fully immutable: every engine action produces a new [TableState]. */
data class TableState(
    val seats: List<Seat>,
    val shoe: Shoe,
    /** The table's remaining house pool. Bets in play are held on the table, not counted here. */
    val house: Long,
    val dealerCards: List<Card> = emptyList(),
    val phase: TablePhase = TablePhase.BETTING,
    val activeSeat: Int = 0,
    val activeHandIndex: Int = 0,
    val round: Int = 0,
    /** Per seat: how many scripted actions it has used this round (see [RoundScript.actions]). */
    val scriptCursor: Map<Int, Int> = emptyMap(),
    /** The player grabbed the table's money and ran; the table is over (the house reads as broke). */
    val grabbed: Boolean = false
) {
    val humanSeatIndex: Int get() = seats.indexOfFirst { it.kind == SeatKind.HUMAN }
    val humanSeat: Seat get() = seats[humanSeatIndex]

    /** The house ran out of money: the table is over (the story layer sends the player onward). */
    val houseBroke: Boolean get() = house <= 0L

    /** Winners are paid in this order when the house is short: the human first, then the rest in seat order. */
    val settlementOrder: List<Int>
        get() = listOf(humanSeatIndex) + seats.indices.filter { it != humanSeatIndex }

    val activeHand: TableHand? get() =
        if (phase == TablePhase.SEAT_TURN) seats.getOrNull(activeSeat)?.hands?.getOrNull(activeHandIndex) else null

    val dealerTotal: Int get() = handValue(dealerCards).first

    /** The dealer's hole card only becomes visible once every seat has finished. */
    val dealerHoleCardRevealed: Boolean
        get() = phase == TablePhase.DEALER_TURN || phase == TablePhase.ROUND_OVER

    val canDoubleDown: Boolean
        get() {
            val hand = activeHand ?: return false
            return hand.cards.size == 2 && seats[activeSeat].stack >= hand.bet
        }

    val canSplit: Boolean
        get() {
            val hand = activeHand ?: return false
            val seat = seats[activeSeat]
            return seat.hands.size == 1 && hand.cards.size == 2 &&
                hand.cards[0].rank == hand.cards[1].rank && seat.stack >= hand.bet
        }
}
