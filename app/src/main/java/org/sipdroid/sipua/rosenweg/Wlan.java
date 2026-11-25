package org.sipdroid.sipua.rosenweg;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.List;

/**
 * WLAN "Rosenweg" (PPSK) der geteilten Telefone (Stefan 25.11.2026): Die Anlage liefert den
 * Schluessel des Netzes, in dem das Telefon sein soll (Variante 1: RK-Clients; spaeter je nach
 * angemeldeter Person das Haus-VLAN). Geraet richtet ihn als Device Owner ein und sperrt die
 * Konfiguration gegen Aendern. Andere gespeicherte WLANs bleiben; es wird kein Wechsel erzwungen.
 * Der Schluessel wird nie geloggt und nur als Pruefsumme gemerkt.
 */
public final class Wlan {
	private static final String TAG = "RosenwegWlan";
	private static final String PREF = "rosenweg_wlan";
	private static final long ABSTAND_MS = 6 * 3600 * 1000L;

	private Wlan() {}

	private static SharedPreferences prefs(Context c) {
		return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
	}

	/** Aus dem Status: hoechstens alle 6 h, oder sofort, wenn sich An-/Abgemeldet geaendert hat. */
	static void vielleicht(Context c, boolean angemeldet) {
		if (!Sperre.istDeviceOwner(c)) return;
		SharedPreferences p = prefs(c);
		boolean wechsel = p.getBoolean("angemeldet", !angemeldet) != angemeldet;
		if (!wechsel && System.currentTimeMillis() - p.getLong("um", 0) < ABSTAND_MS) return;
		p.edit().putBoolean("angemeldet", angemeldet).putLong("um", System.currentTimeMillis()).apply();
		Context app = c.getApplicationContext();
		new Thread(() -> einrichten(app), "Wlan").start();
	}

	/**
	 * Beim Anmelden (Stefan 25.11.2026): das WLAN der Person sofort einrichten, nicht nebenbei.
	 * Blockierend. true = das Telefon verbindet sich gerade neu (anderes VLAN) - dann auf das Netz warten.
	 */
	static boolean fuerAnmeldung(Context c) {
		if (!Sperre.istDeviceOwner(c)) return false;
		prefs(c).edit().putBoolean("angemeldet", true).putLong("um", System.currentTimeMillis()).apply();
		return einrichten(c);
	}

	/** Steht der WLAN-Wechsel fuer diesen Zustand (an-/abgemeldet) noch aus? */
	static boolean wechselOffen(Context c, boolean angemeldet) {
		return Sperre.istDeviceOwner(c) && prefs(c).getBoolean("angemeldet", !angemeldet) != angemeldet;
	}

	/** Beim Abmelden: zurueck ins allgemeine WLAN (RK-Clients). Blockierend, true = neu verbunden. */
	static boolean fuerAbmeldung(Context c) {
		if (!Sperre.istDeviceOwner(c)) return false;
		prefs(c).edit().putBoolean("angemeldet", false).putLong("um", System.currentTimeMillis()).apply();
		return einrichten(c);
	}

	/** @return true, wenn das Telefon dafuer neu verbunden wurde */
	static synchronized boolean einrichten(Context c) {
		try {
			JSONObject w = Anlage.wlan(c);
			String ssid = w.optString("ssid", ""), pw = w.optString("passwort", ""), netz = w.optString("netz", "");
			if (ssid.isEmpty() || pw.length() < 8) return false;
			String pruef = sha(ssid + "\n" + pw);
			WifiManager wm = c.getSystemService(WifiManager.class);
			WifiConfiguration alt = null;
			List<WifiConfiguration> liste = wm.getConfiguredNetworks();
			if (liste != null) for (WifiConfiguration k : liste) {
				if (("\"" + ssid + "\"").equals(k.SSID)) { alt = k; break; }
			}
			if (alt != null && pruef.equals(prefs(c).getString("pruef", ""))) return false;   // unveraendert
			WifiConfiguration k = new WifiConfiguration();
			k.SSID = "\"" + ssid + "\"";
			k.preSharedKey = "\"" + pw + "\"";
			k.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
			int id;
			if (alt != null) {
				k.networkId = alt.networkId;
				id = wm.updateNetwork(k);
			} else {
				id = wm.addNetwork(k);
			}
			if (id < 0) {
				Log.w(TAG, "WLAN " + ssid + " nicht eingerichtet");
				return false;
			}
			wm.enableNetwork(id, false);   // erlaubt, aber kein erzwungener Wechsel
			boolean neu = false;
			// Haengt das Telefon schon im "Rosenweg", gilt der neue Schluessel (anderes VLAN, z.B.
			// nach An-/Abmelden) erst nach einer neuen Verbindung - kurz neu verbinden.
			if (alt != null && ("\"" + ssid + "\"").equals(wm.getConnectionInfo().getSSID())) {
				wm.disconnect();
				wm.reconnect();
				neu = true;
				Log.i(TAG, "neu verbunden (" + netz + ")");
			}
			// Einrichtung gegen Aendern/Vergessen durch Benutzer sperren (nur Device Owner aendert)
			try {
				Sperre.dpm(c).setConfiguredNetworksLockdownState(Sperre.admin(c), true);
			} catch (Throwable e) {
				Log.w(TAG, "Sperre der WLAN-Einrichtung: " + e.getClass().getSimpleName());
			}
			prefs(c).edit().putString("pruef", pruef).putString("netz", netz).apply();
			Log.i(TAG, "WLAN " + ssid + " eingerichtet (" + netz + ")");
			return neu;
		} catch (Exception e) {
			Log.w(TAG, "WLAN: " + e.getClass().getSimpleName());
			return false;
		}
	}

	private static String sha(String s) throws Exception {
		StringBuilder h = new StringBuilder();
		for (byte b : MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"))) h.append(String.format("%02x", b));
		return h.toString();
	}
}
