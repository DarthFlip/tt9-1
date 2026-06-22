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
}
