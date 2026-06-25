/*
 * Copyright (C) 2026 tt9 contributors. GPL-3.0.
 *
 * Token-indexed prefix trie over the active English lexicon. CleverKeys' decoder
 * vocabulary maps a..z to token IDs 4..29 (with 0=PAD, 1=UNK, 2=SOS, 3=EOS).
 * BeamSearch consults this trie to prune candidate next-tokens that would step
 * outside any in-vocabulary prefix.
 *
 * Built once per setWordProvider on the worker thread. Lookups are
 * Array<Node?>(26) — flat array, no HashMap allocation per child — because the
 * English alphabet is fixed-width and the trie is on the hot path.
 *
 * Hebrew / non-English languages do NOT go through here — they use the
 * StatisticalGlideTypingClassifier path entirely, which has its own pruner.
 */
package io.github.sspanak.tt9.ime.swipe

import kotlin.math.ln

class LexiconTrie {
	private var root: Node = Node()
	var wordCount: Int = 0
		private set

	/** Token IDs per CleverKeys' tokenizer config. */
	companion object {
		const val PAD = 0
		const val UNK = 1
		const val SOS = 2
		const val EOS = 3
		const val FIRST_LETTER_TOKEN = 4
		const val LAST_LETTER_TOKEN = 29 // 'z'

		fun charToToken(ch: Char): Int {
			if (ch !in 'a'..'z') return UNK
			return FIRST_LETTER_TOKEN + (ch - 'a')
		}

		fun tokenToChar(token: Int): Char {
			if (token < FIRST_LETTER_TOKEN || token > LAST_LETTER_TOKEN) return 0.toChar()
			return 'a' + (token - FIRST_LETTER_TOKEN)
		}
	}

	fun build(words: List<String>, frequencyOf: (String) -> Float) {
		val newRoot = Node()
		var n = 0
		for (raw in words) {
			val word = raw.lowercase()
			if (word.isEmpty()) continue
			var node = newRoot
			var skip = false
			for (ch in word) {
				if (ch !in 'a'..'z') { skip = true; break }
				val idx = ch - 'a'
				var child = node.children[idx]
				if (child == null) {
					child = Node()
					node.children[idx] = child
				}
				node = child
			}
			if (skip) continue
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

	/** Walk the trie one step given a vocabulary token; returns null if no child exists. */
	fun step(node: Node, token: Int): Node? {
		if (token < FIRST_LETTER_TOKEN || token > LAST_LETTER_TOKEN) return null
		return node.children[token - FIRST_LETTER_TOKEN]
	}

	/** Walk to the node matching a lowercase ASCII prefix. */
	fun walk(prefix: String): Node? {
		var node = root
		for (ch in prefix.lowercase()) {
			if (ch !in 'a'..'z') return null
			val child = node.children[ch - 'a'] ?: return null
			node = child
		}
		return node
	}

	class Node {
		val children: Array<Node?> = arrayOfNulls(26)
		var terminal: Boolean = false
		var word: String? = null
		var logFrequency: Float = 0f
	}
}
