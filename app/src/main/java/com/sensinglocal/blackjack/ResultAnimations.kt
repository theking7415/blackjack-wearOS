package com.sensinglocal.blackjack

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.sensinglocal.blackjack.game.HandStatus
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val BUST_FLASH_DURATION_MS = 380
private const val BUST_SHAKE_STEP_MS = 55
private val BUST_SHAKE_OFFSETS = listOf(-10f, 8f, -6f, 4f, 0f)
private const val STAND_PULSE_DURATION_MS = 420
private const val BANKROLL_COUNT_DURATION_MS = 600

/**
 * Wraps a hand's card row with a one-shot visual reaction to [status], triggered only on the
 * actual transition (keyed on `status` itself, so it doesn't re-fire on unrelated
 * recompositions): a red flash + horizontal shake + a persistent "BUST" stamp for BUST, or a
 * brief gold pulse for STOOD. PLAYING (including the reset at the start of a fresh round, when
 * hand rows are fully disposed/recreated as hands go empty→populated) does nothing.
 */
@Composable
fun HandStatusEffect(status: HandStatus, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val shakeX = remember { Animatable(0f) }
    val flashAlpha = remember { Animatable(0f) }

    LaunchedEffect(status) {
        when (status) {
            HandStatus.BUST -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                launch {
                    flashAlpha.snapTo(0.55f)
                    flashAlpha.animateTo(0f, tween(BUST_FLASH_DURATION_MS))
                }
                for (x in BUST_SHAKE_OFFSETS) {
                    shakeX.animateTo(x, tween(BUST_SHAKE_STEP_MS))
                }
            }
            HandStatus.STOOD -> {
                flashAlpha.snapTo(0.3f)
                flashAlpha.animateTo(0f, tween(STAND_PULSE_DURATION_MS))
            }
            HandStatus.PLAYING -> Unit
        }
    }

    // matchParentSize (needing a separate same-size overlay child) isn't available off the
    // Wear-specific compose-foundation artifact this project uses, so the flash is painted as a
    // background directly on the shaking content box instead of a sibling overlay — the outer
    // Box then just sizes itself to that one child, with the "BUST" stamp centered over it.
    val flashColor = if (status == HandStatus.BUST) BustFlashRed else MenuGold
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .graphicsLayer { translationX = shakeX.value }
                .background(flashColor.copy(alpha = flashAlpha.value))
        ) {
            content()
        }
        if (status == HandStatus.BUST) {
            // A plain white Text here washed out against the cream card faces underneath it —
            // give the stamp its own solid dark badge so it reads regardless of what's behind.
            Box(
                modifier = Modifier
                    .background(MenuBlack)
                    .border(BorderStroke(2.dp, BustFlashRed))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "BUST",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp
                )
            }
        }
    }
}

/** Animates the displayed bankroll counting up/down toward [target] whenever it changes. */
@Composable
fun rememberAnimatedBankroll(target: Int): Int {
    val animated = remember { Animatable(target.toFloat()) }
    LaunchedEffect(target) {
        animated.animateTo(target.toFloat(), tween(BANKROLL_COUNT_DURATION_MS, easing = FastOutSlowInEasing))
    }
    return animated.value.roundToInt()
}
