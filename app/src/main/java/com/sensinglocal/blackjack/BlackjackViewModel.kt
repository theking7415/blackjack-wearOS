package com.sensinglocal.blackjack

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.sensinglocal.blackjack.game.BlackjackEngine
import com.sensinglocal.blackjack.game.BlackjackState

private const val STARTING_BANKROLL = 500
private const val PREFS_NAME = "blackjack_prefs"
private const val KEY_BANKROLL = "bankroll"

class BlackjackViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val engine = BlackjackEngine(
        startingBankroll = prefs.getInt(KEY_BANKROLL, STARTING_BANKROLL)
    )

    var state by mutableStateOf(engine.state)
        private set

    private fun syncState() {
        state = engine.state
        prefs.edit().putInt(KEY_BANKROLL, state.bankroll).apply()
    }

    fun placeBet(amount: Int) {
        engine.placeBet(amount)
        syncState()
    }

    fun hit() {
        engine.hit()
        syncState()
    }

    fun stand() {
        engine.stand()
        syncState()
    }

    fun doubleDown() {
        engine.doubleDown()
        syncState()
    }

    fun split() {
        engine.split()
        syncState()
    }

    fun nextRound() {
        engine.startNextRound()
        syncState()
    }

    fun resetBankroll() {
        engine.resetBankroll(STARTING_BANKROLL)
        syncState()
    }
}
