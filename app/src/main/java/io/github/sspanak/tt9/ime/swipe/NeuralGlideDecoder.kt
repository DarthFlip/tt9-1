/*
 * Copyright (C) 2026 tt9 contributors. GPL-3.0.
 *
 * Neural swipe decoder backed by FUTO's published encoder weights (FUTO Model Weights License 1.0)
 * running on Meta's ExecuTorch Android runtime (BSD-3). Implements the same GlideTypingClassifier
 * interface as the statistical fallback, so SwipeableKeyboardContainer can swap one for the other
 * via build-flavor.
 *
 * Pipeline per gesture:
 *   raw points → GestureResampler → [1, 2, 64] features tensor
 *   layout keys → buildLayoutTensors → [1, 64, 2] + [1, 64] mask
 *   → ExecuTorch Module.forward → log_emissions [1, 32, 65]
 *   → BeamSearch (trie-constrained, MindReader context bonus) → top-N candidates
 *
 * The model is layout-agnostic: layout coordinates flow in as a runtime input, so the same .pte
 * works for English QWERTY, Hebrew QWERTY, T9 grid — no per-language model file needed.
 */
package io.github.sspanak.tt9.ime.swipe

import android.content.Context
import android.util.Log
import io.github.sspanak.tt9.util.Logger
import java.io.File
import java.io.FileOutputStream

class NeuralGlideDecoder(private val context: Context) : GlideTypingClassifier {

	private val gesture = StatisticalGlideTypingClassifier.Gesture()
	private var wordProvider: WordProvider = EmptyWordProvider
	// All four are written from main thread (setLayout) and read from worker (runEncoder /
	// BeamSearch). @Volatile so the worker sees a coherent snapshot — the encoder pipeline
	// is atomic per-gesture; no intra-call mutation is expected.
	@Volatile private var keys: List<SwipeKey> = emptyList()
	@Volatile private var charToKeyIndex: Map<Char, Int> = emptyMap()
	@Volatile private var cachedLayoutKeys: FloatArray = FloatArray(NUM_KEYS * 2)
	@Volatile private var cachedLayoutMask: FloatArray = FloatArray(NUM_KEYS)
	@Volatile private var wordPrefix: String = ""
	private val trie = LexiconTrie()
	private val beamSearch = BeamSearch()

	// ExecuTorch module — loaded lazily on first inference. The model file ships in assets but
	// must be on the filesystem for ExecuTorch's Module.load(path) — we copy on first init.
	@Volatile private var module: Any? = null
	@Volatile private var modulePath: String? = null
	private val moduleLock = Any()

	override val ready: Boolean
		get() = keys.isNotEmpty() && trie.wordCount > 0

	override val layoutKeys: List<SwipeKey> get() = keys

	override fun addGesturePoint(position: GlideTypingGesture.Detector.Position) {
		gesture.addPoint(position.x, position.y)
	}

	override fun setLayout(keys: List<SwipeKey>) {
		this.keys = keys
		// Pre-compute layout tensors so analyzeGesture doesn't redo it per call (layout only
		// changes on bindLanguage / orientation).
		val (k, m) = GestureResampler.buildLayoutTensors(keys, NUM_KEYS)
		cachedLayoutKeys = k
		cachedLayoutMask = m
		// Reverse map char → layout slot index. BeamSearch uses this to translate per-timestep
		// model logits into the trie's char alphabet. First-occurrence wins if the same char
		// appears on multiple keys (shouldn't happen on a real layout but defend anyway).
		val map = HashMap<Char, Int>(keys.size)
		for (i in keys.indices) {
			if (i >= NUM_KEYS) break
			val ch = keys[i].code.toChar().lowercaseChar()
			if (ch !in map) map[ch] = i
		}
		charToKeyIndex = map
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
		val mod = ensureModuleLoaded() ?: return emptyList()

		val t0 = System.currentTimeMillis()

		val features = GestureResampler.resampleToBuffer(snapshot, keys, TIMESTEPS_IN)
		val logEmissions = runEncoder(mod, features) ?: return emptyList()

		val tEnc = System.currentTimeMillis() - t0

		val results = beamSearch.decode(
			logEmissions = logEmissions,
			numKeys = NUM_KEYS,
			charToKeyIndex = charToKeyIndex,
			trie = trie,
			lockedPrefix = wordPrefix,
			contextWords = contextWords,
			maxResults = maxSuggestionCount,
		)
		Logger.d(TAG, "encode=${tEnc}ms total=${System.currentTimeMillis() - t0}ms results=${results.size}")
		return results
	}

	/**
	 * ExecuTorch's Module API requires a filesystem path, but Android assets are inside the APK.
	 * Copy the asset to internal storage on first call; subsequent calls reuse the cached file.
	 *
	 * Returns the loaded Module (as Any to avoid hard-coding the ExecuTorch class name when the
	 * dependency may be flavor-scoped — runtime reflection-style guard).
	 */
	private fun ensureModuleLoaded(): Any? {
		val cached = module
		if (cached != null) return cached
		synchronized(moduleLock) {
			val again = module
			if (again != null) return again
			return try {
				val path = extractAssetIfNeeded()
				val moduleClass = Class.forName("org.pytorch.executorch.Module")
				val loadMethod = moduleClass.getMethod("load", String::class.java)
				val loaded = loadMethod.invoke(null, path)
				modulePath = path
				module = loaded
				Logger.d(TAG, "loaded ExecuTorch module from $path")
				loaded
			} catch (e: Throwable) {
				Log.e(TAG, "failed to load model — neural decoder will return empty results", e)
				// Cached .pte may be truncated from a previous interrupted extraction; wipe so
				// the next attempt re-fetches a clean copy.
				try { wipeCachedAsset() } catch (_: Throwable) {}
				null
			}
		}
	}

