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

    /** Live transcript-so-far while recording/transcribing; "" when idle. */
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial

    fun set(state: State, startedAtElapsedRealtime: Long = 0L) {
        _status.value = Status(state, startedAtElapsedRealtime)
        if (state == State.IDLE) _partial.value = ""
    }

    fun setPartial(text: String) {
        _partial.value = text
    }
}
