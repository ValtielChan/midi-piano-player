package com.fabien.midipiano

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.billthefarmer.mididriver.MidiDriver

/** Séquenceur : envoie les événements au synthé GM intégré d'Android au bon moment. */
class Player {
    private val driver: MidiDriver = MidiDriver.getInstance()
    var song: MidiSong? by mutableStateOf(null); private set
    var playing by mutableStateOf(false); private set
    var speed by mutableStateOf(1f); private set

    private val lock = Any()
    private var base = 0.0      // position (ms) au moment de l'ancrage
    private var anchor = 0L     // nanoTime de l'ancrage
    private var next = 0        // prochain événement à envoyer
    @Volatile private var running = false

    private fun pos() = if (playing) base + (System.nanoTime() - anchor) / 1e6 * speed else base
    fun position(): Double = synchronized(lock) { pos() }

    fun start() {
        driver.start()
        if (running) return
        running = true
        Thread(::loop, "midi-seq").apply { isDaemon = true }.start()
    }

    fun suspend() { pause(); driver.stop() }
    fun release() { running = false; driver.stop() }

    fun load(s: MidiSong) = synchronized(lock) {
        allNotesOff(); song = s; playing = false; base = 0.0; next = 0
    }

    fun play() = synchronized(lock) {
        val s = song ?: return
        if (playing) return
        if (base >= s.duration) seekLocked(0.0)
        anchor = System.nanoTime(); playing = true
    }

    fun pause() = synchronized(lock) {
        if (!playing) return
        base = pos(); playing = false; allNotesOff()
    }

    fun toggle() = if (playing) pause() else play()

    fun stop() = synchronized(lock) { playing = false; seekLocked(0.0) }

    fun seek(ms: Double) = synchronized(lock) { seekLocked(ms) }

    fun changeSpeed(v: Float) = synchronized(lock) { base = pos(); anchor = System.nanoTime(); speed = v }

    private fun seekLocked(ms: Double) {
        val s = song ?: return
        base = ms.coerceIn(0.0, s.duration); anchor = System.nanoTime()
        allNotesOff()
        var lo = 0; var hi = s.events.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (s.events[mid].time < base) lo = mid + 1 else hi = mid }
        next = lo
        // Rejoue l'état (programme, contrôleurs, pitch bend) tel qu'il serait à cette position.
        val state = LinkedHashMap<Int, ByteArray>()
        for (i in 0 until next) {
            val m = s.events[i].msg
            when (m[0].toInt() and 0xF0) {
                0xC0, 0xE0 -> state[m[0].toInt()] = m
                0xB0 -> if (m[1].toInt() != 64) state[(m[0].toInt() shl 8) or m[1].toInt()] = m
            }
        }
        state.values.forEach { driver.write(it) }
    }

    private fun loop() {
        while (running) {
            synchronized(lock) {
                val s = song
                if (playing && s != null) {
                    val p = pos()
                    while (next < s.events.size && s.events[next].time <= p) driver.write(s.events[next++].msg)
                    if (p >= s.duration + 500) { playing = false; base = s.duration; allNotesOff() }
                }
            }
            Thread.sleep(2)
        }
    }

    private fun allNotesOff() {
        for (ch in 0 until 16) {
            driver.write(byteArrayOf((0xB0 or ch).toByte(), 64, 0))
            driver.write(byteArrayOf((0xB0 or ch).toByte(), 123, 0))
        }
    }
}
