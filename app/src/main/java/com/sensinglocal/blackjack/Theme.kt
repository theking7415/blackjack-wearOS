package com.sensinglocal.blackjack

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay

// Shared red/gold pixel-art palette (originated in the main menu) plus the green felt
// table palette used for the in-game background.
val MenuRed = Color(0xFFC8102E)
val MenuRedDark = Color(0xFF7A0C1E)
val MenuGold = Color(0xFFD4AF37)
val MenuBlack = Color(0xFF0A0A0A)
val TableGreen = Color(0xFF0B6623)
val TableGreenDark = Color(0xFF042A0D)
val CardCream = Color(0xFFF3ECD8)

/**
 * The main menu's PLAY button, generalized: chunky pixel-art 3D button (dark fixed base
 * layer + offset top layer that sinks onto it when pressed), gold border, haptic tap.
 * Reused for every button in the app so the whole UI shares one visual language.
 */
@Composable
fun PixelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 84.dp,
    height: Dp = 40.dp,
    fontSize: TextUnit = 14.sp,
    enabled: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressOffset by animateDpAsState(targetValue = if (isPressed) 3.dp else 0.dp, label = "btnPress")

    Box(modifier = modifier.size(width = width, height = height)) {
        Box(
            modifier = Modifier
                .size(width = width, height = height)
                .offset(x = 3.dp, y = 3.dp)
                .background(MenuBlack)
        )
        Box(
            modifier = Modifier
                .size(width = width, height = height)
                .offset(x = pressOffset, y = pressOffset)
                .background(if (enabled) MenuRed else MenuRedDark)
                .border(BorderStroke(2.dp, MenuGold))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (enabled) Color.Black else Color(0xFF3A3A3A),
                fontWeight = FontWeight.Black,
                fontSize = fontSize
            )
        }
    }
}

/**
 * Same chunky pixel-art look as [PixelButton], but for a repeatable stepper action (e.g. a
 * bet +/- control): a plain tap fires [onStep] once, and holding fires it repeatedly, ramping
 * up in speed the longer it's held — first repeat after [initialDelayMs], then the interval
 * between repeats shrinks by [accelerationMs] each time down to [minIntervalMs].
 */
@Composable
fun RepeatingPixelButton(
    text: String,
    onStep: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    fontSize: TextUnit = 22.sp,
    initialDelayMs: Long = 350,
    startIntervalMs: Long = 200,
    minIntervalMs: Long = 45,
    accelerationMs: Long = 18
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val pressOffset by animateDpAsState(targetValue = if (pressed) 3.dp else 0.dp, label = "stepBtnPress")

    Box(
        modifier = modifier
            .size(size)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onStep()
                    waitForUpOrCancellation()
                    pressed = false
                }
            }
    ) {
        Box(modifier = Modifier.size(size).offset(x = 3.dp, y = 3.dp).background(MenuBlack))
        Box(
            modifier = Modifier
                .size(size)
                .offset(x = pressOffset, y = pressOffset)
                .background(MenuRed)
                .border(BorderStroke(2.dp, MenuGold)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = text, color = Color.Black, fontWeight = FontWeight.Black, fontSize = fontSize)
        }
    }

    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        delay(initialDelayMs)
        var interval = startIntervalMs
        while (pressed) {
            onStep()
            delay(interval)
            interval = (interval - accelerationMs).coerceAtLeast(minIntervalMs)
        }
    }
}
