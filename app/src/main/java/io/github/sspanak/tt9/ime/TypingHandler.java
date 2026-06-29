package io.github.sspanak.tt9.ime;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;

import io.github.sspanak.tt9.db.words.DictionaryLoader;
import io.github.sspanak.tt9.hacks.InputType;
import io.github.sspanak.tt9.ime.helpers.CursorOps;
import io.github.sspanak.tt9.ime.helpers.InputConnectionAsync;
import io.github.sspanak.tt9.ime.helpers.InputModeValidator;
import io.github.sspanak.tt9.ime.helpers.SuggestionOps;
import io.github.sspanak.tt9.ime.helpers.TextField;
import io.github.sspanak.tt9.ime.helpers.TextSelection;
import io.github.sspanak.tt9.ime.mindreader.MindReader;
import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.ime.modes.InputModeKind;
import io.github.sspanak.tt9.ime.swipe.Tt9WordProvider;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.languages.LanguageCollection;
import io.github.sspanak.tt9.languages.LanguageKind;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.util.Text;
import io.github.sspanak.tt9.util.chars.Characters;

public abstract class TypingHandler extends KeyPadHandler {
	// internal settings/data
	@NonNull protected InputType inputType = new InputType(null, null);
	@NonNull protected TextField textField = new TextField(null, null, null);
	@NonNull protected TextSelection textSelection = new TextSelection(null, null);
	@NonNull protected SuggestionOps suggestionOps = new SuggestionOps(null, null, null, null, null, null, null, null, null);

	@Nullable private Handler shiftStateDebounceHandler;

	// input
	@NonNull protected ArrayList<Integer> allowedInputModes = new ArrayList<>();
	@NonNull protected InputMode mInputMode = InputMode.getInstance(null, null, null, null, InputMode.MODE_PASSTHROUGH);

	/**
	 * Position-indexed buffer shared between the physical 12-key and the on-screen QWERTY.
	 * Populated only while the QWERTY layout is active. Lock state informs the stem passed to
	 * ModeWords so the two input paths agree on a single in-progress word.
	 */
	@NonNull protected final ComposingWord composingWord = new ComposingWord();

	/**
	 * Set to true while {@link #onQwertyLetter} calls {@code mInputMode.onNumber} synthetically
	 * (to extend digitSequence with the typed letter's T9 digit at a non-contiguous lock position).
	 * Prevents {@link #onNumber} from adding a SECOND ambiguous digit to composingWord — the
	 * QWERTY path already added a LOCKED letter for the same position.
	 */
	private boolean suppressComposingDigitAppend = false;

	// language
	protected ArrayList<Integer> mEnabledLanguages;
	protected Language mLanguage;

	// output: suggestions
	abstract protected void onAcceptSuggestionsDelayed(String s);
	abstract protected void getSuggestions(double loadingId, @Nullable String currentWord, @Nullable Runnable onComplete);

	// output: mind-reading
	@NonNull protected final MindReader mindReader = new MindReader();

	// Wrong-result rejection signal — set whenever a glide gesture commits a word. If the user
	// backspaces within REJECTION_WINDOW_MS, the committed word was almost certainly wrong; we
	// penalize its glide frequency so similar gestures don't pick it again. Cleared by any
	// non-backspace text-producing action (the user moved on instead of rejecting), and by the
	// backspace itself once the penalty fires.
	private static final long REJECTION_WINDOW_MS = 3000L;
	private String lastGlideCommittedWord = "";
	private long lastGlideCommitTimeMs = 0L;
	// Captured at commit time so a language switch between commit and backspace doesn't apply
	// the penalty to whichever language is current at backspace.
	private int lastGlideCommittedLangId = -1;

	// Confused-pair learning: after a rejection penalty fires, the next glide commit (within
	// CONFUSED_PAIR_WINDOW_MS, same language, different word) is interpreted as "the user
	// preferred this over the rejected one." We record (rejected → accepted) in the per-language
	// pair table so the classifier can swap them next time both appear in the top-K. The window
	// is longer than the rejection window because the user often takes a couple seconds to
	// reformulate the gesture they meant.
	private static final long CONFUSED_PAIR_WINDOW_MS = 10000L;
	private String lastRejectedGlideWord = "";
	private long lastRejectionTimeMs = 0L;
	private int lastRejectionLangId = -1;

	/** Drop the wrong-result rejection arm without firing the penalty. */
	private void clearGlideRejectionArm() {
		lastGlideCommittedWord = "";
		lastGlideCommitTimeMs = 0L;
		lastGlideCommittedLangId = -1;
	}
	abstract protected void autoCompleteOnNumber(double loadingId, @NonNull String[] surroundingChars, @Nullable String lastWord, int number);
	abstract protected void guessNextWord(@NonNull String[] surroundingText, @Nullable String lastWord);
	abstract protected boolean shouldAcceptGuessesOnNumber(int key);


	protected void createSuggestionBar() {
		suggestionOps = new SuggestionOps(this, settings, mainView, appHacks, inputType, textField, statusBar, this::onAcceptSuggestionsDelayed, this::onOK);
	}


	protected boolean shouldBeOff() {
		return getCurrentInputConnection() == null || InputModeKind.isPassthrough(mInputMode);
	}


	@Override
	protected void onInit() {
		super.onInit();
		mindReader.setSettings(settings);
	}


	protected void cleanUp() {
		InputConnectionAsync.destroy();
		mindReader.destroy();
	}


