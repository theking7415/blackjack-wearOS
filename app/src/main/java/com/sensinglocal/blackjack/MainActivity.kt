package com.sensinglocal.blackjack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.sensinglocal.blackjack.game.BlackjackState
import com.sensinglocal.blackjack.game.RoundPhase
import com.sensinglocal.blackjack.game.RoundResult

class MainActivity : ComponentActivity() {
    private val viewModel: BlackjackViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                BlackjackScreen(
                    state = viewModel.state,
                    onBet = viewModel::placeBet,
                    onHit = viewModel::hit,
                    onStand = viewModel::stand,
                    onNextRound = viewModel::nextRound
                )
            }
        }
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
