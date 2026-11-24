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
		Bundle b = new Bundle();
		if (arg == null || arg.isEmpty() || !Anlage.eingerichtet(getContext())) return b;
		try {
			if ("einstellungen_holen".equals(method)) {
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