	@Override
	protected boolean onStart(EditorInfo field, boolean restarting) {
		boolean restart = restarting || textField.equals(getCurrentInputConnection(), field);

		setInputField(field);

		// 1. In case we are back from Settings screen, update the language list
		// 2. If the connected app hints it is in a language different than the current one,
		// we try to switch.
		boolean languageChanged = determineLanguage();

		// ignore multiple calls for the same field, caused by requestShowSelf() -> showWindow(),
		// or weirdly functioning apps, such as the Qin SMS app
		if (restart && !languageChanged && appHacks.isRestartForbidden() && mInputMode.getId() == determineInputModeId()) {
			return false;
		}
		settings.setDefaultCharOrder(mLanguage, false);
		resetKeyRepeat();
		mInputMode = determineInputMode();
		determineTextCase();
		suggestionOps.set(null);

		// don't use surroundingText cache on start up
		final String[] surroundingText = textField.getSurroundingStringForAutoAssistance(settings, mInputMode);
		updateShiftState(surroundingText[0], false, false);
		mindReader
			.setLanguage(mLanguage)
			.setContext(mInputMode, mLanguage, surroundingText, null);

		return true;
	}


	protected void setInputField(EditorInfo field) {
		if (textField.equals(getCurrentInputConnection(), field)) {
			return;
		}

		InputMethodService context = field != null ? this : null;
		inputType = new InputType(context, field);
		textField = new TextField(context, settings, field);
		textSelection = new TextSelection(context, inputType);

		// changing the TextField and notifying all interested classes is an atomic operation
		appHacks.setDependencies(inputType, textField, textSelection);
		suggestionOps.setDependencies(appHacks, inputType, textField, statusBar);
	}


	protected void validateLanguages() {
		mEnabledLanguages = InputModeValidator.validateEnabledLanguages(mEnabledLanguages);
		mLanguage = InputModeValidator.validateLanguage(mLanguage, mEnabledLanguages);
		settings.saveInputLanguage(mLanguage.getId());
		settings.saveEnabledLanguageIds(mEnabledLanguages);
	}


	protected void onFinishTyping() {
		if (shiftStateDebounceHandler != null) {
			shiftStateDebounceHandler.removeCallbacksAndMessages(null);
			shiftStateDebounceHandler = null;
		}
		suggestionOps.cancelDelayedAccept();
		mInputMode = InputMode.getInstance(null, null, null, null, InputMode.MODE_PASSTHROUGH);
		composingWord.clear();
		setInputField(null);
	}


	/**
	 * Called when the user taps a letter on the on-screen QWERTY. Appends a locked letter to the
	 * composing buffer and feeds the resulting stem into ModeWords so its existing dictionary
	 * filter narrows the suggestions automatically. Falls back to {@link #onText} for
	 * non-alphabetic input (punctuation, space), which commits the pending word.
	 *
	 * Only the contiguous-prefix case is wired: as long as the user stays on the QWERTY layout,
	 * each new letter extends the prefix. Mixing physical-keypad digits in between currently
	 * blows away the trailing digit positions (setWordStem recomputes digitSequence from the
	 * locked prefix). That case is documented as a known limitation of the first integration.
	 */
	public boolean onQwertyLetter(@Nullable String letter) {
		// Any text-producing action after a glide commit means the user moved on — they didn't
		// reject the prior glide commit, so drop the rejection arm. (onText / onNumber clear it
		// too; clearing here also covers the case where this method handles the letter directly
		// via setWordStem without falling through.)
		clearGlideRejectionArm();
		if (letter == null || letter.length() != 1) {
			return onText(letter == null ? "" : letter, false);
		}
		char c = letter.charAt(0);
		if (!Character.isLetter(c)) {
			return onText(letter, false);
		}
		if (!InputModeKind.isPredictive(mInputMode)) {
			return onText(letter, false);
		}

		suggestionOps.cancelDelayedAccept();
		final int sizeBefore = composingWord.size();
		composingWord.appendLockedLetter(c);

		final String newStem = composingWord.getLockedPrefix();
		// Case 1: the new letter extends the leading locked prefix. Feed it to ModeWords as a
		// stem update — the existing T9 dictionary filter narrows on it.
		if (!newStem.isEmpty() && newStem.length() == sizeBefore + 1) {
			if (mInputMode.setWordStem(newStem, true)) {
				// Show the typed prefix immediately so the keypress feels instant — without this,
				// the letter doesn't appear until the async dictionary query completes (visible lag
				// on slow disks / large vocabularies). The async suggestion handler overwrites this
				// with the highlighted-stem version once it returns.
				appHacks.setComposingText(newStem);
				getSuggestions(Math.random(), null, null);
				return true;
			}
			// setWordStem refused (invalid char for the active language); roll back and commit
			// as literal text below.
			composingWord.removeLast();
			return onText(letter, false);
		}

		// Case 2: there are ambiguous T9 digits between the leading lock-run and this new letter.
		// We can't feed setWordStem (it's prefix-only and would clobber digitSequence). Instead:
		// look up the T9 digit for this letter, append it to digitSequence via a synthetic
		// onNumber call, and rely on Step E's post-filter (ComposingWord.matches) to drop
		// candidates that violate the position lock.
		final int digit = digitFor(c);
		if (digit < 0) {
			composingWord.removeLast();
			return onText(letter, false);
		}

		suppressComposingDigitAppend = true;
		try {
			final String[] surrounding = textField.getSurroundingStringForAutoAssistance(settings, mInputMode);
			if (!mInputMode.onNumber(digit, false, 0, surrounding)) {
				composingWord.removeLast();
				return onText(letter, false);
			}
		} finally {
			suppressComposingDigitAppend = false;
		}

		getSuggestions(Math.random(), null, null);
		return true;
	}


