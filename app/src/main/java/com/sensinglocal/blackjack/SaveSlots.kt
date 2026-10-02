package com.sensinglocal.blackjack

import android.content.Context

const val SAVE_SLOT_COUNT = 3

/**
 * Story Mode save slots (1..[SAVE_SLOT_COUNT]). For now a slot only records whether it's in
 * use — there's no real story state to store yet. Backed by its own SharedPreferences file,
 * separate from Quick Play's `blackjack_prefs` bankroll.
 */
class SaveSlots(context: Context) {
    private val prefs = context.getSharedPreferences("story_saves", Context.MODE_PRIVATE)

    fun isActive(slot: Int): Boolean = prefs.getBoolean(key(slot), false)

    /** Starts a fresh game in [slot], overwriting whatever was there. */
    fun startNew(slot: Int) {
        prefs.edit().putBoolean(key(slot), true).apply()
    }

    private fun key(slot: Int) = "slot_${slot}_active"
}
