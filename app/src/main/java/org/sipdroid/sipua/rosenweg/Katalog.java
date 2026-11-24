package org.sipdroid.sipua.rosenweg;

import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * App-Katalog des geteilten Telefons (kommt von der Anlage, /api/telefon/katalog).
 *
 * - Alle APKs des Katalogs werden still installiert (Device Owner) und sind
 *   versteckt, solange niemand angemeldet ist.
 * - Bei der Anmeldung: die fuer die Person freigegebenen Apps einblenden und
 *   ihnen ihre Einstellungen geben (Managed Configurations).
 * - Beim Abmelden: alles ausblenden, Daten persoenlicher Apps loeschen, die
 *   Web-Sitzungen (Cookies, Speicher) der Rosenweg-App loeschen.
 */
public final class Katalog {
	private static final String TAG = "RosenwegKatalog";
	static final String PREF_KATALOG = "rosenweg_katalog";       // alle Apps (JSON-Array)
	static final String PREF_FREIGEGEBEN = "rosenweg_apps";      // fuer die angemeldete Person
	private static final String PREF_SHA = "rosenweg_apk_sha_";  // + Paket: zuletzt installiert
	/** Pakete, die diese App selbst installiert hat — nur die darf sie wieder entfernen. */
	private static final String PREF_VERWALTET = "rosenweg_verwaltet";

	private Katalog() {}

	public static JSONArray katalog(Context c) {
		return liste(Anlage.prefs(c).getString(PREF_KATALOG, "[]"));
	}

	public static JSONArray freigegeben(Context c) {
		return liste(Anlage.prefs(c).getString(PREF_FREIGEGEBEN, "[]"));
	}

	static void merkeFreigegeben(Context c, JSONArray apps) {
		Anlage.prefs(c).edit().putString(PREF_FREIGEGEBEN, apps == null ? "[]" : apps.toString()).apply();
	}

	private static JSONArray liste(String s) {
		try {
			return new JSONArray(s);
		} catch (Exception e) {
			return new JSONArray();
		}
	}

	// ─── Synchronisieren (Hintergrund-Thread!) ──────────────────────────────

	private static final String PREF_ABGLEICH = "rosenweg_abgleich_zeit";
	private static final long ABGLEICH_ALLE_MS = 6 * 3600 * 1000L;
	private static volatile boolean gleichtAb;

	/** Katalog + Telefonbuch im Hintergrund abgleichen, hoechstens alle 6 h (ausser erzwungen). */
	public static void vielleichtAbgleichen(Context c, boolean erzwingen) {
		Context app = c.getApplicationContext();
		long zuletzt = Anlage.prefs(app).getLong(PREF_ABGLEICH, 0);
		if (gleichtAb || (!erzwingen && System.currentTimeMillis() - zuletzt < ABGLEICH_ALLE_MS)) return;
		if (!Anlage.eingerichtet(app)) return;
		gleichtAb = true;
		new Thread(() -> {
			try {
				synchronisieren(app);
				Kontakte.abgleichen(app);
				Anlage.prefs(app).edit().putLong(PREF_ABGLEICH, System.currentTimeMillis()).apply();
			} finally {
				gleichtAb = false;
			}
		}, "rosenweg-abgleich").start();
	}

	/** Katalog holen und fehlende/geaenderte APKs installieren. Blockiert. */
	public static void synchronisieren(Context c) {
		if (!Sperre.istDeviceOwner(c)) return;
		// Nur ein Abgleich gleichzeitig (Anmeldung, AnmeldeDienst und adb kamen sich in die
		// Quere und stiessen dieselbe Installation mehrfach an).
		synchronized (ABGLEICH) { abgleichen(c); }
	}

	private static final Object ABGLEICH = new Object();

