package com.sensinglocal.blackjack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.sensinglocal.blackjack.game.BlackjackState
import com.sensinglocal.blackjack.game.RoundPhase
import com.sensinglocal.blackjack.game.RoundResult
import kotlinx.coroutines.delay
import kotlin.random.Random

private val SplashRed = Color(0xFF5C0000)

class MainActivity : ComponentActivity() {
    private val viewModel: BlackjackViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var showSplash by remember { mutableStateOf(true) }
                Box(modifier = Modifier.fillMaxSize()) {
                    BlackjackScreen(
                        state = viewModel.state,
                        onBet = viewModel::placeBet,
                        onHit = viewModel::hit,
                        onStand = viewModel::stand,
                        onNextRound = viewModel::nextRound
                    )
                    if (showSplash) {
                        SplashScreen(onFinished = { showSplash = false })
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
    // Each column drains upward at its own speed/delay so the boundary looks like an
    // uneven, dripping edge (jagged peaks where red lingers) rather than a clean wipe.
    val columnSpeeds = remember { List(WIPE_COLUMNS + 1) { 0.7f + Random.nextFloat() * 0.6f } }
    val columnDelays = remember { List(WIPE_COLUMNS + 1) { Random.nextFloat() * 0.4f } }

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
            .graphicsLayer(alpha = 0.99f)
    ) {
        val w = size.width
        val h = size.height
        drawRect(color = SplashRed)

        val t = wipeProgress.value
        val points = (0..WIPE_COLUMNS).map { i ->
            val x = w * i / WIPE_COLUMNS
            val local = ((t - columnDelays[i]) * columnSpeeds[i]).coerceIn(0f, 1f)
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
    onNextRound: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Bankroll: ₹${state.bankroll}")

            HandRow(label = "Dealer", cards = state.dealerCards.joinToString(" ") { it.label }, total = state.dealerTotal, hideTotal = state.phase == RoundPhase.PLAYER_TURN)

            when (state.phase) {
                RoundPhase.BETTING -> BettingControls(bankroll = state.bankroll, onBet = onBet)
                RoundPhase.PLAYER_TURN -> PlayerControls(onHit = onHit, onStand = onStand)
                RoundPhase.DEALER_TURN -> Text("Dealer playing…")
                RoundPhase.ROUND_OVER -> ResultControls(result = state.result, onNextRound = onNextRound)
            }

            HandRow(label = "You", cards = state.playerCards.joinToString(" ") { it.label }, total = state.playerTotal, hideTotal = false)
        }
    }
}

@Composable
private fun HandRow(label: String, cards: String, total: Int, hideTotal: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$label: $cards")
        if (!hideTotal && cards.isNotEmpty()) {
            Text("($total)")
        }
    }
}

@Composable
private fun BettingControls(bankroll: Int, onBet: (Int) -> Unit) {
    val quickBets = listOf(10, 25, 50).filter { it <= bankroll }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        quickBets.forEach { amount ->
            Button(onClick = { onBet(amount) }) {
                Text("₹$amount")
            }
        }
    }
}

@Composable
private fun PlayerControls(onHit: () -> Unit, onStand: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onHit) { Text("Hit") }
        Button(onClick = onStand) { Text("Stand") }
    }
}

@Composable
private fun ResultControls(result: RoundResult?, onNextRound: () -> Unit) {
    val message = when (result) {
        RoundResult.PLAYER_BLACKJACK -> "Blackjack! You win"
        RoundResult.PLAYER_WIN -> "You win"
        RoundResult.DEALER_BUST -> "Dealer busts — you win"
        RoundResult.PUSH -> "Push"
        RoundResult.DEALER_WIN -> "Dealer wins"
        RoundResult.PLAYER_BUST -> "Bust — you lose"
        null -> ""
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message)
        Button(onClick = onNextRound) { Text("Next round") }
    }
}
