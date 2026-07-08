package io.github.sspanak.tt9.ui.main;

import android.content.res.Resources;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import java.util.ArrayList;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.ime.TraditionalT9;
import io.github.sspanak.tt9.ime.swipe.SwipeableKeyboardContainer;
import io.github.sspanak.tt9.ui.main.keys.SoftKey;
import io.github.sspanak.tt9.util.sys.DeviceInfo;

class MainLayoutQwerty extends MainLayoutExtraPanel {
	private static final int ROW_COUNT = 4;
	private boolean isCommandPaletteShown = false;
	private boolean isTextEditingPaletteShown = false;
	private int height;
	// Sticky collapsed state: when the user types physically, we hide the keys; non-tap events
	// (mode-switch hotkey, mid-suggestion render, etc.) that re-run showKeyboard must NOT
	// resurrect the keys. Only a real onStartInputView clears this flag.
	private boolean keysExplicitlyCollapsed = false;


	MainLayoutQwerty(TraditionalT9 tt9) {
		super(tt9, R.layout.main_qwerty);
	}


	@Override
	void showKeyboard() {
		super.showKeyboard();
		isCommandPaletteShown = false;
		isTextEditingPaletteShown = false;
		// Respect the sticky-collapsed flag — non-tap renders (mode switch, suggestion update,
		// hotkey hold) should not undo the user's physical-typing dismissal.
		togglePanel(R.id.qwerty_keys_container, !keysExplicitlyCollapsed);
		if (keysExplicitlyCollapsed) {
			setKeyboardHeight(computeStatusBarHeight());
		}
	}


	@Override
	public boolean isKeysCollapsed() { return keysExplicitlyCollapsed; }


	@Override
	public void setKeysCollapsed(boolean collapsed) {
		keysExplicitlyCollapsed = collapsed;
		togglePanel(R.id.qwerty_keys_container, !collapsed);
		// The outer keyboard_container has a pinned height from setKeyboardHeight; the LinearLayout
		// doesn't reflow on its own when a child becomes GONE. Resize the outer container so the
		// IME visually shrinks to just the suggestion strip when collapsed, and back to full when
		// expanded.
		if (collapsed) {
			setKeyboardHeight(computeStatusBarHeight());
		} else {
			setKeyboardHeight(getHeight(true));
		}
		// (Removed) the resetToPredictiveOnQwerty call. The QWERTY pipeline owns its own
		// `qwertyInputMode` now; no cross-contamination from T9 cycling means no snap-back
		// needed on transitions.
	}


	@Override
	void showCommandPalette() {
		super.showCommandPalette();
		isCommandPaletteShown = true;
		isTextEditingPaletteShown = false;
		togglePanel(R.id.qwerty_keys_container, false);
	}


	@Override
	void showTextEditingPalette() {
		super.showTextEditingPalette();
		isCommandPaletteShown = false;
		isTextEditingPaletteShown = true;
		togglePanel(R.id.qwerty_keys_container, false);
	}


	@Override
	boolean isCommandPaletteShown() { return isCommandPaletteShown; }

	@Override
	boolean isTextEditingPaletteShown() { return isTextEditingPaletteShown; }


	@Override
	int getHeight(boolean forceRecalculate) {
		if (height <= 0 || forceRecalculate) {
			final int rowHeight = tt9.getResources().getDimensionPixelSize(R.dimen.qwerty_row_height);
			height = computeStatusBarHeight() + rowHeight * ROW_COUNT;
		}
		// When the user has collapsed the keys (typed physically), report just the status bar
		// height. ResizableMainView's fitMain() reads this on every render to pin the outer
		// keyboard_container height — without this override, fitMain would re-expand the
		// container after every mode switch / hotkey, leaving a blank area below.
		return keysExplicitlyCollapsed ? computeStatusBarHeight() : height;
	}


	/** Pixel height of just the suggestion strip / status bar — the keyboard's collapsed size. */
	private int computeStatusBarHeight() {
		final Resources res = tt9.getResources();
		final float textSize = res.getDimension(R.dimen.status_bar_text_size);
		final float statusPadding = Math.max(1, textSize * 0.45f);
		return Math.round((statusPadding + textSize) * tt9.getSettings().getSuggestionFontScale());
	}


