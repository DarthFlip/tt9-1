package io.github.sspanak.tt9.ui.main.keys;

import android.content.Context;
import android.util.AttributeSet;

import io.github.sspanak.tt9.commands.CmdNextLanguage;
import io.github.sspanak.tt9.ui.Vibration;

/**
 * Space bar variant that commits a space on tap and cycles through enabled languages on long-press,
 * matching the convention used by stock Android keyboards. Shows a globe hint in the top-right
 * corner when multiple languages are enabled.
 */
public class SoftKeyQwertySpace extends SoftKeyQwertyLetter {
	public SoftKeyQwertySpace(Context context) { super(context); }
	public SoftKeyQwertySpace(Context context, AttributeSet attrs) { super(context, attrs); }
	public SoftKeyQwertySpace(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

	@Override
	protected void handleHold() {
		preventRepeat();
		if (tt9 != null && new CmdNextLanguage().run(tt9)) {
			vibrate(Vibration.getHoldVibration());
		}
	}

	@Override
	public boolean isHoldEnabled() {
		return tt9 != null && tt9.getSettings().areEnabledLanguagesMoreThanN(1);
	}

	@Override
	protected int getCornerIcon(int position) {
		if (position == ICON_POSITION_TOP_RIGHT && isHoldEnabled()) {
			return new CmdNextLanguage().getIcon();
		}
		return -1;
	}
}
