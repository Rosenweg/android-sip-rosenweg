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
 * einem Neustart die PIN der zuletzt angemeldeten Person.
 */
public final class Bildschirmsperre {
	private static final String TAG = "Bildschirmsperre";
	private static final String PREFS = "rosenweg_bildschirmsperre";
	private static final String TOKEN = "token";
	private static final String GESETZT = "gesetzt";
	private static final String ABMELDEN = "nach_neustart_abmelden";

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
	 * Direkt nach dem Neustart (vor dem ersten Entsperren): PIN entfernen und die
	 * Abmeldung vormerken. Die PIN darf nur zusammen mit einer Abmeldung weg
	 * (Entscheid Stefan 24.11.2026) — sonst kaeme, wer neu startet, ohne PIN in
	 * die Sitzung der Person.
	 */
	public static void nachNeustart(Context c) {
		if (!prefs(c).getBoolean(GESETZT, false)) return;
		if (entfernen(c)) prefs(c).edit().putBoolean(ABMELDEN, true).commit();
	}

	public static boolean abmeldungAusstehend(Context c) {
		return prefs(c).getBoolean(ABMELDEN, false);
	}

	/** Vorgemerkte Abmeldung an der Anlage ausfuehren (Netz noetig). @return true wenn erledigt */
	public static boolean abmeldungAusfuehren(Context c) {
		if (!abmeldungAusstehend(c)) return true;
		try {
			Anlage.abmelden(c);
			prefs(c).edit().putBoolean(ABMELDEN, false).commit();
			Log.i(TAG, "Nach Neustart abgemeldet");
			return true;
		} catch (Anlage.AnlageFehler e) {
			if (e.status >= 500) return false;
			// 4xx: Geraet nicht (mehr) freigegeben — es gibt keine Sitzung abzumelden
			prefs(c).edit().putBoolean(ABMELDEN, false).commit();
			Log.w(TAG, "Abmelden nach Neustart: " + e.status + ", nichts abzumelden");
			return true;
		} catch (Exception e) {
			Log.w(TAG, "Abmelden nach Neustart: " + e);
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
