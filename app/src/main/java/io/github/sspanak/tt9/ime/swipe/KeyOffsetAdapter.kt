package io.github.sspanak.tt9.ime.swipe

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-key, per-language touch-offset learner. Inspired by Gboard's
 * `Adaptation.TouchCenter.AvgOffsetMagnitude` mechanism — every time the user successfully types
 * a key, we update an exponential moving average of how far their finger landed from the visual
 * key center. Future touches are interpreted as if shifted back by that learned offset, so the
 * keyboard adapts to where the user's finger actually goes (thumb angle, hand size, posture,
 * screen-calibration drift).
 *
 * Offsets are stored in [SharedPreferences] as a compact CSV-per-language string so they survive
 * IME restarts and crashes. Per-language scope because QWERTY-EN, Hebrew, and emoji layouts
 * place keys differently.
 *
 * Singleton-style object so the adapter's lifetime tracks the process, not the IME view (which
 * is recreated frequently). Thread-safe — the touch path runs on the main thread but
 * persistence work may post elsewhere.
 */
object KeyOffsetAdapter {
	private const val PREFS_NAME = "tt9_key_offsets"
	private const val PREFS_KEY_PREFIX = "lang_"
	// Exponential moving-average weight on the new sample. 0.1 = "10% new, 90% history" — slow
	// enough that a few mistyped taps don't whiplash the offset, fast enough that a real bias
	// shows up within a few dozen taps.
	private const val EMA_ALPHA = 0.10f
	// Hard clamp on accumulated offset. Beyond this many pixels we assume bad data and discard
	// rather than letting one outlier ruin the model. Roughly half a key width on the F1.
	private const val MAX_ABS_OFFSET_PX = 32f

	// languageId → char → (offsetX, offsetY) in pixels, relative to the key's visual center.
	// A positive offsetX means "user systematically taps right of center for this key."
	private val offsetsByLang = ConcurrentHashMap<Int, ConcurrentHashMap<Char, FloatArray>>()
	private val loaded = ConcurrentHashMap<Int, Boolean>()

	/** Lazily load offsets for [languageId] from prefs. Safe to call multiple times. */
	fun ensureLoaded(context: Context, languageId: Int) {
		if (loaded[languageId] == true) return
		synchronized(this) {
			if (loaded[languageId] == true) return
			loadFromPrefs(context, languageId)
			loaded[languageId] = true
		}
	}

	/**
	 * Look up the learned (dx, dy) offset for [keyChar] in [languageId]. Returns (0, 0) when
	 * we haven't seen enough samples yet (the no-op case — falls through to standard hit-testing).
	 * The returned FloatArray is a defensive copy; callers must not mutate.
	 */
	fun getOffset(languageId: Int, keyChar: Char): FloatArray {
		val byChar = offsetsByLang[languageId] ?: return ZERO_OFFSET
		val stored = byChar[Character.toLowerCase(keyChar)] ?: return ZERO_OFFSET
		return floatArrayOf(stored[0], stored[1])
	}

	/**
	 * Update the EMA for [keyChar] with one sample of (newDx, newDy). Caller computes the
	 * sample as `touchDown - keyCenter` in any consistent coordinate space (key-local view
	 * coords are easiest). Persistence is throttled — we write to prefs every
	 * [PERSIST_EVERY_N_RECORDS] updates per language.
	 */
	fun record(context: Context, languageId: Int, keyChar: Char, newDx: Float, newDy: Float) {
		if (!isFinite(newDx) || !isFinite(newDy)) return
		ensureLoaded(context, languageId)
		val byChar = offsetsByLang.getOrPut(languageId) { ConcurrentHashMap() }
		val lower = Character.toLowerCase(keyChar)
		val existing = byChar[lower]
		val updated = if (existing == null) {
			floatArrayOf(newDx * EMA_ALPHA, newDy * EMA_ALPHA)
		} else {
			floatArrayOf(
				existing[0] * (1f - EMA_ALPHA) + newDx * EMA_ALPHA,
				existing[1] * (1f - EMA_ALPHA) + newDy * EMA_ALPHA,
			)
		}
		updated[0] = clamp(updated[0], -MAX_ABS_OFFSET_PX, MAX_ABS_OFFSET_PX)
		updated[1] = clamp(updated[1], -MAX_ABS_OFFSET_PX, MAX_ABS_OFFSET_PX)
		byChar[lower] = updated

		val n = pendingWrites.incrementAndGet()
		if (n >= PERSIST_EVERY_N_RECORDS) {
			pendingWrites.set(0)
			persistAsync(context, languageId)
		}
	}

	/**
	 * Drop all learned offsets for [languageId] (e.g., when the user changes layout / explicitly
	 * resets). Wipes both in-memory and disk state.
	 */
	fun reset(context: Context, languageId: Int) {
		offsetsByLang.remove(languageId)
		loaded.remove(languageId)
		prefs(context).edit().remove(prefsKeyFor(languageId)).apply()
	}

	// ---- private ----

	private val ZERO_OFFSET = floatArrayOf(0f, 0f)
	private const val PERSIST_EVERY_N_RECORDS = 10
	private val pendingWrites = java.util.concurrent.atomic.AtomicInteger(0)

	private fun prefs(context: Context): SharedPreferences =
		context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	private fun prefsKeyFor(languageId: Int): String = PREFS_KEY_PREFIX + languageId

	private fun loadFromPrefs(context: Context, languageId: Int) {
		val raw = prefs(context).getString(prefsKeyFor(languageId), null) ?: return
		val byChar = ConcurrentHashMap<Char, FloatArray>()
		// Format: "c1:dx,dy;c2:dx,dy;..." — utf-8 key char (lowercase ASCII for English), two
		// signed floats, semicolon separator. Tolerates malformed entries by skipping them.
		raw.split(';').forEach { entry ->
			if (entry.isEmpty()) return@forEach
			val colon = entry.indexOf(':')
			if (colon <= 0) return@forEach
			val ch = entry[0]
			val coords = entry.substring(colon + 1).split(',')
			if (coords.size != 2) return@forEach
			val dx = coords[0].toFloatOrNull() ?: return@forEach
			val dy = coords[1].toFloatOrNull() ?: return@forEach
			byChar[Character.toLowerCase(ch)] = floatArrayOf(dx, dy)
		}
		offsetsByLang[languageId] = byChar
	}

	private fun persistAsync(context: Context, languageId: Int) {
		val byChar = offsetsByLang[languageId] ?: return
		Thread({
			val sb = StringBuilder()
			byChar.forEach { (ch, off) ->
				sb.append(ch).append(':').append(off[0]).append(',').append(off[1]).append(';')
			}
			prefs(context).edit().putString(prefsKeyFor(languageId), sb.toString()).apply()
		}, "tt9-key-offset-persist").start()
	}

	private fun clamp(v: Float, lo: Float, hi: Float): Float =
		if (v < lo) lo else if (v > hi) hi else v

	private fun isFinite(v: Float): Boolean = !v.isNaN() && !v.isInfinite()
}
