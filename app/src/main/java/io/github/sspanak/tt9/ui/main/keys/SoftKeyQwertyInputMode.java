package io.github.sspanak.tt9.ui.main.keys;

import android.content.Context;
import android.util.AttributeSet;

/**
 * Input-mode cycler for the QWERTY layout. Same release/hold behaviour as {@link SoftKeyLF4}
 * (tap cycles abc/123/predictive; hold switches language), but the label shows the NEXT mode
 * that will become active on tap, and the globe corner hint is suppressed because the globe
 * now lives on the space bar.
 */
public class SoftKeyQwertyInputMode extends SoftKeyLF4 {
	public SoftKeyQwertyInputMode(Context context) { super(context); }
	public SoftKeyQwertyInputMode(Context context, AttributeSet attrs) { super(context, attrs); }
	public SoftKeyQwertyInputMode(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

	@Override
	protected String getTitle() {
		return tt9 != null ? tt9.getNextInputModeName() : super.getTitle();
	}

	@Override
	protected int getCornerIcon(int position) {
		return -1;
	}
}
