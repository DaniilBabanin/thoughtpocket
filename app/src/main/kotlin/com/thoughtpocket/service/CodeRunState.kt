package com.thoughtpocket.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for the coder feature's run state — same pattern as
 * [RecordState]: CoderRunService owns all writes, Compose observes passively.
 * Everything is in-memory only; nothing persists until the user inserts a
 * result into the note.
 */
object CodeRunState {
    enum class Phase { IDLE, STARTING, GENERATING, RUNNING, FIXING, DONE, FAILED }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val noteId: Long = -1,
        /** The code_runs row this run belongs to (-1 while a new item is still running). */
        val activeRunId: Long = -1,
        val attempt: Int = 0,
        /** Streamed model text for the current generation (progress display). */
        val streamed: String = "",
        val tokenCount: Int = 0,
        /** Final stdout when DONE; honest failure message when FAILED. */
        val result: String = "",
        /** Attempt log of a FAILED run (code to error), for the details view. */
        val failedAttempts: List<Pair<String, String>> = emptyList(),
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    internal fun update(f: (Status) -> Status) {
        _status.value = f(_status.value)
    }

    internal fun reset() {
        _status.value = Status()
    }
}
