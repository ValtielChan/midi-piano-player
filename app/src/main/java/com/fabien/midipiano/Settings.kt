package com.fabien.midipiano

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit

data class Settings(
    val keyboardPreset: Int = 0,      // 0 = auto (adapté au morceau), sinon nombre de touches
    val windowSec: Float = 3f,        // durée visible de chute
    val keyboardFrac: Float = 0.28f,  // hauteur du clavier (fraction de l'écran)
    val palette: Int = 0,
    val handMode: Int = 0,            // 0 auto, 1 pistes, 2 canaux, 3 hauteur
    val splitNote: Int = 60,
    val swapHands: Boolean = false,
    val showLabels: Boolean = true,
    val noteNames: Int = 0,           // nom sur les notes qui tombent : 0 off, 1 ABC, 2 Do Ré Mi
)

class Palette(val name: String, val right: Color, val left: Color)

val PALETTES = listOf(
    Palette("Ciel / Orange", Color(0xFF38BDF8), Color(0xFFFB923C)),
    Palette("Vert / Bleu", Color(0xFF4ADE80), Color(0xFF60A5FA)),
    Palette("Fuchsia / Jaune", Color(0xFFE879F9), Color(0xFFFACC15)),
    Palette("Rose / Turquoise", Color(0xFFF472B6), Color(0xFF2DD4BF)),
)

val KEYBOARD_PRESETS = listOf(0, 25, 49, 61, 76, 88)

class SettingsStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = Settings(
        p.getInt("preset", 0), p.getFloat("window", 3f), p.getFloat("kbFrac", 0.28f), p.getInt("palette", 0),
        p.getInt("handMode", 0), p.getInt("split", 60), p.getBoolean("swap", false), p.getBoolean("labels", true),
        p.getInt("noteNames", 0),
    )

    fun save(s: Settings) = p.edit {
        putInt("preset", s.keyboardPreset); putFloat("window", s.windowSec); putFloat("kbFrac", s.keyboardFrac)
        putInt("palette", s.palette); putInt("handMode", s.handMode); putInt("split", s.splitNote)
        putBoolean("swap", s.swapHands); putBoolean("labels", s.showLabels)
        putInt("noteNames", s.noteNames)
    }

    var lastUri: String?
        get() = p.getString("lastUri", null)
        set(v) = p.edit { putString("lastUri", v) }
}

private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val SOLFEGE = arrayOf("Do", "Do#", "Ré", "Ré#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si")
fun pitchClass(p: Int, solfege: Boolean) = (if (solfege) SOLFEGE else NAMES)[p % 12]
fun noteName(p: Int, solfege: Boolean = false) = pitchClass(p, solfege) + (p / 12 - 1)
fun isBlack(p: Int) = NAMES[p % 12].length == 2

/** Plage de touches [lo, hi] (bornes blanches) selon le preset ou le morceau. */
fun keyRange(song: MidiSong?, preset: Int): IntRange {
    var (lo, hi) = when (preset) {
        88 -> 21 to 108
        76 -> 28 to 103
        61 -> 36 to 96
        49 -> 36 to 84
        25 -> 48 to 72
        else -> if (song == null) 36 to 96 else (song.minPitch - 3).coerceAtLeast(21) to (song.maxPitch + 3).coerceAtMost(108)
    }
    while (isBlack(lo)) lo--
    while (isBlack(hi)) hi++
    return lo..hi
}
