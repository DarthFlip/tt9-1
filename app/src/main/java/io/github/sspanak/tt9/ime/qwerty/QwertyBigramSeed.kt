/*
 * Copyright (C) 2026 tt9 contributors. Apache 2.0.
 */
package io.github.sspanak.tt9.ime.qwerty

import android.content.Context
import io.github.sspanak.tt9.util.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * In-memory bigram lookup for the QWERTY on-screen keyboard's cold-start next-word predictions.
 *
 * Loaded once per process from a build-time-bundled asset (`assets/seed/${lang}_bigrams.tsv.gz`)
 * on the first QWERTY prediction request. Subsequent lookups are O(1) map access.
 *
 * Storage: [Map<String, List<Pair<String, Int>>>] keyed on word1 (lowercase), value = list of
 * (word2, frequency) sorted by frequency desc. Memory footprint at 6,687 English bigrams is
 * roughly 500 KB — small, stays for the process lifetime.
 *
 * **QWERTY-only.** T9 pipeline never touches this class. Consumed by WordPredictions when its
 * `qwertyOnly` flag is true and by SuggestionHandler when the active input mode is the QWERTY
 * pipeline's Predictive instance. Zero risk of contaminating T9 predictions with seeded data.
 */
object QwertyBigramSeed {
	private const val LOG_TAG = "QwertyBigramSeed"
	private const val ASSET_DIR = "seed"

	/** Per-language cached lookup. Key = language code (e.g. "en"), value = the loaded map. */
	@Volatile
	private var loaded: MutableMap<String, Map<String, List<Pair<String, Int>>>> = HashMap()
	private val loadLock = Any()

	/**
	 * Trigger the lazy load for [languageCode] (e.g. "en"). Idempotent: a second call for the
	 * same language is a no-op. Runs on the calling thread — invoke from a worker if the
	 * caller is on main and the map size is large. For the current 6.6k-row English asset the
	 * load is under 50 ms on the F1.
	 */
	@JvmStatic
	fun ensureLoaded(context: Context, languageCode: String) {
		val key = languageCode.lowercase()
		if (loaded.containsKey(key)) return
		synchronized(loadLock) {
			if (loaded.containsKey(key)) return
			val map = loadFromAsset(context, key)
			// Replace the map atomically so readers never see a partial map. HashMap iteration
			// during a put would be a race; the volatile field swap avoids it.
			val updated = HashMap(loaded)
			updated[key] = map
			loaded = updated
		}
	}

	/**
	 * Returns up to [max] words that most commonly follow [word1] in the seed corpus, in
	 * descending frequency order. Empty list if [word1] has no seeded successors or if the
	 * language hasn't been loaded yet.
	 */
	@JvmStatic
	fun getNextWordsAfter(languageCode: String, word1: String, max: Int): List<String> {
		if (word1.isEmpty() || max <= 0) return emptyList()
		val langMap = loaded[languageCode.lowercase()] ?: return emptyList()
		val entries = langMap[word1.lowercase()] ?: return emptyList()
		return if (entries.size <= max) entries.map { it.first } else entries.take(max).map { it.first }
	}

	/**
	 * O(k) check for "does [word2] appear as a seeded successor of [word1]?" where k is the
	 * seeded-successor list length (small — the head of the corpus is dominated by a handful
	 * of common bigrams per pivot word). Case-insensitive.
	 */
	@JvmStatic
	fun contains(languageCode: String, word1: String, word2: String): Boolean {
		val langMap = loaded[languageCode.lowercase()] ?: return false
		val entries = langMap[word1.lowercase()] ?: return false
		val target = word2.lowercase()
		for ((w, _) in entries) {
			if (w == target) return true
		}
		return false
	}

	private fun loadFromAsset(context: Context, languageCode: String): Map<String, List<Pair<String, Int>>> {
		val assetName = "$ASSET_DIR/${languageCode}_bigrams.tsv.gz"
		val map = HashMap<String, ArrayList<Pair<String, Int>>>()
		val start = System.currentTimeMillis()
		try {
			context.applicationContext.assets.open(assetName).use { raw ->
				GZIPInputStream(raw).use { gz ->
					BufferedReader(InputStreamReader(gz, Charsets.UTF_8)).use { reader ->
						while (true) {
							val line = reader.readLine() ?: break
							if (line.isEmpty()) continue
							// TSV: word1 \t word2 \t T9-sequence \t frequency
							val parts = line.split('\t')
							if (parts.size < 4) continue
							val w1 = parts[0]
							val w2 = parts[1]
							val freq = parts[3].toIntOrNull() ?: continue
							val list = map.getOrPut(w1) { ArrayList(4) }
							list.add(w2 to freq)
						}
					}
				}
			}
		} catch (e: Exception) {
			Logger.w(LOG_TAG, "Failed to load bigram seed for $languageCode: $e")
			return emptyMap()
		}
		val elapsed = System.currentTimeMillis() - start
		Logger.d(LOG_TAG, "Loaded ${map.size} pivot words for $languageCode in ${elapsed}ms.")
		return map
	}
}
