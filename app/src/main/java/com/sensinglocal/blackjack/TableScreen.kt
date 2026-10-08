package com.sensinglocal.blackjack

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import androidx.wear.compose.material.Text
import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.HandStatus
import com.sensinglocal.blackjack.game.isBust
import com.sensinglocal.blackjack.game.table.Seat
import com.sensinglocal.blackjack.game.table.SeatKind
import com.sensinglocal.blackjack.game.table.TableEvent
import com.sensinglocal.blackjack.game.table.TableHand
import com.sensinglocal.blackjack.game.table.TablePhase
import com.sensinglocal.blackjack.game.table.TableState
import kotlinx.coroutines.flow.first

/** How long the ring takes to turn to the next seat. */
private const val ROTATE_MS = 380

/**
 * The multiplayer table: the dealer in the middle, every seat on a ring around them (see
 * [SeatRing]). The ring turns so the seat whose turn it is comes round to the bottom at full size
 * while everyone else is shown as mini cards. Between hands and while betting the ring faces you.
 * All geometry is a fraction of the screen side, tuned in [SeatRing].
 *
 * While AI seats play ([aiPlaying]) the controls are hidden and tapping anywhere calls [onSkipAi].
 *
 * [events] is what the engine just reported; cards it dealt fly out of the deck pile to their
 * seats (see [TableFlightController]). The ring holds still while cards are in the air, and labels,
 * results and buttons wait for them to land.
 */
@Composable
fun TableScreen(
    state: TableState,
    seatNames: List<String>,
    minBet: Long,
    aiPlaying: Boolean,
    events: List<TableEvent>,
    onBet: (Long) -> Unit,
    onHit: () -> Unit,
    onStand: () -> Unit,
    onDoubleDown: () -> Unit,
    onSplit: () -> Unit,
    onNextRound: () -> Unit,
    onSkipAi: () -> Unit,
    onQuitToMenu: () -> Unit
) {
    var pauseOpen by remember { mutableStateOf(false) }
    val tapSource = remember { MutableInteractionSource() }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(TableGreen, TableGreenDark)))
            .clickable(interactionSource = tapSource, indication = null, enabled = aiPlaying) { onSkipAi() }
    ) {
        val side = minOf(maxWidth, maxHeight)
        val seatCount = state.seats.size

        // The seat at the bottom: whoever's turn it is, else you. Animated as a (fractional) seat
        // index; unwrapTarget picks the short way round so the ring never spins backwards.
        val controller = remember { TableFlightController() }
        // A side effect of composition (not a LaunchedEffect) on purpose: the flights must exist before
        // the seats below compose, or a newly dealt card would show in its slot for one frame first.
        remember(events) { controller.onEvents(events, state) }
        LaunchedEffect(state.phase) {
            if (state.phase == TablePhase.BETTING) controller.flights.clear()
        }
        val cardsInAir = controller.flights.isNotEmpty()
        // Results only show once every card has landed.
        val settled = state.phase == TablePhase.ROUND_OVER && !cardsInAir

        val frontTarget = if (state.phase == TablePhase.SEAT_TURN) state.activeSeat else state.humanSeatIndex
        val front = remember { Animatable(frontTarget.toFloat()) }
        LaunchedEffect(frontTarget, seatCount) {
            // Don't swing the ring away while cards are still flying to where it is now.
            snapshotFlow { controller.flights.isEmpty() }.first { it }
            front.animateTo(
                SeatRing.unwrapTarget(front.value, frontTarget, seatCount),
                tween(ROTATE_MS, easing = FastOutSlowInEasing)
            )
        }

        TableDeckPile(
            controller = controller,
            visible = state.dealerCards.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = side * SeatRing.DECK_X, y = side * SeatRing.DECK_Y)
                .zIndex(-1f)
        )

        if (state.dealerCards.isNotEmpty()) {
            DealerBlock(
                state = state,
                controller = controller,
                side = side,
                modifier = Modifier.align(Alignment.Center).offset(y = side * SeatRing.CENTER_Y)
            )
        }

        state.seats.forEachIndexed { index, seat ->
            // Your own seat has nothing to show while betting; the bet controls carry your stack.
            val hidden = seat.kind == SeatKind.EMPTY ||
                (seat.kind == SeatKind.HUMAN && seat.hands.isEmpty())
            if (!hidden) {
                val slot = SeatRing.slot(index, front.value, seatCount)
                SeatView(
                    name = seatNames.getOrElse(index) { "Seat ${index + 1}" },
                    seat = seat,
                    seatIndex = index,
                    state = state,
                    controller = controller,
                    settled = settled,
                    slot = slot,
                    side = side,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = side * slot.x, y = side * slot.y)
                        .zIndex(slot.closeness)
                )
            }
        }

        val tableOver = state.houseBroke || state.humanSeat.stack <= 0L
        val humanTurn = state.phase == TablePhase.SEAT_TURN && !aiPlaying && !cardsInAir &&
            state.seats[state.activeSeat].kind == SeatKind.HUMAN
        when {
            state.phase == TablePhase.BETTING -> Box(
                modifier = Modifier.align(Alignment.Center).offset(y = side * 0.04f).zIndex(2f)
            ) {
                if (tableOver) {
                    TableOverControls(houseBroke = state.houseBroke, onLeave = onQuitToMenu)
                } else {
                    TableBetControls(stack = state.humanSeat.stack, minBet = minBet, onBet = onBet)
                }
            }
            humanTurn -> Box(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = side * 0.075f).zIndex(2f)
            ) {
                PlayControls(state, onHit, onStand, onDoubleDown, onSplit)
            }
            settled && !aiPlaying -> Box(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = side * 0.075f).zIndex(2f)
            ) {
                if (tableOver) {
                    PixelButton(text = "Leave table", onClick = onQuitToMenu, width = 118.dp, height = 30.dp, fontSize = 13.sp)
                } else {
                    PixelButton(text = "Next round", onClick = onNextRound, width = 118.dp, height = 30.dp, fontSize = 13.sp)
                }
            }
        }

        TableFlightOverlay(controller = controller, modifier = Modifier.fillMaxSize().zIndex(1.5f))

        PauseButton(
            onClick = { pauseOpen = true },
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = side * 0.31f, y = side * 0.23f)
                .zIndex(3f)
        )

        if (pauseOpen) {
            PauseOverlay(onResume = { pauseOpen = false }, onQuitToMenu = onQuitToMenu)
        }
    }
}

