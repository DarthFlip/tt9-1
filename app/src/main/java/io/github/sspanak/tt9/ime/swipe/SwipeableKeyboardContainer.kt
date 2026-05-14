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

	private val classifier = StatisticalGlideTypingClassifier()
	private var detector: GlideTypingGesture.Detector? = null
	private var layoutCollected = false
	private var boundLangId: Int = UNBOUND_LANG
	private var activeProvider: WordProvider = SeedWordProvider

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

	/** Called on the main thread when a completed gesture resolves to at least one candidate. */
	fun interface OnWordDecoded {
		fun onWord(word: String)
	}

	private var onWordDecoded: OnWordDecoded? = null

	fun setOnWordDecoded(listener: OnWordDecoded?) {
		this.onWordDecoded = listener
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
		Tt9WordProvider.load(language) { provider ->
			post {
				if (boundLangId != langId) return@post
				if (!provider.isLoaded) return@post
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
		classifier.setLayout(keys)

		val density = resources.displayMetrics.density
		val keyWidthDp = keys.first().width / density
		detector = GlideTypingGesture.Detector(keyWidthDp, density).apply {
			registerListener(object : GlideTypingGesture.Listener {
				override fun onGlideAddPoint(point: GlideTypingGesture.Detector.Position) {
					classifier.addGesturePoint(point)
					if (trailPoints.isEmpty()) trailStartTimeMs = System.currentTimeMillis()
					trailPoints.add(point)
					invalidate()
				}

				override fun onGlideComplete(data: GlideTypingGesture.Detector.PointerData) {
					val suggestions = classifier.getSuggestions(3, true)
					classifier.clear()
					clearTrail()
					val top = suggestions.firstOrNull() ?: return
					onWordDecoded?.onWord(top)
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
	}
}
