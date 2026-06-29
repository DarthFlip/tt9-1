package io.github.sspanak.tt9.ui.main.keys;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import io.github.sspanak.tt9.ui.Vibration;

/**
 * Space bar variant with two extra affordances on top of the base "tap = space" behavior:
 *
 *   1. Long-press → cursor-drag mode (Gboard convention). After ~500 ms hold, subsequent
 *      horizontal finger motion translates to text-field cursor moves via
 *      TypingHandler.moveCursor. Released without typing a space (we suppress the release
 *      handler while drag mode is active). Roughly 14 px of horizontal motion = one
 *      cursor step — tuned empirically for the F1's screen density.
 *
 *   2. (Removed) Long-press → cycle next language. With cursor-drag taking the long-press
 *      slot, language cycling lives exclusively on the ?123 / ABC toggle key's long-press
 *      (inherited from SoftKeyLF4.handleHold). The corner-globe icon is also dropped to
 *      reflect that the long-press affordance has changed.
 */
public class SoftKeyQwertySpace extends SoftKeyQwertyLetter {
	private boolean dragMode = false;
	private float lastCursorX = -1f;
	/** Horizontal pixels of finger motion per single-character cursor step. Tighter = more
	 *  sensitive (smaller hand motions move further); looser = more deliberate feel. */
	private static final float PX_PER_CURSOR_STEP = 14f;

	public SoftKeyQwertySpace(Context context) { super(context); }
	public SoftKeyQwertySpace(Context context, AttributeSet attrs) { super(context, attrs); }
	public SoftKeyQwertySpace(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

	@Override
	public boolean onTouch(View view, MotionEvent event) {
		final int action = event.getActionMasked();
		if (action == MotionEvent.ACTION_DOWN) {
			dragMode = false;
			lastCursorX = -1f;
		}
		if (dragMode) {
			if (action == MotionEvent.ACTION_MOVE) {
				if (lastCursorX < 0f) {
					// First move after drag mode armed — capture the baseline so we measure
					// deltas relative to where the finger was when handleHold fired, not
					// relative to the original ACTION_DOWN position (which can be many
					// pixels away after the user held still).
					lastCursorX = event.getX();
					return true;
				}
				final float delta = event.getX() - lastCursorX;
				if (Math.abs(delta) >= PX_PER_CURSOR_STEP) {
					final int steps = (int) (delta / PX_PER_CURSOR_STEP);
					if (tt9 != null && steps != 0) {
						tt9.moveCursor(steps);
						lastCursorX = event.getX();
					}
				}
				return true;
			}
			if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
				dragMode = false;
				// preventRepeat clears the hold/repeat flags in the base class so its
				// ACTION_UP-path handleRelease() doesn't fire — we don't want a space
				// committed after the cursor drag.
				preventRepeat();
				return true;
			}
			return true;
		}
		return super.onTouch(view, event);
	}

	@Override
	protected void handleHold() {
		if (tt9 == null) return;
		preventRepeat();
		dragMode = true;
		lastCursorX = -1f;
		vibrate(Vibration.getHoldVibration());
	}

	@Override
	public boolean isHoldEnabled() {
		return true;
	}

	@Override
	protected int getCornerIcon(int position) {
		// Globe removed — long-press on space is cursor drag now, not language cycle.
		// Language cycling lives on the ?123/ABC bottom-left key's long-press.
		return -1;
	}
}
