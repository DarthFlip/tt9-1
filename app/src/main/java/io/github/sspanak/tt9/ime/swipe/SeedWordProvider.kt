/*
 * Copyright (C) 2025 tt9 contributors. Apache 2.0.
 *
 * Temporary bootstrap word source for on-screen swipe typing. Ships a fixed list of common
 * English words so the gesture classifier has something to score against before Phase 3 wires
 * tt9's real dictionary / letter-constrained queries.
 */
package io.github.sspanak.tt9.ime.swipe

/**
 * A hard-coded top-N English word list ordered by approximate frequency (most common first).
 * The classifier normalises by rank to produce a frequency in [0, 1].
 */
object SeedWordProvider : WordProvider {
	private val WORDS: List<String> = listOf(
		"the","be","to","of","and","a","in","that","have","i","it","for","not","on","with","he","as","you","do","at",
		"this","but","his","by","from","they","we","say","her","she","or","an","will","my","one","all","would","there","their","what",
		"so","up","out","if","about","who","get","which","go","me","when","make","can","like","time","no","just","him","know","take",
		"people","into","year","your","good","some","could","them","see","other","than","then","now","look","only","come","its","over","think","also",
		"back","after","use","two","how","our","work","first","well","way","even","new","want","because","any","these","give","day","most","us",
		"is","was","are","were","been","has","had","did","said","got","gave","made","went","came","found","kept","left","told","felt","came",
		"man","woman","child","boy","girl","home","house","school","book","word","life","hand","part","place","case","week","company","system","program","question",
		"right","wrong","big","small","great","large","little","old","young","high","long","short","black","white","next","last","early","late","same","different",
		"very","much","more","less","many","few","each","every","where","why","how","here","there","where","always","often","never","again","once","still",
		"hello","world","please","thanks","sorry","okay","maybe","sure","happy","sad","love","family","friend","name","phone","car","food","water","music","movie",
		"computer","keyboard","android","swipe","type","letter","number","message","email","text","send","receive","phone","battery","screen","light","dark","light","button","key",
		"quick","brown","fox","jumps","lazy","dog","pack","my","box","five","dozen","liquor","jugs","sphinx","quartz","judge","wizard","vexed","gypsy",
		"code","test","build","ship","run","open","close","read","write","start","stop","begin","end","help","find","lost","keep","leave","check","try",
		"add","remove","delete","save","load","switch","toggle","enable","disable","copy","paste","cut","undo","redo","share","send","search","click","tap","hold"
	)

	override fun getListOfWords(): List<String> = WORDS

	override fun getFrequencyForWord(word: String): Float {
		val rank = WORDS.indexOf(word)
		if (rank < 0) return 0f
		// Linear decay by rank; rank 0 = 1.0, last-ranked word = near-0.
		return 1f - rank.toFloat() / WORDS.size
	}
}
