package io.github.sspanak.tt9.ime;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiny static word→emoji lookup. Used by SuggestionHandler.handleGuesses to inject a relevant
 * emoji into the strip alongside MindReader's next-word predictions, after a word has just been
 * committed. Mirrors Gboard's "predictive emoji" feature in spirit, but kept intentionally small
 * — a single emoji per word so the strip doesn't get spammed.
 *
 * Lookups are case-insensitive. Returns null if no emoji is associated with the context word.
 *
 * Extending the map is the future user-facing path; for now the V1 set covers the chat-y words
 * users actually emote about.
 */
final class EmojiPredictions {
	private EmojiPredictions() {}

	private static final Map<String, String> MAP = build();

	@Nullable
	static String lookup(@Nullable String contextWord) {
		if (contextWord == null || contextWord.isEmpty()) return null;
		return MAP.get(contextWord.toLowerCase());
	}

	private static Map<String, String> build() {
		HashMap<String, String> m = new HashMap<>();
		// Sentiment + reaction
		m.put("happy", "🙂");
		m.put("sad", "😢");
		m.put("cry", "😢");
		m.put("crying", "😢");
		m.put("laughing", "😂");
		m.put("lol", "😂");
		m.put("lmao", "😂");
		m.put("love", "❤️");
		m.put("loved", "❤️");
		m.put("kiss", "😘");
		m.put("wow", "😮");
		m.put("cool", "😎");
		m.put("angry", "😠");
		m.put("mad", "😠");
		m.put("tired", "😴");
		m.put("sleep", "😴");
		m.put("sleeping", "😴");
		m.put("scared", "😨");
		m.put("ok", "👍");
		m.put("okay", "👍");
		m.put("yes", "✅");
		m.put("no", "❌");
		m.put("thanks", "🙏");
		m.put("thank", "🙏");
		m.put("please", "🙏");
		m.put("sorry", "🙏");
		m.put("hi", "👋");
		m.put("hello", "👋");
		m.put("hey", "👋");
		m.put("bye", "👋");
		m.put("goodbye", "👋");
		// Food + drink
		m.put("coffee", "☕");
		m.put("tea", "🍵");
		m.put("food", "🍴");
		m.put("eating", "🍴");
		m.put("hungry", "🍴");
		m.put("pizza", "🍕");
		m.put("burger", "🍔");
		m.put("cake", "🎂");
		m.put("birthday", "🎂");
		m.put("beer", "🍺");
		m.put("wine", "🍷");
		m.put("water", "💧");
		// Things + places
		m.put("car", "🚗");
		m.put("driving", "🚗");
		m.put("home", "🏠");
		m.put("house", "🏠");
		m.put("work", "💼");
		m.put("office", "💼");
		m.put("school", "🏫");
		m.put("phone", "📱");
		m.put("music", "🎵");
		m.put("song", "🎵");
		m.put("party", "🎉");
		m.put("celebrate", "🎉");
		m.put("fire", "🔥");
		m.put("hot", "🔥");
		m.put("rain", "🌧️");
		m.put("snow", "❄️");
		m.put("sun", "☀️");
		m.put("sunny", "☀️");
		m.put("morning", "☀️");
		m.put("night", "🌙");
		m.put("star", "⭐");
		m.put("money", "💰");
		m.put("dog", "🐶");
		m.put("cat", "🐱");
		m.put("running", "🏃");
		m.put("run", "🏃");
		m.put("gym", "🏋️");
		m.put("workout", "🏋️");
		return Collections.unmodifiableMap(m);
	}
}
