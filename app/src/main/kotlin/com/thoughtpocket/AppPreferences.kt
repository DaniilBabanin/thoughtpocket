package com.thoughtpocket

import android.content.Context
import androidx.core.content.edit

class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("thoughtpocket", Context.MODE_PRIVATE)

    init { migrateTwoPassPrefs() }

    /**
     * Stable id of the selected model: "builtin:BASE_Q5" or "custom:<filename>".
     * Migrates legacy values that stored the bare enum name (e.g. "BASE_Q5").
     */
    var selectedModelId: String
        get() {
            val raw = prefs.getString(KEY_MODEL, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
            return when {
                raw.startsWith("builtin:") || raw.startsWith("custom:") -> raw
                runCatching { ModelManager.BuiltInModel.valueOf(raw) }.isSuccess -> "builtin:$raw"
                else -> DEFAULT_MODEL_ID
            }
        }
        set(value) = prefs.edit { putString(KEY_MODEL, value) }

    /** Empty = auto-detect. Otherwise ISO-639-1 code like "en", "de". */
    var language: String
        get() = prefs.getString(KEY_LANG, "")!!
        set(value) = prefs.edit { putString(KEY_LANG, value) }

    var translateToEnglish: Boolean
        get() = prefs.getBoolean(KEY_TRANSLATE, false)
        set(value) = prefs.edit { putBoolean(KEY_TRANSLATE, value) }

    var useGpu: Boolean
        get() = prefs.getBoolean(KEY_GPU, DEFAULT_USE_GPU)
        set(value) = prefs.edit { putBoolean(KEY_GPU, value) }

    /** 0 = "auto" (cores/2, min 2). Otherwise honoured verbatim. */
    var threads: Int
        get() = prefs.getInt(KEY_THREADS, 0)
        set(value) = prefs.edit { putInt(KEY_THREADS, value.coerceIn(0, 16)) }

    /** Beam search instead of greedy: slower but more accurate on noisy/accented audio. */
    var highQuality: Boolean
        get() = prefs.getBoolean(KEY_HIGH_QUALITY, false)
        set(value) = prefs.edit { putBoolean(KEY_HIGH_QUALITY, value) }

    /** Filename of the selected on-device LLM (Gemma) model in the llm/ dir; "" = first found. */
    var llmModelFilename: String
        get() = prefs.getString(KEY_LLM_MODEL, "")!!
        set(value) = prefs.edit { putString(KEY_LLM_MODEL, value) }

    /** Auto-tag new notes with the LLM right after transcription. */
    var autoTag: Boolean
        get() = prefs.getBoolean(KEY_AUTO_TAG, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_TAG, value) }

    /** Auto-format new notes as Markdown (lists/checklists) right after transcription. */
    var autoMarkdown: Boolean
        get() = prefs.getBoolean(KEY_AUTO_MARKDOWN, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_MARKDOWN, value) }

    /**
     * When recordings are appended to an existing note, append the raw transcript only — then once they
     * finish, reformat the note's Markdown and refresh its tags in one pass. On by default.
     */
    var reformatAppendedNotes: Boolean
        get() = prefs.getBoolean(KEY_REFORMAT_APPENDED, true)
        set(value) = prefs.edit { putBoolean(KEY_REFORMAT_APPENDED, value) }

    /**
     * Transcribe progressively while recording (live preview). On by default. Turning it off saves
     * battery/heat and avoids re-transcribing a growing buffer on long recordings.
     *
     * Legacy: superseded by [firstPassEnabled] (the two-pass model). Kept only as the migration source and
     * still read here so a pre-migration value seeds the first pass; new code uses [firstPassEnabled].
     */
    var liveTranscription: Boolean
        get() = prefs.getBoolean(KEY_LIVE_TRANSCRIBE, true)
        set(value) = prefs.edit { putBoolean(KEY_LIVE_TRANSCRIBE, value) }

    // ---- Two-pass transcription (instant first pass + quality final pass) ----
    // Each pass has its own on/off + model. Migration (see [migrateTwoPassPrefs]) preserves today's
    // behavior: final on with the old model; first mirrors the old liveTranscription, same model.

    /** Instant/live first pass — streams a windowed preview while recording (and IS the note in first-only). */
    var firstPassEnabled: Boolean
        get() = prefs.getBoolean(KEY_FIRST_ENABLED, liveTranscription)
        set(value) = prefs.edit { putBoolean(KEY_FIRST_ENABLED, value) }

    /** Model id for the first pass (a STREAMING-eligible entry: windowed-Whisper or Moonshine). */
    var firstPassModelId: String
        get() = prefs.getString(KEY_FIRST_MODEL, selectedModelId) ?: selectedModelId
        set(value) = prefs.edit { putString(KEY_FIRST_MODEL, value) }

    /** Quality final pass — one batch transcribe on stop; owns the durable note when enabled. */
    var finalPassEnabled: Boolean
        get() = prefs.getBoolean(KEY_FINAL_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_FINAL_ENABLED, value) }

    /** Model id for the final pass (a BATCH-eligible entry: a Whisper variant). */
    var finalPassModelId: String
        get() = prefs.getString(KEY_FINAL_MODEL, selectedModelId) ?: selectedModelId
        set(value) = prefs.edit { putString(KEY_FINAL_MODEL, value) }

    /** One-shot: seed the four two-pass keys from the legacy single-model + live-preview prefs. */
    private fun migrateTwoPassPrefs() {
        if (prefs.getBoolean(KEY_TWOPASS_MIGRATED, false)) return
        val c = migrateTwoPass(selectedModelId, liveTranscription)
        prefs.edit {
            putBoolean(KEY_FIRST_ENABLED, c.firstEnabled)
            putString(KEY_FIRST_MODEL, c.firstModelId)
            putBoolean(KEY_FINAL_ENABLED, c.finalEnabled)
            putString(KEY_FINAL_MODEL, c.finalModelId)
            putBoolean(KEY_TWOPASS_MIGRATED, true)
        }
    }

    /** Show the live transcription in the ongoing notification (visible from the shade). Off by default. */
    var liveTranscribeNotification: Boolean
        get() = prefs.getBoolean(KEY_LIVE_NOTIF, false)
        set(value) = prefs.edit { putBoolean(KEY_LIVE_NOTIF, value) }

    /** Save every recording as a WAV into [saveAudioFolder] (a picked SAF folder that survives uninstall). */
    var saveAudio: Boolean
        get() = prefs.getBoolean(KEY_SAVE_AUDIO, false)
        set(value) = prefs.edit { putBoolean(KEY_SAVE_AUDIO, value) }

    /** Persisted SAF tree URI to save recordings into; "" = none picked. */
    var saveAudioFolder: String
        get() = prefs.getString(KEY_SAVE_FOLDER, "")!!
        set(value) = prefs.edit { putString(KEY_SAVE_FOLDER, value) }

    /** Persisted SAF tree URI to import audio FROM (separate from the save folder); "" = none. */
    var importFolder: String
        get() = prefs.getString(KEY_IMPORT_FOLDER, "")!!
        set(value) = prefs.edit { putString(KEY_IMPORT_FOLDER, value) }

    /** Two-way mirror notes (one .md each) to a folder, e.g. a Nextcloud-synced dir. Off by default. */
    var syncEnabled: Boolean
        get() = prefs.getBoolean(KEY_SYNC_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_SYNC_ENABLED, value) }

    /** Persisted SAF tree URI to sync notes into; "" = none picked. */
    var syncFolder: String
        get() = prefs.getString(KEY_SYNC_FOLDER, "")!!
        set(value) = prefs.edit { putString(KEY_SYNC_FOLDER, value) }

    /** URIs of audio files already imported (dedup), so a re-scan doesn't re-transcribe them. */
    var importedAudio: Set<String>
        get() = prefs.getStringSet(KEY_IMPORTED, emptySet())!!
        set(value) = prefs.edit { putStringSet(KEY_IMPORTED, value) }

    /** Disable non-essential motion (card reveals, glow parallax, pulse). Off by default. */
    var reduceAnimations: Boolean
        get() = prefs.getBoolean(KEY_REDUCE_MOTION, false)
        set(value) = prefs.edit { putBoolean(KEY_REDUCE_MOTION, value) }

    /** Per-task model overrides (filename in llm/ dir); "" = smart default. */
    var tagModelFilename: String
        get() = prefs.getString(KEY_TAG_MODEL, "")!!
        set(value) = prefs.edit { putString(KEY_TAG_MODEL, value) }

    var analysisModelFilename: String
        get() = prefs.getString(KEY_ANALYSIS_MODEL, "")!!
        set(value) = prefs.edit { putString(KEY_ANALYSIS_MODEL, value) }

    /** Optional HuggingFace token for downloading licence-gated Gemma models. */
    var hfToken: String
        get() = prefs.getString(KEY_HF_TOKEN, "")!!
        set(value) = prefs.edit { putString(KEY_HF_TOKEN, value.trim()) }

    /** Reveals advanced controls (thread slider, benchmark). Toggled by tapping the home tip. */
    var developerMode: Boolean
        get() = prefs.getBoolean(KEY_DEV_MODE, false)
        set(value) = prefs.edit { putBoolean(KEY_DEV_MODE, value) }

    /** Developer-only escape hatch: bypass SHA-256 check on model downloads. */
    var skipModelVerification: Boolean
        get() = prefs.getBoolean(KEY_SKIP_VERIFY, false)
        set(value) = prefs.edit { putBoolean(KEY_SKIP_VERIFY, value) }

    /** One-shot flag: set when a previous GPU run crashed; cleared once observed. */
    var gpuCrashedNotice: Boolean
        get() = prefs.getBoolean(KEY_GPU_CRASHED, false)
        set(value) = prefs.edit { putBoolean(KEY_GPU_CRASHED, value) }

    /** Called on app start when the GPU crumb is found. Force CPU and arm the notice. */
    fun onGpuCrashed() {
        // Use commit() so it's durable before the user retries anything.
        prefs.edit().putBoolean(KEY_GPU, false).putBoolean(KEY_GPU_CRASHED, true).commit()
    }

    fun resolvedThreads(): Int =
        if (threads > 0) threads
        else (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)

    companion object {
        private const val KEY_MODEL = "model"
        private const val KEY_LANG = "language"
        private const val KEY_TRANSLATE = "translate"
        private const val KEY_GPU = "use_gpu"
        private const val KEY_THREADS = "threads"
        private const val KEY_HIGH_QUALITY = "high_quality"
        private const val KEY_LLM_MODEL = "llm_model"
        private const val KEY_AUTO_TAG = "auto_tag"
        private const val KEY_AUTO_MARKDOWN = "auto_markdown"
        private const val KEY_REFORMAT_APPENDED = "reformat_appended_notes"
        private const val KEY_LIVE_TRANSCRIBE = "live_transcription"
        private const val KEY_LIVE_NOTIF = "live_transcribe_notification"
        private const val KEY_FIRST_ENABLED = "first_pass_enabled"
        private const val KEY_FIRST_MODEL = "first_pass_model"
        private const val KEY_FINAL_ENABLED = "final_pass_enabled"
        private const val KEY_FINAL_MODEL = "final_pass_model"
        private const val KEY_TWOPASS_MIGRATED = "two_pass_migrated"
        private const val KEY_SAVE_AUDIO = "save_audio"
        private const val KEY_SAVE_FOLDER = "save_audio_folder"
        private const val KEY_IMPORT_FOLDER = "import_folder"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_SYNC_FOLDER = "sync_folder"
        private const val KEY_IMPORTED = "imported_audio"
        private const val KEY_REDUCE_MOTION = "reduce_animations"
        private const val KEY_TAG_MODEL = "tag_model"
        private const val KEY_ANALYSIS_MODEL = "analysis_model"
        private const val KEY_HF_TOKEN = "hf_token"
        private const val KEY_DEV_MODE = "developer_mode"
        private const val KEY_SKIP_VERIFY = "skip_model_verification"
        private const val KEY_GPU_CRASHED = "gpu_crashed_notice"

        private const val DEFAULT_MODEL_ID = "builtin:BASE_EN_Q5"

        // Off by default — Vulkan support varies by GPU (Mali on Pixel 9 has been
        // known to abort mid-inference). Power users can enable it from settings.
        private const val DEFAULT_USE_GPU = false
    }
}

