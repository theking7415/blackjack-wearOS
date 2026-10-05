package com.sensinglocal.blackjack

import android.content.Context

const val SAVE_SLOT_COUNT = 3

/**
 * Story Mode save slots (1..[SAVE_SLOT_COUNT]), each holding one [StorySave] as versioned JSON.
 * Backed by its own SharedPreferences file, separate from Quick Play's `blackjack_prefs`.
 * A slot whose JSON is corrupt or from an unknown version reads as empty.
 */
class SaveSlots(context: Context) {
    private val prefs = context.getSharedPreferences("story_saves", Context.MODE_PRIVATE)

    fun load(slot: Int): StorySave? {
        prefs.getString(saveKey(slot), null)?.let { return StorySaveCodec.decode(it) }
        // Pre-2026-10-05 slots only stored an "active" flag; treat them as a game at the street.
        return if (prefs.getBoolean(legacyKey(slot), false)) StorySave.legacyMigrated() else null
    }

    fun isActive(slot: Int): Boolean = load(slot) != null

    fun save(slot: Int, save: StorySave) {
        prefs.edit().putString(saveKey(slot), StorySaveCodec.encode(save)).apply()
    }

    /** Starts a fresh game in [slot], overwriting whatever was there. */
    fun startNew(slot: Int): StorySave = StorySave.newGame().also { save(slot, it) }

    fun delete(slot: Int) {
        prefs.edit().remove(saveKey(slot)).remove(legacyKey(slot)).apply()
    }

    private fun saveKey(slot: Int) = "slot_${slot}_save"
    private fun legacyKey(slot: Int) = "slot_${slot}_active"
}
