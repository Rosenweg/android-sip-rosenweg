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
			// Rosenweg Phone (Fossify-Fork): Anruf-Bildschirm, sobald Standard-Telefon-App
			"ch.rosenweg4303.phone",
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

	/**
	 * Text auf dem Android-Sperrbildschirm, unter "Dieses Geraet gehoert Rosenweg":
	 * "Rosenweg Smartphone 2 · Stefan Mueller" bzw. "· nicht angemeldet".
	 */
	static void sperrbildschirmText(Context c) {
		if (!istDeviceOwner(c) || Build.VERSION.SDK_INT < 24) return;
		String wer = Anlage.angemeldet(c) && !Anlage.name(c).isEmpty() ? Anlage.name(c) : "nicht angemeldet";
		try {
			dpm(c).setDeviceOwnerLockScreenInfo(admin(c), Anlage.geraet(c) + " · " + wer);
		} catch (Exception e) {
			Log.w(TAG, "Sperrbildschirm-Text: " + e);
		}
	}

	/** Rosenweg Launcher (Fossify-Fork) — sonst fragt Android bei zwei Launchern nach. */
	static final String LAUNCHER_PAKET = "ch.rosenweg4303.home";

	private static void rosenwegLauncherAlsHome(Context c, DevicePolicyManager d, ComponentName a) {
		if (!Katalog.installiert(c, LAUNCHER_PAKET)) return;
		try {
			IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
			home.addCategory(Intent.CATEGORY_HOME);
			home.addCategory(Intent.CATEGORY_DEFAULT);
			d.addPersistentPreferredActivity(a, home,
					new ComponentName(LAUNCHER_PAKET, "org.fossify.home.activities.MainActivity"));
		} catch (Exception e) {
			Log.w(TAG, "Launcher als HOME: " + e);
		}
	}

	static final String TELEFON_APP = "ch.rosenweg4303.phone";

	/**
	 * "Anrufen", "Waehlen" und tel:-Links gehen fest an Rosenweg Phone — ohne
	 * Rueckfrage "Telefon oder Telefonanrufe". Notrufe nimmt Android trotzdem
	 * zwingend ueber die System-Telefon-App (Telecom, EmergencyInCallServiceConnection);
	 * die bleibt darum installiert, ist im Rosenweg Launcher aber unsichtbar.
	 */
	private static void telefonAppFestlegen(Context c, DevicePolicyManager d, ComponentName a) {
		if (!Katalog.installiert(c, TELEFON_APP)) return;
		ComponentName waehlen = new ComponentName(TELEFON_APP, "org.fossify.phone.activities.DialpadActivity");
		ComponentName anrufen = new ComponentName(TELEFON_APP, "org.fossify.phone.activities.DialerActivity");
		try {
			IntentFilter dialOhne = new IntentFilter(Intent.ACTION_DIAL);
			dialOhne.addCategory(Intent.CATEGORY_DEFAULT);
			d.addPersistentPreferredActivity(a, dialOhne, waehlen);
			IntentFilter dialTel = new IntentFilter(Intent.ACTION_DIAL);
			dialTel.addCategory(Intent.CATEGORY_DEFAULT);
			dialTel.addDataScheme("tel");
			d.addPersistentPreferredActivity(a, dialTel, waehlen);
			IntentFilter viewTel = new IntentFilter(Intent.ACTION_VIEW);
			viewTel.addCategory(Intent.CATEGORY_DEFAULT);
			viewTel.addCategory(Intent.CATEGORY_BROWSABLE);
			viewTel.addDataScheme("tel");
			d.addPersistentPreferredActivity(a, viewTel, waehlen);
			IntentFilter callTel = new IntentFilter(Intent.ACTION_CALL);
			callTel.addCategory(Intent.CATEGORY_DEFAULT);
			callTel.addDataScheme("tel");
			d.addPersistentPreferredActivity(a, callTel, anrufen);
		} catch (Exception e) {
			Log.w(TAG, "Telefon-App festlegen: " + e);
		}
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
			}
			sperrbildschirmText(c);
			d.addUserRestriction(a, UserManager.DISALLOW_FACTORY_RESET);
			d.addUserRestriction(a, UserManager.DISALLOW_SAFE_BOOT);
			d.addUserRestriction(a, UserManager.DISALLOW_ADD_USER);
			// Niemand installiert nach (Play Store, Galaxy Store, OMC-Agent haben
			// auf dem A15 nach der Einrichtung still LinkedIn, Office, Spiele
			// nachgeschoben). Gilt auch fuer uns: Katalog.installieren hebt es nur
			// fuer die eigene Installation kurz auf.
			d.addUserRestriction(a, UserManager.DISALLOW_INSTALL_APPS);
			d.addUserRestriction(a, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY);
			// Bewusst NICHT DISALLOW_DEBUGGING_FEATURES: adb bleibt zum Einrichten.
			String[] erlaubt = new String[ERLAUBT_GESPERRT.length + 1];
			erlaubt[0] = c.getPackageName();
			System.arraycopy(ERLAUBT_GESPERRT, 0, erlaubt, 1, ERLAUBT_GESPERRT.length);
			d.setLockTaskPackages(a, erlaubt);
			telefonAppFestlegen(c, d, a);
			Tastatur.anwenden(c);     // vor dem Ausblenden: die aktive Tastatur bleibt geschuetzt
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
		sperrbildschirmText(c);
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
		sperrbildschirmText(c);
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
				rosenwegLauncherAlsHome(c, d, a);
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
