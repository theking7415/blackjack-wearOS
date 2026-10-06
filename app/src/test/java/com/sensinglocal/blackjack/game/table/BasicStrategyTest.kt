package com.sensinglocal.blackjack.game.table

import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.Rank
import com.sensinglocal.blackjack.game.Suit
import com.sensinglocal.blackjack.game.table.TableAction.DOUBLE
import com.sensinglocal.blackjack.game.table.TableAction.HIT
import com.sensinglocal.blackjack.game.table.TableAction.SPLIT
import com.sensinglocal.blackjack.game.table.TableAction.STAND
import org.junit.Assert.assertEquals
import org.junit.Test

class BasicStrategyTest {
    private fun rank(label: String) = Rank.entries.first { it.label == label }

    /** decide() for a hand like "10,6" against a dealer up-card like "9". */
    private fun decide(
        hand: String, up: String, canDouble: Boolean = true, canSplit: Boolean = true
    ): TableAction {
        val suits = Suit.entries
        val cards = hand.split(",").mapIndexed { i, r -> Card(rank(r), suits[i % suits.size]) }
        return BasicStrategy.decide(TableHand(cards, bet = 10), Card(rank(up), Suit.CLUBS), canDouble, canSplit)
    }

    @Test fun `hard totals up to 8 always hit`() {
        assertEquals(HIT, decide("5,3", "6"))
        assertEquals(HIT, decide("2,3", "10"))
    }

    @Test fun `hard 9 doubles against 3 to 6 only`() {
        assertEquals(DOUBLE, decide("5,4", "3"))
        assertEquals(DOUBLE, decide("5,4", "6"))
        assertEquals(HIT, decide("5,4", "2"))
        assertEquals(HIT, decide("5,4", "7"))
        assertEquals(HIT, decide("5,4", "6", canDouble = false))
    }

    @Test fun `hard 10 doubles against 2 to 9, hits 10 and ace`() {
        assertEquals(DOUBLE, decide("6,4", "9"))
        assertEquals(HIT, decide("6,4", "10"))
        assertEquals(HIT, decide("6,4", "A"))
    }

    @Test fun `hard 11 doubles against everything including an ace`() {
        assertEquals(DOUBLE, decide("6,5", "2"))
        assertEquals(DOUBLE, decide("6,5", "A"))
        assertEquals(HIT, decide("6,5", "A", canDouble = false))
    }

    @Test fun `hard 12 stands only against 4 to 6`() {
        assertEquals(HIT, decide("10,2", "3"))
        assertEquals(STAND, decide("10,2", "4"))
        assertEquals(STAND, decide("10,2", "6"))
        assertEquals(HIT, decide("10,2", "7"))
    }

    @Test fun `hard 13 to 16 stands against 2 to 6, hits otherwise`() {
        assertEquals(STAND, decide("10,3", "2"))
        assertEquals(STAND, decide("10,6", "6"))
        assertEquals(HIT, decide("10,6", "7"))
        assertEquals(HIT, decide("10,6", "10"))
        assertEquals(HIT, decide("10,6", "A"))
    }

    @Test fun `hard 17 and up always stand`() {
        assertEquals(STAND, decide("10,7", "A"))
        assertEquals(STAND, decide("10,10", "6"))
        assertEquals(STAND, decide("10,5,6", "10")) // three-card 21
    }

    @Test fun `soft 13 to 16 double only against the right small cards`() {
        assertEquals(DOUBLE, decide("A,2", "5"))
        assertEquals(HIT, decide("A,2", "4"))
        assertEquals(DOUBLE, decide("A,5", "4"))
        assertEquals(HIT, decide("A,5", "3"))
        assertEquals(HIT, decide("A,5", "4", canDouble = false))
    }

    @Test fun `soft 17 doubles against 3 to 6, else hits`() {
        assertEquals(DOUBLE, decide("A,6", "3"))
        assertEquals(HIT, decide("A,6", "2"))
        assertEquals(HIT, decide("A,6", "7"))
    }

    @Test fun `soft 18 doubles-or-stands vs 2 to 6, stands vs 7 and 8, hits vs 9 10 ace`() {
        assertEquals(DOUBLE, decide("A,7", "3"))
        assertEquals(STAND, decide("A,7", "3", canDouble = false))
        assertEquals(DOUBLE, decide("A,7", "2"))
        assertEquals(STAND, decide("A,7", "8"))
        assertEquals(HIT, decide("A,7", "9"))
        assertEquals(HIT, decide("A,7", "A"))
    }

    @Test fun `soft 19 and up stand`() {
        assertEquals(STAND, decide("A,8", "6"))
        assertEquals(STAND, decide("A,9", "10"))
    }

    @Test fun `aces and eights always split`() {
        assertEquals(SPLIT, decide("A,A", "A"))
        assertEquals(SPLIT, decide("8,8", "10"))
        assertEquals(SPLIT, decide("8,8", "A"))
    }

    @Test fun `tens and fives never split`() {
        assertEquals(STAND, decide("10,10", "6"))
        assertEquals(DOUBLE, decide("5,5", "6")) // plays as hard 10
        assertEquals(HIT, decide("5,5", "10"))
    }

    @Test fun `nines split except against 7, 10 and ace`() {
        assertEquals(SPLIT, decide("9,9", "6"))
        assertEquals(SPLIT, decide("9,9", "8"))
        assertEquals(SPLIT, decide("9,9", "9"))
        assertEquals(STAND, decide("9,9", "7"))
        assertEquals(STAND, decide("9,9", "10"))
        assertEquals(STAND, decide("9,9", "A"))
    }

    @Test fun `small pairs split against the right dealer cards`() {
        assertEquals(SPLIT, decide("7,7", "7"))
        assertEquals(HIT, decide("7,7", "8"))
        assertEquals(SPLIT, decide("6,6", "6"))
        assertEquals(HIT, decide("6,6", "7"))
        assertEquals(SPLIT, decide("4,4", "5"))
        assertEquals(HIT, decide("4,4", "7"))
        assertEquals(SPLIT, decide("3,3", "7"))
        assertEquals(SPLIT, decide("2,2", "2"))
        assertEquals(HIT, decide("2,2", "8"))
    }

    @Test fun `a pair that cannot split is played as its total`() {
        assertEquals(HIT, decide("8,8", "10", canSplit = false)) // hard 16 vs 10
        assertEquals(STAND, decide("8,8", "6", canSplit = false)) // hard 16 vs 6
        assertEquals(HIT, decide("A,A", "10", canSplit = false)) // soft 12
        assertEquals(DOUBLE, decide("A,A", "6", canSplit = false))
    }

    @Test fun `a three-card hand never splits or doubles by itself`() {
        assertEquals(HIT, decide("2,3,4", "6", canDouble = false, canSplit = false))
        assertEquals(STAND, decide("A,2,3,4", "10", canDouble = false, canSplit = false)) // soft 20
    }
}
