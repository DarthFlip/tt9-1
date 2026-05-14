package io.github.sspanak.tt9.ui.main.keys;

import android.content.Context;
import android.util.AttributeSet;

import io.github.sspanak.tt9.ime.modes.InputMode;

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
}
