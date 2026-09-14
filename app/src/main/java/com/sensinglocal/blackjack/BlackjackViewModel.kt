package com.sensinglocal.blackjack

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.sensinglocal.blackjack.game.BlackjackEngine
import com.sensinglocal.blackjack.game.BlackjackState

private const val STARTING_BANKROLL = 500

class BlackjackViewModel : ViewModel() {
    private val engine = BlackjackEngine(startingBankroll = STARTING_BANKROLL)

    var state by mutableStateOf(engine.state)
        private set

    fun placeBet(amount: Int) {
        engine.placeBet(amount)
        state = engine.state
    }

    fun hit() {
        engine.hit()
        state = engine.state
    }

    fun stand() {
        engine.stand()
        state = engine.state
    }

    fun nextRound() {
        engine.startNextRound()
        state = engine.state
    }
}