	private static void abgleichen(Context c) {
		try {
			JSONObject k = Anlage.katalog(c);
			JSONArray apps = k.getJSONArray("apps");
			Anlage.prefs(c).edit().putString(PREF_KATALOG, apps.toString()).apply();
			Ausblenden.merkeListe(c, k.optJSONArray("ausblenden"));
			Ausblenden.merkeUnsichtbar(c, k.optJSONArray("unsichtbar"));
			Ausblenden.anwenden(c);
			entfernteDeinstallieren(c, apkPakete(apps));
			for (int i = 0; i < apps.length(); i++) {
				JSONObject a = apps.getJSONObject(i);
				if (!"apk".equals(a.optString("art")) || a.isNull("download")) continue;
				String paket = a.getString("paket");
				String sha = a.optString("sha256", "");
				// Massgebend ist die tatsaechlich installierte APK, nicht nur die gemerkte
				// Pruefsumme: beim Selbst-Update beendet Android die App vor der
				// Erfolgsmeldung, die Pruefsumme wurde nie gemerkt und die App installierte
				// sich bei jedem Abgleich erneut.
				if (installiert(c, paket)) {
					if (sha.equals(Anlage.prefs(c).getString(PREF_SHA + paket, ""))
							&& sha.equalsIgnoreCase(installierteSha(c, paket))) continue;
					if (sha.equalsIgnoreCase(installierteSha(c, paket))) {
						installiertGemerkt(c, paket, sha);
						continue;
					}
				}
				installieren(c, paket, a.getString("download"), sha);
			}
		} catch (Exception e) {
			Log.w(TAG, "synchronisieren: " + e);
		}
	}

	static boolean installiert(Context c, String paket) {
		try {
			int flags = Build.VERSION.SDK_INT >= 24 ? PackageManager.MATCH_UNINSTALLED_PACKAGES : 0;
			c.getPackageManager().getPackageInfo(paket, flags);
			return true;
		} catch (PackageManager.NameNotFoundException e) {
			return false;
		}
	}

	private static final String PREF_IST = "rosenweg_apk_ist_";  // + Paket: Datei-Stand:Pruefsumme

	/** SHA-256 der installierten base.apk, zwischengespeichert nach Pfad, Groesse und Zeit. */
	static String installierteSha(Context c, String paket) {
		try {
			File f = new File(c.getPackageManager().getApplicationInfo(paket, 0).sourceDir);
			String stand = f.getPath() + "|" + f.length() + "|" + f.lastModified();
			String gemerkt = Anlage.prefs(c).getString(PREF_IST + paket, "");
			int k = gemerkt.lastIndexOf(':');
			if (k > 0 && gemerkt.substring(0, k).equals(stand)) return gemerkt.substring(k + 1);
			String sha = sha256(f);
			Anlage.prefs(c).edit().putString(PREF_IST + paket, stand + ":" + sha).apply();
			return sha;
		} catch (Exception e) {
			Log.w(TAG, "installierteSha " + paket + ": " + e);
			return "";
		}
	}

	private static void installieren(Context c, String paket, String pfad, String sha) throws Exception {
		File datei = new File(c.getCacheDir(), "apk-" + paket + ".apk");
		Anlage.herunterladen(c, pfad, datei);
		String ist = sha256(datei);
		if (!ist.equalsIgnoreCase(sha)) {
			datei.delete();
			throw new SecurityException("SHA-256 stimmt nicht: " + paket);
		}
		installationErlauben(c, true);
		PackageInstaller pi = c.getPackageManager().getPackageInstaller();
		PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
		p.setAppPackageName(paket);
		if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
		int id = pi.createSession(p);
		try (PackageInstaller.Session s = pi.openSession(id)) {
			try (OutputStream o = s.openWrite("base.apk", 0, datei.length());
				 InputStream in = new FileInputStream(datei)) {
				byte[] puffer = new byte[65536];
				int n;
				while ((n = in.read(puffer)) > 0) o.write(puffer, 0, n);
				s.fsync(o);
			}
			Intent fertig = new Intent(c, InstallEmpfaenger.class)
					.putExtra(InstallEmpfaenger.EXTRA_PAKET, paket)
					.putExtra(InstallEmpfaenger.EXTRA_SHA, sha);
			int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
			s.commit(PendingIntent.getBroadcast(c, id, fertig, f).getIntentSender());
		} finally {
			datei.delete();
		}
		Log.i(TAG, "Installation angestossen: " + paket);
	}

