package org.sipdroid.sipua.rosenweg;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import java.security.SecureRandom;

/**
 * Persoenliche PIN als Android-Bildschirmsperre, solange die Person angemeldet ist
 * (Entscheid 24.11.2026: PIN liegt verschluesselt auf der Anlage).
 *
 * Der Device Owner setzt und entfernt die Sperre mit einem eigenen Schluessel
 * (resetPasswordWithToken). Der Schluessel liegt im geraetegeschuetzten Speicher,
 * damit die Sperre auch direkt nach einem Neustart — vor dem ersten Entsperren —
 * wieder entfernt werden kann (BootEntsperren). Sonst verlangte das Telefon nach
 * einem Neustart die PIN der zuletzt angemeldeten Person, bevor Geraet laufen kann.
 * Laeuft die Sitzung an der Anlage weiter, setzt Geraet die PIN danach wieder (v239).
 */
public final class Bildschirmsperre {
	private static final String TAG = "Bildschirmsperre";
	private static final String PREFS = "rosenweg_bildschirmsperre";
	private static final String TOKEN = "token";
	private static final String GESETZT = "gesetzt";
	/** bis v238: Abmeldung nach Neustart vorgemerkt (wird nur noch entfernt) */
	private static final String ABMELDEN_ALT = "nach_neustart_abmelden";
	private static final String NEUSTART = "nach_neustart_wiederherstellen";

	private Bildschirmsperre() {}

	private static SharedPreferences prefs(Context c) {
		// geraetegeschuetzt: lesbar auch vor dem ersten Entsperren
		return c.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	private static DevicePolicyManager dpm(Context c) {
		return (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
	}

	private static ComponentName admin(Context c) {
		return new ComponentName(c, AdminReceiver.class);
	}

	private static byte[] token(Context c) {
		String t = prefs(c).getString(TOKEN, null);
		return t == null ? null : Base64.decode(t, Base64.NO_WRAP);
	}

	/**
	 * Schluessel hinterlegen. Nur ohne gesetzte Sperre ist er sofort aktiv —
	 * darum beim Einrichten bzw. im gesperrten Zustand (niemand angemeldet) aufrufen.
	 */
	public static void vorbereiten(Context c) {
		if (Build.VERSION.SDK_INT < 26 || !Sperre.istDeviceOwner(c)) return;
		try {
			DevicePolicyManager d = dpm(c);
			byte[] t = token(c);
			if (t != null && d.isResetPasswordTokenActive(admin(c))) return;
			t = new byte[32];
			new SecureRandom().nextBytes(t);
			if (d.setResetPasswordToken(admin(c), t)) {
				prefs(c).edit().putString(TOKEN, Base64.encodeToString(t, Base64.NO_WRAP)).commit();
			}
			Log.i(TAG, "Schluessel hinterlegt, aktiv: " + d.isResetPasswordTokenActive(admin(c)));
		} catch (Exception e) {
			Log.e(TAG, "vorbereiten", e);
		}
	}

	public static boolean bereit(Context c) {
		if (Build.VERSION.SDK_INT < 26 || !Sperre.istDeviceOwner(c) || token(c) == null) return false;
		try {
			return dpm(c).isResetPasswordTokenActive(admin(c));
		} catch (Exception e) {
			return false;
		}
	}

	/** PIN als Bildschirmsperre setzen. @return true bei Erfolg */
	public static boolean setzen(Context c, String pin) {
		if (!bereit(c) || pin == null || pin.isEmpty()) return false;
		try {
			boolean ok = dpm(c).resetPasswordWithToken(admin(c), pin, token(c), 0);
			if (ok) prefs(c).edit().putBoolean(GESETZT, true).commit();
			Log.i(TAG, "PIN gesetzt: " + ok);
			return ok;
		} catch (Exception e) {
			Log.e(TAG, "setzen", e);
			return false;
		}
	}

	/**
	 * Direkt nach dem Neustart (vor dem ersten Entsperren): PIN entfernen und den Neustart
	 * vormerken. Bis v238 wurde die Person dabei abgemeldet (Entscheid 24.11.2026: "wer vergisst,
	 * sich abzumelden, wird durch den Neustart abgemeldet"). Seit v239 (Stefan 26.11.2026: "nach
	 * einem Neustart abgemeldet - das darf nicht sein") bleibt die Sitzung: die SperrActivity
	 * prueft sie an der Anlage, stellt sie wieder her, setzt die PIN von der Anlage neu und sperrt
	 * den Bildschirm (lockNow), bevor die Person wieder hineinkommt. Wer neu startet, kommt so
	 * weiterhin nicht ohne PIN in die Sitzung, und das Telefon haengt nie an der PIN einer Person.
	 */
	public static void nachNeustart(Context c) {
		prefs(c).edit().putBoolean(NEUSTART, true).remove(ABMELDEN_ALT).commit();
		entfernen(c);
	}

	/** Neustart vorgemerkt, Sitzung noch nicht wiederhergestellt (PIN und Sperre ausstehend). */
	public static boolean neustartOffen(Context c) {
		return prefs(c).getBoolean(NEUSTART, false);
	}

	public static void neustartErledigt(Context c) {
		if (neustartOffen(c)) prefs(c).edit().remove(NEUSTART).commit();
	}

	/** Bildschirm sofort sperren (Device Owner, Richtlinie force-lock). @return true bei Erfolg */
	public static boolean jetztSperren(Context c) {
		try {
			dpm(c).lockNow();
			Log.i(TAG, "Bildschirm gesperrt");
			return true;
		} catch (Exception e) {
			Log.e(TAG, "lockNow", e);
			return false;
		}
	}

	/** Sperre entfernen (Abmelden, Neustart). Ohne Netz, auch vor dem ersten Entsperren. */
	public static boolean entfernen(Context c) {
		SharedPreferences p = prefs(c);
		if (!p.getBoolean(GESETZT, false)) return true;
		byte[] t = token(c);
		if (Build.VERSION.SDK_INT < 26 || t == null) return false;
		try {
			boolean ok = dpm(c).resetPasswordWithToken(admin(c), null, t, 0);
			if (ok) p.edit().putBoolean(GESETZT, false).commit();
			Log.i(TAG, "PIN entfernt: " + ok);
			return ok;
		} catch (Exception e) {
			Log.e(TAG, "entfernen", e);
			return false;
		}
	}
}