	/**
	 * Extract the .pte asset to internal storage atomically. Naive `copyTo(out)` is non-atomic —
	 * if the IME process is killed mid-copy, `out` ends up truncated. Future cold-loads see
	 * `length() > 0` and skip re-extraction → `Module.load` throws → silent empty results.
	 * Fix: write to a sibling .tmp file, fsync, then rename. If `Module.load` fails downstream
	 * (`ensureModuleLoaded`'s catch), the cached file is wiped so the next attempt re-extracts.
	 */
	private fun extractAssetIfNeeded(): String {
		val out = File(context.filesDir, ASSET_FILENAME)
		if (out.exists() && out.length() > 0) return out.absolutePath
		val tmp = File(context.filesDir, "$ASSET_FILENAME.tmp")
		if (tmp.exists()) tmp.delete()
		context.assets.open("models/$ASSET_FILENAME").use { input ->
			FileOutputStream(tmp).use { output ->
				input.copyTo(output)
				output.fd.sync()
			}
		}
		if (!tmp.renameTo(out)) {
			tmp.delete()
			throw RuntimeException("rename of $tmp → $out failed")
		}
		Logger.d(TAG, "extracted asset to ${out.absolutePath} (${out.length()} bytes)")
		return out.absolutePath
	}

	private fun wipeCachedAsset() {
		val out = File(context.filesDir, ASSET_FILENAME)
		if (out.exists()) out.delete()
	}

	/**
	 * Run the encoder model. Inputs: features [1,2,64], layout_keys [1,64,2], layout_mask [1,64].
	 * Returns log_emissions as [T × (numKeys+1)] = [32 × 65].
	 *
	 * Uses reflection to dodge a hard compile-time dependency on ExecuTorch types — keeps this
	 * file compiling cleanly in flavors that don't ship the runtime.
	 */
	private fun runEncoder(mod: Any, features: FloatArray): Array<FloatArray>? {
		return try {
			val tensorClass = Class.forName("org.pytorch.executorch.Tensor")
			val evalueClass = Class.forName("org.pytorch.executorch.EValue")
			val fromBlobFloat = tensorClass.getMethod(
				"fromBlob",
				FloatArray::class.java,
				LongArray::class.java,
			)
			val evalueFrom = evalueClass.getMethod("from", tensorClass)

			val featuresTensor = fromBlobFloat.invoke(null, features, longArrayOf(1, 2, TIMESTEPS_IN.toLong()))
			val layoutTensor = fromBlobFloat.invoke(null, cachedLayoutKeys, longArrayOf(1, NUM_KEYS.toLong(), 2))
			val maskTensor = fromBlobFloat.invoke(null, cachedLayoutMask, longArrayOf(1, NUM_KEYS.toLong()))

			// Build an EValue[] (NOT Object[]) so reflection's getMethod() matches forward's
			// declared signature `EValue[] forward(EValue[] inputs)`.
			val evalueArrayClass = java.lang.reflect.Array.newInstance(evalueClass, 0).javaClass
			val inputs = java.lang.reflect.Array.newInstance(evalueClass, 3)
			java.lang.reflect.Array.set(inputs, 0, evalueFrom.invoke(null, featuresTensor))
			java.lang.reflect.Array.set(inputs, 1, evalueFrom.invoke(null, layoutTensor))
			java.lang.reflect.Array.set(inputs, 2, evalueFrom.invoke(null, maskTensor))

			val moduleClass = mod.javaClass
			val forwardMethod = moduleClass.getMethod("forward", evalueArrayClass)
			val outputs = forwardMethod.invoke(mod, inputs) as Array<*>

			// Output 0 is log_emissions [1, 32, 65]
			val outEvalue = outputs[0] ?: return null
			val toTensorMethod = evalueClass.getMethod("toTensor")
			val outTensor = toTensorMethod.invoke(outEvalue)
			val getDataAsFloatArray = tensorClass.getMethod("getDataAsFloatArray")
			val flat = getDataAsFloatArray.invoke(outTensor) as FloatArray

			val expectedSize = TIMESTEPS_OUT * (NUM_KEYS + 1)
			if (flat.size != expectedSize) {
				Log.e(TAG, "model output size mismatch: got ${flat.size}, expected $expectedSize")
				return null
			}

			// Reshape [1, 32, 65] row-major flat → [32][65]
			Array(TIMESTEPS_OUT) { t ->
				FloatArray(NUM_KEYS + 1) { k -> flat[t * (NUM_KEYS + 1) + k] }
			}
		} catch (e: Throwable) {
			Log.e(TAG, "encoder inference failed", e)
			null
		}
	}

	companion object {
		private const val TAG = "tt9/NeuralGlide"
		private const val ASSET_FILENAME = "model_fp32.pte"
		const val TIMESTEPS_IN = 64
		const val TIMESTEPS_OUT = 32
		const val NUM_KEYS = 64
	}
}
