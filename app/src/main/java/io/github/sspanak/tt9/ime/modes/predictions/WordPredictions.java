package io.github.sspanak.tt9.ime.modes.predictions;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashSet;

import io.github.sspanak.tt9.db.DataStore;
import io.github.sspanak.tt9.ime.swipe.Tt9WordProvider;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.languages.LanguageKind;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.util.TextTools;
import io.github.sspanak.tt9.util.chars.Characters;

public class WordPredictions extends Predictions {
	private LocaleWordsSorter localeWordsSorter;

	private String inputWord;
	private boolean isStemFuzzy;

	private String lastEnforcedTopWord;
	protected String penultimateWord;

	/**
	 * When true, this instance belongs to the on-screen QWERTY pipeline (owned by
	 * `qwertyInputMode`) and is eligible for QWERTY-only enhancements: bigram-context boost
	 * over prefix completions, seeded next-word candidates, etc. Set exclusively by
	 * TypingHandler.onStart when it allocates `qwertyInputMode`. The T9 pipeline's
	 * WordPredictions instance never has this true → every gated codepath is a no-op for T9.
	 */
	private boolean qwertyOnly = false;


	public WordPredictions(SettingsStore settings) {
		super(settings);
		lastEnforcedTopWord = "";
		localeWordsSorter = new LocaleWordsSorter(null);
		penultimateWord = "";
		stem = "";
	}

	/** See {@link #qwertyOnly}. Idempotent. Fire-and-forget setter — no other state depends on it. */
	public WordPredictions setQwertyOnly(boolean yes) {
		this.qwertyOnly = yes;
		return this;
	}

	public boolean isQwertyOnly() {
		return qwertyOnly;
	}


	@Override
	public Predictions setDigitSequence(@NonNull String digitSequence) {
		if (digitSequence.length() == 1 || digitSequence.equals(this.digitSequence)) {
			penultimateWord = ""; // enforce reloading the penultimate word
		}
		return super.setDigitSequence(digitSequence);
	}

	@Override
	public Predictions setLanguage(@NonNull Language language) {
		super.setLanguage(language);
		localeWordsSorter = new LocaleWordsSorter(language);

		return this;
	}


	public WordPredictions setIsStemFuzzy(boolean yes) {
		this.isStemFuzzy = yes;
		return this;
	}


	public WordPredictions setStem(String stem) {
		this.stem = stem;
		return this;
	}


	public WordPredictions setInputWord(String inputWord) {
		this.inputWord = inputWord.toLowerCase(language.getLocale());
		return this;
	}


	private void loadWithoutLeadingPunctuation() {
		DataStore.getWords(
			(dbWords) -> {
				char firstChar = inputWord.isEmpty() ? 0 : inputWord.charAt(0);
				for (int i = 0; firstChar > 0 && i < dbWords.size(); i++) {
					dbWords.set(i, firstChar + dbWords.get(i));
				}
				onDbWords(dbWords, false);
			},
			language,
			digitSequence.substring(1),
			onlyExactMatches,
			stem.length() > 1 ? stem.substring(1) : "",
			orderWordsByLength,
			minWords,
			maxWords
		);
	}


	@Override
	protected boolean isRetryAllowed() {
		return true;
	}

