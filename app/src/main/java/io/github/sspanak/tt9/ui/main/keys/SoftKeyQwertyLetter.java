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
	 * Long-press emits the uppercase variant of this letter as a literal one-shot — Gboard-style
	 * capital. Goes through onText so any in-progress composing word commits first and the cap
	 * lands as standalone text. After the hold, preventRepeat() stops auto-repeat AND suppresses
	 * the upcoming ACTION_UP handleRelease so we don't double-emit the lowercase letter.
	 */
	@Override
	protected void handleHold() {
		preventRepeat();
		if (tt9 == null || keyChar.isEmpty()) return;
		tt9.onText(keyChar.toUpperCase(), false);
		vibrate(Vibration.getHoldVibration());
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
