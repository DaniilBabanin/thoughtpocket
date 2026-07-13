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
import com.thoughtpocket.data.CodeRun
import com.thoughtpocket.data.NotesDb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import android.os.SystemClock
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Drives one coder session (dataSync foreground, main process): begin session →
 * generate → gate → execute in the :coder process → diagnose → repair → repeat,
 * publishing progress through [CodeRunState] only — the UI never touches this
 * service (RecordState convention). The session (and the 5.6 GB model) stays
 * warm across follow-ups; it ends via ACTION_END (screen exited while idle)
 * or the idle timer (run finished but nobody came back — back/home mid-run
 * intentionally does NOT kill the run, it finishes in the background).
 *
 * dataSync vs specialUse: dataSync is the type this app already uses for long
 * background work and a run's ~20 min cap stays under Android 15's cumulative
 * limit; revisit as specialUse if runs ever approach the timeout.
 */
class CoderRunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runJob: Job? = null
    private var idleJob: Job? = null

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
                val allNotes = intent.getBooleanExtra(EXTRA_ALL_NOTES, false)
                if (noteId >= 0 && instruction.isNotBlank() && runJob?.isActive != true) {
                    idleJob?.cancel()
                    runJob = scope.launch { runOnce(noteId, instruction, iterateRunId = null, allNotes) }
                }
            }
            ACTION_FOLLOWUP -> {
                val instruction = intent.getStringExtra(EXTRA_INSTRUCTION).orEmpty()
                val runId = intent.getLongExtra(EXTRA_RUN_ID, -1)
                if (runId >= 0 && instruction.isNotBlank() && runJob?.isActive != true) {
                    idleJob?.cancel()
                    runJob = scope.launch {
                        // The run row is the durable source of its note — the
                        // in-memory state may have been reset since the last run.
                        val base = NotesDb.get(this@CoderRunService).codeRuns().getById(runId)
                        if (base == null) { fail("Coding item not found"); scheduleIdleEnd() }
                        else runOnce(base.noteId, instruction, iterateRunId = runId)
                    }
                }
            }
            ACTION_RERUN_EDITED -> {
                val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
                val runId = intent.getLongExtra(EXTRA_RUN_ID, -1)
                if (code.isNotBlank() && runId >= 0 && runJob?.isActive != true) {
                    idleJob?.cancel()
                    runJob = scope.launch { rerunEdited(runId, code) }
                }
            }
            ACTION_CANCEL -> cancelRun()
            ACTION_END -> endSession()
        }
        return START_NOT_STICKY
    }

    /**
     * The model stays warm between runs (loading 5.6 GB takes ~a minute), but
     * nothing may pin it forever — the screen only ends the session when idle,
     * and if the user walked away mid-run (back/home) nobody ends it at all.
     * So: every run completion arms this timer; every new run disarms it.
     */
    private fun scheduleIdleEnd() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            endSession()
        }
    }

    /**
     * [iterateRunId] null = new coding item; set = follow-up updating that item
     * in place. [allNotesRequested] only applies to a NEW item — follow-ups
     * inherit the access their item was created with.
     */
    private suspend fun runOnce(noteId: Long, instruction: String, iterateRunId: Long?, allNotesRequested: Boolean = false) {
        try {
            runLoop(noteId, instruction, iterateRunId, allNotesRequested)
        } catch (e: CancellationException) {
            throw e // endSession/cancel owns the teardown — don't arm the idle timer
        } catch (e: Exception) {
            // e.g. the note was deleted mid-run (FK on insert) — a failed run,
            // not an app crash.
            Log.e(TAG, "run crashed", e)
            fail(e.message ?: "Unexpected error")
        }
        scheduleIdleEnd()
    }

    private suspend fun runLoop(noteId: Long, instruction: String, iterateRunId: Long?, allNotesRequested: Boolean) {
        val startMs = SystemClock.elapsedRealtime()
        fun elapsed() = SystemClock.elapsedRealtime() - startMs

        val note = NotesDb.get(this).notes().getById(noteId) ?: run {
            fail("Note not found"); return
        }
        val runsDao = NotesDb.get(this).codeRuns()
        val baseRun = iterateRunId?.let { runsDao.getById(it) }
        CodeRunState.update {
            it.copy(phase = CodeRunState.Phase.STARTING, noteId = noteId,
                activeRunId = iterateRunId ?: -1, attempt = 0,
                streamed = "", tokenCount = 0, result = "")
        }

        if (!CoderEngine.inSession) {
            notify("Loading coding model…")
            CoderEngine.beginSession(this).onFailure { e ->
                fail(e.message ?: "Couldn't load the coding model"); return
            }
        }

        val allNotes = baseRun?.allNotes ?: allNotesRequested
        val notesPath = if (allNotes) writeNotesSnapshot() else ""
        val attempts = mutableListOf<Attempt>()
        val attemptLog = mutableListOf<Pair<String, String>>()

        while (true) {
            when (val action = CoderHarness.decide(attempts, elapsed())) {
                is Action.Done -> {
                    val code = attempts.last().code
                    // Persist: iterate updates its row (originalCode moves with the
                    // model's fresh script — revert targets model output, not history);
                    // a new prompt inserts a new item.
                    val savedId = if (baseRun != null) {
                        runsDao.update(baseRun.copy(
                            instruction = "${baseRun.instruction} → $instruction",
                            code = code, originalCode = code,
                            output = action.output,
                            attempts = baseRun.attempts + attempts.size,
                        ))
                        baseRun.id
                    } else {
                        runsDao.insert(CodeRun(
                            noteId = noteId, createdAt = System.currentTimeMillis(),
                            instruction = instruction, code = code, originalCode = code,
                            output = action.output, attempts = attempts.size,
                            allNotes = allNotes,
                        ))
                    }
                    CodeRunState.update {
                        it.copy(phase = CodeRunState.Phase.DONE, result = action.output, activeRunId = savedId)
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
                    val prompt = buildPrompt(note.title, body, instruction, attempts, baseRun, allNotes)
                    // Stuck pair → one sampled attempt; greedy otherwise.
                    val temperature = if (action.sampled) 0.6f else 0f
                    val reply = CoderEngine.generate(prompt, CoderHarness.MAX_GEN_TOKENS, temperature) { piece ->
                        CodeRunState.update {
                            it.copy(streamed = it.streamed + piece, tokenCount = it.tokenCount + 1)
                        }
                    }.getOrElse { e ->
                        // Native-level failure (not a bad script) — retrying won't help.
                        fail(e.message ?: "Generation failed"); return
                    }

                    val code = CoderHarness.extractCodeFence(reply)
                    if (code == null) {
                        val gate = CoderHarness.fenceGateError(reply)
                        attempts += Attempt(code = "", gateError = gate)
                        attemptLog += "" to gate
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
                    val res = PyRunnerClient.exec(this, code, CoderHarness.EXEC_TIMEOUT_MS, notesPath)
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

    /** Repair rounds use the note+task with last code+error; iterating threads the base item. */
    private fun buildPrompt(
        title: String, body: String, instruction: String,
        attempts: List<Attempt>, baseRun: CodeRun?, allNotes: Boolean,
    ): String {
        val last = attempts.lastOrNull()
        val user = when {
            last != null -> CoderHarness.repairUser(
                title, body, instruction, last.code, CoderHarness.errorKey(last)
            )
            baseRun != null -> CoderHarness.followUpUser(
                title, body, baseRun.instruction, baseRun.code, baseRun.output, instruction
            )
            else -> CoderHarness.firstUser(title, body, instruction)
        }
        // Model's own chat template (BYO models get their native format);
        // ChatML fallback for GGUFs that ship none.
        return CoderEngine.formatPrompt(CoderHarness.system(allNotes), user) ?: CoderHarness.chatml(user, allNotes)
    }

    /** "Run edited script": user-authored code skips generation, keeps the gates; updates its item. */
    private suspend fun rerunEdited(runId: Long, code: String) {
        try {
            rerunEditedInner(runId, code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "rerun crashed", e)
            fail(e.message ?: "Unexpected error")
        }
        scheduleIdleEnd()
    }

    private suspend fun rerunEditedInner(runId: Long, code: String) {
        val runsDao = NotesDb.get(this).codeRuns()
        val run = runsDao.getById(runId) ?: run { fail("Coding item not found"); return }
        when (val scan = CoderHarness.scanImports(code)) {
            is CoderHarness.ImportScan.Blocked -> { fail("Edited script uses disallowed ${scan.what}"); return }
            CoderHarness.ImportScan.Allowed -> Unit
        }
        CodeRunState.update {
            it.copy(phase = CodeRunState.Phase.RUNNING, noteId = run.noteId, activeRunId = runId, result = "")
        }
        notify("Running edited script…")
        val res = PyRunnerClient.exec(
            this, code, CoderHarness.EXEC_TIMEOUT_MS,
            if (run.allNotes) writeNotesSnapshot() else "",
        )
        if (res.ok && res.stdout.isNotBlank()) {
            // originalCode untouched — that's what "revert" restores.
            runsDao.update(run.copy(code = code, output = res.stdout, attempts = run.attempts + 1))
            CodeRunState.update { it.copy(phase = CodeRunState.Phase.DONE, result = res.stdout) }
            Notifications.done(this, "Result ready")
        } else {
            fail(
                if (res.timedOut) "Edited script timed out"
                else CoderHarness.summarizeTraceback(res.stderr).ifBlank { "Edited script produced no output" }
            )
        }
    }

    /**
     * Serialize every note to a private cache file the runner loads into a
     * `notes` global — enables "meta" tasks spanning all notes (count TODOs
     * across notes, etc.). Only called when the item was granted "access all
     * notes"; otherwise the runner gets no path and `notes` stays empty. Via a
     * file, not the IPC Bundle, so a big library can't blow the Binder
     * transaction limit. The script can't open files (gate), only read the
     * injected global.
     */
    private suspend fun writeNotesSnapshot(): String {
        val arr = JSONArray()
        for (n in NotesDb.get(this).notes().allOnce()) {
            arr.put(JSONObject().apply {
                put("id", n.id)
                put("title", n.title)
                put("text", n.text)
                put("markdown", n.markdown)
                put("tags", JSONArray(n.tags))
                put("created", n.createdAt)
            })
        }
        val f = File(cacheDir, "coder_notes.json")
        f.writeText(arr.toString())
        return f.absolutePath
    }

    private fun cancelRun() {
        runJob?.cancel()
        CoderEngine.cancelGeneration()
        PyRunnerClient.kill(this)
        CodeRunState.update { it.copy(phase = CodeRunState.Phase.FAILED, result = "Cancelled") }
        notify("Cancelled")
        scheduleIdleEnd() // session stays warm for a retry, but never forever
    }

    private fun endSession() {
        idleJob?.cancel()
        CoderEngine.cancelGeneration()
        PyRunnerClient.kill(this)
        val job = runJob
        scope.launch {
            // Free the model only after the in-flight generate has returned —
            // releasing under a live llama_decode is a use-after-free. The
            // abort callback bounds this join to milliseconds.
            job?.cancelAndJoin()
            CoderEngine.endSession()
            CodeRunState.reset()
            ServiceCompat.stopForeground(this@CoderRunService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
        idleJob?.cancel()
        CoderEngine.cancelGeneration()
        // System-initiated destroy can land mid-generation; same UAF rule as
        // endSession. The abort callback keeps this join to milliseconds.
        runBlocking { runJob?.cancelAndJoin() }
        CoderEngine.endSession()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CoderRunService"
        /** Warm-model grace after a run; reload costs ~a minute, RAM costs ~6 GB. */
        private const val IDLE_TIMEOUT_MS = 10 * 60_000L
        const val ACTION_RUN = "com.thoughtpocket.coder.RUN"
        const val ACTION_FOLLOWUP = "com.thoughtpocket.coder.FOLLOWUP"
        const val ACTION_CANCEL = "com.thoughtpocket.coder.CANCEL"
        const val ACTION_END = "com.thoughtpocket.coder.END"
        const val ACTION_RERUN_EDITED = "com.thoughtpocket.coder.RERUN_EDITED"
        private const val EXTRA_NOTE_ID = "noteId"
        private const val EXTRA_INSTRUCTION = "instruction"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_RUN_ID = "runId"
        private const val EXTRA_ALL_NOTES = "allNotes"

        fun run(context: Context, noteId: Long, instruction: String, allNotes: Boolean = false) =
            context.startForegroundService(
                Intent(context, CoderRunService::class.java).setAction(ACTION_RUN)
                    .putExtra(EXTRA_NOTE_ID, noteId).putExtra(EXTRA_INSTRUCTION, instruction)
                    .putExtra(EXTRA_ALL_NOTES, allNotes)
            )

        fun followUp(context: Context, runId: Long, instruction: String) =
            context.startForegroundService(
                Intent(context, CoderRunService::class.java).setAction(ACTION_FOLLOWUP)
                    .putExtra(EXTRA_RUN_ID, runId).putExtra(EXTRA_INSTRUCTION, instruction)
            )

        fun rerunEdited(context: Context, runId: Long, code: String) = context.startForegroundService(
            Intent(context, CoderRunService::class.java).setAction(ACTION_RERUN_EDITED)
                .putExtra(EXTRA_RUN_ID, runId).putExtra(EXTRA_CODE, code)
        )

        fun cancel(context: Context) = context.startForegroundService(
            Intent(context, CoderRunService::class.java).setAction(ACTION_CANCEL)
        )

        fun end(context: Context) = context.startForegroundService(
            Intent(context, CoderRunService::class.java).setAction(ACTION_END)
        )
    }
}
