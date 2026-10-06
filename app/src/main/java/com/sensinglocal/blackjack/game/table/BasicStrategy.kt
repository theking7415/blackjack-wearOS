package com.sensinglocal.blackjack.game.table

import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.Rank

enum class TableAction { HIT, STAND, DOUBLE, SPLIT }

/**
 * Basic strategy for this table's rules: dealer hits soft 17, double on any two cards (also after
 * a split), one split per round. Used by AI seats. "Double" falls back to hit (or stand for soft
 * 18) when doubling isn't allowed; splits fall back to playing the pair as a plain total.
 */
object BasicStrategy {
    fun decide(hand: TableHand, dealerUp: Card, canDouble: Boolean, canSplit: Boolean): TableAction {
        val up = dealerUp.rank.blackjackValue // 2..10, ace = 11
        if (canSplit && hand.cards.size == 2 && hand.cards[0].rank == hand.cards[1].rank && shouldSplit(hand.cards[0].rank, up)) {
            return TableAction.SPLIT
        }
        return if (hand.soft) soft(hand.total, up, canDouble) else hard(hand.total, up, canDouble)
    }

    // Pairs not listed (5s, 10s, and the "don't split" cases) are played as their plain total.
    private fun shouldSplit(rank: Rank, up: Int): Boolean = when (rank) {
        Rank.ACE, Rank.EIGHT -> true
        Rank.NINE -> up in 2..6 || up == 8 || up == 9
        Rank.SEVEN, Rank.THREE, Rank.TWO -> up in 2..7
        Rank.SIX -> up in 2..6
        Rank.FOUR -> up in 5..6
        else -> false
    }

    private fun double(canDouble: Boolean) = if (canDouble) TableAction.DOUBLE else TableAction.HIT

    private fun hard(total: Int, up: Int, canDouble: Boolean): TableAction = when {
        total >= 17 -> TableAction.STAND
        total >= 13 -> if (up in 2..6) TableAction.STAND else TableAction.HIT
        total == 12 -> if (up in 4..6) TableAction.STAND else TableAction.HIT
        total == 11 -> double(canDouble) // dealer hits soft 17, so double even against an ace
        total == 10 -> if (up in 2..9) double(canDouble) else TableAction.HIT
        total == 9 -> if (up in 3..6) double(canDouble) else TableAction.HIT
        else -> TableAction.HIT
    }

    private fun soft(total: Int, up: Int, canDouble: Boolean): TableAction = when {
        total >= 19 -> TableAction.STAND
        total == 18 -> when (up) {
            in 2..6 -> if (canDouble) TableAction.DOUBLE else TableAction.STAND
            7, 8 -> TableAction.STAND
            else -> TableAction.HIT
        }
        total == 17 -> if (up in 3..6) double(canDouble) else TableAction.HIT
        total in 15..16 -> if (up in 4..6) double(canDouble) else TableAction.HIT
        else -> if (up in 5..6) double(canDouble) else TableAction.HIT
    }
}
