package io.github.sspanak.tt9.ui.main.keys;

import android.content.Context;
import android.util.AttributeSet;

import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.ime.swipe.KeyOffsetAdapter;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.ui.Vibration;

public class SoftKeyQwertyLetter extends SoftKeyText {
	/**
	 * The English glyph from android:text — kept as a stable "position identifier" that the
	 * QwertyLayoutTable uses to look up the language-specific glyph. When no override exists
	 * (English mode), this doubles as the character emitted and displayed.
	 */
	private String positionId = "";

	public SoftKeyQwertyLetter(Context context) { super(context); }
	public SoftKeyQwertyLetter(Context context, AttributeSet attrs) { super(context, attrs); init(); }
	public SoftKeyQwertyLetter(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

	private void init() {
		CharSequence text = getText();
		positionId = text == null ? "" : text.toString();
	}

	/**
	 * Resolve position → glyph for the currently-active language. Hebrew/Yiddish return
	 * SI-1452 letters; English returns the position ID itself.
	 */
	private String resolveChar() {
		if (tt9 == null) return positionId;
		final String override = QwertyLayoutTable.override(tt9.getLanguage(), positionId);
		return override != null ? override : positionId;
	}

	@Override
	protected String getKeyChar() {
		return resolveChar();
	}

	@Override
	protected String getTitle() {
		final String ch = resolveChar();
		if (tt9 == null) return ch;
		int c = tt9.getTextCase();
		if (c == InputMode.CASE_UPPER || c == InputMode.CASE_CAPITALIZE) {
			// Hebrew's LanguageDefinition sets hasUpperCase=no, so getTextCase never returns
			// UPPER/CAPITALIZE for Hebrew — this branch is a no-op there. For Latin languages
			// it does the standard uppercase render.
			return ch.toUpperCase();
		}
		return ch;
	}

	@Override
	public boolean isDynamic() {
		return true;
	}

	@Override
	protected boolean handleRelease() {
		return tt9 != null && tt9.onQwertyLetter(resolveChar());
	}

	/**
	 * Long-press emits the "shifted" alternate for this key:
	 *   - letters → uppercase variant (Gboard capital-on-hold)
	 *   - digits → US QWERTY shift-row symbol (1→!, 2→@, …)
	 *   - comma  → toggles voice input (Gboard convention; same affordance as Gboard's
	 *               long-press-comma). Keyed on POSITION so it still fires in Hebrew mode
	 *               where the comma key renders as ת.
	 *   - other  → no alternate (the key emits nothing meaningful on hold; for Hebrew this
	 *               yields the same letter since toUpperCase is a no-op).
	 * Goes through onText for character emission so any in-progress composing word commits
	 * first. preventRepeat() stops auto-repeat AND suppresses the upcoming ACTION_UP
	 * handleRelease so we don't double-emit the base character.
	 */
	@Override
	protected void handleHold() {
		preventRepeat();
		if (tt9 == null || positionId.isEmpty()) return;
		if (",".equals(positionId)) {
			tt9.toggleVoiceInput();
			vibrate(Vibration.getHoldVibration());
			return;
		}
		// Alternates popup takes priority over the shifted-alternate fallback. Currently only
		// Hebrew / Yiddish position keys have entries (sofit ף on פ, niqqud on א ו י ש); every
		// other key falls through to the pre-existing uppercase-on-hold behavior.
		final String[] alternates = AlternatesTable.forKey(tt9.getLanguage(), positionId);
		if (alternates != null && alternates.length > 0) {
			vibrate(Vibration.getHoldVibration());
			new AlternatesPopup(getContext()).show(this, alternates, chosen -> {
				if (tt9 != null) tt9.onText(chosen, false);
			});
			return;
		}
		final String shifted = shiftedAlternate(resolveChar());
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
		if (tt9 == null) return;
		final Language lang = tt9.getLanguage();
		if (lang == null) return;
		final String ch = resolveChar();
		if (ch.isEmpty()) return;
		// Offset = touch position relative to the key's visual center. Width/height ARE the key's
		// dimensions because this view IS the key — no parent-coord conversion needed. Offsets
		// are keyed on the RESOLVED char (Hebrew ק, English q, …) so per-language calibration
		// stays separate.
		final float dx = downXLocal - getWidth() / 2f;
		final float dy = downYLocal - getHeight() / 2f;
		KeyOffsetAdapter.INSTANCE.record(getContext(), lang.getId(), ch.charAt(0), dx, dy);
	}
}
