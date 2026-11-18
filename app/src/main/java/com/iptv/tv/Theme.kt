package com.iptv.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** App colours. Every value reads Compose state, so changing the theme/accent in Settings recolours the UI live. */
object Theme {
    val modes = listOf("Midnight", "Graphite", "Super AMOLED")
    val accents: List<Pair<String, Long>> = listOf(
        "Red" to 0xFFE50914, "Blue" to 0xFF2979FF, "Green" to 0xFF00C853, "Purple" to 0xFF8E44FF,
        "Orange" to 0xFFFF6D00, "Teal" to 0xFF00BFA5, "Pink" to 0xFFFF4081, "Gold" to 0xFFFFC107
    )

    private var modeState by mutableStateOf(0)
    private var accentState by mutableStateOf(0)
    val mode: Int get() = modeState
    val accentIdx: Int get() = accentState

    fun load() {
        modeState = Store.int("theme", 0).coerceIn(0, modes.size - 1)
        accentState = Store.int("accent", 0).coerceIn(0, accents.size - 1)
    }
    fun setMode(i: Int) { modeState = i.coerceIn(0, modes.size - 1); Store.put("theme", modeState) }
    fun setAccent(i: Int) { accentState = i.coerceIn(0, accents.size - 1); Store.put("accent", accentState) }

    val accent: Color get() = Color(accents[accentIdx].second)
    val onAccent: Color get() = if (accent.luminance() > 0.5f) Color.Black else Color.White

    val bg: Color get() = when (mode) { 1 -> Color(0xFF121212); 2 -> Color(0xFF000000); else -> Color(0xFF0B0E1A) }
    val sidebar: Color get() = when (mode) { 1 -> Color(0xFF1A1A1A); 2 -> Color(0xFF050505); else -> Color(0xFF10142A) }
    val panel: Color get() = when (mode) { 1 -> Color(0xFF1E1E1E); 2 -> Color(0xFF0A0A0A); else -> Color(0xFF131833) }
    val card: Color get() = when (mode) { 1 -> Color(0xFF262626); 2 -> Color(0xFF141414); else -> Color(0xFF1A2040) }
    val chip: Color get() = when (mode) { 1 -> Color(0xFF303030); 2 -> Color(0xFF1E1E1E); else -> Color(0xFF242C52) }
    val muted: Color get() = Color(0xFF9AA3C0)
}
