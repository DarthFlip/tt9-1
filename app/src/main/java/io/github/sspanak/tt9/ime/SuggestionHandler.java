package io.github.sspanak.tt9.ime;

import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.db.words.DictionaryLoader;
import io.github.sspanak.tt9.ime.helpers.SuggestionOps;
import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.ime.modes.InputModeKind;
import io.github.sspanak.tt9.ui.UI;
import io.github.sspanak.tt9.util.Text;
import io.github.sspanak.tt9.util.TextTools;
import io.github.sspanak.tt9.util.chars.Characters;
import io.github.sspanak.tt9.util.sys.Clipboard;

abstract public class SuggestionHandler extends TypingHandler {
	@Nullable private Handler suggestionHandler;


	@Override
	protected void setInputField(EditorInfo field) {
		super.setInputField(field);
		mindReader.setInputType(inputType);
	}


	private Handler getAsyncHandler() {
		if (suggestionHandler == null) {
			suggestionHandler = new Handler(Looper.getMainLooper());
		}

		return suggestionHandler;
	}


	@Override
	protected void onFinishTyping() {
		if (suggestionHandler != null) {
			suggestionHandler.removeCallbacksAndMessages(null);
			suggestionHandler = null;
		}

		mindReader.clearContext();
		super.onFinishTyping();
	}


	private String[] onAcceptPreviousSuggestion() {
		final int lastWordLength = InputModeKind.isABC(mInputMode) ? 1 : mInputMode.getSequenceLength() - 1;
		String lastWord = suggestionOps.getCurrent(mLanguage, lastWordLength);
		if (Characters.PLACEHOLDER.equals(lastWord)) {
			lastWord = "";
		}

		suggestionOps.commitCurrent(false, true);
		mInputMode.onAcceptSuggestion(lastWord, true);
		final String[] surroundingText = autoCorrectSpace(
			lastWord,
			textField.getSurroundingStringForAutoAssistance(settings, mInputMode),
			false,
			mInputMode.getFirstKey()
		);
		mInputMode.determineNextWordTextCase(surroundingText[0], -1);
		mindReader.setContext(mInputMode, mLanguage, surroundingText, lastWord);

		return surroundingText;
	}


	@Override
	protected void onAcceptSuggestionsDelayed(String word) {
		onAcceptSuggestionManually(word, -1);
		forceShowWindow();
	}


	protected void onAcceptSuggestionManually(String word, int fromKey) {
		mInputMode.onAcceptSuggestion(word);
		if (Clipboard.contains(word)) {
			Clipboard.copy(this, word);
		}

		if (!word.isEmpty()) {
			String[] surroundingText = autoCorrectSpace(
				word,
				textField.getSurroundingStringForAutoAssistance(settings, mInputMode),
				true,
				fromKey
			);

			mInputMode.determineNextWordTextCase(surroundingText[0], -1);
			updateShiftState(surroundingText[0], false, false);
			resetKeyRepeat();
			guessNextWord(surroundingText, word);
		}

		if (!Characters.getSpace(mLanguage).equals(word)) {
			waitForSpaceTrimKey();
		}
	}


	@NonNull
	@Override
	public SuggestionOps getSuggestionOps() {
		return suggestionOps;
	}


	/**
	 * Ask the InputMode to load suggestions for the current state. No action is taken if the dictionary
	 * is still loading. Note that onComplete is called even if the loading was skipped.
	 */
	@Override
	protected void getSuggestions(double loadingId, @Nullable String currentWord, @Nullable Runnable onComplete) {
		// `activeInputMode` reflects whichever pipeline (QWERTY vs T9) most recently fed
		// input; we read predictions from it so the strip shows the right candidates. Callers
		// set activeInputMode before invoking us.
		if (InputModeKind.isPredictive(activeInputMode) && DictionaryLoader.isRunning()) {
			activeInputMode.reset();
			UI.toastShortSingle(this, R.string.dictionary_loading_please_wait);
			if (onComplete != null) {
				onComplete.run();
			}
		} else {
			activeInputMode
				.setOnSuggestionsUpdated(() -> handleSuggestionsAsync(loadingId, onComplete))
				.loadSuggestions(currentWord == null ? suggestionOps.getCurrent() : currentWord);
		}
	}


