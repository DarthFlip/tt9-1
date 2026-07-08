package io.github.sspanak.tt9.ui.main.keys;

import android.content.Context;
import android.os.Handler;
import android.util.AttributeSet;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.commands.CmdBackspace;
import io.github.sspanak.tt9.languages.LanguageKind;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;

public class SoftKeyBackspace extends BaseSwipeableKey {
	private int repeat = 0;

	private boolean isActionPerformed = false;
	private final Handler waitForSwipe = new Handler();


	public SoftKeyBackspace(Context context) {
		super(context);
		isSwipeable = true;
	}
	public SoftKeyBackspace(Context context, AttributeSet attrs) {
		super(context, attrs);
		isSwipeable = true;
	}
	public SoftKeyBackspace(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		isSwipeable = true;
	}


	private boolean isFastDeleteOn() {
		return tt9 != null && tt9.getSettings().getBackspaceAcceleration();
	}


	/**
	 * When fast-delete is on, decrease the hold duration threshold for smoother operation.
	 */
	@Override
	protected float getHoldDurationThreshold() {
		return isFastDeleteOn() ? SettingsStore.SOFT_KEY_REPEAT_DELAY * 3 : super.getHoldDurationThreshold();
	}


	/**
	 * When fast-delete is on, prevent swapping that does nothing. It may feel frustrating if the user
	 * moves their finger slightly and the key does not delete anything.
	 */
	@Override
	protected float getSwipeXThreshold() {
		return isFastDeleteOn() ? super.getSwipeXThreshold() : Integer.MAX_VALUE;
	}


	/**
	 * Disable vertical swiping for backspace key.
	 */
	@Override
	protected float getSwipeYThreshold() {
		return Integer.MAX_VALUE;
	}


	@Override
	final protected boolean handlePress() {
		super.handlePress();
		isActionPerformed = false;
		waitForSwipe.postDelayed(this::handlePressDebounced, 1 + getAverageSwipeProcessingTime());
		return true;
	}


	/**
	 * Avoids deleting text twice when swiping - first, when the user touches the screen, and then,
	 * when they finish the swipe gesture.
	 */
	private void handlePressDebounced() {
		if (!isActionPerformed) {
			isActionPerformed = true;
			CmdBackspace.deleteText(tt9, repeat);
		}
	}


	@Override
	protected void handleStartSwipeX(float position, float delta) {
		if (!isActionPerformed) {
			isActionPerformed = true;
			CmdBackspace.deleteWord(tt9);
		}
	}


	@Override
	protected void handleSwipeX(float position, float delta) {
		handleStartSwipeX(position, delta);
	}


	@Override
	protected void handleEndSwipeX(float position, float delta) {
		if (isActionPerformed) {
			return;
		}

		isActionPerformed = true;

		if (delta < SWIPE_X_THRESHOLD) {
			CmdBackspace.deleteText(tt9, repeat);
		} else {
			CmdBackspace.deleteWord(tt9);
		}
	}


	@Override
	final protected void handleHold() {
		isActionPerformed = true;
		repeat++;
		// After ~12 repeat ticks (≈600 ms of holding at the default 50 ms repeat delay), switch
		// from single-character deletion to whole-word deletion. Standard Gboard behavior — the
		// first hundreds of ms of hold delete one char at a time so the user can correct a single
		// typo without overshooting; past that, the user is clearly clearing a longer mistake and
		// word-at-a-time is dramatically faster. The existing sideways-swipe-on-backspace
		// gesture (handleSwipeX above) also deletes whole words; this is the long-press equivalent.
		if (repeat >= WORD_DELETE_REPEAT_THRESHOLD) {
			CmdBackspace.deleteWord(tt9);
		} else {
			CmdBackspace.deleteText(tt9, repeat);
		}
	}

	/** Repeat-tick count past which a hold transitions from char-delete to word-delete. */
	private static final int WORD_DELETE_REPEAT_THRESHOLD = 12;


	@Override
	final protected boolean handleRelease() {
		// No release-vibrate here — BaseClickableKey.handlePress already fires a press haptic on
		// ACTION_DOWN, and firing a second haptic when the user lifts their finger after a hold
		// (repeat > 0) felt "messed up": the motor sat idle through the whole hold-delete stream
		// and then buzzed after they'd already stopped. Consistent with letter keys — one haptic
		// per input event, at press.
		repeat = 0;

		return true;
	}


	@Override
	protected float getTitleScale() {
		float scale = 1.1f;

		if (tt9 != null && tt9.getSettings().isMainLayoutLarge()) {
			scale *= super.getTitleScale();
		}

		return scale;
	}


	@Override
	protected int getNoEmojiTitle() {
		return R.string.virtual_key_del;
	}


	@Override
	protected String getTitle() {
		return new CmdBackspace().getIconTxt(
			LanguageKind.isRTL(tt9 != null ? tt9.getLanguage() : null)
		);
	}


	@Override
	public void render() {
		if (tt9 != null) {
			setEnabled(!tt9.isVoiceInputActive());
		}
		super.render();
	}
}