	/**
	 * dbWordsHandler
	 * Callback for when the database has finished loading words. If there were no matches in the database,
	 * they will be generated based on the "inputWord". After the word list is compiled, it notifies the
	 * external handler it is now possible to use it with "getList()".
	 */
	protected void onDbWords(ArrayList<String> dbWords, boolean isRetryAllowed) {
		// only the first round matters, the second one is just for getting the letters for a given key
		areThereDbWords = !dbWords.isEmpty() && isRetryAllowed;

		// If there were no database words for ",a", try getting the letters only (e.g. "a", "b", "c").
		// We do this to display them in the correct order.
		if (isRetryAllowed && dbWords.isEmpty() && digitSequence.length() == 2 && digitSequence.charAt(0) == '1') {
			loadWithoutLeadingPunctuation();
			return;
		}

		ArrayList<String> newWords = new ArrayList<>();
		suggestStem(newWords);
		dbWords = localeWordsSorter.shouldSort(stem, digitSequence) ? localeWordsSorter.sort(dbWords) : dbWords;
		dbWords = rearrangeByPairFrequency(dbWords);
		suggestMissingWords(generatePossibleStemVariations(dbWords), newWords);
		// QWERTY-tap path (stem length matches digit sequence length: the user typed exact
		// letters, no ambiguity): the T9 SQLite query is biased toward exact-length textonyms,
		// so for short typed prefixes like "th" it returns just the literal 2-letter word and
		// misses the obvious completions (the, this, they, them, their, then, ...). Always
		// supplement DB results with frequency-ordered prefix matches from Tt9WordProvider's
		// in-memory vocab. T9 path is unchanged.
		//
		// We do NOT call generateWordVariations on QWERTY when dbWords is empty — that
		// produces T9-textonym fallback ("tgw" → "tgw, tgx, tgy, tgz") which is wrong for
		// exact-letter input.
		final boolean qwertyExactMode = !stem.isEmpty() && stem.length() == digitSequence.length();
		if (qwertyExactMode && language != null) {
			if (!dbWords.isEmpty()) {
				suggestMissingWords(dbWords, newWords);
			}
			suggestMissingWords(
				Tt9WordProvider.getWordsByPrefix(language.getId(), stem.toLowerCase(language.getLocale()), 8),
				newWords
			);
		} else if (!dbWords.isEmpty()) {
			suggestMissingWords(dbWords, newWords);
		} else {
			suggestMissingWords(generateWordVariations(inputWord), newWords);
		}
		words = insertPunctuationCompletions(newWords);
		// QWERTY-only: reorder prefix candidates by seeded bigram context. If the user just
		// committed a word that's a common context anchor (e.g. "the", "happy", "to"), lift
		// matching prefix completions to the front. Small precision win with no risk to T9
		// because the whole method early-returns when `qwertyOnly` is false. Runs BEFORE fuzzy
		// injection so fuzzy adds still sit below the context-lifted primary candidates.
		if (qwertyOnly && qwertyExactMode) {
			applyBigramContextBoost(words);
		}
		// Fuzzy typo-correction (Damerau-Levenshtein distance 1): inject up to 3 dictionary
		// words that are one edit away from the typed prefix. Insert at position 1 so they sit
		// just below the top prefix-match for normal typing, and act as the primary fallback
		// when there are no prefix matches at all (the case where generateWordVariations used
		// to fire with T9 textonyms).
		insertFuzzyCandidates(words);
		// Smart contractions inserted at position 1 (visible alternative, not auto-committed).
		// Previously placed at position 0 — the auto-promotion felt surprising to the user
		// when they DID want "im" or "dont" typed literally (e.g. lowercase casual style).
		applySmartContraction(words);

		onWordsChanged.run();
	}


	/** How many fuzzy variants we surface per suggestion update. Higher → noisier strip. */
	private static final int MAX_FUZZY_CANDIDATES = 2;
	/** Stems shorter than this skip insertion/deletion/transposition variants (they balloon
	 *  noise — "h", "t", "hth", "thg" instead of useful corrections). They still get
	 *  spatial-substitution variants ("tg" → "th" via 'g'→'h'), which are precise enough to
	 *  be useful on a 2-letter typo. */
	private static final int MIN_FUZZY_INSERT_DELETE_LEN = 3;
	/** Below this length we don't fuzz at all (single-letter stems are too ambiguous). */
	private static final int MIN_FUZZY_PREFIX_LEN = 2;
	private static final int MAX_FUZZY_PREFIX_LEN = 12;

