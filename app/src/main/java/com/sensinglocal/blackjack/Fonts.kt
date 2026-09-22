package com.sensinglocal.blackjack

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.res.ResourcesCompat

// VT323 — a chunky, pixelated monospace face (Google Fonts, OFL). Used app-wide so every
// piece of text, including the pixel-art title/card labels drawn straight onto a Canvas,
// matches the pixel-art visual direction.
val VT323 = FontFamily(Font(R.font.vt323_regular))

/** Loads the same VT323 face as a plain [android.graphics.Typeface], for text drawn via a
 * raw Canvas Paint (e.g. the curved menu title, card rank labels) rather than a Compose Text. */
fun vt323Typeface(context: Context): android.graphics.Typeface =
    ResourcesCompat.getFont(context, R.font.vt323_regular) ?: android.graphics.Typeface.MONOSPACE
