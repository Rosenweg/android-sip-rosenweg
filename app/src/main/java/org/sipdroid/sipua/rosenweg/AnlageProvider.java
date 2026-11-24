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
 *   call("karten_info", uid) -> {json: {found, assigned, status, display_id, person:{name,email}}}
 *   call("karten_zuordnen", uid, {email}) -> {json: {ok, display_id, name}}
 *   call("karten_entziehen", uid) -> {json: {ok}}
 *   call("karten_loeschen", uid) -> {json: {ok}}  (nur nicht zugeordnete Karten: aus UniFi Access loeschen)
 *   call("karten_personen", "personen") -> {json: {personen:[{name,email}]}}
 *       Kartenverwaltung (App "Rosenweg Karten"): nur wenn die angemeldete Person Technik/
 *       Praesident ist (hier live geprueft, die Anlage prueft nochmals). Sonst
 *       {fehler: "nicht berechtigt", nicht_berechtigt: true}. Fehler der Anlage: {fehler, status}.
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
