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

	/**
	 * Gesperrt muss die Home-Taste zum Sperrbildschirm fuehren. Nach einem Update der
	 * Geraete-App raeumt Android deren Home-Voreinstellung auf — manchmal erst, nachdem
	 * StartReceiver schon gesperrt hat (24.11.2026: Rosenweg Launcher lag danach offen).
	 * Darum prueft die SperrActivity das laufend und setzt es bei Bedarf neu.
	 * @return true, wenn neu gesetzt werden musste
	 */
	static boolean homeSichern(Context c) {
		if (!istDeviceOwner(c) || Anlage.angemeldet(c)) return false;
		Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
		android.content.pm.ResolveInfo r = c.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
		if (r != null && r.activityInfo != null && homeKomponente(c).getClassName().equals(r.activityInfo.name)
				&& c.getPackageName().equals(r.activityInfo.packageName)) return false;
		try {
			homeAlias(c, true);
			dpm(c).clearPackagePersistentPreferredActivities(admin(c), LAUNCHER_PAKET);
			IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
			home.addCategory(Intent.CATEGORY_HOME);
			home.addCategory(Intent.CATEGORY_DEFAULT);
			dpm(c).addPersistentPreferredActivity(admin(c), home, homeKomponente(c));
			dpm(c).setStatusBarDisabled(admin(c), true);
			Log.w(TAG, "Home zeigte nicht auf den Sperrbildschirm — neu gesetzt");
		} catch (Exception e) {
			Log.e(TAG, "homeSichern", e);
		}
		return true;
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
		// Standard-Telefon-App (Rolle DIALER): ab Android 14 darf der Device Owner das selbst
		if (Build.VERSION.SDK_INT >= 34) {
			try {
				d.setDefaultDialerApplication(TELEFON_APP);
			} catch (Exception e) {
				Log.w(TAG, "Standard-Telefon-App: " + e);
			}
		}
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

	static final String SIP_PERSON_GEAENDERT = "ch.rosenweg4303.SIP_PERSON_GEAENDERT";

	/**
	 * Rosenweg Telefon Bescheid geben: der persoenliche SIP-Zugang hat gewechselt (An-,
	 * Ab- oder Ummelden). Es holt ihn dann ueber AnlageProvider.call("sip_person") neu.
	 * Die Nebenstelle des Geraets (Sipdroid hier in der Geraete-App) bleibt davon unberuehrt.
	 */
	static void sipPersonGeaendert(Context c) {
		try {
			c.sendBroadcast(new Intent(SIP_PERSON_GEAENDERT).setPackage(TELEFON_APP)
					.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES), "ch.rosenweg4303.permission.ANLAGE");
		} catch (Exception e) {
			Log.w(TAG, "SIP_PERSON_GEAENDERT: " + e);
		}
	}

	/** Niemand angemeldet: Sperrbildschirm als HOME, Lock-Task, Statusleiste aus. */
	public static void sperren(Context c) {
		Bildschirmsperre.entfernen(c);   // persoenliche PIN weg, bevor jemand anderes kommt
		Bildschirmsperre.vorbereiten(c); // ohne Sperre ist ein neuer Schluessel sofort aktiv
		Anlage.merke(c, false, null);
		Anlage.prefs(c).edit().remove(PREF_TECHNIK).apply();
		sperrbildschirmText(c);
		Katalog.sperren(c);       // Apps verstecken, persoenliche Daten + Web-Sitzungen loeschen
		startseite(c, "abmelden"); // Rosenweg Launcher: zurueck auf den Standard
		einstellungenSperren(c, false); // gesperrt schuetzt der Lock-Task; adb fuer Wartung offen
		grundregeln(c);   // setzt u.a. die Lock-Task-Erlaubnisliste wieder
		Receiver.updatePhoneAccount(c);
		sipPersonGeaendert(c);    // Rosenweg Telefon: persoenliches SIP-Konto abmelden
		c.stopService(new Intent(c, AnmeldeDienst.class));
		if (istDeviceOwner(c)) {
			DevicePolicyManager d = dpm(c);
			ComponentName a = admin(c);
			try {
				homeAlias(c, true);
				// Festlegung "Launcher = Home" vom Anmelden weg, sonst gewinnt sie
				d.clearPackagePersistentPreferredActivities(a, LAUNCHER_PAKET);
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
		startseite(c, "anmelden"); // Rosenweg Launcher: Startseite der Person laden
		Receiver.updatePhoneAccount(c);
		sipPersonGeaendert(c);    // nach Katalog.freigeben: Rosenweg Telefon ist dann sichtbar
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
		persoenlichePinSetzen(c.getApplicationContext());
		// Nextcloud-Zugang schon holen, damit die Apps ihn sofort haben; Einstellungen je nach Gruppe
		Context app = c.getApplicationContext();
		new Thread(() -> {
			try { Anlage.nextcloudKonto(app); } catch (Exception e) { Log.w(TAG, "Nextcloud-Zugang: " + e); }
			try {
				org.json.JSONObject s = Anlage.status(app);
				if (s.optBoolean("angemeldet")) {
					boolean technik = s.optBoolean("technik");
					Anlage.prefs(app).edit().putBoolean(PREF_TECHNIK, technik).apply();
					einstellungenSperren(app, !technik);
				}
			} catch (Exception e) {
				// Anlage nicht erreichbar (z.B. Nebenstelle umgestellt): den letzten bekannten Stand
				// behalten statt sperren — sonst ist auch adb weg und das Telefon nur noch von Hand
				// wieder erreichbar (24.11.2026).
				Log.w(TAG, "Status fuer Einstellungen: " + e);
				einstellungenSperren(app, !Anlage.prefs(app).getBoolean(PREF_TECHNIK, false));
			}
		}).start();
	}

	/**
	 * Gefaehrliche Einstellungen fuer alle ausser Technik/Praesident (Entscheid Stefan 24.11.2026):
	 * Zuruecksetzen, Entwickleroptionen/USB-Debugging, Konten, Benutzer, App-Verwaltung,
	 * abgesicherter Modus, Netzwerk-Reset. WLAN, Bildschirm, Ton bleiben offen.
	 */
	static final String[] GEFAEHRLICH = {
			android.os.UserManager.DISALLOW_FACTORY_RESET,
			android.os.UserManager.DISALLOW_DEBUGGING_FEATURES,
			android.os.UserManager.DISALLOW_MODIFY_ACCOUNTS,
			android.os.UserManager.DISALLOW_ADD_USER,
			android.os.UserManager.DISALLOW_APPS_CONTROL,
			android.os.UserManager.DISALLOW_SAFE_BOOT,
			android.os.UserManager.DISALLOW_NETWORK_RESET,
			android.os.UserManager.DISALLOW_CONFIG_CREDENTIALS,
	};

	static final String PREF_TECHNIK = "rosenweg_technik";

	static void einstellungenSperren(Context c, boolean sperren) {
		if (!istDeviceOwner(c)) return;
		DevicePolicyManager d = dpm(c);
		ComponentName a = admin(c);
		for (String r : GEFAEHRLICH) {
			try {
				if (sperren) d.addUserRestriction(a, r);
				else d.clearUserRestriction(a, r);
			} catch (Exception e) {
				Log.w(TAG, "Einschraenkung " + r + ": " + e);
			}
		}
		// DISALLOW_DEBUGGING_FEATURES schaltet USB-Debugging aus, das Aufheben aber nicht wieder
		// ein: adb nach dem Entsperren selbst wieder einschalten (Einrichtung, Wartung).
		if (!sperren) {
			try { d.setGlobalSetting(a, android.provider.Settings.Global.ADB_ENABLED, "1"); }
			catch (Exception e) { Log.w(TAG, "adb einschalten: " + e); }
		}
		Log.i(TAG, "Gefaehrliche Einstellungen " + (sperren ? "gesperrt" : "offen"));
	}

	/** Rosenweg Launcher Bescheid geben (Startseite pro Person, RosenwegStartseite). */
	static void startseite(Context c, String was) {
		try {
			c.sendBroadcast(new Intent("ch.rosenweg4303.home.STARTSEITE").setPackage(LAUNCHER_PAKET).putExtra("was", was));
		} catch (Exception e) {
			Log.w(TAG, "Startseite " + was + ": " + e);
		}
	}

	/** Persoenliche PIN der angemeldeten Person von der Anlage holen und als Bildschirmsperre setzen. */
	static void persoenlichePinSetzen(Context c) {
		Bildschirmsperre.vorbereiten(c);
		if (!Bildschirmsperre.bereit(c) || Bildschirmsperre.abmeldungAusstehend(c)) return;
		new Thread(() -> {
			try {
				org.json.JSONObject p = Anlage.pin(c);
				if (p.optBoolean("gesetzt") && Anlage.angemeldet(c)) Bildschirmsperre.setzen(c, p.optString("pin"));
			} catch (Exception e) {
				Log.w(TAG, "PIN holen: " + e);
			}
		}).start();
	}
}
