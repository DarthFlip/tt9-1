/*
 * Copyright (C) 2026 tt9 contributors. GPL-3.0.
 *
 * Neural English-only swipe decoder backed by CleverKeys' published ONNX encoder +
 * decoder weights (github.com/tribixbite/CleverKeys, GPL-3.0). ExecuTorch can't
 * ship to the F1's 32-bit ARM hardware; ONNX Runtime does, which is why we pivoted
 * here from the earlier FUTO/ExecuTorch attempt.
 *
 * Pipeline per gesture:
 *   raw points → CleverKeysFeatures → [1, 250, 6] features + [1, 250] nearest_keys + [1] length
 *   → encoder ORT session → memory [1, seq_len, 256]
 *   → BeamSearch.decode(runDecoder) — autoregressive, up to 12 steps × 4 beams
 *   → top-N candidate words via lexicon trie + length norm + freq + context bonus
 *
 * Hebrew + other non-English languages NEVER reach this class — they go through
 * StatisticalGlideTypingClassifier via per-language dispatch in
 * SwipeableKeyboardContainer.bindLanguage.
 */
package io.github.sspanak.tt9.ime.swipe

import android.content.Context
import android.util.Log
import io.github.sspanak.tt9.util.Logger
import java.nio.FloatBuffer
import java.nio.IntBuffer

class NeuralGlideDecoder(private val context: Context) : GlideTypingClassifier {

	private val gesture = StatisticalGlideTypingClassifier.Gesture()
	private var wordProvider: WordProvider = EmptyWordProvider
	@Volatile private var keys: List<SwipeKey> = emptyList()
	@Volatile private var wordPrefix: String = ""
	private val trie = LexiconTrie()
	private val beamSearch = BeamSearch()

	// ORT environment + session handles loaded lazily on first inference. Both ONNX models
	// load directly from APK assets via byte arrays (ONNX Runtime's mmap path has a known
	// SIGBUS issue on armv7).
	@Volatile private var ortEnv: Any? = null
	@Volatile private var encoderSession: Any? = null
	@Volatile private var decoderSession: Any? = null
	private val loadLock = Any()

	override val ready: Boolean
		get() = keys.isNotEmpty() && trie.wordCount > 0

	override val layoutKeys: List<SwipeKey> get() = keys

	override fun addGesturePoint(position: GlideTypingGesture.Detector.Position) {
		gesture.addPoint(position.x, position.y)
	}

	override fun setLayout(keys: List<SwipeKey>) {
		this.keys = keys
	}

	override fun setWordProvider(provider: WordProvider) {
		wordProvider = provider
		trie.build(provider.getListOfWords()) { provider.getFrequencyForWord(it) }
		Logger.d(TAG, "trie built: ${trie.wordCount} words")
	}

	override fun setWordPrefix(prefix: String) {
		wordPrefix = prefix.lowercase()
	}

	override fun initGestureFromPointerData(pointerData: GlideTypingGesture.Detector.PointerData) {
		for (p in pointerData.positions) gesture.addPoint(p.x, p.y)
	}

	override fun getSuggestions(maxSuggestionCount: Int, gestureCompleted: Boolean): List<String> {
		return analyzeGesture(gesture.clone(), maxSuggestionCount, emptySet())
	}

	override fun clear() {
		gesture.clear()
	}

	override fun snapshotGestureAndClear(): StatisticalGlideTypingClassifier.Gesture {
		val snap = gesture.clone()
		gesture.clear()
		return snap
	}

	override fun cloneInternalGesture(): StatisticalGlideTypingClassifier.Gesture = gesture.clone()

	override fun analyzeGesture(
		snapshot: StatisticalGlideTypingClassifier.Gesture,
		maxSuggestionCount: Int,
		contextWords: Set<String>,
	): List<String> {
		if (!ready || snapshot.isEmpty) return emptyList()
		if (!ensureSessionsLoaded()) return emptyList()

		val t0 = System.currentTimeMillis()

		val features = CleverKeysFeatures.build(snapshot, keys)
		if (features.actualLength[0] == 0) return emptyList()

		val memory = runEncoder(features) ?: return emptyList()
		val tEnc = System.currentTimeMillis() - t0

		val seqLen = memory.size / ENCODER_HIDDEN
		val srcLen = IntArray(1) { features.actualLength[0] }
		val results = beamSearch.decode(
			runDecoder = { tokens, numBeams -> runDecoder(memory, seqLen, srcLen, tokens, numBeams) },
			trie = trie,
			lockedPrefix = wordPrefix,
			contextWords = contextWords,
			maxResults = maxSuggestionCount,
		)
		Logger.d(TAG, "enc=${tEnc}ms total=${System.currentTimeMillis() - t0}ms results=${results.size}")
		return results
	}

	// ───────────────────────────── ORT session lifecycle ──────────────────────────────

	private fun ensureSessionsLoaded(): Boolean {
		if (encoderSession != null && decoderSession != null) return true
		synchronized(loadLock) {
			if (encoderSession != null && decoderSession != null) return true
			return try {
				val ortEnvClass = Class.forName("ai.onnxruntime.OrtEnvironment")
				val env = ortEnvClass.getMethod("getEnvironment").invoke(null)
				ortEnv = env

				val encoderBytes = readAsset("models/$ENCODER_FILENAME")
				val decoderBytes = readAsset("models/$DECODER_FILENAME")

				val createSession = ortEnvClass.getMethod("createSession", ByteArray::class.java)
				encoderSession = createSession.invoke(env, encoderBytes)
				decoderSession = createSession.invoke(env, decoderBytes)
				Logger.d(TAG, "ONNX sessions loaded (encoder ${encoderBytes.size}B, decoder ${decoderBytes.size}B)")
				true
			} catch (e: Throwable) {
				Log.e(TAG, "failed to load ONNX sessions — neural decoder will return empty results", e)
				false
			}
		}
	}