	/**
	 * Return the T9 digit (2..9) that produces [c] in the active language, or -1 if [c] is
	 * not a letter on the keypad. Wraps {@link io.github.sspanak.tt9.languages.Language#getDigitSequenceForWord}
	 * for the single-char case.
	 */
	private int digitFor(char c) {
		if (mLanguage == null) return -1;
		try {
			final String seq = mLanguage.getDigitSequenceForWord(String.valueOf(c));
			if (seq.isEmpty()) return -1;
			final char d = seq.charAt(0);
			if (d < '0' || d > '9') return -1;
			return d - '0';
		} catch (Exception e) {
			return -1;
		}
	}


	/**
	 * Called when the user taps backspace while on the QWERTY layout. Walks tt9's usual
	 * backspace pipeline; {@link #onBackspace} already trims the composing buffer for us, so
	 * we don't double-remove here.
	 */
	public boolean onQwertyBackspace() {
		return onBackspace(0);
	}


	/**
	 * Currently locked QWERTY-tapped prefix of the in-progress word, if any. Used by the glide
	 * pipeline so candidates are filtered to words starting with the prefix and matching is done
	 * against the suffix only (the user's gesture starts at the letter after the prefix).
	 */
	@NonNull
	public String getLockedPrefix() {
		return composingWord.getLockedPrefix();
	}


	/**
	 * Called by the glide container at the FIRST point of every new gesture. Commits any
	 * pending suggestion (from a previous, completed gesture) with a trailing space — so
	 * word-after-word swiping produces "hello world", not "hellohello" or "helloworld".
	 * Safe to call when there's nothing pending: it's a no-op.
	 */
	public void onGlideGestureStarted() {
		if (suggestionOps.isEmpty()) return;
		final String lastWord = suggestionOps.acceptIncomplete();
		if (lastWord == null || lastWord.isEmpty()) return;
		mInputMode.onAcceptSuggestion(lastWord);
		if (mLanguage != null && new Text(lastWord).isAlphabetic()) {
			textField.setText(Characters.getSpace(mLanguage));
			final String lowerLast = lastWord.toLowerCase();
			Tt9WordProvider.bumpFrequency(mLanguage.getId(), lowerLast);
			Tt9WordProvider.noteCommittedWord(mLanguage.getId(), lowerLast);
			// Confused-pair learner: if the user just rejected a different word in the recent
			// past (same language), interpret this commit as "this is what I meant instead."
			// Record the pair so the classifier swaps them whenever both surface together.
			if (!lastRejectedGlideWord.isEmpty()
					&& lastRejectionLangId == mLanguage.getId()
					&& !lastRejectedGlideWord.equals(lowerLast)
					&& System.currentTimeMillis() - lastRejectionTimeMs < CONFUSED_PAIR_WINDOW_MS) {
				Tt9WordProvider.recordConfusedPair(mLanguage.getId(), lastRejectedGlideWord, lowerLast);
			}
			// Whether or not a pair was recorded, the rejection arm is now consumed.
			lastRejectedGlideWord = "";
			lastRejectionTimeMs = 0L;
			lastRejectionLangId = -1;
			// Arm the rejection-window: if the user backspaces within REJECTION_WINDOW_MS,
			// this commit was wrong and we'll penalize lastWord's frequency. Capture the langId
			// so a language switch before backspace doesn't mis-penalize a different language.
			lastGlideCommittedWord = lowerLast;
			lastGlideCommitTimeMs = System.currentTimeMillis();
			lastGlideCommittedLangId = mLanguage.getId();
		}
		composingWord.clear();
	}


	/**
	 * MindReader's current next-word predictions, lowercased and deduped — passed to the glide
	 * classifier so contextually-likely words get a score boost. Empty set when MindReader has
	 * no usable context (start of input, no recent words).
	 */
	@NonNull
	public java.util.Set<String> getGlideContextWords() {
		final java.util.ArrayList<String> guesses = mindReader.getGuesses();
		if (guesses == null || guesses.isEmpty()) return java.util.Collections.emptySet();
		final java.util.HashSet<String> out = new java.util.HashSet<>(guesses.size());
		for (String g : guesses) {
			if (g != null && !g.isEmpty()) out.add(g.toLowerCase());
		}
		return out;
	}


	/**
	 * True if [key] is a letter-bearing T9 digit (2..9 in the standard T9 convention). Used to
	 * decide whether a digit press should grow the shared composing buffer.
	 */
	private static boolean isWordDigit(int key) {
		return key >= 2 && key <= 9;
	}


	/**
	 * Step E: drop suggestions that violate a non-contiguous lock in the shared composing buffer.
	 *
	 * The leading contiguous lock is already enforced by {@code setWordStem}; this filter only
	 * runs when there are locks PAST an ambiguous T9 digit (the QWERTY-mid-T9 case), where stem
	 * filtering can't express the constraint.
	 *
	 * No-op when the buffer is empty or only has a contiguous prefix.
	 */
	@Nullable
	protected ArrayList<String> filterByComposingWord(@Nullable ArrayList<String> suggestions) {
		if (suggestions == null || suggestions.isEmpty()) return suggestions;
		if (composingWord.isEmpty() || !composingWord.hasNonContiguousLocks()) return suggestions;

		final ArrayList<String> filtered = new ArrayList<>(suggestions.size());
		for (String s : suggestions) {
			if (composingWord.matches(s)) filtered.add(s);
		}
		// If the filter empties the list, fall back to the unfiltered list rather than show
		// nothing — better UX than "no suggestions" when the user has been typing.
		return filtered.isEmpty() ? suggestions : filtered;
	}


