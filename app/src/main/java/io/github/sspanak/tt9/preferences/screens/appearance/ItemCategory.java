package io.github.sspanak.tt9.preferences.screens.appearance;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceCategory;

import io.github.sspanak.tt9.preferences.settings.SettingsStore;

public final class ItemCategory implements ItemLayoutChangeReactive {
	@Nullable private final PreferenceCategory item;

	public ItemCategory(@Nullable PreferenceCategory item) {
		this.item = item;
	}

	@Nullable public PreferenceCategory item() { return item; }

	@Override
	public void onLayoutChange(int mainViewLayout) {
		if (item != null) {
			item.setVisible(mainViewLayout != SettingsStore.LAYOUT_STEALTH);
		}
	}
}
