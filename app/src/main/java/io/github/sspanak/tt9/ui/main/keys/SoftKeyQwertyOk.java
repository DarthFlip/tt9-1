package io.github.sspanak.tt9.ui.main.keys;

import android.content.Context;
import android.util.AttributeSet;

/**
 * Enter/commit key for the QWERTY layout. Inherits the full behaviour of {@link SoftKeyOk} but
 * shows an enter arrow drawable (set via XML as {@code android:drawableLeft} on the button)
 * instead of the literal "OK" text.
 */
public class SoftKeyQwertyOk extends SoftKeyOk {
	public SoftKeyQwertyOk(Context context) { super(context); }
	public SoftKeyQwertyOk(Context context, AttributeSet attrs) { super(context, attrs); }
	public SoftKeyQwertyOk(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

	@Override
	protected String getTitle() {
		return "";
	}
}
