package io.github.sspanak.tt9.ui.main.keys;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * Floating tap-to-select popup showing alternate glyphs for a long-pressed QWERTY key —
 * ASK-style. Sits directly above the anchor key. Tap an alternate to commit it; tap
 * outside to dismiss. Doesn't steal focus from the host EditText (focusable=false).
 *
 * MVP scope: tap-to-select. Finger-slide selection (Gboard's actual UX) is a follow-up.
 */
public class AlternatesPopup {
	/** Padding around each alternate label in DP. */
	private static final int ITEM_PADDING_DP = 12;
	/** Corner radius of the popup background in DP. */
	private static final int CORNER_RADIUS_DP = 8;
	/** Text size for the alternate labels in SP. Matches the base key label size roughly. */
	private static final int LABEL_TEXT_SP = 20;

	private final Context context;
	private PopupWindow window;

	public AlternatesPopup(Context context) {
		this.context = context;
	}

	/**
	 * Show a popup above [anchor] listing [alternates]. Tapping an alternate fires
	 * [onSelect] with the raw insert-unit string, then dismisses. Tapping outside dismisses
	 * without calling back.
	 */
	public void show(View anchor, String[] alternates, Consumer<String> onSelect) {
		if (alternates == null || alternates.length == 0 || anchor == null) return;
		dismiss(); // one popup at a time

		final LinearLayout row = new LinearLayout(context);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER);
		row.setBackground(buildBackground());
		final int rowPad = dp(4);
		row.setPadding(rowPad, rowPad, rowPad, rowPad);

		for (String alt : alternates) {
			row.addView(buildItem(alt, onSelect));
		}

		row.measure(
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
		);
		final int width = row.getMeasuredWidth();
		final int height = row.getMeasuredHeight();

		window = new PopupWindow(row, width, height);
		// Don't steal focus from the target editor — this is a UI overlay, not a dialog.
		window.setFocusable(false);
		// Tap outside the popup dismisses.
		window.setOutsideTouchable(true);
		window.setTouchable(true);
		// Transparent background so setOutsideTouchable actually works (Android quirk).
		window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

		// Center horizontally over the anchor, sit directly above it.
		final int[] loc = new int[2];
		anchor.getLocationInWindow(loc);
		final int anchorCenterX = loc[0] + anchor.getWidth() / 2;
		final int x = anchorCenterX - width / 2;
		final int y = loc[1] - height - dp(4);
		window.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
	}

	public void dismiss() {
		if (window != null && window.isShowing()) {
			window.dismiss();
		}
		window = null;
	}

	private TextView buildItem(String label, Consumer<String> onSelect) {
		TextView tv = new TextView(context);
		tv.setText(label);
		tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_TEXT_SP);
		tv.setTextColor(getThemeTextColor());
		tv.setGravity(Gravity.CENTER);
		final int pad = dp(ITEM_PADDING_DP);
		tv.setPadding(pad, pad / 2, pad, pad / 2);
		tv.setClickable(true);
		tv.setFocusable(false);
		tv.setOnClickListener(v -> {
			if (onSelect != null) onSelect.accept(label);
			dismiss();
		});
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.WRAP_CONTENT,
			ViewGroup.LayoutParams.WRAP_CONTENT
		);
		lp.setMargins(dp(2), 0, dp(2), 0);
		tv.setLayoutParams(lp);
		return tv;
	}

	private GradientDrawable buildBackground() {
		GradientDrawable bg = new GradientDrawable();
		bg.setShape(GradientDrawable.RECTANGLE);
		bg.setCornerRadius(dp(CORNER_RADIUS_DP));
		bg.setColor(getThemeBgColor());
		bg.setStroke(dp(1), Color.argb(80, 0, 0, 0));
		return bg;
	}

	private int dp(int v) {
		final float density = context.getResources().getDisplayMetrics().density;
		return Math.round(density * v);
	}

	/** Popup background: opaque near-white in light mode, near-black in dark mode. */
	private int getThemeBgColor() {
		return isNightMode() ? Color.rgb(48, 48, 48) : Color.rgb(240, 240, 240);
	}

	/** Popup label color, high-contrast against the background above. */
	private int getThemeTextColor() {
		return isNightMode() ? Color.WHITE : Color.BLACK;
	}

	private boolean isNightMode() {
		int mode = context.getResources().getConfiguration().uiMode
			& android.content.res.Configuration.UI_MODE_NIGHT_MASK;
		return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
	}
}
