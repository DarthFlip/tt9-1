package io.github.sspanak.tt9.preferences.settings;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.ime.modes.InputModeKind;
import io.github.sspanak.tt9.preferences.screens.keypad.SwitchUpsideDownKeys;
import io.github.sspanak.tt9.preferences.screens.modeAbc.DropDownAbcAutoAcceptTime;
import io.github.sspanak.tt9.preferences.screens.modePredictive.DropDownOneKeyEmoji;
import io.github.sspanak.tt9.preferences.screens.modePredictive.DropDownPredictiveAutoAcceptTime;
import io.github.sspanak.tt9.preferences.screens.modePredictive.DropDownZeroKeyCharacter;
import io.github.sspanak.tt9.preferences.screens.modePredictive.OneKeyEmojiOptions;

class SettingsTyping extends SettingsPunctuation {
	SettingsTyping(Context context) { super(context); }

	public int getAutoAcceptTimeoutAbc() {
		int time = getStringifiedInt(DropDownAbcAutoAcceptTime.NAME, DropDownAbcAutoAcceptTime.DEFAULT);
		return time > 0 ? time + getKeyPadDebounceTime() : time;
	}
	public boolean getAutoSpaceAbc() {
		return prefs.getBoolean("auto_space_abc_v2", true);
	}
	public boolean getAutoTextCaseAbc() {
		return prefs.getBoolean("auto_text_case_abc_v2", true);
	}

	public int getAutoAcceptTimeoutPredictive() {
		int time = getStringifiedInt(DropDownPredictiveAutoAcceptTime.NAME, DropDownPredictiveAutoAcceptTime.DEFAULT);
		return time > 0 ? time + getKeyPadDebounceTime() : time;
	}
	public boolean getAutoSpacePredictive() { return prefs.getBoolean("auto_space_predictive", true); }
	public boolean getAutoTextCasePredictive() { return prefs.getBoolean("auto_text_case_predictive", true); }
	public boolean getAutoCapitalsAfterNewline() {
		// Default ON. Standard messaging-app expectation: capital letter at the start of a new
		// line, just like at the start of a sentence. Tied to auto-text-case-predictive being on
		// (which it already is by default).
		return getAutoTextCasePredictive() && prefs.getBoolean("auto_capitals_after_newline", true);
	}

	public boolean getAutoMindReading() {
		// Default ON. MindReader supplies next-word predictions based on context — without it,
		// suggestions ignore what the user typed before. No reason for an average user to want
		// this off; power users can disable in settings if they don't like the autocompletion feel.
		return prefs.getBoolean("auto_mind_reading", true);
	}

	public boolean getAutoTrimTrailingSpace() {
		return prefs.getBoolean("auto_trim_trailing_space", true);
	}

	public boolean isAutoTextCaseOn(@Nullable InputMode mode) {
		return
			(InputModeKind.isPredictive(mode) && getAutoTextCasePredictive()) ||
			(InputModeKind.isABC(mode) && getAutoTextCaseAbc());
	}

	public boolean isAutoAssistanceOn(@Nullable InputMode mode) {
		return
			(getAutoMindReading() && (InputModeKind.isPredictive(mode) || InputModeKind.isABC(mode))) ||
			(InputModeKind.isPredictive(mode) && (getAutoSpacePredictive() || getAutoTextCasePredictive() || getPredictWordPairs())) ||
			(InputModeKind.isABC(mode) && (getAutoSpaceAbc() || getAutoTextCaseAbc()));
	}

	public boolean getBackspaceAcceleration() {
		// Default ON. Holding backspace deletes word-at-a-time once the user holds long enough,
		// vs character-at-a-time. Faster cleanup after a wrong-word commit, which on this IME
		// happens more than on Gboard because swipe accuracy is ~75-85% (vs 95%+).
		return prefs.getBoolean("backspace_acceleration", true);
	}

	public boolean getBackspaceRecomposing() {
		return prefs.getBoolean("backspace_recomposing", true);
	}

	@NonNull
	public String getDoubleZeroChar() {
		String character = prefs.getString(DropDownZeroKeyCharacter.NAME, DropDownZeroKeyCharacter.DEFAULT);

		// SharedPreferences return a corrupted string when using the real "\n"... :(
		return  character.equals("\\n") ? "\n" : character;
	}

	public boolean areEmojisEnabled() {
		return getOneKeyEmojiMode() != OneKeyEmojiOptions.OPTIONS.NONE;
	}

	public OneKeyEmojiOptions.OPTIONS getOneKeyEmojiMode() {
		try {
			return OneKeyEmojiOptions.OPTIONS.valueOf(prefs.getString(DropDownOneKeyEmoji.NAME, OneKeyEmojiOptions.DEFAULT));
		} catch (IllegalArgumentException e) {
			return OneKeyEmojiOptions.OPTIONS.valueOf(OneKeyEmojiOptions.DEFAULT);
		}
	}

	public boolean getPredictiveMode() {
		return prefs.getBoolean("pref_predictive_mode", true);
	}

	public boolean getPredictWordPairs() {
		return prefs.getBoolean("pref_predict_word_pairs", true);
	}

	public boolean getShowSuggestions() {
		// On the QWERTY on-screen layout, suggestions are ALWAYS on — the QWERTY pipeline
		// uses its own qwertyInputMode which is always Predictive, independent of whatever
		// `getInputMode()` (mInputMode / T9-side) is currently cycled to. Without this gate,
		// pressing # to cycle T9 into ABC would silently hide the QWERTY strip. We check the
		// layout preference directly (inlined here because isMainLayoutQwerty lives on the
		// SettingsUI subclass, not accessible from this base).
		final int currentLayout = getStringifiedInt(
			io.github.sspanak.tt9.preferences.screens.appearance.DropDownLayoutType.NAME,
			-1
		);
		if (currentLayout == SettingsUI.LAYOUT_QWERTY) {
			return true;
		}
		final int inputMode = getInputMode();
		final boolean showInAbc = prefs.getBoolean("show_suggestions_abc", false);

		return inputMode != InputMode.MODE_ABC || showInAbc;
	}

	public boolean getWordEditing() {
		return prefs.getBoolean("word_editing", false);
	}

	public boolean getUpsideDownKeys() { return prefs.getBoolean(SwitchUpsideDownKeys.NAME, SwitchUpsideDownKeys.DEFAULT); }
}
