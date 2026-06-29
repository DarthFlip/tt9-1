package io.github.sspanak.tt9.ui;

import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.ui.main.keys.BaseClickableKey;
import io.github.sspanak.tt9.util.Logger;
import io.github.sspanak.tt9.util.sys.DeviceInfo;

public final class Vibration {
	@NonNull private final SettingsStore settings;
	@Nullable private final View view;

	public Vibration(@NonNull SettingsStore settings, @Nullable View view) {
		this.settings = settings;
		this.view = view;
	}

	@NonNull public SettingsStore settings() { return settings; }
	@Nullable public View view() { return view; }

	public static int getNoVibration() {
		return -1;
	}

	public static int getPressVibration(BaseClickableKey key) {
		// Pick the lightest soft-keyboard-tuned haptic available. KEYBOARD_PRESS (API 30+)
		// is the tight, short pattern Android specifically tunes for soft-keyboard taps —
		// noticeably less mushy than VIRTUAL_KEY, which was the default for non-number keys
		// and made every QWERTY tap feel laggy on devices with a basic vibrator motor.
		// KEYBOARD_TAP is the API-8.1 fallback (its own constant since 8.1, aliased to
		// VIRTUAL_KEY before that), and VIRTUAL_KEY remains the floor for pre-8.1.
		if (DeviceInfo.AT_LEAST_ANDROID_11) {
			return HapticFeedbackConstants.KEYBOARD_PRESS;
		}
		if (DeviceInfo.AT_LEAST_ANDROID_8_1) {
			return HapticFeedbackConstants.KEYBOARD_TAP;
		}
		return HapticFeedbackConstants.VIRTUAL_KEY;
	}

	public static int getHoldVibration() {
		if (DeviceInfo.AT_LEAST_ANDROID_11) {
			return HapticFeedbackConstants.CONFIRM;
		} else {
			return HapticFeedbackConstants.VIRTUAL_KEY;
		}
	}

	public static int getReleaseVibration() {
		if (DeviceInfo.AT_LEAST_ANDROID_8_1) {
			return HapticFeedbackConstants.KEYBOARD_RELEASE;
		} else {
			return HapticFeedbackConstants.VIRTUAL_KEY;
		}
	}

	public void vibrate(int vibrationType) {
		if (view == null || !settings.getHapticFeedback()) {
			return;
		}

		try {
			view.performHapticFeedback(vibrationType, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
		} catch (Exception e) {
			// Some Xiaomi and Poco devices with Android 16 crash when trying to vibrate.
			// This is a workaround to prevent the app from crashing on such devices.
			settings.setHapticFeedbackProblematic(true);

			Logger.e(getClass().getSimpleName(), "Failed to vibrate, disabling haptic feedback. " + e);
		}
	}

	public void vibrate() {
		vibrate(getPressVibration(null));
	}
}
