package com.sensinglocal.blackjack

import com.sensinglocal.blackjack.game.BlackjackState
import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.Deck
import com.sensinglocal.blackjack.game.HandStatus
import com.sensinglocal.blackjack.game.PlayerHand
import com.sensinglocal.blackjack.game.Rank
import com.sensinglocal.blackjack.game.RoundPhase
import com.sensinglocal.blackjack.game.RoundResult
import com.sensinglocal.blackjack.game.Suit
import org.json.JSONArray
import org.json.JSONObject

/** Phase 1 = Credits, phase 2 = Silver Coins, phase 3 = Gold Bars (see CLAUDE.md). */
enum class CurrencyTier { CREDITS, SILVER_COINS, GOLD_BARS }

const val OPENING_CUTSCENE_ID = "opening"
const val STARTING_STORY_BANKROLL = 50L

/** A mid-round table state, including the exact undealt deck order so resuming can't re-roll it. */
data class HandSnapshot(
    val phase: RoundPhase,
    val hands: List<PlayerHand>,
    val activeHandIndex: Int,
    val dealerCards: List<Card>,
    val deck: List<Card>
) {
    /** Bankroll is passed in (not stored here) because [StorySave.bankroll] is the single source. */
    fun toState(bankroll: Long) = BlackjackState(
        bankroll = bankroll.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        deck = Deck.fromSnapshot(deck),
        hands = hands,
        activeHandIndex = activeHandIndex,
        dealerCards = dealerCards,
        phase = phase
    )

    companion object {
        fun from(state: BlackjackState) = HandSnapshot(
            phase = state.phase,
            hands = state.hands,
            activeHandIndex = state.activeHandIndex,
            dealerCards = state.dealerCards,
            deck = state.deck.snapshot()
        )
    }
}

/**
 * Where Resume should drop the player. Save rules (decided 2026-10-05):
 * - [InHand]: the exact hand, deck order included, written after every action.
 * - [BeforeCutscene]: written when a cutscene *starts*; the cutscene only counts as seen (added
 *   to [StorySave.completedCutscenes], checkpoint advanced) once it finishes or is skipped, so
 *   quitting partway replays it.
 * - [BuyIn]: the buy-in selection screen, nothing further along.
 * - [Street]: placeholder hub after the opening cutscene until real story scenes exist.
 */
sealed interface Checkpoint {
    data object BuyIn : Checkpoint
    data object Street : Checkpoint
    data class BeforeCutscene(val cutsceneId: String) : Checkpoint
    data class InHand(val hand: HandSnapshot) : Checkpoint
}

data class StorySave(
    val currencyTier: CurrencyTier,
    /** Long, not Int: Credits climb toward 1,000,000,000 before the reset, and 3:2 payouts overshoot Int. */
    val bankroll: Long,
    val casino: Int,
    val table: Int,
    val completedCutscenes: Set<String>,
    val checkpoint: Checkpoint
) {
    companion object {
        fun newGame() = StorySave(
            currencyTier = CurrencyTier.CREDITS,
            bankroll = STARTING_STORY_BANKROLL,
            casino = 1,
            table = 1,
            completedCutscenes = emptySet(),
            checkpoint = Checkpoint.BeforeCutscene(OPENING_CUTSCENE_ID)
        )

        /** Slots from before real saves existed only had an "in use" flag and resumed at the street. */
        fun legacyMigrated() = newGame().copy(
            completedCutscenes = setOf(OPENING_CUTSCENE_ID),
            checkpoint = Checkpoint.Street
        )
    }
}

/**
 * JSON (de)serialization for [StorySave]. Versioned so later schema changes (e.g. the
 * multiplayer-table engine) can migrate or cleanly reject old saves; [decode] returns null for
 * anything corrupt or from an unknown version rather than throwing.
 */
object StorySaveCodec {
    const val VERSION = 1

