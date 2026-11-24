package org.sipdroid.sipua.rosenweg;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * Ab Android 15 (targetSdk 35) zeichnen Apps bis unter Status- und Navigationsleiste.
 * Haelt den Inhalt davon frei: die Raender der Leisten (und der Tastatur) kommen
 * zum eigenen Innenabstand der Ansicht dazu.
 */
final class Raender {
	private Raender() {}

	static <T extends View> T freihalten(T v) {
		if (Build.VERSION.SDK_INT < 30) {
			v.setFitsSystemWindows(true);
			return v;
		}
		final int l = v.getPaddingLeft(), o = v.getPaddingTop(), r = v.getPaddingRight(), u = v.getPaddingBottom();
		v.setOnApplyWindowInsetsListener((ansicht, insets) -> {
			android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars()
					| WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
			ansicht.setPadding(l + i.left, o + i.top, r + i.right, u + i.bottom);
			return WindowInsets.CONSUMED;
		});
		v.requestApplyInsets();
		return v;
	}
}
