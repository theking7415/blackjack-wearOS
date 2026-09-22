package com.sensinglocal.blackjack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.Typography
import com.sensinglocal.blackjack.game.BlackjackState
import com.sensinglocal.blackjack.game.PlayerHand
import com.sensinglocal.blackjack.game.RoundPhase
import com.sensinglocal.blackjack.game.RoundResult
import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.random.Random

private enum class AppScreen { SPLASH, MENU, GAME }

class MainActivity : ComponentActivity() {
    private val viewModel: BlackjackViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // VT323 (pixel-art monospace, Google Fonts) set as the default face for every
            // text style, so all Compose Text() calls app-wide pick it up automatically.
            MaterialTheme(typography = Typography(defaultFontFamily = VT323)) {
                var screen by remember { mutableStateOf(AppScreen.SPLASH) }
                Box(modifier = Modifier.fillMaxSize()) {
                    when (screen) {
                        // The menu is composed (and its icon/title bitmaps baked) underneath
                        // the splash from the very start, so it's already sitting there ready
                        // to be revealed as the splash wipes away — no black gap while it
                        // waits for the splash to finish before it even starts building.
                        AppScreen.SPLASH, AppScreen.MENU ->
                            MainMenuScreen(onPlay = { screen = AppScreen.GAME })
                        AppScreen.GAME -> SwipeToDismissBox(
                            onDismissed = { screen = AppScreen.MENU }
                        ) { isBackground ->
                            if (isBackground) {
                                MainMenuScreen(onPlay = {})
                            } else {
                                BlackjackScreen(
                                    state = viewModel.state,
                                    onBet = viewModel::placeBet,
                                    onHit = viewModel::hit,
                                    onStand = viewModel::stand,
                                    onDoubleDown = viewModel::doubleDown,
                                    onSplit = viewModel::split,
                                    onNextRound = viewModel::nextRound,
                                    onResetBankroll = viewModel::resetBankroll
                                )
                            }
                        }
                    }
                    if (screen == AppScreen.SPLASH) {
                        SplashScreen(onFinished = { screen = AppScreen.MENU })
                    }
                }
            }
        }
    }
}

private const val WIPE_COLUMNS = 18

@Composable
private fun SplashScreen(onFinished: () -> Unit) {
    val wipeProgress = remember { Animatable(0f) }
    // Each column drains upward at its own delay/curve so the boundary looks like an uneven,
    // dripping edge (jagged peaks where red lingers) rather than a clean wipe. The exponent
    // (rather than a raw speed multiplier) controls how eagerly a column catches up after its
    // delay — this guarantees every column's local progress reaches exactly 1 when the overall
    // animation reaches t=1, so the screen is always fully drained (never cut off mid-drip)
    // right as onFinished fires.
    val columnDelays = remember { List(WIPE_COLUMNS + 1) { Random.nextFloat() * 0.35f } }
    val columnExponents = remember { List(WIPE_COLUMNS + 1) { 0.6f + Random.nextFloat() * 1.0f } }

    LaunchedEffect(Unit) {
        delay(450)
        wipeProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1100, easing = LinearOutSlowInEasing)
        )
        onFinished()
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            // The menu now sits (and is clickable) underneath this splash overlay the whole
            // time, so swallow touches here to stop the PLAY button being tapped through
            // before the wipe animation has actually revealed it.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .graphicsLayer(alpha = 0.99f)
    ) {
        val w = size.width
        val h = size.height
        // Same dark red as the menu background (MenuRedDark), so the wipe reveals a screen
        // that already matches — no color jump at the moment the splash disappears.
        drawRect(color = MenuRedDark)

        val t = wipeProgress.value
        val points = (0..WIPE_COLUMNS).map { i ->
            val x = w * i / WIPE_COLUMNS
            val delay = columnDelays[i]
            val raw = if (t <= delay) 0f else ((t - delay) / (1f - delay)).coerceIn(0f, 1f)
            val local = raw.pow(columnExponents[i])
            Offset(x, h * (1f - local))
        }

        val clearedPath = Path().apply {
            moveTo(0f, h)
            lineTo(points.first().x, points.first().y)
            for (i in 0 until points.size - 1) {
                val current = points[i]
                val next = points[i + 1]
                val midX = (current.x + next.x) / 2f
                val midY = (current.y + next.y) / 2f
                quadraticBezierTo(current.x, current.y, midX, midY)
            }
            lineTo(points.last().x, points.last().y)
            lineTo(w, h)
            close()
        }

        drawPath(path = clearedPath, color = Color.Transparent, blendMode = BlendMode.Clear)
    }
}

