package org.sipdroid.sipua.rosenweg;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Band oben ueber allem, solange eine Fernwartungs-Sitzung laeuft (Stefan 27.11.2026: auch fuer
 * die angemeldete Person sichtbar, auch bei Bildschirmuebertragung, ohne Rueckfrage):
 * "🛠 Fernwartung aktiv – Technik (Name) seit 14:02".
 *
 * Fenster vom Typ TYPE_APPLICATION_OVERLAY: ueber jeder App und dem Kiosk. Nimmt keine
 * Beruehrung an (darunter bleibt alles bedienbar). Braucht SYSTEM_ALERT_WINDOW
 * (geteiltes-telefon-einrichten.sh: appops set ... SYSTEM_ALERT_WINDOW allow); fehlt es,
 * bleibt die laute Benachrichtigung "Fernwartung aktiv". Nur im Haupt-Faden aufrufen.
 */
final class FernwartungBand {
	private static TextView band;

	private FernwartungBand() {}

	static void zeigen(Context c, String text) {
		Context app = c.getApplicationContext();
		if (band != null) {
			band.setText(text);
			return;
		}
		if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(app)) {
			Log.w(Fernwartung.TAG, "Band nicht moeglich: SYSTEM_ALERT_WINDOW fehlt (Einrichtungsskript)");
			return;
		}
		try {
			TextView t = new TextView(app);
			t.setText(text);
			t.setTextColor(0xFFFFFFFF);
			t.setBackgroundColor(0xEEB91C1C);
			t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
			t.setGravity(Gravity.CENTER);
			int pad = (int) (8 * app.getResources().getDisplayMetrics().density);
			t.setPadding(pad, pad, pad, pad);
			int typ = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
					: WindowManager.LayoutParams.TYPE_PHONE;
			WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
					WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, typ,
					WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
							| WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
					PixelFormat.TRANSLUCENT);
			lp.gravity = Gravity.TOP;
			app.getSystemService(WindowManager.class).addView(t, lp);
			band = t;
		} catch (Exception e) {
			Log.w(Fernwartung.TAG, "Band: " + e);
		}
	}

	static void verbergen(Context c) {
		if (band == null) return;
		try {
			c.getApplicationContext().getSystemService(WindowManager.class).removeView(band);
		} catch (Exception ignored) { }
		band = null;
	}
}
