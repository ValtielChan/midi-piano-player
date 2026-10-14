package com.fabien.midipiano

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiParserTest {
    private fun vlq(n: Int): ByteArray {
        val out = ArrayList<Int>(); var v = n
        out += v and 0x7F; v = v shr 7
        while (v > 0) { out += (v and 0x7F) or 0x80; v = v shr 7 }
        return out.reversed().map { it.toByte() }.toByteArray()
    }
    private fun track(vararg ev: Pair<Int, ByteArray>): ByteArray {
        val data = ev.fold(byteArrayOf()) { acc, (d, m) -> acc + vlq(d) + m } + byteArrayOf(0, 0xFF.toByte(), 0x2F, 0)
        return "MTrk".toByteArray() + byteArrayOf(0, 0, 0, data.size.toByte()) + data
    }

    @Test
    fun parsesTwoTracksWithTempoChangeAndRunningStatus() {
        val ppq = 480
        val header = "MThd".toByteArray() + byteArrayOf(0, 0, 0, 6, 0, 1, 0, 3, (ppq shr 8).toByte(), (ppq and 0xFF).toByte())
        // Tempo 120 bpm, then 60 bpm at tick 480
        val tempo = track(
            0 to byteArrayOf(0xFF.toByte(), 0x51, 3, 0x07, 0xA1.toByte(), 0x20),
            ppq to byteArrayOf(0xFF.toByte(), 0x51, 3, 0x0F, 0x42, 0x40),
        )
        // Right hand: C5 quarter at 0, then (running status) E5 quarter at 480
        val rh = track(
            0 to byteArrayOf(0x90.toByte(), 72, 100), ppq to byteArrayOf(72, 0),
            0 to byteArrayOf(76, 100), ppq to byteArrayOf(76, 0),
        )
        // Left hand: C3 half note on channel 1
        val lh = track(0 to byteArrayOf(0x91.toByte(), 48, 80), 2 * ppq to byteArrayOf(0x81.toByte(), 48, 0))

        val song = MidiParser.parse(header + tempo + rh + lh)

        assertEquals(3, song.notes.size)
        val c3 = song.notes.first { it.pitch == 48 }
        val c5 = song.notes.first { it.pitch == 72 }
        val e5 = song.notes.first { it.pitch == 76 }
        assertEquals(0.0, c5.start, 1e-6); assertEquals(500.0, c5.end, 1e-6)
        assertEquals(500.0, e5.start, 1e-6); assertEquals(1500.0, e5.end, 1e-6)   // second beat at 60 bpm = 1 s
        assertEquals(1500.0, c3.end, 1e-6)
        assertEquals(1500.0, song.duration, 1e-6)
        assertEquals(1, song.autoHandMode)
        assertEquals(0, song.hand(c5, 0, 60, false))
        assertEquals(1, song.hand(c3, 0, 60, false))
        assertEquals(0, song.hand(c3, 0, 60, true))
        assertEquals(1, song.hand(c3, 3, 60, false))
    }
}