@Composable
fun BlackjackScreen(
    state: BlackjackState,
    onBet: (Int) -> Unit,
    onHit: () -> Unit,
    onStand: () -> Unit,
    onDoubleDown: () -> Unit,
    onSplit: () -> Unit,
    onNextRound: () -> Unit,
    onResetBankroll: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Green felt blackjack table — radial gradient reads as a simple table vignette
            // on the round display without needing a full pixel-tiled texture.
            .background(Brush.radialGradient(listOf(TableGreen, TableGreenDark))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            FeltText("Bankroll: ₹${state.bankroll}", fontSize = 17.sp)

            DealerHandRow(state = state)

            when (state.phase) {
                RoundPhase.BETTING -> BettingControls(
                    bankroll = state.bankroll,
                    onBet = onBet,
                    onResetBankroll = onResetBankroll
                )
                RoundPhase.PLAYER_TURN -> PlayerControls(
                    state = state,
                    onHit = onHit,
                    onStand = onStand,
                    onDoubleDown = onDoubleDown,
                    onSplit = onSplit
                )
                RoundPhase.DEALER_TURN -> FeltText("Dealer playing…")
                RoundPhase.ROUND_OVER -> ResultControls(hands = state.hands, onNextRound = onNextRound)
            }

            PlayerHandsColumn(state = state)
        }
    }
}

/** Gold text on the green felt — readable against the table background. */
@Composable
private fun FeltText(text: String, fontSize: androidx.compose.ui.unit.TextUnit = 16.sp) {
    Text(text = text, color = MenuGold, fontWeight = FontWeight.Bold, fontSize = fontSize)
}

@Composable
private fun DealerHandRow(state: BlackjackState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FeltText("Dealer")
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            state.dealerCards.forEachIndexed { index, card ->
                val faceDown = index == 1 && !state.dealerHoleCardRevealed
                PixelCard(card = card, faceDown = faceDown)
            }
        }
        if (state.dealerHoleCardRevealed && state.dealerCards.isNotEmpty()) {
            FeltText("(${state.dealerTotal})", fontSize = 14.sp)
        }
    }
}

@Composable
private fun PlayerHandsColumn(state: BlackjackState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.hands.forEachIndexed { index, hand ->
            val label = if (state.hands.size > 1) "Hand ${index + 1}" else "You"
            val marker = if (state.phase == RoundPhase.PLAYER_TURN && state.hands.size > 1 &&
                index == state.activeHandIndex
            ) "▶ " else ""
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FeltText("$marker$label (${hand.total})", fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    hand.cards.forEach { card -> PixelCard(card = card) }
                }
            }
        }
    }
}

@Composable
private fun BettingControls(bankroll: Int, onBet: (Int) -> Unit, onResetBankroll: () -> Unit) {
    if (bankroll <= 0) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FeltText("You're broke!")
            PixelButton(text = "Reset Bankroll", onClick = onResetBankroll, width = 132.dp, height = 38.dp, fontSize = 13.sp)
        }
        return
    }
    val quickBets = listOf(10, 25, 50).filter { it <= bankroll }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        quickBets.forEach { amount ->
            PixelButton(text = "₹$amount", onClick = { onBet(amount) }, width = 68.dp, height = 38.dp, fontSize = 15.sp)
        }
    }
}

@Composable
private fun PlayerControls(
    state: BlackjackState,
    onHit: () -> Unit,
    onStand: () -> Unit,
    onDoubleDown: () -> Unit,
    onSplit: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton(text = "Hit", onClick = onHit, width = 72.dp, height = 38.dp, fontSize = 15.sp)
            PixelButton(text = "Stand", onClick = onStand, width = 72.dp, height = 38.dp, fontSize = 15.sp)
        }
        if (state.canDoubleDown || state.canSplit) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.canDoubleDown) {
                    PixelButton(text = "Double", onClick = onDoubleDown, width = 74.dp, height = 38.dp, fontSize = 14.sp)
                }
                if (state.canSplit) {
                    PixelButton(text = "Split", onClick = onSplit, width = 74.dp, height = 38.dp, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun ResultControls(hands: List<PlayerHand>, onNextRound: () -> Unit) {
    fun messageFor(result: RoundResult?): String = when (result) {
        RoundResult.PLAYER_BLACKJACK -> "Blackjack! You win"
        RoundResult.PLAYER_WIN -> "You win"
        RoundResult.DEALER_BUST -> "Dealer busts — you win"
        RoundResult.PUSH -> "Push"
        RoundResult.DEALER_WIN -> "Dealer wins"
        RoundResult.PLAYER_BUST -> "Bust — you lose"
        null -> ""
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hands.size > 1) {
            hands.forEachIndexed { index, hand ->
                FeltText("Hand ${index + 1}: ${messageFor(hand.result)}", fontSize = 14.sp)
            }
        } else {
            FeltText(messageFor(hands.firstOrNull()?.result))
        }
        PixelButton(text = "Next round", onClick = onNextRound, width = 122.dp, height = 38.dp, fontSize = 14.sp)
    }
}
