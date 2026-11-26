package org.sipdroid.sipua.rosenweg;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

/**
 * Anmeldung per NFC-Karte (UniFi-Access-Karten der Rosenweg-Tueren).
 *
 * Gelesen wird nur die Seriennummer (UID, Tag.getId() als Hex, z.B. 04D4C227C22A81) —
 * dieselbe Zahl, die UniFi Access als nfc_id fuehrt. Die Anlage fragt bei jeder
 * Anmeldung live in Access nach, wem die Karte gehoert; entzogene Karten gelten sofort
 * nicht mehr. Die Karte allein meldet an (Entscheid Stefan 24.11.2026).
 *
 * Diese Activity bekommt TECH_DISCOVERED, wenn gerade nicht die SperrActivity vorne ist
 * (die liest Karten selbst im Reader-Modus). Niemand angemeldet: anmelden. Schon
 * angemeldet: Karte ignorieren (die Kartenverwaltung ist die eigene App "Rosenweg Karten"
 * und liest im Vordergrund selbst). Keine Oberflaeche, schliesst sich sofort.
 */
public class NfcKarte extends Activity {
	private static final String TAG = "RosenwegNfc";
	/** Reader-Modus: aus commons-rosenweg (anmeldeschirm), gleich wie der Anmeldeschirm der Apps. */
	public static final int LESER_FLAGS = ch.rosenweg4303.anmeldeschirm.Kartenleser.FLAGS;

	private static volatile boolean laeuft;
	private static String zuletzt;
	private static long zuletztUm;

	/** Rueckmeldung einer Kartenanmeldung (im UI-Thread). */
	public interface Ergebnis {
		void angemeldet(org.json.JSONObject antwort);

		void fehler(String meldung);
	}

	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);
		auswerten(getIntent());
		finish();
	}

	@Override
	protected void onNewIntent(Intent i) {
		super.onNewIntent(i);
		auswerten(i);
		finish();
	}

	private void auswerten(Intent i) {
		if (i == null) return;
		Tag tag = i.getParcelableExtra(NfcAdapter.EXTRA_TAG);
		String uid = uid(tag);
		if (uid == null) return;
		Context app = getApplicationContext();
		if (Anlage.angemeldet(app) || !Anlage.eingerichtet(app)) {
			Log.i(TAG, "Karte ignoriert (schon angemeldet oder nicht eingerichtet)");
			return;
		}
		anmelden(app, uid, new Ergebnis() {
			@Override
			public void angemeldet(org.json.JSONObject r) {
				// wie die Anmeldung im Anmeldefenster (WebAppActivity)
				if (Nachlauf.aktiv(app)) {
					Toast.makeText(app, "Bitte kurz warten: Dateien der letzten Person werden noch hochgeladen.",
							Toast.LENGTH_LONG).show();
					return;
				}
				Sperre.freigeben(app, r.optString("name", ""), r.optJSONArray("apps"));
			}

			@Override
			public void fehler(String meldung) {
				Toast.makeText(app, meldung, Toast.LENGTH_LONG).show();
			}
		});
	}

	/** UID der Karte als Hex in Grossbuchstaben, null ohne Karte. */
	public static String uid(Tag tag) {
		return ch.rosenweg4303.anmeldeschirm.Kartenleser.uid(tag);
	}

	/**
	 * Karte an der Anlage einloesen (eigener Thread); Ergebnis im UI-Thread. Dieselbe Karte
	 * wird innert 4 s nur einmal eingeloest (der Leser meldet sie mehrfach).
	 */
	public static void anmelden(Context c, String uid, Ergebnis ergebnis) {
		synchronized (NfcKarte.class) {
			long jetzt = SystemClock.elapsedRealtime();
			if (laeuft || (uid.equals(zuletzt) && jetzt - zuletztUm < 4000)) {
				Log.i(TAG, "Karte verworfen: " + (laeuft ? "Anmeldung laeuft schon" : "gleiche Karte innert 4 s"));
				return;
			}
			laeuft = true;
			zuletzt = uid;
			zuletztUm = jetzt;
		}
		Handler ui = new Handler(Looper.getMainLooper());
		Log.i(TAG, "Karte gelesen, Anmeldung an der Anlage (" + uid.length() / 2 + " Byte UID)");
		new Thread(() -> {
			try {
				org.json.JSONObject r = Anlage.karte(c, uid);
				if (r.optBoolean("angemeldet", false)) ui.post(() -> ergebnis.angemeldet(r));
				else ui.post(() -> ergebnis.fehler("Anmelden fehlgeschlagen"));
			} catch (Anlage.AnlageFehler e) {
				String m = e.status == 401 ? "Dieses Telefon ist an der Anlage nicht als geteiltes Telefon freigegeben."
						: e.status >= 500 ? "Keine Verbindung zur Anlage" : e.getMessage();
				ui.post(() -> ergebnis.fehler(m));
			} catch (Exception e) {
				Log.w(TAG, "Karte: " + e);
				ui.post(() -> ergebnis.fehler("Keine Verbindung zur Anlage"));
			} finally {
				laeuft = false;
			}
		}).start();
	}
}