	/**
	 * Generate Damerau-Levenshtein-1 variants of [stem] (transposition + deletion + insertion +
	 * substitution) and inject the ones present in the per-language vocabulary into [out] at
	 * index 1. Gated to the QWERTY-tap path: only fires when stem is a full unambiguous letter
	 * sequence (i.e. {@code stem.length() == digitSequence.length()}). No-op when the
	 * Tt9WordProvider for this language isn't loaded yet.
	 */
	private void insertFuzzyCandidates(@NonNull ArrayList<String> out) {
		if (stem == null || stem.length() != digitSequence.length()) return;
		if (stem.length() < MIN_FUZZY_PREFIX_LEN || stem.length() > MAX_FUZZY_PREFIX_LEN) return;
		if (language == null || language.getId() <= 0) return;
		final String prefix = stem.toLowerCase(language.getLocale());
		final int langId = language.getId();
		// Skip fuzzy entirely if the typed stem is itself a real dictionary word — the user
		// isn't typing a typo; surfacing variants like "th" → "thy/thx" or "hi" → "ho/hu" just
		// clutters the strip with words the user didn't intend. Fuzzy is for typo recovery,
		// not for "alternatives to a perfectly-good word."
		if (Tt9WordProvider.containsWord(langId, prefix)) return;
		// Also skip if we already have several prefix completions — the strip is showing
		// useful real-prefix matches, no need to add fuzzy mush alongside.
		if (out.size() >= 3) return;
		final HashSet<String> seen = new HashSet<>(out.size());
		for (String w : out) seen.add(w);
		seen.add(prefix); // don't propose the typed string itself as a fuzzy correction

		final ArrayList<String> hits = new ArrayList<>(MAX_FUZZY_CANDIDATES);
		// Insert/delete/transpose only on longer stems — on 2-letter stems they produce too much
		// noise ("h", "t", "hth", "thg" instead of useful corrections). Spatial substitution
		// (below) still runs on length-2 to catch "tg" → "th" etc.
		final boolean allowInsertDelete = prefix.length() >= MIN_FUZZY_INSERT_DELETE_LEN;
		if (allowInsertDelete) {
			// Transpositions — cheapest variant class, also the highest-precision typo class.
			for (int i = 0; i < prefix.length() - 1 && hits.size() < MAX_FUZZY_CANDIDATES; i++) {
				char a = prefix.charAt(i), b = prefix.charAt(i + 1);
				if (a == b) continue;
				tryAdd(prefix.substring(0, i) + b + a + prefix.substring(i + 2), langId, seen, hits);
			}
			// Deletions — remove one character.
			for (int i = 0; i < prefix.length() && hits.size() < MAX_FUZZY_CANDIDATES; i++) {
				tryAdd(prefix.substring(0, i) + prefix.substring(i + 1), langId, seen, hits);
			}
			// Insertions — try each lowercase letter at each position.
			for (int i = 0; i <= prefix.length() && hits.size() < MAX_FUZZY_CANDIDATES; i++) {
				for (char c = 'a'; c <= 'z' && hits.size() < MAX_FUZZY_CANDIDATES; c++) {
					tryAdd(prefix.substring(0, i) + c + prefix.substring(i), langId, seen, hits);
				}
			}
		}
		// Substitutions — replace each character with another. Prioritize keyboard-adjacent
		// keys first ("tge" → swap 'g' with its neighbors 'f','h','v','y','t','b' before
		// the rest of the alphabet) because adjacent-key typos are by far the most common
		// substitution error on a QWERTY layout. With MAX_FUZZY_CANDIDATES=3 the spatial
		// ordering decides WHICH 3 hits we keep, not whether we look at more candidates —
		// so this is purely a precision win on a fixed budget.
		for (int i = 0; i < prefix.length() && hits.size() < MAX_FUZZY_CANDIDATES; i++) {
			char orig = prefix.charAt(i);
			final char[] neighbors = QWERTY_NEIGHBORS.get(orig);
			if (neighbors != null) {
				for (char c : neighbors) {
					if (hits.size() >= MAX_FUZZY_CANDIDATES) break;
					tryAdd(prefix.substring(0, i) + c + prefix.substring(i + 1), langId, seen, hits);
				}
			}
			// Then fall through alphabetically for completeness — catches the non-spatial
			// cases (homophones, intentional spelling variants).
			for (char c = 'a'; c <= 'z' && hits.size() < MAX_FUZZY_CANDIDATES; c++) {
				if (c == orig) continue;
				tryAdd(prefix.substring(0, i) + c + prefix.substring(i + 1), langId, seen, hits);
			}
		}

		if (hits.isEmpty()) return;
		// Insert fuzzy hits at position 1 so they're VISIBLE but never silently committed by
		// space. The literal typed stem stays at position 0 — what the user typed is what they
		// get on space. (Earlier inline-autocorrect-on-space variant displaced the stem when
		// it wasn't a known word; user found it surprising and asked to roll it back. Fuzzy
		// stays as a tappable suggestion alongside the literal.)
		final int insertAt = out.isEmpty() ? 0 : 1;
		out.addAll(insertAt, hits);
	}

	private void tryAdd(String variant, int langId, HashSet<String> seen, ArrayList<String> hits) {
		if (seen.contains(variant)) return;
		if (!Tt9WordProvider.containsWord(langId, variant)) return;
		seen.add(variant);
		hits.add(variant);
	}


