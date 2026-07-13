package io.github.sspanak.tt9.ui.main.keys;

import java.util.HashMap;
import java.util.Map;

import io.github.sspanak.tt9.languages.Language;

/**
 * Per-language QWERTY key overrides for the on-screen keyboard.
 *
 * Each letter/punctuation key in main_qwerty.xml is inflated with a hardcoded English
 * character via android:text — that character doubles as the key's "position ID". When the
 * active language ships a different glyph at that position (Hebrew SI-1452, etc.), override()
 * returns the language-specific character; otherwise it returns null and the caller falls
 * through to the position ID (i.e. English default).
 *
 * The layout is a UI/positional mapping, not a linguistic property, so it lives here rather
 * than in LanguageDefinition / definitions.yml — those describe T9 digit-key groupings, which
 * are orthogonal to which glyph shows up at the QWERTY "e" position.
 */
public class QwertyLayoutTable {
	/**
	 * Israeli Standard SI-1452 mapping — the layout Gboard / SwiftKey / iOS ship for Hebrew.
	 * Yiddish uses the same layout (no distinct Yiddish touchscreen keyboard exists in the
	 * wild); the only per-language difference is which dictionary is queried.
	 */
	private static final Map<String, String> HEBREW_SI1452;
	static {
		Map<String, String> m = new HashMap<>();
		// Row 1
		m.put("q", "/");  m.put("w", "'");  m.put("e", "ק"); m.put("r", "ר");
		m.put("t", "א"); m.put("y", "ט"); m.put("u", "ו"); m.put("i", "ן");
		m.put("o", "ם"); m.put("p", "פ");
		// Row 2
		m.put("a", "ש"); m.put("s", "ד"); m.put("d", "ג"); m.put("f", "כ");
		m.put("g", "ע"); m.put("h", "י"); m.put("j", "ח"); m.put("k", "ל");
		m.put("l", "ך");
		// Row 3
		m.put("z", "ז"); m.put("x", "ס"); m.put("c", "ב"); m.put("v", "ה");
		m.put("b", "נ"); m.put("n", "מ"); m.put("m", "צ");
		// Punctuation row: comma → tav, period → final tsade
		m.put(",", "ת"); m.put(".", "ץ");
		HEBREW_SI1452 = m;
	}

	/**
	 * Returns the language-specific character for [positionId], or null if the language uses
	 * the position ID as-is (English fallback). Position IDs are the single-char English glyphs
	 * baked into main_qwerty.xml — "q", "w", …, ",", ".".
	 */
	public static String override(Language language, String positionId) {
		if (language == null || positionId == null) return null;
		String code = language.getLocale().getLanguage();
		// Java maps "iw"→"he" and "yi"→"ji" (and vice-versa) inconsistently across Locale
		// factory paths, so accept both spellings for Hebrew and Yiddish.
		if ("iw".equals(code) || "he".equals(code) || "ji".equals(code) || "yi".equals(code)) {
			return HEBREW_SI1452.get(positionId);
		}
		return null;
	}
}
