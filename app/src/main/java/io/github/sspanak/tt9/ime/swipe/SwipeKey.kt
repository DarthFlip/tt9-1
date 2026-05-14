/*
 * Copyright (C) 2025 The FlorisBoard Contributors (original concept, Apache 2.0)
 * Adapted for tt9 — this is a data holder abstracting a keyboard key for gesture classification.
 */
package io.github.sspanak.tt9.ime.swipe

import android.graphics.RectF

/**
 * A flat geometry + label record for a single key. The gesture classifier only needs the visible
 * bounds (in the coordinate space of the touch-receiving view) and the code point this key emits.
 *
 * This intentionally avoids coupling to tt9's SoftKey view, so the classifier stays pure.
 */
data class SwipeKey(
	val bounds: RectF,
	val code: Int,
) {
	val centerX: Float get() = bounds.centerX()
	val centerY: Float get() = bounds.centerY()
	val width: Float get() = bounds.width()
	val height: Float get() = bounds.height()
}
