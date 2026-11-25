package org.sipdroid.sipua.rosenweg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * Rueckmeldung des Rosenweg Launchers (ab v27) nach "ch.rosenweg4303.home.STARTSEITE":
 * Startseite der Person geladen bzw. auf Standard zurueckgesetzt (Stefan 25.11.2026,
 * Einrichtungsbildschirm). Nur mit der Signatur-Berechtigung ANLAGE (Manifest).
 *
 * Extras: was (anmelden/abmelden), symbole (Elemente der Startseite), standard (true = die
 * Standard-Startseite gilt, der Launcher baut sie beim ersten Anzeigen auf), fehler (Text).
 */
public class StartseiteGeladen extends BroadcastReceiver {
	private static final String TAG = "RosenwegStartseite";
	static final String AKTION = "ch.rosenweg4303.telefon.STARTSEITE_GELADEN";

	private static final Object SPERRE = new Object();
	private static Antwort letzte;

	static final class Antwort {
		final long um;
		final String was;
		final int symbole;
		final boolean standard;
		final String fehler;

		Antwort(long um, String was, int symbole, boolean standard, String fehler) {
			this.um = um;
			this.was = was;
			this.symbole = symbole;
			this.standard = standard;
			this.fehler = fehler;
		}
	}

	@Override
	public void onReceive(Context c, Intent i) {
		if (!AKTION.equals(i.getAction())) return;
		Antwort a = new Antwort(SystemClock.elapsedRealtime(), i.getStringExtra("was"),
				i.getIntExtra("symbole", -1), i.getBooleanExtra("standard", false), i.getStringExtra("fehler"));
		Log.i(TAG, "Launcher meldet: " + a.was + ", " + a.symbole + " Symbole" + (a.standard ? ", Standard" : "")
				+ (a.fehler != null ? ", Fehler: " + a.fehler : ""));
		synchronized (SPERRE) {
			letzte = a;
			SPERRE.notifyAll();
		}
	}

	/**
	 * Blockiert, bis eine Rueckmeldung fuer [was] eintrifft, die nach [seit] (elapsedRealtime) kam.
	 * @return die Rueckmeldung oder null nach [ms]
	 */
	static Antwort warten(String was, long seit, long ms) throws InterruptedException {
		long bis = SystemClock.elapsedRealtime() + ms;
		synchronized (SPERRE) {
			while (true) {
				Antwort a = letzte;
				if (a != null && a.um >= seit && was.equals(a.was)) return a;
				long rest = bis - SystemClock.elapsedRealtime();
				if (rest <= 0) return null;
				SPERRE.wait(rest);
			}
		}
	}
}