	@WorkerThread
	protected void handleSuggestionsAsync() {
		handleSuggestionsAsync(0, null);
	}


	@WorkerThread
	protected void handleSuggestionsAsync(double loadingId, @Nullable Runnable onComplete) {
		final Handler handler = getAsyncHandler();
		handler.removeCallbacksAndMessages(null);
		handler.post(() -> handleSuggestions(loadingId, onComplete));
	}


	@MainThread
	protected void handleSuggestions(double loadingId, @Nullable Runnable onComplete) {
		// Read from `activeInputMode` (set by the entry point that triggered the load), not
		// `mInputMode` directly — so handleSuggestions works for both the QWERTY pipeline
		// (where activeInputMode = qwertyInputMode) and the T9 pipeline (mInputMode).
		final InputMode source = activeInputMode;
		// Second pass, analyze the available suggestions and decide if combining them with the
		// last key press makes up a compound word like: (it)'s, (I)'ve, l'(oiseau), or it is
		// just the end of a sentence, like: "word." or "another?"
		String[] surroundingText = null;
		if (source.shouldAcceptPreviousSuggestion(suggestionOps.getCurrent())) {
			surroundingText = onAcceptPreviousSuggestion();
		}

		// Step E: drop candidates that violate any non-contiguous lock in the shared composing
		// buffer (e.g. user typed T9 "234" then tapped QWERTY "h" — we keep words with 'h' at
		// position 3). No-op when there are no locks past an ambiguous T9 digit.
		final ArrayList<String> suggestions = filterByComposingWord(source.getSuggestions());
		suggestionOps.set(suggestions, source.getRecommendedSuggestionIdx(), source.containsGeneratedSuggestions());

		// either accept the first one automatically (when switching from punctuation to text
		// or vice versa), or schedule auto-accept in N seconds (in ABC mode)
		if (suggestionOps.scheduleDelayedAccept(source.getAutoAcceptTimeout())) {
			if (onComplete != null) {
				onComplete.run();
			}
			return;
		}

		// We have not accepted anything yet, which means the user is composing a word.
		// put the first suggestion in the text field, but cut it off to the length of the sequence
		// (the count of key presses), for a more intuitive experience.
		String trimmedWord;

		if (InputModeKind.isRecomposing(source)) {
			// highlight the current letter, when editing a word
			trimmedWord = source.getWordStem() + suggestionOps.getCurrent();
			appHacks.setComposingTextPartsWithHighlightedJoining(trimmedWord, source.getRecomposingSuffix());
		} else {
			// or highlight the stem, when filtering
			trimmedWord = suggestionOps.getCurrent(mLanguage, source.getSequenceLength());
			appHacks.setComposingTextWithHighlightedStem(trimmedWord, source.getWordStem(), source.isStemFilterFuzzy());
		}

		// append guesses from the MindReader
		if (loadingId != 0 && loadingId == mindReader.getLoadingId()) {
			handleOrWaitForGuesses();
		}

		onAfterSuggestionsHandled(onComplete, surroundingText, trimmedWord, suggestions.isEmpty());
	}


	private void onAfterSuggestionsHandled(@Nullable Runnable callback, @Nullable String[] surroundingText, @Nullable String trimmedWord, boolean noSuggestions) {
		final String shiftStateContext = surroundingText != null ? surroundingText[0] + trimmedWord : trimmedWord;
		if (noSuggestions) {
			updateShiftStateDebounced(shiftStateContext, true, false);
		} else {
			updateShiftStateDebounced(shiftStateContext, false, true);
		}

		forceShowWindow();

		if (callback != null) {
			callback.run();
		}
	}


	@Override
	protected void autoCompleteOnNumber(double loadingId, @NonNull String[] surroundingText, @Nullable String lastWord, int number) {
		if (mLanguage.hasLettersOnAllKeys() || mLanguage.isTranscribed()) {
			return;
		}

		if (mLanguage.hasSpaceBetweenWords()) {
			autoCompleteOnNumberRegularLanguage(loadingId, surroundingText, lastWord, number);
		} else {
			autoCompleteOnNumberNoSpaceLanguage(loadingId, surroundingText, number);
		}
	}


