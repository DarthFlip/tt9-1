package io.github.sspanak.tt9.ui.main;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import java.util.ArrayList;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.ime.TraditionalT9;
import io.github.sspanak.tt9.ime.swipe.SwipeableKeyboardContainer;
import io.github.sspanak.tt9.ui.main.keys.SoftKey;
import io.github.sspanak.tt9.util.sys.DeviceInfo;

class MainLayoutQwerty extends MainLayoutExtraPanel {
	private static final int ROW_COUNT = 4;
	private boolean isCommandPaletteShown = false;
	private boolean isTextEditingPaletteShown = false;
	private int height;


	MainLayoutQwerty(TraditionalT9 tt9) {
		super(tt9, R.layout.main_qwerty);
	}


	@Override
	void showKeyboard() {
		super.showKeyboard();
		isCommandPaletteShown = false;
		isTextEditingPaletteShown = false;
		togglePanel(R.id.qwerty_keys_container, true);
	}


	@Override
	void showCommandPalette() {
		super.showCommandPalette();
		isCommandPaletteShown = true;
		isTextEditingPaletteShown = false;
		togglePanel(R.id.qwerty_keys_container, false);
	}


	@Override
	void showTextEditingPalette() {
		super.showTextEditingPalette();
		isCommandPaletteShown = false;
		isTextEditingPaletteShown = true;
		togglePanel(R.id.qwerty_keys_container, false);
	}


	@Override
	boolean isCommandPaletteShown() { return isCommandPaletteShown; }

	@Override
	boolean isTextEditingPaletteShown() { return isTextEditingPaletteShown; }


	@Override
	int getHeight(boolean forceRecalculate) {
		if (height <= 0 || forceRecalculate) {
			final Resources res = tt9.getResources();
			final float textSize = res.getDimension(R.dimen.status_bar_text_size);
			final float statusPadding = Math.max(1, textSize * 0.45f);
			final int statusBarHeight = Math.round((statusPadding + textSize) * tt9.getSettings().getSuggestionFontScale());
			final int rowHeight = res.getDimensionPixelSize(R.dimen.qwerty_row_height);
			height = statusBarHeight + rowHeight * ROW_COUNT;
		}
		return height;
	}


	@NonNull
	@Override
	protected ArrayList<SoftKey> getKeys() {
		if (view != null && keys.isEmpty()) {
			ViewGroup root = view.findViewById(R.id.qwerty_keys_container);
			if (root != null) {
				collectSoftKeys(root, keys);
			}
		}
		return keys;
	}


	private static void collectSoftKeys(@NonNull ViewGroup container, @NonNull ArrayList<SoftKey> out) {
		final int childCount = container.getChildCount();
		for (int i = 0; i < childCount; i++) {
			View child = container.getChildAt(i);
			if (child instanceof SoftKey) {
				out.add((SoftKey) child);
			} else if (child instanceof ViewGroup) {
				collectSoftKeys((ViewGroup) child, out);
			}
		}
	}


	@Override
	void render() {
		final boolean isPortrait = !DeviceInfo.isLandscapeOrientation(tt9);

		getView();
		setPadding();
		setWidth(tt9.getSettings().getWidthPercent(isPortrait), tt9.getSettings().getAlignment());
		setBackgroundBlending();
		enableClickHandlers();
		renderKeys(false);
		wireSwipeContainer();
	}


	private void wireSwipeContainer() {
		if (view == null) return;
		View container = view.findViewById(R.id.qwerty_keys_container);
		if (!(container instanceof SwipeableKeyboardContainer)) return;
		final SwipeableKeyboardContainer swipeHost = (SwipeableKeyboardContainer) container;
		swipeHost.setOnWordDecoded(word -> {
			if (tt9 != null && !word.isEmpty()) {
				// Auto-space so users can glide word-after-word without hunting for the spacebar.
				tt9.onText(word + " ", false);
			}
		});
		swipeHost.bindLanguage(tt9 != null ? tt9.getLanguage() : null);
	}
}
