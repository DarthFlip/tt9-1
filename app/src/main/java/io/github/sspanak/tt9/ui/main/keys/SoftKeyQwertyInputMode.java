package io.github.sspanak.tt9.ui.main.keys;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.ui.Vibration;

/**
 * Bottom-left key on the QWERTY layout. Toggles between the letter panel and the symbols
 * panel (Gboard-style ?123 / ABC). Hold-to-cycle-language inherited from SoftKeyLF4 still works.
 *
 * Visibility toggle: walks up to the SwipeableKeyboardContainer parent, finds
 * qwerty_letter_panel + qwerty_symbols_panel siblings, flips which is GONE / VISIBLE.
 * Label updates to "?123" when letters are showing, "ABC" when symbols are showing.
 */
public class SoftKeyQwertyInputMode extends SoftKeyLF4 {
	public SoftKeyQwertyInputMode(Context context) { super(context); disableSwipes(); }
	public SoftKeyQwertyInputMode(Context context, AttributeSet attrs) { super(context, attrs); disableSwipes(); }
	public SoftKeyQwertyInputMode(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); disableSwipes(); }

	private void disableSwipes() {
		// SoftKeyLF4 (our parent) treats horizontal swipes as "next IME" (switches to Gboard
		// and orphans the user) and vertical swipes as "open IME picker". Both surprise the
		// user when they're trying to tap the symbols-toggle and their finger drifts a few
		// pixels. Disable swipe handling entirely — tap is the only intent for this key.
		isSwipeable = false;
	}

	@Override
	protected void handleEndSwipeX(float position, float delta) { /* no-op — see disableSwipes() */ }

	@Override
	protected void handleEndSwipeY(float position, float delta) { /* no-op — see disableSwipes() */ }

	@Override
	protected boolean isTapSlopActive() {
		// This key isn't part of any glide gesture. The slop guard's purpose (preventing
		// letter emission during swipes) doesn't apply, and normal finger drift was
		// suppressing legitimate taps.
		return false;
	}

	@Override
	@NonNull
	protected String getTitle() {
		return isSymbolsPanelVisible() ? "ABC" : "?123";
	}

	// getCornerIcon: inherit the parent's behavior — show the next-language icon at top-right
	// when multiple languages are enabled. Hold-to-cycle still works on this key, and without
	// the icon multi-language users had no visual cue that the affordance existed.

	@Override
	protected boolean handleRelease() {
		if (!notSwiped()) return false;
		if (!toggleSymbolsPanel()) {
			// Couldn't find the panels (shouldn't happen on the QWERTY layout) —
			// fall through to the upstream input-mode cycle so the key isn't dead.
			return super.handleRelease();
		}
		vibrate(Vibration.getReleaseVibration());
		// Force this key to re-render so the label flips between "?123" and "ABC".
		render();
		return true;
	}

	/**
	 * Walk up the view tree to the SwipeableKeyboardContainer, find the letter and symbols
	 * panels by id, and flip which is visible. Both panels live in a FrameLayout wrapper so
	 * we use INVISIBLE rather than GONE on the hidden one — this keeps both panels measured
	 * and laid out, so the toggle is just a draw-bit flip with no measure/layout pass. Touch
	 * events still go to the visible panel only (Android skips INVISIBLE for dispatch).
	 * Returns true if both panels were found and toggled; false otherwise.
	 */
	private boolean toggleSymbolsPanel() {
		ViewGroup container = findKeysContainer();
		if (container == null) return false;
		View letterPanel = container.findViewById(R.id.qwerty_letter_panel);
		View symbolsPanel = container.findViewById(R.id.qwerty_symbols_panel);
		if (letterPanel == null || symbolsPanel == null) return false;
		boolean showSymbols = symbolsPanel.getVisibility() != View.VISIBLE;
		symbolsPanel.setVisibility(showSymbols ? View.VISIBLE : View.INVISIBLE);
		letterPanel.setVisibility(showSymbols ? View.INVISIBLE : View.VISIBLE);
		return true;
	}

	private boolean isSymbolsPanelVisible() {
		ViewGroup container = findKeysContainer();
		if (container == null) return false;
		View symbolsPanel = container.findViewById(R.id.qwerty_symbols_panel);
		return symbolsPanel != null && symbolsPanel.getVisibility() == View.VISIBLE;
	}

	private ViewGroup findKeysContainer() {
		View v = this;
		while (v != null) {
			if (v.getId() == R.id.qwerty_keys_container && v instanceof ViewGroup) {
				return (ViewGroup) v;
			}
			if (v.getParent() instanceof View) {
				v = (View) v.getParent();
			} else {
				return null;
			}
		}
		return null;
	}
}
