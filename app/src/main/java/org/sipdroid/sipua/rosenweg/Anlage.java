package org.sipdroid.sipua.rosenweg;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Base64;

import org.json.JSONObject;
import org.sipdroid.sipua.ui.Settings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Verbindung zur PBX-API der Rosenweg-Anlage (pbx.rosenweg4303.ch).
 *
 * Das Geraet weist sich mit seinen SIP-Zugangsdaten aus (HTTP Basic,
 * Nebenstelle:Passwort) — die stehen ohnehin in den App-Einstellungen. Die API
 * nimmt nur Nebenstellen an, die als "geteilt" gefuehrt sind.
 *
 * Alle Methoden blockieren: nie im UI-Thread aufrufen.
 */
public final class Anlage {
	public static final String API_STANDARD = "https://pbx.rosenweg4303.ch";
	/** Ueberschreibbar in den Einstellungen (Import-Datei), z.B. fuer Tests. */
	public static final String PREF_API = "rosenweg_api";
	public static final String PREF_ANGEMELDET = "rosenweg_angemeldet";
	public static final String PREF_NAME = "rosenweg_name";
	/** Name des Geraets laut Anlage (Name der Nebenstelle, z.B. "Rosenweg Smartphone 2"). */
	public static final String PREF_GERAET = "rosenweg_geraet";

	public static String geraet(Context c) {
		String g = prefs(c).getString(PREF_GERAET, "");
		return g.isEmpty() ? "Nebenstelle " + nebenstelle(c) : g;
	}

	private Anlage() {}

	public static SharedPreferences prefs(Context c) {
		return PreferenceManager.getDefaultSharedPreferences(c);
	}

	public static boolean eingerichtet(Context c) {
		SharedPreferences p = prefs(c);
		return !p.getString(Settings.PREF_USERNAME, "").isEmpty()
				&& !p.getString(Settings.PREF_PASSWORD, "").isEmpty();
	}

	public static String nebenstelle(Context c) {
		return prefs(c).getString(Settings.PREF_USERNAME, "");
	}

	/** Zuletzt bekannter Anmeldestand (fuer UI und Kontoname, ohne Netz). */
	public static boolean angemeldet(Context c) {
		return prefs(c).getBoolean(PREF_ANGEMELDET, false);
	}

	public static String name(Context c) {
		return prefs(c).getString(PREF_NAME, "");
	}

	static final String PREF_NUMMER = "rosenweg_nummer";

	/** Persoenliche interne Telefonnummer der angemeldeten Person (leer, wenn unbekannt). */
	public static String nummer(Context c) {
		return angemeldet(c) ? prefs(c).getString(PREF_NUMMER, "") : "";
	}

	static void merkeNummer(Context c, JSONObject r) {
		prefs(c).edit().putString(PREF_NUMMER,
				r != null && r.optBoolean("angemeldet", false) ? r.optString("nummer", "") : "").apply();
	}

	static void merke(Context c, boolean angemeldet, String name) {
		// Persoenliche Voicemails (AnlageProvider) gehoeren nicht auf ein geteiltes Geraet,
		// sobald niemand mehr angemeldet ist.
		if (!angemeldet) {
			loescheVoicemails(c);
			nextcloud = null;
			messenger = null;
		}
		// Jede An- oder Abmeldung: der persoenliche SIP-Zugang ist ein neuer (bzw. weg)
		sipPerson = null;
		prefs(c).edit()
				.putBoolean(PREF_ANGEMELDET, angemeldet)
				.putString(PREF_NAME, angemeldet && name != null ? name : "")
				.apply();
	}

	/** POST /api/telefon/einloesen {token} — gescannten Anmelde-QR einloesen. */
	public static JSONObject einloesen(Context c, String qrInhalt) throws Exception {
		JSONObject k = new JSONObject();
		k.put("token", qrInhalt);
		JSONObject r = anfrage(c, "POST", "/api/telefon/einloesen", k.toString());
		merke(c, r.optBoolean("angemeldet", false), r.optString("name", ""));
		merkeNummer(c, r);
		return r;
	}

	/**
	 * POST /api/telefon/karte {uid} — NFC-Karte (UniFi Access) einloesen. Gleiche Antwort wie
	 * einloesen; Fehlertexte der Anlage: "Karte nicht bekannt", "Karte gesperrt", ...
	 */
	public static JSONObject karte(Context c, String uid) throws Exception {
		JSONObject r = anfrage(c, "POST", "/api/telefon/karte", new JSONObject().put("uid", uid).toString(), 20000);
		merke(c, r.optBoolean("angemeldet", false), r.optString("name", ""));
		merkeNummer(c, r);
		return r;
	}

