/*
 * Copyright (C) 2025 tt9 contributors. Apache 2.0.
 */
package io.github.sspanak.tt9.ime.swipe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.sspanak.tt9.languages.Language
import io.github.sspanak.tt9.ui.main.keys.SoftKeyQwertyLetter
import kotlin.math.sqrt

/**
 * Hosts the QWERTY rows and owns the glide-typing classifier. Taps flow through to individual
 * [SoftKeyQwertyLetter] children untouched; swipes get intercepted once the detector decides the
 * motion is a gesture, and are fed to the classifier instead.
 *
 * On a gesture completion the container fires [onWordDecoded] (if set) with the top candidate.
 */
class SwipeableKeyboardContainer @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

	// Per-language dispatch — English uses the CleverKeys ONNX neural decoder; everything else
	// (Hebrew, French, German, etc.) uses the statistical fallback. Both implement
	// GlideTypingClassifier so the rest of this class is implementation-agnostic.
	//
	// Both decoders are lazy: a Hebrew-only user never spins up the ONNX runtime (~50 MB
	// resident saved). bindLanguage() picks the active one; setLayout() pushes geometry to
	// both so either is ready when toggled.
	private val statisticalFallback: StatisticalGlideTypingClassifier by lazy {
		StatisticalGlideTypingClassifier()
	}
	private val neuralEnglish: NeuralGlideDecoder? by lazy {
		if (io.github.sspanak.tt9.BuildConfig.HAS_NEURAL_DECODER) NeuralGlideDecoder(context) else null
	}
	@Volatile private var classifier: GlideTypingClassifier = statisticalFallback
	private var detector: GlideTypingGesture.Detector? = null
	private var layoutCollected = false
	private var boundLangId: Int = UNBOUND_LANG
	private var activeProvider: WordProvider = SeedWordProvider

	// (Removed) Touch-snap fields — see comment above onInterceptTouchEvent for context.

	// Swipe-trail renderer — ported from florisboard's drawGlideTrail (Apache 2.0). Points accumulate
	// during a gesture and paint as shrinking circles on top of the keys; cleared on completion.
	private val trailPoints = ArrayList<GlideTypingGesture.Detector.Position>()
	private var trailStartTimeMs: Long = 0L
	private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		color = Color.argb(180, 255, 255, 255)
	}
	private val trailInitialRadiusPx: Float by lazy {
		TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics)
	}
	private val trailTargetDistPx: Float by lazy {
		TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, resources.displayMetrics)
	}
	private val trailRadiusReduction: Float = 0.985f

	/** Called on the main thread when a completed gesture resolves to a ranked candidate list. */
	fun interface OnGlideSuggestions {
		fun onCandidates(words: List<String>)
	}

	/**
	 * Called on the main thread with the classifier's best guesses *while* a gesture is still in
	 * progress. Lets the IME show evolving candidates so the user can lift off when they like one,
	 * instead of waiting for the gesture to finish. Throttled internally.
	 */
	fun interface OnGlideMidSuggestions {
		fun onCandidates(words: List<String>)
	}

	private var onSuggestions: OnGlideSuggestions? = null
	private var onMidSuggestions: OnGlideMidSuggestions? = null
	// Plain Runnable — zero-arg Kotlin `fun interface`s sometimes don't SAM-convert from Java
	// lambdas, but Runnable always does. Fires on the first point of every new gesture so the
	// IME can auto-commit any pending suggestion (with trailing space).
	private var onGestureStarted: Runnable? = null
	private var prefixSupplier: () -> String = { "" }
	// Pulled per-gesture from the IME so MindReader's most recent next-word predictions can bias
	// the classifier's ranking. The classifier multiplies in-context candidates' scores by 4×.
	private var contextSupplier: () -> Set<String> = { emptySet() }
	private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
	private var lastMidGestureScheduled: Long = 0L

	// Moving-average smoothing of incoming gesture points. The Schok F1's cheap touch panel
	// reports jittery sample-to-sample positions; smoothing kills spurious direction-changes
	// before they confuse the classifier's direction-cosine penalty. Pattern from CleverKeys'
	// ImprovedSwipeGestureRecognizer.applySmoothing.
	private val smoothBuffer = ArrayList<GlideTypingGesture.Detector.Position>(SMOOTHING_WINDOW)
	private fun smoothPoint(p: GlideTypingGesture.Detector.Position): GlideTypingGesture.Detector.Position {
		smoothBuffer.add(p)
		while (smoothBuffer.size > SMOOTHING_WINDOW) smoothBuffer.removeAt(0)
		var sx = 0f; var sy = 0f
		for (pt in smoothBuffer) { sx += pt.x; sy += pt.y }
		val n = smoothBuffer.size.toFloat()
		// Use the LATEST incoming point's timestamp — averaging timestamps would smear the
		// trajectory's velocity profile, which is exactly what the neural decoder needs to read
		// to identify deceleration-at-letter points. (Without this, t=0 fell through to
		// SwipeTrajectoryProcessor and produced zero-dt velocity garbage.)
		return GlideTypingGesture.Detector.Position(sx / n, sy / n, p.t)
	}
	// Single-threaded executor for the classifier. Keeps gestures sequential (no race between
	// two analyses) and off the main thread so the UI stays responsive during scoring.
	private val classifierExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
		Thread(r, "tt9-glide-classifier").apply { priority = Thread.NORM_PRIORITY - 1 }
	}

	fun setOnGlideSuggestions(listener: OnGlideSuggestions?) {
		this.onSuggestions = listener
	}

	fun setOnGlideMidSuggestions(listener: OnGlideMidSuggestions?) {
		this.onMidSuggestions = listener
	}

	/**
	 * Hook the container calls at the start of every gesture to pull the currently locked QWERTY
	 * prefix from the IME. When non-empty, glide candidates are constrained to words that start
	 * with the prefix and are matched against the suffix only.
	 */
	fun setPrefixSupplier(supplier: (() -> String)?) {
		this.prefixSupplier = supplier ?: { "" }
	}

	fun setContextSupplier(supplier: (() -> Set<String>)?) {
		this.contextSupplier = supplier ?: { emptySet() }
	}

	fun setOnGestureStarted(callback: Runnable?) {
		this.onGestureStarted = callback
	}

	init {
		// Lay out our own children vertically by default; XML can override.
		if (orientation != VERTICAL) orientation = VERTICAL
		classifier.setWordProvider(SeedWordProvider)
		// Required so dispatchDraw runs for overlays on a ViewGroup with no background.
		setWillNotDraw(false)
	}

	/**
	 * Swap the classifier's vocabulary in for [language]'s factory dictionary. No-op if we've
	 * already bound this language. Falls back to the seed provider while the DB load is in flight
	 * or if the language has no dictionary yet.
	 */
	fun bindLanguage(language: Language?) {
		if (language == null) return
		val langId = language.id
		if (langId == boundLangId) return
		boundLangId = langId
		// Lazy-load the learned per-key touch offsets for the new language so they're ready by
		// the first tap.
		KeyOffsetAdapter.ensureLoaded(context, langId)

		// Pick which classifier serves this language. CleverKeys' neural decoder can only
		// produce English a-z output; everything else routes to the statistical fallback.
		val target: GlideTypingClassifier =
			if (language.code.equals("en", ignoreCase = true)) {
				neuralEnglish ?: statisticalFallback
			} else {
				statisticalFallback
			}
		if (target !== classifier) {
			// Layout is static per orientation; push it to the newly-active classifier so it's
			// ready by the time the word provider loads.
			val keys = classifier.layoutKeys
			if (keys.isNotEmpty()) target.setLayout(keys)
			classifier = target
		}

		Tt9WordProvider.load(language) { provider ->
			if (!provider.isLoaded) return@load
			// Pruner build loops every word. Do it on the classifier worker, not main, otherwise
			// language switches stutter for hundreds of ms.
			classifierExecutor.execute {
				if (boundLangId != langId) return@execute
				activeProvider = provider
				classifier.setWordProvider(provider)
			}
		}
	}

	override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
		super.onLayout(changed, l, t, r, b)
		if (changed || !layoutCollected) {
			collectKeysAndConfigure()
		}
	}

	private fun collectKeysAndConfigure() {
		val keys = ArrayList<SwipeKey>()
		walk(this) { view ->
			if (view is SoftKeyQwertyLetter) {
				val c = view.getText()?.firstOrNull()?.lowercaseChar() ?: return@walk
				if (!c.isLetter()) return@walk
				val bounds = localBoundsOf(view) ?: return@walk
				keys.add(SwipeKey(bounds, c.code))
			}
		}
		if (keys.isEmpty()) return
		// Push geometry to BOTH classifiers so a language switch finds the right layout
		// ready without re-walking the view tree.
		statisticalFallback.setLayout(keys)
		neuralEnglish?.setLayout(keys)

		val density = resources.displayMetrics.density
		val keyWidthDp = keys.first().width / density
		detector = GlideTypingGesture.Detector(keyWidthDp, density).apply {
			registerListener(object : GlideTypingGesture.Listener {
				override fun onGlideAddPoint(point: GlideTypingGesture.Detector.Position) {
					if (trailPoints.isEmpty()) {
						// First point of a fresh gesture. Notify the IME so it can commit
						// any pending suggestion from a previous gesture (with trailing space)
						// BEFORE this new gesture's mid-gesture preview starts overwriting
						// composing text. Distinguishes "previous gesture committable" from
						// "this gesture's mid-preview" which was the "hellohello" bug.
						onGestureStarted?.run()
						classifier.setWordPrefix(prefixSupplier())
						trailStartTimeMs = System.currentTimeMillis()
						smoothBuffer.clear()
					}
					val smoothed = smoothPoint(point)
					classifier.addGesturePoint(smoothed)
					// Trail uses the raw point so the visual trail tracks the real finger; the
					// smoothed copy goes to the classifier where jitter matters.
					trailPoints.add(point)
					invalidate()
					// Mid-gesture suggestion refresh (worker-thread). Debounced: only the last
					// add-point in each MID_GESTURE_THROTTLE_MS window kicks off a query.
					// Snapshot the gesture on the main thread, score on the worker, post back.
					if (onMidSuggestions != null && trailPoints.size >= MID_GESTURE_MIN_POINTS) {
						val now = System.currentTimeMillis()
						if (now - lastMidGestureScheduled >= MID_GESTURE_THROTTLE_MS) {
							lastMidGestureScheduled = now
							val snapshot = classifier.cloneInternalGesture()
							val ctx = contextSupplier()
							classifierExecutor.execute {
								val live = classifier.analyzeGesture(snapshot, MID_GESTURE_CANDIDATE_COUNT, ctx)
								if (live.isNotEmpty()) {
									mainHandler.post { onMidSuggestions?.onCandidates(live) }
								}
							}
						}
					}
				}

				override fun onGlideComplete(data: GlideTypingGesture.Detector.PointerData) {
					// Snapshot the gesture and clear the live one atomically on the main thread,
					// THEN dispatch scoring to the worker. The user's finger may already be down
					// for the next gesture by the time scoring finishes — having a snapshot lets
					// addGesturePoint keep mutating the live gesture safely in parallel.
					val snapshot = classifier.snapshotGestureAndClear()
					// Pull context on the main thread (MindReader access) and pass it to the
					// worker — keeps the classifier purely off-main.
					val ctx = contextSupplier()
					io.github.sspanak.tt9.util.Logger.d(
						"tt9/Glide",
						"onGlideComplete: snapshot ${snapshot.pointCount} pts, classifier ready=${classifier.ready}, ctx=${ctx.size}"
					)
					clearTrail()
					val startMs = System.currentTimeMillis()
					classifierExecutor.execute {
						val suggestions = classifier.analyzeGesture(snapshot, GLIDE_CANDIDATE_COUNT, ctx)
						val elapsed = System.currentTimeMillis() - startMs
						io.github.sspanak.tt9.util.Logger.d(
							"tt9/Glide",
							"analyzeGesture returned ${suggestions.size} in ${elapsed}ms: $suggestions"
						)
						mainHandler.post {
							onSuggestions?.onCandidates(suggestions)
							io.github.sspanak.tt9.util.Logger.d(
								"tt9/Glide",
								"posted ${suggestions.size} candidates to IME"
							)
						}
					}
				}

				override fun onGlideCancelled() {
					classifier.clear()
					clearTrail()
				}
			})
		}
		layoutCollected = true
	}

	/** Bounds of [child] in this container's coordinate space. */
	private fun localBoundsOf(child: View): RectF? {
		if (child.width == 0 || child.height == 0) return null
		var x = 0f
		var y = 0f
		var node: View = child
		while (node !== this) {
			x += node.x
			y += node.y
			val parent = node.parent as? View ?: return null
			node = parent
		}
		return RectF(x, y, x + child.width, y + child.height)
	}

	private fun walk(group: ViewGroup, visit: (View) -> Unit) {
		val n = group.childCount
		for (i in 0 until n) {
			val child = group.getChildAt(i)
			visit(child)
			if (child is ViewGroup) walk(child, visit)
		}
	}

	override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
		// Apply the per-key learned touch offset to the DOWN event ONLY (so the right child key
		// receives the touch). Subsequent MOVE / UP events keep their raw coords so the glide
		// gesture detector sees the user's actual finger path. The applied offset is small
		// (capped at ~32px) — well below the gesture-detector's distance threshold, so it can't
		// trigger a phantom gesture.
		if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
			applyKeyOffsetCorrection(ev)
		}
		return super.dispatchTouchEvent(ev)
	}

	private fun applyKeyOffsetCorrection(ev: MotionEvent) {
		if (boundLangId == UNBOUND_LANG) return
		val keys = classifier.layoutKeys
		if (keys.isEmpty()) return
		val idx = ev.actionIndex
		val x = ev.getX(idx)
		val y = ev.getY(idx)
		val nearest = findNearestKey(x, y, keys) ?: return
		val offset = KeyOffsetAdapter.getOffset(boundLangId, nearest.code.toChar())
		if (offset[0] != 0f || offset[1] != 0f) {
			// Subtract the learned offset: if user systematically taps "right of A", a touch
			// that lands "right of A" gets shifted left so it falls inside A's hitbox.
			ev.offsetLocation(-offset[0], -offset[1])
		}
	}

	private fun findNearestKey(x: Float, y: Float, keys: List<SwipeKey>): SwipeKey? {
		var best: SwipeKey? = null
		var bestSq = Float.MAX_VALUE
		for (k in keys) {
			val dx = k.centerX - x
			val dy = k.centerY - y
			val sq = dx * dx + dy * dy
			if (sq < bestSq) { bestSq = sq; best = k }
		}
		return best
	}

	override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
		val d = detector ?: return false
		// Feed the detector; it returns true once it's confident the motion is a gesture.
		val intercept = d.onTouchEvent(ev)
		// Reset on DOWN so a fresh sequence can start even if the previous gesture was cancelled.
		return intercept
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		val d = detector ?: return false
		d.onTouchEvent(event)
		return true
	}

	override fun dispatchDraw(canvas: Canvas) {
		super.dispatchDraw(canvas)
		drawGlideTrail(canvas)
	}

	/**
	 * Florisboard's tapered-circle trail algorithm (Apache 2.0). Walks the captured points in reverse,
	 * interpolating fixed-distance samples between adjacent pairs and drawing a circle with shrinking
	 * radius at each. The newest points are largest so the trail looks like a tadpole tail.
	 */
	private fun drawGlideTrail(canvas: Canvas) {
		if (trailPoints.size < 2) return
		var radius = trailInitialRadiusPx
		var prevX = trailPoints.last().x
		var prevY = trailPoints.last().y
		for (i in trailPoints.size - 1 downTo 1) {
			val p = trailPoints[i - 1]
			val dx = prevX - p.x
			val dy = prevY - p.y
			val dist = sqrt(dx * dx + dy * dy)
			val numPoints = (dist / trailTargetDistPx).toInt()
			if (numPoints == 0) continue
			val cur = trailPoints[i]
			for (j in 0 until numPoints) {
				radius *= trailRadiusReduction
				if (radius < 0.5f) return
				val t = j.toFloat() / numPoints
				val ix = cur.x * (1 - t) + p.x * t
				val iy = cur.y * (1 - t) + p.y * t
				canvas.drawCircle(ix, iy, radius, trailPaint)
				prevX = ix
				prevY = iy
			}
		}
	}

	private fun clearTrail() {
		trailPoints.clear()
		invalidate()
	}

	companion object {
		private const val UNBOUND_LANG: Int = -1
		// How many candidates to surface to the suggestion strip. The strip itself is scrollable so
		// the user can pick #2/#3 when shapes collide.
		private const val GLIDE_CANDIDATE_COUNT: Int = 5
		// Fewer candidates during the gesture — the list is more volatile, more is just noise.
		private const val MID_GESTURE_CANDIDATE_COUNT: Int = 3
		// Touch-smoothing window. Average of last N raw points before feeding to classifier.
		// 3 absorbs panel jitter without lagging behind real motion.
		private const val SMOOTHING_WINDOW: Int = 3
		// Min gesture points before a mid-gesture query is worth running. Below ~12 sampled
		// points the path is too short for the classifier to do anything meaningful.
		private const val MID_GESTURE_MIN_POINTS: Int = 12
		// Mid-gesture throttle window in ms. Classifier now runs on the worker thread so the UI
		// doesn't block; this is just rate-limiting so the suggestion strip doesn't churn.
		private const val MID_GESTURE_THROTTLE_MS: Long = 150L
	}
}
