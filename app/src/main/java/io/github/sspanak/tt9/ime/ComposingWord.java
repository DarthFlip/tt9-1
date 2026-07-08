package io.github.sspanak.tt9.ime;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Position-indexed composing buffer used to bridge physical T9 input and on-screen QWERTY input.
 *
 * Each token is either a locked letter (typed/swiped on QWERTY — the character is known) or an
 * ambiguous digit (tapped on the physical keypad — could be any of the letters on that key).
 * The buffer sits above {@code ModeWords}: letters locked here are fed back as a stem prefix when
 * they form a contiguous run from position 0, so tt9's existing prediction engine filters on them
 * for free. Non-contiguous locks (letter → digit → letter) are applied as a post-filter via
 * {@link #matches(String)} over the suggestion list.
 *
 * The buffer is only populated while the on-screen QWERTY layout is active. Physical-keypad-only
 * flows never touch it.
 */
public class ComposingWord {
	private final List<Token> tokens = new ArrayList<>();

	public void clear() {
		tokens.clear();
	}

	public boolean isEmpty() {
		return tokens.isEmpty();
	}

	public int size() {
		return tokens.size();
	}

	public void appendLockedLetter(char letter) {
		tokens.add(new Token(Character.toLowerCase(letter), true));
	}

	public void appendAmbiguousDigit() {
		tokens.add(new Token('\0', false));
	}

	public void removeLast() {
		if (!tokens.isEmpty()) tokens.remove(tokens.size() - 1);
	}

	/**
	 * Longest contiguous run of locked letters starting at position 0. Suitable for feeding into
	 * {@code ModeWords.setWordStem}, since tt9's stem filter only supports prefix-locking.
	 */
	@NonNull
	public String getLockedPrefix() {
		StringBuilder sb = new StringBuilder();
		for (Token t : tokens) {
			if (!t.locked) break;
			sb.append(t.letter);
		}
		return sb.toString();
	}

	/**
	 * True if the token at [position] is an ambiguous T9 digit (the user pressed a T9 key but
	 * hasn't committed to a specific letter). Out-of-range positions return false.
	 */
	public boolean isPositionAmbiguous(int position) {
		if (position < 0 || position >= tokens.size()) return false;
		return !tokens.get(position).locked;
	}


	/**
	 * True if any locks exist past the leading contiguous prefix. When true, callers should
	 * post-filter tt9's suggestions via {@link #matches(String)} because the stem alone cannot
	 * express the trailing locks.
	 */
	public boolean hasNonContiguousLocks() {
		boolean seenDigit = false;
		for (Token t : tokens) {
			if (!t.locked) {
				seenDigit = true;
			} else if (seenDigit) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns true if {@code candidate} is compatible with every locked position in this buffer.
	 * Ambiguous positions accept any letter. Candidates shorter than the buffer are rejected;
	 * candidates longer than the buffer are accepted (they are still valid completions in tt9,
	 * which surfaces longer words as predictions of shorter digit sequences).
	 */
	public boolean matches(@Nullable String candidate) {
		if (candidate == null) return false;
		if (candidate.length() < tokens.size()) return false;
		String lower = candidate.toLowerCase();
		for (int i = 0; i < tokens.size(); i++) {
			Token t = tokens.get(i);
			if (t.locked && lower.charAt(i) != t.letter) return false;
		}
		return true;
	}

	@NonNull
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(tokens.size());
		for (Token t : tokens) sb.append(t.locked ? t.letter : '?');
		return sb.toString();
	}

	private static final class Token {
		final char letter;
		final boolean locked;

		Token(char letter, boolean locked) {
			this.letter = letter;
			this.locked = locked;
		}
	}
}