/** The two-pass settings derived for a user (first = instant/live, final = quality/batch). */
data class TwoPassConfig(
    val firstEnabled: Boolean,
    val firstModelId: String,
    val finalEnabled: Boolean,
    val finalModelId: String,
)

/**
 * Migrate the legacy single-model + live-preview prefs to the two-pass model, preserving today's behavior:
 * the final (Whisper batch) pass stays on with the old model; the first (live preview) pass mirrors the old
 * [liveTranscription] toggle and reuses the same model (windowed-Whisper — no extra download needed).
 */
fun migrateTwoPass(selectedModelId: String, liveTranscription: Boolean) = TwoPassConfig(
    firstEnabled = liveTranscription,
    firstModelId = selectedModelId,
    finalEnabled = true,
    finalModelId = selectedModelId,
)

/**
 * Enforce the invariant "at least one pass is enabled". Given the user's desired flags, if BOTH would be
 * off, keep one on ([keepFinalOnConflict] picks which). Returns the coerced (first, final) pair. The Settings
 * UI calls this so toggling off the last active pass is a no-op rather than leaving transcription dead.
 */
fun coercePasses(first: Boolean, final: Boolean, keepFinalOnConflict: Boolean = true): Pair<Boolean, Boolean> =
    when {
        first || final -> first to final
        keepFinalOnConflict -> false to true
        else -> true to false
    }
