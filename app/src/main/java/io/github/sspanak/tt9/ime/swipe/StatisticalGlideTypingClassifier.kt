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

	val ready: Boolean get() = layoutReady && wordsReady && pruner != null

	companion object {
		private const val PRUNING_LENGTH_THRESHOLD = 8.42
		// Reference calibration: SHAPE_STD/LOCATION_STD were tuned against this point count.
		// Actual sampling at gesture time scales with gesture length; SHAPE_STD scales with it
		// so the Gaussian likelihood stays comparable across short and long gestures.
		private const val REFERENCE_SAMPLES: Int = 200
		private const val SAMPLES_PER_KEY: Float = 25f
		private const val MIN_SAMPLES: Int = 80
		private const val MAX_SAMPLES: Int = 240
		private const val SHAPE_STD = 22.08f
		private const val LOCATION_STD = 0.5109f
		private const val SUGGESTION_CACHE_SIZE = 5
		// Start- and end-key search widened (was 2 each). The hard cutoff used to be brittle —
		// a finger landing one key off-target killed the right candidate. Now both sides allow
		// a wider candidate pool and a SOFT quadratic proximity penalty (below) sorts them by
		// closeness to the actual key center. Pattern from AnySoftKeyboard's GestureTypingDetector.
		private const val START_KEY_CANDIDATES: Int = 5
		private const val END_KEY_CANDIDATES: Int = 5
		// Proximity penalty: penalises candidates whose word-start/end falls further from the
		// user's gesture start/end. End factor is HALVED — users systematically overshoot at
		// the end of a swipe. Tuned per AnySoftKeyboard (PROXIMITY_PENALTY_FACTOR=0.000667,
		// END_PROXIMITY_PENALTY_FACTOR=0.000333).
		private const val START_PROXIMITY_PENALTY_FACTOR: Float = 0.000667f
		private const val END_PROXIMITY_PENALTY_FACTOR: Float = 0.000333f
		// Direction penalty: each gesture segment whose direction disagrees with the ideal-path
		// direction at the same position contributes more to total shape distance.
		// cosθ=+1 (same direction) → 1×, cosθ=0 (perpendicular) → 2×, cosθ=-1 (opposite) → 3×.
		// Catches the failure mode where shape distance accepts a wildly wrong-direction path.
		private const val DIRECTION_PENALTY_FACTOR: Float = 1.0f
	}

	override fun addGesturePoint(position: GlideTypingGesture.Detector.Position) {
		if (!gesture.isEmpty) {
			val dx = gesture.getLastX() - position.x
			val dy = gesture.getLastY() - position.y
			if (dx * dx + dy * dy > distanceThresholdSquared) {
				gesture.addPoint(position.x, position.y)
			}
		} else {
			gesture.addPoint(position.x, position.y)
		}
	}

	override fun setLayout(keys: List<SwipeKey>) {
		if (this.keys == keys && layoutReady) return

		keysByCharacter.clear()
		this.keys.clear()
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
		this.words = provider.getListOfWords()
		this.wordsReady = true
		lruSuggestionCache.evictAll()
		maybeInitPruner()
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

	private val lruSuggestionCache = LruCache<Pair<Gesture, Int>, List<String>>(SUGGESTION_CACHE_SIZE)
	override fun getSuggestions(maxSuggestionCount: Int, gestureCompleted: Boolean): List<String> {
		return analyzeGesture(this.gesture, maxSuggestionCount)
	}

	/**
	 * Worker-thread-safe scoring entry point. Takes a gesture snapshot (clone the live one before
	 * passing it in) so the caller can clear or mutate the classifier's own state in parallel.
	 * All other state read here (keys, pruner, words, wordPrefix) is set up at layout / load
	 * time and stable during a gesture's lifetime.
	 */
	fun analyzeGesture(snapshot: Gesture, maxSuggestionCount: Int): List<String> {
		if (!ready) return emptyList()
		if (snapshot.pointCount < 2) return emptyList()
		synchronized(lruSuggestionCache) {
			lruSuggestionCache.get(Pair(snapshot, maxSuggestionCount))?.let { return it }
		}
		val suggestions = unCachedGetSuggestions(maxSuggestionCount, snapshot)
		synchronized(lruSuggestionCache) {
			lruSuggestionCache.put(Pair(snapshot.clone(), maxSuggestionCount), suggestions)
		}
		return suggestions
	}

	/** Take a copy of the current gesture buffer for off-main scoring, then clear the live one. */
	fun snapshotGestureAndClear(): Gesture {
		val snap = gesture.clone()
		gesture.clear()
		return snap
	}

	/** Clone the live gesture without clearing — used by mid-gesture preview scoring. */
	fun cloneInternalGesture(): Gesture = gesture.clone()

	private fun unCachedGetSuggestions(maxSuggestionCount: Int, gesture: Gesture): List<String> {
		val candidates = arrayListOf<String>()
		val candidateWeights = arrayListOf<Float>()
		val key = keys.firstOrNull() ?: return listOf()
		val radius = min(key.height, key.width)
		val activePruner = pruner ?: return listOf()

		// Adaptive sampling: scale point count with gesture length so short gestures aren't
		// over-sampled and long ones aren't under-sampled. SHAPE_STD scales with point count
		// (it's a sum of per-point distances) to keep the Gaussian calibration consistent.
		val gestureLen = gesture.getLength()
		val samplingPoints = ((gestureLen / key.width) * SAMPLES_PER_KEY)
			.toInt()
			.coerceIn(MIN_SAMPLES, MAX_SAMPLES)
		val shapeStd = SHAPE_STD * (samplingPoints.toFloat() / REFERENCE_SAMPLES)

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

		// Track the worst shapeDistance still in the kept candidate list so we can early-exit
		// further per-word scoring. Updated alongside candidateWeights. Pattern from
		// AnySoftKeyboard's `failFastThreshold`.
		val candidateShapeDistances = ArrayList<Float>()
		var failFastShapeDistance = Float.POSITIVE_INFINITY

		for (i in remainingWords.indices) {
			val word = remainingWords[i]
			val idealGestures = Gesture.generateIdealGestures(word, keysByCharacter, prefixLen)

			for (idealGesture in idealGestures) {
				if (idealGesture.pointCount < 2) continue
				val wordGesture = idealGesture.resample(samplingPoints)
				val normalizedGesture: Gesture = wordGesture.normalizeByBoxSide()
				val shapeDistance = calcShapeDistance(normalizedGesture, normalizedUserGesture, failFastShapeDistance)
				if (shapeDistance.isInfinite()) continue  // bailed early — word can't beat current top-N
				val locationDistance = calcLocationDistance(wordGesture, userGesture)
				val shapeProbability = calcGaussianProbability(shapeDistance, 0.0f, shapeStd)
				val locationProbability = calcGaussianProbability(locationDistance, 0.0f, LOCATION_STD * radius)
				val frequency = 255f * wordProvider.getFrequencyForWord(word)
				// Soft proximity penalty: distance² from gesture start/end to the word's
				// actual first/last key, halved at the end (users overshoot on swipe-out).
				// Added directly to confidence (smaller = better), so further-off keys score worse
				// without being eliminated by hard pruning.
				val firstKey = wordKeyAt(word, prefixLen)
				val lastKey = wordKeyAt(word, word.length - 1)
				var proximityPenalty = 0f
				if (firstKey != null) {
					val sdx = gesture.getFirstX() - firstKey.centerX
					val sdy = gesture.getFirstY() - firstKey.centerY
					proximityPenalty += START_PROXIMITY_PENALTY_FACTOR * (sdx * sdx + sdy * sdy)
				}
				if (lastKey != null) {
					val edx = gesture.getLastX() - lastKey.centerX
					val edy = gesture.getLastY() - lastKey.centerY
					proximityPenalty += END_PROXIMITY_PENALTY_FACTOR * (edx * edx + edy * edy)
				}
				val confidence = 1.0f / (shapeProbability * locationProbability * (frequency + 1f)) + proximityPenalty

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

	/** Read-only view of the currently configured key bounds. Used by the touch-offset adapter. */
	val layoutKeys: List<SwipeKey> get() = keys

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

		fun addPoint(x: Float, y: Float) {
			if (size >= MAX_SIZE) return
			xs[size] = x
			ys[size] = y
			size += 1
		}

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

		fun clone(): Gesture = Gesture(xs.clone(), ys.clone(), size)

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
