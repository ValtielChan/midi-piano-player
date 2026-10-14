package com.fabien.midipiano

class Note(val start: Double, val end: Double, val pitch: Int, val velocity: Int, val track: Int, val channel: Int)
class MidiEvent(val time: Double, val msg: ByteArray)

/** Morceau parsé : notes (hors batterie) pour l'affichage, événements bruts pour le synthé. Temps en ms. */
class MidiSong(val notes: List<Note>, val events: List<MidiEvent>, val duration: Double) {
    /** prefixMaxEnd[i] = max(end) des notes 0..i → recherche binaire de la 1re note encore visible. */
    val prefixMaxEnd = DoubleArray(notes.size).also { arr ->
        var m = 0.0
        for (i in notes.indices) { m = maxOf(m, notes[i].end); arr[i] = m }
    }
    val minPitch = notes.minOfOrNull { it.pitch } ?: 48
    val maxPitch = notes.maxOfOrNull { it.pitch } ?: 72
    private val meanPitch = notes.map { it.pitch }.average()
    private val trackIsRight = notes.groupBy { it.track }.mapValues { (_, l) -> l.map { it.pitch }.average() >= meanPitch }
    private val channelIsRight = notes.groupBy { it.channel }.mapValues { (_, l) -> l.map { it.pitch }.average() >= meanPitch }
    /** 1 = par piste, 2 = par canal, 3 = par hauteur. */
    val autoHandMode = when {
        trackIsRight.size >= 2 -> 1
        channelIsRight.size >= 2 -> 2
        else -> 3
    }

    /** 0 = main droite, 1 = main gauche. */
    fun hand(n: Note, mode: Int, splitNote: Int, swap: Boolean): Int {
        val m = if (mode == 0) autoHandMode else mode
        val right = when (m) {
            1 -> trackIsRight[n.track] ?: true
            2 -> channelIsRight[n.channel] ?: true
            else -> n.pitch >= splitNote
        }
        return if (right != swap) 0 else 1
    }
}

object MidiParser {
    private class Raw(val tick: Long, val order: Int, val track: Int, val msg: ByteArray?, val tempo: Int)

    fun parse(b: ByteArray): MidiSong {
        var p = 0
        fun u8() = b[p++].toInt() and 0xFF
        fun u16() = (u8() shl 8) or u8()
        fun u32() = (u16().toLong() shl 16) or u16().toLong()
        fun vlq(): Long {
            var v = 0L
            while (true) { val c = u8(); v = (v shl 7) or (c and 0x7F).toLong(); if (c and 0x80 == 0) return v }
        }
        require(b.size > 14 && String(b, 0, 4, Charsets.US_ASCII) == "MThd") { "Ce n'est pas un fichier MIDI" }
        p = 4
        val hlen = u32().toInt(); u16() /* format */; val ntracks = u16(); val division = u16()
        p = 8 + hlen

        val raws = ArrayList<Raw>()
        var order = 0
        for (t in 0 until ntracks) {
            if (p + 8 > b.size) break
            val id = String(b, p, 4, Charsets.US_ASCII); p += 4
            val end = minOf(b.size.toLong(), p + 4 + u32()).toInt()
            if (id != "MTrk") { p = end; continue }
            var tick = 0L; var status = 0
            while (p < end) {
                tick += vlq()
                var s = u8()
                if (s < 0x80) { p--; s = status } else if (s < 0xF0) status = s
                when {
                    s == 0xFF -> {
                        val type = u8(); val l = vlq().toInt()
                        if (type == 0x51 && l == 3) raws += Raw(tick, order++, t, null, (u8() shl 16) or (u8() shl 8) or u8())
                        else p += l
                    }
                    s == 0xF0 || s == 0xF7 -> p += vlq().toInt()
                    s >= 0xF8 -> {}
                    else -> {
                        val n = if ((s and 0xF0) == 0xC0 || (s and 0xF0) == 0xD0) 1 else 2
                        val msg = ByteArray(n + 1); msg[0] = s.toByte()
                        for (i in 1..n) msg[i] = b[p++]
                        raws += Raw(tick, order++, t, msg, 0)
                    }
                }
            }
            p = end
        }
        raws.sortWith(compareBy({ it.tick }, { it.order }))

        val smpte = division and 0x8000 != 0
        var tempo = 500_000
        fun msPerTick() = if (smpte) 1000.0 / ((256 - (division shr 8)) * (division and 0xFF)) else tempo / 1000.0 / division
        var lastTick = 0L; var lastMs = 0.0
        val events = ArrayList<MidiEvent>(raws.size)
        val notes = ArrayList<Note>()
        val open = HashMap<Int, Pair<Double, Int>>()
        for (r in raws) {
            val ms = lastMs + (r.tick - lastTick) * msPerTick()
            lastMs = ms; lastTick = r.tick
            val msg = r.msg
            if (msg == null) { tempo = r.tempo; continue }
            events += MidiEvent(ms, msg)
            val cmd = msg[0].toInt() and 0xF0; val ch = msg[0].toInt() and 0x0F
            if (ch == 9 || (cmd != 0x80 && cmd != 0x90)) continue
            val pitch = msg[1].toInt(); val vel = msg[2].toInt()
            val key = (r.track * 16 + ch) * 128 + pitch
            open.remove(key)?.let { notes += Note(it.first, ms, pitch, it.second, r.track, ch) }
            if (cmd == 0x90 && vel > 0) open[key] = ms to vel
        }
        for ((key, v) in open) notes += Note(v.first, lastMs, key % 128, v.second, key / 2048, (key / 128) % 16)
        notes.sortBy { it.start }
        return MidiSong(notes, events, lastMs)
    }
}
