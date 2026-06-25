/*
 * Copyright (C) 2026 tt9 contributors. GPL-3.0.
 *
 * Prefix trie over the active language's lexicon, with log-frequency at terminal nodes. Used by
 * BeamSearch to constrain CTC decoding to valid words and rank them by prior frequency.
 *
 * Built lazily on setWordProvider; rebuild is O(n_words × avg_word_len). For tt9's ~5k-30k word
 * languages on the F1 this is ~20-100ms, run once on the worker thread.
 *
 * Uses HashMap<Char, Node> rather than a fixed-size array so Hebrew / Cyrillic / any non-ASCII
 * alphabet works without modification. Memory overhead vs an a-z array is ~150 bytes per node
 * but for a 30k-word English lexicon (~70k nodes) that's a one-time 10 MB cost, well within budget.
 */
package io.github.sspanak.tt9.ime.swipe

import kotlin.math.ln

class LexiconTrie {
	private var root: Node = Node()
	var wordCount: Int = 0
		private set

	/** Build the trie from a flat list of words + a per-word frequency lookup. */
	fun build(words: List<String>, frequencyOf: (String) -> Float) {
		val newRoot = Node()
		var n = 0
		for (raw in words) {
			val word = raw.lowercase()
			if (word.isEmpty()) continue
			var node = newRoot
			for (ch in word) {
				node = node.children.getOrPut(ch) { Node() }
			}
			node.terminal = true
			val freq = frequencyOf(raw).coerceAtLeast(1e-6f)
			node.logFrequency = ln(freq + 1f)
			node.word = word
			n++
		}
		root = newRoot
		wordCount = n
	}

	val rootNode: Node get() = root

	/**
	 * Walk to the node matching [prefix]. Returns null if the prefix is absent (no words start with it).
	 * Used by BeamSearch to initialize the beam at the locked-prefix node so candidates are
	 * automatically constrained to words starting with the prefix.
	 */
	fun walk(prefix: String): Node? {
		var node = root
		for (ch in prefix.lowercase()) {
			val child = node.children[ch] ?: return null
			node = child
		}
		return node
	}

	class Node {
		val children: HashMap<Char, Node> = HashMap()
		var terminal: Boolean = false
		var word: String? = null
		var logFrequency: Float = 0f
	}
}
