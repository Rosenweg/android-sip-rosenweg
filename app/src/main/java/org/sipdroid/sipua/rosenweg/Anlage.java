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

	static void merke(Context c, boolean angemeldet, String name) {
		// Persoenliche Voicemails (AnlageProvider) gehoeren nicht auf ein geteiltes Geraet,
		// sobald niemand mehr angemeldet ist.
		if (!angemeldet) loescheVoicemails(c);
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
		return r;
	}

	/** GET /api/telefon/status -> {angemeldet, name, email, seit, apps}; merkt sich das Ergebnis. */
	public static JSONObject status(Context c) throws Exception {
		JSONObject s = anfrage(c, "GET", "/api/telefon/status");
		if (!s.optString("geraet", "").isEmpty()) {
			prefs(c).edit().putString(PREF_GERAET, s.optString("geraet")).apply();
		}
		merke(c, s.optBoolean("angemeldet", false), s.optString("name", ""));
		Sperre.sperrbildschirmText(c);
		return s;
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

	private static JSONObject anfrage(Context c, String methode, String pfad) throws Exception {
		return anfrage(c, methode, pfad, "{}");
	}

	private static JSONObject anfrage(Context c, String methode, String pfad, String koerper) throws Exception {
		SharedPreferences p = prefs(c);
		String basis = p.getString(PREF_API, API_STANDARD);
		String zugang = p.getString(Settings.PREF_USERNAME, "") + ":" + p.getString(Settings.PREF_PASSWORD, "");
		HttpURLConnection v = (HttpURLConnection) new URL(basis + pfad).openConnection();
		try {
			v.setRequestMethod(methode);
			v.setConnectTimeout(8000);
			v.setReadTimeout(8000);
			v.setUseCaches(false);
			v.setRequestProperty("Accept", "application/json");
			v.setRequestProperty("Authorization", "Basic "
					+ Base64.encodeToString(zugang.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
			if ("POST".equals(methode)) {
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
