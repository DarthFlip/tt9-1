package io.github.sspanak.tt9.preferences.screens.appearance;

import androidx.preference.SwitchPreferenceCompat;

import io.github.sspanak.tt9.preferences.settings.SettingsStore;

public final class ItemStatusIcon {
	public static final String NAME = "pref_status_icon";

	private final SwitchPreferenceCompat item;
	private final SettingsStore settings;

	public ItemStatusIcon(SwitchPreferenceCompat item, SettingsStore settings) {
		this.item = item;
		this.settings = settings;
	}

	public SwitchPreferenceCompat item() { return item; }
	public SettingsStore settings() { return settings; }

	public void populate() {
		if (item != null) {
			item.setChecked(settings.isStatusIconEnabled());
		}
	}
}