	/**
	 * DISALLOW_INSTALL_APPS gilt auch fuer den Device Owner. Fuer die eigene
	 * Installation kurz aufheben; InstallEmpfaenger setzt es danach wieder.
	 * Sicherheitsnetz: nach 2 Minuten wird es in jedem Fall wieder gesetzt.
	 */
	static void installationErlauben(Context c, boolean erlauben) {
		installationErlauben(c, erlauben, 0);
	}

	static void installationErlauben(Context c, boolean erlauben, long dauerMs) {
		if (!Sperre.istDeviceOwner(c)) return;
		try {
			if (erlauben) {
				Sperre.dpm(c).clearUserRestriction(Sperre.admin(c), android.os.UserManager.DISALLOW_INSTALL_APPS);
				new android.os.Handler(android.os.Looper.getMainLooper())
						.postDelayed(() -> installationErlauben(c, false), dauerMs > 0 ? dauerMs : 120_000);
			} else {
				Sperre.dpm(c).addUserRestriction(Sperre.admin(c), android.os.UserManager.DISALLOW_INSTALL_APPS);
			}
		} catch (Exception e) {
			Log.w(TAG, "installationErlauben " + erlauben + ": " + e);
		}
	}

	static void installiertGemerkt(Context c, String paket, String sha) {
		java.util.Set<String> v = new java.util.HashSet<>(
				Anlage.prefs(c).getStringSet(PREF_VERWALTET, new java.util.HashSet<>()));
		v.add(paket);
		Anlage.prefs(c).edit().putString(PREF_SHA + paket, sha).putStringSet(PREF_VERWALTET, v).apply();
	}

	/**
	 * Apps, die aus dem Katalog entfernt wurden, wieder deinstallieren — aber nur
	 * solche, die diese App selbst installiert hat. Sonst bliebe z.B. eine
	 * ersetzte App nach der naechsten Anmeldung sichtbar.
	 */
	private static void entfernteDeinstallieren(Context c, List<String> imKatalog) {
		java.util.Set<String> verwaltet = new java.util.HashSet<>(
				Anlage.prefs(c).getStringSet(PREF_VERWALTET, new java.util.HashSet<>()));
		// Aeltere Installationen (vor dieser Liste) erkennt man am gemerkten SHA.
		for (String k : Anlage.prefs(c).getAll().keySet()) {
			if (k.startsWith(PREF_SHA)) verwaltet.add(k.substring(PREF_SHA.length()));
		}
		java.util.Set<String> bleibt = new java.util.HashSet<>();
		PackageInstaller pi = c.getPackageManager().getPackageInstaller();
		verwaltet.remove(c.getPackageName());   // sich selbst nie deinstallieren
		for (String paket : verwaltet) {
			if (imKatalog.contains(paket)) {
				bleibt.add(paket);
				continue;
			}
			Anlage.prefs(c).edit().remove(PREF_SHA + paket).apply();
			if (!installiert(c, paket)) continue;
			try {
				Intent fertig = new Intent(c, InstallEmpfaenger.class).putExtra(InstallEmpfaenger.EXTRA_PAKET, paket)
						.putExtra(InstallEmpfaenger.EXTRA_ENTFERNT, true);
				int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
				pi.uninstall(paket, PendingIntent.getBroadcast(c, paket.hashCode(), fertig, f).getIntentSender());
				Log.i(TAG, "Deinstallation angestossen: " + paket);
			} catch (Exception e) {
				Log.w(TAG, "deinstallieren " + paket + ": " + e);
				bleibt.add(paket);   // naechstes Mal wieder versuchen
			}
		}
		Anlage.prefs(c).edit().putStringSet(PREF_VERWALTET, bleibt).apply();
	}

