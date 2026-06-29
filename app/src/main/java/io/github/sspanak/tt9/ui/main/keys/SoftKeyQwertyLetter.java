package io.github.sspanak.tt9.ui.main.keys;

import android.content.Context;
import android.util.AttributeSet;

import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.ime.swipe.KeyOffsetAdapter;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.ui.Vibration;

public class SoftKeyQwertyLetter extends SoftKeyText {
	private String keyChar = "";

	public SoftKeyQwertyLetter(Context context) { super(context); }
	public SoftKeyQwertyLetter(Context context, AttributeSet attrs) { super(context, attrs); init(); }
	public SoftKeyQwertyLetter(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

	private void init() {
		CharSequence text = getText();
		keyChar = text == null ? "" : text.toString();
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