	/** POST /api/telefon/karten/info {uid} -> {found, assigned, status, display_id, person:{name,email}} (Technik). */
	public static JSONObject kartenInfo(Context c, String uid) throws Exception {
		return anfrage(c, "POST", "/api/telefon/karten/info", new JSONObject().put("uid", uid).toString(), 20000);
	}

	/** POST /api/telefon/karten/zuordnen {uid, email} (Technik). */
	public static JSONObject kartenZuordnen(Context c, String uid, String email) throws Exception {
		return anfrage(c, "POST", "/api/telefon/karten/zuordnen",
				new JSONObject().put("uid", uid).put("email", email).toString(), 20000);
	}

	/** POST /api/telefon/karten/entziehen {uid} (Technik). */
	public static JSONObject kartenEntziehen(Context c, String uid) throws Exception {
		return anfrage(c, "POST", "/api/telefon/karten/entziehen", new JSONObject().put("uid", uid).toString(), 20000);
	}

	/** POST /api/telefon/karten/loeschen {uid} (Technik): nur nicht zugeordnete Karten, aus dem UniFi-Access-Bestand. */
	public static JSONObject kartenLoeschen(Context c, String uid) throws Exception {
		return anfrage(c, "POST", "/api/telefon/karten/loeschen", new JSONObject().put("uid", uid).toString(), 20000);
	}

	/** GET /api/telefon/karten/personen -> {personen:[{name,email}]} (Technik). */
	public static JSONObject kartenPersonen(Context c) throws Exception {
		return anfrage(c, "GET", "/api/telefon/karten/personen", "{}", 20000);
	}

	/** GET /api/telefon/status -> {angemeldet, name, email, seit, apps}; merkt sich das Ergebnis. */
	public static JSONObject status(Context c) throws Exception {
		JSONObject s = anfrage(c, "GET", "/api/telefon/status");
		if (!s.optString("geraet", "").isEmpty()) {
			prefs(c).edit().putString(PREF_GERAET, s.optString("geraet")).apply();
		}
		merke(c, s.optBoolean("angemeldet", false), s.optString("name", ""));
		merkeNummer(c, s);
		// Zuerst ueber die Einstellungs-Sperre entscheiden, vor WLAN/Wartung (die koennen beim
		// Netzwechsel scheitern - dann blieb Technik gesperrt, Stefan 25.11.2026). Bei jedem
		// Status, damit es sich selbst repariert.
		if (s.optBoolean("angemeldet", false)) {
			boolean technik = s.optBoolean("technik");
			if (technik != prefs(c).getBoolean(Sperre.PREF_TECHNIK, false) || technik) {
				prefs(c).edit().putBoolean(Sperre.PREF_TECHNIK, technik).apply();
				Sperre.einstellungenSperren(c, !technik);
				if (technik) Sperre.entwickleroptionen(c, true);
			}
		}
		// Seitentaste & Co. (nur ueber adb setzbar): hoechstens stuendlich pruefen
		if (System.currentTimeMillis() - prefs(c).getLong("einst_geprueft", 0) > 3600_000L) {
			prefs(c).edit().putLong("einst_geprueft", System.currentTimeMillis()).apply();
			final Context app = c.getApplicationContext();
			new Thread(() -> AdbSelbst.einstellungenPruefen(app), "EinstPruefen").start();
		}
		Sperre.sperrbildschirmText(c);
		Sperrbild.fuer(c, s.optBoolean("angemeldet", false) ? s.optString("sperrbild", "") : "");
		if (s.has("wlan_debug_bis")) WlanDebug.zentral(c, s.optLong("wlan_debug_bis", 0));
		Wlan.vielleicht(c, s.optBoolean("angemeldet", false));
		return s;
	}

	/** POST /api/telefon/adb {adresse, port}: Debug-Port fuer "adb connect" auf /smartphones (0 = aus). */
	public static void adbMelden(Context c, String adresse, int port) throws Exception {
		anfrage(c, "POST", "/api/telefon/adb", new JSONObject().put("adresse", adresse).put("port", port).toString());
	}

	/** GET /api/telefon/wlan -> {ssid, netz, vlan, passwort} (PPSK, nie loggen). */
	public static JSONObject wlan(Context c) throws Exception {
		return anfrage(c, "GET", "/api/telefon/wlan");
	}

	/** GET /api/telefon/katalog -> {apps: alle Apps des Geraets, ausblenden: System-Apps}. */
	public static JSONObject katalog(Context c) throws Exception {
		return anfrage(c, "GET", "/api/telefon/katalog");
	}

	/** GET /api/telefon/kontakte[?version=] -> {version, kontakte} oder {unveraendert:true}. */
	public static JSONObject kontakte(Context c, String version) throws Exception {
		return anfrage(c, "GET", "/api/telefon/kontakte"
				+ (version == null || version.isEmpty() ? "" : "?version=" + java.net.URLEncoder.encode(version, "UTF-8")));
	}

