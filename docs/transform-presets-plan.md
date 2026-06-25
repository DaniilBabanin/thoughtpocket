# Plan: One-Tap Transform Presets (Key Points / Formal / Short / Long)

Status: proposal · Owner: TBD · Created: 2026-06-24
Prompted by: Google AI Edge Eloquent's one-tap "Key points / Formal / Short / Long" transforms.

## Goal

Four one-tap buttons on a note that rewrite its body via the on-device Gemma LLM:
**Key Points** (bullets), **Formal** (professional tone), **Short** (condense), **Long** (expand).
Single-tap undo. Fully on-device.

## Chosen UX: preset buttons pre-fill the existing Interact command box

Rather than a separate UI, the preset buttons **pre-fill the existing Interact command field** with a
natural-language instruction and run it through the command flow that already exists.
(Per the owner's steer: "could just have buttons that pre-fill instructions for the presets.")

Why this is the right call:
- The note-detail Interact command field, send action, AI-error display, and **single-tap undo
  snapshot are already built** (`ui/NotesScreens.kt`: `runCommand()` ~`:740`, `applyMarkdown()` /
  `doUndo()` ~`:719-739`, `UndoSnackbar` ~`:1085`). Presets become *shortcuts into that*, not new
  plumbing.
- The instruction stays **visible and editable** before sending — user can tweak "make it formal" to
  "make it formal and shorter" for free. Discoverable: teaches people what the command box can do.
- One apply path, one undo path. No second code path to keep in sync.

Behaviour:
- Tapping a preset sets the command text (e.g. Key Points → `"rewrite as concise bullet-point key
  points"`). Default: **pre-fill and run immediately** (one tap = result), but the instruction
  remains shown so the user sees what ran and can edit + re-run. (Alternative: pre-fill only, user
  hits send — decide in review; recommend auto-run for the "one-tap" promise.)
- Result replaces the note body (markdown), exactly like the existing **Reformat** action, and is
  undoable via the existing snapshot. Transforms are destructive-but-undoable on the body — same
  mental model users already have for Reformat.

UI placement: a small row of preset chips in the note-detail action area, next to **Reformat**
(`ui/NotesScreens.kt` sticky action bar ~`:1049-1071`).

## Mechanism: extend the command router, reuse deterministic apply

The project pattern is **Hybrid LLM → deterministic apply** (CLAUDE.md). Today
`InteractEngine.interpret()` (`ai/InteractEngine.kt:25`) maps a command to an `InteractOp`
(`Check / Add / Remove / SetTitle / Convert / Suggest / Unknown`) and `apply()` runs pure
`MarkdownOps`. A free-form "rewrite the whole body" is **not** an existing op — a transform produces
new prose, which can't be a pure deterministic edit of the old text.

So add one op:

- `InteractOp.Rewrite(body: String)` — carries the LLM-rewritten markdown.
- The command router recognizes a transform/rewrite intent and routes it to the rewrite path; the
  returned text is applied via the **existing** `applyMarkdown(newBody)` (snapshot + undo come free,
  same as `Convert`/`SetTitle` which already bypass `MarkdownOps` and apply text directly).

Two ways to produce the rewrite — pick one in review:
1. **Inside InteractEngine**: when the parsed intent is a rewrite, make a second generate call that
   returns the rewritten body (don't try to stuff a whole rewritten note into the intent-JSON).
2. **Thin `TransformEngine.transform(context, markdown, preset)`** that the command router calls for
   preset taps, mirroring `MarkdownEngine.toMarkdown()` (`ai/MarkdownEngine.kt`) which already does
   LLM-text-in → markdown-out. Preset buttons can call this directly and skip intent-parsing
   entirely (the preset *is* the intent), while typed free-form rewrites still go through
   InteractEngine.

Recommendation: **option 2 for the preset buttons** (a preset is an unambiguous intent — no need to
spend an LLM call re-parsing "make it formal" into an op), and add `InteractOp.Rewrite` so *typed*
free-form rewrites in the command box also work. Both end at `applyMarkdown()`.

## The one real decision: which Gemma model

- `InteractEngine` uses **E2B** (fast intent parsing).
- `MarkdownEngine` uses **E4B** with an explicit note in code: *"E4B reformats prose vs. checklists
  correctly; E2B over-structures."* (`ai/MarkdownEngine.kt`).

Body rewrites are prose-shaping, the exact thing E2B is documented to do worse. So **transforms
should run on E4B**, like MarkdownEngine — even though the *buttons live next to the Interact (E2B)
field*. The model is chosen per-op, not per-UI. `LlmEngine.generate(context, prompt, model)` takes
an explicit model file, and `LlmEngine.resolve(context, <analysis/E4B filename>, "4b")` already
resolves it (same call MarkdownEngine uses). E4B is the markdown/Q&A model the user already has
installed for auto-markdown, so no new download.

## Prompts (draft, tune on device)

- Key Points: "Rewrite as concise bullet-point key points. Markdown bullets only, no preamble."
- Formal: "Rewrite in a formal, professional tone. Preserve all facts. Return only the rewritten note."
- Short: "Condense to the essential 1-3 sentences. Return only the condensed note."
- Long: "Expand with more detail and structure, inventing no new facts. Return only the expanded note."

Reuse `MarkdownEngine`'s output cleaning (strip `<think>` blocks and ```` ```markdown ```` fences)
so model scaffolding never lands in the note.

## Touch points

- `ai/InteractEngine.kt` — add `InteractOp.Rewrite(body)`; route rewrite intent (typed path).
- New `ai/TransformEngine.kt` (option 2) — `transform(context, markdown, preset)` → `Result<String>`
  on E4B; preset enum. ~ mirrors `MarkdownEngine`.
- `ui/NotesScreens.kt` — preset chip row near Reformat; on tap, pre-fill command text + call the
  transform; apply via existing `applyMarkdown()`; reuse `aiError` + `UndoSnackbar`.
- No DB change. No new model (E4B already used for auto-markdown).

## Validation / success criteria

- Each preset returns a body that (a) keeps the note's facts, (b) matches the requested shape
  (bullets / formal / shorter / longer), (c) contains no model scaffolding.
- Undo restores the exact prior note (`doUndo()` already restores the full snapshot).
- Pure helpers (e.g. output cleaning, preset→prompt mapping) get a JVM unit test
  (`app/src/test/`), per the deterministic-transform convention. The LLM call itself is exercised by
  an instrumented spike on device (cf. `MarkdownSpike`).

## Risks / notes

- E2B-vs-E4B: routing rewrites to E2B (because the button sits by the Interact field) would visibly
  regress prose quality — the plan deliberately routes to E4B.
- Transform replaces the body; mitigated by single-tap undo (matches Reformat).
- Long/Short on very long notes: cap input length like other engines (`take(...)`) to bound latency.

## Out of scope

- Cloud "richer rewrite" (Eloquent offers optional Gemini cloud cleanup) — we stay on-device by
  design; this is a deliberate non-adoption and a differentiator.
- Transform history / multi-level undo (single-level snapshot is consistent with the rest of the app).
