package io.github.sspanak.tt9.util;

import android.text.Editable;
import android.text.TextWatcher;

import androidx.annotation.Nullable;

import java.util.function.Consumer;

public final class TextChangeWatcher implements TextWatcher {
	private final Consumer<Editable> onChange;

	public TextChangeWatcher(@Nullable Consumer<Editable> onChange) {
		this.onChange = onChange;
	}

	public Consumer<Editable> onChange() { return onChange; }

	@Override
	public void afterTextChanged(Editable s) {
		if (onChange != null) onChange.accept(s);
	}

	@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
	@Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
}
