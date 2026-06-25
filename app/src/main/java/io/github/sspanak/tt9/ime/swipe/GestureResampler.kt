/*
 * Copyright (C) 2026 tt9 contributors. GPL-3.0.
 *
 * Resamples a variable-length gesture to exactly N (x, y) points via cumulative arc-length
 * — the input format FUTO's encoder expects ([1, 2, 64]). Identical interpolation strategy as
 * Gesture.resample() in StatisticalGlideTypingClassifier.kt, written separately so the neural
 * path doesn't import statistical-only code.
 */
package io.github.sspanak.tt9.ime.swipe

import kotlin.math.sqrt

object GestureResampler {

	/**
	 * Resample [gesture] to exactly [targetPoints] uniformly-spaced (x, y) samples and stack into
	 * a flat float buffer compatible with the model's `features` input `[1, 2, targetPoints]`.
	 *
	 * Output layout:
	 *   `[x0, x1, ..., x_{N-1}, y0, y1, ..., y_{N-1}]`  (channels-first, NCT)
	 *
	 * Coordinates are normalized to `[0, 1]` relative to the bounding box of all key centers in
	 * [layoutKeys]. The model was trained on normalized inputs, so passing raw pixel coords would
	 * produce garbage candidates.
	 */
	fun resampleToBuffer(
		gesture: StatisticalGlideTypingClassifier.Gesture,
		layoutKeys: List<SwipeKey>,
		targetPoints: Int = 64,
	): FloatArray {
		val out = FloatArray(2 * targetPoints)
		if (gesture.isEmpty || layoutKeys.isEmpty()) return out

		val (minX, maxX, minY, maxY) = layoutBounds(layoutKeys)
		val w = (maxX - minX).coerceAtLeast(1f)
		val h = (maxY - minY).coerceAtLeast(1f)

		val totalLen = gesture.getLength().coerceAtLeast(1e-3f)
		val step = totalLen / (targetPoints - 1).coerceAtLeast(1)
		var traversed = 0f
		var srcIdx = 0
		var outIdx = 0
		var lastX = gesture.getX(0)
		var lastY = gesture.getY(0)
		var segLen = 0f
		var segDx = 0f
		var segDy = 0f

		fun normX(v: Float) = (v - minX) / w
		fun normY(v: Float) = (v - minY) / h

		fun emit(x: Float, y: Float) {
			out[outIdx] = normX(x)
			out[targetPoints + outIdx] = normY(y)
			outIdx++
		}

		emit(lastX, lastY)
		var targetDist = step

		while (outIdx < targetPoints && srcIdx < gesture.pointCount - 1) {
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
				emit(px, py)
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

		// Pad remaining slots with the last point. Happens for very short gestures.
		val lastIdx = (outIdx - 1).coerceAtLeast(0)
		val padX = out[lastIdx]
		val padY = out[targetPoints + lastIdx]
		while (outIdx < targetPoints) {
			out[outIdx] = padX
			out[targetPoints + outIdx] = padY
			outIdx++
		}
		return out
	}

	/**
	 * Build the model's `layout_keys` `[1, 64, 2]` input + `layout_mask` `[1, 64]` from the
	 * current keyboard layout. Key centers are normalized to the same `[0, 1]` box as the
	 * gesture so the model sees consistent coordinates.
	 *
	 * Returns ([keys, mask]) flat arrays sized 128 and 64 respectively.
	 */
	fun buildLayoutTensors(layoutKeys: List<SwipeKey>, maxKeys: Int = 64): Pair<FloatArray, FloatArray> {
		val keys = FloatArray(maxKeys * 2)
		val mask = FloatArray(maxKeys)
		if (layoutKeys.isEmpty()) return keys to mask

		val (minX, maxX, minY, maxY) = layoutBounds(layoutKeys)
		val w = (maxX - minX).coerceAtLeast(1f)
		val h = (maxY - minY).coerceAtLeast(1f)

		val n = layoutKeys.size.coerceAtMost(maxKeys)
		for (i in 0 until n) {
			val k = layoutKeys[i]
			keys[i * 2] = (k.centerX - minX) / w
			keys[i * 2 + 1] = (k.centerY - minY) / h
			mask[i] = 1f
		}
		return keys to mask
	}

	private fun layoutBounds(layoutKeys: List<SwipeKey>): Bounds {
		var minX = Float.MAX_VALUE
		var maxX = -Float.MAX_VALUE
		var minY = Float.MAX_VALUE
		var maxY = -Float.MAX_VALUE
		for (k in layoutKeys) {
			if (k.centerX < minX) minX = k.centerX
			if (k.centerX > maxX) maxX = k.centerX
			if (k.centerY < minY) minY = k.centerY
			if (k.centerY > maxY) maxY = k.centerY
		}
		return Bounds(minX, maxX, minY, maxY)
	}

	private data class Bounds(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float)
}
