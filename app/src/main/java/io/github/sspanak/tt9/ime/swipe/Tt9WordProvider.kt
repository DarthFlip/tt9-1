/*
 * Copyright (C) 2025 tt9 contributors. Apache 2.0.
 */
package io.github.sspanak.tt9.ime.swipe

import android.content.Context
import android.os.Handler
import android.os.Looper
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
	// Inverse layer: when the user backspaces a just-glide-committed word, the rejected word's
	// effective frequency is scaled DOWN. Stored as a penalty in [0, 0.9]; the multiplier
	// applied in getFrequencyForWord is (1 - penalty). Accumulates across multiple rejections
	// of the same word — three strikes leaves it ~very-low priority.
	private val sessionPenalty = java.util.concurrent.ConcurrentHashMap<String, Float>()
	// Confused-pair map: when the user rejects A then accepts B, we remember A→B. Next time
	// the ranker emits both in the top-K with A ranked above B, we swap them so B wins. The
	// per-word session boost/penalty already pushes ranking in the right direction; the pair
	// map is the targeted final-pass swap for the cases where similar-shape rivals stay too
	// close in score for the boost alone to flip them. Lowercase keys; one preferred per
	// rejected (last write wins — if the user later rejects A in favor of C, A→C overwrites).
	private val confusedPairs = java.util.concurrent.ConcurrentHashMap<String, String>()

	val isLoaded: Boolean get() = words.isNotEmpty()

	override fun getListOfWords(): List<String> = words

	override fun getFrequencyForWord(word: String): Float {
		val base = freqByWord[word] ?: 0f
		val boost = sessionBoost[word] ?: 0f
		val penalty = sessionPenalty[word] ?: 0f
		val penaltyMul = (1f - penalty).coerceAtLeast(0.05f)
		return ((base + boost) * penaltyMul).coerceAtMost(1f)
	}

	/** The preferred replacement for [rejected] (lowercase), or null if no pair recorded. */
	override fun getConfusedPreferred(rejected: String): String? = confusedPairs[rejected]

	companion object {
		private val cache = HashMap<Int, Tt9WordProvider>()

		// Disk-backed persistence for per-user learning (sessionBoost, sessionPenalty,
		// confusedPairs). Without this, every IME process kill / device reboot would wipe
		// everything the user has taught the keyboard. We store one TSV-encoded string per map
		// per language in a dedicated SharedPreferences file, written async via apply() and
		// debounced so a burst of acceptances doesn't hammer the disk.
		private const val PREFS_NAME = "tt9_word_provider_learning"
		private const val PREFIX_BOOST = "lang_%d_boost"
		private const val PREFIX_PENALTY = "lang_%d_penalty"
		private const val PREFIX_PAIRS = "lang_%d_pairs"
		private const val SAVE_DEBOUNCE_MS = 1500L
		@Volatile private var appContext: Context? = null
		private val saveHandler = Handler(Looper.getMainLooper())
		private val pendingSaves = java.util.concurrent.ConcurrentHashMap<Int, Runnable>()

		/**
		 * Wire in an application Context so the learning store can save/restore. Idempotent;
		 * subsequent calls update to the most recent applicationContext (handy if the IME service
		 * is recreated). Should be called once at IME init from a place that has Context — see
		 * TypingHandler.determineLanguage.
		 */
		@JvmStatic
		fun attachContext(context: Context) {
			appContext = context.applicationContext
		}
		// Additive bump per acceptance. Bumped 0.10 → 0.25 so a single user choice meaningfully
		// outweighs the shape-distance signal for similar-shape candidates. With 0.10, the user
		// had to pick the same word 3-4 times before it reliably reranked above shape-similar
		// distractors. At 0.25, a single acceptance is decisive next time.
		private const val FREQUENCY_BUMP_PER_USE: Float = 0.25f
		/** Per-rejection penalty added to sessionPenalty. Capped at 0.9 in the merger above. */
		private const val PENALTY_PER_REJECTION: Float = 0.30f

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
			// If the user is re-accepting a word they previously rejected, clear the penalty —
			// they've changed their mind. Avoids cementing wrong rejections.
			provider.sessionPenalty.remove(word)
			scheduleSave(languageId)
		}

		/**
		 * Penalize [word] for [languageId] — call when the user has just rejected this word
		 * (e.g. backspaced a just-glide-committed suggestion). Accumulates across rejections;
		 * three rejections drop the word to ~5% of its base frequency.
		 */
		@JvmStatic
		fun penalizeFrequency(languageId: Int, word: String) {
			if (word.isEmpty()) return
			val provider = synchronized(cache) { cache[languageId] } ?: return
			if (!provider.isLoaded) return
			val key = word.lowercase()
			provider.sessionPenalty.merge(key, PENALTY_PER_REJECTION) { a, b ->
				(a + b).coerceAtMost(0.9f)
			}
			scheduleSave(languageId)
		}

		/**
		 * Record that the user rejected [rejected] and accepted [accepted] (lowercase, in
		 * [languageId]). Stored as a directional pair consulted by the classifier's final
		 * re-rank pass — when both words appear in the top-K, [accepted] gets swapped above
		 * [rejected]. Also stacks an extra session boost on [accepted] so it climbs in cases
		 * where the rejected rival isn't in the top-K.
		 */
		@JvmStatic
		fun recordConfusedPair(languageId: Int, rejected: String, accepted: String) {
			if (rejected.isEmpty() || accepted.isEmpty() || rejected == accepted) return
			val provider = synchronized(cache) { cache[languageId] } ?: return
			if (!provider.isLoaded) return
			val r = rejected.lowercase()
			val a = accepted.lowercase()
			provider.confusedPairs[r] = a
			// Stack an extra boost on the accepted word — bumpFrequency already ran on accept,
			// this is the "you preferred it after a rejection" signal that the ranker should
			// give it a heavier nudge than a plain acceptance would.
			provider.sessionBoost.merge(a, FREQUENCY_BUMP_PER_USE) { x, y -> (x + y).coerceAtMost(1f) }
			scheduleSave(languageId)
		}

		/** Static accessor for the classifier's final swap pass; null if no provider/pair. */
		@JvmStatic
		fun getConfusedPreferred(languageId: Int, rejected: String): String? {
			if (rejected.isEmpty()) return null
			val provider = synchronized(cache) { cache[languageId] } ?: return null
			if (!provider.isLoaded) return null
			return provider.getConfusedPreferred(rejected.lowercase())
		}

		/**
		 * Debounced save. Coalesces multiple bumps/penalties/pair-records into a single disk
		 * write per language ~1.5 s after the last mutation. Cheap to call from the hot path.
		 */
		private fun scheduleSave(languageId: Int) {
			val ctx = appContext ?: return
			val existing = pendingSaves.remove(languageId)
			if (existing != null) saveHandler.removeCallbacks(existing)
			val provider = synchronized(cache) { cache[languageId] } ?: return
			val task = Runnable {
				pendingSaves.remove(languageId)
				saveLearningSync(ctx, languageId, provider)
			}
			pendingSaves[languageId] = task
			saveHandler.postDelayed(task, SAVE_DEBOUNCE_MS)
		}

		private fun saveLearningSync(ctx: Context, languageId: Int, provider: Tt9WordProvider) {
			val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
			prefs.edit()
				.putString(PREFIX_BOOST.format(languageId), encodeFloatMap(provider.sessionBoost))
				.putString(PREFIX_PENALTY.format(languageId), encodeFloatMap(provider.sessionPenalty))
				.putString(PREFIX_PAIRS.format(languageId), encodeStringMap(provider.confusedPairs))
				.apply()
		}

		private fun restoreLearning(ctx: Context, languageId: Int, provider: Tt9WordProvider) {
			val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
			decodeFloatMap(prefs.getString(PREFIX_BOOST.format(languageId), null), provider.sessionBoost)
			decodeFloatMap(prefs.getString(PREFIX_PENALTY.format(languageId), null), provider.sessionPenalty)
			decodeStringMap(prefs.getString(PREFIX_PAIRS.format(languageId), null), provider.confusedPairs)
		}

		// TSV encoding: word\tvalue\n... — robust against tabs/newlines in keys because
		// dictionary words don't contain either. Skips empty / malformed lines on decode.
		private fun encodeFloatMap(m: Map<String, Float>): String {
			if (m.isEmpty()) return ""
			val sb = StringBuilder(m.size * 16)
			for ((k, v) in m) {
				if (k.contains('\t') || k.contains('\n')) continue
				sb.append(k).append('\t').append(v).append('\n')
			}
			return sb.toString()
		}

		private fun encodeStringMap(m: Map<String, String>): String {
			if (m.isEmpty()) return ""
			val sb = StringBuilder(m.size * 24)
			for ((k, v) in m) {
				if (k.contains('\t') || k.contains('\n') || v.contains('\t') || v.contains('\n')) continue
				sb.append(k).append('\t').append(v).append('\n')
			}
			return sb.toString()
		}

		private fun decodeFloatMap(s: String?, into: java.util.concurrent.ConcurrentHashMap<String, Float>) {
			if (s.isNullOrEmpty()) return
			for (line in s.split('\n')) {
				if (line.isEmpty()) continue
				val tab = line.indexOf('\t')
				if (tab <= 0) continue
				val v = line.substring(tab + 1).toFloatOrNull() ?: continue
				into[line.substring(0, tab)] = v
			}
		}

		private fun decodeStringMap(s: String?, into: java.util.concurrent.ConcurrentHashMap<String, String>) {
			if (s.isNullOrEmpty()) return
			for (line in s.split('\n')) {
				if (line.isEmpty()) continue
				val tab = line.indexOf('\t')
				if (tab <= 0 || tab >= line.length - 1) continue
				into[line.substring(0, tab)] = line.substring(tab + 1)
			}
		}

		/**
		 * Java-friendly fire-and-forget preload: triggers the async vocabulary load for [language]
		 * if not already cached. No callback — useful from Java sites that just want the cache to
		 * be warm by the time it's queried later (e.g. fuzzy lookups in WordPredictions).
		 */
		@JvmStatic
		fun preload(language: Language) {
			load(language) { /* no-op; the cache itself is the side effect */ }
		}

		/**
		 * Returns up to [maxResults] vocabulary words starting with [prefix] (lowercase), sorted
		 * by descending frequency. DataStore.getAllWords returns words in dictionary insertion
		 * order (typically by digit-sequence id then by frequency-within-sequence), so a naive
		 * "first N matches" scan would surface arbitrary low-frequency words like "thy / thx"
		 * before the obvious completions "the / this / they / them". Doing a full O(n) scan +
		 * sort over the cached vocabulary is sub-millisecond at 50k words and ensures the most
		 * common completions surface first.
		 *
		 * Empty list if the provider for [languageId] isn't loaded yet. Used by WordPredictions
		 * to supplement the SQLite digit-sequence query on QWERTY-typed prefixes.
		 */
		@JvmStatic
		fun getWordsByPrefix(languageId: Int, prefix: String, maxResults: Int): java.util.ArrayList<String> {
			val out = java.util.ArrayList<String>()
			if (prefix.isEmpty() || maxResults <= 0) return out
			val provider = synchronized(cache) { cache[languageId] } ?: return out
			if (!provider.isLoaded) return out
			val lowered = prefix.lowercase()

			val matches = java.util.ArrayList<Pair<String, Float>>()
			for (w in provider.words) {
				if (w.length > lowered.length && w.startsWith(lowered)) {
					matches.add(w to (provider.freqByWord[w] ?: 0f))
				}
			}
			matches.sortByDescending { it.second }
			val limit = minOf(maxResults, matches.size)
			for (i in 0 until limit) out.add(matches[i].first)
			return out
		}

		/**
		 * O(1) membership test against the loaded vocabulary for [languageId]. Lowercases [word]
		 * before lookup so callers don't have to. Returns false if the provider hasn't been loaded
		 * yet (no false positives — silently degrades to "no fuzzy candidates this round"). Used by
		 * the QWERTY tap path's Damerau-Levenshtein-1 fuzzy candidate generation.
		 */
		@JvmStatic
		fun containsWord(languageId: Int, word: String): Boolean {
			if (word.isEmpty()) return false
			val provider = synchronized(cache) { cache[languageId] } ?: return false
			if (!provider.isLoaded) return false
			return provider.freqByWord.containsKey(word.lowercase())
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
				// Restore previously-persisted learning (boost/penalty/pairs) AFTER the vocab
				// is loaded so the maps are immediately usable for ranking. Idempotent on
				// subsequent loads — the cache check at the top of load() short-circuits
				// before we'd re-restore. Safe to call without attachContext (no-op if context
				// hasn't been wired yet — early bumps will save on the next mutation once
				// context arrives).
				appContext?.let { restoreLearning(it, langId, provider) }
				onReady(provider)
			}, language)
		}
	}
}