	@NonNull
	@Override
	protected ArrayList<SoftKey> getKeys() {
		if (view != null && keys.isEmpty()) {
			ViewGroup root = view.findViewById(R.id.qwerty_keys_container);
			if (root != null) {
				collectSoftKeys(root, keys);
			}
		}
		return keys;
	}


	private static void collectSoftKeys(@NonNull ViewGroup container, @NonNull ArrayList<SoftKey> out) {
		final int childCount = container.getChildCount();
		for (int i = 0; i < childCount; i++) {
			View child = container.getChildAt(i);
			if (child instanceof SoftKey) {
				out.add((SoftKey) child);
			} else if (child instanceof ViewGroup) {
				collectSoftKeys((ViewGroup) child, out);
			}
		}
	}


	@Override
	void render() {
		final boolean isPortrait = !DeviceInfo.isLandscapeOrientation(tt9);

		getView();
		setPadding();
		setWidth(tt9.getSettings().getWidthPercent(isPortrait), tt9.getSettings().getAlignment());
		setBackgroundBlending();
		enableClickHandlers();
		renderKeys(false);
		wireSwipeContainer();
		wireTapToExpand();
	}


	/**
	 * When the keys panel is collapsed (after physical typing), any tap on the visible IME area
	 * (the suggestion strip / status bar) should expand the keys back. Suggestion children
	 * consume their own clicks, so this only fires for taps on empty container area — the
	 * intent is "the user wants the keyboard back."
	 */
	private void wireTapToExpand() {
		if (view == null) return;
		final View kc = view.findViewById(R.id.keyboard_container);
		if (kc == null) return;
		kc.setOnTouchListener((v, ev) -> {
			if (keysExplicitlyCollapsed && ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
				setKeysCollapsed(false);
				return true;
			}
			return false;
		});
	}


	private void wireSwipeContainer() {
		if (view == null) return;
		View container = view.findViewById(R.id.qwerty_keys_container);
		if (!(container instanceof SwipeableKeyboardContainer)) return;
		final SwipeableKeyboardContainer swipeHost = (SwipeableKeyboardContainer) container;
		// Pull the QWERTY-tap-locked prefix at the start of every gesture so glide can match the
		// suffix only and continue a half-typed word.
		swipeHost.setPrefixSupplier(() -> tt9 != null ? tt9.getLockedPrefix() : "");
		// Pull MindReader's next-word predictions per gesture and pass them to the classifier
		// as a context-boost set. Lifts contextually-correct candidates above shape-similar
		// wrong ones (e.g. "happy birthday" → "birthday" beats "boundary").
		swipeHost.setContextSupplier(() -> tt9 != null ? tt9.getGlideContextWords() : java.util.Collections.emptySet());
		// On the first point of every new gesture, commit any pending previous-gesture
		// suggestion (with trailing space). Enables word-after-word swiping without the
		// "hellohello" mid-gesture-confusion bug — see TypingHandler.onGlideGestureStarted.
		swipeHost.setOnGestureStarted(() -> {
			if (tt9 != null) tt9.onGlideGestureStarted();
		});
		// Route the candidate list through tt9's existing suggestion strip instead of force-
		// committing top-1 with an auto-space. The user can scroll/pick alternatives and the
		// commit pipeline (which triggers MindReader + the live-frequency bump) runs as usual.
		swipeHost.setOnGlideSuggestions(words -> {
			if (tt9 != null) tt9.onGlideSuggestions(words);
		});
		// Live mid-gesture candidates: refresh the strip while the finger is still moving so the
		// user can lift off when they like a result. Throttled inside the container.
		swipeHost.setOnGlideMidSuggestions(words -> {
			if (tt9 != null) tt9.onGlideMidSuggestions(words);
		});
		swipeHost.bindLanguage(tt9 != null ? tt9.getLanguage() : null);
	}
}
