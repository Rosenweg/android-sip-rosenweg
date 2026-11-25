package org.sipdroid.sipua.rosenweg;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "Telefon gesucht" (25.11.2026): Statt der normalen Anrufanzeige zeigt Rosenweg Geraet beim
 * Suchanruf der Anlage (2099) dieses Vollbild — auch ueber dem Sperrbildschirm und ohne
 * angemeldete Person (die Geraetenummer gehoert dem Geraet). "Gefunden" stoppt das Klingeln,
 * beendet den Anruf und meldet es der Anlage (pbx-admin / Telefon-Seite zeigen "Gefunden um …").
 */
public class SuchAktivitaet extends Activity {
	static volatile SuchAktivitaet offen;

	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);
		if (Build.VERSION.SDK_INT >= 27) {
			setShowWhenLocked(true);
			setTurnScreenOn(true);
		} else {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
					| WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
		}
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		offen = this;

		LinearLayout l = new LinearLayout(this);
		l.setOrientation(LinearLayout.VERTICAL);
		l.setGravity(Gravity.CENTER);
		l.setBackgroundColor(Color.parseColor("#1E3A8A"));
		int p = dp(28);
		l.setPadding(p, p, p, p);

		TextView titel = new TextView(this);
		titel.setText("🔔  Telefon gesucht");
		titel.setTextColor(Color.WHITE);
		titel.setTypeface(Typeface.DEFAULT_BOLD);
		titel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
		titel.setGravity(Gravity.CENTER);
		l.addView(titel);

		TextView text = new TextView(this);
		text.setText("Jemand sucht dieses Telefon.\nNebenstelle " + Anlage.nebenstelle(this));
		text.setTextColor(Color.parseColor("#DBEAFE"));
		text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
		text.setGravity(Gravity.CENTER);
		text.setPadding(0, dp(16), 0, dp(40));
		l.addView(text);

		Button gefunden = new Button(this);
		gefunden.setText("Gefunden");
		gefunden.setAllCaps(false);
		gefunden.setTextColor(Color.WHITE);
		gefunden.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
		GradientDrawable g = new GradientDrawable();
		g.setColor(Color.parseColor("#16A34A"));
		g.setCornerRadius(dp(16));
		gefunden.setBackground(g);
		gefunden.setOnClickListener(v -> {
			Suchen.gefunden(getApplicationContext());
			finish();
		});
		l.addView(gefunden, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)));
		setContentView(l);
	}

	@Override
	protected void onDestroy() {
		if (offen == this) offen = null;
		// Niemand angemeldet: Das Suchfenster lief in einer eigenen Lock-Task-Aufgabe; mit ihrem
		// Ende endet der Kiosk-Modus. Den Rosenweg-Sperrbildschirm sofort zurueckholen (er startet
		// den Lock-Task selbst wieder), sonst laege die Startseite offen (gefunden 25.11.2026).
		if (!Anlage.angemeldet(this)) {
			try {
				startActivity(new android.content.Intent(this, SperrActivity.class)
						.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
			} catch (Exception e) {
				android.util.Log.w("RosenwegSuchen", "Sperrbildschirm zurueck: " + e);
			}
		}
		super.onDestroy();
	}

	/** Suche vorbei (Anrufer hat aufgegeben / 45 s um): Fenster schliessen. */
	static void schliessen() {
		SuchAktivitaet a = offen;
		if (a != null) a.runOnUiThread(a::finish);
	}

	private int dp(int v) {
		return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
	}
}
