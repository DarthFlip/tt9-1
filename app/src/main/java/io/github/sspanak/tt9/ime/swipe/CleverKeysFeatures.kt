/*
 * Copyright (C) 2026 tt9 contributors. GPL-3.0.
 *
 * Builds the three input tensors CleverKeys' transformer encoder expects:
 *   trajectory_features [1, 250, 6]  — (x, y, vx, vy, ax, ay) per timestep
 *   nearest_keys        [1, 250]     — int32 layout-key index per timestep
 *   actual_length       [1]          — int32 non-padded length
 *
 * The model was trained on QWERTY-normalized coordinates. We resample the user's
 * variable-length gesture down to ≤250 points via cumulative arc-length, normalize
 * each (x, y) to the bounding box of all key centers, compute finite-difference
 * velocity + acceleration, and assign each point to the nearest layout key.
 *
 * Sequence length 250 is a HARD model constraint (encoder export pinned at
 * max_seq_length=250) — feeding longer crashes ONNX Runtime with ORT_INVALID_ARGUMENT.
 */
package io.github.sspanak.tt9.ime.swipe

import kotlin.math.sqrt

object CleverKeysFeatures {

	const val MAX_SEQ_LEN = 250
	const val FEATURE_DIM = 6
	/** Fallback when the caller didn't pass a real duration. ~300ms is a typical swipe. */
	private const val DEFAULT_DURATION_MS = 300L

	data class Inputs(
		val features: FloatArray, // size = 1 * MAX_SEQ_LEN * FEATURE_DIM
		val nearestKeys: IntArray, // size = 1 * MAX_SEQ_LEN
		val actualLength: IntArray, // size = 1
	)

	/**
	 * Build a CleverKeys encoder input batch for one gesture against one layout.
	 *
	 * Channels-last layout: `features[t * 6 + c]` is channel `c` at timestep `t`. The
	 * ONNX tensor will be reshaped to `[1, MAX_SEQ_LEN, 6]` row-major which matches.
	 */
	fun build(
		gesture: StatisticalGlideTypingClassifier.Gesture,
		layoutKeys: List<SwipeKey>,
		// Total raw-gesture duration in ms. CleverKeys computes velocity as
		// (normalized_dx / dt_ms) — so the dt scale matters a LOT. Caller passes the actual
		// wall-clock duration; we divide by MAX_SEQ_LEN to get per-step dt.
		gestureDurationMs: Long = DEFAULT_DURATION_MS,
	): Inputs {
		val features = FloatArray(MAX_SEQ_LEN * FEATURE_DIM)
		val nearest = IntArray(MAX_SEQ_LEN)
		val actualLength = IntArray(1)

		if (gesture.isEmpty || layoutKeys.isEmpty()) {
			actualLength[0] = 0
			return Inputs(features, nearest, actualLength)
		}

		// Match CleverKeys' training-time normalization: use the QWERTY AREA bounds — key
		// edges, not key centers — so (0,0) is the top-left of the layout area and (1,1) is the
		// bottom-right. The earlier center-based bounds slightly over-compressed coordinates and
		// the model decoded garbage like "berke" instead of the actual word.
		val (minX, maxX, minY, maxY) = layoutAreaBounds(layoutKeys)
		val w = (maxX - minX).coerceAtLeast(1f)
		val h = (maxY - minY).coerceAtLeast(1f)
		// Finger-occlusion correction: users typically touch ABOVE the intended key by ~30% of
		// key height (finger covers the target). CleverKeys uses a hard-coded ~74 px; we scale
		// to the F1's actual key height which is roughly 1/4 of the average.
		val avgKeyHeight = avgKeyHeight(layoutKeys)
		val touchYOffset = avgKeyHeight * 0.30f

		// Real-time dt per resampled step. Velocity becomes (normalized_dx / dt_ms) which is
		// what CleverKeys' training pipeline produced — values typically 0.001-0.01 per ms.
		val dtMs = (gestureDurationMs.toFloat() / MAX_SEQ_LEN).coerceAtLeast(0.5f)

		// Resample to MAX_SEQ_LEN points via cumulative arc-length. Pads short gestures by
		// repeating the last point (with zero derivatives) so the downstream tensor still
		// has fixed shape; actualLength signals the real cutoff to the encoder's attention mask.
		val resampled = resamplePoints(gesture, MAX_SEQ_LEN)
		val len = resampled.size
		actualLength[0] = len

		// (x, y) normalized
		var prevX = 0f
		var prevY = 0f
		var prevVx = 0f
		var prevVy = 0f
		for (t in 0 until len) {
			val rawX = resampled[t].first
			// Apply touchYOffset: shift the touch DOWN to the user's intended position before
			// normalizing. (User touches above intended target → adjusted = raw + offset.)
			val rawY = resampled[t].second + touchYOffset
			val nx = (rawX - minX) / w
			val ny = (rawY - minY) / h

			// Finite-difference velocity / acceleration, divided by real-time dt in ms. Match
			// CleverKeys' training pipeline. Clipped to [-10, 10] so a degenerate dt → 0 doesn't
			// produce inf-velocity that explodes the model.
			val vx = if (t == 0) 0f else ((nx - prevX) / dtMs).coerceIn(-10f, 10f)
			val vy = if (t == 0) 0f else ((ny - prevY) / dtMs).coerceIn(-10f, 10f)
			val ax = if (t < 2) 0f else ((vx - prevVx) / dtMs).coerceIn(-10f, 10f)
			val ay = if (t < 2) 0f else ((vy - prevVy) / dtMs).coerceIn(-10f, 10f)

			val base = t * FEATURE_DIM
			features[base + 0] = nx
			features[base + 1] = ny
			features[base + 2] = vx
			features[base + 3] = vy
			features[base + 4] = ax
			features[base + 5] = ay

			nearest[t] = nearestKeyIndex(rawX, rawY, layoutKeys)

			prevX = nx
			prevY = ny
			prevVx = vx
			prevVy = vy
		}

		// Pad the rest of nearest_keys with 0 (PAD token); features remain zero.
		// Encoder attention will mask these out via actual_length.

		return Inputs(features, nearest, actualLength)
	}