	/**
	 * QWERTY-only bigram-context boost. Reads the previously-committed word (via
	 * `getPenultimateWord`) and looks up its seeded next-word candidates in
	 * {@link io.github.sspanak.tt9.ime.qwerty.QwertyBigramSeed}. Any prefix-completion in [out]
	 * that appears in that set is moved to the front of the list, preserving relative order
	 * among boosted / non-boosted entries.
	 *
	 * No-op for T9 (gated on `qwertyOnly` at the call site) and no-op if the language isn't
	 * seeded (`en` for now). Runs on the main thread; the seed lookup is a single HashMap get
	 * + a short list scan — under 1 ms even in cold-cache pathological cases.
	 */
	private void applyBigramContextBoost(@NonNull ArrayList<String> out) {
		if (out.size() < 2) return;
		if (language == null) return;
		final String langCode = language.getCode();
		if (langCode == null || langCode.isEmpty()) return;
		String prev = penultimateWord;
		if (prev == null || prev.isEmpty()) {
			// Not yet resolved (setDigitSequence hasn't triggered a refresh) — try to compute
			// it lazily so the boost fires on the first-letter tap after a space commit too.
			prev = getPenultimateWord(out.get(0));
		}
		if (prev.isEmpty()) return;
		final java.util.List<String> boosted =
			io.github.sspanak.tt9.ime.qwerty.QwertyBigramSeed.getNextWordsAfter(langCode, prev.toLowerCase(language.getLocale()), 20);
		if (boosted.isEmpty()) return;
		final java.util.HashSet<String> boostedSet = new java.util.HashSet<>(boosted);
		// Stable partition: everything in `boostedSet` goes to the front, in original order;
		// remaining entries follow in original order.
		final ArrayList<String> front = new ArrayList<>(4);
		final ArrayList<String> rest = new ArrayList<>(out.size());
		for (String w : out) {
			if (w == null) continue;
			if (boostedSet.contains(w.toLowerCase(language.getLocale()))) {
				front.add(w);
			} else {
				rest.add(w);
			}
		}
		if (front.isEmpty()) return;
		out.clear();
		out.addAll(front);
		out.addAll(rest);
	}


	/**
	 * Tiny apostrophe-free → apostrophe-correct map. When the user types one of these (lowercase
	 * exact match, QWERTY-locked-prefix mode), we surface the contraction at position 0 so the
	 * standard space-to-commit gesture types the proper form. Same convention as every major
	 * mobile IME going back to the BlackBerry days.
	 *
	 * Length intentionally short. A bigger list (every "wouldnt/shouldnt/couldnt") doesn't add
	 * value once a personal-dictionary auto-add layer is in place (Phase 2b) — this catches
	 * the high-frequency cases so the feature works out of the box.
	 */
	private static final java.util.Map<String, String> SMART_CONTRACTIONS = buildContractions();

	private static java.util.Map<String, String> buildContractions() {
		java.util.HashMap<String, String> m = new java.util.HashMap<>();
		m.put("im", "I'm");
		m.put("ive", "I've");
		m.put("ill", "I'll");
		m.put("id", "I'd");
		m.put("dont", "don't");
		m.put("doesnt", "doesn't");
		m.put("didnt", "didn't");
		m.put("isnt", "isn't");
		m.put("wasnt", "wasn't");
		m.put("werent", "weren't");
		m.put("arent", "aren't");
		m.put("cant", "can't");
		m.put("couldnt", "couldn't");
		m.put("wont", "won't");
		m.put("wouldnt", "wouldn't");
		m.put("shouldnt", "shouldn't");
		m.put("hasnt", "hasn't");
		m.put("havent", "haven't");
		m.put("hadnt", "hadn't");
		m.put("youre", "you're");
		m.put("youve", "you've");
		m.put("youll", "you'll");
		m.put("youd", "you'd");
		m.put("theyre", "they're");
		m.put("theyve", "they've");
		m.put("theyll", "they'll");
		m.put("theyd", "they'd");
		m.put("weve", "we've");
		m.put("well", "we'll");
		m.put("wed", "we'd");
		m.put("hes", "he's");
		m.put("shes", "she's");
		m.put("its", "it's");
		m.put("thats", "that's");
		m.put("lets", "let's");
		m.put("whats", "what's");
		m.put("whos", "who's");
		m.put("wheres", "where's");
		m.put("theres", "there's");
		return java.util.Collections.unmodifiableMap(m);
	}

