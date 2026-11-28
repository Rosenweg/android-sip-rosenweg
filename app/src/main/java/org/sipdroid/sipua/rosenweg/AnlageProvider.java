package org.sipdroid.sipua.rosenweg;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Daten der Anlage fuer andere Rosenweg-Apps (Rosenweg Telefon), ohne dass diese
 * Netz oder die SIP-Zugangsdaten brauchen. Nur fuer Apps mit derselben Signatur
 * (Permission ch.rosenweg4303.permission.ANLAGE, protectionLevel signature).
 *
 *   content://ch.rosenweg4303.telefon.anlage/anrufliste
 *       Anrufe der angemeldeten Person ueber alle Geraete (Regel 6). Spalten:
 *       _id, richtung (ausgehend|eingehend|verpasst), nummer, beginn (ms),
 *       dauer (s), voicemail (Zusammenfassung), voicemail_audio (0/1), ueber (Nebenstelle)
 *   content://ch.rosenweg4303.telefon.anlage/voicemail/<id>
 *       persoenliche Voicemail als WAV (openFile)
 *   call("einstellungen_holen", paket) -> {geaendert (long), werte (JSON-Text)}
 *   call("einstellungen_senden", paket, {geaendert, werte}) -> {uebernommen}
 *       Einstellungen einer App pro Person (Regel 7, gespeichert auf der Anlage)
 *   call("sip_person", paket) -> {angemeldet (bool), server, port (int), transport (udp|tcp|tls),
 *       user, auth_user, password, nummer, name}
 *       Persoenlicher SIP-Zugang der angemeldeten Person fuer Rosenweg Telefon (gilt nur
 *       fuer diese Sitzung). angemeldet=false: niemand angemeldet oder keine persoenliche
 *       Nummer — Rosenweg Telefon meldet sich dann ab und vergisst den Zugang.
 *       Server/Port/Transport sind die des Geraets (dieselbe Anlage, hier erprobt).
 *       Aenderungen meldet die Geraete-App mit dem Broadcast ch.rosenweg4303.SIP_PERSON_GEAENDERT.
 *   call("hintergrund_zuruecksetzen", "1") -> {ok (bool), fehler}
 *       Hintergrund der angemeldeten Person auf den Rosenweg-Standard (Anlage + sofort hier);
 *       Rosenweg Launcher, Einstellungen "Hintergrund zuruecksetzen".
 *   call("karten_info", uid) -> {json: {found, assigned, status, display_id, person:{name,email}}}
 *   call("karten_zuordnen", uid, {email}) -> {json: {ok, display_id, name}}
 *   call("karten_entziehen", uid) -> {json: {ok}}
 *   call("karten_loeschen", uid) -> {json: {ok}}  (nur nicht zugeordnete Karten: aus UniFi Access loeschen)
 *   call("karten_personen", "personen") -> {json: {personen:[{name,email}]}}
 *       Kartenverwaltung (App "Rosenweg Karten"): nur wenn die angemeldete Person Technik/
 *       Praesident ist (hier live geprueft, die Anlage prueft nochmals). Sonst
 *       {fehler: "nicht berechtigt", nicht_berechtigt: true}. Fehler der Anlage: {fehler, status}.
 *   call("portal_anfrage", pfad, {methode, koerper, paket}) -> {status (int), json (Text), sitzung}
 *       Anfrage einer Rosenweg-App (Flutter, rosenweg-basis GeraetTransport) an die Haupt-API
 *       im Namen der angemeldeten Person. pfad "/api/...", methode GET|POST|PUT|DELETE,
 *       koerper JSON-Text oder fehlt. Weg: hier -> PBX-API (POST /api/telefon/portal, Geraete-
 *       zugang) -> Haupt-API mit einem Token fuer genau diese Anfrage, das die Anlage behaelt.
 *       Die App sieht nie ein Token. Welche Pfade eine App so erreicht, legt die Anlage fest
 *       (Config portal_pfade); hier: Signatur des Aufrufers, Methode, Pfadform, Groesse, Takt.
 *       {nicht_angemeldet: true}: niemand am Telefon angemeldet. {fehler}: Netz/Anlage.
 *       Hochladen: extras "teil" (JSON-Text {id, nr, anzahl, gesamt, kopf}) und koerper = Stueck
 *       (base64, hoechstens 180 KB); Antwort 202 je Zwischenstueck, beim letzten die der Haupt-API.
 *       Siehe pbx/README.md (Website). Hoechstens 300 Anfragen je App und Minute.
 *       "sitzung" wechselt mit jeder An-/Abmeldung (die App laedt dann neu); paket nimmt
 *       der Provider vom Aufrufer, nicht aus den extras.
 *
 * Blockiert (Netz): nicht vom UI-Thread aufrufen.
 */
