package com.thoughtpocket.service

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

    /** Live transcript-so-far while recording, trimmed to the freshest words for the card; "" otherwise. */
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial

    /** Same live preview but untrimmed (full window text) — for inline display in an editor; "" otherwise. */
    private val _partialFull = MutableStateFlow("")
    val partialFull: StateFlow<String> = _partialFull

    /** Recordings still transcribing in the background — lets a new recording start immediately. */
    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending

    /**
     * One optimistic "note" per clip in the transcription queue, shown under
     * Recent until the real note replaces it. RecordingService owns all writes.
     */
    data class QueueItem(
        val id: Long,
        /** "Recording HH:MM", or the source filename for imports. */
        val label: String,
        val active: Boolean = false,
        /** 0..1 progress of the active item (whisper only); 0 = indeterminate. */
        val progress: Float = 0f,
        /** Transcript-so-far of the active item (tail-trimmed). */
        val partial: String = "",
        /** Cancel requested — teardown in flight, card shows "Cancelling…". */
        val cancelling: Boolean = false,
    )

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue

    fun queueAdd(item: QueueItem) { _queue.value = _queue.value + item }
    fun queueUpdate(id: Long, f: (QueueItem) -> QueueItem) {
        _queue.value = _queue.value.map { if (it.id == id) f(it) else it }
    }
    fun queueRemove(id: Long) { _queue.value = _queue.value.filterNot { it.id == id } }

    /** Smoothed mic loudness 0..1 while recording (drives the orb pulse); 0 otherwise. */
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    fun set(state: State, startedAtElapsedRealtime: Long = 0L) {
        _status.value = Status(state, startedAtElapsedRealtime)
        // Live partial + level belong only to an active recording; clear them on any other transition.
        if (state != State.RECORDING) { _partial.value = ""; _partialFull.value = ""; _amplitude.value = 0f }
    }

    fun setAmplitude(level: Float) {
        _amplitude.value = level
    }

    fun setPartial(tail: String, full: String) {
        _partial.value = tail
        _partialFull.value = full
    }

    fun setPending(count: Int) {
        _pending.value = count
    }
}