	/** GET /api/telefon/einstellungen?paket= -> {geaendert, werte} der angemeldeten Person. */
	public static JSONObject einstellungen(Context c, String paket) throws Exception {
		return anfrage(c, "GET", "/api/telefon/einstellungen?paket=" + java.net.URLEncoder.encode(paket, "UTF-8"));
	}

	/** PUT /api/telefon/einstellungen?paket= {geaendert, werte} -> {uebernommen} */
	public static JSONObject einstellungenSenden(Context c, String paket, JSONObject daten) throws Exception {
		return anfrage(c, "PUT", "/api/telefon/einstellungen?paket=" + java.net.URLEncoder.encode(paket, "UTF-8"),
				daten.toString());
	}

	/** GET /api/telefon/pin -> {gesetzt, pin} der angemeldeten Person (Bildschirmsperre). */
	public static JSONObject pin(Context c) throws Exception {
		return anfrage(c, "GET", "/api/telefon/pin");
	}

	/** PUT /api/telefon/pin {pin} — 4 bis 12 Ziffern; Fehlertext kommt von der Anlage. */
	public static void pinSetzen(Context c, String pin) throws Exception {
		anfrage(c, "PUT", "/api/telefon/pin", new JSONObject().put("pin", pin).toString());
	}

	/** DELETE /api/telefon/pin */
	public static void pinEntfernen(Context c) throws Exception {
		anfrage(c, "DELETE", "/api/telefon/pin");
	}

	private static volatile JSONObject nextcloud;   // nur im Speicher, nie auf die Platte
	private static volatile JSONObject messenger;   // dito
	private static volatile JSONObject sipPerson;   // dito

	/**
	 * Persoenlicher SIP-Zugang der angemeldeten Person fuer Rosenweg Telefon
	 * (GET /api/telefon/sip-person): {server, port, transport, user, auth_user, password,
	 * nummer, name}. Gilt nur fuer diese Sitzung; die Anlage loescht ihn beim Abmelden.
	 * Null, wenn niemand angemeldet ist. AnlageFehler 404: keine persoenliche Nummer.
	 */
	public static JSONObject sipPerson(Context c) throws Exception {
		if (!angemeldet(c)) return null;
		JSONObject k = sipPerson;
		if (k != null) return k;
		k = anfrage(c, "GET", "/api/telefon/sip-person");
		sipPerson = k;
		return k;
	}

	/**
	 * Rosenweg-Messenger-Zugang der angemeldeten Person (Anlage-Token, 12 h, vom message-store
	 * angenommen). Wird kurz vor Ablauf neu geholt. Null, wenn niemand angemeldet ist.
	 */
	public static JSONObject messengerKonto(Context c) throws Exception {
		if (!angemeldet(c)) return null;
		JSONObject k = messenger;
		if (k != null && k.optLong("gueltig_bis") * 1000 - System.currentTimeMillis() > 3600_000) return k;
		k = anfrage(c, "GET", "/api/telefon/messenger");
		messenger = k;
		return k;
	}

	/**
	 * Nextcloud-Zugang der angemeldeten Person (App-Passwort, ausgestellt von der Anlage,
	 * beim Abmelden dort widerrufen). Null, wenn niemand angemeldet ist.
	 */
	public static JSONObject nextcloudKonto(Context c) throws Exception {
		if (!angemeldet(c) && !Nachlauf.aktiv(c)) return null;   // Nachlauf: Zugang bis alles oben ist
		JSONObject k = nextcloud;
		if (k != null) return k;
		k = anfrage(c, "GET", "/api/telefon/nextcloud");
		nextcloud = k;
		return k;
	}

	/** POST /api/telefon/nextcloud/fertig — nach dem Abmelden ist alles oben, Zugang widerrufen. */
	static void nextcloudFertig(Context c) throws Exception {
		nextcloud = null;
		anfrage(c, "POST", "/api/telefon/nextcloud/fertig");
	}

	static void loescheVoicemails(Context c) {
		java.io.File[] fs = new java.io.File(c.getCacheDir(), "voicemail").listFiles();
		if (fs != null) for (java.io.File f : fs) f.delete();
	}

	/** GET /api/telefon/anrufliste -> {angemeldet, name, anrufe:[...]}: Anrufe der angemeldeten Person. */
	public static JSONObject anrufliste(Context c) throws Exception {
		return anfrage(c, "GET", "/api/telefon/anrufliste?limit=200");
	}

