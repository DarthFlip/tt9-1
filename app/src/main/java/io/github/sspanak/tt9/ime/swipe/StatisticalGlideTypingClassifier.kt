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

	val ready: Boolean get() = layoutReady && wordsReady && pruner != null

	companion object {
		private const val PRUNING_LENGTH_THRESHOLD = 8.42
		private const val SAMPLING_POINTS: Int = 200
		private const val SHAPE_STD = 22.08f
		private const val LOCATION_STD = 0.5109f
		private const val SUGGESTION_CACHE_SIZE = 5
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
		maybeInitPruner()
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
		if (!ready) return emptyList()
		return when (val cached = lruSuggestionCache.get(Pair(this.gesture, maxSuggestionCount))) {
			null -> {
				val suggestions = unCachedGetSuggestions(maxSuggestionCount)
				lruSuggestionCache.put(Pair(this.gesture.clone(), maxSuggestionCount), suggestions)
				suggestions
			}
			else -> cached
		}
	}

	private fun unCachedGetSuggestions(maxSuggestionCount: Int): List<String> {
		val candidates = arrayListOf<String>()
		val candidateWeights = arrayListOf<Float>()
		val key = keys.firstOrNull() ?: return listOf()
		val radius = min(key.height, key.width)
		val activePruner = pruner ?: return listOf()
		var remainingWords = activePruner.pruneByExtremities(gesture, this.keys)
		val userGesture = gesture.resample(SAMPLING_POINTS)
		val normalizedUserGesture: Gesture = userGesture.normalizeByBoxSide()
		remainingWords = activePruner.pruneByLength(gesture, remainingWords, keysByCharacter, keys)

		for (i in remainingWords.indices) {
			val word = remainingWords[i]
			val idealGestures = Gesture.generateIdealGestures(word, keysByCharacter)

			for (idealGesture in idealGestures) {
				val wordGesture = idealGesture.resample(SAMPLING_POINTS)
				val normalizedGesture: Gesture = wordGesture.normalizeByBoxSide()
				val shapeDistance = calcShapeDistance(normalizedGesture, normalizedUserGesture)
				val locationDistance = calcLocationDistance(wordGesture, userGesture)
				val shapeProbability = calcGaussianProbability(shapeDistance, 0.0f, SHAPE_STD)
				val locationProbability = calcGaussianProbability(locationDistance, 0.0f, LOCATION_STD * radius)
				val frequency = 255f * wordProvider.getFrequencyForWord(word)
				val confidence = 1.0f / (shapeProbability * locationProbability * (frequency + 1f))

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
					}
					candidateWeights.add(candidateDistanceSortedIndex, confidence)
					candidates.add(candidateDistanceSortedIndex, word)
					if (candidateWeights.size > maxSuggestionCount) {
						candidateWeights.removeAt(maxSuggestionCount)
						candidates.removeAt(maxSuggestionCount)
					}
				}
			}
		}

		return candidates
	}

	override fun clear() { gesture.clear() }

	private fun calcLocationDistance(gesture1: Gesture, gesture2: Gesture): Float {
		var totalDistance = 0.0f
		for (i in 0 until SAMPLING_POINTS) {
			totalDistance += abs(gesture1.getX(i) - gesture2.getX(i)) + abs(gesture1.getY(i) - gesture2.getY(i))
		}
		return totalDistance / SAMPLING_POINTS / 2
	}

	private fun calcGaussianProbability(value: Float, mean: Float, standardDeviation: Float): Float {
		val factor = 1.0 / (standardDeviation * sqrt(2 * PI))
		val exponent = ((value - mean) / standardDeviation).toDouble().pow(2.0)
		return (factor * exp(-1.0 / 2 * exponent)).toFloat()
	}

	private fun calcShapeDistance(gesture1: Gesture, gesture2: Gesture): Float {
		var totalDistance = 0.0f
		for (i in 0 until SAMPLING_POINTS) {
			totalDistance += Gesture.distance(gesture1.getX(i), gesture1.getY(i), gesture2.getX(i), gesture2.getY(i))
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
			val startKeys = findNClosestKeys(userGesture.getFirstX(), userGesture.getFirstY(), 2, keys)
			val endKeys = findNClosestKeys(userGesture.getLastX(), userGesture.getLastY(), 2, keys)
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

			fun generateIdealGestures(word: String, keysByCharacter: SparseArrayCompat<SwipeKey>): List<Gesture> {
				val idealGesture = Gesture()
				val idealGestureWithLoops = Gesture()
				var previousLetter = ' '
				var hasLoops = false

				for (c in word) {
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
				for (i in 0 until SAMPLING_POINTS) resampledGesture.addPoint(xs[0], ys[0])
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
			var result = xs.contentHashCode()
			result = 31 * result + ys.contentHashCode()
			result = 31 * result + size
			return result
		}
	}
}