    fun encode(save: StorySave): String = JSONObject().apply {
        put("version", VERSION)
        put("currencyTier", save.currencyTier.name)
        put("bankroll", save.bankroll)
        put("casino", save.casino)
        put("table", save.table)
        put("completedCutscenes", JSONArray(save.completedCutscenes.sorted()))
        put("checkpoint", encodeCheckpoint(save.checkpoint))
    }.toString()

    fun decode(text: String): StorySave? = try {
        val o = JSONObject(text)
        if (o.getInt("version") != VERSION) {
            null
        } else {
            StorySave(
                currencyTier = CurrencyTier.valueOf(o.getString("currencyTier")),
                bankroll = o.getLong("bankroll"),
                casino = o.getInt("casino"),
                table = o.getInt("table"),
                completedCutscenes = o.getJSONArray("completedCutscenes").strings().toSet(),
                checkpoint = decodeCheckpoint(o.getJSONObject("checkpoint"))
            )
        }
    } catch (e: Exception) {
        null
    }

    private fun encodeCheckpoint(c: Checkpoint) = JSONObject().apply {
        when (c) {
            Checkpoint.BuyIn -> put("type", "buyIn")
            Checkpoint.Street -> put("type", "street")
            is Checkpoint.BeforeCutscene -> {
                put("type", "beforeCutscene")
                put("cutsceneId", c.cutsceneId)
            }
            is Checkpoint.InHand -> {
                put("type", "inHand")
                put("hand", encodeHand(c.hand))
            }
        }
    }

    private fun decodeCheckpoint(o: JSONObject): Checkpoint = when (val type = o.getString("type")) {
        "buyIn" -> Checkpoint.BuyIn
        "street" -> Checkpoint.Street
        "beforeCutscene" -> Checkpoint.BeforeCutscene(o.getString("cutsceneId"))
        "inHand" -> Checkpoint.InHand(decodeHand(o.getJSONObject("hand")))
        else -> throw IllegalArgumentException("Unknown checkpoint type: $type")
    }

    private fun encodeHand(h: HandSnapshot) = JSONObject().apply {
        put("phase", h.phase.name)
        put("activeHandIndex", h.activeHandIndex)
        put("dealerCards", encodeCards(h.dealerCards))
        put("deck", encodeCards(h.deck))
        put("hands", JSONArray().apply {
            h.hands.forEach { hand ->
                put(JSONObject().apply {
                    put("cards", encodeCards(hand.cards))
                    put("bet", hand.bet)
                    put("status", hand.status.name)
                    put("fromSplit", hand.fromSplit)
                    hand.result?.let { put("result", it.name) }
                })
            }
        })
    }

    private fun decodeHand(o: JSONObject): HandSnapshot {
        val handsJson = o.getJSONArray("hands")
        return HandSnapshot(
            phase = RoundPhase.valueOf(o.getString("phase")),
            hands = List(handsJson.length()) { i ->
                val hand = handsJson.getJSONObject(i)
                PlayerHand(
                    cards = decodeCards(hand.getJSONArray("cards")),
                    bet = hand.getInt("bet"),
                    status = HandStatus.valueOf(hand.getString("status")),
                    fromSplit = hand.getBoolean("fromSplit"),
                    result = if (hand.has("result")) RoundResult.valueOf(hand.getString("result")) else null
                )
            },
            activeHandIndex = o.getInt("activeHandIndex"),
            dealerCards = decodeCards(o.getJSONArray("dealerCards")),
            deck = decodeCards(o.getJSONArray("deck"))
        )
    }

    // A card is "RANK:SUIT" using enum names, e.g. "ACE:SPADES" — ~200 of these per saved deck.
    private fun encodeCards(cards: List<Card>) = JSONArray(cards.map { "${it.rank.name}:${it.suit.name}" })

    private fun decodeCards(a: JSONArray): List<Card> = a.strings().map {
        val (rank, suit) = it.split(":")
        Card(Rank.valueOf(rank), Suit.valueOf(suit))
    }

    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
}
