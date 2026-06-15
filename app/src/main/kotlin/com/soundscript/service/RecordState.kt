package com.soundscript.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Single source of truth for recording/transcription state, observed by UI, widget and tile. */
object RecordState {
    enum class State { IDLE, RECORDING, TRANSCRIBING }

    data class Status(
        val state: State,
        /** SystemClock.elapsedRealtime() at record start; 0 when not recording. */
        val startedAtElapsedRealtime: Long = 0L,
    )

    private val _status = MutableStateFlow(Status(State.IDLE))
    val status: StateFlow<Status> = _status

    /** Live transcript-so-far while recording; "" otherwise. */
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial

    /** Recordings still transcribing in the background — lets a new recording start immediately. */
    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending

    /** Smoothed mic loudness 0..1 while recording (drives the orb pulse); 0 otherwise. */
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    fun set(state: State, startedAtElapsedRealtime: Long = 0L) {
        _status.value = Status(state, startedAtElapsedRealtime)
        // Live partial + level belong only to an active recording; clear them on any other transition.
        if (state != State.RECORDING) { _partial.value = ""; _amplitude.value = 0f }
    }

    fun setAmplitude(level: Float) {
        _amplitude.value = level
    }

    fun setPartial(text: String) {
        _partial.value = text
    }

    fun setPending(count: Int) {
        _pending.value = count
    }
}
