package com.sensinglocal.blackjack.game

enum class Suit(val symbol: String) {
    SPADES("♠"), HEARTS("♥"), DIAMONDS("♦"), CLUBS("♣")
}

enum class Rank(val label: String, val blackjackValue: Int) {
    TWO("2", 2), THREE("3", 3), FOUR("4", 4), FIVE("5", 5),
    SIX("6", 6), SEVEN("7", 7), EIGHT("8", 8), NINE("9", 9),
    TEN("10", 10), JACK("J", 10), QUEEN("Q", 10), KING("K", 10),
    ACE("A", 11)
}

data class Card(val rank: Rank, val suit: Suit) {
    val label: String get() = "${rank.label}${suit.symbol}"
}

class Deck(shoeCount: Int = 1) {
    private val cards = ArrayDeque<Card>()

    init {
        val fresh = buildList {
            repeat(shoeCount) {
                for (suit in Suit.entries) {
                    for (rank in Rank.entries) {
                        add(Card(rank, suit))
                    }
                }
            }
        }.shuffled()
        cards.addAll(fresh)
    }

    fun draw(): Card {
        if (cards.isEmpty()) throw IllegalStateException("Deck is empty")
        return cards.removeFirst()
    }

    fun remaining(): Int = cards.size
}

/** Returns best hand total (accounting for soft aces) and whether that total is soft. */
fun handValue(cards: List<Card>): Pair<Int, Boolean> {
    var total = cards.sumOf { it.rank.blackjackValue }
    var acesCountedAsEleven = cards.count { it.rank == Rank.ACE }
    while (total > 21 && acesCountedAsEleven > 0) {
        total -= 10
        acesCountedAsEleven--
    }
    return total to (acesCountedAsEleven > 0)
}

fun isBust(cards: List<Card>): Boolean = handValue(cards).first > 21

fun isBlackjack(cards: List<Card>): Boolean =
    cards.size == 2 && handValue(cards).first == 21