	private void applySmartContraction(@NonNull ArrayList<String> out) {
		if (stem == null || stem.isEmpty() || stem.length() != digitSequence.length()) return;
		if (language == null) return;
		// The map is English-only. Non-English locales will simply never match a key, so we
		// don't need an explicit language gate — but lowercasing with the correct Locale
		// matters for Turkish 'i' / 'I' so we still pass language.getLocale().
		final String typed = stem.toLowerCase(language.getLocale());
		final String contraction = SMART_CONTRACTIONS.get(typed);
		if (contraction == null) return;
		out.remove(contraction);
		// Insert at position 1 (after the literal typed stem) — user can tap to accept the
		// contraction but it doesn't auto-commit on space. Surprises users less.
		final int insertAt = out.isEmpty() ? 0 : 1;
		out.add(insertAt, contraction);
	}

	/**
	 * Standard QWERTY adjacency map (US layout). Only lowercase a-z. Used by the substitution
	 * pass of insertFuzzyCandidates — when the user types a wrong letter, the wrong letter is
	 * overwhelmingly likely to be one physically adjacent to the intended one (~80% of typos
	 * per Damerau's classic 1964 analysis). Trying neighbors first within a fixed candidate
	 * budget surfaces the right correction much more often than alphabetical iteration.
	 *
	 * Non-US layouts (AZERTY, QWERTZ, Dvorak) aren't covered here — the spatial model
	 * degrades gracefully to alphabetical fallback. Future: derive this from the live
	 * SwipeKey geometry per language so multi-layout users get the right model.
	 */
	private static final java.util.Map<Character, char[]> QWERTY_NEIGHBORS = buildQwertyNeighbors();

	private static java.util.Map<Character, char[]> buildQwertyNeighbors() {
		java.util.HashMap<Character, char[]> m = new java.util.HashMap<>();
		m.put('q', new char[]{'w', 'a'});
		m.put('w', new char[]{'q', 'e', 'a', 's'});
		m.put('e', new char[]{'w', 'r', 's', 'd'});
		m.put('r', new char[]{'e', 't', 'd', 'f'});
		m.put('t', new char[]{'r', 'y', 'f', 'g'});
		m.put('y', new char[]{'t', 'u', 'g', 'h'});
		m.put('u', new char[]{'y', 'i', 'h', 'j'});
		m.put('i', new char[]{'u', 'o', 'j', 'k'});
		m.put('o', new char[]{'i', 'p', 'k', 'l'});
		m.put('p', new char[]{'o', 'l'});
		m.put('a', new char[]{'q', 'w', 's', 'z'});
		m.put('s', new char[]{'a', 'w', 'd', 'z', 'x', 'e'});
		m.put('d', new char[]{'s', 'e', 'f', 'c', 'x', 'r'});
		m.put('f', new char[]{'d', 'r', 'g', 'v', 'c', 't'});
		m.put('g', new char[]{'f', 't', 'h', 'b', 'v', 'y'});
		m.put('h', new char[]{'g', 'y', 'j', 'n', 'b', 'u'});
		m.put('j', new char[]{'h', 'u', 'k', 'm', 'n', 'i'});
		m.put('k', new char[]{'j', 'i', 'l', 'm', 'o'});
		m.put('l', new char[]{'k', 'o', 'p'});
		m.put('z', new char[]{'a', 's', 'x'});
		m.put('x', new char[]{'z', 's', 'd', 'c'});
		m.put('c', new char[]{'x', 'd', 'f', 'v'});
		m.put('v', new char[]{'c', 'f', 'g', 'b'});
		m.put('b', new char[]{'v', 'g', 'h', 'n'});
		m.put('n', new char[]{'b', 'h', 'j', 'm'});
		m.put('m', new char[]{'n', 'j', 'k'});
		return java.util.Collections.unmodifiableMap(m);
	}


	/**
	 * suggestStem
	 * Add the current stem filter to the predictions list, when it has length of X and
	 * the user has pressed X keys (otherwise, it makes no sense to add it).
	 */
	private void suggestStem(@NonNull ArrayList<String> newWords) {
		if (!stem.isEmpty() && stem.length() == digitSequence.length()) {
			newWords.add(stem);
		}
	}


