/*
 * Copyright (C) 2026 tt9 contributors. GPL-3.0.
 *
 * CTC beam search over the encoder's [time × (alphabet + blank)] log-emissions, constrained to
 * a lexicon trie. Pure Kotlin — no model dep — so it can be unit-tested with synthetic logits.
 *
 * Post-decode rescoring layers in tt9's cross-mode signals: MindReader context bonus, locked-
 * prefix initialization, and log-frequency from the trie itself.
 *
 * Hyperparameters mirror scoring.json["encoder:honorable_sturgeon"] from FUTO. We accept them
 * verbatim — they were tuned on a held-out validation set FUTO ran (Optuna, 3k trials per paper).
 */
package io.github.sspanak.tt9.ime.swipe

import kotlin.math.ln
import kotlin.math.min

/**
 * @param keyToChar maps the model's per-timestep argmax index `[0, numKeys)` to the lowercase
 *                  char of the corresponding key (e.g. layout[i] → keyCenters[i] → which letter).
 *                  Index `numKeys` is the CTC blank — handled internally.
 */
class BeamSearch(
	private val beamWidth: Int = 50,
	private val gammaFrequency: Float = 0.4056f, // scoring.json gamma (frequency weight)
	private val lambdaContext: Float = 0.0176f, // intentionally tiny; we override with our own bonus
	private val contextBonus: Float = 1.5f, // additive log-bonus for MindReader hits
) {

	/**
	 * Decode [logEmissions] (shape `[T × (numKeys + 1)]`, channels-last, log-space) into the top
	 * [maxResults] words.
	 *
	 * @param logEmissions raw model output; column index `numKeys` is the CTC blank.
	 * @param numKeys length of the actual key alphabet (FUTO's encoder = 64).
	 * @param keyToChar index → lowercase char for each key in the layout (size == numKeys).
	 *                  Unset entries (padded slots) should map to `0.toChar()`.
	 * @param trie lexicon trie built from the active language's word list.
	 * @param lockedPrefix locked QWERTY prefix from the IME (already lowercased). Empty for a
	 *                     full-word swipe; non-empty when continuing after QWERTY taps.
	 * @param contextWords MindReader's next-word predictions for the current cursor position.
	 *                     Candidates in this set get [contextBonus] added to their log score.
	 */
	fun decode(
		logEmissions: Array<FloatArray>,
		numKeys: Int,
		keyToChar: CharArray,
		trie: LexiconTrie,
		lockedPrefix: String = "",
		contextWords: Set<String> = emptySet(),
		maxResults: Int = 5,
	): List<String> {
		val startNode = if (lockedPrefix.isEmpty()) trie.rootNode else trie.walk(lockedPrefix) ?: return emptyList()
		val blankIdx = numKeys

		// Each beam state: trie node, accumulated log-prob, last-emitted char (for CTC collapse),
		// and the word built so far (suffix; the prefix is prepended at result time).
		var beams = mutableListOf(BeamState(startNode, 0f, NO_CHAR, ""))
		val T = logEmissions.size

		for (t in 0 until T) {
			val emit = logEmissions[t]
			val next = HashMap<BeamKey, BeamState>(beams.size * 4)

			for (b in beams) {
				// Option 1: emit blank (or repeat the last char without advancing the trie — CTC
				// allows the same char on consecutive timesteps if separated by a blank).
				val blankProb = b.logProb + emit[blankIdx]
				addBeam(next, b.copy(logProb = blankProb, lastChar = NO_CHAR))

				// Option 2: try every child of the current trie node. CTC says consecutive
				// identical chars without a blank between them collapse to one — so if the child's
				// char equals b.lastChar, this expansion only makes sense via the blank path above.
				for (i in 0 until 26) {
					val child = b.node.children[i] ?: continue
					val ch = ('a' + i)
					val keyIdx = findKeyIndex(ch, keyToChar, numKeys)
					if (keyIdx < 0) continue
					val pCh = emit[keyIdx]

					// Direct extension — only valid if we're not stuck on the same char.
					if (ch != b.lastChar) {
						val newProb = b.logProb + pCh
						val newSuffix = b.suffix + ch
						addBeam(next, BeamState(child, newProb, ch, newSuffix))
					}
				}
			}

			// Prune to top [beamWidth].
			beams = next.values
				.sortedByDescending { it.logProb }
				.take(beamWidth)
				.toMutableList()
		}

		// Collect terminal beams (= valid words). For each, add prior log-frequency and context
		// bonus, then rank.
		data class Scored(val word: String, val score: Float)
		val scored = mutableListOf<Scored>()
		for (b in beams) {
			if (!b.node.terminal) continue
			val full = lockedPrefix + b.suffix
			val freqScore = gammaFrequency * b.node.logFrequency
			val ctxScore = if (full in contextWords) contextBonus else 0f
			scored.add(Scored(full, b.logProb + freqScore + ctxScore))
		}
		return scored
			.sortedByDescending { it.score }
			.distinctBy { it.word }
			.take(maxResults)
			.map { it.word }
	}

	private fun addBeam(map: HashMap<BeamKey, BeamState>, candidate: BeamState) {
		val key = BeamKey(candidate.node, candidate.lastChar, candidate.suffix)
		val existing = map[key]
		if (existing == null || candidate.logProb > existing.logProb) {
			map[key] = candidate
		}
	}

	private fun findKeyIndex(ch: Char, keyToChar: CharArray, numKeys: Int): Int {
		for (i in 0 until min(numKeys, keyToChar.size)) {
			if (keyToChar[i] == ch) return i
		}
		return -1
	}

	private data class BeamState(
		val node: LexiconTrie.Node,
		val logProb: Float,
		val lastChar: Char,
		val suffix: String,
	)

	private data class BeamKey(val node: LexiconTrie.Node, val lastChar: Char, val suffix: String)

	companion object {
		private const val NO_CHAR = 0.toChar()
		const val LOG_FLOOR = -1e9f
		val LOG_FLOOR_TERM: Float = ln(1e-9f)
	}
}
