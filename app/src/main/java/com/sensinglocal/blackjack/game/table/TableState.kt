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
    val result: RoundResult? = null
) {
    val total: Int get() = handValue(cards).first
    val soft: Boolean get() = handValue(cards).second
    val isNaturalBlackjack: Boolean get() = !fromSplit && isBlackjack(cards)
}

data class Seat(
    val kind: SeatKind,
    val stack: Long,
    val hands: List<TableHand> = emptyList()
)

data class SeatConfig(val kind: SeatKind, val stack: Long)

data class TableConfig(
    val seats: List<SeatConfig>,
    val shoeCount: Int = 4,
    /** A fresh shuffled shoe replaces the current one at the start of a round below this many cards. */
    val reshuffleBelow: Int = 15
) {
    companion object {
        /** Player vs. dealer, the Quick Play shape. */
        fun singlePlayer(stack: Long) = TableConfig(listOf(SeatConfig(SeatKind.HUMAN, stack)))
    }
}

/** Fully immutable: every engine action produces a new [TableState]. */
data class TableState(
    val seats: List<Seat>,
    val shoe: Shoe,
    val dealerCards: List<Card> = emptyList(),
    val phase: TablePhase = TablePhase.BETTING,
    val activeSeat: Int = 0,
    val activeHandIndex: Int = 0,
    val round: Int = 0
) {
    val humanSeatIndex: Int get() = seats.indexOfFirst { it.kind == SeatKind.HUMAN }
    val humanSeat: Seat get() = seats[humanSeatIndex]

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
