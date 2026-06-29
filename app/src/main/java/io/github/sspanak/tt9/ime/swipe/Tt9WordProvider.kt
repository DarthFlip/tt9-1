/*
 * Copyright (C) 2025 tt9 contributors. Apache 2.0.
 */
package io.github.sspanak.tt9.ime.swipe

import io.github.sspanak.tt9.db.DataStore
import io.github.sspanak.tt9.languages.Language

/**
 * Word source backed by tt9's factory dictionary. Loads the full per-language vocabulary from
 * SQLite on a background thread and hands the classifier a flat List<String> plus a frequency map
 * normalised to [0, 1] by dividing each raw count by the language's most-common word.
 *
 * Instances are cached per language id so rapid language cycling doesn't re-hit the DB.
 */
class Tt9WordProvider private constructor() : WordProvider {
	@Volatile private var words: List<String> = emptyList()
	@Volatile private var freqByWord: Map<String, Float> = emptyMap()
	// Per-process in-memory boost layered on top of the SQLite-loaded frequencies. Lets the user's
	// just-accepted words rerank for glide without re-reading the DB. The disk-side T9 frequency
	// column is still updated by the existing T9 acceptance path.
	private val sessionBoost = java.util.concurrent.ConcurrentHashMap<String, Float>()

	val isLoaded: Boolean get() = words.isNotEmpty()

	override fun getListOfWords(): List<String> = words

	override fun getFrequencyForWord(word: String): Float {
		val base = freqByWord[word] ?: 0f
		val boost = sessionBoost[word] ?: 0f
		return (base + boost).coerceAtMost(1f)
	}

	companion object {
		private val cache = HashMap<Int, Tt9WordProvider>()
		// Additive bump per acceptance. Bumped 0.10 → 0.25 so a single user choice meaningfully
		// outweighs the shape-distance signal for similar-shape candidates. With 0.10, the user
		// had to pick the same word 3-4 times before it reliably reranked above shape-similar
		// distractors. At 0.25, a single acceptance is decisive next time.
		private const val FREQUENCY_BUMP_PER_USE: Float = 0.25f

		/**
		 * Increment the in-memory frequency boost for [word] in [languageId]'s cached provider.
		 * No-op if the language hasn't been loaded yet (the boost would be lost on later load —
		 * which is fine; disk-side frequency catches up).
		 */
		@JvmStatic
		fun bumpFrequency(languageId: Int, word: String) {
			if (word.isEmpty()) return
			val provider = synchronized(cache) { cache[languageId] } ?: return
			if (!provider.isLoaded) return
			provider.sessionBoost.merge(word, FREQUENCY_BUMP_PER_USE) { a, b -> (a + b).coerceAtMost(1f) }
		}

		/**
		 * Fetches (or reuses a cached) provider for [language]. [onReady] is invoked with a loaded
		 * provider once the vocabulary is in memory. If the language has no dictionary yet (e.g.
		 * import still pending) the provider stays empty and [onReady] is still invoked so the
		 * caller can decide whether to fall back.
		 *
		 * The callback runs on the DataStore executor thread — callers that touch UI-facing state
		 * (like the classifier) must post it back to the main thread themselves.
		 */
		fun load(language: Language, onReady: (Tt9WordProvider) -> Unit) {
			val langId = language.id
			val cached = synchronized(cache) { cache[langId] }
			if (cached != null && cached.isLoaded) {
				onReady(cached)
				return
			}
			val provider = cached ?: Tt9WordProvider().also {
				synchronized(cache) { cache[langId] = it }
			}
			DataStore.getAllWords({ wordList ->
				if (wordList == null || wordList.isEmpty()) {
					onReady(provider)
					return@getAllWords
				}
				var maxFreq = 1
				for (i in 0 until wordList.size) {
					val f = wordList[i].frequency
					if (f > maxFreq) maxFreq = f
				}
				val inv = 1f / maxFreq
				val list = ArrayList<String>(wordList.size)
				val freq = HashMap<String, Float>(wordList.size)
				for (i in 0 until wordList.size) {
					val w = wordList[i]
					list.add(w.word)
					freq[w.word] = (w.frequency * inv).coerceAtMost(1f)
				}
				provider.words = list
				provider.freqByWord = freq
				onReady(provider)
			}, language)
		}
	}
}
