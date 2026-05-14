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
	fun initGestureFromPointerData(pointerData: GlideTypingGesture.Detector.PointerData)
	fun getSuggestions(maxSuggestionCount: Int, gestureCompleted: Boolean): List<String>
	fun clear()
}