public class AnlageProvider extends ContentProvider {
	private static final String TAG = "RosenwegAnlage";
	public static final String AUTHORITY = "ch.rosenweg4303.telefon.anlage";
	private static final int ANRUFLISTE = 1, VOICEMAIL = 2;
	private static final UriMatcher PFADE = new UriMatcher(UriMatcher.NO_MATCH);
	static {
		PFADE.addURI(AUTHORITY, "anrufliste", ANRUFLISTE);
		PFADE.addURI(AUTHORITY, "voicemail/#", VOICEMAIL);
	}
	private static final String[] SPALTEN = {"_id", "richtung", "nummer", "beginn", "dauer", "voicemail", "voicemail_audio", "ueber"};

	@Override
	public boolean onCreate() {
		return true;
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		if (PFADE.match(uri) != ANRUFLISTE) return null;
		MatrixCursor c = new MatrixCursor(SPALTEN);
		if (!Anlage.eingerichtet(getContext())) return c;
		try {
			JSONObject d = Anlage.anrufliste(getContext());
			JSONArray a = d.optJSONArray("anrufe");
			SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
			utc.setTimeZone(TimeZone.getTimeZone("UTC"));
			for (int i = 0; a != null && i < a.length(); i++) {
				JSONObject r = a.getJSONObject(i);
				long beginn;
				try {
					beginn = utc.parse(r.optString("beginn")).getTime();
				} catch (Exception e) {
					continue;
				}
				c.addRow(new Object[]{
						r.optLong("id"), r.optString("richtung"), r.optString("nummer"), beginn,
						r.isNull("dauer") ? 0 : r.optInt("dauer"),
						r.isNull("voicemail") ? "" : r.optString("voicemail"),
						r.optBoolean("voicemail_audio") ? 1 : 0, r.optString("ueber")});
			}
		} catch (Exception e) {
			Log.w(TAG, "anrufliste: " + e);
		}
		return c;
	}