	/**
	 * Stronger filter for glide candidates: enforces BOTH locked-position matches AND
	 * ambiguous-position-must-match-T9-digit. The T9 lookup path doesn't need this because the
	 * DB query already keys on (digitSequence, stem); glide goes through a shape-matching
	 * classifier that's blind to the digit sequence.
	 *
	 * Example: composingWord = [ambig, ambig, locked('h')], digitSequence = "23h's-key-here".
	 * A glide candidate "xyz...h..." with x at position 0 must be a 2-key letter, y at position 1
	 * a 3-key letter, and z (or 'h') at position 2 must equal 'h'.
	 */
	@NonNull
	protected ArrayList<String> filterGlideByComposingState(@NonNull java.util.List<String> suggestions) {
		final ArrayList<String> in = new ArrayList<>(suggestions);
		if (in.isEmpty() || composingWord.isEmpty()) return in;

		final String digits = mInputMode.getDigitSequence();
		final boolean needsAmbigCheck = digits.length() > 0 && hasAmbiguousTokens();
		final boolean needsLockCheck = composingWord.hasNonContiguousLocks();
		if (!needsAmbigCheck && !needsLockCheck) return in;

		final ArrayList<String> filtered = new ArrayList<>(in.size());
		for (String w : in) {
			if (!composingWord.matches(w)) continue;
			if (needsAmbigCheck && !ambiguousPositionsMatchDigits(w, digits)) continue;
			filtered.add(w);
		}
		return filtered.isEmpty() ? in : filtered;
	}


	private boolean hasAmbiguousTokens() {
		for (int i = 0; i < composingWord.size(); i++) {
			if (composingWord.isPositionAmbiguous(i)) return true;
		}
		return false;
	}


	private boolean ambiguousPositionsMatchDigits(@NonNull String word, @NonNull String digits) {
		if (mLanguage == null) return true;
		final String lower = word.toLowerCase();
		final int len = Math.min(Math.min(lower.length(), composingWord.size()), digits.length());
		for (int i = 0; i < len; i++) {
			if (!composingWord.isPositionAmbiguous(i)) continue;
			final char d = digits.charAt(i);
			if (d < '0' || d > '9') continue;
			final ArrayList<String> keyChars = mLanguage.getKeyCharacters(d - '0');
			if (keyChars == null || keyChars.isEmpty()) continue;
			if (!keyChars.contains(String.valueOf(lower.charAt(i)))) return false;
		}
		return true;
	}


	/**
	 * Entry point for completed glide gestures. Receives the classifier's top-N candidates and
	 * routes them through the same suggestion strip the T9/QWERTY-tap paths use, so the user can
	 * scroll/pick alternatives instead of being force-committed to the top-1.
	 *
	 * Reordering: any candidate that also appears in MindReader's current next-word guess list
	 * (bigram/context prior) is moved to the front while preserving its relative order. Cheap
	 * because glide commits are rare.
	 *
	 * Empty list: no-op (gesture was unmatchable; leave the field untouched).
	 */
	/**
	 * Step F: live in-gesture suggestions. Called from the glide container on every throttled
	 * mid-gesture tick. Lightweight: just refreshes the suggestion strip — no composing-text
	 * update, no ComposingWord mutation, no ModeWords stem change. The strip stabilizes on
	 * gesture lift-off when {@link #onGlideSuggestions} runs.
	 */
	public boolean onGlideMidSuggestions(@Nullable java.util.List<String> rawWords) {
		if (rawWords == null || rawWords.isEmpty()) return false;
		if (InputModeKind.isPassthrough(mInputMode)) return false;
		// Apply the same composing-state filter as the final-gesture path so live candidates
		// can't violate a position lock or a pending T9 digit. Apply the active text case so the
		// strip preview reads correctly when shift / caps is in play.
		final ArrayList<String> filtered = filterGlideByComposingState(rawWords);
		if (filtered.isEmpty()) return false;
		suggestionOps.cancelDelayedAccept();
		if (mLanguage != null) suggestionOps.setTextCase(mLanguage, mInputMode.getTextCase());
		suggestionOps.set(filtered, 0, false);
		// Live composing-text preview: the user reads the field while their finger is in motion,
		// so anchor it to the current top candidate. Replaced on each tick; the final list
		// overwrites this when the finger lifts.
		appHacks.setComposingText(filtered.get(0));
		return true;
	}


