# tt9 Neural Glide Decoder — Architecture

**Status**: Phase 1 design (`/Users/akivajeger/.claude/plans/swirling-painting-frost.md`).
This document is the architectural commitment for the FUTO-weights-based
neural swipe-typing decoder that will replace
`StatisticalGlideTypingClassifier.kt` in the `full` build flavor.

Strategy: ship FUTO's published model weights
([HuggingFace](https://huggingface.co/futo-org/futo-swipe)) under the
FUTO Model Weights License 1.0 attribution requirement. Architecture is
described in their paper ([arXiv:2606.25247](https://arxiv.org/abs/2606.25247),
Miller & Kostarevas, June 2026). **No FUTO code is used** — runtime
integration is independently written.

**License posture**: tt9 stays GPL-3.0; FUTO weights ship as a bundled
asset under FUTO License 1.0. The combined APK lives in a legal gray
area for redistribution (GPL forbids additional restrictions; FUTO's
attribution + no-sublicense clauses are additional). Acceptable for
personal use on the user's own phone; not appropriate for F-Droid or
formal commercial distribution.

---

## 1. Design constraints

| Constraint | Source | Target |
|---|---|---|
| Inference latency, warm | Mid-gesture preview throttle is 150 ms (`SwipeableKeyboardContainer.kt:398`) | <100 ms encode+decode on Schok F1 |
| Inference latency, cold | First-gesture cache load | <500 ms |
| Encoder model size, INT8 | F1 RAM budget; APK size sensitivity | <1 MB |
| Total APK delta (full flavor) | Lite stays at 7.8 MB; full ceiling 50 MB | <30 MB including ONNX Runtime AAR |
| Memory at runtime | F1 has ~512 MB available to apps | <40 MB resident |
| Decoder API surface | `GlideTypingClassifier.kt:13-26` | Implement all 7 methods, no caller changes |
| Layout-agnosticism | tt9 ships en/he/fr/es/de/ru/ar | One encoder, per-layout basis files |
| Cross-mode integration | Plumbed via `TypingHandler.getLockedPrefix/getGlideContextWords` | Consume both, no `TypingHandler` changes |

---

## 2. Model architecture

### 2.1 Tensor contract (verified from FUTO README)

We bundle FUTO's pre-trained `honorable_sturgeon/model_fp32.pte` as-is.
The encoder accepts:

| Tensor | Direction | Shape | Notes |
|---|---|---|---|
| `features` | input | `[1, 2, 64]` | Raw (x, y) resampled to 64 points, normalized to layout bounds |
| `layout_keys` | input | `[1, 64, 2]` | Per-key (x, y) centers, padded to 64 slots |
| `layout_mask` | input | `[1, 64]` | Float — 1.0 for real key slots, 0.0 for padding |
| `log_emissions` | output | `[1, 32, 65]` | Log-probs over 64 keys + 1 CTC blank |
| `coefficients` | output | `[1, 32, 64]` | Spectral coefficients (optional `magic_macaw` decoder) |
| `lambda` | output | `[1, 32, 1]` | Intention gate (optional `magic_macaw` decoder) |

We use only `log_emissions`. The spectral / intention outputs are
discarded because we don't ship `magic_macaw`.

**Feature engineering is internal to the model.** The .pte graph contains
the Savitzky-Golay + velocity/acceleration/curvature derivation as a fixed
prefix — we just pass raw normalized (x, y). This is what makes FUTO's
checkpoint a drop-in: no Kotlin-side feature math, no risk of training/
inference skew.

**Layout agnosticism is a runtime input.** `layout_keys` flows from
`GestureResampler.buildLayoutTensors(SwipeKey list)` — recomputed once per
`bindLanguage()` for English / Hebrew / T9 / anything. No per-language
model file.

**Temporal axis**: raw `MotionEvent` samples → cumulative-arc-length
resample to exactly 64 (x, y) points via `GestureResampler.resampleToBuffer`.

### 2.2 Encoder: 1D Temporal CNN (informational — we don't build it)

Stack of 8 dilated depthwise-separable convolution blocks, ConvNeXt-v2
style:

```
input  : [B, 8, 64]
       → Linear(8 → 32, kernel=1)               [B, 32, 64]
       → 4 × ConvBlock(dilation=1,2,4,8)        [B, 64, 64]
       → DownsampleAdapter(stride=2, kernel=3)  [B, 64, 32]
       → 4 × ConvBlock(dilation=1,2,4,8)        [B, 128, 32]
       → DownsampleAdapter(stride=2, kernel=3)  [B, 128, 16]
       → SpatialHead(128 → 64)                  [B, 64, 16]  ← spectral coeffs
       → IntentionHead(128 → 1, sigmoid)        [B, 1, 16]   ← intention gate λᵢ
```

**ConvBlock**:
```
x → DepthwiseConv1d(kernel=7, dilation=d, groups=C)
  → LayerNorm
  → PointwiseConv1d(C → 2C, kernel=1)
  → GLU                        # gated activation; halves C back
  → PointwiseConv1d(C → C, kernel=1)
  → Add(residual=input)
```

**Parameter accounting** (rough):

| Layer | Params |
|---|---|
| Input projection (8→32, k=1) | 256 |
| 4 × ConvBlock @ 32 ch | ~38K |
| Downsample 32→64 | 6K |
| 4 × ConvBlock @ 64 ch | ~140K |
| Downsample 64→128 | 25K |
| 4 × ConvBlock @ 128 ch | ~530K |
| SpatialHead (128→64) | 8K |
| IntentionHead (128→1) | 128 |
| **Total** | **~750K** |

INT8-quantized binary size: ~750 KB. Within the <1 MB budget.

Note this is bigger than my earlier 300-500K hand-wave; honest accounting after specifying every layer. Still 18% smaller than FUTO's 635K encoder + 304K decoder combined and we get layout-agnosticism for free.

### 2.3 Spectral-spatial output head — the key trick

At each output timestep `t` (16 total), the encoder produces:

- A **64-dim spectral coefficient vector** `cₜ ∈ ℝ⁶⁴`
- A **scalar intention gate** `λₜ ∈ [0,1]` (sigmoid)

The keyboard layout (loaded at IME bind time, not at training time)
supplies a **basis matrix** `Φ ∈ ℝ^{K × 64}` where K is the number of
keys. `Φ[k]` is a 64-dim "fingerprint" of key k's position on the
2D layout — computed as the 2D cosine transform of a Gaussian centered
at the key's screen position.

Per-key logit at timestep t:
```
zₜ,ₖ = cₜ · Φ[k]                        # dot product
gated_zₜ,ₖ = λₜ · zₜ,ₖ + (1 - λₜ) · blank_logit
```

This is the FUTO insight, re-implemented. The encoder learns "what
intent looks like in cosine-basis space" without ever seeing a specific
layout. To support a new layout (Hebrew, T9 grid, ClearFlow), we
compute Φ analytically from key (x, y) positions — no training,
no retraining, no second model.

**Implementation**:
- In Phase 2/3 (PyTorch training): basis Φ is computed in the data
  loader from each batch's layout metadata. Loss is per-timestep CTC
  over the masked logits `gated_zₜ,ₖ`.
- In Phase 4 (Kotlin runtime): `LayoutBasis.compute(SwipeKey list) →
  FloatArray(K × 64)`. Called once per `bindLanguage()`. Cached. The
  ONNX model outputs raw `(cₜ, λₜ)`; the Kotlin runtime does the
  basis multiplication + masking before beam search.

### 2.4 Beam search + lexicon constraint

CTC decode → character lattice → prefix-trie-constrained beam search.

**Trie**: built from `Tt9WordProvider.getListOfWords()` at language load.
Each node stores: character, parent pointer, terminal flag, log-frequency
(from existing `Tt9WordProvider.getFrequencyForWord()`).

**Beam search**:
- Beam width: 50 (vs FUTO's 100 — F1 CPU constraint).
- State: `(trie_node, log_prob, last_char_was_blank)`.
- At each timestep, expand each beam by either:
  (a) staying on current trie node and emitting blank, OR
  (b) advancing to a child node if its character's logit is in the
      top-K (K=5) at this timestep.
- Length-aware pruning: drop beams whose accumulated path length
  drastically exceeds the gesture's measured length (`Gesture.getLength()`).

**Score**:
```
final_score(word) = beam_log_prob(word)
                  + β_freq × log(1 + frequency)
                  + β_context × 1[word ∈ MindReader.guesses]
                  + β_prefix × 1[word starts with locked_prefix]
```
where `β_freq`, `β_context`, `β_prefix` are tuned on the validation set
(Phase 3). The MindReader context boost replaces the current hard-coded
4× `CONTEXT_BOOST`.

### 2.5 Cross-mode signal handling

Three signals are available at gesture time:

1. **Locked QWERTY prefix** (`TypingHandler.getLockedPrefix()`, line 275)
   — constrain the trie walk to start at that prefix's node. Done in
   the beam search initialization. Not a model input — keeps the
   encoder language-agnostic.
2. **T9 digit constraints** (`TypingHandler.filterGlideByComposingState()`,
   line 360) — filter beam candidates post-decode (existing path,
   unchanged).
3. **MindReader context words**
   (`TypingHandler.getGlideContextWords()`, line 305) — additive bonus
   `β_context` in the final score (above).

All three are post-decode rescoring, so the model stays small + the
training pipeline stays simple. We don't need cross-mode-labeled
training data for the model itself; we get cross-mode for free via the
rescoring layer.

---

## 3. Runtime: ONNX Runtime + XNNPACK

**Library**: `ai.onnxruntime:onnxruntime-android:1.18.1` (~12 MB AAR).
ONNX Runtime ships ARM64 + ARMv7 JNI bindings; no NDK setup required.

**Quantization**: post-training dynamic INT8 quantization via
`onnxruntime.quantization.quantize_dynamic`. Per-channel for the
convolution weights, per-tensor for activations. Validated against
FP32 baseline on the held-out eval set — accept ≤2 percentage-point
top-1 degradation.

**XNNPACK execution provider**: enable via
`SessionOptions.addConfigEntry("session.use_xnnpack", "1")`. XNNPACK has
hand-optimized ARM kernels for depthwise-separable convolutions —
matches our architecture choice.

**Threading**: one session per IME instance. Inference runs on the
existing `classifierExecutor` worker thread in
`SwipeableKeyboardContainer.kt:100`. No concurrent inference
(sequential gesture processing already enforced).

**Memory**: Estimated 10 MB resident — model weights (~750 KB) + ORT
arena allocator (~5 MB) + intermediate tensors (~2 MB) + JNI overhead.

---

## 4. Performance budget (sanity check)

FUTO's encoder runs 1.54 ms on a Pixel 4 A76 core. The Schok F1 has
4× Cortex-A55 cores at 1.6 GHz — roughly 5-7× slower than the Pixel 4's
A76. Linear extrapolation: ~10-15 ms per inference, well under our
100 ms warm budget.

Beam search dominates: with beam=50, 16 timesteps, average ~3
expansions per beam → ~2400 trie touches per gesture. On the F1 we
budget 30-50 ms for beam search. Lexicon trie sits in heap; no I/O.

**Total**: ~50-80 ms warm-path. Cold path adds model load (~200-300 ms,
one-time per IME process). Both within budget.

---

## 5. Training pipeline summary

Detailed in Phase 2 of the master plan. Brief outline:

- PyTorch + ExecuTorch-free pipeline (we export to ONNX directly).
- Data: FUTO MIT swipe-1 (1.04 M swipes) for English.
- Synthetic augmentation via the same Gaussian-jittered ideal-path
  generator we'll write to test the runtime.
- Loss: weighted CTC (0.3) + ranking loss (5.0) + consistency
  regularization (0.1). Matches FUTO's recipe; well-tested.
- Augmentations: y-scale, x-scale, shear, flips, rotation, translation,
  time-reversal. All apply to the spectral-basis output too via the
  layout matrix.
- Optimizer: AdamW, batch 1024, 120 epochs.
- Hardware: rented Lambda Labs A10 or A40 (~$50-100 total budget).

Hebrew (Phase 5): we expect zero-shot transfer via the layout-agnostic
encoder + analytically-computed `Φ_he`. If accuracy is insufficient,
fine-tune the encoder on synthetic Hebrew swipes (no real dataset
available).

---

## 6. Files this design produces

| Path | Purpose |
|---|---|
| `app/download-neural-model.gradle` | Build-time SHA256-verified download of FUTO weights |
| `app/src/main/java/io/github/sspanak/tt9/ime/swipe/NeuralGlideDecoder.kt` | Implements `GlideTypingClassifier`; loads ExecuTorch module + drives encoder/beam-search |
| `app/src/main/java/io/github/sspanak/tt9/ime/swipe/GestureResampler.kt` | Resamples raw gesture to `[1,2,64]` features + builds `[1,64,2]` layout / `[1,64]` mask tensors |
| `app/src/main/java/io/github/sspanak/tt9/ime/swipe/LexiconTrie.kt` | `HashMap<Char, Node>` prefix trie (alphabet-agnostic) |
| `app/src/main/java/io/github/sspanak/tt9/ime/swipe/BeamSearch.kt` | Trie-constrained CTC beam search width=50 |
| `app/src/main/assets/models/model_fp32.pte` (downloaded, not in git) | FUTO encoder weights, 2.5 MB |
| `docs/neural-decoder/MODEL_README.md` | Asset documentation |
| `docs/neural-decoder/FUTO_WEIGHTS_LICENSE.md` | FUTO Model Weights License 1.0 verbatim |

---

## 7. Open questions

These get resolved in Phase 2/3 with empirical data:

- **Beam width 50 vs 100**: tune for F1 latency/accuracy tradeoff.
- **Trie node format**: flat array vs object graph. Measure on F1; the
  cache-miss cost of object pointer chasing may be significant.
- **Float16 vs INT8 for the encoder**: INT8 is the baseline; if accuracy
  drops more than 2 pp, switch to FP16 (doubles model size but still
  under 2 MB).
- **Cross-mode training data**: do we generate synthetic cross-mode
  swipes for training (model input feature) or stick with pure
  post-decode rescoring (current plan)? Try post-decode first; only
  retrain with cross-mode data if there's a measurable gap.
- **Multi-segment gestures** (lift-and-continue): the spectral-basis
  trick may support this naturally — each segment generates its own
  intention-gated coefficient stream and they concatenate. Defer until
  baseline works.