	@Override
	public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
		if (PFADE.match(uri) != VOICEMAIL || !"r".equals(mode)) throw new FileNotFoundException(uri.toString());
		String id = uri.getLastPathSegment();
		File dir = new File(getContext().getCacheDir(), "voicemail");
		dir.mkdirs();
		File f = new File(dir, id + ".wav");
		try {
			if (!f.exists() || f.length() == 0) {
				File tmp = new File(dir, id + ".teil");
				Anlage.herunterladen(getContext(), "/api/telefon/voicemail/" + id, tmp);
				if (!tmp.renameTo(f)) throw new FileNotFoundException("speichern");
			}
		} catch (FileNotFoundException e) {
			throw e;
		} catch (Exception e) {
			Log.w(TAG, "voicemail " + id + ": " + e);
			throw new FileNotFoundException(e.getMessage());
		}
		return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
	}

	@Override
	public Bundle call(String method, String arg, Bundle extras) {
		// call() prueft die readPermission NICHT von selbst
		if (getContext().checkCallingPermission("ch.rosenweg4303.permission.ANLAGE") != PackageManager.PERMISSION_GRANTED) {
			throw new SecurityException("Nur Rosenweg-Apps");
		}
		if (PORTAL.equals(method)) {
			// Aufrufer jetzt bestimmen (im Netz-Thread kennt Android ihn nicht mehr)
			String paket = portalAufrufer();
			if (paket == null) throw new SecurityException("Nur Rosenweg-Apps mit derselben Signatur");
			if (!Anlage.eingerichtet(getContext())) return fehler("Dieses Telefon ist noch nicht eingerichtet.");
			try {
				return NETZ.submit(() -> portalAnfrage(arg, extras, paket)).get(25, java.util.concurrent.TimeUnit.SECONDS);
			} catch (Exception e) {
				return fehler("Das Portal antwortet nicht (Zeitüberschreitung).");
			}
		}
		if (arg == null || arg.isEmpty() || !Anlage.eingerichtet(getContext())) return new Bundle();
		// Eigener Thread: ruft eine App vom Haupt-Thread, gaebe Android dessen StrictMode
		// ueber Binder mit (NetworkOnMainThreadException hier im Provider).
		try {
			// Aufrufer jetzt merken: im eigenen Thread kennt Android ihn nicht mehr
			final String aufrufer = getCallingPackage();
			return NETZ.submit(() -> ausfuehren(method, arg, extras, aufrufer)).get(25, java.util.concurrent.TimeUnit.SECONDS);
		} catch (Exception e) {
			Bundle b = new Bundle();
			b.putString("fehler", "Zeitueberschreitung");
			return b;
		}
	}

	private static final java.util.concurrent.ExecutorService NETZ = java.util.concurrent.Executors.newCachedThreadPool();

	// ─── portal_anfrage (siehe Kopf) ────────────────────────────────────────
	static final String PORTAL = "portal_anfrage";
	static final int PORTAL_KOERPER_MAX = 256 * 1024;
	/** Binder-Grenze ~1 MB je Transaktion; die Anlage begrenzt ebenso. */
	static final int PORTAL_ANTWORT_MAX = 512 * 1024;
	/** Hoechstens so viele Anfragen je App und Minute (eine haengende Schleife legt sonst die Anlage lahm). */
	static final int PORTAL_TAKT_MINUTE = 300;
	private static final java.util.regex.Pattern PORTAL_PFAD = java.util.regex.Pattern.compile(
			"/api/[A-Za-z0-9._~!$&'()*+,;=:@%/-]{1,1000}(\\?[A-Za-z0-9._~!$&'()*+,;=:@%/?-]{0,1000})?");
	private static final java.util.regex.Pattern PORTAL_KODIERT_VERBOTEN =
			java.util.regex.Pattern.compile("%(2e|2f|5c|00)", java.util.regex.Pattern.CASE_INSENSITIVE);
	private static final java.util.Map<String, long[]> PORTAL_TAKT = new java.util.HashMap<>();

	/**
	 * Paket des Aufrufers, wenn er eine Rosenweg-App mit derselben Signatur ist, sonst null.
	 * checkCallingPermission (ANLAGE, signature) hat call() schon geprueft; hier zusaetzlich
	 * die Signatur der UID direkt (falls die Berechtigung je anders vergeben wuerde).
	 */
	private String portalAufrufer() {
		String paket = getCallingPackage();
		if (paket == null || !paket.startsWith("ch.rosenweg4303.")) return null;
		PackageManager pm = getContext().getPackageManager();
		int uid = android.os.Binder.getCallingUid();
		if (pm.checkSignatures(android.os.Process.myUid(), uid) != PackageManager.SIGNATURE_MATCH) return null;
		String[] pakete = pm.getPackagesForUid(uid);
		return pakete != null && java.util.Arrays.asList(pakete).contains(paket) ? paket : null;
	}

	static boolean portalPfadOk(String pfad) {
		if (pfad == null || !PORTAL_PFAD.matcher(pfad).matches()) return false;
		String weg = pfad.split("\\?", 2)[0];
		if (weg.contains("//") || PORTAL_KODIERT_VERBOTEN.matcher(pfad).find()) return false;
		for (String teil : weg.split("/")) if (".".equals(teil) || "..".equals(teil)) return false;
		return true;
	}

	private static synchronized boolean portalTaktOk(String paket) {
		long jetzt = System.currentTimeMillis();
		long[] t = PORTAL_TAKT.get(paket);   // {Beginn der Minute, Anzahl}
		if (t == null || jetzt - t[0] > 60_000) {
			PORTAL_TAKT.put(paket, new long[]{jetzt, 1});
			return true;
		}
		return ++t[1] <= PORTAL_TAKT_MINUTE;
	}

	private static Bundle fehler(String text) {
		Bundle b = new Bundle();
		b.putString("fehler", text);
		return b;
	}

	private static Bundle antwort(int status, String json, String sitzung) {
		Bundle b = new Bundle();
		b.putInt("status", status);
		b.putString("json", json == null ? "" : json);
		if (sitzung != null && !sitzung.isEmpty()) b.putString("sitzung", sitzung);
		return b;
	}

	private Bundle portalAnfrage(String pfad, Bundle extras, String paket) {
		android.content.Context c = getContext();
		String methode = extras == null ? "GET" : extras.getString("methode", "GET");
		String koerper = extras == null ? null : extras.getString("koerper");
		// Hochladen in Stuecken: {id, nr, anzahl, gesamt, kopf} als JSON-Text, koerper = Stueck (base64)
		JSONObject teil = null;
		String teilText = extras == null ? null : extras.getString("teil");
		if (teilText != null) {
			if (teilText.length() > 8192) return fehler("Upload-Angaben zu gross.");
			try {
				teil = new JSONObject(teilText);
			} catch (Exception e) {
				return fehler("Upload-Angaben ungültig.");
			}
			if (!("POST".equals(methode) || "PUT".equals(methode))) return fehler("Hochladen nur mit POST oder PUT.");
		}
		if (!("GET".equals(methode) || "POST".equals(methode) || "PUT".equals(methode) || "DELETE".equals(methode))) {
			return fehler("Methode nicht erlaubt: " + methode);
		}
		if (!portalPfadOk(pfad)) return fehler("Ungültiger Pfad für das Portal.");
		if (koerper != null && koerper.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > PORTAL_KOERPER_MAX) {
			return fehler("Die Anfrage ist zu gross für das Telefon.");
		}
		if (!portalTaktOk(paket)) return antwort(429, "", null);
		Bundle b = new Bundle();
		if (!Anlage.angemeldet(c)) {
			b.putBoolean("nicht_angemeldet", true);
			return b;
		}
		try {
			JSONObject r = Anlage.portal(c, methode, pfad, koerper, paket, teil);
			String json = r.optString("json", "");
			if (json.length() > PORTAL_ANTWORT_MAX) return fehler("Die Antwort ist zu gross für das Telefon.");
			return antwort(r.optInt("status", 502), json, r.optString("sitzung", ""));
		} catch (Anlage.AnlageFehler e) {
			switch (e.status) {
				case 409:
					b.putBoolean("nicht_angemeldet", true);
					return b;
				case 403:   // Pfad fuer diese App auf geteilten Telefonen nicht freigegeben
				case 400:
				case 413:
					try {
						return antwort(e.status, new JSONObject().put("error", e.getMessage()).toString(), null);
					} catch (Exception ignored) {
						return fehler(String.valueOf(e.getMessage()));
					}
				case 401:
					return fehler("Dieses Telefon ist an der Anlage nicht als geteiltes Telefon freigegeben.");
				default:
					Log.w(TAG, "portal " + paket + " " + methode + ": Anlage " + e.status);
					return fehler(e.getMessage() != null && !e.getMessage().startsWith("HTTP ")
							? e.getMessage() : "Die Telefonanlage antwortet nicht (" + e.status + ").");
			}
		} catch (Exception e) {
			Log.w(TAG, "portal " + paket + " " + methode + ": " + e.getClass().getSimpleName());
			return fehler("Keine Verbindung zur Telefonanlage.");
		}
	}

	private Bundle ausfuehren(String method, String arg, Bundle extras, String aufrufer) {
		Bundle b = new Bundle();
		try {
			if ("angemeldet".equals(method)) {
				// ohne Netz: zuletzt bekannter Stand (Launcher prueft das bei jedem Anzeigen)
				b.putBoolean("angemeldet", Anlage.angemeldet(getContext()));
			} else if ("sip_person".equals(method)) {
				return sipPerson();
			} else if ("messenger_konto".equals(method)) {
				// Rosenweg Messenger: Anlage-Token der angemeldeten Person
				JSONObject k = Anlage.messengerKonto(getContext());
				if (k != null) {
					b.putString("server", k.optString("server"));
					b.putString("token", k.optString("token"));
					b.putLong("gueltig_bis", k.optLong("gueltig_bis"));
				}
			} else if ("nextcloud_konto".equals(method)) {
				// Abgemeldet: nur Apps im Nachlauf (offene Dateien) bekommen den Zugang noch
				if (!Anlage.angemeldet(getContext()) && !Nachlauf.enthaelt(getContext(), aufrufer)) return b;
				// Nextcloud-Zugang der angemeldeten Person fuer die Rosenweg-Apps (cloud-Bibliothek)
				JSONObject k = Anlage.nextcloudKonto(getContext());
				if (k != null) {
					b.putString("server", k.optString("server"));
					b.putString("benutzer", k.optString("benutzer"));
					b.putString("app_passwort", k.optString("app_passwort"));
				}
			} else if ("hintergrund_zuruecksetzen".equals(method)) {
				String f = Hintergrund.zuruecksetzen(getContext());
				b.putBoolean("ok", f == null);
				if (f != null) b.putString("fehler", f);
			} else if (method != null && method.startsWith("karten_")) {
				return karten(method, arg, extras);
			} else if ("einstellungen_holen".equals(method)) {
				JSONObject d = Anlage.einstellungen(getContext(), arg);
				b.putLong("geaendert", d.optLong("geaendert"));
				JSONObject w = d.optJSONObject("werte");
				b.putString("werte", w == null ? "{}" : w.toString());
			} else if ("einstellungen_senden".equals(method) && extras != null) {
				JSONObject daten = new JSONObject()
						.put("geaendert", extras.getLong("geaendert"))
						.put("werte", new JSONObject(extras.getString("werte", "{}")));
				b.putBoolean("uebernommen", Anlage.einstellungenSenden(getContext(), arg, daten).optBoolean("uebernommen"));
			}
		} catch (Exception e) {
			Log.w(TAG, method + ": " + e);
			b.putString("fehler", String.valueOf(e.getMessage()));
		}
		return b;
	}

	/** Persoenlicher SIP-Zugang fuer Rosenweg Telefon (siehe Kopf). */
	private Bundle sipPerson() {
		android.content.Context c = getContext();
		Bundle b = new Bundle();
		b.putBoolean("angemeldet", false);
		try {
			JSONObject k = Anlage.sipPerson(c);
			if (k == null || k.optString("password").isEmpty()) return b;
			android.content.SharedPreferences p = Anlage.prefs(c);
			String server = p.getString(org.sipdroid.sipua.ui.Settings.PREF_SERVER, "");
			String port = p.getString(org.sipdroid.sipua.ui.Settings.PREF_PORT, "");
			String transport = p.getString(org.sipdroid.sipua.ui.Settings.PREF_PROTOCOL, "");
			b.putBoolean("angemeldet", true);
			b.putString("server", server.isEmpty() ? k.optString("server") : server);
			int pz;
			try {
				pz = port.isEmpty() ? k.optInt("port", 5060) : Integer.parseInt(port);
			} catch (NumberFormatException e) {
				pz = k.optInt("port", 5060);
			}
			b.putInt("port", pz);
			b.putString("transport", transport.isEmpty() ? k.optString("transport", "tcp") : transport);
			b.putString("user", k.optString("user"));
			b.putString("auth_user", k.optString("auth_user", k.optString("user")));
			b.putString("password", k.optString("password"));
			b.putString("nummer", k.optString("nummer"));
			b.putString("name", k.optString("name"));
		} catch (Anlage.AnlageFehler e) {
			// 404: niemand angemeldet / keine persoenliche Nummer -> angemeldet=false
			if (e.status != 404) {
				b.putString("fehler", String.valueOf(e.getMessage()));
				b.putInt("status", e.status);
			}
		} catch (Exception e) {
			Log.w(TAG, "sip_person: " + e.getClass().getSimpleName());   // nie den Zugang loggen
			b.putString("fehler", "Keine Verbindung zur Anlage");
		}
		return b;
	}

	/** Kartenverwaltung fuer "Rosenweg Karten" (Technik/Praesident). */
	private Bundle karten(String method, String arg, Bundle extras) {
		Bundle b = new Bundle();
		android.content.Context c = getContext();
		try {
			// Live statt gemerkt: nach einem Personenwechsel ist PREF_TECHNIK kurz noch der alte Stand
			JSONObject s = Anlage.status(c);
			if (!s.optBoolean("angemeldet") || !s.optBoolean("technik")) {
				b.putString("fehler", "nicht berechtigt");
				b.putBoolean("nicht_berechtigt", true);
				return b;
			}
			JSONObject r;
			switch (method) {
				case "karten_info":
					r = Anlage.kartenInfo(c, arg);
					break;
				case "karten_zuordnen":
					String email = extras == null ? "" : extras.getString("email", "");
					if (email.isEmpty()) {
						b.putString("fehler", "E-Mail fehlt");
						return b;
					}
					r = Anlage.kartenZuordnen(c, arg, email);
					break;
				case "karten_entziehen":
					r = Anlage.kartenEntziehen(c, arg);
					break;
				case "karten_loeschen":
					r = Anlage.kartenLoeschen(c, arg);
					break;
				case "karten_personen":
					r = Anlage.kartenPersonen(c);
					break;
				default:
					b.putString("fehler", "unbekannte Methode");
					return b;
			}
			b.putString("json", r.toString());
		} catch (Anlage.AnlageFehler e) {
			b.putString("fehler", String.valueOf(e.getMessage()));
			b.putInt("status", e.status);
			if (e.status == 403 && "nicht berechtigt".equals(e.getMessage())) b.putBoolean("nicht_berechtigt", true);
		} catch (Exception e) {
			Log.w(TAG, method + ": " + e);
			b.putString("fehler", "Keine Verbindung zur Anlage");
		}
		return b;
	}

	@Override
	public String getType(Uri uri) {
		return PFADE.match(uri) == VOICEMAIL ? "audio/wav" : null;
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		return null;
	}

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		return 0;
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		return 0;
	}
}
