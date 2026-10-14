package com.fabien.midipiano

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Notes qui tombent + clavier. Redessiné à chaque frame via [pos] (ms). */
@Composable
fun PianoCanvas(modifier: Modifier, song: MidiSong?, pos: Double, s: Settings) {
    val range = remember(song, s.keyboardPreset) { keyRange(song, s.keyboardPreset) }
    val whitesBefore = remember(range) {
        IntArray(range.last + 2).also { arr ->
            var c = 0
            for (p in range.first..range.last + 1) { arr[p] = c; if (!isBlack(p)) c++ }
        }
    }
    val nWhite = whitesBefore[range.last + 1]
    val pal = PALETTES[s.palette]
    val density = LocalDensity.current
    val textPaint = remember(density) {
        Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; textSize = with(density) { 10.sp.toPx() } }
    }
    val notePaint = remember {
        Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; isFakeBoldText = true; color = 0xD9000000.toInt() }
    }
    val solfege = s.noteNames == 2
    val active = remember { IntArray(128) }

    Canvas(modifier) {
        val w = size.width; val h = size.height
        val kbH = h * s.keyboardFrac; val hitY = h - kbH
        val lo = range.first; val hi = range.last
        val ww = w / nWhite; val bw = ww * 0.6f
        val radius = minOf(6.dp.toPx(), ww * 0.25f)
        fun keyX(p: Int) = if (isBlack(p)) whitesBefore[p] * ww - bw / 2 else whitesBefore[p] * ww
        fun handColor(hand: Int) = if (hand == 0) pal.right else pal.left

        // Fond
        drawRect(Brush.verticalGradient(listOf(Color(0xFF07080F), Color(0xFF141829)), endY = hitY), size = Size(w, hitY))
        for (p in lo..hi) if (isBlack(p)) drawRect(Color.Black.copy(alpha = 0.16f), Offset(keyX(p), 0f), Size(bw, hitY))
        for (p in lo..hi) if (p % 12 == 0 && p > lo) {
            val x = keyX(p)
            drawLine(Color.White.copy(alpha = 0.07f), Offset(x, 0f), Offset(x, hitY), 1f)
        }

        // Notes
        java.util.Arrays.fill(active, -1)
        if (song != null) {
            val windowMs = s.windowSec * 1000.0
            val pxPerMs = hitY / windowMs
            val notes = song.notes
            notePaint.textSize = minOf(12.sp.toPx(), ww * (if (solfege) 0.36f else 0.48f))
            var i = lowerBound(song.prefixMaxEnd, pos)
            while (i < notes.size) {
                val n = notes[i++]
                if (n.start > pos + windowMs) break
                if (n.end < pos || n.pitch < lo || n.pitch > hi) continue
                val hand = song.hand(n, s.handMode, s.splitNote, s.swapHands)
                val isOn = n.start <= pos
                if (isOn) active[n.pitch] = hand
                val black = isBlack(n.pitch)
                val x = keyX(n.pitch) + (if (black) bw * 0.1f else 2f)
                val nw = if (black) bw * 0.8f else ww - 4f
                val top = (hitY - (n.end - pos) * pxPerMs).toFloat().coerceAtLeast(-radius)
                val bottom = (hitY - (n.start - pos) * pxPerMs).toFloat().coerceAtMost(hitY)
                val nh = (bottom - top).coerceAtLeast(3f)
                var c = handColor(hand)
                if (black) c = lerp(c, Color.Black, 0.25f)
                if (isOn) {
                    drawRoundRect(c.copy(alpha = 0.35f), Offset(x - 4f, top - 4f), Size(nw + 8f, nh + 8f), CornerRadius(radius + 4f))
                    val cx = x + nw / 2; val r = ww * 1.3f
                    drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.55f), Color.Transparent), Offset(cx, hitY), r), r, Offset(cx, hitY))
                }
                drawRoundRect(
                    Brush.horizontalGradient(listOf(lerp(c, Color.White, 0.35f), c, lerp(c, Color.Black, 0.3f)), startX = x, endX = x + nw),
                    Offset(x, top), Size(nw, nh), CornerRadius(radius),
                )
                if (isOn) drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(x, top), Size(nw, nh), CornerRadius(radius))
                if (s.noteNames != 0 && nh > notePaint.textSize + 6f) {
                    drawContext.canvas.nativeCanvas.drawText(pitchClass(n.pitch, solfege), x + nw / 2, bottom - notePaint.textSize * 0.4f, notePaint)
                }
            }
        }

        // Ligne d'impact
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.12f)), startY = hitY - 30f, endY = hitY), Offset(0f, hitY - 30f), Size(w, 30f))
        drawLine(Color.White.copy(alpha = 0.6f), Offset(0f, hitY), Offset(w, hitY), 2f)

        // Touches blanches
        val kr = ww * 0.12f
        for (p in lo..hi) if (!isBlack(p)) {
            val x = keyX(p); val a = active[p]
            val brush = if (a < 0) Brush.verticalGradient(listOf(Color(0xFFF7F7FA), Color(0xFFD7D9E2)), startY = hitY, endY = h)
            else Brush.verticalGradient(listOf(lerp(handColor(a), Color.White, 0.45f), handColor(a)), startY = hitY, endY = h)
            key(brush, x + 1f, hitY, ww - 2f, kbH, kr)
            if (s.showLabels && p % 12 == 0) {
                textPaint.color = if (a < 0) 0xFF70758A.toInt() else 0xFFFFFFFF.toInt()
                drawContext.canvas.nativeCanvas.drawText(noteName(p, solfege), x + ww / 2, h - 6.dp.toPx(), textPaint)
            }
        }
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), startY = hitY, endY = hitY + 14f), Offset(0f, hitY), Size(w, 14f))

        // Touches noires
        val bh = kbH * 0.62f
        for (p in lo..hi) if (isBlack(p)) {
            val x = keyX(p); val a = active[p]
            val brush = if (a < 0) Brush.verticalGradient(listOf(Color(0xFF2B2D3A), Color(0xFF0D0E15)), startY = hitY, endY = hitY + bh)
            else Brush.verticalGradient(listOf(lerp(handColor(a), Color.White, 0.2f), lerp(handColor(a), Color.Black, 0.25f)), startY = hitY, endY = hitY + bh)
            drawRect(Color.Black.copy(alpha = 0.4f), Offset(x - 1.5f, hitY), Size(bw + 3f, bh + 2.5f))
            key(brush, x, hitY, bw, bh, kr)
        }
    }
}

/** Touche : coins arrondis en bas seulement. */
private fun DrawScope.key(brush: Brush, x: Float, y: Float, w: Float, h: Float, r: Float) {
    drawRoundRect(brush, Offset(x, y), Size(w, h), CornerRadius(r))
    drawRect(brush, Offset(x, y), Size(w, r))
}

/** Premier index i tel que arr[i] >= v (arr croissant). */
private fun lowerBound(arr: DoubleArray, v: Double): Int {
    var lo = 0; var hi = arr.size
    while (lo < hi) { val mid = (lo + hi) ushr 1; if (arr[mid] < v) lo = mid + 1 else hi = mid }
    return lo
}