	public boolean onGlideSuggestions(@Nullable java.util.List<String> rawWords) {
		io.github.sspanak.tt9.util.Logger.d(
			"tt9/Glide",
			"onGlideSuggestions received: raw=" + rawWords + " passthrough=" + InputModeKind.isPassthrough(mInputMode)
		);
		if (rawWords == null || rawWords.isEmpty()) return false;
		if (InputModeKind.isPassthrough(mInputMode)) return false;

		// Removed: auto-commit-previous-gesture logic.
		// It was meant to add spaces between word-after-word glide commits, but the mid-gesture
		// preview also populates suggestionOps + composing text from the SAME gesture. The
		// auto-commit couldn't tell "previous gesture's suggestion" from "this gesture's
		// in-progress preview" and would commit the preview right before re-setting it,
		// producing "hellohello" from a single swipe. Spaces between words should come from
		// the user's explicit tap on a suggestion / press space — same as T9.

		// Enforce position locks AND ambiguous-digit constraints first — context reordering
		// shouldn't be able to resurrect a candidate that violates what the user has already
		// typed via T9 / QWERTY tap.
		final java.util.List<String> constrained = filterGlideByComposingState(rawWords);
		if (constrained.isEmpty()) return false;

		final java.util.ArrayList<String> ranked = new java.util.ArrayList<>(constrained.size());
		final java.util.ArrayList<String> contextWords = mindReader.getGuesses();
		if (contextWords != null && !contextWords.isEmpty()) {
			final java.util.HashSet<String> contextSet = new java.util.HashSet<>(contextWords.size());
			for (String g : contextWords) contextSet.add(g.toLowerCase());
			for (String w : constrained) if (contextSet.contains(w.toLowerCase())) ranked.add(w);
			for (String w : constrained) if (!contextSet.contains(w.toLowerCase())) ranked.add(w);
		} else {
			ranked.addAll(constrained);
		}

		suggestionOps.cancelDelayedAccept();
		if (mLanguage != null) suggestionOps.setTextCase(mLanguage, mInputMode.getTextCase());
		suggestionOps.set(ranked, 0, false);

		// Show the top candidate as composing text so it's visible in the field before the user
		// presses OK / a continuation key. No locked-prefix highlight — the prefix (if any) is
		// already in the text field via QWERTY taps.
		final String top = ranked.get(0);
		appHacks.setComposingText(top);

		// Step D: treat the top glide candidate as a tentative in-progress word. Repopulate the
		// shared composing buffer with its letters as locked tokens and sync ModeWords' stem to
		// it. That way a follow-up QWERTY tap extends the candidate ("hello" + tap "s" -> stem
		// "hellos"), a follow-up T9 digit appends ambiguously, and OK/space commits via the
		// normal path. If the user picks a different candidate via scroll, scrollSuggestions
		// re-syncs (see below).
		composingWord.clear();
		for (int i = 0; i < top.length(); i++) {
			composingWord.appendLockedLetter(top.charAt(i));
		}
		// setWordStem can throw / refuse if the candidate has chars the language doesn't recognise
		// (rare in practice — glide only emits words from the language's dictionary). Failure
		// just leaves stem unchanged; the strip is still set and OK still works.
		try {
			mInputMode.setWordStem(top, true);
		} catch (Exception ignored) {}

		forceShowWindow();
		return true;
	}


	@Override
	public boolean onBackspace(int repeat) {
		// Dialer fields seem to handle backspace on their own and we must ignore it,
		// otherwise, keyDown race condition occur for all keys.
		if (InputModeKind.isPassthrough(mInputMode)) {
			return false;
		}
		// Wrong-result penalty: if the user is backspacing within REJECTION_WINDOW_MS of a
		// glide-committed word, that commit was almost certainly wrong. Penalize the word's
		// glide-frequency so subsequent similar gestures don't pick it again. Single-fire per
		// commit (the arm is cleared after firing). Gated on language match so a switch between
		// commit and backspace doesn't misapply the penalty.
		if (!lastGlideCommittedWord.isEmpty()
				&& mLanguage != null
				&& lastGlideCommittedLangId == mLanguage.getId()
				&& System.currentTimeMillis() - lastGlideCommitTimeMs < REJECTION_WINDOW_MS) {
			Tt9WordProvider.penalizeFrequency(mLanguage.getId(), lastGlideCommittedWord);
			// Arm the confused-pair learner: if the user glide-accepts a DIFFERENT word in the
			// next CONFUSED_PAIR_WINDOW_MS (same language), we'll record (this → that) as the
			// preferred swap and trigger the ranker's final swap pass next time both surface.
			lastRejectedGlideWord = lastGlideCommittedWord;
			lastRejectionTimeMs = System.currentTimeMillis();
			lastRejectionLangId = mLanguage.getId();
			clearGlideRejectionArm();
		}

		// Keep the shared composing buffer in lockstep with ModeWords' digitSequence.
		// No-op when the QWERTY layout never populated it.
		composingWord.removeLast();

		mindReader.clearContext();

		if (appHacks.onBackspace(settings, mInputMode)) {
			mInputMode.reset();
			mainView.renderDynamicKeys();
			return false;
		}

		suggestionOps.cancelDelayedAccept();
		resetKeyRepeat();

		if (settings.getBackspaceAcceleration() && repeat > 0 && repeat % SettingsStore.BACKSPACE_ACCELERATION_REPEAT_DEBOUNCE != 0) {
			return true;
		}

		mInputMode.beforeDeleteText();

		// load new words only if there is no selected text, because it would be confusing
		if (repeat == 0 && mInputMode.onBackspace() && textSelection.isEmpty()) {
			final Runnable onLoad = InputModeKind.isRecomposing(mInputMode) ? null : () -> recompose(repeat, false);
			getSuggestions(0, null, onLoad);
		} else {
			suggestionOps.commitCurrent(false, true);
			mInputMode.reset();
			deleteText(settings.getBackspaceAcceleration() && repeat > 0);
			updateShiftStateDebounced(null, mInputMode.noSuggestions(), false); // backspace may change the text too much, so no beforeCursor cache for now
			recompose(repeat, !textSelection.isEmpty());
		}

		return true;
	}


