package io.github.sspanak.tt9.languages;

/**
 * Hebrew "sofit" (final-form) letter helpers.
 *
 * Five Hebrew letters have distinct final-form glyphs used only at word end:
 *   מ → ם (mem sofit)
 *   נ → ן (nun sofit)
 *   צ → ץ (tsade sofit)
 *   פ → ף (peh sofit)
 *   כ → ך (kaf sofit)
 *
 * On a physical Hebrew keyboard both variants are typed directly. On a touch keyboard using
 * SI-1452, three sofit forms sit on their own keys (ם ן ך) but the other two (ף ץ) are
 * accessed via long-press. Users don't reliably reach for those, so the Hebrew IME
 * convention is to auto-convert at word-boundary: whatever regular form the user typed at the
 * end of a word gets replaced with the sofit variant when the word commits. Hebrew orthography
 * makes this unambiguous — a word-final ם/ן/ץ/ף/ך MUST be sofit; there is no case where the
 * regular form is orthographically correct at word end.
 *
 * Also used the opposite way (normalizeToRegular) so prefix lookup treats "שלומ" (typed by
 * the user before commit) and "שלום" (in the dictionary) as equivalent.
 */
public class HebrewSofit {
	private HebrewSofit() {}

	/**
	 * True when [language] uses the Hebrew alphabet (Hebrew or Yiddish). Yiddish shares the
	 * alphabet including sofit forms — same normalization rules apply.
	 */
	public static boolean appliesTo(Language language) {
		if (language == null) return false;
		String code = language.getLocale().getLanguage();
		return "iw".equals(code) || "he".equals(code) || "ji".equals(code) || "yi".equals(code);
	}

	/**
	 * If [word]'s last character is a Hebrew regular form with a sofit variant, return the word
	 * with that character replaced by its sofit. Otherwise return [word] unchanged. Yiddish uses
	 * the same alphabet — the caller decides whether to invoke us based on language.
	 */
	public static String applyFinalForm(String word) {
		if (word == null || word.isEmpty()) return word;
		char last = word.charAt(word.length() - 1);
		char converted;
		switch (last) {
			case 'מ': converted = 'ם'; break;
			case 'נ': converted = 'ן'; break;
			case 'צ': converted = 'ץ'; break;
			case 'פ': converted = 'ף'; break;
			case 'כ': converted = 'ך'; break;
			default: return word;
		}
		return word.substring(0, word.length() - 1) + converted;
	}

	/**
	 * Normalize every sofit form in [s] to its regular counterpart. Used at prefix-lookup time
	 * so a stem typed with regular letters ("שלומ") matches a dictionary entry with a sofit
	 * ("שלום"). Fast path: returns the input reference when nothing needs converting.
	 */
	public static String normalizeToRegular(String s) {
		if (s == null || s.isEmpty()) return s;
		int i;
		for (i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == 'ם' || c == 'ן' || c == 'ץ' || c == 'ף' || c == 'ך') break;
		}
		if (i == s.length()) return s;
		StringBuilder sb = new StringBuilder(s.length());
		sb.append(s, 0, i);
		for (int j = i; j < s.length(); j++) {
			char c = s.charAt(j);
			switch (c) {
				case 'ם': sb.append('מ'); break;
				case 'ן': sb.append('נ'); break;
				case 'ץ': sb.append('צ'); break;
				case 'ף': sb.append('פ'); break;
				case 'ך': sb.append('כ'); break;
				default: sb.append(c); break;
			}
		}
		return sb.toString();
	}
}
