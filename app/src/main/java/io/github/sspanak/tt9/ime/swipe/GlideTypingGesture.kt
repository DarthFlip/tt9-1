/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * Licensed under the Apache License, Version 2.0.
 *
 * Lightly adapted for tt9:
 *   - Dropped FlorisBoard's R.dimen and ViewUtils deps; the caller supplies keySizeDp + density.
 *   - Removed the TextKey/keycode gating — tt9 decides separately whether a tap on a special
 *     key should bypass gesture detection.
 */
package io.github.sspanak.tt9.ime.swipe

import android.view.MotionEvent
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Wrapper class which holds all enums, interfaces and classes for detecting a gesture.
 */
class GlideTypingGesture {
	/**
	 * Detects swipes based on given [MotionEvent]s. Single-finger; additional pointers are ignored.
	 *
	 * @param keySizeDp Width of a typical key in dp — used as the "distance must exceed 1 key" gate.
	 * @param density  Pixel density for converting raw px distances into dp.
	 */
	class Detector(private val keySizeDp: Float, private val density: Float) {
		private var pointerData: PointerData = PointerData(mutableListOf(), 0)
		private val listeners: ArrayList<Listener> = arrayListOf()
		private var pointerId: Int = -1

		companion object {
			private const val MAX_DETECT_TIME = 500
			// Engagement threshold — half what florisboard ships (0.10) so users who spell out
			// a word at ~5cm/s still get recognised as gliding instead of falling through to
			// per-key taps.
			private const val VELOCITY_THRESHOLD = 0.05 // dp per ms
			// Distance-only fallback: even if the user pauses mid-swipe (velocity drops), once
			// the finger has travelled this far from the down point we know it's a gesture.
			private const val DISTANCE_FALLBACK_MULTIPLIER = 1.5f
		}

		private fun px2dp(px: Float): Float = px / density

		/**
		 * Returns true while a gesture is in progress (caller should keep intercepting events).
		 */
		fun onTouchEvent(event: MotionEvent): Boolean {
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN,
				MotionEvent.ACTION_POINTER_DOWN -> {
					if (event.actionMasked == MotionEvent.ACTION_DOWN) {
						resetState()
					}
					if (pointerId != -1) return false
					val pointerIndex = event.actionIndex
					pointerId = event.getPointerId(pointerIndex)
					pointerData.apply {
						positions.add(Position(event.getX(pointerIndex), event.getY(pointerIndex), event.eventTime))
						startTime = System.currentTimeMillis()
					}
					return false
				}
				MotionEvent.ACTION_MOVE -> {
					if (pointerId != event.getPointerId(event.actionIndex)) return false

					val pointerIndex = event.findPointerIndex(pointerId)
					for (i in 0..event.historySize) {
						val pos = when (i) {
							event.historySize -> Position(event.getX(pointerIndex), event.getY(pointerIndex), event.eventTime)
							else -> Position(event.getHistoricalX(pointerIndex, i), event.getHistoricalY(pointerIndex, i), event.getHistoricalEventTime(i))
						}
						pointerData.positions.add(pos)
						if (pointerData.isActuallyGesture == null) {
							val dist = px2dp(pointerData.positions[0].dist(pos))
							val time = (System.currentTimeMillis() - pointerData.startTime) + 1
							// Two engagement paths:
							//   1. Fast enough swipe: distance > 1 key + velocity > threshold.
							//   2. Far enough swipe regardless of speed (user paused mid-glide):
							//      distance > 1.5× key. Pure distance, no velocity gate.
							val velocityOk = dist > keySizeDp && (dist / time) > VELOCITY_THRESHOLD
							val distanceOk = dist > keySizeDp * DISTANCE_FALLBACK_MULTIPLIER
							if (velocityOk || distanceOk) {
								pointerData.isActuallyGesture = true
								io.github.sspanak.tt9.util.Logger.d(
									"tt9/Glide",
									"detector engaged: dist=${dist}dp velocityOk=$velocityOk distanceOk=$distanceOk time=${time}ms bufferedPoints=${pointerData.positions.size}"
								)
								pointerData.positions.take(pointerData.positions.size - 1).forEach { point ->
									listeners.forEach { it.onGlideAddPoint(point) }
								}
							} else if (time > MAX_DETECT_TIME) {
								pointerData.isActuallyGesture = false
								io.github.sspanak.tt9.util.Logger.d(
									"tt9/Glide",
									"detector timed out: dist=${dist}dp time=${time}ms — treated as tap"
								)
							}
						}

						if (pointerData.isActuallyGesture == true) {
							pointerData.positions.last()
								.let { point -> listeners.forEach { it.onGlideAddPoint(point) } }
						}
					}
					return pointerData.isActuallyGesture ?: false
				}
				MotionEvent.ACTION_UP,
				MotionEvent.ACTION_POINTER_UP -> {
					if (pointerId != event.getPointerId(event.actionIndex)) return false
					val wasGesture = pointerData.isActuallyGesture == true
					if (wasGesture) {
						listeners.forEach { listener -> listener.onGlideComplete(pointerData) }
					}
					resetState()
					return wasGesture
				}
				MotionEvent.ACTION_CANCEL -> {
					if (pointerData.isActuallyGesture == true) {
						listeners.forEach { it.onGlideCancelled() }
					}
					resetState()
				}
				else -> return false
			}
			return false
		}

		fun registerListener(listener: Listener) { listeners.add(listener) }

		fun unregisterListener(listener: Listener) { listeners.remove(listener) }

		private fun resetState() {
			pointerData.apply {
				positions.clear()
				startTime = 0
				isActuallyGesture = null
			}
			pointerId = -1
		}

		data class PointerData(
			val positions: MutableList<Position>,
			var startTime: Long,
			var isActuallyGesture: Boolean? = null,
		)

		/**
		 * @param t wall-clock `MotionEvent.eventTime`. Required by the neural decoder for
		 *          real-velocity feature extraction; defaults to 0 for callers that haven't
		 *          been updated to thread it through.
		 */
		data class Position(val x: Float, val y: Float, val t: Long = 0L) {
			fun dist(p2: Position): Float = sqrt((p2.x - x).pow(2) + (p2.y - y).pow(2))
		}
	}

	interface Listener {
		fun onGlideComplete(data: Detector.PointerData) {}
		fun onGlideAddPoint(point: Detector.Position) {}
		fun onGlideCancelled() {}
	}
}