	/**
	 * onNumber
	 *
	 * @param key     Must be a number from 1 to 9, not a "KeyEvent.KEYCODE_X"
	 * @param hold    If "true" we are calling the handler, because the key is being held.
	 * @param repeat  If "true" we are calling the handler, because the key was pressed more than once
	 * @return boolean
	 */
	protected boolean onNumber(int key, boolean hold, int repeat) {
		suggestionOps.cancelDelayedAccept();
		// User typed something after a glide commit — they're moving on, not rejecting. Drop
		// the rejection arm so a later backspace doesn't penalize the prior glide word.
		clearGlideRejectionArm();

		hold = hold && settings.getHoldToType();
		String[] surroundingChars = textField.getSurroundingStringForAutoAssistance(settings, mInputMode);
		String lastWord = null;

		// Automatically accept the previous word, when the next one is a space or punctuation,
		// instead of requiring "OK" before that.
		// First pass, analyze the incoming key press and decide whether it could be the start of
		// a new word. In case we do accept it, we preserve the suggestion list instead of clearing,
		// to prevent flashing while the next suggestions are being loaded.

		if (mInputMode.shouldAcceptPreviousSuggestion(suggestionOps.getCurrent(), key, hold) || shouldAcceptGuessesOnNumber(key)) {
			// WARNING! Ensure the code after "acceptIncompleteAndKeepList()" does not depend on
			// the suggestions in SuggestionOps, since we don't clear that list.
			lastWord = suggestionOps.acceptIncompleteAndKeepList();
			mInputMode.onAcceptSuggestion(lastWord);
			surroundingChars = autoCorrectSpace(lastWord, surroundingChars, false, key);
			mindReader.setContext(mInputMode, mLanguage, surroundingChars, lastWord);
		}

		// Auto-adjust the text case before each word/char, if the InputMode supports it.
		mInputMode.determineNextWordTextCase(surroundingChars[0], key);

		if (!mInputMode.onNumber(key, hold, repeat, surroundingChars)) {
			forceShowWindow();
			return false;
		}

		// Step A: keep the shared composing buffer in sync with T9 digit presses, so subsequent
		// QWERTY taps / glide gestures can reason about the in-progress word. Only matters when
		// the on-screen QWERTY is active — physical-keypad-only sessions never read the buffer.
		// Skipped when called synthetically from onQwertyLetter (a locked letter was already
		// appended for this same digit position).
		if (!suppressComposingDigitAppend
			&& !settings.isMainLayoutStealth()
			&& InputModeKind.isPredictive(mInputMode)
			&& isWordDigit(key)) {
			composingWord.appendAmbiguousDigit();
		}

		if (mInputMode.shouldSelectNextSuggestion() && !mInputMode.noSuggestions()) {
			scrollSuggestions(false);
			suggestionOps.scheduleDelayedAccept(mInputMode.getAutoAcceptTimeout());
		} else {
			final double loadingId = Math.random();
			autoCompleteOnNumber(loadingId, surroundingChars, lastWord, key);
			getSuggestions(loadingId, null, null);
		}

		return true;
	}


	public boolean onText(String text, boolean validateOnly) {
		if (mInputMode.shouldIgnoreText(text)) {
			return false;
		}

		if (validateOnly) {
			return true;
		}

		suggestionOps.cancelDelayedAccept();
		// Text input after a glide commit means the user moved on, not rejected. Drop the arm.
		clearGlideRejectionArm();

		// Smart-period: double-tapping space at the end of a word should commit ". " (period
		// + space) instead of a redundant second space. Standard Gboard behavior. Only fires
		// when the char immediately before the cursor is a space AND the char before THAT is
		// alphanumeric — guards against firing inside code/URL contexts or when the user
		// genuinely wants a double-space.
		if (" ".equals(text) && mLanguage != null) {
			final String beforeCursor = textField.getStringBeforeCursor(2);
			if (beforeCursor != null && beforeCursor.length() == 2
					&& beforeCursor.charAt(1) == ' '
					&& Character.isLetterOrDigit(beforeCursor.charAt(0))) {
				textField.deleteChars(mLanguage, 1);
				text = ". ";
			}
		}

		String[] surroundingChars;

		// accept the previously typed word (if any)
		String lastWord = suggestionOps.acceptIncomplete();
		if (lastWord.isEmpty()) {
			surroundingChars = textField.getSurroundingStringForAutoAssistance(settings, mInputMode);
		} else {
			mInputMode.onAcceptSuggestion(lastWord);
			surroundingChars = autoCorrectSpace(
				lastWord,
				textField.getSurroundingStringForAutoAssistance(settings, mInputMode),
				false,
				-1
			);
		}

		mindReader.setContext(mInputMode, mLanguage, surroundingChars, text);

		// "type" and accept the new word
		mInputMode.onAcceptSuggestion(text);
		textField.setText(text);
		surroundingChars[0] += text;
		surroundingChars = autoCorrectSpace(text, surroundingChars, true, -1);

		if (surroundingChars[0].endsWith(Characters.getSpace(mLanguage))) {
			waitForSpaceTrimKey();
		}

		forceShowWindow();

		mInputMode.determineNextWordTextCase(surroundingChars[0], -1);
		updateShiftState(surroundingChars[0], false, false);

		// A whole word went to the text field — whatever was in the shared composing buffer is
		// now stale.
		composingWord.clear();

		// Keep glide's in-memory per-user frequency map in sync without an SQLite reload. Disk
		// frequencies are still incremented by the T9 acceptance path; this just nudges the
		// glide-side ranking so words the user just chose are more likely to win next time.
		// Also feeds the personal-dictionary auto-add — Tt9WordProvider tracks unrecognized
		// words and promotes them after 3 commits.
		if (mLanguage != null && text != null && !text.isEmpty() && new Text(text).isAlphabetic()) {
			final String lowered = text.toLowerCase();
			Tt9WordProvider.bumpFrequency(mLanguage.getId(), lowered);
			Tt9WordProvider.noteCommittedWord(mLanguage.getId(), lowered);
		}

		// Gboard-style next-word predictions: after a word commits (space, punctuation, or text
		// paste boundary), surface MindReader's bigram-based guesses in the strip immediately so
		// the user can tap one without typing the first letter. Previously fired only from the
		// strip-tap acceptance path, leaving the strip empty after space-committed words.
		final String nextWordContext;
		if (!lastWord.isEmpty()) {
			nextWordContext = lastWord;
		} else if (text != null && !text.isEmpty() && new Text(text).isAlphabetic()) {
			nextWordContext = text;
		} else {
			nextWordContext = "";
		}
		if (!nextWordContext.isEmpty()) {
			guessNextWord(surroundingChars, nextWordContext);
		}

		return true;
	}