	private fun readAsset(path: String): ByteArray =
		context.assets.open(path).use { it.readBytes() }

	// ───────────────────────────── Inference helpers ──────────────────────────────────

	private fun runEncoder(input: CleverKeysFeatures.Inputs): FloatArray? {
		return try {
			val env = ortEnv ?: return null
			val session = encoderSession ?: return null

			val featuresTensor = createFloatTensor(env, input.features, longArrayOf(1, CleverKeysFeatures.MAX_SEQ_LEN.toLong(), CleverKeysFeatures.FEATURE_DIM.toLong()))
			val nearestTensor = createIntTensor(env, input.nearestKeys, longArrayOf(1, CleverKeysFeatures.MAX_SEQ_LEN.toLong()))
			val lengthTensor = createIntTensor(env, input.actualLength, longArrayOf(1))

			try {
				val inputs = mapOf(
					"trajectory_features" to featuresTensor,
					"nearest_keys" to nearestTensor,
					"actual_length" to lengthTensor,
				)
				val result = runSession(session, inputs) ?: return null
				try {
					extractFloatTensor(result, "memory")
				} finally {
					closeAutoCloseable(result)
				}
			} finally {
				closeAutoCloseable(featuresTensor)
				closeAutoCloseable(nearestTensor)
				closeAutoCloseable(lengthTensor)
			}
		} catch (e: Throwable) {
			Log.e(TAG, "encoder inference failed", e)
			null
		}
	}

	private fun runDecoder(
		memory: FloatArray,
		seqLen: Int,
		actualSrcLength: IntArray,
		targetTokens: IntArray,
		numBeams: Int,
	): FloatArray? {
		return try {
			val env = ortEnv ?: return null
			val session = decoderSession ?: return null

			val memoryTensor = createFloatTensor(env, memory, longArrayOf(1, seqLen.toLong(), ENCODER_HIDDEN.toLong()))
			val tokensTensor = createIntTensor(env, targetTokens, longArrayOf(numBeams.toLong(), BeamSearch.DECODER_SEQ_LEN.toLong()))
			val srcLenTensor = createIntTensor(env, actualSrcLength, longArrayOf(1))

			try {
				val inputs = mapOf(
					"memory" to memoryTensor,
					"target_tokens" to tokensTensor,
					"actual_src_length" to srcLenTensor,
				)
				val result = runSession(session, inputs) ?: return null
				try {
					extractFloatTensor(result, "logits")
				} finally {
					closeAutoCloseable(result)
				}
			} finally {
				closeAutoCloseable(memoryTensor)
				closeAutoCloseable(tokensTensor)
				closeAutoCloseable(srcLenTensor)
			}
		} catch (e: Throwable) {
			Log.e(TAG, "decoder inference failed", e)
			null
		}
	}

	// ───────────────────────────── ONNX Runtime reflection ────────────────────────────

	private fun createFloatTensor(env: Any, data: FloatArray, shape: LongArray): Any {
		val onnxTensorClass = Class.forName("ai.onnxruntime.OnnxTensor")
		val ortEnvClass = Class.forName("ai.onnxruntime.OrtEnvironment")
		val method = onnxTensorClass.getMethod(
			"createTensor",
			ortEnvClass,
			FloatBuffer::class.java,
			LongArray::class.java,
		)
		return method.invoke(null, env, FloatBuffer.wrap(data), shape)
	}

	private fun createIntTensor(env: Any, data: IntArray, shape: LongArray): Any {
		val onnxTensorClass = Class.forName("ai.onnxruntime.OnnxTensor")
		val ortEnvClass = Class.forName("ai.onnxruntime.OrtEnvironment")
		val method = onnxTensorClass.getMethod(
			"createTensor",
			ortEnvClass,
			IntBuffer::class.java,
			LongArray::class.java,
		)
		return method.invoke(null, env, IntBuffer.wrap(data), shape)
	}

	private fun runSession(session: Any, inputs: Map<String, Any>): Any? {
		val sessionClass = Class.forName("ai.onnxruntime.OrtSession")
		val runMethod = sessionClass.getMethod("run", Map::class.java)
		return runMethod.invoke(session, inputs)
	}

	@Suppress("UNCHECKED_CAST")
	private fun extractFloatTensor(result: Any, name: String): FloatArray? {
		val resultClass = Class.forName("ai.onnxruntime.OrtSession\$Result")
		val getByName = resultClass.getMethod("get", String::class.java)
		val optional = getByName.invoke(result, name)
		val valueObj = when (optional) {
			null -> null
			is java.util.Optional<*> -> optional.orElse(null)
			else -> optional
		} ?: return null

		val onnxTensorClass = Class.forName("ai.onnxruntime.OnnxTensor")
		if (!onnxTensorClass.isInstance(valueObj)) return null

		// getFloatBuffer() returns a read-only FloatBuffer view of the underlying tensor.
		val getFloatBuffer = onnxTensorClass.getMethod("getFloatBuffer")
		val buf = getFloatBuffer.invoke(valueObj) as? FloatBuffer ?: return null
		val out = FloatArray(buf.remaining())
		buf.get(out)
		return out
	}

	private fun closeAutoCloseable(obj: Any?) {
		if (obj == null) return
		try {
			(obj as? AutoCloseable)?.close()
		} catch (_: Throwable) {
			// best-effort cleanup
		}
	}

	companion object {
		private const val TAG = "tt9/NeuralGlide"
		private const val ENCODER_FILENAME = "swipe_encoder.onnx"
		private const val DECODER_FILENAME = "swipe_decoder.onnx"
		const val ENCODER_HIDDEN = 256
	}
}