	/**
	 * generateWordVariations
	 * When there are no matching suggestions after the last key press, generate a list of possible
	 * ones, so that the user can complete a missing word that is completely different from the ones
	 * in the dictionary.
	 * For example, if the word is "missin_" and the last pressed key is "4", the results would be:
	 * | missing | missinh | missini |
	 */
	protected ArrayList<String> generateWordVariations(String baseWord) {
		ArrayList<String> generatedWords = new ArrayList<>();

		// This function is called from async context, so by the time it is executed, the digit sequence
		// might have been deleted. But in this case, it makes no sense to generate suggestions.
		if (digitSequence.isEmpty()) {
			return generatedWords;
		}

		// Make sure the displayed word and the digit sequence, we will be generating suggestions from,
		// have the same length, to prevent visual discrepancies.
		baseWord = (baseWord != null && !baseWord.isEmpty()) ? baseWord.substring(0, Math.min(digitSequence.length() - 1, baseWord.length())) : "";

		// append all letters for the last digit in the sequence (the last pressed key)
		int lastSequenceDigit = digitSequence.charAt(digitSequence.length() - 1) - '0';
		for (String keyLetter : settings.getOrderedKeyChars(language, lastSequenceDigit)) {
			if (Character.isAlphabetic(keyLetter.charAt(0)) || Characters.isCombiningPunctuation(language, keyLetter.charAt(0)) || TextTools.isCombining(keyLetter)) {
				generatedWords.add(baseWord + keyLetter);
			}
		}

		// if there are no letters for this key, just append the number
		if (generatedWords.isEmpty()) {
			generatedWords.add(baseWord + digitSequence.charAt(digitSequence.length() - 1));
		}

		containsGeneratedWords = true;
		return generatedWords;
	}


	/**
	 * insertPunctuationCompletions
	 * When given: "don'", for example, this inserts all other 1-key alternatives, like:
	 * "don.", "don?", "don!" and so on. The generated words will be inserted after the exact
	 * database matches, as if they were in the database with low frequency. This is to preserve the
	 * sorting by length and frequency.
	 * Finally, based on the discussion in <a href="https://github.com/sspanak/tt9/issues/634">Issue 634</a>,
	 * we skip the fuzzy matches, because it is more convenient to select the last word "don?" using
	 * a single key press, instead of longer words like "don't".
	 */
	private ArrayList<String> insertPunctuationCompletions(ArrayList<String> dbWords) {
		if (!stem.isEmpty() || dbWords.isEmpty() || digitSequence.length() < 2 || !digitSequence.endsWith("1")) {
			return dbWords;
		}

		ArrayList<String> complementedWords = new ArrayList<>();
		int exactMatchLength = digitSequence.length();

		// shortest database words (exact matches)
		for (String w : dbWords) {
			if (w.length() <= exactMatchLength) {
				complementedWords.add(w);
			}
		}

		// generated "exact matches"
		String baseWord = inputWord.length() == digitSequence.length() - 1 ? inputWord : dbWords.get(0);
		for (String w : generateWordVariations(baseWord)) {
			if (!dbWords.contains(w) && !dbWords.contains(w.toLowerCase(language.getLocale()))) {
				complementedWords.add(w);
			}
		}

		// no longer database words (skip the fuzzy matches)

		containsGeneratedWords = true;
		return complementedWords;
	}


	/**
	 * generatePossibleStemVariations
	 * Similar to generatePossibleCompletions(), but uses the current filter as a base word. This is
	 * used to complement the database results with all possible variations for the next key, when
	 * the stem filter is on.
	 * <p>
	 * It will not generate anything if more than one key was pressed after filtering though.
	 * <p>
	 * For example, if the filter is "extr", the current word is "extr_" and the user has pressed "1",
	 * the database would have returned only "extra", but this function would also
	 * generate: "extrb" and "extrc". This is useful for typing an unknown word, that is similar to
	 * the ones in the dictionary.
	 */
	private ArrayList<String> generatePossibleStemVariations(ArrayList<String> dbWords) {
		ArrayList<String> variations = new ArrayList<>();

		if (isStemFuzzy && !stem.isEmpty() && stem.length() == digitSequence.length() - 1) {
			ArrayList<String> allPossibleVariations = generateWordVariations(stem);

			// first add the known words, because it makes more sense to see them first
			for (String variation : allPossibleVariations) {
				if (dbWords.contains(variation)) {
					variations.add(variation);
				}
			}

			// then add the unknown ones, so they can be used as possible beginnings of new words.
			for (String word : allPossibleVariations) {
				if (!dbWords.contains(word)) {
					variations.add(word);
				}
			}
		}

		containsGeneratedWords = !variations.isEmpty();
		return variations;
	}


