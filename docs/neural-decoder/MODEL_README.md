# Neural swipe decoder — model assets

tt9's `full`, `lite`, and `premium` flavors include a neural swipe-typing
decoder backed by [FUTO's published encoder weights](https://huggingface.co/futo-org/futo-swipe).
The model is **not committed to this repository** — it's downloaded at build
time by `app/download-neural-model.gradle`.

## What's downloaded

| File | SHA256 | Size | Source |
|---|---|---|---|
| `model_fp32.pte` | `725242bab5d14345e96ff214e8de2bfbc1f962c232d320df9c24cb82ffd1fbaf` | 2.5 MB | [HuggingFace `honorable_sturgeon/model_fp32.pte`](https://huggingface.co/futo-org/futo-swipe/resolve/main/honorable_sturgeon/model_fp32.pte) |
| `scoring.json` | — | ~1 KB | [HuggingFace `scoring.json`](https://huggingface.co/futo-org/futo-swipe/resolve/main/scoring.json) |

Both files land in `app/src/main/assets/models/` and are bundled into the
final APK as Android assets.

## License posture

These weights are under **FUTO Model Weights License 1.0**
(see [FUTO_WEIGHTS_LICENSE.md](FUTO_WEIGHTS_LICENSE.md) for the full text).
This license **is not GPL-3.0 compatible** because it adds:

- Mandatory attribution ("FUTO Swipe technology" visible to end users)
- A prohibition on sublicensing
- A patent termination clause

tt9 itself remains GPL-3.0. The bundled FUTO weights coexist with that
license uneasily. **The resulting combined APK is suitable for personal
use only** — not for F-Droid, the Play Store, or formal commercial
distribution. Anyone publishing the combined work needs to do their own
legal review.

## Required attribution

The FUTO Model Weights License requires a visible notice that "FUTO
Swipe" technology is in use. tt9 satisfies this by displaying the
notice on:

- The IME settings screen
- The About screen

If you remove the neural decoder from your fork (e.g. by setting
`HAS_NEURAL_DECODER = false` in `app/build.gradle`) you may also remove
the attribution notice.

## Citation

> Miller, D. & Kostarevas, A. (2026). FUTO Swipe: Layout-Agnostic Neural
> Swipe Decoding. arXiv:2606.25247.

## Tensor contract

The encoder takes three input tensors and produces three output tensors:

| Tensor | Direction | Shape | Notes |
|---|---|---|---|
| `features` | input | `[1, 2, 64]` | Raw (x, y) gesture resampled to 64 points, normalized to layout bounds |
| `layout_keys` | input | `[1, 64, 2]` | Per-key (x, y) centers, padded to 64 slots |
| `layout_mask` | input | `[1, 64]` | Float mask — 1.0 for real key slots, 0.0 for padding |
| `log_emissions` | output | `[1, 32, 65]` | Log-probabilities over 64 keys + 1 CTC blank |
| `coefficients` | output | `[1, 32, 64]` | Spectral coefficients (for optional `magic_macaw` decoder) |
| `lambda` | output | `[1, 32, 1]` | Intention gate (for optional `magic_macaw` decoder) |

tt9 uses only `log_emissions`; the spectral / intention outputs are
discarded because we don't ship the `magic_macaw` decoder.

## How tt9 uses the model

1. Touch points enter `SwipeableKeyboardContainer.onGlideAddPoint()`.
2. On gesture completion, `NeuralGlideDecoder.analyzeGesture()` resamples
   the trajectory to 64 (x, y) points via `GestureResampler`.
3. The encoder runs once via ExecuTorch (off-main thread).
4. `BeamSearch` walks the lexicon trie constrained by the per-timestep
   key log-probs, applies MindReader context bonus + locked-prefix
   constraint + word frequency.
5. Top-N candidates feed back into tt9's existing suggestion strip.
