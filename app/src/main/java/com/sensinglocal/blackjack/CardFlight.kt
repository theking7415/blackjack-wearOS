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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.sensinglocal.blackjack.game.BlackjackState
import com.sensinglocal.blackjack.game.Card
import kotlin.math.roundToInt

/** Identifies one card slot: which hand row ("dealer", "hand-0", "hand-1", ...) and its index within that row. */
internal data class SlotKey(val row: String, val index: Int)

private const val DEAL_DURATION_MS = 420

/** One card currently animating from the deck to its resting slot. */
internal class FlyingCardUi(
    val id: Long,
    val key: SlotKey,
    val card: Card,
    val faceDown: Boolean,
    val start: Offset
) {
    // Null until the target row has been measured (it may have just been created this same
    // state update, e.g. a fresh round's opening deal or the two rows a split creates) — the
    // rendering side simply waits to appear until this resolves, a frame or two later at most.
    val end = mutableStateOf<Offset?>(null)
    val progress = Animatable(0f)
}

/**
 * Owns everything needed to animate cards flying from the dealer's deck to their slot: the
 * screen-space anchor for the deck and for each hand row (kept as a plain map, not Compose
 * state — these are written from `onGloballyPositioned` on every layout pass and only ever read
 * when a new flight starts, so they don't need to trigger recomposition on their own), and the
 * list of in-flight cards (which *is* Compose state, since the overlay and hand rows both need
 * to react to it).
 */
internal class FlightController {
    val anchors = mutableMapOf<String, Offset>()
    var deckAnchor: Offset = Offset.Zero
    val flights = mutableStateListOf<FlyingCardUi>()
    private var nextId = 0L

    fun isFlying(key: SlotKey): Boolean = flights.any { it.key == key }

    fun addFlight(key: SlotKey, card: Card, faceDown: Boolean) {
        flights.add(FlyingCardUi(id = nextId++, key = key, card = card, faceDown = faceDown, start = deckAnchor))
    }
}

/**
 * A small stack of face-down cards near the dealer, showing some depth (a real pile, not a
 * single flat back) — this is where dealt cards visually come from. [modifier] should carry an
 * `onGloballyPositioned` to record the pile's screen position as the flight system's start point.
 */
@Composable
internal fun DeckStack(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(PixelCardWidth + 6.dp, PixelCardHeight + 6.dp)) {
        // Drawn back-to-front so the top card of the pile sits at (0,0) — the same point flight
        // anchors are captured from.
        for (i in 3 downTo 0) {
            PixelCard(
                card = null,
                faceDown = true,
                modifier = Modifier.offset(x = (i * 2).dp, y = (i * 2).dp)
            )
        }
    }
}

/**
 * Diffs [before] against [after] and returns every (slot, card) pair that just got dealt to the
 * dealer's row. The dealer's card list only ever grows within a round (never restructured), so a
 * plain size comparison is always correct here.
 */
private fun computeDealerFlights(before: BlackjackState, after: BlackjackState): List<Pair<SlotKey, Card>> =
    (before.dealerCards.size until after.dealerCards.size).map { index ->
        SlotKey("dealer", index) to after.dealerCards[index]
    }

/**
 * Diffs [before] against [after] for every player hand row. A split needs its own rule: it turns
 * 1 hand into 2, and both resulting hands keep their *original* first card plus exactly one new
 * draw — a plain per-index size comparison can't tell the untouched original card in the new
 * second hand apart from the freshly dealt one, so isSplit is handled explicitly (index 1 in
 * each of the two hands is always the new card). Every other action (bet, hit, double) only ever
 * appends to an existing hand, where a size comparison is correct.
 */
private fun computeHandFlights(before: BlackjackState, after: BlackjackState): List<Pair<SlotKey, Card>> {
    val isSplit = before.hands.size == 1 && after.hands.size == 2
    if (isSplit) {
        return listOf(
            SlotKey("hand-0", 1) to after.hands[0].cards[1],
            SlotKey("hand-1", 1) to after.hands[1].cards[1]
        )
    }
    return after.hands.indices.flatMap { index ->
        val beforeSize = before.hands.getOrNull(index)?.cards?.size ?: 0
        val hand = after.hands[index]
        (beforeSize until hand.cards.size).map { cardIndex -> SlotKey("hand-$index", cardIndex) to hand.cards[cardIndex] }
    }
}

/**
 * Watches [state] for changes and registers a flight on the returned [FlightController] for
 * every card that just got dealt, comparing against the previously seen state. Uses a
 * `remember(state) { }` side effect (not `LaunchedEffect`) specifically so the new flights exist
 * *before* this composition pass reaches the hand rows further down — otherwise the newly dealt
 * card would render plainly in its row for one frame (since the state already contains it)
 * before the flight system had a chance to suppress it, causing a one-frame flicker/pop-in.
 */
@Composable
internal fun rememberFlightController(state: BlackjackState): FlightController {
    val controller = remember { FlightController() }
    val previous = remember { arrayOf(state) }
    remember(state) {
        val before = previous[0]
        val after = state
        if (before !== after) {
            for ((key, card) in computeDealerFlights(before, after)) {
                val faceDown = key.index == 1 && !after.dealerHoleCardRevealed
                controller.addFlight(key, card, faceDown)
            }
            for ((key, card) in computeHandFlights(before, after)) {
                controller.addFlight(key, card, faceDown = false)
            }
            previous[0] = after
        }
    }
    return controller
}

/** Full-screen overlay that renders every in-flight card on top of everything else. */
@Composable
internal fun CardFlightOverlay(controller: FlightController, modifier: Modifier = Modifier) {
    var overlayRootPosition by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val stridePx = with(density) { (PixelCardWidth + PixelCardSpacing).toPx() }
    Box(
        modifier = modifier.onGloballyPositioned { overlayRootPosition = it.positionInRoot() }
    ) {
        controller.flights.forEach { flying ->
            key(flying.id) {
                LaunchedEffect(flying.id) {
                    // Give the target row a frame or two to be measured — it may have just come
                    // into existence in this very state update (a fresh deal, or a split's two
                    // new rows), so its anchor isn't necessarily populated yet.
                    withFrameNanos {}
                    withFrameNanos {}
                    val rowAnchor = controller.anchors[flying.key.row] ?: controller.deckAnchor
                    flying.end.value = rowAnchor + Offset(flying.key.index * stridePx, 0f)
                    flying.progress.animateTo(1f, tween(durationMillis = DEAL_DURATION_MS, easing = FastOutSlowInEasing))
                    controller.flights.remove(flying)
                }
                val end = flying.end.value
                if (end != null) {
                    val t = flying.progress.value
                    val local = lerp(flying.start, end, t) - overlayRootPosition
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(local.x.roundToInt(), local.y.roundToInt()) }
                            .graphicsLayer {
                                // Rotate up to 90° (edge-on) and back to 0° rather than a
                                // continuous 0->180° spin, so the second half of the flip isn't
                                // mirrored — the face swap happens right at the 90° midpoint.
                                rotationY = if (t < 0.5f) t * 180f else (1f - t) * 180f
                                cameraDistance = 16f * density.density
                            }
                    ) {
                        PixelCard(card = flying.card, faceDown = if (t < 0.5f) true else flying.faceDown)
                    }
                }
            }
        }
    }
}
