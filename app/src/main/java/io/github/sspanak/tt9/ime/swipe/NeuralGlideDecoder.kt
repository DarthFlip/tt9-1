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
import android.graphics.PointF
import android.util.Log
import io.github.sspanak.tt9.ime.swipe.cleverkeys.SwipeInput
import io.github.sspanak.tt9.ime.swipe.cleverkeys.SwipeTrajectoryProcessor
import io.github.sspanak.tt9.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.IntBuffer

class NeuralGlideDecoder(private val context: Context) : GlideTypingClassifier {

	private val gesture = StatisticalGlideTypingClassifier.Gesture()
	private var wordProvider: WordProvider = EmptyWordProvider
	@Volatile private var keys: List<SwipeKey> = emptyList()
	@Volatile private var wordPrefix: String = ""
	private val trie = LexiconTrie()
	private val beamSearch = BeamSearch()
	// Ported CleverKeys preprocessing. Their pipeline: raw coords + real timestamps
	// → optional resample if > 250 → per-point velocity/acceleration/normalization → fixed
	// [1, 250, 6] tensor + nearest-keys + actual_length. Replaces our hand-rolled
	// CleverKeysFeatures which got the velocity scaling wrong by ~250×.
	private val trajectoryProcessor = SwipeTrajectoryProcessor()

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
		gesture.addPoint(position.x, position.y, position.t)
	}

	override fun setLayout(keys: List<SwipeKey>) {
		this.keys = keys
		// Wire CleverKeys' processor with the layout — it uses these for both nearest-key
		// detection and the qwerty-area normalization frame the model expects.
		val (minX, maxX, minY, maxY) = layoutBounds(keys)
		val width = (maxX - minX).coerceAtLeast(1f)
		val height = (maxY - minY).coerceAtLeast(1f)
		val keyPositions = HashMap<Char, PointF>(keys.size)
		for (k in keys) {
			val ch = k.code.toChar().lowercaseChar()
			if (ch in 'a'..'z' && ch !in keyPositions) {
				keyPositions[ch] = PointF(k.centerX, k.centerY)
			}
		}
		trajectoryProcessor.setKeyboardLayout(keyPositions, width, height)
		trajectoryProcessor.setMargins(minX, 0f)
		trajectoryProcessor.setQwertyAreaBounds(minY, height)
		// Finger-occlusion correction: scale CleverKeys' ~74px to the F1's key height.
		val avgKeyHeight = if (keys.isEmpty()) 0f else keys.sumOf { it.height.toDouble() }.toFloat() / keys.size
		trajectoryProcessor.setTouchYOffset(avgKeyHeight * 0.30f)
	}

	private fun layoutBounds(keys: List<SwipeKey>): FloatArray {
		var minX = Float.MAX_VALUE
		var maxX = -Float.MAX_VALUE
		var minY = Float.MAX_VALUE
		var maxY = -Float.MAX_VALUE
		for (k in keys) {
			val hw = k.width / 2f
			val hh = k.height / 2f
			if (k.centerX - hw < minX) minX = k.centerX - hw
			if (k.centerX + hw > maxX) maxX = k.centerX + hw
			if (k.centerY - hh < minY) minY = k.centerY - hh
			if (k.centerY + hh > maxY) maxY = k.centerY + hh
		}
		return floatArrayOf(minX, maxX, minY, maxY)
	}

	private operator fun FloatArray.component1() = this[0]
	private operator fun FloatArray.component2() = this[1]
	private operator fun FloatArray.component3() = this[2]
	private operator fun FloatArray.component4() = this[3]

	override fun setWordProvider(provider: WordProvider) {
		wordProvider = provider
		trie.build(provider.getListOfWords()) { provider.getFrequencyForWord(it) }
		Logger.d(TAG, "trie built: ${trie.wordCount} words")
	}

	override fun setWordPrefix(prefix: String) {
		wordPrefix = prefix.lowercase()
	}

	override fun initGestureFromPointerData(pointerData: GlideTypingGesture.Detector.PointerData) {
		for (p in pointerData.positions) gesture.addPoint(p.x, p.y, p.t)
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

		// Build SwipeInput from the gesture snapshot using REAL MotionEvent.eventTime
		// timestamps. This is what fixes velocity scaling — uniform synthetic dt produced
		// "flat" velocity profiles the model couldn't interpret.
		val coordinates = ArrayList<PointF>(snapshot.pointCount)
		val timestamps = ArrayList<Long>(snapshot.pointCount)
		for (i in 0 until snapshot.pointCount) {
			coordinates.add(PointF(snapshot.getX(i), snapshot.getY(i)))
			timestamps.add(snapshot.getT(i))
		}
		val swipeInput = SwipeInput(coordinates, timestamps)
		val features = trajectoryProcessor.extractFeatures(swipeInput, MAX_SEQ_LEN)
		if (features.actualLength == 0) return emptyList()

		// Flatten to model tensors: features [1, 250, 6] timestep-major, nearest_keys [1, 250],
		// actual_length [1]. SwipeTrajectoryProcessor handles padding/truncation already.
		val featuresFlat = FloatArray(MAX_SEQ_LEN * FEATURE_DIM)
		val nearestFlat = IntArray(MAX_SEQ_LEN)
		val n = features.actualLength.coerceAtMost(MAX_SEQ_LEN)
		for (i in 0 until n) {
			val p = features.normalizedPoints[i]
			val base = i * FEATURE_DIM
			featuresFlat[base] = p.x
			featuresFlat[base + 1] = p.y
			featuresFlat[base + 2] = p.vx
			featuresFlat[base + 3] = p.vy
			featuresFlat[base + 4] = p.ax
			featuresFlat[base + 5] = p.ay
			nearestFlat[i] = features.nearestKeys[i]
		}
		val actualLength = IntArray(1) { n }
		val encoderInputs = CleverKeysFeatures.Inputs(featuresFlat, nearestFlat, actualLength)

		val memory = runEncoder(encoderInputs) ?: return emptyList()
		val tEnc = System.currentTimeMillis() - t0

		val seqLen = memory.size / ENCODER_HIDDEN
		val srcLen = IntArray(1) { n }
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

				// Use file-path loading (NOT byte-array). The byte-array createSession overload
				// triggers a SIGBUS / BUS_ADRALN crash inside libonnxruntime.so on armv7 — the
				// model bytes get parsed with unaligned-load instructions that armv7 doesn't
				// tolerate. The file-path overload routes through a different C++ code path
				// that uses aligned mmap on the file descriptor instead.
				val encoderPath = extractAssetAtomic(ENCODER_FILENAME)
				val decoderPath = extractAssetAtomic(DECODER_FILENAME)

				val createSession = ortEnvClass.getMethod("createSession", String::class.java)
				encoderSession = createSession.invoke(env, encoderPath)
				decoderSession = createSession.invoke(env, decoderPath)
				Logger.d(TAG, "ONNX sessions loaded from $encoderPath, $decoderPath")
				true
			} catch (e: Throwable) {
				Log.e(TAG, "failed to load ONNX sessions — neural decoder will return empty results", e)
				// Cached files may be truncated from a previous interrupted extraction; wipe so
				// the next attempt re-extracts a clean copy.
				try { wipeCachedAsset(ENCODER_FILENAME); wipeCachedAsset(DECODER_FILENAME) } catch (_: Throwable) {}
				false
			}
		}
	}

	/**
	 * Extract an asset to internal storage atomically — write to .tmp + fsync + rename, so a
	 * mid-copy process kill doesn't leave a truncated file the next launch silently accepts.
	 */
	private fun extractAssetAtomic(filename: String): String {
		val out = File(context.filesDir, filename)
		if (out.exists() && out.length() > 0) return out.absolutePath
		val tmp = File(context.filesDir, "$filename.tmp")
		if (tmp.exists()) tmp.delete()
		context.assets.open("models/$filename").use { input ->
			FileOutputStream(tmp).use { output ->
				input.copyTo(output)
				output.fd.sync()
			}
		}
		if (!tmp.renameTo(out)) {
			tmp.delete()
			throw RuntimeException("rename of $tmp → $out failed")
		}
		Logger.d(TAG, "extracted $filename to ${out.absolutePath} (${out.length()} bytes)")
		return out.absolutePath
	}

	private fun wipeCachedAsset(filename: String) {
		val out = File(context.filesDir, filename)
		if (out.exists()) out.delete()
	}

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
					extractFloatTensor(result, "encoder_output")
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
					extractFloatTensor(result, "log_probs")
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
		// ORT format (not raw .onnx) — converted via convert_onnx_models_to_ort with
		// --target_platform arm. The ORT flatbuffer guarantees 4-byte aligned buffers, which
		// fixes the SIGBUS/BUS_ADRALN crash ORT 1.20-1.26 hits when parsing raw .onnx on the
		// F1's armv7 hardware. Same API path: createSession(String) handles both formats.
		private const val ENCODER_FILENAME = "swipe_encoder.ort"
		private const val DECODER_FILENAME = "swipe_decoder.ort"
		const val ENCODER_HIDDEN = 256
		const val MAX_SEQ_LEN = 250
		const val FEATURE_DIM = 6
	}
}