	private void autoCompleteOnNumberNoSpaceLanguage(double loadingId, @NonNull String[] surroundingText, int number) {
		if (mInputMode.getSequenceLength() == 1) {
			autoCompleteWord(loadingId, surroundingText, number);
		}
	}


	private void autoCompleteOnNumberRegularLanguage(double loadingId, @NonNull String[] surroundingText, @Nullable String lastWord, int number) {
		if (mInputMode.getSequenceLength() != 1 || new Text(mLanguage, surroundingText[1]).startsWithWord()) {
			mindReader.clearContext();
			return;
		}

		if (surroundingText[0].isEmpty() || Characters.getSpace(mLanguage).equals(lastWord) || TextTools.endsWithSpace(surroundingText[0])) {
			autoCompleteWord(loadingId, surroundingText, number);
		}
	}


	private void autoCompleteWord(double loadingId, @NonNull String[] surroundingText, int number) {
		mindReader
			.setCurrentGuessHandler(null)
			.setTextCase(mInputMode.getTextCaseRaw())
			.setLanguage(mLanguage)
			.complete(loadingId, mInputMode, surroundingText, number);
	}


	/** Last word given to MindReader's guess() — kept on the worker thread for emoji injection
	 *  in handleGuesses. Reset each call to guessNextWord. */
	private volatile String lastGuessContextWord = "";

	@Override
	protected void guessNextWord(@NonNull String[] surroundingText, @Nullable String lastWord) {
		lastGuessContextWord = lastWord == null ? "" : lastWord;
		mindReader
			.setTextCase(mInputMode.getTextCaseRaw())
			.setLanguage(mLanguage)
			.guess(mInputMode, surroundingText, lastWord, this::handleGuessesAsync);
	}


	@WorkerThread
	private void handleGuessesAsync() {
		final Handler handler = getAsyncHandler();
		handler.removeCallbacks(this::handleGuesses);
		handler.post(this::handleGuesses);
	}


	@MainThread
	private boolean handleGuesses() {
		final ArrayList<String> guesses = mindReader.getGuesses();
		// QWERTY-only: merge seeded next-word candidates from QwertyBigramSeed. This fixes the
		// cold-start problem where MindReader has no learned bigrams yet and the strip stays
		// empty after a space commit on a fresh install. Gated on `activeInputMode ==
		// qwertyInputMode` so T9's next-word rendering stays byte-identical. User-learned
		// guesses always win ties — seed entries are appended after MindReader's.
		if (activeInputMode == qwertyInputMode
				&& mLanguage != null
				&& mLanguage.getCode() != null
				&& lastGuessContextWord != null
				&& !lastGuessContextWord.isEmpty()) {
			final java.util.List<String> seeded =
				io.github.sspanak.tt9.ime.qwerty.QwertyBigramSeed.getNextWordsAfter(mLanguage.getCode(), lastGuessContextWord, 5);
			for (String s : seeded) {
				if (s == null || s.isEmpty()) continue;
				boolean already = false;
				for (String g : guesses) {
					if (g != null && g.equalsIgnoreCase(s)) {
						already = true;
						break;
					}
				}
				if (!already) guesses.add(s);
			}
		}
		// Predictive emoji: when the word just committed has a relevant emoji, inject it at
		// position 1 (NEVER position 0 — the top guess gets written into the composing region
		// via appHacks.setComposingText below, and rendering an emoji there would be weird).
		// Skip if MindReader returned nothing so we don't render an emoji-only strip.
		final String emoji = EmojiPredictions.lookup(lastGuessContextWord);
		if (emoji != null && !guesses.isEmpty() && !guesses.contains(emoji)) {
			guesses.add(1, emoji);
		}
		if (guesses.isEmpty()) {
			return false;
		}

		suggestionOps.cancelDelayedAccept();
		appHacks.setComposingText(guesses.get(0));
		suggestionOps.addGuesses(guesses);

		return true;
	}


	@MainThread
	private void handleOrWaitForGuesses() {
		if (!handleGuesses()) {
			mindReader.setCurrentGuessHandler(this::handleGuessesAsync);
		}
	}


	@Override
	protected boolean shouldAcceptGuessesOnNumber(int number) {
		return number == 0 && !mLanguage.hasLettersOnAllKeys() && suggestionOps.containsOnlyGuesses();
	}
}
