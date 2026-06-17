# Embedding A/B: Gecko 110m vs EmbeddingGemma-300m (seq512)

> The A/B harness (`EmbeddingAbTest` + `corpus_hard.json` + `ab_queries*.json`) and the `-Pabtest`
> coexisting-install flag were removed after this decision — re-buildable from this doc if revisited.
> The SDK ships `GemmaEmbeddingModel(modelPath, tokenizerPath, useGpu)` beside `GeckoEmbeddingModel`
> (no SDK bump). EmbeddingGemma files live on the project Nextcloud under `gemmaembedings/`.

On-device (Pixel 9, Tensor G4), via `EmbeddingAbTest` + AI Edge RAG SDK 0.3.0
(`GeckoEmbeddingModel` / `GemmaEmbeddingModel`). Both embed at dim 768.
precision@5 = fraction of top-k with the right topic (centered cosine, mirrors the app).
separation margin = mean same-topic cosine − mean cross-topic cosine (raw). Higher = cleaner space.

## Clean corpus (318 notes, 10 well-separated topics) — CPU

| Metric | Gecko | EmbeddingGemma | Δ |
|---|---|---|---|
| Lexical precision@5 | 1.000 | 1.000 | tie |
| Paraphrase precision@5 | 0.850 | 0.900 | +0.05 |
| Separation margin | 0.090 | 0.266 | +0.176 (≈3×) |
| Cluster purity (best thr) | 0.994 | 0.997 | tie |
| Latency / note | 309 ms | 1147 ms | 3.7× slower |

Read: retrieval & clustering tie at the ceiling (corpus too clean to discriminate). EmbeddingGemma's
3× separation margin looked compelling — but see below.

## Hard corpus (82 notes, 7 deliberately confusable topics: work↔finance, health↔family, home↔finance, travel↔family, health↔groceries) — 12 hard semantic queries

| Metric | Gecko (CPU) | EmbGemma (CPU) | Gecko (GPU) | EmbGemma (GPU) |
|---|---|---|---|---|
| precision@5 | **0.833** | 0.800 | **0.833** | 0.817 |
| separation margin | 0.037 | 0.067 | 0.037 | 0.067 |
| cluster purity (best) | **0.850** | 0.656 | **0.850** | 0.688 |
| latency / note | 311 ms | 1109 ms | **298 ms** | 477 ms |

## Conclusion: no case to make EmbeddingGemma the default / migrate (as of 2026-06-17)

Anchor on retrieval — the primary "Ask" path:

- **Retrieval is a TIE on hard data.** 0.833 vs 0.80–0.82 across 12 queries ≈ 0.4 in summed precision
  (< one item in one query); per-query they trade wins. Indistinguishable. The clean-corpus
  "3× separation margin" that looked compelling was a **ceiling/clean-data artifact** — on realistic
  overlapping notes both margins collapse.
- **Cost:** EmbeddingGemma is 1.6–3.7× slower per embed. GPU helps only it (~2.3×: 1109→477 ms);
  Gecko's delegate doesn't engage (311→298). Even on GPU it stays ~1.6× slower.
- **Complexity it would add:** per-note model stamp + schema migration + re-embed-on-switch + model
  hosting + dual backend.

→ No retrieval gain + higher latency + real added complexity ⇒ **not worth making it the default or
migrating.** Keep Gecko as the default.

Footnotes (weaker, scale-dependent — do NOT anchor on these):
- Clustering looked better for Gecko (0.85 vs ~0.66–0.69), but the pipeline (centered cosine +
  single-linkage union-find + 0.05 threshold grid) is Gecko-calibrated and EmbeddingGemma's cosines
  live on a different scale (cross-topic ~0.40 vs Gecko ~0.82) — partly circular, likely understated.
- Separation margin is also scale-dependent and not comparable across the two distributions.

Limitations (directional call, not a definitive benchmark):
- EmbeddingGemma was scored through Gecko's centered-cosine retrieval; its already-spread space might
  do better with raw cosine.
- Small set: 12 queries / 82 hard notes.

Open question for the product owner: this answers "default/migrate?" (no). It does NOT decide whether
to still ship EmbeddingGemma as an optional, user-selectable embedder (Gecko default) for
experimentation — that's a product call.
