package com.sensinglocal.blackjack

import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.HandStatus
import com.sensinglocal.blackjack.game.Rank
import com.sensinglocal.blackjack.game.RoundResult
import com.sensinglocal.blackjack.game.Suit
import com.sensinglocal.blackjack.game.table.Seat
import com.sensinglocal.blackjack.game.table.SeatKind
import com.sensinglocal.blackjack.game.table.Shoe
import com.sensinglocal.blackjack.game.table.StyleProfile
import com.sensinglocal.blackjack.game.table.TableHand
import com.sensinglocal.blackjack.game.table.TablePhase
import com.sensinglocal.blackjack.game.table.TableState
import org.json.JSONArray
import org.json.JSONObject

/** Phase 1 = Credits, phase 2 = Silver Coins, phase 3 = Gold Bars (see CLAUDE.md). */
enum class CurrencyTier { CREDITS, SILVER_COINS, GOLD_BARS }

const val OPENING_CUTSCENE_ID = "opening"
const val STARTING_STORY_BANKROLL = 50L

/**
 * Where Resume should drop the player. Save rules (decided 2026-10-05):
 * - [AtTable]: the whole table — every seat's stack and hands, the dealer, the exact undealt shoe
 *   order, the house pool, whose turn it is, the round number and script cursor — written after
 *   every action. Covers mid-hand, between hands (BETTING) and the results screen (ROUND_OVER).
 *   The table's *config* (seats' characters, scripts, minimum bet...) is static story data rebuilt
 *   from [StorySave.casino]/[StorySave.table], not saved.
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
    data class AtTable(val table: TableState) : Checkpoint
}

data class StorySave(
    val currencyTier: CurrencyTier,
    /**
     * The player's money **off the table**. Sitting down moves the chosen buy-in into the human seat's
     * stack (inside [Checkpoint.AtTable]); leaving returns what's left. So while at a table this is
     * not the whole fortune. Long, not Int: Credits climb toward 1,000,000,000 before the reset, and
     * 3:2 payouts overshoot Int.
     */
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
 * JSON (de)serialization for [StorySave]. Versioned so schema changes can migrate or cleanly
 * reject old saves; [decode] returns null for anything corrupt or from an unknown version rather
 * than throwing. Version 2 replaced the single-hand snapshot with the whole table ([Checkpoint.AtTable]);
 * v1 saves still load (their buy-in/cutscene/street checkpoints are unchanged), except a v1 in-hand
 * checkpoint, which never shipped and reads as an unreadable save.
 */
object StorySaveCodec {
    const val VERSION = 2

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
        if (o.getInt("version") !in 1..VERSION) {
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
            is Checkpoint.AtTable -> {
                put("type", "atTable")
                put("table", encodeTable(c.table))
            }
        }
    }

    private fun decodeCheckpoint(o: JSONObject): Checkpoint = when (val type = o.getString("type")) {
        "buyIn" -> Checkpoint.BuyIn
        "street" -> Checkpoint.Street
        "beforeCutscene" -> Checkpoint.BeforeCutscene(o.getString("cutsceneId"))
        "atTable" -> Checkpoint.AtTable(decodeTable(o.getJSONObject("table")))
        else -> throw IllegalArgumentException("Unknown checkpoint type: $type")
    }

    private fun encodeTable(t: TableState) = JSONObject().apply {
        put("phase", t.phase.name)
        put("activeSeat", t.activeSeat)
        put("activeHandIndex", t.activeHandIndex)
        put("round", t.round)
        put("house", t.house)
        put("grabbed", t.grabbed)
        put("dealerCards", encodeCards(t.dealerCards))
        put("shoe", encodeCards(t.shoe.snapshot()))
        put("scriptCursor", JSONObject().apply { t.scriptCursor.forEach { (seat, used) -> put(seat.toString(), used) } })
        put("seats", JSONArray().apply { t.seats.forEach { put(encodeSeat(it)) } })
    }

    private fun decodeTable(o: JSONObject): TableState {
        val seatsJson = o.getJSONArray("seats")
        val cursor = o.getJSONObject("scriptCursor")
        return TableState(
            seats = List(seatsJson.length()) { decodeSeat(seatsJson.getJSONObject(it)) },
            shoe = Shoe.of(decodeCards(o.getJSONArray("shoe"))),
            house = o.getLong("house"),
            dealerCards = decodeCards(o.getJSONArray("dealerCards")),
            phase = TablePhase.valueOf(o.getString("phase")),
            activeSeat = o.getInt("activeSeat"),
            activeHandIndex = o.getInt("activeHandIndex"),
            round = o.getInt("round"),
            scriptCursor = cursor.keys().asSequence().associate { it.toInt() to cursor.getInt(it) },
            grabbed = o.getBoolean("grabbed")
        )
    }

    private fun encodeSeat(s: Seat) = JSONObject().apply {
        put("kind", s.kind.name)
        put("stack", s.stack)
        put("betPercent", s.style.betPercent)
        put("hands", JSONArray().apply {
            s.hands.forEach { hand ->
                put(JSONObject().apply {
                    put("cards", encodeCards(hand.cards))
                    put("bet", hand.bet)
                    put("status", hand.status.name)
                    put("fromSplit", hand.fromSplit)
                    hand.result?.let { put("result", it.name) }
                    put("paid", hand.paid)
                })
            }
        })
    }

    private fun decodeSeat(o: JSONObject): Seat {
        val handsJson = o.getJSONArray("hands")
        return Seat(
            kind = SeatKind.valueOf(o.getString("kind")),
            stack = o.getLong("stack"),
            hands = List(handsJson.length()) { i ->
                val hand = handsJson.getJSONObject(i)
                TableHand(
                    cards = decodeCards(hand.getJSONArray("cards")),
                    bet = hand.getLong("bet"),
                    status = HandStatus.valueOf(hand.getString("status")),
                    fromSplit = hand.getBoolean("fromSplit"),
                    result = if (hand.has("result")) RoundResult.valueOf(hand.getString("result")) else null,
                    paid = hand.getLong("paid")
                )
            },
            style = StyleProfile(o.getInt("betPercent"))
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