	/**
	 * onAccept
	 * This stores common word pairs, so they can be used in "rearrangeByPairFrequency()" method.
	 * For example, if the user types "I am an apple", the word "am" will be suggested after "I",
	 * and "an" after "am", even if "am" frequency was boosted right before typing "an". This both
	 * prevents from suggesting the same word twice in row and makes the suggestions more intuitive
	 * when there are many textonyms for a single sequence.
	 */
	public void onAccept(String word, String sequence) {
		if (
			word == null
			// If the word is the first suggestion, we have already guessed it right, and it makes no
			// sense to store it as a popular pair or increase its priority. However, if the stem has been
			// set using word filtering, the user has probably tried to search for a word that has not been
			// displayed at the beginning. In this case, we process it after all.
			|| (!words.isEmpty() && words.get(0).equals(word) && stem.isEmpty())
		) {
			return;
		}

		pairWithPreviousWord(word, sequence);
		makeTopWord(word, sequence);
	}


	/**
	 * Update the priority only if the user has selected the word, not when we have enforced it
	 * because it is in a popular word pair.
	 */
	protected void makeTopWord(String word, String sequence) {
		if (!word.equals(lastEnforcedTopWord)) {
			DataStore.makeTopWord(language, word, sequence);
		}
	}


	/**
	 * rearrangeByPairFrequency
	 * Uses the last two words in the text field to rearrange the suggestions, so that the most popular
	 * one in a pair comes first. This is useful for typing phrases, like "I am an apple". Since, in
	 * "onAccept()", we have remembered the "am" comes after "I" and "an" comes after "am", we will
	 * not suggest the textonyms "am" or "an" twice (depending on which has the highest frequency).
	 */
	protected ArrayList<String> rearrangeByPairFrequency(ArrayList<String> words) {
		lastEnforcedTopWord = "";

		if (!settings.getPredictWordPairs() || words.size() < 2) {
			return words;
		}

		ArrayList<String> rearrangedWords = new ArrayList<>();
		if (penultimateWord.isEmpty()) {
			penultimateWord = getPenultimateWord(words.get(0));
		}

		String pairWord = DataStore.getWord2(language, penultimateWord, digitSequence);
		int morePopularIndex = TextTools.indexOfIgnoreCase(words, pairWord);
		if (morePopularIndex == -1) {
			return words;
		}

		lastEnforcedTopWord = words.get(morePopularIndex);
		rearrangedWords.add(lastEnforcedTopWord);

		for (int i = 0; i < words.size(); i++) {
			if (i != morePopularIndex) {
				rearrangedWords.add(words.get(i));
			}
		}

		return rearrangedWords;
	}


	/**
	 * Pairs the given word and its digit sequence to the last word in the text field.
	 * Second condition note: If the accepted word is longer than the sequence, it is some different word,
	 * not a textonym of the fist suggestion. We don't need to store it.
	 */
	protected void pairWithPreviousWord(@NonNull String word, @NonNull String sequence) {
		if (settings.getPredictWordPairs() && sequence.length() == digitSequence.length()) {
			DataStore.addWordPair(language, getPenultimateWord(word), word, sequence);
		}
	}


	/**
	 * Returns the last word in the text field. The way of finding it depends on the language, so
	 * we have a separate method for that.
	 */
	@NonNull
	protected String getPenultimateWord(@NonNull String currentWord) {
		// We are in the middle of a word or at the beginning of a new one. Pairing makes no sense.
		if (afterCursor.startsWithWord()) {
			return "";
		}

		if (beforeCursor.isEmpty()) {
			return Characters.START_OF_TEXT;
		}

		// We are at the end of a word. The user is probably typing a compound word. We do not want to
		// pair with the first part of the compound word.
		final String before = beforeCursor.toString();
		if (before.length() > currentWord.length() && before.endsWith(currentWord) && Character.isAlphabetic(before.charAt(before.length() - currentWord.length() - 1))) {
			return Characters.END_OF_TEXT;
		}

		return beforeCursor.getPreviousWord(
			!currentWord.isEmpty(),
			LanguageKind.isUkrainian(language) || LanguageKind.isHebrew(language),
			true
		);
	}
}
