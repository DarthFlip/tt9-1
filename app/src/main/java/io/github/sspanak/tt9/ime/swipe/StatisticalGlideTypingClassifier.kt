/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * Licensed under the Apache License, Version 2.0.
 *
 * Adapted for tt9:
 *   - Replaced TextKey with tt9's SwipeKey (flat Rect + code).
 *   - Replaced nlpManager with a pluggable WordProvider.
 *   - Dropped Subtype — word data and layout are set independently.
 *
 * Credit: algorithm write-up by Étienne Desticourt
 * (https://github.com/AnySoftKeyboard/AnySoftKeyboard/pull/1870).
 */
package io.github.sspanak.tt9.ime.swipe

import androidx.collection.LruCache
import androidx.collection.SparseArrayCompat
import androidx.collection.set
import java.text.Normalizer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class StatisticalGlideTypingClassifier : GlideTypingClassifier {
	private var wordProvider: WordProvider = EmptyWordProvider

	private val gesture = Gesture()
	private var keysByCharacter: SparseArrayCompat<SwipeKey> = SparseArrayCompat()
	private var words: List<String> = emptyList()
	private var keys: ArrayList<SwipeKey> = arrayListOf()
	private var pruner: Pruner? = null
	private var distanceThresholdSquared = 0
	private var layoutReady = false
	private var wordsReady = false
	private var wordPrefix: String = ""

	override val ready: Boolean get() = layoutReady && wordsReady && pruner != null

	companion object {
		// Tightening to 5.0 was too aggressive — gestures shorter than ~30pt returned 0 candidates
		// because no word's ideal length fit within 5×radius. The continuous length-match penalty
		// in the fine pass already differentiates similar-shape candidates, so the pruner doesn't
		// need to be aggressive. Back to 7.0 (between Florisboard's permissive 8.42 and the
		// over-tight 5.0). Empirical: never see all-empty results at 7.0.
		// FlorisBoard/SHARK² use 8.42×keyRadius. Earlier we tried 5.0 (too aggressive — short
		// swipes returned 0 candidates) then 7.0 (still under upstream). Bumping to 8.42
		// matches the literature: the length-match continuous penalty inside the fine pass
		// already differentiates similar-shape candidates, so the pruner only needs to drop
		// obviously-wrong-length words. Looser pruner = more candidates surface.
		private const val PRUNING_LENGTH_THRESHOLD = 8.42
		// Halved from 200 → 100 to bring the warm-cache gesture cost under 200ms. With the
		// cached resampled+normalized ideal gestures (cachedResampledIdeal), 100 sample points
		// still captures the shape; the bottleneck was the per-point distance/direction math.
		// 100 → 64. The original SHARK² paper uses 100 sampling points, but per-point cost
		// dominates the fine-pass loop. 64 still preserves direction-change resolution (corner
		// points correlate with letters) but cuts shape distance compute by 36%.
		private const val REFERENCE_SAMPLES: Int = 64
		private const val SAMPLES_PER_KEY: Float = 25f
		private const val MIN_SAMPLES: Int = 80
		private const val MAX_SAMPLES: Int = 240
		// Kept for documentation; the Gaussian probability path was replaced with a direct
		// 1/(1+distance) score that avoids the expensive pow+exp on every word.
		private const val SHAPE_STD = 22.08f
		private const val LOCATION_STD = 0.5109f
		private const val SUGGESTION_CACHE_SIZE = 5
		// Start- and end-key search widened (was 2 each). The hard cutoff used to be brittle —
		// a finger landing one key off-target killed the right candidate. Now both sides allow
		// a wider candidate pool and a SOFT quadratic proximity penalty (below) sorts them by
		// closeness to the actual key center. Pattern from AnySoftKeyboard's GestureTypingDetector.
		private const val START_KEY_CANDIDATES: Int = 5
		private const val END_KEY_CANDIDATES: Int = 5
		// Proximity penalty factors. AnySoftKeyboard's original (0.000667, 0.000333) are
		// calibrated for their Gaussian-product confidence which lands in the 10^4 range; with
		// our direct 1/(1+d) score confidence is in single digits, so the penalty was lost in
		// the noise (a 50px end miss added 0.83 to a 1316 confidence — invisible). Bumped 100×
		// so end-key accuracy actually moves ranking. End factor stays HALVED — users
		// systematically overshoot at swipe-out.
		private const val START_PROXIMITY_PENALTY_FACTOR: Float = 0.0667f
		private const val END_PROXIMITY_PENALTY_FACTOR: Float = 0.0333f
		// Direction penalty: each segment whose direction disagrees with the ideal-path
		// direction contributes more to total shape distance. cosθ=+1 → 1×, cosθ=0 → 1+F×,
		// cosθ=-1 → 1+2F×. Bringing it down from 1.0 → 0.3 because the high value was over-
		// penalising legitimate paths that wobble between letters.
		private const val DIRECTION_PENALTY_FACTOR: Float = 0.3f
		// Length-match bonus: penalises candidates whose ideal-gesture LENGTH (in pixels) differs
		// from the user's gesture length. pruneByLength is binary in/out; this is the continuous
		// signal that lets 5-letter "hello" beat 4-letter "hero" when the user clearly drew a
		// longer zigzag path. Penalty is added to confidence; magnitude scales with the difference
		// divided by key radius so it's comparable to the proximity-penalty factors.
		private const val LENGTH_MATCH_PENALTY_FACTOR: Float = 0.5f
		// Frequency floor — bumped from +1 to +5 so rare-word penalty isn't so harsh. Combined
		// with log compression below, common words still consistently win ties without drowning
		// out the shape signal.
		private const val FREQUENCY_FLOOR: Float = 5f
		/** Top-N most-frequent words to score for swipe. Past ~40k the tail is pure distractor
		 *  noise — words that shape-match but the user never types. */
		private const val VOCAB_CAP: Int = 40_000
		// N-gram context boost: when a candidate appears in MindReader's current next-word
		// predictions (bigram/trigram lookahead based on prior committed words), its score
		// gets multiplied by this. 4× is enough to lift a contextually-correct word over a
		// shape-similar wrong one — e.g. "happy birthday" makes "birthday" beat "boundary".
		private const val CONTEXT_BOOST: Float = 4f
		// Round C: corner-point two-pass scoring.
		// Coarse pass extracts direction-change corners from both user and ideal gestures and
		// scores them with a simple per-point distance. Cheap (corners are ~5-15 points vs 100
		// resampled) — used to pick the top-N candidates that then run through the full fine
		// pass. AnySoftKeyboard pattern; CURVATURE_THRESHOLD is the complement of their 170°.
		private const val CORNER_THRESHOLD_RADIANS: Float = 0.52f  // ~30°
		// Fixed sample count for corner sequences — different words have different corner counts
		// (5-15 typically) but we need uniform-length comparison. Small enough that the coarse
		// pass is genuinely cheap.
		private const val CORNER_SAMPLE_COUNT: Int = 16
		// Top-N candidates carried from coarse → fine pass. Tightened 300 → 100 because user
		// was seeing 1-2 second waits on cold-cache gestures (kosher firmware kills the IME
		// service between sessions, defeating the warm-cache speedup). Fewer fine-pass words
		// means lower worst-case latency. If correct candidates start being dropped at the
		// coarse stage we can bump this back up.
		// Tightened from 100 → 30 for latency. Logs showed 1.3s per gesture with 100 candidates
		// in the fine pass — each scoring is ~12ms (ideal-gesture gen + resample + per-point
		// shape distance over 100 samples). 30 candidates × 12ms = 360ms target. The coarse
		// pass already ranks corners-only shape distance; the top 30 are very likely to contain
		// the right word, and the fine pass is just for ordering.
		private const val COARSE_TOP_N: Int = 30
		// Minimum post-prune candidate set size before the coarse pass is even worth running.
		// Lowered 400 → 150 so smaller candidate sets ALSO benefit from the coarse-prune
		// speedup. Below 150 the fine pass alone is genuinely fast.
		private const val COARSE_PASS_MIN_SET_SIZE: Int = 150
	}

	override fun addGesturePoint(position: GlideTypingGesture.Detector.Position) {
		if (!gesture.isEmpty) {
			val dx = gesture.getLastX() - position.x
			val dy = gesture.getLastY() - position.y
			if (dx * dx + dy * dy > distanceThresholdSquared) {
				gesture.addPoint(position.x, position.y, position.t)
			}
		} else {
			gesture.addPoint(position.x, position.y, position.t)
		}
	}

	// Per-word, per-prefix-length cache of resampled+normalized ideal gestures. The ideal path
	// for any given word on a fixed keyboard layout never changes, so doing the resample +
	// normalizeByBoxSide every gesture is pure waste — that was the bulk of the 1-2 sec per-
	// gesture cost. Cleared on setLayout / setWordProvider. Keyed by "word|prefixLen|variant".
	private val cachedResampledIdeal = java.util.concurrent.ConcurrentHashMap<String, List<Pair<Gesture, Gesture>>>()

	// Round C: parallel cache of CORNER-extracted-and-resampled ideal gestures. Used by the
	// coarse pass to pick the top-N candidates that get the full fine pass.
	private val cachedCornerIdeal = java.util.concurrent.ConcurrentHashMap<String, List<Gesture>>()

	override fun setLayout(keys: List<SwipeKey>) {
		if (this.keys == keys && layoutReady) return

		keysByCharacter.clear()
		this.keys.clear()
		cachedResampledIdeal.clear()
		cachedCornerIdeal.clear()
		keys.forEach {
			keysByCharacter[it.code] = it
			this.keys.add(it)
		}
		val first = keys.firstOrNull() ?: return
		distanceThresholdSquared = (first.width / 4).toInt()
		distanceThresholdSquared *= distanceThresholdSquared
		layoutReady = true
		maybeInitPruner()
	}

	override fun setWordProvider(provider: WordProvider) {
		this.wordProvider = provider
		// Cap to top-frequency words. Past ~40k the long tail is noise — rare words that
		// shape-match the gesture but aren't what the user typed. Per the SHARK² research,
		// 40k covers >99% of typed text; the extras only hurt accuracy by adding distractor
		// candidates. Also halves the pruner build cost.
		val allWords = provider.getListOfWords()
		this.words = if (allWords.size > VOCAB_CAP) {
			allWords.sortedByDescending { provider.getFrequencyForWord(it) }.subList(0, VOCAB_CAP)
		} else {
			allWords
		}
		this.wordsReady = true
		lruSuggestionCache.evictAll()
		cachedResampledIdeal.clear()
		cachedCornerIdeal.clear()
		maybeInitPruner()
		// Removed: precacheIdealGestures(). Each cached Gesture allocates two 500-float arrays
		// (4KB per Gesture), but resampled gestures only use 100 points — 80% waste. For 5000
		// words × ~3 Gesture variants, pre-filling blew through the 268MB heap → OOM crashes.
		// Lazy caching at query time still works, just first-gesture pays the cost. To revisit:
		// either shrink the Gesture backing arrays to fit exact size, or precache only top-N
		// most frequent words.
	}

	override fun setWordPrefix(prefix: String) {
		val normalized = prefix.lowercase()
		if (this.wordPrefix == normalized) return
		this.wordPrefix = normalized
		// Cached results were computed under the old prefix — they're stale even though the gesture
		// key compares equal.
		lruSuggestionCache.evictAll()
	}

	private fun maybeInitPruner() {
		if (!layoutReady || !wordsReady) return
		pruner = Pruner(PRUNING_LENGTH_THRESHOLD, words, keysByCharacter)
	}

	override fun initGestureFromPointerData(pointerData: GlideTypingGesture.Detector.PointerData) {
		for (position in pointerData.positions) addGesturePoint(position)
	}

	// Cache key: (gesture, maxCount, contextHash). Context is part of the key because the same
	// gesture with different prior-word context can yield different rankings.
	private val lruSuggestionCache = LruCache<Triple<Gesture, Int, Int>, List<String>>(SUGGESTION_CACHE_SIZE)
	override fun getSuggestions(maxSuggestionCount: Int, gestureCompleted: Boolean): List<String> {
		return analyzeGesture(this.gesture, maxSuggestionCount)
	}

	/**
	 * Worker-thread-safe scoring entry point. Takes a gesture snapshot (clone the live one before
	 * passing it in) so the caller can clear or mutate the classifier's own state in parallel.
	 * All other state read here (keys, pruner, words, wordPrefix) is set up at layout / load
	 * time and stable during a gesture's lifetime.
	 *
	 * [contextWords] (lowercase) are MindReader's current next-word predictions based on the
	 * preceding text; candidates that match get a CONTEXT_BOOST multiplier in scoring. Empty
	 * set = no context bias.
	 */
	override fun analyzeGesture(snapshot: Gesture, maxSuggestionCount: Int, contextWords: Set<String>): List<String> {
		if (!ready) return emptyList()
		if (snapshot.pointCount < 2) return emptyList()
		// Cache key includes contextWords because the same gesture with different context can
		// yield different rankings. Use hashCode of the set as a compact discriminator.
		val cacheKey = Triple(snapshot, maxSuggestionCount, contextWords.hashCode())
		synchronized(lruSuggestionCache) {
			lruSuggestionCache.get(cacheKey)?.let { return it }
		}
		val suggestions = unCachedGetSuggestions(maxSuggestionCount, snapshot, contextWords)
		synchronized(lruSuggestionCache) {
			lruSuggestionCache.put(Triple(snapshot.clone(), maxSuggestionCount, contextWords.hashCode()), suggestions)
		}
		return suggestions
	}

	/** Take a copy of the current gesture buffer for off-main scoring, then clear the live one. */
	override fun snapshotGestureAndClear(): Gesture {
		val snap = gesture.clone()
		gesture.clear()
		return snap
	}

	/** Clone the live gesture without clearing — used by mid-gesture preview scoring. */
	override fun cloneInternalGesture(): Gesture = gesture.clone()

	private fun unCachedGetSuggestions(maxSuggestionCount: Int, gesture: Gesture, contextWords: Set<String> = emptySet()): List<String> {
		val candidates = arrayListOf<String>()
		val candidateWeights = arrayListOf<Float>()
		val key = keys.firstOrNull() ?: return listOf()
		val radius = min(key.height, key.width)
		val activePruner = pruner ?: return listOf()

		// Fixed sampling count so per-word ideal gestures can be cached (see
		// cachedResampledIdeal). Adaptive sampling broke caching — each gesture's different
		// samplingPoints invalidated every cached ideal. The fixed REFERENCE_SAMPLES was already
		// the calibration anchor, so this is a no-op for accuracy but unlocks a ~10× speedup.
		val samplingPoints = REFERENCE_SAMPLES
		val shapeStd = SHAPE_STD

		val activePrefix = wordPrefix
		val prefixLen = activePrefix.length

		var remainingWords: ArrayList<String> = if (activePrefix.isEmpty()) {
			activePruner.pruneByExtremities(gesture, this.keys)
		} else {
			// With a locked prefix, the user's gesture starts at word[prefixLen], not word[0],
			// so the pruner's (first-key, last-key) tree is wrong. Filter on prefix instead and
			// let the full scoring loop run on what remains (typically a small subset).
			val filtered = ArrayList<String>()
			for (w in words) {
				if (w.length > prefixLen && w.startsWith(activePrefix)) filtered.add(w)
			}
			filtered
		}

		val userGesture = gesture.resample(samplingPoints)
		val normalizedUserGesture: Gesture = userGesture.normalizeByBoxSide()
		if (activePrefix.isEmpty()) {
			remainingWords = activePruner.pruneByLength(gesture, remainingWords, keysByCharacter, keys)
		}

		// Round C: cheap coarse pass over the post-prune candidate set. Reduce each candidate's
		// ideal path to corners only, score against the user's gesture corners with a simple
		// Euclidean per-point distance. Keep the top COARSE_TOP_N for the expensive fine pass.
		// Skipped when the candidate set is small enough that the fine pass can chew through it
		// directly — corner matching is imprecise and risks dropping the right answer.
		if (remainingWords.size > COARSE_PASS_MIN_SET_SIZE) {
			val userCorners = gesture.cornerPoints(CORNER_THRESHOLD_RADIANS)
			val userCornersResampled = if (userCorners.pointCount >= 2)
				userCorners.resample(CORNER_SAMPLE_COUNT)
			else null
			if (userCornersResampled != null) {
				// Use a parallel array of (word, coarseDistance) so we can sort then trim.
				val coarseScored = ArrayList<Pair<String, Float>>(remainingWords.size)
				for (word in remainingWords) {
					val cachedKey = "$word|$prefixLen"
					val cornerVariants = cachedCornerIdeal.getOrPut(cachedKey) {
						val rawIdeals = Gesture.generateIdealGestures(word, keysByCharacter, prefixLen)
						rawIdeals.mapNotNull { ideal ->
							val corners = ideal.cornerPoints(CORNER_THRESHOLD_RADIANS)
							if (corners.pointCount < 2) return@mapNotNull null
							corners.resample(CORNER_SAMPLE_COUNT)
						}
					}
					if (cornerVariants.isEmpty()) continue
					var best = Float.POSITIVE_INFINITY
					for (cv in cornerVariants) {
						val d = simpleEuclideanDistance(userCornersResampled, cv)
						if (d < best) best = d
					}
					coarseScored.add(word to best)
				}
				// SAFETY: only overwrite remainingWords when the coarse pass actually produced
				// useful filtering. If 0 words yielded corners (tiny gestures where most words'
				// ideal paths can't produce ≥2 direction-change points), keep the full
				// pre-coarse set so the fine pass still has something to score.
				if (coarseScored.isNotEmpty()) {
					coarseScored.sortBy { it.second }
					val kept = if (coarseScored.size > COARSE_TOP_N)
						coarseScored.subList(0, COARSE_TOP_N) else coarseScored
					remainingWords = ArrayList(kept.size)
					for ((w, _) in kept) remainingWords.add(w)
				}
			}
		}

		// Track the worst shapeDistance still in the kept candidate list so we can early-exit
		// further per-word scoring. Updated alongside candidateWeights. Pattern from
		// AnySoftKeyboard's `failFastThreshold`.
		val candidateShapeDistances = ArrayList<Float>()
		var failFastShapeDistance = Float.POSITIVE_INFINITY

		for (i in remainingWords.indices) {
			val word = remainingWords[i]
			val cachedKey = "$word|$prefixLen"
			val resampledPairs = cachedResampledIdeal.getOrPut(cachedKey) {
				val raw = Gesture.generateIdealGestures(word, keysByCharacter, prefixLen)
				raw.mapNotNull { ideal ->
					if (ideal.pointCount < 2) return@mapNotNull null
					val resampled = ideal.resample(samplingPoints)
					val normalized = resampled.normalizeByBoxSide()
					Pair(resampled, normalized)
				}
			}
			if (resampledPairs.isEmpty()) continue

			for ((wordGesture, normalizedGesture) in resampledPairs) {
				val shapeDistance = calcShapeDistance(normalizedGesture, normalizedUserGesture, failFastShapeDistance)
				if (shapeDistance.isInfinite()) continue  // bailed early — word can't beat current top-N
				val locationDistance = calcLocationDistance(wordGesture, userGesture)
				// Replaced Gaussian probability (pow+exp per word, ~50% of total per-word cost)
				// with a direct 1/(1+d) score that preserves ordering. The Gaussian's calibration
				// to a specific std-dev is irrelevant once we just need a monotonically decreasing
				// score for sort ordering. radius is divided into locationDistance so the score
				// is comparable across screen sizes.
				val shapeProbability = 1f / (1f + shapeDistance)
				val locationProbability = 1f / (1f + locationDistance / (radius + 1f))
				// Log-compressed frequency. Raw frequency × 255 had the most-common word at 255
				// and rare at ~5 — a 50× range that drowned out shape distance signal. Log
				// compression keeps common-word preference (still ~log(256) = 5.5 vs log(6) ~ 1.8
				// for the floor) but at a magnitude that combines reasonably with shape scores
				// in the 0.1–1.0 range.
				val rawFrequency = 255f * wordProvider.getFrequencyForWord(word)
				val frequency = kotlin.math.ln(1f + rawFrequency)
				// First-key Bayesian prior + end-key soft penalty. Per the SHARK² paper, the
				// first touched key is the single highest-confidence signal of user intent
				// (they deliberately tap down on the intended key). We model it as a Gaussian
				// likelihood: P(intended first key = X | touched at point P) ∝ exp(-||P - X||²/(2σ²)).
				// Multiplied into confidence_inv so distant-first-key candidates get suppressed
				// much more aggressively than the previous additive penalty allowed.
				val firstKey = wordKeyAt(word, prefixLen)
				val lastKey = wordKeyAt(word, word.length - 1)
				val firstKeyPrior = if (firstKey != null) {
					val sdx = gesture.getFirstX() - firstKey.centerX
					val sdy = gesture.getFirstY() - firstKey.centerY
					val distSq = sdx * sdx + sdy * sdy
					// σ = key radius. Touch at key center → prior=1; one-key-away → ~e^(-2) ≈ 0.135
					val sigma = (radius + 1f)
					kotlin.math.exp(-distSq / (2f * sigma * sigma))
				} else 1f
				var proximityPenalty = 0f
				if (lastKey != null) {
					val edx = gesture.getLastX() - lastKey.centerX
					val edy = gesture.getLastY() - lastKey.centerY
					proximityPenalty += END_PROXIMITY_PENALTY_FACTOR * (edx * edx + edy * edy)
				}
				// Length-match penalty: prefer candidates whose ideal-gesture length matches the
				// user's gesture length. wordGesture.getLength() is the cached ideal in pixels;
				// userGesture.getLength() is the user's. Without this, a straight 4-letter
				// "hero" beat a zigzag 5-letter "hello" because shape alone can't tell apart
				// similar-direction paths of different lengths.
				val lengthMismatch = kotlin.math.abs(wordGesture.getLength() - userGesture.getLength()) / (radius + 1f)
				val lengthPenalty = LENGTH_MATCH_PENALTY_FACTOR * lengthMismatch
				// N-gram context boost: if MindReader's bigram lookahead predicts this word, divide
				// confidence by CONTEXT_BOOST (smaller = better in this codebase's sort order).
				// Free win when user types in sentences: "happy birthday" makes "birthday" win
				// over shape-similar "boundary" even when shape-distance favours the latter.
				val contextMultiplier = if (contextWords.isNotEmpty() && word.lowercase() in contextWords) CONTEXT_BOOST else 1f
				val confidence = (1.0f / (shapeProbability * locationProbability * (frequency + FREQUENCY_FLOOR) * contextMultiplier * (firstKeyPrior + 0.01f))) + proximityPenalty + lengthPenalty

				var candidateDistanceSortedIndex = 0
				var duplicateIndex = Int.MAX_VALUE

				while (candidateDistanceSortedIndex < candidateWeights.size
					&& candidateWeights[candidateDistanceSortedIndex] <= confidence
				) {
					if (candidates[candidateDistanceSortedIndex].contentEquals(word))
						duplicateIndex = candidateDistanceSortedIndex
					candidateDistanceSortedIndex++
				}
				if (candidateDistanceSortedIndex < maxSuggestionCount && candidateDistanceSortedIndex <= duplicateIndex) {
					if (duplicateIndex < Int.MAX_VALUE) {
						candidateWeights.removeAt(duplicateIndex)
						candidates.removeAt(duplicateIndex)
						candidateShapeDistances.removeAt(duplicateIndex)
					}
					candidateWeights.add(candidateDistanceSortedIndex, confidence)
					candidates.add(candidateDistanceSortedIndex, word)
					candidateShapeDistances.add(candidateDistanceSortedIndex, shapeDistance)
					if (candidateWeights.size > maxSuggestionCount) {
						candidateWeights.removeAt(maxSuggestionCount)
						candidates.removeAt(maxSuggestionCount)
						candidateShapeDistances.removeAt(maxSuggestionCount)
					}
					// Tighten the early-exit threshold once we have a full set. The worst kept
					// shapeDistance is the bar to beat; anything scoring beyond it would be
					// dropped after sort anyway.
					if (candidateShapeDistances.size >= maxSuggestionCount) {
						failFastShapeDistance = candidateShapeDistances[maxSuggestionCount - 1]
					}
				}
			}
		}

		return candidates
	}

	override fun clear() { gesture.clear() }

	/**
	 * Cheap pairwise distance between two same-length gestures, no direction-penalty, no
	 * normalization. Used by Round-C coarse pass on corner-resampled paths.
	 */
	private fun simpleEuclideanDistance(g1: Gesture, g2: Gesture): Float {
		var total = 0f
		val n = min(g1.pointCount, g2.pointCount)
		for (i in 0 until n) {
			total += Gesture.distance(g1.getX(i), g1.getY(i), g2.getX(i), g2.getY(i))
		}
		return total
	}

	/** Read-only view of the currently configured key bounds. Used by the touch-offset adapter. */
	override val layoutKeys: List<SwipeKey> get() = keys

	/**
	 * Return the [SwipeKey] for the character at [index] in [word], or null if either the index
	 * is out of range or the character isn't on the current layout. Falls back to the NFD base
	 * character (e.g. ñ → n) so accented letters still resolve to their physical key.
	 */
	private fun wordKeyAt(word: String, index: Int): SwipeKey? {
		if (index < 0 || index >= word.length) return null
		val lc = Character.toLowerCase(word[index])
		val direct = keysByCharacter[lc.code]
		if (direct != null) return direct
		val base = Normalizer.normalize(lc.toString(), Normalizer.Form.NFD)[0]
		return keysByCharacter[base.code]
	}

	private fun calcLocationDistance(gesture1: Gesture, gesture2: Gesture): Float {
		var totalDistance = 0.0f
		val n = min(gesture1.pointCount, gesture2.pointCount)
		if (n == 0) return 0f
		for (i in 0 until n) {
			totalDistance += abs(gesture1.getX(i) - gesture2.getX(i)) + abs(gesture1.getY(i) - gesture2.getY(i))
		}
		return totalDistance / n / 2
	}

	private fun calcGaussianProbability(value: Float, mean: Float, standardDeviation: Float): Float {
		val factor = 1.0 / (standardDeviation * sqrt(2 * PI))
		val exponent = ((value - mean) / standardDeviation).toDouble().pow(2.0)
		return (factor * exp(-1.0 / 2 * exponent)).toFloat()
	}

	private fun calcShapeDistance(gesture1: Gesture, gesture2: Gesture, failFastAbove: Float = Float.POSITIVE_INFINITY): Float {
		var totalDistance = 0.0f
		val n = min(gesture1.pointCount, gesture2.pointCount)
		for (i in 0 until n) {
			// Early termination (AnySoftKeyboard pattern): once cumulative distance has exceeded
			// the worst candidate currently in the top-N, scoring this word any further is wasted
			// work — the word cannot displace the worst candidate. Bail with infinity; caller
			// treats it as "skip this word."
			if (totalDistance > failFastAbove) return Float.POSITIVE_INFINITY
			val d = Gesture.distance(gesture1.getX(i), gesture1.getY(i), gesture2.getX(i), gesture2.getY(i))
			// Direction penalty (AnySoftKeyboard pattern). Scale each segment's contribution by
			// how much the two paths' LOCAL directions disagree. cosθ=+1 → 1×, cosθ=0 → 2×,
			// cosθ=-1 → 3×. Catches paths that visit the right keys in the wrong order.
			// Safe on UI now that the classifier runs on a worker thread.
			val mult = if (i > 0) {
				val u1x = gesture1.getX(i) - gesture1.getX(i - 1)
				val u1y = gesture1.getY(i) - gesture1.getY(i - 1)
				val u2x = gesture2.getX(i) - gesture2.getX(i - 1)
				val u2y = gesture2.getY(i) - gesture2.getY(i - 1)
				val m1sq = u1x * u1x + u1y * u1y
				val m2sq = u2x * u2x + u2y * u2y
				if (m1sq > 0f && m2sq > 0f) {
					val cos = (u1x * u2x + u1y * u2y) / sqrt(m1sq * m2sq)
					1f + DIRECTION_PENALTY_FACTOR * (1f - cos)
				} else 1f
			} else 1f
			totalDistance += d * mult
		}
		return totalDistance
	}

	class Pruner(
		private val lengthThreshold: Double,
		words: List<String>,
		keysByCharacter: SparseArrayCompat<SwipeKey>,
	) {
		private val wordTree = Collections.synchronizedMap(HashMap<Pair<Int, Int>, ArrayList<String>>())

		fun pruneByExtremities(userGesture: Gesture, keys: Iterable<SwipeKey>): ArrayList<String> {
			val remainingWords = ArrayList<String>()
			val startKeys = findNClosestKeys(userGesture.getFirstX(), userGesture.getFirstY(), START_KEY_CANDIDATES, keys)
			val endKeys = findNClosestKeys(userGesture.getLastX(), userGesture.getLastY(), END_KEY_CANDIDATES, keys)
			for (startKey in startKeys) {
				for (endKey in endKeys) {
					val wordsForKeys = synchronized(wordTree) { wordTree[Pair(startKey, endKey)] }
					if (wordsForKeys != null) remainingWords.addAll(wordsForKeys)
				}
			}
			return remainingWords
		}

		fun pruneByLength(
			userGesture: Gesture,
			words: ArrayList<String>,
			keysByCharacter: SparseArrayCompat<SwipeKey>,
			keys: List<SwipeKey>,
		): ArrayList<String> {
			val remainingWords = ArrayList<String>()
			val key = keys.firstOrNull() ?: return arrayListOf()
			val radius = min(key.height, key.width)
			val userLength = userGesture.getLength()
			for (word in words) {
				val idealGestures = Gesture.generateIdealGestures(word, keysByCharacter)
				for (idealGesture in idealGestures) {
					val wordIdealLength = getCachedIdealLength(word, idealGesture)
					if (abs(userLength - wordIdealLength) < lengthThreshold * radius) {
						remainingWords.add(word)
					}
				}
			}
			return remainingWords
		}

		private val cachedIdealLength = ConcurrentHashMap<String, Float>()
		private fun getCachedIdealLength(word: String, idealGesture: Gesture): Float =
			cachedIdealLength.getOrPut(word) { idealGesture.getLength() }

		companion object {
			private fun getFirstKeyLastKey(word: String, keysByCharacter: SparseArrayCompat<SwipeKey>): Pair<Int, Int>? {
				if (word.isEmpty()) return null
				val firstLetter = word[0]
				val lastLetter = word[word.length - 1]
				val firstBaseChar = Normalizer.normalize(firstLetter.toString(), Normalizer.Form.NFD)[0]
				val lastBaseChar = Normalizer.normalize(lastLetter.toString(), Normalizer.Form.NFD)[0]
				if (keysByCharacter.indexOfKey(firstBaseChar.code) < 0 || keysByCharacter.indexOfKey(lastBaseChar.code) < 0) {
					return null
				}
				val firstKey = keysByCharacter[firstBaseChar.code] ?: return null
				val lastKey = keysByCharacter[lastBaseChar.code] ?: return null
				return firstKey.code to lastKey.code
			}

			private fun findNClosestKeys(x: Float, y: Float, n: Int, keys: Iterable<SwipeKey>): Iterable<Int> {
				val keyDistances = HashMap<SwipeKey, Float>()
				for (key in keys) {
					keyDistances[key] = Gesture.distance(key.centerX, key.centerY, x, y)
				}
				return keyDistances.entries.sortedWith { c1, c2 -> c1.value.compareTo(c2.value) }
					.take(n).map { it.key.code }
			}
		}

		init {
			synchronized(wordTree) {
				for (word in words) {
					getFirstKeyLastKey(word, keysByCharacter)?.let { keyPair ->
						wordTree.getOrPut(keyPair) { arrayListOf() }.add(word)
					}
				}
			}
		}
	}

	class Gesture(
		private val xs: FloatArray = FloatArray(MAX_SIZE),
		private val ys: FloatArray = FloatArray(MAX_SIZE),
		// Parallel wall-clock timestamps (MotionEvent.eventTime ms). Required by the neural
		// decoder for real velocity / acceleration features. Statistical scoring ignores it.
		private val ts: LongArray = LongArray(MAX_SIZE),
		private var size: Int = 0,
	) {
		companion object {
			private const val MAX_SIZE = 500

			fun generateIdealGestures(
				word: String,
				keysByCharacter: SparseArrayCompat<SwipeKey>,
				startIndex: Int = 0,
			): List<Gesture> {
				val idealGesture = Gesture()
				val idealGestureWithLoops = Gesture()
				var previousLetter = ' '
				var hasLoops = false

				var charIdx = -1
				for (c in word) {
					charIdx++
					if (charIdx < startIndex) {
						previousLetter = Character.toLowerCase(c)
						continue
					}
					val lc = Character.toLowerCase(c)
					var key = keysByCharacter[lc.code]
					if (key == null) {
						val baseCharacter: Char = Normalizer.normalize(lc.toString(), Normalizer.Form.NFD)[0]
						key = keysByCharacter[baseCharacter.code]
						if (key == null) continue
					}
					val cx = key.centerX
					val cy = key.centerY
					val hw = key.width / 4.0f
					val hh = key.height / 4.0f

					if (previousLetter == lc) {
						idealGestureWithLoops.addPoint(cx + hw, cy + hh)
						idealGestureWithLoops.addPoint(cx + hw, cy - hh)
						idealGestureWithLoops.addPoint(cx - hw, cy - hh)
						idealGestureWithLoops.addPoint(cx - hw, cy + hh)
						hasLoops = true
						idealGesture.addPoint(cx, cy)
					} else {
						idealGesture.addPoint(cx, cy)
						idealGestureWithLoops.addPoint(cx, cy)
					}
					previousLetter = lc
				}
				return if (hasLoops) listOf(idealGesture, idealGestureWithLoops) else listOf(idealGesture)
			}

			fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
				sqrt((x1 - x2).pow(2) + (y1 - y2).pow(2))
		}

		val isEmpty: Boolean get() = size == 0

		val pointCount: Int get() = size

		/**
		 * Reduce this gesture to direction-change points. A point is a corner if the angle
		 * between the incoming and outgoing segments exceeds [thresholdRadians]. First and last
		 * points are always included. Used by the Round-C coarse pass — corners correlate with
		 * intended letters because users naturally decelerate at letter positions.
		 */
		fun cornerPoints(thresholdRadians: Float): Gesture {
			val out = Gesture()
			if (size == 0) return out
			out.addPoint(xs[0], ys[0])
			if (size <= 2) {
				if (size == 2) out.addPoint(xs[1], ys[1])
				return out
			}
			for (i in 1 until size - 1) {
				val dx1 = xs[i] - xs[i - 1]
				val dy1 = ys[i] - ys[i - 1]
				val dx2 = xs[i + 1] - xs[i]
				val dy2 = ys[i + 1] - ys[i]
				val m1 = sqrt(dx1 * dx1 + dy1 * dy1)
				val m2 = sqrt(dx2 * dx2 + dy2 * dy2)
				if (m1 == 0f || m2 == 0f) continue
				val cos = ((dx1 * dx2 + dy1 * dy2) / (m1 * m2)).coerceIn(-1f, 1f)
				// Angle between segments. 0 = straight, π = U-turn. Threshold typical ~0.5
				// (≈30° change from straight) catches real letter transitions while ignoring
				// touch jitter.
				val angle = kotlin.math.acos(cos)
				if (angle > thresholdRadians) out.addPoint(xs[i], ys[i])
			}
			out.addPoint(xs[size - 1], ys[size - 1])
			return out
		}

		fun addPoint(x: Float, y: Float, t: Long = 0L) {
			if (size >= MAX_SIZE) return
			xs[size] = x
			ys[size] = y
			ts[size] = t
			size += 1
		}

		fun getT(i: Int): Long = ts.getOrElse(i) { 0L }

		fun resample(numPoints: Int): Gesture {
			val interpointDistance = (getLength() / numPoints)
			val resampledGesture = Gesture()
			if (size == 0) return resampledGesture
			resampledGesture.addPoint(xs[0], ys[0])
			var lastX = xs[0]
			var lastY = ys[0]
			var cumulativeError = 0.0f

			if (this.size == 1) {
				for (i in 0 until numPoints) resampledGesture.addPoint(xs[0], ys[0])
			}

			for (i in 0 until size - 1) {
				var dx = xs[i + 1] - xs[i]
				var dy = ys[i + 1] - ys[i]
				val norm = sqrt(dx.pow(2.0f) + dy.pow(2.0f))
				if (norm == 0f) continue
				dx /= norm
				dy /= norm

				var numNewPoints = norm / interpointDistance
				cumulativeError += numNewPoints - numNewPoints.toInt()
				if (cumulativeError > 1) {
					numNewPoints = (numNewPoints.toInt() + cumulativeError.toInt()).toFloat()
					cumulativeError %= 1
				}
				for (j in 0 until numNewPoints.toInt()) {
					val newX = lastX + dx * interpointDistance
					val newY = lastY + dy * interpointDistance
					lastX = newX
					lastY = newY
					resampledGesture.addPoint(newX, newY)
				}
			}
			return resampledGesture
		}

		fun normalizeByBoxSide(): Gesture {
			val normalizedGesture = Gesture()
			var maxX = -1.0f
			var maxY = -1.0f
			var minX = 10000.0f
			var minY = 10000.0f
			for (i in 0 until size) {
				maxX = max(xs[i], maxX)
				maxY = max(ys[i], maxY)
				minX = min(xs[i], minX)
				minY = min(ys[i], minY)
			}
			val width = maxX - minX
			val height = maxY - minY
			val longestSide = max(max(width, height), 0.00001f)
			val centroidX = (width / 2 + minX) / longestSide
			val centroidY = (height / 2 + minY) / longestSide
			for (i in 0 until size) {
				normalizedGesture.addPoint(xs[i] / longestSide - centroidX, ys[i] / longestSide - centroidY)
			}
			return normalizedGesture
		}

		fun getFirstX(): Float = xs.getOrElse(0) { 0f }
		fun getFirstY(): Float = ys.getOrElse(0) { 0f }
		fun getLastX(): Float = xs.getOrElse(size - 1) { 0f }
		fun getLastY(): Float = ys.getOrElse(size - 1) { 0f }

		fun getLength(): Float {
			var length = 0f
			for (i in 1 until size) {
				length += distance(xs[i - 1], ys[i - 1], xs[i], ys[i])
			}
			return length
		}

		fun clear() { this.size = 0 }

		fun getX(i: Int): Float = xs.getOrElse(i) { 0f }
		fun getY(i: Int): Float = ys.getOrElse(i) { 0f }

		fun clone(): Gesture = Gesture(xs.clone(), ys.clone(), ts.clone(), size)

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (javaClass != other?.javaClass) return false
			other as Gesture
			if (this.size != other.size) return false
			for (i in 0 until size) {
				if (xs[i] != other.xs[i] || ys[i] != other.ys[i]) return false
			}
			return true
		}

		override fun hashCode(): Int {
			// equals only compares [0, size) so hashCode must do the same — using contentHashCode
			// over the full backing array hashed stale tail data, violating the contract.
			var result = size
			for (i in 0 until size) {
				result = 31 * result + xs[i].toRawBits()
				result = 31 * result + ys[i].toRawBits()
			}
			return result
		}
	}
}
