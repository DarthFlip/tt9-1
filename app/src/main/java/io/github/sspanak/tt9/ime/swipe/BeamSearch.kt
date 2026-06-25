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
 *
 * The decoder is alphabet-agnostic: it walks the trie's actual children (HashMap<Char, Node>)
 * and looks up each char's keyboard-key index via the layout-built charToKeyIndex map, so it
 * works for English / Hebrew / Cyrillic without modification.
 */
package io.github.sspanak.tt9.ime.swipe

import kotlin.math.ln

class BeamSearch(
	private val beamWidth: Int = 50,
	private val gammaFrequency: Float = 0.4056f, // scoring.json gamma (frequency weight)
	private val contextBonus: Float = 1.5f, // additive log-bonus for MindReader hits
) {

	/**
	 * Decode [logEmissions] (shape `[T × (numKeys + 1)]`, channels-last, log-space) into the top
	 * [maxResults] words.
	 *
	 * @param logEmissions raw model output; column index `numKeys` is the CTC blank.
	 * @param numKeys length of the actual key alphabet (FUTO's encoder = 64).
	 * @param charToKeyIndex map char → key index `[0, numKeys)`. Built from the active layout
	 *                       by NeuralGlideDecoder.setLayout.
	 * @param trie lexicon trie built from the active language's word list.
	 * @param lockedPrefix locked QWERTY prefix from the IME (already lowercased). Empty for a
	 *                     full-word swipe; non-empty when continuing after QWERTY taps.
	 * @param contextWords MindReader's next-word predictions for the current cursor position.
	 *                     Candidates in this set get [contextBonus] added to their log score.
	 */
	fun decode(
		logEmissions: Array<FloatArray>,
		numKeys: Int,
		charToKeyIndex: Map<Char, Int>,
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
				// Option 1: emit blank — keeps us on the same trie node, resets the last-char
				// barrier so a child with the same char as lastChar can extend next step.
				val blankProb = b.logProb + emit[blankIdx]
				addBeam(next, b.copy(logProb = blankProb, lastChar = NO_CHAR))

				// Option 2: extend by every trie child whose char is present in the layout.
				// CTC says consecutive identical chars without a blank between them collapse to
				// one — so we skip extension when ch == b.lastChar (that path only makes sense
				// via the blank above).
				for ((ch, child) in b.node.children) {
					val keyIdx = charToKeyIndex[ch] ?: continue
					if (ch == b.lastChar) continue
					val newProb = b.logProb + emit[keyIdx]
					addBeam(next, BeamState(child, newProb, ch, b.suffix + ch))
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
