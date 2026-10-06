package com.sensinglocal.blackjack.game.table

import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.RoundResult

/**
 * What just happened at the table, returned by each [TableEngine] action in the order it happened.
 * Events only *describe* — [TableState] stays the source of truth and is never rebuilt from them —
 * so the UI can play them back at its own pace (card flights, seat highlights, chip moves) and
 * characters' speech bubbles can react to them. Seats are indices into [TableState.seats]; `hand`
 * is the index into that seat's hands.
 */
sealed interface TableEvent {
    /** The shoe was replaced by a freshly shuffled one before dealing. */
    data object ShoeReshuffled : TableEvent

    /** A stake went onto the table: the opening bet, or the extra bet of a double or split. */
    data class BetPlaced(val seat: Int, val amount: Long) : TableEvent

    /** A card to a player's hand: the deal, a hit, a double, or the new card of each split hand. */
    data class SeatCardDealt(val seat: Int, val hand: Int, val card: Card) : TableEvent

    /** A card to the dealer. [faceDown] is true only for the hole card on the deal. */
    data class DealerCardDealt(val card: Card, val faceDown: Boolean) : TableEvent

    /** The seat was dealt a natural blackjack. */
    data class Natural(val seat: Int) : TableEvent

    /** It is now this hand's turn to act. */
    data class TurnStarted(val seat: Int, val hand: Int) : TableEvent

    data class ActionTaken(val seat: Int, val hand: Int, val action: TableAction) : TableEvent

    data class Bust(val seat: Int, val hand: Int) : TableEvent

    /** All seats are done: the dealer turns over the hole card. */
    data class HoleCardRevealed(val card: Card) : TableEvent

    data class DealerBust(val total: Int) : TableEvent

    /** One hand's final result. [paid] < [fullPayout] means the house ran short. */
    data class HandSettled(
        val seat: Int,
        val hand: Int,
        val result: RoundResult,
        val bet: Long,
        val paid: Long,
        val fullPayout: Long
    ) : TableEvent

    /** The seat played this round and now has nothing left. */
    data class SeatBrokeOut(val seat: Int) : TableEvent

    /** The house pool hit zero: the table is over. */
    data object HouseBroke : TableEvent
}
