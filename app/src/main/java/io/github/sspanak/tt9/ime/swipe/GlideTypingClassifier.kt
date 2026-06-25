/*
 * Copyright (C) 2025 The FlorisBoard Contributors, adapted for tt9.
 * Apache 2.0.
 */
package io.github.sspanak.tt9.ime.swipe

/**
 * Decodes a pointer path into ranked candidate words.
 *
 * Adapted from FlorisBoard: we dropped the Subtype concept (tt9 treats language switching
 * separately) and the layout/word loaders take plain objects rather than subtypes.
 */
interface GlideTypingClassifier {
	fun addGesturePoint(position: GlideTypingGesture.Detector.Position)
	fun setLayout(keys: List<SwipeKey>)
	fun setWordProvider(provider: WordProvider)
	/**
	 * Locked letter prefix already in the composing word (typed via QWERTY tap). When non-empty,
	 * candidates must start with [prefix] and matching is done against the SUFFIX only — the user's
	 * gesture starts at the letter after [prefix], not the first letter of the word.
	 * Pass "" to clear. Lowercase ASCII expected.
	 */
	fun setWordPrefix(prefix: String)
	fun initGestureFromPointerData(pointerData: GlideTypingGesture.Detector.PointerData)
	fun getSuggestions(maxSuggestionCount: Int, gestureCompleted: Boolean): List<String>
	fun clear()

	/** True once layout + word provider are bound and the decoder is ready to score gestures. */
	val ready: Boolean

	/** Read-only view of the currently bound key set — used by SwipeableKeyboardContainer
	 *  for trail-rendering geometry. */
	val layoutKeys: List<SwipeKey>

	/**
	 * Worker-thread entry point: score a captured gesture snapshot and return ranked candidates.
	 * [contextWords] are MindReader's next-word predictions — implementations should apply a
	 * context bonus to candidates in the set.
	 */
	fun analyzeGesture(
		snapshot: StatisticalGlideTypingClassifier.Gesture,
		maxSuggestionCount: Int,
		contextWords: Set<String> = emptySet(),
	): List<String>

	/** Capture the in-progress gesture and clear it atomically — used at gesture completion to
	 *  hand the worker thread an immutable snapshot. */
	fun snapshotGestureAndClear(): StatisticalGlideTypingClassifier.Gesture

	/** Clone the in-progress gesture without clearing — used for mid-gesture preview scoring. */
	fun cloneInternalGesture(): StatisticalGlideTypingClassifier.Gesture
}
