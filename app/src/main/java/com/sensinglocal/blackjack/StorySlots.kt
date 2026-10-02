package com.sensinglocal.blackjack

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text

enum class SlotMode { NEW_GAME, RESUME }

/**
 * Save-slot picker shared by New Game and Resume. New Game lets any slot be picked (an
 * occupied one gets overwritten); Resume only enables occupied slots, and if none are
 * occupied shows "Start a new game" instead of the list.
 */
@Composable
fun StorySlotsScreen(
    mode: SlotMode,
    saveSlots: SaveSlots,
    onSlotChosen: (Int) -> Unit,
    onBack: () -> Unit
) {
    val active = remember { List(SAVE_SLOT_COUNT) { saveSlots.isActive(it + 1) } }
    ScrollableInfoScreen(onBack = onBack) {
        item { Spacer(Modifier.height(40.dp)) }
        item {
            Text(
                text = if (mode == SlotMode.NEW_GAME) "New Game" else "Resume",
                color = MenuGold,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp
            )
        }
        if (mode == SlotMode.RESUME && active.none { it }) {
            item {
                Text(
                    text = "Start a new game",
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 30.dp, vertical = 12.dp)
                )
            }
        } else {
            if (mode == SlotMode.NEW_GAME && active.any { it }) {
                item {
                    Text(
                        text = "Picking a saved slot overwrites it",
                        color = Color(0xFFAAAAAA),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 30.dp)
                    )
                }
            }
            for (slot in 1..SAVE_SLOT_COUNT) {
                val isActive = active[slot - 1]
                item {
                    PixelButton(
                        text = "Slot $slot: ${if (isActive) "Saved" else "Empty"}",
                        onClick = { onSlotChosen(slot) },
                        enabled = mode == SlotMode.NEW_GAME || isActive,
                        width = 148.dp,
                        height = 42.dp,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}
