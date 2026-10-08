package com.sensinglocal.blackjack

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.table.TableEvent
import com.sensinglocal.blackjack.game.table.TableState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val FLIGHT_MS = 420
private const val DEAL_STAGGER_MS = 110L
private const val DEALER_DRAW_FIRST_MS = 300L
private const val DEALER_DRAW_STAGGER_MS = 350L

/** Frames a flight will wait for its landing slot to be measured before giving up. */
private const val MAX_FRAMES_WAITING_FOR_SLOT = 90

/** One card slot on the table: [row] is "dealer" or "seat-<seat>-hand-<hand>"; [index] is its place in that hand. */
internal data class TableSlotKey(val row: String, val index: Int)

internal fun seatRowKey(seat: Int, hand: Int) = "seat-$seat-hand-$hand"
internal const val DEALER_ROW = "dealer"

/** A measured rectangle in root coordinates, in pixels. */
internal data class SlotRect(val topLeft: Offset, val size: Size)

internal class TableFlight(
    val id: Long,
    val key: TableSlotKey,
    val card: Card,
    val faceDown: Boolean,
    val startDelayMs: Long
) {
    val progress = Animatable(0f)

    /** False while waiting out the start delay and for the landing slot to be measured. */
    var started by mutableStateOf(false)
}

/**
 * Turns the engine's [TableEvent]s into card flights from the deck pile to each card's slot on the
 * ring. Every card slot reports its own on-screen rectangle (position *and* size, so mini seats and
 * overlapping hands land exactly right and the card scales to fit on the way), and a flying card's
 * own slot is drawn as an empty placeholder until it lands. Labels and results wait for
 * [flights] to be empty so they never run ahead of the cards.
 */
internal class TableFlightController {
    val slots = mutableMapOf<TableSlotKey, SlotRect>()
    var deck: SlotRect? = null
    val flights = mutableStateListOf<TableFlight>()
    private var nextId = 0L

    fun isFlying(key: TableSlotKey) = flights.any { it.key == key }
    fun isFlyingIn(row: String) = flights.any { it.key.row == row }

    /**
     * Registers a flight for every card [events] says was dealt. Must run during composition,
     * before the seats compose (see the `remember(events)` in TableScreen), so the new card is
     * never drawn in its slot for a frame before its flight takes over.
     *
     * A card's slot is the end of its hand in [state]: if a batch dealt k cards to one hand, they
     * are that hand's last k cards (a split's two new cards are each hand's second card).
     */
    fun onEvents(events: List<TableEvent>, state: TableState) {
        val perSeatHand = events.filterIsInstance<TableEvent.SeatCardDealt>()
            .groupingBy { it.seat to it.hand }.eachCount()
        val dealerTotal = events.count { it is TableEvent.DealerCardDealt }
        val seenSeatHand = mutableMapOf<Pair<Int, Int>, Int>()
        var seenDealer = 0
        var dealIndex = 0
        var drawIndex = 0
        var holeRevealed = false

        for (event in events) {
            when (event) {
                is TableEvent.HoleCardRevealed -> holeRevealed = true
                is TableEvent.SeatCardDealt -> {
                    val id = event.seat to event.hand
                    val k = seenSeatHand[id] ?: 0
                    seenSeatHand[id] = k + 1
                    val size = state.seats.getOrNull(event.seat)?.hands?.getOrNull(event.hand)?.cards?.size ?: continue
                    val index = size - perSeatHand.getValue(id) + k
                    if (index < 0) continue
                    add(TableSlotKey(seatRowKey(event.seat, event.hand), index), event.card, false, dealIndex++ * DEAL_STAGGER_MS)
                }
                is TableEvent.DealerCardDealt -> {
                    val k = seenDealer++
                    val index = state.dealerCards.size - dealerTotal + k
                    if (index < 0) continue
                    // Cards the dealer draws once the hole card is turned over come one at a time;
                    // the up-card and hole card belong to the interleaved opening deal.
                    val startDelay = if (holeRevealed) {
                        DEALER_DRAW_FIRST_MS + drawIndex++ * DEALER_DRAW_STAGGER_MS
                    } else {
                        dealIndex++ * DEAL_STAGGER_MS
                    }
                    add(TableSlotKey(DEALER_ROW, index), event.card, event.faceDown, startDelay)
                }
                else -> Unit
            }
        }
    }