	@NonNull
	protected String[] autoCorrectSpace(@Nullable String currentWord, @NonNull String[] surroundingChars, boolean isWordAcceptedManually, int nextKey) {
		if (currentWord == null || currentWord.isEmpty() || !settings.isAutoAssistanceOn(mInputMode)) {
			return surroundingChars;
		}

		String previousChars = surroundingChars[0];
		final String nextChars = surroundingChars[1];

		if (!inputType.isRustDesk() && mInputMode.shouldDeletePrecedingSpace(previousChars)) {
			textField.deletePrecedingSpace(currentWord);
			if (previousChars.endsWith(" " + currentWord) && previousChars.length() > currentWord.length()) {
				final int precedingSpace = previousChars.length() - currentWord.length() - 1;
				previousChars = previousChars.substring(0, precedingSpace) + currentWord;
			}
		}

		if (mInputMode.shouldAddPrecedingSpace(previousChars)) {
			textField.addPrecedingSpace(currentWord);
			if (previousChars.endsWith(currentWord)) {
				final int startOfWord = previousChars.length() - currentWord.length();
				previousChars = previousChars.substring(0, startOfWord) + " " + currentWord;
			}
		}

		if (mInputMode.shouldAddTrailingSpace(previousChars, nextChars, isWordAcceptedManually, nextKey)) {
			textField.setText(" ");
			previousChars += " ";
		}

		return new String[] { previousChars, nextChars };
	}


	private void deleteText(boolean deleteMany) {
		int charsToDelete = 1;

		if (!textSelection.isEmpty()) {
			charsToDelete = textSelection.length();
			textSelection.clear(false);
		} else if (deleteMany) {
			charsToDelete = textField.getComposingText().length();
			charsToDelete = charsToDelete > 0 ? charsToDelete : Math.max(textField.getPaddedWordBeforeCursorLength(), 1);
		}

		textField.deleteChars(mLanguage, charsToDelete);
	}


	/**
	 * determineLanguage
	 * Restore the last language or auto-select a more appropriate one, if the application hints so.
	 * In case the settings are not valid, we will fallback to the default language.
	 */
	private boolean determineLanguage() {
		mEnabledLanguages = settings.getEnabledLanguageIds();

		int oldLang = mLanguage != null ? mLanguage.getId() : -1;
		mLanguage = LanguageCollection.getLanguage(settings.getInputLanguage());
		validateLanguages();

		Language appLanguage = textField.getLanguage(mEnabledLanguages);
		if (appLanguage != null) {
			mLanguage = appLanguage;
		}

		// Warm Tt9WordProvider's vocabulary HashMap as soon as we know the language. Fuzzy
		// typo-correction in WordPredictions reads it via Tt9WordProvider.containsWord; the
		// alternative was waiting for the QWERTY swipe container to bindLanguage, which only
		// fires after the keyboard view inflates — by then the user has already typed the
		// first letters and seen empty/T9-shaped suggestions. Triggering here gets the load
		// running while the IME is still wiring up.
		//
		// Also attach the application Context so Tt9WordProvider can persist its learning
		// (sessionBoost / sessionPenalty / confusedPairs) across IME process kills. Without
		// this, every restart would wipe per-user learning.
		if (mLanguage != null && mLanguage.getId() > 0) {
			Tt9WordProvider.attachContext(getApplicationContext());
			Tt9WordProvider.preload(mLanguage);
		}

		return oldLang != mLanguage.getId();
	}


	/**
	 * determineTextCase
	 * Restore the last used text case or auto-select a new one based on the input field properties.
	 */
	protected void determineTextCase() {
		InputModeValidator.validateTextCase(mInputMode, settings.getTextCase());
	}


	/**
	 * determineInputModeId
	 * Return the last input mode ID or choose a more appropriate one.
	 * Some input fields support only numbers or are not suited for predictions (e.g. password fields).
	 * Others do not support text retrieval or composing text, or the AppHacks detected them as incompatible with us.
	 * We do not want to handle any of these, hence we pass through all input to the system.
	 */
	protected int determineInputModeId() {
		if (!inputType.isValid() || (inputType.isLimited() && !inputType.isTeams() && !inputType.isTermux())) {
			return InputMode.MODE_PASSTHROUGH;
		}

		allowedInputModes = new ArrayList<>(inputType.determineInputModes(getApplicationContext()));
		if (LanguageKind.isJapanese(mLanguage)) {
			determineJapaneseInputModes();
		}

		if (!mLanguage.hasABC()) {
			allowedInputModes.remove((Integer) InputMode.MODE_ABC);
		}

		if (!settings.getPredictiveMode()) {
			allowedInputModes.remove((Integer) InputMode.MODE_PREDICTIVE);
		}

		// QWERTY layout: there's no T9-style mode cycling exposed (the bottom-left key is the
		// ?123/ABC symbols toggle), and the user expects Gboard-shaped behavior — letter-prefix
		// suggestions always on. Force Predictive whenever it's an option for the active language.
		// Falls through to the normal validator if Predictive is genuinely unavailable (e.g. the
		// language has no dictionary, or the input field disallowed it).
		if (settings.isMainLayoutQwerty()
				&& settings.getPredictiveMode()
				&& mLanguage.hasABC()
				&& allowedInputModes.contains((Integer) InputMode.MODE_PREDICTIVE)) {
			return InputMode.MODE_PREDICTIVE;
		}

		return InputModeValidator.validateMode(settings.getInputMode(), allowedInputModes);
	}


