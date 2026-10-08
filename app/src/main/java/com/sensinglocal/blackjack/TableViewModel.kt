package com.sensinglocal.blackjack

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sensinglocal.blackjack.game.table.SeatKind
import com.sensinglocal.blackjack.game.table.TableEngine
import com.sensinglocal.blackjack.game.table.TableEvent
import com.sensinglocal.blackjack.game.table.TablePhase
import com.sensinglocal.blackjack.game.table.TableState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pause between one AI seat's action and the next, so you can watch each one play. */
private const val AI_STEP_MS = 700L

/**
 * Before the first AI action after a deal: the ten opening cards fly out one by one (about 1.5s),
 * then the ring turns to the first seat (about 0.4s), and only then does that seat act.
 */
private const val AFTER_DEAL_MS = 2300L

/**
 * Runs a [TableEngine] for the table screen. The engine's `step()` plays exactly one AI action, so
 * pacing is just a loop with a delay: [state] is always a real engine state and the UI never has to
 * replay anything. While AI seats are playing [aiPlaying] is true (the screen hides the controls)
 * and [skipAi] finishes the remaining AI turns at once.
 */
class TableViewModel : ViewModel() {
    private val config = DemoTable.config
    private var engine = TableEngine(config)

    var state: TableState by mutableStateOf(engine.state)
        private set

    /** What the most recent engine call reported — for effects and, later, speech bubbles. */
    var lastEvents: List<TableEvent> by mutableStateOf(emptyList())
        private set

    var aiPlaying by mutableStateOf(false)
        private set

    private var skip = false
    private var aiJob: Job? = null

    fun newTable() {
        aiJob?.cancel()
        aiPlaying = false
        engine = TableEngine(config)
        publish(emptyList())
    }

    fun placeBet(amount: Long) = act(AFTER_DEAL_MS) { engine.placeBet(amount) }
    fun hit() = act { engine.hit() }
    fun stand() = act { engine.stand() }
    fun doubleDown() = act { engine.doubleDown() }
    fun split() = act { engine.split() }

    fun nextRound() {
        if (aiPlaying) return
        engine.startNextRound()
        publish(emptyList())
    }

    fun skipAi() {
        skip = true
    }

    private fun act(delayBeforeAiMs: Long = AI_STEP_MS, action: () -> List<TableEvent>) {
        if (aiPlaying) return
        publish(action())
        if (isAiTurn()) playAiTurns(delayBeforeAiMs)
    }

    private fun isAiTurn(): Boolean {
        val s = engine.state
        return s.phase == TablePhase.SEAT_TURN && s.seats[s.activeSeat].kind == SeatKind.AI
    }

    private fun playAiTurns(firstDelayMs: Long) {
        aiPlaying = true
        skip = false
        aiJob = viewModelScope.launch {
            var wait = firstDelayMs
            while (true) {
                if (!skip) delay(wait)
                val events = engine.step()
                if (events.isEmpty()) break
                publish(events)
                wait = AI_STEP_MS
            }
            aiPlaying = false
        }
    }

    private fun publish(events: List<TableEvent>) {
        state = engine.state
        lastEvents = events
    }
}
