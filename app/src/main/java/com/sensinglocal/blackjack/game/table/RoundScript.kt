package com.sensinglocal.blackjack.game.table

import com.sensinglocal.blackjack.game.Card

/**
 * Story control over one round of a table (keyed by round number in [TableConfig.scripts]; the
 * first round is 0). Nothing here is visible to the player: no event announces it.
 *
 * - **Rigged cards.** Pin any of the opening cards, and/or what comes off the shoe next. Anything
 *   left unpinned is dealt from the real shoe as usual. Seat entries for a seat that ends up sitting
 *   out (an AI under the table minimum) are ignored.
 * - **Forced actions.** For AI seats only (the human is never forced): the seat's 1st, 2nd, ...
 *   decision this round is taken from the list; once it runs out, or if the forced action isn't
 *   legal right then (e.g. DOUBLE with no money), the seat falls back to basic strategy.
 *
 * A script is static story data, not saved state: resuming mid-round re-reads it from the config,
 * and the table state remembers how many forced actions each seat has already used.
 */
data class RoundScript(
    /** Seat index -> its first two cards (a one-element list pins only the first). */
    val seatCards: Map<Int, List<Card>> = emptyMap(),
    val dealerUp: Card? = null,
    val dealerHole: Card? = null,
    /** The cards that come off the shoe after the opening deal, in order (hits, dealer draws...). */
    val draws: List<Card> = emptyList(),
    val actions: Map<Int, List<TableAction>> = emptyMap()
) {
    init {
        require(seatCards.values.all { it.size <= 2 }) { "A seat's rigged opening is at most two cards" }
    }
}