	/** Datei (APK) mit den Geraete-Zugangsdaten herunterladen. */
	public static void herunterladen(Context c, String pfad, java.io.File ziel) throws Exception {
		SharedPreferences p = prefs(c);
		String zugang = p.getString(Settings.PREF_USERNAME, "") + ":" + p.getString(Settings.PREF_PASSWORD, "");
		HttpURLConnection v = (HttpURLConnection) new URL(p.getString(PREF_API, API_STANDARD) + pfad).openConnection();
		try {
			v.setConnectTimeout(15000);
			v.setReadTimeout(60000);
			v.setRequestProperty("Authorization", "Basic "
					+ Base64.encodeToString(zugang.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
			if (v.getResponseCode() != 200) throw new AnlageFehler(v.getResponseCode(), "Download " + pfad);
			try (InputStream in = v.getInputStream(); OutputStream o = new java.io.FileOutputStream(ziel)) {
				byte[] puffer = new byte[65536];
				int n;
				while ((n = in.read(puffer)) > 0) o.write(puffer, 0, n);
			}
		} finally {
			v.disconnect();
		}
	}

	/** POST /api/telefon/abmelden */
	public static void abmelden(Context c) throws Exception {
		anfrage(c, "POST", "/api/telefon/abmelden");
		merke(c, false, null);
	}

	/**
	 * POST /api/telefon/portal {methode, pfad, koerper, paket} -> {status, json, sitzung}:
	 * Anfrage einer Rosenweg-App an die Haupt-API im Namen der angemeldeten Person
	 * (AnlageProvider "portal_anfrage"). Die Anlage stellt dafuer ein Token fuer genau diese
	 * Anfrage aus und behaelt es — hier kommt nur die Antwort an.
	 * AnlageFehler 409: niemand angemeldet, 403: Pfad fuer diese App nicht freigegeben.
	 */
	public static JSONObject portal(Context c, String methode, String pfad, String koerper, String paket) throws Exception {
		JSONObject k = new JSONObject()
				.put("methode", methode)
				.put("pfad", pfad)
				.put("paket", paket)
				.put("koerper", koerper == null ? JSONObject.NULL : koerper);
		return anfrage(c, "POST", "/api/telefon/portal", k.toString(), 22000);
	}

	/** POST /api/telefon/gefunden — Suchanruf mit "Gefunden" beantwortet (Geraetezugang). */
	public static JSONObject gefunden(Context c) throws Exception {
		return anfrage(c, "POST", "/api/telefon/gefunden", "{}");
	}

	private static JSONObject anfrage(Context c, String methode, String pfad) throws Exception {
		return anfrage(c, methode, pfad, "{}");
	}

	private static JSONObject anfrage(Context c, String methode, String pfad, String koerper) throws Exception {
		return anfrage(c, methode, pfad, koerper, 8000);
	}

	/** lesenMs: Karten-Anfragen gehen bis UniFi Access (Import, Abgleich) und brauchen laenger. */
	private static JSONObject anfrage(Context c, String methode, String pfad, String koerper, int lesenMs) throws Exception {
		SharedPreferences p = prefs(c);
		String basis = p.getString(PREF_API, API_STANDARD);
		String zugang = p.getString(Settings.PREF_USERNAME, "") + ":" + p.getString(Settings.PREF_PASSWORD, "");
		HttpURLConnection v = (HttpURLConnection) new URL(basis + pfad).openConnection();
		try {
			v.setRequestMethod(methode);
			v.setConnectTimeout(8000);
			v.setReadTimeout(lesenMs);
			v.setUseCaches(false);
			v.setRequestProperty("Accept", "application/json");
			v.setRequestProperty("Authorization", "Basic "
					+ Base64.encodeToString(zugang.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
			if ("POST".equals(methode) || "PUT".equals(methode)) {
				v.setDoOutput(true);
				v.setRequestProperty("Content-Type", "application/json");
				try (OutputStream o = v.getOutputStream()) {
					o.write(koerper.getBytes(StandardCharsets.UTF_8));
				}
			}
			int code = v.getResponseCode();
			String body = lies(code >= 400 ? v.getErrorStream() : v.getInputStream());
			if (code >= 400) {
				String fehler = "HTTP " + code;
				try {
					fehler = new JSONObject(body).optString("error", fehler);
				} catch (Exception ignored) {}
				throw new AnlageFehler(code, fehler);
			}
			return new JSONObject(body);
		} finally {
			v.disconnect();
		}
	}

	private static String lies(InputStream in) throws Exception {
		if (in == null) return "";
		try (InputStream i = in; ByteArrayOutputStream b = new ByteArrayOutputStream()) {
			byte[] puffer = new byte[4096];
			int n;
			while ((n = i.read(puffer)) > 0) b.write(puffer, 0, n);
			return b.toString("UTF-8");
		}
	}

	/** Fehlerantwort der API (401 = Geraet nicht als geteilt freigegeben). */
	public static class AnlageFehler extends Exception {
		public final int status;

		AnlageFehler(int status, String meldung) {
			super(meldung);
			this.status = status;
		}
	}
}
