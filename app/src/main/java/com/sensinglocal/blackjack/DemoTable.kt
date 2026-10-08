package com.sensinglocal.blackjack

import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.Rank
import com.sensinglocal.blackjack.game.Suit
import com.sensinglocal.blackjack.game.table.RoundScript
import com.sensinglocal.blackjack.game.table.SeatConfig
import com.sensinglocal.blackjack.game.table.SeatKind
import com.sensinglocal.blackjack.game.table.StyleProfile
import com.sensinglocal.blackjack.game.table.TableAction
import com.sensinglocal.blackjack.game.table.TableConfig

/**
 * The developer-only "Table Preview" table (Settings): you and three AI seats around a dealer, so
 * the multiplayer table screen can be tried on the watch before real Story Mode tables exist.
 * Throw this away (or keep it as a test table) once story tables are built.
 */
object DemoTable {
    /** Index-aligned with the config's seats (Rex acts first, then you, Mara, Vic). */
    val seatNames = listOf("Rex", "You", "Mara", "Vic")

    val config = TableConfig(
        seats = listOf(
            SeatConfig(SeatKind.AI, 300, StyleProfile.RECKLESS),
            SeatConfig(SeatKind.HUMAN, 400),
            SeatConfig(SeatKind.AI, 300, StyleProfile.STEADY),
            SeatConfig(SeatKind.AI, 300, StyleProfile.CAUTIOUS)
        ),
        houseBankroll = 5_000,
        minBet = 5,
        scripts = mapOf(
            // Round 1: Rex is dealt 10+6, is forced to hit, and the next card is a 10: he busts.
            // (He acts first, so the ring turns to him straight away.)
            0 to RoundScript(
                seatCards = mapOf(0 to listOf(Card(Rank.TEN, Suit.SPADES), Card(Rank.SIX, Suit.DIAMONDS))),
                draws = listOf(Card(Rank.TEN, Suit.HEARTS)),
                actions = mapOf(0 to listOf(TableAction.HIT))
            ),
            // Round 2: you are dealt a pair of eights, so Split (and the four-button row) can be tried.
            1 to RoundScript(
                seatCards = mapOf(1 to listOf(Card(Rank.EIGHT, Suit.SPADES), Card(Rank.EIGHT, Suit.DIAMONDS)))
            )
        )
    )
}
