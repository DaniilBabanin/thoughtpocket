package com.thoughtpocket.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.thoughtpocket.ai.coder.CoderEngine
import com.thoughtpocket.ai.coder.CoderHarness
import com.thoughtpocket.ai.coder.CoderHarness.Action
import com.thoughtpocket.ai.coder.CoderHarness.Attempt
import com.thoughtpocket.coder.PyRunnerClient
import com.thoughtpocket.data.NotesDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.os.SystemClock

/**
 * Drives one coder session (dataSync foreground, main process): begin session →
 * generate → gate → execute in the :coder process → diagnose → repair → repeat,
 * publishing progress through [CodeRunState] only — the UI never touches this
 * service (RecordState convention). The session (and the 5.6 GB model) stays
 * warm across follow-ups until ACTION_END from the screen's exit.
 *
 * dataSync vs specialUse: dataSync is the type this app already uses for long
 * background work and a run's ~20 min cap stays under Android 15's cumulative
 * limit; revisit as specialUse if runs ever approach the timeout.
 */
class CoderRunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannel(this)
        ServiceCompat.startForeground(
            this, Notifications.CODER_ID, Notifications.ongoing(this, "Coding…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        when (intent?.action) {
            ACTION_RUN -> {
                val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1)
                val instruction = intent.getStringExtra(EXTRA_INSTRUCTION).orEmpty()
                if (noteId >= 0 && instruction.isNotBlank() && runJob?.isActive != true) {
                    runJob = scope.launch { runOnce(noteId, instruction) }
                }
            }
            ACTION_FOLLOWUP -> {
                val instruction = intent.getStringExtra(EXTRA_INSTRUCTION).orEmpty()
                val noteId = CodeRunState.status.value.noteId
                if (noteId >= 0 && instruction.isNotBlank() && runJob?.isActive != true) {
                    runJob = scope.launch { runOnce(noteId, instruction) }
                }
            }
            ACTION_RERUN_EDITED -> {
                val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
                if (code.isNotBlank() && runJob?.isActive != true) {
                    runJob = scope.launch { rerunEdited(code) }
                }
            }
            ACTION_CANCEL -> cancelRun()
            ACTION_END -> endSession()
        }
        return START_NOT_STICKY
    }

    private suspend fun runOnce(noteId: Long, instruction: String) {
        val startMs = SystemClock.elapsedRealtime()
        fun elapsed() = SystemClock.elapsedRealtime() - startMs

        val note = NotesDb.get(this).notes().getById(noteId) ?: run {
            fail("Note not found"); return
        }
        CodeRunState.update {
            it.copy(phase = CodeRunState.Phase.STARTING, noteId = noteId, attempt = 0,
                streamed = "", tokenCount = 0, result = "")
        }

        if (!CoderEngine.inSession) {
            notify("Loading coding model…")
            CoderEngine.beginSession(this).onFailure { e ->
                fail(e.message ?: "Couldn't load the coding model"); return
            }
        }

        val turns = CodeRunState.status.value.turns
        val attempts = mutableListOf<Attempt>()
        val attemptLog = mutableListOf<Pair<String, String>>()

        while (true) {
            when (val action = CoderHarness.decide(attempts, elapsed())) {
                is Action.Done -> {
                    val code = attempts.last().code
                    CodeRunState.update {
                        it.copy(
                            phase = CodeRunState.Phase.DONE, result = action.output,
                            turns = it.turns + CodeRunState.Turn(
                                instruction, code, action.output, attempts.size, attemptLog.toList()
                            ),
                        )
                    }
                    Notifications.done(this, "Result ready")
                    return
                }
                is Action.Fail -> { fail(action.reason, attemptLog.toList()); return }
                is Action.Generate -> {
                    val n = attempts.size + 1
                    CodeRunState.update {
                        it.copy(
                            phase = if (n == 1) CodeRunState.Phase.GENERATING else CodeRunState.Phase.FIXING,
                            attempt = n, streamed = "", tokenCount = 0,
                        )
                    }
                    notify(if (n == 1) "Writing script…" else "Fixing an error (attempt $n/${CoderHarness.MAX_ATTEMPTS})…")

                    // Prefer the formatted body; fall back to the raw transcript.
                    val body = note.markdown.ifBlank { note.text }
                    val prompt = buildPrompt(note.title, body, instruction, attempts, turns)
                    val reply = CoderEngine.generate(prompt, CoderHarness.MAX_GEN_TOKENS) { piece ->
                        CodeRunState.update {
                            it.copy(streamed = it.streamed + piece, tokenCount = it.tokenCount + 1)
                        }
                    }.getOrElse { e ->
                        // Native-level failure (not a bad script) — retrying won't help.
                        fail(e.message ?: "Generation failed"); return
                    }

                    val code = CoderHarness.extractCodeFence(reply)
                    if (code == null) {
                        attempts += Attempt(code = "", gateError = "reply contained no code block")
                        attemptLog += "" to "reply contained no code block"
                        continue
                    }
                    when (val scan = CoderHarness.scanImports(code)) {
                        is CoderHarness.ImportScan.Blocked -> {
                            attempts += Attempt(code = code, gateError = "uses disallowed ${scan.what}")
                            attemptLog += code to "uses disallowed ${scan.what}"
                            continue
                        }
                        CoderHarness.ImportScan.Allowed -> Unit
                    }

                    CodeRunState.update { it.copy(phase = CodeRunState.Phase.RUNNING) }
                    notify("Running script…")
                    val res = PyRunnerClient.exec(this, code, CoderHarness.EXEC_TIMEOUT_MS)
                    val emptyOk = res.ok && res.stdout.isBlank()
                    attempts += Attempt(
                        code = code, stdout = res.stdout, stderr = res.stderr,
                        ok = res.ok && !emptyOk, timedOut = res.timedOut,
                        gateError = if (emptyOk) "script printed nothing to stdout" else null,
                    )
                    attemptLog += code to when {
                        res.ok && !emptyOk -> ""
                        emptyOk -> "printed nothing"
                        res.timedOut -> "timed out after ${CoderHarness.EXEC_TIMEOUT_MS / 1000}s"
                        else -> CoderHarness.summarizeTraceback(res.stderr).ifBlank { res.stderr.take(200) }
                    }
                }
            }
        }
    }

    /** Repair rounds use the note+task with last code+error; follow-ups thread the prior turn. */
    private fun buildPrompt(
        title: String, body: String, instruction: String,
        attempts: List<Attempt>, priorTurns: List<CodeRunState.Turn>,
    ): String {
        val last = attempts.lastOrNull()
        return when {
            last != null -> CoderHarness.buildRepairPrompt(
                title, body, instruction, last.code, CoderHarness.errorKey(last)
            )
            priorTurns.isNotEmpty() -> priorTurns.last().let { t ->
                CoderHarness.buildFollowUpPrompt(title, body, t.instruction, t.code, t.output, instruction)
            }
            else -> CoderHarness.buildFirstPrompt(title, body, instruction)
        }
    }

    /** Details screen's "run edited script": user-authored code skips generation, keeps the gates. */
    private suspend fun rerunEdited(code: String) {
        when (val scan = CoderHarness.scanImports(code)) {
            is CoderHarness.ImportScan.Blocked -> { fail("Edited script uses disallowed ${scan.what}"); return }
            CoderHarness.ImportScan.Allowed -> Unit
        }
        CodeRunState.update { it.copy(phase = CodeRunState.Phase.RUNNING) }
        notify("Running edited script…")
        val res = PyRunnerClient.exec(this, code, CoderHarness.EXEC_TIMEOUT_MS)
        if (res.ok && res.stdout.isNotBlank()) {
            CodeRunState.update {
                val turns = it.turns.toMutableList()
                val last = turns.removeLastOrNull()
                turns += CodeRunState.Turn(
                    last?.instruction ?: "edited script", code, res.stdout,
                    (last?.attempts ?: 0) + 1,
                    (last?.attemptLog ?: emptyList()) + (code to ""),
                )
                it.copy(phase = CodeRunState.Phase.DONE, result = res.stdout, turns = turns)
            }
            Notifications.done(this, "Result ready")
        } else {
            fail(
                if (res.timedOut) "Edited script timed out"
                else CoderHarness.summarizeTraceback(res.stderr).ifBlank { "Edited script produced no output" }
            )
        }
    }

    private fun cancelRun() {
        runJob?.cancel()
        CoderEngine.cancelGeneration()
        PyRunnerClient.kill(this)
        CodeRunState.update { it.copy(phase = CodeRunState.Phase.FAILED, result = "Cancelled") }
        notify("Cancelled")
    }

    private fun endSession() {
        runJob?.cancel()
        CoderEngine.cancelGeneration()
        PyRunnerClient.kill(this)
        CoderEngine.endSession()
        CodeRunState.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(reason: String, attemptLog: List<Pair<String, String>> = emptyList()) {
        Log.w(TAG, "run failed: $reason")
        CodeRunState.update {
            it.copy(phase = CodeRunState.Phase.FAILED, result = reason, failedAttempts = attemptLog)
        }
        Notifications.done(this, "Coding failed — $reason")
    }

    private fun notify(text: String) {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(Notifications.CODER_ID, Notifications.ongoing(this, text))
    }

    override fun onDestroy() {
        CoderEngine.endSession()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CoderRunService"
        const val ACTION_RUN = "com.thoughtpocket.coder.RUN"
        const val ACTION_FOLLOWUP = "com.thoughtpocket.coder.FOLLOWUP"
        const val ACTION_CANCEL = "com.thoughtpocket.coder.CANCEL"
        const val ACTION_END = "com.thoughtpocket.coder.END"
        const val ACTION_RERUN_EDITED = "com.thoughtpocket.coder.RERUN_EDITED"
        private const val EXTRA_NOTE_ID = "noteId"
        private const val EXTRA_INSTRUCTION = "instruction"
        private const val EXTRA_CODE = "code"

        fun run(context: Context, noteId: Long, instruction: String) =
            context.startForegroundService(
                Intent(context, CoderRunService::class.java).setAction(ACTION_RUN)
                    .putExtra(EXTRA_NOTE_ID, noteId).putExtra(EXTRA_INSTRUCTION, instruction)
            )

        fun followUp(context: Context, instruction: String) =
            context.startForegroundService(
                Intent(context, CoderRunService::class.java).setAction(ACTION_FOLLOWUP)
                    .putExtra(EXTRA_INSTRUCTION, instruction)
            )

        fun rerunEdited(context: Context, code: String) = context.startForegroundService(
            Intent(context, CoderRunService::class.java).setAction(ACTION_RERUN_EDITED)
                .putExtra(EXTRA_CODE, code)
        )

        fun cancel(context: Context) = context.startForegroundService(
            Intent(context, CoderRunService::class.java).setAction(ACTION_CANCEL)
        )

        fun end(context: Context) = context.startForegroundService(
            Intent(context, CoderRunService::class.java).setAction(ACTION_END)
        )
    }
}
