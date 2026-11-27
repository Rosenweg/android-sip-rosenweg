package org.sipdroid.sipua.rosenweg;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import ch.rosenweg4303.rwtunnel.RwFernwartung;

/**
 * Fernwartung ueber den dauerhaften adb-Tunnel (Plan adb-tunnel-telefone, Stefan 27.11.2026:
 * "dauerhaft, die Telefone sind nicht privat"; Tunnel in Rosenweg Geraet, keine eigene App).
 *
 * Die Anlage sagt im Status, wie es steht ("fernwartung"):
 *   neu     noch kein Schluessel bekannt: eigenes WireGuard-Paar erzeugen, oeffentlichen Teil melden
 *   wartet  Technik muss den Schluessel auf /smartphones bestaetigen; kein Tunnel
 *   bereit  Tunnel dauerhaft offen (FernwartungDienst), adb dauerhaft an, Port 5555 haelt Geraet
 *   aus     Not-Aus: Tunnel zu, ADB_ENABLED=0 (kein adbd, auch nicht per USB), bis "ein"
 * Fehlt das Feld (Anlage ohne Zentrale), aendert sich nichts.
 *
 * Im Tunnel bietet das Telefon nur adbd (5555) und die Anzeige (7000) an, nur fuer die Zentrale
 * (rwtunnel, Hoermodus). Jede adb-Verbindung sieht Geraet selbst: daran haengt das Band
 * "Fernwartung aktiv - Technik (Name)" (FernwartungBand).
 *
 * Der private Schluessel liegt nur in noBackupFilesDir (wie der adb-Schluessel von AdbSelbst).
 */
public final class Fernwartung {
	static final String TAG = "RosenwegFernwartung";
	private static final String SCHLUESSEL_DATEI = "fernwartung_wg.key";
	/** Schluessel hoechstens alle 10 Min. neu melden (Anlage nicht erreichbar, Telefon wartet). */
	private static final long MELDEN_MS = 10 * 60_000L;

	private Fernwartung() {}

	static SharedPreferences prefs(Context c) {
		return c.getSharedPreferences("rosenweg_fernwartung", Context.MODE_PRIVATE);
	}

	/** Tunnel steht laut Anlage und kein Not-Aus: adb bleibt dauerhaft an. */
	public static boolean adbDauerhaft(Context c) {
		return "bereit".equals(prefs(c).getString("stand", "")) && !notAus(c);
	}

	/** Not-Aus von /smartphones: adb bleibt aus, auch "1 Std." schaltet es nicht ein. */
	public static boolean notAus(Context c) {
		return "aus".equals(prefs(c).getString("stand", ""));
	}

	/** Fingerabdruecke (adb), die die Bedienungshilfe zusaetzlich zum eigenen zulassen darf. */
	static Set<String> erlaubteSchluessel(Context c) {
		Set<String> s = new HashSet<>(prefs(c).getStringSet("schluessel", new HashSet<>()));
		String eigen = AdbSelbst.eigenerFingerabdruck(c);
		if (!eigen.isEmpty()) s.add(eigen);
		return s;
	}

	/** Die Bedienungshilfe hat einen bekannten Schluessel (Zentrale, Updater) zugelassen. */
	static void zugelassen(Context c, String fp) {
		Set<String> z = new HashSet<>(prefs(c).getStringSet("zugelassen", new HashSet<>()));
		if (z.add(fp)) prefs(c).edit().putStringSet("zugelassen", z).apply();
	}

	/**
	 * Erste Verbindung durch den Tunnel, aber der Schluessel der Zentrale ist hier noch nicht
	 * zugelassen: die Rueckfrage sichtbar machen. Hoechstens alle 30 Min. (adbd fragt nur bei
	 * unbekannten Schluesseln; ist er schon bekannt, kommt keine Rueckfrage).
	 */
	static boolean rueckfrageNoetig(Context c) {
		SharedPreferences p = prefs(c);
		Set<String> offen = new HashSet<>(p.getStringSet("schluessel", new HashSet<>()));
		offen.removeAll(p.getStringSet("zugelassen", new HashSet<>()));
		if (offen.isEmpty() || System.currentTimeMillis() - p.getLong("rueckfrage_um", 0) < 30 * 60_000L) return false;
		p.edit().putLong("rueckfrage_um", System.currentTimeMillis()).apply();
		return true;
	}

