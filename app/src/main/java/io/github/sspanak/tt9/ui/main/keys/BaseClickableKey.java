package io.github.sspanak.tt9.ui.main.keys;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import io.github.sspanak.tt9.ime.TraditionalT9;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.ui.Vibration;
import io.github.sspanak.tt9.util.Logger;

public class BaseClickableKey extends com.google.android.material.button.MaterialButton implements View.OnTouchListener, View.OnLongClickListener {
	private final String LOG_TAG = getClass().getSimpleName();

	protected TraditionalT9 tt9;
	protected Vibration vibration;

	private boolean hold = false;
	private boolean repeat = false;
	private long lastLongClickTime = 0;
	private final Handler repeatHandler = new Handler(Looper.getMainLooper());

	private static int lastPressedKey = -1;
	private boolean ignoreLastPressedKey = false;

	// Tap-vs-swipe disambiguation. Recorded on ACTION_DOWN; on ACTION_UP if the finger moved
	// more than tapSlopPx away, we treat the touch as a swipe passing through this key
	// (not a tap intended for it) and suppress the release-emit. Without this, dragging a finger
	// across the QWERTY row commits every letter the finger passes over.
	private float downX = 0f;
	private float downY = 0f;
	private int tapSlopPx = 0;


	public BaseClickableKey(Context context) {
		super(context);
		setHapticFeedbackEnabled(false);
		setOnTouchListener(this);
		setOnLongClickListener(this);
	}


	public BaseClickableKey(Context context, AttributeSet attrs) {
		super(context, attrs);
		setHapticFeedbackEnabled(false);
		setOnTouchListener(this);
		setOnLongClickListener(this);
	}


	public BaseClickableKey(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		setHapticFeedbackEnabled(false);
		setOnTouchListener(this);
		setOnLongClickListener(this);
	}


	public void setTT9(TraditionalT9 tt9) {
		this.tt9 = tt9;
	}


	protected boolean validateTT9Handler() {
		if (tt9 == null) {
			Logger.w(LOG_TAG, "Traditional T9 handler is not set. Ignoring key press.");
			return false;
		}

		return true;
	}


	@Override
	public boolean onTouch(View view, MotionEvent event) {
		super.onTouchEvent(event);

		int action = (event.getAction() & MotionEvent.ACTION_MASK);

		if (action == MotionEvent.ACTION_DOWN) {
			downX = event.getX();
			downY = event.getY();
			if (tapSlopPx <= 0) {
				// 1.5× standard touch slop — generous enough to absorb finger wobble on a real
				// tap, tight enough to recognise a swipe through this key as not-a-tap. Pulled
				// once and cached; ViewConfiguration access isn't free.
				tapSlopPx = (int) (ViewConfiguration.get(getContext()).getScaledTouchSlop() * 1.5f);
			}
			return handlePress();
		} else if (action == MotionEvent.ACTION_UP) {
			if (!repeat || hold) {
				hold = false;
				repeat = false;
				// If the finger moved past tap-slop between DOWN and UP, the touch was a swipe
				// passing through this key — not a tap intended for it. Don't emit. This is the
				// load-bearing fix for glide-typing committing every key the finger crosses.
				final float dx = event.getX() - downX;
				final float dy = event.getY() - downY;
				final float distSq = dx * dx + dy * dy;
				if (isTapSlopActive() && distSq > tapSlopPx * tapSlopPx) {
					// Touch was a swipe through this key, not a tap on it. Don't emit.
					return false;
				}
				boolean result = handleRelease();
				if (result) {
					// Feed the per-key touch-offset adapter — only on confirmed taps, not on
					// suppressed swipes. Subclasses override to supply their keyChar; default
					// no-op for non-letter keys.
					recordTouchOffset(downX, downY);
				}
				lastPressedKey = ignoreLastPressedKey ? -1 : getId();
				return result;
			}
			repeat = false;
		}
		return false;
	}


	@Override
	public boolean onLongClick(View view) {
		// sometimes this gets called twice, so we debounce the call to the repeating function
		final long now = System.currentTimeMillis();
		if (now - lastLongClickTime < SettingsStore.SOFT_KEY_DOUBLE_CLICK_DELAY) {
			return false;
		}

		hold = true;
		lastLongClickTime = now;
		repeatOnLongPress();
		return true;
	}


	/**
	 * repeatOnLongPress
	 * Repeatedly calls "handleHold()" upon holding the respective SoftKey, to simulate physical keyboard behavior.
	 */
	private void repeatOnLongPress() {
		if (hold) {
			repeat = true;
			handleHold();
			lastPressedKey = ignoreLastPressedKey ? -1 : getId();
			repeatHandler.removeCallbacks(this::repeatOnLongPress);
			repeatHandler.postDelayed(this::repeatOnLongPress, SettingsStore.SOFT_KEY_REPEAT_DELAY);
		}
	}


	/**
	 * preventRepeat
	 * Prevents "handleHold()" from being called repeatedly when the SoftKey is being held.
	 */
	protected void preventRepeat() {
		hold = false;
		repeatHandler.removeCallbacks(this::repeatOnLongPress);
	}


	protected static int getLastPressedKey() {
		return lastPressedKey;
	}


	protected void ignoreLastPressedKey() {
		ignoreLastPressedKey = true;
	}


	protected boolean handlePress() {
		if (validateTT9Handler() && getVisibility() == VISIBLE) {
			vibrate(Vibration.getPressVibration(this));
			// Subtle audio click via the platform's built-in keyboard-tap sound effect. Default
			// off — toggled in Settings → UI. Uses View.playSoundEffect so we don't bundle audio
			// assets; the OS routes through the user's notification/media volume per their
			// system preferences. Safe to call even if disabled (gated on the setting).
			if (tt9 != null && tt9.getSettings() != null && tt9.getSettings().getSoundFeedback()) {
				playSoundEffect(android.view.SoundEffectConstants.CLICK);
			}
		}

		return false;
	}


	protected void handleHold() {}


	protected boolean handleRelease() {
		return false;
	}


	/**
	 * Hook called after an ACCEPTED tap release (tap was within slop, key emitted). Override in
	 * letter-key subclasses to feed the per-key touch-offset adapter. [downXLocal]/[downYLocal]
	 * are in this view's local coords; key center is (getWidth()/2, getHeight()/2). Default no-op
	 * for non-letter keys (backspace, shift, space, etc. — their position doesn't map to a single
	 * letter so per-key learning doesn't apply).
	 */
	protected void recordTouchOffset(float downXLocal, float downYLocal) {}

	/**
	 * Whether the tap-slop guard applies to this key. The guard exists to prevent QWERTY
	 * letter keys from emitting every time a glide gesture crosses them — for a key that
	 * isn't part of any gesture (e.g. the symbols-toggle), finger drift during a deliberate
	 * tap shouldn't suppress the release. Subclasses that are tap-only return false.
	 */
	protected boolean isTapSlopActive() { return true; }


	public boolean isHoldEnabled() {
		return true;
	}


	protected void vibrate(int vibrationType) {
		if (tt9 != null) {
			vibration = vibration == null ? new Vibration(tt9.getSettings(), this) : vibration;
			vibration.vibrate(vibrationType);
		}
	}
}
