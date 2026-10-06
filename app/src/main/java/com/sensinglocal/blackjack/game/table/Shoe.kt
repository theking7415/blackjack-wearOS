package com.sensinglocal.blackjack.game.table

import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.Rank
import com.sensinglocal.blackjack.game.Suit
import kotlin.random.Random

/**
 * An immutable card shoe: a fixed card order plus a read position. [draw] never mutates — it
 * returns the card and the *next* shoe — so any [TableState] holding a shoe can be kept, compared,
 * saved or rewound (undo, live-odds simulation) without snapshotting anything.
 */
class Shoe private constructor(private val cards: List<Card>, private val position: Int) {
    val remaining: Int get() = cards.size - position

    data class Drawn(val card: Card, val shoe: Shoe)

    fun draw(): Drawn {
        check(remaining > 0) { "Shoe is empty" }
        return Drawn(cards[position], Shoe(cards, position + 1))
    }

    /** The undealt cards in draw order (next card first). */
    fun snapshot(): List<Card> = cards.subList(position, cards.size).toList()

    /**
     * A shoe whose next cards are [slots] in order: a card pins that position, null leaves it to the
     * real shoe. Pinned cards are taken out of the remaining shoe (when present) so the physical card
     * counts stay as honest as the script allows; unpinned positions and everything after the slots
     * keep the original order.
     */
    fun withScriptedTop(slots: List<Card?>): Shoe {
        val rest = snapshot().toMutableList()
        for (card in slots.filterNotNull()) rest.remove(card)
        val fillers = rest.iterator()
        val top = slots.map { it ?: run { check(fillers.hasNext()) { "Shoe too small for the scripted deal" }; fillers.next() } }
        return of(top + fillers.asSequence().toList())
    }

    override fun equals(other: Any?): Boolean = other is Shoe && snapshot() == other.snapshot()
    override fun hashCode(): Int = snapshot().hashCode()
    override fun toString(): String = "Shoe(remaining=$remaining)"

    companion object {
        /** A shoe with exactly [cards] in this draw order — used for saves, scripting and tests. */
        fun of(cards: List<Card>): Shoe = Shoe(cards.toList(), 0)

        fun fresh(shoeCount: Int, random: Random = Random.Default): Shoe {
            val all = buildList {
                repeat(shoeCount) {
                    for (suit in Suit.entries) for (rank in Rank.entries) add(Card(rank, suit))
                }
            }
            return Shoe(all.shuffled(random), 0)
        }
    }
}
