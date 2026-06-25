/*
 * Copyright (C) 2026 tt9 contributors. GPL-3.0.
 *
 * Autoregressive beam search over CleverKeys' transformer decoder. The decoder is
 * called once per step, returning per-position logits [num_beams, 20, 30]; we read
 * the slice at the current step to expand each beam by the top-K valid next-tokens,
 * prune to beamWidth, and stop early when every beam has emitted EOS.
 *
 * Lexicon trie constrains expansion — only tokens that extend a real word's prefix
 * (or terminate at a word) are allowed. Locked-prefix tokens are force-decoded as
 * the initial state.
 */
package io.github.sspanak.tt9.ime.swipe

import kotlin.math.exp
import kotlin.math.ln

/**
 * The decoder function is injected so this class stays unit-testable with synthetic
 * logits. Signature: given a packed `[numBeams, 20]` int32 token tensor, return the
 * flat `[numBeams * 20 * 30]` row-major logits.
 */
typealias DecoderCall = (tokens: IntArray, numBeams: Int) -> FloatArray?

class BeamSearch(
	private val beamWidth: Int = 4,
	private val maxSteps: Int = 12,
	private val lengthAlpha: Float = 1.0f,
	private val freqWeight: Float = 0.4f,
	private val contextBonus: Float = 1.5f,
) {

	fun decode(
		runDecoder: DecoderCall,
		trie: LexiconTrie,
		lockedPrefix: String = "",
		contextWords: Set<String> = emptySet(),
		maxResults: Int = 5,
	): List<String> {
		// Initialize beams with SOS, then force-decode any locked-prefix tokens.
		val initialTokens = mutableListOf(LexiconTrie.SOS)
		var trieNode = trie.rootNode
		for (ch in lockedPrefix.lowercase()) {
			if (ch !in 'a'..'z') return emptyList()
			val token = LexiconTrie.charToToken(ch)
			val child = trie.step(trieNode, token) ?: return emptyList()
			initialTokens.add(token)
			trieNode = child
		}

		var beams = listOf(BeamState(initialTokens.toIntArray(), trieNode, 0f, finished = false))

		for (step in 0 until maxSteps) {
			val live = beams.filter { !it.finished }
			if (live.isEmpty()) break

			// Pack live beams into a [numBeams, 20] tensor; pad with 0 (PAD).
			val numBeams = live.size
			val packed = IntArray(numBeams * DECODER_SEQ_LEN)
			for (i in live.indices) {
				val tokens = live[i].tokens
				val copyLen = minOf(tokens.size, DECODER_SEQ_LEN)
				System.arraycopy(tokens, 0, packed, i * DECODER_SEQ_LEN, copyLen)
				// remaining positions stay 0 (PAD)
			}

			val flatLogits = runDecoder(packed, numBeams) ?: return emptyList()
			val expectedSize = numBeams * DECODER_SEQ_LEN * VOCAB_SIZE
			if (flatLogits.size != expectedSize) return emptyList()

			val next = ArrayList<BeamState>(numBeams * (beamWidth + 1))
			// Carry finished beams forward.
			for (b in beams) if (b.finished) next.add(b)

			for (i in live.indices) {
				val beam = live[i]
				// Read logits at the position of the LAST emitted real token — that slot's
				// distribution predicts the NEXT token to emit.
				val readPos = (beam.tokens.size - 1).coerceIn(0, DECODER_SEQ_LEN - 1)
				val logitsOffset = (i * DECODER_SEQ_LEN + readPos) * VOCAB_SIZE
				val logProbs = softmaxLog(flatLogits, logitsOffset)

				// Build the candidate-next-token list: trie children (for valid prefix extension)
				// plus EOS (allowed only after at least 1 letter emitted past SOS).
				val emittedLetters = beam.tokens.size - 1
				val candidates = ArrayList<Pair<Int, Float>>(8)
				for (letterIdx in 0 until 26) {
					val child = beam.trieNode.children[letterIdx] ?: continue
					val token = LexiconTrie.FIRST_LETTER_TOKEN + letterIdx
					candidates.add(token to logProbs[token])
				}
				if (emittedLetters >= 1 && beam.trieNode.terminal) {
					candidates.add(LexiconTrie.EOS to logProbs[LexiconTrie.EOS])
				}
				if (candidates.isEmpty()) {
					// Dead-end beam — drop it.
					continue
				}

				// Take top-K candidates per beam to keep next-set manageable.
				candidates.sortByDescending { it.second }
				val k = minOf(candidates.size, beamWidth)
				for (j in 0 until k) {
					val (tok, lp) = candidates[j]
					val newProb = beam.logProb + lp
					val newTokens = IntArray(beam.tokens.size + 1)
					System.arraycopy(beam.tokens, 0, newTokens, 0, beam.tokens.size)
					newTokens[beam.tokens.size] = tok
					val newNode = if (tok == LexiconTrie.EOS) beam.trieNode else trie.step(beam.trieNode, tok) ?: continue
					val finished = tok == LexiconTrie.EOS
					next.add(BeamState(newTokens, newNode, newProb, finished))
				}
			}

			// Prune to beamWidth by raw log-prob (length normalization is applied at finalization).
			beams = next.sortedByDescending { it.logProb }.take(beamWidth)
			if (beams.all { it.finished }) break
		}

		// Score finished beams. Length normalization + frequency + context bonus.
		val scored = mutableListOf<Pair<String, Float>>()
		for (b in beams) {
			if (!b.trieNode.terminal) continue
			val word = b.trieNode.word ?: continue
			val len = (b.tokens.size - 1).coerceAtLeast(1)
			val norm = b.logProb / pow(len.toFloat(), lengthAlpha)
			val freq = freqWeight * b.trieNode.logFrequency
			val ctx = if (word in contextWords) contextBonus else 0f
			scored.add(word to (norm + freq + ctx))
		}
		return scored
			.sortedByDescending { it.second }
			.distinctBy { it.first }
			.take(maxResults)
			.map { it.first }
	}

	private fun softmaxLog(flat: FloatArray, offset: Int): FloatArray {
		// Numerically-stable log-softmax.
		var maxLogit = Float.NEGATIVE_INFINITY
		for (i in 0 until VOCAB_SIZE) {
			val v = flat[offset + i]
			if (v > maxLogit) maxLogit = v
		}
		var sumExp = 0f
		for (i in 0 until VOCAB_SIZE) {
			sumExp += exp(flat[offset + i] - maxLogit)
		}
		val logZ = maxLogit + ln(sumExp.coerceAtLeast(1e-30f))
		val out = FloatArray(VOCAB_SIZE)
		for (i in 0 until VOCAB_SIZE) {
			out[i] = flat[offset + i] - logZ
		}
		return out
	}

	private fun pow(base: Float, exponent: Float): Float =
		Math.pow(base.toDouble(), exponent.toDouble()).toFloat()

	private data class BeamState(
		val tokens: IntArray,
		val trieNode: LexiconTrie.Node,
		val logProb: Float,
		val finished: Boolean,
	)

	companion object {
		const val DECODER_SEQ_LEN = 20
		const val VOCAB_SIZE = 30
	}
}
