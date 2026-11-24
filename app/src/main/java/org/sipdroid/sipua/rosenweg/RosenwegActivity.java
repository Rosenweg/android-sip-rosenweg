package org.sipdroid.sipua.rosenweg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.sipdroid.sipua.R;
import org.sipdroid.sipua.ui.Settings;

/**
 * Startansicht "Rosenweg": Kacheln fuer die Apps, die der angemeldeten Person
 * freigegeben sind (Web-Apps im eigenen Fenster, installierte Apps direkt),
 * dazu Abmelden und Info.
 *
 * Die alte Einstellungsseite (Sipdroid) zeigt SIP-Zugangsdaten; als Device
 * Owner ist sie hier nicht erreichbar. Ohne Device Owner (Entwicklung) gibt es
 * einen Knopf dafuer.
 */
public class RosenwegActivity extends Activity {

	@Override
	protected void onResume() {
		super.onResume();
		setContentView(baue());
	}

	private int dp(float v) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
	}

	private Button kachel(String text, String farbe, Runnable aktion) {
		Button b = new Button(this);
		b.setText(text);
		b.setAllCaps(false);
		b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
		b.setTextColor(Color.WHITE);
		b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
		b.setPadding(dp(20), 0, dp(20), 0);
		GradientDrawable g = new GradientDrawable();
		g.setColor(Color.parseColor(farbe));
		g.setCornerRadius(dp(14));
		b.setBackground(g);
		b.setOnClickListener(v -> aktion.run());
		return b;
	}

	private LinearLayout baue() {
		LinearLayout l = new LinearLayout(this);
		l.setOrientation(LinearLayout.VERTICAL);
		l.setBackgroundColor(Color.parseColor("#0F172A"));
		l.setPadding(dp(20), dp(36), dp(20), dp(20));

		TextView titel = new TextView(this);
		titel.setText("Rosenweg");
		titel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
		titel.setTypeface(Typeface.DEFAULT_BOLD);
		titel.setTextColor(Color.parseColor("#E5E7EB"));
		l.addView(titel);

		TextView wer = new TextView(this);
		wer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		wer.setTextColor(Color.parseColor("#94A3B8"));
		wer.setPadding(0, dp(4), 0, dp(20));
		wer.setText(Anlage.angemeldet(this) ? "Angemeldet: " + Anlage.name(this)
				+ " · Nebenstelle " + Anlage.nebenstelle(this) : "Niemand angemeldet");
		l.addView(wer);

		ScrollView sv = new ScrollView(this);
		LinearLayout liste = new LinearLayout(this);
		liste.setOrientation(LinearLayout.VERTICAL);
		sv.addView(liste);
		l.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

		JSONArray apps = Anlage.angemeldet(this) ? Katalog.freigegeben(this) : new JSONArray();
		for (int i = 0; i < apps.length(); i++) {
			JSONObject a = apps.optJSONObject(i);
			if (a == null) continue;
			String name = a.optString("name");
			Runnable aktion;
			if ("web".equals(a.optString("art"))) {
				String url = a.optString("url");
				aktion = () -> startActivity(WebAppActivity.oeffnen(this, name, url));
			} else {
				Intent start = getPackageManager().getLaunchIntentForPackage(a.optString("paket"));
				if (start == null) continue;   // noch nicht installiert
				aktion = () -> startActivity(start);
			}
			LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
			p.bottomMargin = dp(10);
			liste.addView(kachel(name, "#1E3A8A", aktion), p);
		}
		if (apps.length() == 0) {
			TextView leer = new TextView(this);
			leer.setTextColor(Color.parseColor("#94A3B8"));
			leer.setText(Anlage.angemeldet(this) ? "Für dein Konto sind keine Apps freigegeben."
					: "Zum Anmelden das Telefon sperren lassen (Abmelden) oder den Anmelde-QR scannen.");
			liste.addView(leer);
		}

		LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
		kp.topMargin = dp(10);
		if (Anlage.angemeldet(this)) {
			if (Sperre.istDeviceOwner(this)) {
				l.addView(kachel("Persönliche PIN", "#334155", this::pinDialog), kp);
			}
			l.addView(kachel("Abmelden", "#DC2626", this::abmelden), new LinearLayout.LayoutParams(kp));
		}
		if (!Sperre.istDeviceOwner(this)) {
			l.addView(kachel("SIP-Einstellungen", "#334155",
					() -> startActivity(new Intent(this, Settings.class))), new LinearLayout.LayoutParams(kp));
		}
		// Info mit den Urheberangaben — nach ADDITIONAL_TERMS.txt (Sipdroid) muss
		// dieser Text unveraendert erreichbar bleiben.
		l.addView(kachel("Info", "#334155", () -> new AlertDialog.Builder(this)
				.setTitle(getString(R.string.menu_about))
				.setMessage(getString(R.string.about).replace("\\n", "\n"))
				.setIcon(R.drawable.icon)
				.setCancelable(true)
				.show()), new LinearLayout.LayoutParams(kp));
		return l;
	}

	/**
	 * Persoenliche PIN: liegt verschluesselt auf der Anlage und sperrt jedes
	 * geteilte Telefon, solange die Person dort angemeldet ist.
	 */
	private void pinDialog() {
		LinearLayout f = new LinearLayout(this);
		f.setOrientation(LinearLayout.VERTICAL);
		f.setPadding(dp(24), dp(8), dp(24), 0);
		EditText eins = pinFeld("Neue PIN (4–12 Ziffern)");
		EditText zwei = pinFeld("PIN wiederholen");
		f.addView(eins);
		f.addView(zwei);
		AlertDialog d = new AlertDialog.Builder(this)
				.setTitle("Persönliche PIN")
				.setMessage("Sperrt das Telefon, solange du angemeldet bist — auf jedem Rosenweg-Telefon. "
						+ "Beim Abmelden wird sie wieder entfernt.")
				.setView(f)
				.setPositiveButton("Speichern", null)
				.setNeutralButton("PIN entfernen", (x, w) -> pinAnlage(null))
				.setNegativeButton("Abbrechen", null)
				.show();
		d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			String pin = eins.getText().toString();
			if (!pin.equals(zwei.getText().toString())) {
				zwei.setError("Die beiden PINs sind verschieden.");
				return;
			}
			d.dismiss();
			pinAnlage(pin);
		});
	}

	private EditText pinFeld(String hinweis) {
		EditText e = new EditText(this);
		e.setHint(hinweis);
		e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
		e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
		return e;
	}

	/** pin == null: entfernen */
	private void pinAnlage(String pin) {
		new Thread(() -> {
			String meldung;
			try {
				if (pin == null) {
					Anlage.pinEntfernen(this);
					Bildschirmsperre.entfernen(this);
					meldung = "PIN entfernt.";
				} else {
					Anlage.pinSetzen(this, pin);
					Bildschirmsperre.vorbereiten(this);
					meldung = Bildschirmsperre.setzen(this, pin) ? "PIN gespeichert und aktiv."
							: "PIN gespeichert, aber auf diesem Telefon nicht gesetzt.";
				}
			} catch (Exception e) {
				meldung = e.getMessage();
			}
			String m = meldung;
			runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
		}).start();
	}

	private void abmelden() {
		new AlertDialog.Builder(this)
				.setMessage("Abmelden? Persönliche Daten in den Apps werden gelöscht.")
				.setPositiveButton("Abmelden", (d, w) -> startService(
						new Intent(this, AnmeldeDienst.class).setAction(AnmeldeDienst.AKTION_ABMELDEN)))
				.setNegativeButton("Abbrechen", null)
				.show();
	}
}
