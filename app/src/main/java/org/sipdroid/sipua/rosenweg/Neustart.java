package org.sipdroid.sipua.rosenweg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Jede Sitzung endet direkt beim Neustart (Stefan 27.11.2026): "weil das die Moeglichkeit ist,
 * ins Telefon zu kommen, wenn sich jemand vergisst auszuloggen." Gilt fuer alle, auch Technik.
 * "Es darf keiner das Telefon bedienen, bevor das nicht erledigt ist."
 *
 * Ablauf:
 * 1. Beim Herunterfahren (ACTION_SHUTDOWN, nur zur Laufzeit empfangbar) und frueh beim Start
 *    (LOCKED_BOOT_COMPLETED, noch vor dem ersten Entsperren) wird der Sperrbildschirm als
 *    HOME gesetzt und die Statusleiste abgeschaltet ({@link Sperre#sperrenFrueh}). Nach dem
 *    Neustart startet Android so gleich den Sperrbildschirm, nicht den Launcher der Person.
 * 2. Ein neuer Boot wird erkannt am Boot-Zaehler (Settings.Global.BOOT_COUNT) und, falls der
 *    fehlt oder ein Broadcast verpasst wurde, an elapsedRealtime (kleiner als zuletzt gesehen).
 *    Dann gilt die Sitzung sofort als beendet ({@link Anlage#angemeldet} = false), die Abmeldung
 *    an der Anlage ist vorgemerkt (Bildschirmsperre, geraetegeschuetzt).
 * 3. Sobald die normalen Einstellungen lesbar sind, raeumt {@link #abschliessen} lokal auf wie
 *    beim Abmelden per Knopf (AnmeldeDienst.abmelden ohne den Netz-Teil) und sperrt.
 * 4. Die Abmeldung an der Anlage holt der Sperrbildschirm nach, sobald Netz da ist; bis dahin
 *    gibt es keine neue Anmeldung (Anlage.einloesen/karte pruefen das ebenfalls).
 * Zusaetzlich schickt jede Anfrage an die Anlage den Boot-Zaehler mit (X-Rosenweg-Boot); die
 * Anlage beendet die Sitzung bei einem neuen Boot selbst.
 */
public final class Neustart {
	private static final String TAG = "RosenwegNeustart";
	private static final String PREFS = "rosenweg_neustart";
	private static final String BOOT = "boot_zahl";
	private static final String ZEIT = "elapsed";
	private static final String LOKAL = "lokal_abmelden";
	/** elapsedRealtime hoechstens jede Minute speichern (wird bei jedem angemeldet() geprueft). */
	private static final long SCHREIBEN_MS = 60_000;

	private Neustart() {}

	private static SharedPreferences prefs(Context c) {
		// geraetegeschuetzt: lesbar auch vor dem ersten Entsperren (LOCKED_BOOT_COMPLETED)
		return (Build.VERSION.SDK_INT >= 24 ? c.createDeviceProtectedStorageContext() : c)
				.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	/** Boot-Zaehler von Android, -1 wenn unbekannt. */
	static int bootZahl(Context c) {
		try {
			return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
		} catch (Exception e) {
			return -1;
		}
	}

	/**
	 * Neuer Boot seit dem letzten Aufruf? Dann wird die lokale Abmeldung und die Abmeldung an
	 * der Anlage vorgemerkt. Billig; darf oft aufgerufen werden, auch vor dem ersten Entsperren.
	 * @return true, wenn (noch) eine Abmeldung nach Neustart lokal offen ist
	 */
	static synchronized boolean offen(Context c) {
		SharedPreferences p = prefs(c);
		int zahl = bootZahl(c);
		long jetzt = SystemClock.elapsedRealtime();
		int alteZahl = p.getInt(BOOT, -1);
		long alteZeit = p.getLong(ZEIT, -1);
		boolean neu = (zahl >= 0 && alteZahl >= 0 && zahl != alteZahl)
				|| (alteZeit >= 0 && jetzt < alteZeit);
		SharedPreferences.Editor e = null;
		if (neu) {
			Log.w(TAG, "Neustart erkannt (Boot " + alteZahl + " -> " + zahl + ", " + alteZeit + " -> " + jetzt
					+ " ms): Sitzung endet");
			e = p.edit().putBoolean(LOKAL, true);
			Bildschirmsperre.abmeldungVormerken(c);
		}
		if (neu || zahl != alteZahl || alteZeit < 0 || jetzt - alteZeit > SCHREIBEN_MS) {
			if (e == null) e = p.edit();
			e.putInt(BOOT, zahl).putLong(ZEIT, jetzt).commit();
		}
		return p.getBoolean(LOKAL, false);
	}

	/** Beim Herunterfahren bzw. Neustart von Hand: Sitzung jetzt schon fuer beendet erklaeren. */
	static void vormerken(Context c) {
		prefs(c).edit().putBoolean(LOKAL, true).commit();
		Bildschirmsperre.abmeldungVormerken(c);
	}

	private static boolean entsperrt(Context c) {
		return Build.VERSION.SDK_INT < 24 || c.getSystemService(UserManager.class).isUserUnlocked();
	}

	/**
	 * Lokal abmelden, falls nach einem Neustart offen — wie AnmeldeDienst.abmelden: gemeinsamen
	 * Speicher leeren, ausdrueckliche Abmeldung vormerken (der Sperrbildschirm meldet an der
	 * Anlage ab, solange sie die Sitzung noch kennt), sperren. Nur im UI-Thread.
	 * @return true, wenn abgemeldet und gesperrt wurde
	 */
	static boolean abschliessen(Context c) {
		if (!Sperre.istDeviceOwner(c) || !entsperrt(c) || !offen(c)) return false;
		Log.w(TAG, "Nach Neustart: lokal abmelden" + (Anlage.prefs(c).getBoolean(Anlage.PREF_ANGEMELDET, false)
				? " (" + Anlage.prefs(c).getString(Anlage.PREF_NAME, "") + ")" : ""));
		Aufraeumen.gemeinsamenSpeicherLeeren(c);
		Sperre.abmeldungVormerken(c, true);
		prefs(c).edit().putBoolean(LOKAL, false).commit();
		Sperre.sperren(c);
		return true;
	}

	private static BroadcastReceiver aus;

	/** Aus SipdroidApp.onCreate: Herunterfahren mitbekommen (Android schickt es nur an Laufzeit-Empfaenger). */
	public static synchronized void beobachten(Context c) {
		if (aus != null) return;
		final Context app = c.getApplicationContext();
		aus = new BroadcastReceiver() {
			@Override
			public void onReceive(Context k, Intent i) {
				if (!Sperre.istDeviceOwner(app)) return;
				Log.w(TAG, "Herunterfahren: Sperrbildschirm als Startbildschirm, Sitzung endet");
				Sperre.sperrenFrueh(app);
				vormerken(app);
				Bildschirmsperre.entfernen(app);   // persoenliche PIN weg: nach dem Start sofort der Sperrbildschirm
			}
		};
		IntentFilter f = new IntentFilter(Intent.ACTION_SHUTDOWN);
		f.addAction("android.intent.action.REBOOT");
		f.addAction("android.intent.action.QUICKBOOT_POWEROFF");
		try {
			if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(aus, f, Context.RECEIVER_EXPORTED);
			else app.registerReceiver(aus, f);
		} catch (Exception e) {
			Log.w(TAG, "Herunterfahren beobachten: " + e);
		}
	}
}