// --- the dealer ----------------------------------------------------------------------------

@Composable
private fun DealerBlock(state: TableState, controller: TableFlightController, side: Dp, modifier: Modifier = Modifier) {
    val revealed = state.dealerHoleCardRevealed
    // The total waits until the dealer's last drawn card has landed, so it never spoils the draw.
    val showTotal = revealed && !controller.isFlyingIn(DEALER_ROW)
    val label = when {
        !showTotal -> "Dealer"
        isBust(state.dealerCards) -> "Dealer BUST"
        else -> "Dealer ${state.dealerTotal}"
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            text = label,
            color = if (showTotal && isBust(state.dealerCards)) ResultLoseRed else MenuGold,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
        // The dealer has no hand status, so a bust (once it's visible) stands in for one.
        HandStatusEffect(
            status = if (showTotal && isBust(state.dealerCards)) HandStatus.BUST else HandStatus.PLAYING,
            stampSize = 11.sp
        ) {
            CardRow(
                cards = state.dealerCards,
                hiddenIndex = if (revealed) -1 else 1,
                scale = SeatRing.DEALER_SCALE,
                maxWidth = side * 0.4f,
                rowKey = DEALER_ROW,
                controller = controller
            )
        }
    }
}

// --- one seat ------------------------------------------------------------------------------

