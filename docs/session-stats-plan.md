# Plan: Session Stats (duration · word count · words-per-minute)

Status: proposal · Owner: TBD · Created: 2026-06-24
Prompted by: Google AI Edge Eloquent's per-session stats (WPM, word count).

## Goal

Show lightweight per-recording stats on a note: **duration**, **word count**, **words-per-minute**.
Small delight + a sense of "I just braindumped N words." Fully derivable on-device, no LLM.

## What we already have (no guesswork needed)

- **Exact audio duration** is free: `MicRecorder.samples` (16 kHz mono) → `samples / 16_000` seconds.
  The clip already carries the sample count — `startRecording` reads `rec.samples` and queues it
  (`service/RecordingService.kt:148-153`). This is more accurate than wall-clock
  (`RecordState.Status.startedAtElapsedRealtime`, `service/RecordState.kt:13`), which includes
  start/stop lag — use the sample count.
- **Final transcript** is the saved `Note.text` (set in `transcribeAndSave`, `:233`). Word count =
  whitespace-split token count of the final body. WPM = `words / (durationSeconds / 60)`.
- The note **list card** and **detail header** already render title/date/tags
  (`ui/NotesScreens.kt`, NoteCard ~`:450-496`, detail ~`:870-909`) — natural places to surface stats.

## Design decision: store the stats, don't recompute

Compute once at save time and persist on the note. Reasons: duration **cannot** be recovered after
the fact (the `.pcm` is deleted once the transcript is saved, `:235`); appended notes
(`appendToNote`, `:269`) accumulate multiple recordings, so a single stored counter is the only
correct source. Word count *could* be recomputed from `text`, but storing it keeps the card render
cheap and keeps WPM consistent with the duration it was measured against.

### Schema change

Add to the `Note` entity (`data/Note.kt:22-33`):
- `durationMs: Long = 0` (sum across appends)
- `wordCount: Int = 0`

WPM is derived at render time (`wordCount / (durationMs/60000)`), not stored — it's just the ratio.

Migration: the entity is at version 4 with an established additive-migration pattern
(`MIGRATION_1_2` title, `MIGRATION_2_3` embedding, `MIGRATION_3_4` markdown, `data/Note.kt:85,93-109`).
Add `MIGRATION_4_5` = `ALTER TABLE notes ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0` (+
`wordCount`), bump `@Database(version = 5)`. Old notes default to 0 → render hides stats when 0 (see
below).

### Where the numbers get set

- New note (`transcribeAndSave`, `:233-234`): set `durationMs = clip.samples * 1000L / 16_000`,
  `wordCount = body.trim().split(Regex("\\s+")).count { it.isNotBlank() }` on the `Note(...)` it
  inserts.
- Append (`appendToNote`, `:277-279`): `durationMs += newClipMs`, recompute `wordCount` from the
  combined text. The clip already knows its samples; thread it through like the transcript is.

## UI

- **List card** (`ui/NotesScreens.kt` ~`:450-496`): one muted line under the tags, e.g.
  `1:24 · 213 words · 152 wpm`. Only when `durationMs > 0` (old/imported notes show nothing — no
  empty "0 wpm").
- **Detail** (~`:870-909`): same stat line under the title, or a small stats chip row.
- Format duration `m:ss`; WPM rounded to int. Pure formatter → JVM unit test.

## Validation / success criteria

- A 60 s recording of ~150 spoken words shows ≈150 wpm (±tokenization noise).
- Imported audio files and pre-migration notes show **no** stats (gracefully absent), never `0 wpm`.
- Appended notes sum duration and re-total words across all segments.
- Stats survive the notes↔folder sync round-trip *or* are explicitly recomputed on import — decide:
  the folder/markdown file format (`NoteFile`/`NotesFolderIo`) may not carry duration, so a synced-in
  note may legitimately have `durationMs = 0` and just omit stats. That's acceptable (treat like
  imported audio). Flag in review whether to persist stats into the file front-matter.

## Scope

Small. One entity change + migration, two assignment sites in `RecordingService`, one render line
(card + detail), one pure formatter + its test.

## Out of scope / optional

- **Filler-word count** (Eloquent strips "um/uh"): our Whisper output isn't reliably verbatim and we
  don't currently count or strip conversational fillers — skip unless there's demand; it's a separate,
  fuzzier feature, not a free derivation.
- Aggregate/all-time stats dashboards (total words this week, streaks) — possible later from the
  stored per-note fields, but not asked for.
