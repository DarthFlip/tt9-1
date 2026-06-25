/*
 * Ported from github.com/tribixbite/CleverKeys (GPL-3.0).
 * Slimmed for tt9: dropped touchedKeys + KeyboardData/KeyValue deps, kept only the
 * coords + timestamps the SwipeTrajectoryProcessor actually reads. The various metric
 * helpers (isHighQualitySwipe, getSwipeConfidence) were UI-side gesture-quality scoring
 * that tt9 handles separately via GlideTypingGesture.Detector.
 */
package io.github.sspanak.tt9.ime.swipe.cleverkeys

import android.graphics.PointF

/**
 * Minimal swipe-input payload — list of touch points + their wall-clock timestamps.
 * The CleverKeys preprocessing pipeline only reads these two fields; everything else
 * (velocity, acceleration, key sequence) is derived downstream.
 */
class SwipeInput(
	@JvmField val coordinates: List<PointF>,
	@JvmField val timestamps: List<Long>,
)