    private fun add(key: TableSlotKey, card: Card, faceDown: Boolean, delayMs: Long) {
        // Forget any measurement left over from an earlier round; the flight waits for a fresh one.
        slots.remove(key)
        flights.add(TableFlight(nextId++, key, card, faceDown, delayMs))
    }
}

/** A short face-down pile where dealt cards come from. Invisible (but measured) until a hand is dealt. */
@Composable
internal fun TableDeckPile(controller: TableFlightController, visible: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val cardSize = with(density) {
        Size(PixelCardWidth.toPx() * SeatRing.DECK_SCALE, PixelCardHeight.toPx() * SeatRing.DECK_SCALE)
    }
    Box(
        modifier = modifier
            .size(PixelCardWidth * SeatRing.DECK_SCALE + 4.dp, PixelCardHeight * SeatRing.DECK_SCALE + 4.dp)
            .alpha(if (visible) 1f else 0f)
            .onGloballyPositioned { controller.deck = SlotRect(it.positionInRoot(), cardSize) }
    ) {
        for (i in 2 downTo 0) {
            PixelCard(
                card = null,
                faceDown = true,
                scale = SeatRing.DECK_SCALE,
                modifier = Modifier.offset(x = (i * 2).dp, y = (i * 2).dp)
            )
        }
    }
}

/** Draws every card in flight on top of the table, flipping face-up and resizing as it travels. */
@Composable
internal fun TableFlightOverlay(controller: TableFlightController, modifier: Modifier = Modifier) {
    var overlayRoot by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val fullWidthPx = with(density) { PixelCardWidth.toPx() }
    Box(modifier = modifier.onGloballyPositioned { overlayRoot = it.positionInRoot() }) {
        controller.flights.forEach { flight ->
            key(flight.id) {
                LaunchedEffect(flight.id) {
                    if (flight.startDelayMs > 0) delay(flight.startDelayMs)
                    var waited = 0
                    while (controller.slots[flight.key] == null && waited < MAX_FRAMES_WAITING_FOR_SLOT) {
                        withFrameNanos {}
                        waited++
                    }
                    if (controller.slots[flight.key] == null) {
                        // The slot never appeared (the table moved on); don't hold up the ring or the results.
                        controller.flights.remove(flight)
                        return@LaunchedEffect
                    }
                    flight.started = true
                    flight.progress.animateTo(1f, tween(FLIGHT_MS, easing = FastOutSlowInEasing))
                    controller.flights.remove(flight)
                }
                val deck = controller.deck
                // Read every frame (not captured once) so a landing slot that is still settling is tracked.
                val end = controller.slots[flight.key]
                if (flight.started && deck != null && end != null) {
                    val t = flight.progress.value
                    val topLeft = lerp(deck.topLeft, end.topLeft, t) - overlayRoot
                    val startScale = deck.size.width / fullWidthPx
                    val endScale = end.size.width / fullWidthPx
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                            .graphicsLayer {
                                // Up to edge-on and back (not a full spin) so the second half isn't mirrored;
                                // the face swaps at the midpoint.
                                rotationY = if (t < 0.5f) t * 180f else (1f - t) * 180f
                                cameraDistance = 16f * density.density
                            }
                    ) {
                        PixelCard(
                            card = flight.card,
                            faceDown = if (t < 0.5f) true else flight.faceDown,
                            scale = startScale + (endScale - startScale) * t
                        )
                    }
                }
            }
        }
    }
}
