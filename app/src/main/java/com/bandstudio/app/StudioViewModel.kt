package com.bandstudio.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.bandstudio.app.audio.AudioEngine

/** Trạng thái giao diện + chuyển lệnh xuống engine. Sống sót qua xoay màn hình. */
class StudioViewModel : ViewModel() {

    var bpm by mutableIntStateOf(120)
        private set

    var playing by mutableStateOf(false)
        private set

    var metronome by mutableStateOf(true)
        private set

    /** Quãng tám của phím C bên trái (4 -> C4 = nốt MIDI 60). */
    var octave by mutableIntStateOf(4)
        private set

    val startNote: Int get() = 12 * (octave + 1)

    fun changeBpm(value: Int) {
        bpm = value.coerceIn(40, 240)
        AudioEngine.setBpm(bpm.toFloat())
    }

    fun togglePlay() {
        playing = !playing
        AudioEngine.setPlaying(playing)
    }

    fun toggleMetronome() {
        metronome = !metronome
        AudioEngine.setMetronome(metronome)
    }

    fun changeOctave(delta: Int) {
        AudioEngine.allNotesOff()
        octave = (octave + delta).coerceIn(1, 6)
    }

    fun noteOn(note: Int) = AudioEngine.noteOn(note, 100)

    fun noteOff(note: Int) = AudioEngine.noteOff(note)

    /** Đẩy lại trạng thái xuống engine (sau khi mở lại stream). */
    fun syncEngine() {
        AudioEngine.setBpm(bpm.toFloat())
        AudioEngine.setMetronome(metronome)
        AudioEngine.setPlaying(playing)
    }

    /** Khi ứng dụng xuống nền, engine bị đóng nên dừng luôn transport cho khớp. */
    fun onBackground() {
        playing = false
        AudioEngine.setPlaying(false)
        AudioEngine.allNotesOff()
    }
}