@Composable
private fun SeatView(
    name: String,
    seat: Seat,
    seatIndex: Int,
    state: TableState,
    controller: TableFlightController,
    settled: Boolean,
    slot: SeatRing.Slot,
    side: Dp,
    modifier: Modifier = Modifier
) {
    val closeness = slot.closeness
    val scale = slot.scale
    val isFront = closeness > 0.5f
    val isActive = state.phase == TablePhase.SEAT_TURN && state.activeSeat == seatIndex
    val nameSize = (10f + 3f * closeness).sp
    val infoSize = (11f + 3f * closeness).sp
    val maxWidth = side * (0.22f + 0.28f * closeness)
    val nameColor = if (isActive) MenuGold else Color.White

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        if (seat.hands.isEmpty()) {
            // Between hands: just who they are and what they have.
            Text(text = name, color = nameColor, fontWeight = FontWeight.Bold, fontSize = nameSize)
            Text(text = compactAmount(seat.stack), color = MenuGold, fontSize = infoSize)
        } else {
            // Labels describe only the cards that have landed, and results wait for the whole table.
            val shownHands = seat.hands.mapIndexed { handIndex, hand ->
                shownHand(hand, seatRowKey(seatIndex, handIndex), controller)
            }
            val labels = shownHands.map { handLabel(it, settled) }
            // After a split, which of the hands is being played right now (-1 if not applicable).
            val playingHand = if (isActive && seat.hands.size > 1) state.activeHandIndex else -1
            if (isFront) {
                val text = buildString {
                    if (isActive) append("▶ ")
                    append(name)
                    // The hand being played is bracketed: "[16]/12".
                    val totals = labels.mapIndexed { i, label ->
                        if (i == playingHand && label.first.isNotEmpty()) "[${label.first}]" else label.first
                    }.joinToString("/").trim('/')
                    if (totals.isNotEmpty()) append(' ').append(totals)
                    append(" · ")
                    append(compactAmount(seat.stack))
                }
                Text(
                    text = text,
                    color = if (labels.size == 1 && labels[0].second != Color.White) labels[0].second else nameColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = nameSize
                )
            } else {
                Text(text = name, color = nameColor, fontWeight = FontWeight.Bold, fontSize = nameSize)
            }
            val handGap = 6.dp
            val handMax = (maxWidth - handGap * (seat.hands.size - 1)) / seat.hands.size
            Row(horizontalArrangement = Arrangement.spacedBy(handGap), verticalAlignment = Alignment.Top) {
                seat.hands.forEachIndexed { handIndex, hand ->
                    // The hand you're not playing is dimmed, and the one you are gets a gold bar under it.
                    val dimmed = playingHand >= 0 && handIndex != playingHand
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.alpha(if (dimmed) 0.45f else 1f)
                    ) {
                        // Bust flash/shake/stamp and the stand pulse fire when the status the player can
                        // see changes (shownHand holds it back until the cards have landed). Only your
                        // own hands buzz; the stamp shrinks with the seat.
                        HandStatusEffect(
                            status = shownHands[handIndex].status,
                            stampSize = (8f + 6f * closeness).sp,
                            haptics = seat.kind == SeatKind.HUMAN
                        ) {
                            CardRow(
                                cards = hand.cards,
                                hiddenIndex = -1,
                                scale = scale,
                                maxWidth = handMax,
                                rowKey = seatRowKey(seatIndex, handIndex),
                                controller = controller,
                                highlight = handIndex == playingHand
                            )
                        }
                        if (!isFront) {
                            Text(
                                text = labels[handIndex].first,
                                color = labels[handIndex].second,
                                fontWeight = FontWeight.Bold,
                                fontSize = infoSize
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What to print for a hand: its total while playing, BUST/21!, or the net win/loss once settled. */
private fun handLabel(hand: TableHand, roundOver: Boolean): Pair<String, Color> {
    if (roundOver && hand.result != null) {
        val net = hand.paid - hand.bet
        return when {
            net > 0 -> "+${compactAmount(net)}" to ResultWinGreen
            net < 0 -> "-${compactAmount(-net)}" to ResultLoseRed
            else -> "PUSH" to MenuGold
        }
    }
    return when {
        hand.cards.isEmpty() -> "" to Color.White // every card still in the air
        hand.status == HandStatus.BUST -> "BUST" to ResultLoseRed
        hand.isNaturalBlackjack -> "21!" to MenuGold
        else -> hand.total.toString() to Color.White
    }
}

/**
 * The hand as the player should currently see it: cards still in the air don't count yet, and while
 * any are, its bust/stand status and result are held back too.
 */
private fun shownHand(hand: TableHand, rowKey: String, controller: TableFlightController): TableHand {
    if (!controller.isFlyingIn(rowKey)) return hand
    val landed = hand.cards.filterIndexed { index, _ -> !controller.isFlying(TableSlotKey(rowKey, index)) }
    return hand.copy(cards = landed, status = HandStatus.PLAYING, result = null)
}

/**
 * A row of cards that overlaps (like a fanned hand) when it would otherwise exceed [maxWidth].
 * Every slot reports its own rectangle to the [controller], and a card that is still flying in is
 * an empty placeholder of the same size until it lands.
 */
@Composable
private fun CardRow(
    cards: List<Card>,
    hiddenIndex: Int,
    scale: Float,
    maxWidth: Dp,
    rowKey: String,
    controller: TableFlightController,
    highlight: Boolean = false
) {
    val spacing = cardSpacing(
        count = cards.size,
        cardWidth = PixelCardWidth.value * scale,
        maxWidth = maxWidth.value,
        normalSpacing = PixelCardSpacing.value * scale
    )
    // A thin gold bar just under the row marks the hand currently being played.
    val rowModifier = if (highlight) {
        Modifier.drawBehind {
            drawRect(
                color = MenuGold,
                topLeft = Offset(0f, size.height + 1.dp.toPx()),
                size = Size(size.width, 2.dp.toPx())
            )
        }
    } else {
        Modifier
    }
    Row(modifier = rowModifier, horizontalArrangement = Arrangement.spacedBy(spacing.dp)) {
        cards.forEachIndexed { index, card ->
            val key = TableSlotKey(rowKey, index)
            val measured = Modifier.onGloballyPositioned { coordinates ->
                controller.slots[key] = SlotRect(coordinates.positionInRoot(), coordinates.size.toSize())
            }
            if (controller.isFlying(key)) {
                Box(modifier = measured.size(PixelCardWidth * scale, PixelCardHeight * scale))
            } else {
                PixelCard(card = card, faceDown = index == hiddenIndex, modifier = measured, scale = scale)
            }
        }
    }
}

// --- controls ------------------------------------------------------------------------------

/**
 * One row under your hand. The bottom of a round screen is narrow, so with Double and Split both
 * available (any pair) the labels shorten to fit four buttons.
 */
@Composable
private fun PlayControls(
    state: TableState,
    onHit: () -> Unit,
    onStand: () -> Unit,
    onDoubleDown: () -> Unit,
    onSplit: () -> Unit
) {
    val canDouble = state.canDoubleDown
    val canSplit = state.canSplit
    val four = canDouble && canSplit
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        PixelButton(text = "Hit", onClick = onHit, width = if (four) 28.dp else 34.dp, height = 28.dp, fontSize = 11.sp)
        PixelButton(
            text = if (four) "Std" else "Stand", onClick = onStand,
            width = if (four) 28.dp else 42.dp, height = 28.dp, fontSize = 11.sp
        )
        if (canDouble) {
            PixelButton(text = "Dbl", onClick = onDoubleDown, width = if (four) 28.dp else 36.dp, height = 28.dp, fontSize = 11.sp)
        }
        if (canSplit) {
            PixelButton(
                text = if (four) "Spl" else "Split", onClick = onSplit,
                width = if (four) 28.dp else 36.dp, height = 28.dp, fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun TableBetControls(stack: Long, minBet: Long, onBet: (Long) -> Unit) {
    val lowest = minOf(minBet, stack)
    var bet by remember(stack, minBet) { mutableStateOf(minOf(maxOf(10L, lowest), stack)) }
    val step = if (stack >= 50) 5L else 1L
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CreditIcon(size = 16.dp)
            Text(text = bet.toString(), color = MenuGold, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Text(text = "of ${compactAmount(stack)}", color = Color(0xFFAAAAAA), fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RepeatingPixelButton(
                text = "-",
                onStep = { bet = (bet - step).coerceIn(lowest, stack) },
                size = 44.dp
            )
            RepeatingPixelButton(
                text = "+",
                onStep = { bet = (bet + step).coerceIn(lowest, stack) },
                size = 44.dp
            )
        }
        PixelButton(text = "Bet", onClick = { onBet(bet) }, width = 96.dp, height = 32.dp, fontSize = 14.sp)
    }
}

@Composable
private fun TableOverControls(houseBroke: Boolean, onLeave: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (houseBroke) "The house is broke!" else "Out of chips",
            color = MenuGold,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        PixelButton(text = "Leave table", onClick = onLeave, width = 120.dp, height = 34.dp, fontSize = 14.sp)
    }
}