	// ─── Schluessel ─────────────────────────────────────────────────────────

	/** Eigenes WireGuard-Paar: {privat, oeffentlich}, einmal erzeugt, fest bis Technik es verwirft. */
	static synchronized String[] schluessel(Context c) throws Exception {
		if (!RwFernwartung.verfuegbar()) throw new Exception("rwtunnel fehlt: " + RwFernwartung.ladeFehler());
		File f = new File(c.getNoBackupFilesDir(), SCHLUESSEL_DATEI);
		if (f.exists()) {
			byte[] b = new byte[(int) f.length()];
			try (FileInputStream in = new FileInputStream(f)) {
				int n = 0;
				while (n < b.length) {
					int r = in.read(b, n, b.length - n);
					if (r < 0) break;
					n += r;
				}
			}
			String priv = new String(b, StandardCharsets.US_ASCII).trim();
			String pub = prefs(c).getString("pubkey", "");
			if (pub.isEmpty()) {
				JSONObject o = new JSONObject(RwFernwartung.oeffentlich(priv));
				if (o.has("fehler")) throw new Exception(o.getString("fehler"));
				pub = o.getString("oeffentlich");
				prefs(c).edit().putString("pubkey", pub).apply();
			}
			return new String[]{priv, pub};
		}
		JSONObject o = new JSONObject(RwFernwartung.schluesselpaar());
		if (o.has("fehler")) throw new Exception(o.getString("fehler"));
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(o.getString("privat").getBytes(StandardCharsets.US_ASCII));
		}
		f.setReadable(false, false);
		f.setReadable(true, true);
		prefs(c).edit().putString("pubkey", o.getString("oeffentlich")).apply();
		Log.i(TAG, "eigener Tunnel-Schluessel erzeugt: " + o.getString("oeffentlich").substring(0, 8) + "…");
		return new String[]{o.getString("privat"), o.getString("oeffentlich")};
	}

	// ─── Stand von der Anlage (Status, nach Weckanruf 2098) ─────────────────

	/** Aus Anlage.status (Hintergrund-Faden). */
	public static synchronized void anlageStand(Context c, JSONObject fw) {
		final Context app = c.getApplicationContext();
		String stand = fw.optString("stand", "");
		try {
			switch (stand) {
				case "neu":
				case "wartet":
					stoppen(app, "wartet auf Bestaetigung");
					prefs(app).edit().putString("stand", stand).apply();
					String eigen = schluessel(app)[1];
					if ("neu".equals(stand) || !eigen.equals(fw.optString("pubkey", ""))) melden(app, eigen);
					break;
				case "bereit":
					bereit(app, fw);
					break;
				case "aus":
					aus(app);
					break;
				default:
					Log.w(TAG, "unbekannter Stand " + stand);
			}
		} catch (Exception e) {
			Log.w(TAG, "Stand " + stand + ": " + e);
		}
	}

	private static void melden(Context c, String pub) throws Exception {
		if (System.currentTimeMillis() - prefs(c).getLong("gemeldet_um", 0) < MELDEN_MS) return;
		prefs(c).edit().putLong("gemeldet_um", System.currentTimeMillis()).apply();
		JSONObject r = Anlage.tunnelMelden(c, pub);
		Log.i(TAG, "Schluessel gemeldet, Stand " + r.optString("stand"));
		if ("bereit".equals(r.optString("stand"))) bereit(c, r);
	}

	private static void bereit(Context c, JSONObject fw) throws Exception {
		String eigen = schluessel(c)[1];
		if (!eigen.equals(fw.optString("pubkey", ""))) {
			// Anlage kennt einen anderen Schluessel (Telefon zurueckgesetzt?): neu melden
			stoppen(c, "anderer Schluessel");
			melden(c, eigen);
			return;
		}
		SharedPreferences p = prefs(c);
		boolean warAus = notAus(c);
		JSONObject alt = new JSONObject(p.getString("konfig", "{}"));
		JSONObject k = new JSONObject();
		for (String n : new String[]{"adresse", "gateway", "peer_public", "endpoint", "endpoint_intern"}) {
			// Antwortet die Zentrale gerade nicht, fehlen peer_public/endpoint: das Alte gilt
			String v = fw.optString(n, "");
			k.put(n, v.isEmpty() ? alt.optString(n, "") : v);
		}
		Set<String> fps = new HashSet<>();
		JSONArray a = fw.optJSONArray("schluessel");
		for (int i = 0; a != null && i < a.length(); i++) {
			String fp = a.optString(i, "");
			if (fp.matches("([0-9A-F]{2}:){15}[0-9A-F]{2}")) fps.add(fp);
		}
		boolean neu = !k.toString().equals(alt.toString());
		p.edit().putString("stand", "bereit").putString("konfig", k.toString()).putStringSet("schluessel", fps).apply();
		if (warAus) Log.i(TAG, "Fernwartung wieder ein");
		// adb dauerhaft an; Port 5555 stellt Geraet selbst her (AdbSelbst, auch nach Neustart)
		if (Sperre.istDeviceOwner(c) && !WlanDebug.an(c)) {
			try {
				Sperre.dpm(c).setGlobalSetting(Sperre.admin(c), Settings.Global.ADB_ENABLED, "1");
			} catch (Exception e) {
				Log.w(TAG, "adb ein: " + e.getClass().getSimpleName());
			}
		}
		WlanDebug.tcpPruefen(c, 8_000);
		if (k.optString("peer_public").isEmpty() || k.optString("adresse").isEmpty()) {
			Log.w(TAG, "bereit, aber noch ohne Angaben der Zentrale");
			return;
		}
		if (neu || !FernwartungDienst.laeuft()) {
			starten(c, neu);
		} else {
			FernwartungDienst.anstossen();   // Weckanruf 2098: sofort Handshake
		}
	}

	private static void aus(Context c) {
		boolean war = notAus(c);
		prefs(c).edit().putString("stand", "aus").apply();
		stoppen(c, "Not-Aus");
		WlanDebug.ausschalten(c);   // adbd aus (Device Owner), auch USB
		try {
			if (Sperre.istDeviceOwner(c) && WlanDebug.an(c)) {
				Sperre.dpm(c).setGlobalSetting(Sperre.admin(c), Settings.Global.ADB_ENABLED, "0");
			}
		} catch (Exception e) {
			Log.w(TAG, "adb aus: " + e.getClass().getSimpleName());
		}
		if (!war) Log.w(TAG, "Not-Aus: Tunnel zu, adb aus");
	}

	// ─── Dienst ─────────────────────────────────────────────────────────────

	/** Nach Neustart und Update (StartReceiver): Tunnel wieder auf, wenn er stand. */
	public static void nachStart(Context c) {
		if (adbDauerhaft(c) && RwFernwartung.verfuegbar()) starten(c, false);
	}

	static void starten(Context c, boolean neu) {
		Intent i = new Intent(c, FernwartungDienst.class).setAction(neu ? FernwartungDienst.NEU : FernwartungDienst.START);
		try {
			if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
			else c.startService(i);
		} catch (Exception e) {
			Log.w(TAG, "Dienst starten: " + e);
		}
	}

	static void stoppen(Context c, String grund) {
		if (FernwartungDienst.laeuft()) Log.i(TAG, "Tunnel zu: " + grund);
		c.stopService(new Intent(c, FernwartungDienst.class));
	}

	/** Konfiguration fuer RwFernwartung.start, mit dem gewuenschten Endpunkt. */
	static JSONObject tunnelKonfig(Context c, boolean intern) throws Exception {
		JSONObject k = new JSONObject(prefs(c).getString("konfig", "{}"));
		String ep = intern && !k.optString("endpoint_intern").isEmpty() ? k.optString("endpoint_intern") : k.optString("endpoint");
		if (ep.isEmpty()) ep = k.optString("endpoint_intern");
		return new JSONObject().put("private_key", schluessel(c)[0]).put("peer_public", k.optString("peer_public"))
				.put("endpoint", ep).put("adresse", k.optString("adresse")).put("gateway", k.optString("gateway"))
				.put("keepalive", 25);
	}

	static boolean hatIntern(Context c) {
		try {
			return !new JSONObject(prefs(c).getString("konfig", "{}")).optString("endpoint_intern").isEmpty();
		} catch (Exception e) {
			return false;
		}
	}

	static String endpunkt(Context c, boolean intern) {
		try {
			JSONObject k = new JSONObject(prefs(c).getString("konfig", "{}"));
			return intern ? k.optString("endpoint_intern") : k.optString("endpoint");
		} catch (Exception e) {
			return "";
		}
	}
}
