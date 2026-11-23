package org.sipdroid.sipua.rosenweg;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

import org.sipdroid.sipua.ui.Receiver;

/**
 * Sperrt und entsperrt das geteilte Telefon.
 *
 * Gesperrt (niemand angemeldet): SperrActivity ist Startbildschirm (HOME) und
 * laeuft im Lock-Task-Modus. Erlaubt sind nur diese App, die Anruf-Oberflaeche
 * (damit eingehende Anrufe angenommen werden koennen) und Samsungs
 * Notruf-Waehler, der nur Notrufnummern zulaesst. Telefonieren sperrt
 * zusaetzlich die Anlage selbst (Dialplan [geteilt]).
 *
 * Entsperrt (angemeldet): normaler Startbildschirm, AnmeldeDienst zeigt die
 * Anmeldung als Benachrichtigung mit "Abmelden" und sperrt wieder, sobald die
 * Anlage die Sitzung nicht mehr kennt.
 *
 * Alles hier wirkt nur, wenn die App Device Owner ist
 * (adb shell dpm set-device-owner ch.rosenweg4303.telefon/org.sipdroid.sipua.rosenweg.AdminReceiver).
 * Ohne Device Owner zeigt die App die Anmeldung trotzdem, kann aber nichts sperren.
 */
public final class Sperre {
	private static final String TAG = "RosenwegSperre";

	/** Was im gesperrten Zustand laufen darf (Lock-Task-Allowlist). */
	private static final String[] ERLAUBT_GESPERRT = {
			// Samsung (Galaxy A15)
			"com.samsung.android.incallui",      // Anruf-Bildschirm (eingehend annehmen)
			"com.samsung.android.emergency",     // Notruf-Waehler, nur Notrufnummern
			// AOSP / Google (Emulator, Pixel): Anruf-Oberflaeche steckt im Dialer,
			// der Notruf-Waehler in com.android.phone.
			"com.google.android.dialer",
			"com.android.dialer",
			"com.android.server.telecom",
			"com.android.phone",
	};

	private Sperre() {}

	public static ComponentName admin(Context c) {
		return new ComponentName(c, AdminReceiver.class);
	}

