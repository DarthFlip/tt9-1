# Neural swipe decoder — model assets

tt9's neural swipe decoder uses [CleverKeys'](https://github.com/tribixbite/CleverKeys)
trained ONNX models for English gestures. The model is **not committed to
this repository** — it's downloaded at build time by
`app/download-neural-model.gradle` from the upstream GitHub.

Hebrew and other non-English languages continue to use tt9's
`StatisticalGlideTypingClassifier`; the per-language dispatch lives in
`SwipeableKeyboardContainer.bindLanguage`.

## What's downloaded

| File | SHA256 | Size | Source |
|---|---|---|---|
| `swipe_encoder.onnx` | `964c721783df57a0a98aec67fb7db84732e0139d1b5ef9ff08c5b1fd2480f817` | 5.1 MB | [CleverKeys `swipe_encoder_android.onnx`](https://raw.githubusercontent.com/tribixbite/CleverKeys/main/src/main/assets/models/swipe_encoder_android.onnx) |
| `swipe_decoder.onnx` | `b438984986dbb0afeeac9551dcba8fc2ea87eea6bb011a0381dd93cc8cf92c0d` | 4.7 MB | [CleverKeys `swipe_decoder_android.onnx`](https://raw.githubusercontent.com/tribixbite/CleverKeys/main/src/main/assets/models/swipe_decoder_android.onnx) |

Both files land in `app/src/main/assets/models/` and are bundled into the
APK.

## License

CleverKeys (code + ONNX weights) is **GPL-3.0** — same license as tt9.
No additional attribution clause, no F-Droid blocker. Use this as a
GPL-clean alternative to FUTO's Source First weights.

Upstream LICENSE: https://github.com/tribixbite/CleverKeys/blob/main/LICENSE

## Citation

CleverKeys is a community project by `tribixbite`. If you publish about
the integration, credit the upstream repo. There's no formal paper.

## Tensor contract — encoder

| Tensor | Direction | Shape | dtype | Notes |
|---|---|---|---|---|
| `trajectory_features` | input | `[1, 250, 6]` | f32 | (x, y, vx, vy, ax, ay) per timestep, normalized to [0,1] in QWERTY bounding box |
| `nearest_keys` | input | `[1, 250]` | i32 | Index of the nearest layout key per timestep |
| `actual_length` | input | `[1]` | i32 | Non-padded length of the trajectory |
| `memory` | output | `[1, seq_len, 256]` | f32 | Encoded representation fed to decoder |

## Tensor contract — decoder (called iteratively, one step at a time)

| Tensor | Direction | Shape | dtype | Notes |
|---|---|---|---|---|
| `memory` | input | `[1, seq_len, 256]` | f32 | Encoder output, reused across all beam steps |
| `target_tokens` | input | `[num_beams, 20]` | i32 | Tokens emitted so far (PAD=0, UNK=1, SOS=2, EOS=3, a-z=4..29). Padded with 0. |
| `actual_src_length` | input | `[1]` | i32 | Encoder's actual length |
| `logits` | output | `[num_beams, 20, 30]` | f32 | Per-position vocab logits; we read the slice at the current step |

CleverKeys' decoder is `broadcast`-mode: pass a single memory tensor for
all beams; the model internally replicates across beam dim.

## Decoding strategy

1. Encode once per gesture → `memory`.
2. Initialize beams as `[[SOS]]` with `logProb = 0`.
3. For each step `t ∈ [0, max_steps)`:
   - Build `target_tokens [num_beams, 20]` with current tokens followed
     by PAD.
   - Run decoder → `logits[beam, t, vocab]`.
   - For each beam, expand by top-K valid next-tokens (constrained by
     lexicon trie + locked prefix + EOS allowed only after >=2 chars).
   - Prune to `beamWidth`.
   - If all beams have emitted EOS, stop early.
4. Strip control tokens, look up complete words in the trie, rank by
   `logProb / length^α + freqBonus + contextBonus`, return top-N.

## How tt9 uses CleverKeys

- `NeuralGlideDecoder.kt` — owns both ONNX sessions, runs the encoder
  + autoregressive decoder loop.
- `CleverKeysFeatures.kt` — resamples gesture to 250 (x, y, vx, vy, ax, ay)
  + nearest_keys lookup. Caps at 250 (the model's hardcoded sequence limit).
- `BeamSearch.kt` — autoregressive beam search using the decoder ONNX session.
- `LexiconTrie.kt` — token-keyed (4..29) prefix trie built from
  `Tt9WordProvider`'s English word list.
- `SwipeableKeyboardContainer.bindLanguage` — dispatch: English → neural,
  everything else → statistical fallback.

## Known limitations

- **English-only at the model vocabulary level.** Cannot output Hebrew,
  Cyrillic, or any non-Latin character. Hebrew uses the statistical
  decoder instead.
- **QWERTY-biased.** The encoder was trained on QWERTY geometry.
  Non-QWERTY layouts (Dvorak, Colemak, AZERTY) will degrade.
- **Sequence length capped at 250.** Long unrealistic gestures get
  downsampled before they reach the model.
