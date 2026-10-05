package com.sensinglocal.blackjack

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * occupied one asks for confirmation before being overwritten); Resume only enables occupied
 * slots, and if none are occupied shows "Start a new game" instead of the list. Long-pressing
 * an occupied slot (either mode) offers Delete, also behind a confirmation.
 */
@Composable
fun StorySlotsScreen(
    mode: SlotMode,
    saveSlots: SaveSlots,
    onSlotChosen: (Int) -> Unit,
    onBack: () -> Unit
) {
    val active = remember { mutableStateListOf<Boolean>().apply { repeat(SAVE_SLOT_COUNT) { add(saveSlots.isActive(it + 1)) } } }
    var pendingOverwrite by remember { mutableStateOf<Int?>(null) }
    var pendingDelete by remember { mutableStateOf<Int?>(null) }

    ScrollableInfoScreen(onBack = onBack) {
        item { Spacer(Modifier.height(40.dp)) }
        val overwriteSlot = pendingOverwrite
        val deleteSlot = pendingDelete
        if (overwriteSlot != null || deleteSlot != null) {
            val slot = (overwriteSlot ?: deleteSlot)!!
            val deleting = deleteSlot != null
            item {
                Text(
                    text = if (deleting) "Delete Slot $slot?" else "Overwrite Slot $slot?",
                    color = MenuGold,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
            }
            item {
                Text(
                    text = "This can't be undone",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp)
                )
            }
            item {
                PixelButton(
                    text = if (deleting) "Delete" else "Overwrite",
                    onClick = {
                        if (deleting) {
                            saveSlots.delete(slot)
                            active[slot - 1] = false
                            pendingDelete = null
                        } else {
                            pendingOverwrite = null
                            onSlotChosen(slot)
                        }
                    },
                    width = 148.dp,
                    height = 42.dp,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            item {
                PixelButton(
                    text = "Cancel",
                    onClick = { pendingOverwrite = null; pendingDelete = null },
                    width = 148.dp,
                    height = 42.dp,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        } else {
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
                if (active.any { it }) {
                    item {
                        Text(
                            text = if (mode == SlotMode.NEW_GAME) {
                                "Picking a saved slot overwrites it. Hold a saved slot to delete"
                            } else {
                                "Hold a saved slot to delete"
                            },
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
                            onClick = {
                                if (mode == SlotMode.NEW_GAME && isActive) pendingOverwrite = slot
                                else onSlotChosen(slot)
                            },
                            enabled = mode == SlotMode.NEW_GAME || isActive,
                            width = 148.dp,
                            height = 42.dp,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(vertical = 4.dp),
                            onLongClick = if (isActive) ({ pendingDelete = slot }) else null
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}