	/**
	 * In Japanese, Hiragana and Katakana modes are the equivalents of ABC mode in other languages.
	 * So when typing letters is possible (ABC mode allowed), we replace ABC with these two modes.
	 */
	private void determineJapaneseInputModes() {
		if (allowedInputModes.contains(InputMode.MODE_ABC)) {
			allowedInputModes.add(InputMode.MODE_HIRAGANA);
			allowedInputModes.add(InputMode.MODE_KATAKANA);
		}
	}


	/**
	 * determineInputMode
	 * Same as determineInputModeId(), but returns an actual InputMode.
	 */
	protected InputMode determineInputMode() {
		return InputMode.getInstance(settings, mLanguage, inputType, textField, determineInputModeId());
	}


	/**
	 * Try to recompose the current word after a backspace operation. If successful, load new
	 * suggestions. Otherwise, reset the InputMode.
	 */
	private void recompose(int backspaceRepeat, boolean isTextSelected) {
		if (!settings.getBackspaceRecomposing() || backspaceRepeat > 0 || isFnPanelVisible() || isTextSelected || !suggestionOps.isEmpty() || DictionaryLoader.isRunning()) {
			return;
		}

		final String previousWord = mInputMode.recompose();
		if (textField.recompose(previousWord)) {
			getSuggestions(0, previousWord, null);
		} else {
			mInputMode.reset();
		}
	}


	@Override
	public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd) {
//		Logger.d("onUpdateSelection", "old (" + oldSelStart + ", " + oldSelEnd + ") => new (" + newSelStart + ", " + newSelEnd + "); candidates = (" + candidatesStart + ", " + candidatesEnd + ")");

		super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
		textSelection.onSelectionUpdate(newSelStart, newSelEnd);

		// in case the app has modified the InputField and moved the cursor without notifying us...
		if (CursorOps.isInputReset(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)) {
			stopWaitingForSpaceTrimKey();
			mindReader.clearContext();
			if (!appHacks.acceptComposingTextOnCursorReset(mInputMode, suggestionOps, textField)) {
				suggestionOps.clear();
			}
			return;
		}

		// If the cursor moves while composing a word (usually, because the user has touched the screen outside the word), we must
		// end typing end accept the word. Otherwise, the cursor would jump back at the end of the word, after the next key press.
		// This is confusing from user perspective, so we want to avoid it.
		if (CursorOps.isMovedWhileTyping(newSelStart, newSelEnd, candidatesStart, candidatesEnd)) {
			stopWaitingForSpaceTrimKey();
			mInputMode.onCursorMove(suggestionOps.acceptIncomplete());
			mindReader.clearContext();
			return;
		}

		// Prevent deleting a space using the left arrow key, if the user has moved the cursor to another
		// location. This prevents undesired deletion of the space, in the middle of the text.
		if (CursorOps.isMovedFar(newSelStart, newSelEnd, oldSelStart, oldSelEnd)) {
			stopWaitingForSpaceTrimKey();
		}
	}


	protected void scrollSuggestions(boolean backward) {
		suggestionOps.cancelDelayedAccept();
		// User is picking a different candidate — the prior glide commit is being replaced, not
		// rejected. Drop the rejection arm so a later backspace doesn't penalize the wrong word.
		clearGlideRejectionArm();
		suggestionOps.scrollTo(backward ? -1 : 1);
		final String picked = suggestionOps.getCurrent();
		// Step D: if the shared buffer is all-locked-and-contiguous, the user was picking among
		// glide candidates (or among scroll-equivalent candidates). Resync the buffer so a
		// follow-up QWERTY tap / T9 digit extends THIS candidate, not the old top.
		if (!composingWord.isEmpty()
			&& composingWord.size() == composingWord.getLockedPrefix().length()
			&& picked != null && !picked.isEmpty()) {
			composingWord.clear();
			for (int i = 0; i < picked.length(); i++) {
				composingWord.appendLockedLetter(picked.charAt(i));
			}
		}
		mInputMode.setWordStem(picked, true);
		if (InputModeKind.isRecomposing(mInputMode)) {
			appHacks.setComposingTextPartsWithHighlightedJoining(mInputMode.getWordStem() + suggestionOps.getCurrent(), mInputMode.getRecomposingSuffix());
		} else {
			appHacks.setComposingTextWithHighlightedStem(suggestionOps.getCurrent(), mInputMode.getWordStem(), mInputMode.isStemFilterFuzzy());
		}
	}


	protected void updateShiftStateDebounced(@Nullable String beforeCursor, boolean determineTextCase, boolean onlyWhenLetters) {
		if (shiftStateDebounceHandler == null) {
			shiftStateDebounceHandler = new Handler(Looper.getMainLooper());
		} else {
			shiftStateDebounceHandler.removeCallbacksAndMessages(null);
		}
		shiftStateDebounceHandler.postDelayed(() -> updateShiftState(beforeCursor, determineTextCase, onlyWhenLetters), SettingsStore.SHIFT_STATE_DEBOUNCE_TIME);
	}


	protected void updateShiftState(@Nullable String beforeCursor, boolean determineTextCase, boolean onlyWhenLetters) {
		if (onlyWhenLetters && !new Text(suggestionOps.getCurrent()).isAlphabetic()) {
			return;
		}

		if (determineTextCase) {
			beforeCursor = beforeCursor != null ? beforeCursor : textField.getStringBeforeCursor();
			mInputMode.determineNextWordTextCase(beforeCursor, -1);
		}

		getDisplayTextCase(mLanguage, mInputMode.getTextCase());
		setStatusIcon(mInputMode, mLanguage);
		mainView.renderDynamicKeys();
		if (!mainView.isTextEditingPaletteShown() && !mainView.isCommandPaletteShown()) {
			statusBar.setText(mInputMode);
		}
	}
}
