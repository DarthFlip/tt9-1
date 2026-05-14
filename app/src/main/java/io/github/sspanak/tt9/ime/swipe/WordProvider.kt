/*
 * Copyright (C) 2025 tt9 contributors. Apache 2.0.
 */
package io.github.sspanak.tt9.ime.swipe

/**
 * Source of candidate words + their frequencies for gesture-typing decoding.
 *
 * Decoupled from tt9's T9 dictionary so the classifier remains independent of how the word list
 * is produced (flat asset, SQLite dump, in-memory, etc).
 */
interface WordProvider {
	/** Every word the classifier should consider when decoding gestures. */
	fun getListOfWords(): List<String>

	/** Frequency score in [0, 1]; higher = more common. Unknown words should return ~0. */
	fun getFrequencyForWord(word: String): Float
}

/** No-op fallback so the classifier never NPEs when no provider has been wired yet. */
object EmptyWordProvider : WordProvider {
	override fun getListOfWords(): List<String> = emptyList()
	override fun getFrequencyForWord(word: String): Float = 0f
}
