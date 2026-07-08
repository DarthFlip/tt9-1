package io.github.sspanak.tt9.ui.main.keys;

import android.content.Context;
import android.util.AttributeSet;

import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.ime.swipe.KeyOffsetAdapter;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.ui.Vibration;

public class SoftKeyQwertyLetter extends SoftKeyText {
	private String keyChar = "";

	public SoftKeyQwertyLetter(Context context) { super(context); isSwipeable = true; }
	public SoftKeyQwertyLetter(Context context, AttributeSet attrs) { super(context, attrs); isSwipeable = true; init(); }
	public SoftKeyQwertyLetter(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); isSwipeable = true; init(); }

	private void init() {
		CharSequence text = getText();
		keyChar = text == null ? "" : text.toString();
	}


	/**
	 * Disable swipe-X detection. We only turn on {@code isSwipeable} so
	 * {@link #getHoldDurationThreshold()} takes effect (BaseSwipeableKey uses its own
	 * long-press timer only in the swipeable path). We don't actually want swipe gestures
	 * to engage on letter keys — small horizontal drift during a deliberate tap should
	 * NOT cancel our custom 300ms long-press timer. Glide typing already runs at the
	 * container level and doesn't need per-key swipe support.
	 */
	@Override
	protected float getSwipeXThreshold() {
		return Integer.MAX_VALUE;
	}


	/**
	 * Disable swipe-Y detection. Same reasoning as {@link #getSwipeXThreshold()} —
	 * vertical drift shouldn't cancel long-press.
	 */
	@Override
	protected float getSwipeYThreshold() {
		return Integer.MAX_VALUE;
	}

	@Override
	protected String getKeyChar() {
		return keyChar;
	}

	@Override
	protected String getTitle() {
		if (tt9 == null) return keyChar;
		int c = tt9.getTextCase();
		if (c == InputMode.CASE_UPPER || c == InputMode.CASE_CAPITALIZE) {
			return keyChar.toUpperCase();
		}
		return keyChar;
	}

	@Override
	public boolean isDynamic() {
		return true;
	}

	@Override
	protected boolean handleRelease() {
		return tt9 != null && tt9.onQwertyLetter(keyChar);
	}

	/**
	 * Long-press emits the "shifted" alternate for this key:
	 *   - letters → uppercase variant (Gboard capital-on-hold)
	 *   - digits → US QWERTY shift-row symbol (1→!, 2→@, …)
	 *   - comma  → toggles voice input (Gboard convention; same affordance as Gboard's
	 *               long-press-comma)
	 *   - other  → no alternate (the key emits nothing on hold)
	 * Goes through onText for character emission so any in-progress composing word commits
	 * first. preventRepeat() stops auto-repeat AND suppresses the upcoming ACTION_UP
	 * handleRelease so we don't double-emit the base character.
	 */
	@Override
	protected void handleHold() {
		preventRepeat();
		if (tt9 == null || keyChar.isEmpty()) return;
		// Comma → voice input. Convention from Gboard. We hand off to the existing voice
		// infrastructure in TraditionalT9 (via VoiceHandler); no fallback emit because
		// firing voice and ALSO typing a comma would be surprising.
		if (",".equals(keyChar)) {
			tt9.toggleVoiceInput();
			vibrate(Vibration.getHoldVibration());
			return;
		}
		final String shifted = shiftedAlternate(keyChar);
		tt9.onText(shifted, false);
		vibrate(Vibration.getHoldVibration());
	}

	/**
	 * Returns the shifted-row alternate for [keyChar]: letters → uppercase, digits → US QWERTY
	 * shift symbol, punctuation/symbols → unchanged (no alternate). Single-char keyChar only.
	 */
	private static String shiftedAlternate(String keyChar) {
		if (keyChar == null || keyChar.length() != 1) {
			return keyChar == null ? "" : keyChar.toUpperCase();
		}
		switch (keyChar.charAt(0)) {
			case '1': return "!";
			case '2': return "@";
			case '3': return "#";
			case '4': return "$";
			case '5': return "%";
			case '6': return "^";
			case '7': return "&";
			case '8': return "*";
			case '9': return "(";
			case '0': return ")";
			default:  return keyChar.toUpperCase();
		}
	}

	@Override
	public boolean isHoldEnabled() {
		return true;
	}


	/**
	 * Long-press threshold on QWERTY letter keys — 300 ms, matching Gboard. Android's default
	 * {@code ViewConfiguration.getLongPressTimeout()} is 500 ms, which was measurably sluggish
	 * for the letter-hold-for-uppercase / digit-hold-for-shift-symbol affordances users engage
	 * many times per typing session. Other keys (backspace has its own fast-delete tuning,
	 * space long-press is cursor-drag, ?123/ABC is instant) keep their existing thresholds.
	 */
	@Override
	protected float getHoldDurationThreshold() {
		return 300f;
	}


	/** Enable the dwell-gated release-vibrate — a deliberate press-and-lift on a letter
	 *  key gets a second confirming haptic click. Quick taps stay single-buzz. */
	@Override
	protected boolean isReleaseVibrateEnabled() {
		return true;
	}


	@Override
	protected void recordTouchOffset(float downXLocal, float downYLocal) {
		if (tt9 == null || keyChar.isEmpty()) return;
		final Language lang = tt9.getLanguage();
		if (lang == null) return;
		// Offset = touch position relative to the key's visual center. Width/height ARE the key's
		// dimensions because this view IS the key — no parent-coord conversion needed.
		final float dx = downXLocal - getWidth() / 2f;
		final float dy = downYLocal - getHeight() / 2f;
		KeyOffsetAdapter.INSTANCE.record(getContext(), lang.getId(), keyChar.charAt(0), dx, dy);
	}
}