	public static DevicePolicyManager dpm(Context c) {
		return (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
	}

	public static boolean istDeviceOwner(Context c) {
		DevicePolicyManager d = dpm(c);
		return d != null && d.isDeviceOwnerApp(c.getPackageName());
	}

	private static ComponentName homeKomponente(Context c) {
		return new ComponentName(c.getPackageName(), SperrActivity.class.getPackage().getName() + ".SperrHome");
	}

	/** Der HOME-Alias ist nur gesperrt aktiv — sonst fragte Android nach dem Startbildschirm. */
	private static void homeAlias(Context c, boolean an) {
		c.getPackageManager().setComponentEnabledSetting(homeKomponente(c),
				an ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
				PackageManager.DONT_KILL_APP);
	}

	/** Einmalig nach dem Setzen als Device Owner (und bei jedem Start, idempotent). */
	public static void grundregeln(Context c) {
		if (!istDeviceOwner(c)) return;
		DevicePolicyManager d = dpm(c);
		ComponentName a = admin(c);
		try {
			// Laufzeitrechte der eigenen App ohne Rueckfrage — sonst bleibt ein
			// geteiltes Telefon an einem Dialog haengen, den niemand bestaetigt.
			String[] rechte = {
					android.Manifest.permission.RECORD_AUDIO,
					android.Manifest.permission.CAMERA,          // Anmelde-QR scannen
					android.Manifest.permission.READ_CONTACTS,   // Telefonbuch "Rosenweg"
					android.Manifest.permission.WRITE_CONTACTS,
					android.Manifest.permission.READ_PHONE_STATE,
					android.Manifest.permission.CALL_PHONE,
					"android.permission.BLUETOOTH_CONNECT",
					"android.permission.POST_NOTIFICATIONS",
			};
			for (String r : rechte) {
				try {
					d.setPermissionGrantState(a, c.getPackageName(), r,
							DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
				} catch (Exception e) {
					Log.w(TAG, "Recht " + r + ": " + e.getMessage());
				}
			}
			// "Rosenweg" ueberall, wo Android es zulaesst. Der Netzbetreiber-Name in
			// der Statusleiste gehoert nicht dazu: den liefert das Modem aus der
			// SIM, auch ein Device Owner kann ihn nicht setzen.
			if (Build.VERSION.SDK_INT >= 24) {
				d.setOrganizationName(a, "Rosenweg");
				d.setDeviceOwnerLockScreenInfo(a, "Rosenweg Telefon · Nebenstelle " + Anlage.nebenstelle(c));
			}
			d.addUserRestriction(a, UserManager.DISALLOW_FACTORY_RESET);
			d.addUserRestriction(a, UserManager.DISALLOW_SAFE_BOOT);
			d.addUserRestriction(a, UserManager.DISALLOW_ADD_USER);
			// Bewusst NICHT DISALLOW_DEBUGGING_FEATURES: adb bleibt zum Einrichten.
			String[] erlaubt = new String[ERLAUBT_GESPERRT.length + 1];
			erlaubt[0] = c.getPackageName();
			System.arraycopy(ERLAUBT_GESPERRT, 0, erlaubt, 1, ERLAUBT_GESPERRT.length);
			d.setLockTaskPackages(a, erlaubt);
			Ausblenden.anwenden(c);   // gemerkte Liste, auch ohne Netz
			if (Build.VERSION.SDK_INT >= 28) {
				// Im gesperrten Zustand: keine Benachrichtigungen, kein Home/Overview,
				// keine Schnelleinstellungen. Uhr/Akku (SYSTEM_INFO) und der
				// Ein/Aus-Dialog (GLOBAL_ACTIONS, fuer Neustart) bleiben.
				d.setLockTaskFeatures(a, DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
						| DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS);
			}
		} catch (Exception e) {
			Log.e(TAG, "grundregeln", e);
		}
	}

	/** Niemand angemeldet: Sperrbildschirm als HOME, Lock-Task, Statusleiste aus. */
	public static void sperren(Context c) {
		Anlage.merke(c, false, null);
		Katalog.sperren(c);       // Apps verstecken, persoenliche Daten + Web-Sitzungen loeschen
		grundregeln(c);   // setzt u.a. die Lock-Task-Erlaubnisliste wieder
		Receiver.updatePhoneAccount(c);
		c.stopService(new Intent(c, AnmeldeDienst.class));
		if (istDeviceOwner(c)) {
			DevicePolicyManager d = dpm(c);
			ComponentName a = admin(c);
			try {
				homeAlias(c, true);
				IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
				home.addCategory(Intent.CATEGORY_HOME);
				home.addCategory(Intent.CATEGORY_DEFAULT);
				d.addPersistentPreferredActivity(a, home, homeKomponente(c));
				d.setStatusBarDisabled(a, true);
			} catch (Exception e) {
				Log.e(TAG, "sperren", e);
			}
		}
		Intent i = new Intent(c, SperrActivity.class);
		i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		c.startActivity(i);
	}

	/** Angemeldet: normaler Startbildschirm zurueck, Apps der Person, Dienst mit Benachrichtigung. */
	public static void freigeben(Context c, String name, org.json.JSONArray apps) {
		Anlage.merke(c, true, name);
		Katalog.freigeben(c, apps != null ? apps : Katalog.freigegeben(c));
		Receiver.updatePhoneAccount(c);
		if (istDeviceOwner(c)) {
			DevicePolicyManager d = dpm(c);
			ComponentName a = admin(c);
			try {
				// Erlaubnisliste leeren beendet den Lock-Task-Modus zuverlaessig —
				// stopLockTask() aus der Activity griff nicht, wenn der Task per
				// lockTaskMode="if_whitelisted" gestartet worden war (Emulator-Test
				// 23.11.2026: Launcher lief danach in "Lock Task Mode violation").
				d.setLockTaskPackages(a, new String[0]);
				d.clearPackagePersistentPreferredActivities(a, c.getPackageName());
				homeAlias(c, false);
				d.setStatusBarDisabled(a, false);
			} catch (Exception e) {
				Log.e(TAG, "freigeben", e);
			}
		}
		Intent dienst = new Intent(c, AnmeldeDienst.class);
		if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(dienst);
		else c.startService(dienst);
	}
}