	private static String sha256(File f) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		try (InputStream in = new FileInputStream(f)) {
			byte[] puffer = new byte[65536];
			int n;
			while ((n = in.read(puffer)) > 0) md.update(puffer, 0, n);
		}
		StringBuilder sb = new StringBuilder();
		for (byte b : md.digest()) sb.append(String.format("%02x", b));
		return sb.toString();
	}

	// ─── Sperren / Freigeben ────────────────────────────────────────────────

	private static List<String> apkPakete(JSONArray apps) {
		List<String> l = new ArrayList<>();
		for (int i = 0; i < apps.length(); i++) {
			JSONObject a = apps.optJSONObject(i);
			if (a != null && "apk".equals(a.optString("art"))) l.add(a.optString("paket"));
		}
		return l;
	}

	/** Abmelden: alle Katalog-Apps verstecken, persoenliche Daten loeschen. */
	/** Browser, deren Daten beim Abmelden geloescht werden (nicht synchronisierbar, Entscheid 24.11.2026). */
	static final String[] BROWSER_DATEN = {"com.android.chrome"};

	/**
	 * Apps, die ihre Daten beim Abmelden selbst loeschen (Paket -> Authority ihres Bestaetigungs-
	 * Providers). clearApplicationUserData setzt auch die per adb erteilten Sonderrechte zurueck
	 * (Vollbild ueber der Sperre, exakte Wecker, "Nicht stoeren", Akku-Ausnahme) — fuer den
	 * Notfall-Alarm des Messengers unbrauchbar. Bestaetigt die App das Loeschen nicht innert
	 * 10 s, wird wie bisher komplett geloescht (Datenschutz vor Komfort).
	 */
	static final java.util.Map<String, String> LOESCHT_SELBST = java.util.Collections.singletonMap(
			"ch.rosenweg4303.messages", "ch.rosenweg4303.messages.rosenweg.loeschen");

	private static void selbstLoeschenLassen(Context c, DevicePolicyManager d, ComponentName a, String paket) {
		Context app = c.getApplicationContext();
		long start = System.currentTimeMillis();
		app.sendBroadcast(new Intent("ch.rosenweg4303.ABGEMELDET").setPackage(paket)
				.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES), "ch.rosenweg4303.permission.ANLAGE");
		new Thread(() -> {
			boolean ok = false;
			android.net.Uri uri = android.net.Uri.parse("content://" + LOESCHT_SELBST.get(paket));
			for (int i = 0; i < 20 && !ok; i++) {
				try { Thread.sleep(500); } catch (InterruptedException ignored) { }
				try {
					Bundle r = app.getContentResolver().call(uri, "gewischt", null, null);
					ok = r != null && r.getBoolean("ok", false) && r.getLong("zeit", 0) >= start - 2000;
				} catch (Exception ignored) {
					// Provider noch nicht bereit oder App alt: weiter warten, dann komplett loeschen
				}
			}
			if (ok) {
				Log.i(TAG, "Daten selbst geloescht " + paket + " (Sonderrechte bleiben)");
			} else {
				Log.w(TAG, "keine Loesch-Bestaetigung von " + paket + " - loesche komplett");
				try {
					d.clearApplicationUserData(a, paket, Executors.newSingleThreadExecutor(),
							(p, erfolg) -> Log.i(TAG, "Daten geloescht " + p + ": " + erfolg));
				} catch (Exception e) {
					Log.w(TAG, "clearApplicationUserData " + paket + ": " + e);
				}
			}
		}, "SelbstLoeschen-" + paket).start();
	}

	static void sperren(Context c) {
		JSONArray alle = katalog(c);
		merkeFreigegeben(c, null);
		webDatenLoeschen();
		if (!Sperre.istDeviceOwner(c)) return;
		DevicePolicyManager d = Sperre.dpm(c);
		ComponentName a = Sperre.admin(c);
		java.util.Set<String> zurueck = new java.util.HashSet<>();
		for (int i = 0; i < alle.length(); i++) {
			JSONObject app = alle.optJSONObject(i);
			if (app == null || !"apk".equals(app.optString("art"))) continue;
			String paket = app.optString("paket");
			if (!installiert(c, paket)) continue;
			if (app.optBoolean("immer", false)) {
				// Werkzeug (Tastatur, Browser): bleibt sichtbar, Daten bleiben — persoenliche
				// Einstellungen/Verlauf vergisst die App selbst (z.B. Rosenweg Tastatur)
				try { d.setApplicationHidden(a, paket, false); } catch (Exception ignored) {}
				c.sendBroadcast(new Intent("ch.rosenweg4303.ABGEMELDET").setPackage(paket),
						"ch.rosenweg4303.permission.ANLAGE");
				continue;
			}
			// Abmelden erst, wenn alles hochgeladen ist: Apps mit offenen Dateien
			// bleiben, bis der Nachlauf sie aufraeumt (Nachlauf.java)
			if (app.optBoolean("persoenlich", true) && (Nachlauf.enthaelt(c, paket) || Nachlauf.offen(c, paket) > 0)) {
				zurueck.add(paket);
				Nachlauf.anstossen(c, paket);
				continue;
			}
			try {
				d.setApplicationRestrictions(a, paket, new Bundle());
				d.setApplicationHidden(a, paket, true);
				if (app.optBoolean("persoenlich", true) && Build.VERSION.SDK_INT >= 28) {
					if (LOESCHT_SELBST.containsKey(paket)) selbstLoeschenLassen(c, d, a, paket);
					else d.clearApplicationUserData(a, paket, Executors.newSingleThreadExecutor(),
							(p, ok) -> Log.i(TAG, "Daten geloescht " + p + ": " + ok));
				}
			} catch (Exception e) {
				Log.w(TAG, "sperren " + paket + ": " + e);
			}
		}
		if (zurueck.isEmpty() && !Nachlauf.aktiv(c)) Nachlauf.fertigMelden(c);   // Nextcloud-Zugang darf weg
		else Nachlauf.vormerken(c, zurueck);
		// Browser ausserhalb des Katalogs (Chrome): Verlauf, Cookies, Anmeldungen der Person weg
		if (Build.VERSION.SDK_INT >= 28) {
			for (String b : BROWSER_DATEN) {
				if (!installiert(c, b)) continue;
				try {
					d.clearApplicationUserData(a, b, Executors.newSingleThreadExecutor(),
							(p, ok) -> Log.i(TAG, "Browserdaten geloescht " + p + ": " + ok));
				} catch (Exception e) {
					Log.w(TAG, "Browserdaten " + b + ": " + e);
				}
			}
		}
	}

	/** Anmelden: freigegebene Apps einblenden und einrichten. */
	static void freigeben(Context c, JSONArray apps) {
		merkeFreigegeben(c, apps);
		if (!Sperre.istDeviceOwner(c) || apps == null) return;
		// Werkzeuge (immer sichtbar) gehoeren nicht zur Liste der Person, bekommen die Anmeldung aber auch
		JSONArray alle = katalog(c);
		for (int i = 0; i < alle.length(); i++) {
			JSONObject app = alle.optJSONObject(i);
			if (app == null || !app.optBoolean("immer", false) || !installiert(c, app.optString("paket"))) continue;
			c.sendBroadcast(new Intent("ch.rosenweg4303.ANGEMELDET").setPackage(app.optString("paket")).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
					"ch.rosenweg4303.permission.ANLAGE");
		}
		DevicePolicyManager d = Sperre.dpm(c);
		ComponentName a = Sperre.admin(c);
		for (int i = 0; i < apps.length(); i++) {
			JSONObject app = apps.optJSONObject(i);
			if (app == null || !"apk".equals(app.optString("art"))) continue;
			String paket = app.optString("paket");
			if (!installiert(c, paket)) continue;
			try {
				// Launcher: seine Einstellung ist die Liste "nur unsichtbar" — nicht ueberschreiben.
				if (!Ausblenden.LAUNCHER.equals(paket)) {
					d.setApplicationRestrictions(a, paket, bundle(app.optJSONObject("konfig")));
				}
				d.setApplicationHidden(a, paket, false);
				berechtigungenErteilen(c, d, a, paket);
				// App Bescheid geben (z.B. Rosenweg Uhr: Einstellungen der Person sofort holen).
				// FLAG_INCLUDE_STOPPED_PACKAGES: nach dem Daten-Loeschen beim Abmelden sind die Apps gestoppt.
				c.sendBroadcast(new Intent("ch.rosenweg4303.ANGEMELDET").setPackage(paket).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
						"ch.rosenweg4303.permission.ANLAGE");
			} catch (Exception e) {
				Log.w(TAG, "freigeben " + paket + ": " + e);
			}
		}
	}

	/**
	 * Laufzeit-Berechtigungen einer Katalog-App erteilen. Beim Abmelden loescht
	 * clearApplicationUserData sie mit den Daten — ohne das fragte z.B. Rosenweg
	 * Kamera bei jeder Anmeldung neu. Alle gefaehrlichen inkl. Standort (Entscheid Stefan 24.11.2026).
	 */
	static void berechtigungenErteilen(Context c, DevicePolicyManager d, ComponentName a, String paket) {
		if (Build.VERSION.SDK_INT < 23) return;
		try {
			android.content.pm.PackageManager pm = c.getPackageManager();
			String[] wuensche = pm.getPackageInfo(paket, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
			if (wuensche == null) return;
			for (String p : wuensche) {
				try {
					android.content.pm.PermissionInfo info = pm.getPermissionInfo(p, 0);
					int schutz = Build.VERSION.SDK_INT >= 28 ? info.getProtection() : info.protectionLevel & 0xf;
					if (schutz != android.content.pm.PermissionInfo.PROTECTION_DANGEROUS) continue;
					boolean ok = d.setPermissionGrantState(a, paket, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
					if (!ok) Log.w(TAG, "Berechtigung nicht erteilt: " + paket + " " + p);
				} catch (Exception ignored) {}
			}
		} catch (Exception e) {
			Log.w(TAG, "berechtigungen " + paket + ": " + e);
		}
	}

	/** Nach einer Installation waehrend niemand angemeldet ist: gleich verstecken. */
	static void nachInstallation(Context c, String paket) {
		if (!Sperre.istDeviceOwner(c)) return;
		boolean immer = false;
		JSONArray alle = katalog(c);
		for (int i = 0; i < alle.length(); i++) {
			JSONObject x = alle.optJSONObject(i);
			if (x != null && paket.equals(x.optString("paket"))) immer = x.optBoolean("immer", false);
		}
		boolean erlaubt = immer || (apkPakete(freigegeben(c)).contains(paket) && Anlage.angemeldet(c));
		try {
			Sperre.dpm(c).setApplicationHidden(Sperre.admin(c), paket, !erlaubt);
		} catch (Exception e) {
			Log.w(TAG, "nachInstallation " + paket + ": " + e);
		}
	}

	private static Bundle bundle(JSONObject k) {
		Bundle b = new Bundle();
		if (k == null) return b;
		Iterator<String> it = k.keys();
		while (it.hasNext()) {
			String key = it.next();
			Object v = k.opt(key);
			if (v instanceof Boolean) b.putBoolean(key, (Boolean) v);
			else if (v instanceof Integer) b.putInt(key, (Integer) v);
			else if (v != null) b.putString(key, String.valueOf(v));
		}
		return b;
	}

	/** Cookies und Web-Speicher der Rosenweg-App: Authentik- und Web-App-Sitzungen weg. */
	static void webDatenLoeschen() {
		try {
			CookieManager cm = CookieManager.getInstance();
			cm.removeAllCookies(null);
			cm.flush();
			WebStorage.getInstance().deleteAllData();
		} catch (Exception e) {
			Log.w(TAG, "webDatenLoeschen: " + e);
		}
	}

	@SuppressWarnings("unused")
	private static void schreibe(File f, byte[] d) throws Exception {
		try (FileOutputStream o = new FileOutputStream(f)) {
			o.write(d);
		}
	}
}