	/**
	 * Cumulative-arc-length resample to exactly [target] points. Used to be in
	 * GestureResampler — folded in here because we removed that helper. For very short
	 * gestures (<5 raw points) we just repeat-pad and let actualLength tell the encoder
	 * to ignore the tail.
	 */
	private fun resamplePoints(
		gesture: StatisticalGlideTypingClassifier.Gesture,
		target: Int,
	): List<Pair<Float, Float>> {
		val pts = ArrayList<Pair<Float, Float>>(target)
		if (gesture.pointCount == 0) return pts
		if (gesture.pointCount == 1) {
			pts.add(gesture.getX(0) to gesture.getY(0))
			return pts
		}

		val totalLen = gesture.getLength().coerceAtLeast(1e-3f)
		val step = totalLen / (target - 1).coerceAtLeast(1)
		var srcIdx = 0
		var lastX = gesture.getX(0)
		var lastY = gesture.getY(0)
		var segLen = 0f
		var segDx = 0f
		var segDy = 0f
		var traversed = 0f
		var targetDist = step

		pts.add(lastX to lastY)

		while (pts.size < target && srcIdx < gesture.pointCount - 1) {
			if (segLen == 0f) {
				val nx = gesture.getX(srcIdx + 1)
				val ny = gesture.getY(srcIdx + 1)
				segDx = nx - lastX
				segDy = ny - lastY
				segLen = sqrt(segDx * segDx + segDy * segDy)
				if (segLen < 1e-6f) {
					srcIdx++
					lastX = nx
					lastY = ny
					segLen = 0f
					continue
				}
			}

			val remaining = segLen - traversed
			if (remaining >= targetDist) {
				val t = (traversed + targetDist) / segLen
				val px = lastX + segDx * t
				val py = lastY + segDy * t
				pts.add(px to py)
				traversed += targetDist
				targetDist = step
			} else {
				targetDist -= remaining
				srcIdx++
				lastX = gesture.getX(srcIdx)
				lastY = gesture.getY(srcIdx)
				traversed = 0f
				segLen = 0f
			}
		}

		return pts
	}

	/**
	 * Map a touch point to CleverKeys' canonical "nearest key token". The model was trained with
	 * `(char - 'a') + 4` indexing — same scheme as the vocab tokens — so 'a'=4, 'b'=5, ..., 'z'=29.
	 * Layout list-position (what I had before) makes the model produce garbage because the embedding
	 * table is keyed on canonical positions, not whatever order Android's view tree returns keys in.
	 */
	private fun nearestKeyIndex(x: Float, y: Float, keys: List<SwipeKey>): Int {
		var bestIdx = -1
		var bestDist = Float.MAX_VALUE
		for (i in keys.indices) {
			val dx = keys[i].centerX - x
			val dy = keys[i].centerY - y
			val d = dx * dx + dy * dy
			if (d < bestDist) {
				bestDist = d
				bestIdx = i
			}
		}
		if (bestIdx < 0) return PAD_TOKEN
		val ch = keys[bestIdx].code.toChar().lowercaseChar()
		return if (ch in 'a'..'z') (ch - 'a') + FIRST_LETTER_TOKEN else PAD_TOKEN
	}

	private const val PAD_TOKEN = 0
	private const val FIRST_LETTER_TOKEN = 4

	/**
	 * Layout AREA bounds — extends each key center by ± half the key's width/height to capture
	 * the actual touchable region. (0,0) is the top-left edge of the leftmost-topmost key;
	 * (1,1) is the bottom-right edge of the rightmost-bottommost key. Closer to CleverKeys'
	 * training-time "QWERTY area" frame than center-only bounds.
	 */
	private fun layoutAreaBounds(keys: List<SwipeKey>): Bounds {
		var minX = Float.MAX_VALUE
		var maxX = -Float.MAX_VALUE
		var minY = Float.MAX_VALUE
		var maxY = -Float.MAX_VALUE
		for (k in keys) {
			val hw = k.width / 2f
			val hh = k.height / 2f
			if (k.centerX - hw < minX) minX = k.centerX - hw
			if (k.centerX + hw > maxX) maxX = k.centerX + hw
			if (k.centerY - hh < minY) minY = k.centerY - hh
			if (k.centerY + hh > maxY) maxY = k.centerY + hh
		}
		return Bounds(minX, maxX, minY, maxY)
	}

	private fun avgKeyHeight(keys: List<SwipeKey>): Float {
		if (keys.isEmpty()) return 0f
		var sum = 0f
		for (k in keys) sum += k.height
		return sum / keys.size
	}

	private data class Bounds(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float)
}
