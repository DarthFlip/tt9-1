package io.github.sspanak.tt9.ui.main.keys;

import java.util.HashMap;
import java.util.Map;

import io.github.sspanak.tt9.languages.Language;

/**
 * Per-language, per-QWERTY-position alternate characters exposed via long-press popup.
 *
 * Hebrew / Yiddish get the interesting mappings — sofit (final) forms not on their own
 * SI-1452 key, and niqqud (vowel points) for the vowel-carrier letters. English long-press
 * still falls through to the existing uppercase / shifted-digit behavior via
 * SoftKeyQwertyLetter.shiftedAlternate; nothing here overrides that.
 *
 * Alternates are stored per-position as String[] so multi-code-point items (Hebrew base
 * letter + combining niqqud) stay together as one insert unit rather than being split into
 * "base letter" + "diacritic" by a naive char-by-char parse.
 */
public class AlternatesTable {
	private static final Map<String, String[]> HEBREW = new HashMap<>();
	static {
		// ף (final peh) is the one SI-1452 sofit without a dedicated key. Long-press פ (P)
		// to reach it. The other sofits (ם ן ך ץ) already have their own SI-1452 positions
		// AND are auto-inserted at word-boundary by HebrewSofit.applyFinalForm, so we don't
		// duplicate them here.
		HEBREW.put("p", new String[]{"ף"});

		// Vowel niqqud — most meaningful in Yiddish (Hebrew orthography typically omits
		// niqqud in daily writing, but reader-friendly Yiddish uses them). Each entry is a
		// base letter + combining mark bundled as one insert.
		HEBREW.put("t", new String[]{"אַ", "אָ", "אֶ", "אֵ", "אִ", "אֻ"});
		HEBREW.put("u", new String[]{"וֹ", "וּ"});
		HEBREW.put("h", new String[]{"יִ", "יֵ"});
		HEBREW.put("a", new String[]{"שׁ", "שׂ"});
	}

	/**
	 * Returns the alternates array for [positionId] in [language]'s layout, or null if none.
	 * Each element is a complete insert unit; for base+niqqud that means 2 code points
	 * bundled together.
	 */
	public static String[] forKey(Language language, String positionId) {
		if (language == null || positionId == null) return null;
		String code = language.getLocale().getLanguage();
		if ("iw".equals(code) || "he".equals(code) || "ji".equals(code) || "yi".equals(code)) {
			return HEBREW.get(positionId);
		}
		return null;
	}
}
