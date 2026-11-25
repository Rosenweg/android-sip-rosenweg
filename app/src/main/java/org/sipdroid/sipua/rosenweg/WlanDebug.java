package org.sipdroid.sipua.rosenweg;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * "Debugging ueber WLAN" fuer Technik (Stefan 25.11.2026, App-adb in Rosenweg Geraet):
 * befristet einschalten (1 Std.), danach und beim Abmelden wieder aus. Geht ueber den
 * Device Owner (setGlobalSetting ADB_WIFI_ENABLED). Koppeln bleibt beim Menschen: einmal
 * "Mit Kopplungscode koppeln" in den Entwickleroptionen, dann kennt das Telefon den Mac.
 */
public class WlanDebug extends BroadcastReceiver {
	private static final String TAG = "RosenwegWlanDebug";
	static final long DAUER_MS = 60 * 60 * 1000L;
	private static final String AUS = "ch.rosenweg4303.telefon.WLAN_DEBUG_AUS";
	private static final String PRUEFEN = "ch.rosenweg4303.telefon.WLAN_DEBUG_PRUEFEN";
	/** Nachpruefen, solange zentral eingeschaltet: an? Port noch gleich? */
	private static final long PRUEFEN_MS = 5 * 60 * 1000L;
	private static final long NOCHMALS_MS = 5 * 60 * 1000L;
	/** Nach Netzwechsel: wieder einschalten, aber nicht oefter als alle 30 s (Android-Dialog). */
	private static final long WIEDER_MS = 30 * 1000L;

	/**
	 * Stand 25.11.2026 (Stefan): klassisches adb ueber TCP (Port 5555, einmal "adb tcpip 5555")
	 * statt "Debugging ueber WLAN". Android stellt Letzteres bei jedem neuen Zugangspunkt ab und
	 * fragt bei gesperrtem Bildschirm gar nicht erst ("startConfirmationForNetwork:
	 * isLockScreenMode"). Zentral geschaltet wird darum nur noch adb selbst (ADB_ENABLED): an =
	 * adbd laeuft (USB und Port 5555), aus = kein adbd. Verbinden darf nur ein Rechner mit
	 * bekanntem Schluessel. Nach einem Neustart des Telefons ist Port 5555 weg (einmal USB).
	 */
	static final int TCP_PORT = 5555;

	public static boolean an(Context c) {
		return Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
	}

	/** Hoert adbd auf Port 5555? (blockierend, nicht im UI-Thread) */
	static boolean tcpOffen() {
		try (java.net.Socket so = new java.net.Socket()) {
			so.connect(new java.net.InetSocketAddress("127.0.0.1", TCP_PORT), 1000);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/** Debugging ueber WLAN nur in diesem WLAN (Stefan 25.11.2026). */
	static final String SSID = "Rosenweg";

	/** Name des aktuellen WLANs ohne Anfuehrungszeichen, "" wenn unbekannt. */
	static String aktuellesWlan(Context c) {
		try {
			if (Sperre.istDeviceOwner(c)) {
				// Standort-Recht braucht Android fuer den WLAN-Namen; der Geraeteverwalter gibt es sich selbst
				Sperre.dpm(c).setPermissionGrantState(Sperre.admin(c), c.getPackageName(),
						android.Manifest.permission.ACCESS_FINE_LOCATION,
						android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
			}
			android.net.wifi.WifiManager wm = c.getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
			String s = wm.getConnectionInfo().getSSID();
			return s == null ? "" : s.replace("\"", "");
		} catch (Exception e) {
			Log.w(TAG, "WLAN-Name: " + e);
			return "";
		}
	}

	/** Bestaetigung des Android-Dialogs erwartet (Geraet hat in den letzten 2 Min. eingeschaltet). */
	static boolean erwartetBestaetigung(Context c) {
		return System.currentTimeMillis() - prefs(c).getLong("eingeschaltet_um", 0) < 120_000
				&& SSID.equals(aktuellesWlan(c));
	}

	static void bestaetigt(Context c) {
		prefs(c).edit().putLong("eingeschaltet_um", 0).apply();
	}

	static android.content.SharedPreferences prefs(Context c) {
		return c.getSharedPreferences("rosenweg_wlan_debug", Context.MODE_PRIVATE);
	}

	/**
	 * Zentral (PBX-Verwaltung, Status "wlan_debug_bis" in epoch s): bis in der Zukunft = an bis
	 * dahin, auch ohne Anmeldung (Wartung); 0 = aus, falls es zentral eingeschaltet war.
	 */
	public static synchronized void zentral(Context c, long bisSek) {
		long jetzt = System.currentTimeMillis() / 1000;
		long war = prefs(c).getLong("zentral_bis", 0);
		if (bisSek > jetzt) {
			// Gleicher Anstoss: nur wiederholen, wenn Android es inzwischen abgeschaltet hat (WLAN-
			// Wechsel, Dialog nicht bestaetigt) - und hoechstens alle 5 Min., sonst ging der
			// Android-Dialog bei jedem Status neu auf (Stefan 25.11.2026: "stabiler machen").
			if (bisSek == war) {
				if (an(c)) {
					portMelden(c);
					return;
				}
				if (System.currentTimeMillis() - prefs(c).getLong("versuch_um", 0) < NOCHMALS_MS) return;
			}
			prefs(c).edit().putLong("zentral_bis", bisSek).apply();
			String f = einschalten(c, (bisSek - jetzt) * 1000L);
			pruefenPlanen(c);
			tcpPruefen(c, 10_000);
			Log.i(TAG, "zentral an bis " + bisSek + (f == null ? "" : " - " + f));
		} else if (war != 0) {
			prefs(c).edit().remove("zentral_bis").apply();
			ausschalten(c);
		}
	}

	/** Zentral (Anlage) eingeschaltet und noch nicht abgelaufen? */
	static boolean zentralAn(Context c) {
		return prefs(c).getLong("zentral_bis", 0) > System.currentTimeMillis() / 1000;
	}

	/** Beim Abmelden: nur das in der Sitzung (Kachel) eingeschaltete Debugging aus. */
	public static void sitzungEnde(Context c) {
		if (prefs(c).getLong("zentral_bis", 0) > System.currentTimeMillis() / 1000) return;
		ausschalten(c);
	}

	/** Einschalten; null = ok, sonst die Fehlermeldung. */
	public static String einschalten(Context c) {
		return einschalten(c, DAUER_MS);
	}

	static String einschalten(Context c, long dauerMs) {
		if (!Sperre.istDeviceOwner(c)) return "Rosenweg Gerät ist nicht Geräteverwalter";
		// Bestaetigt wird automatisch nur im WLAN "Rosenweg" (WlanBestaetiger); in anderen WLANs
		// fragt Android und jemand muss am Telefon von Hand zulassen (Stefan 25.11.2026).
		prefs(c).edit().putLong("eingeschaltet_um", System.currentTimeMillis())
				.putLong("versuch_um", System.currentTimeMillis()).apply();
		try {
			Sperre.dpm(c).setGlobalSetting(Sperre.admin(c), Settings.Global.ADB_ENABLED, "1");
		} catch (Exception e) {
			Log.w(TAG, "einschalten: " + e);
			return "Android lässt das nicht zu: " + e.getClass().getSimpleName();
		}
		AlarmManager am = c.getSystemService(AlarmManager.class);
		am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + dauerMs, ausIntent(c));
		Log.i(TAG, "adb an (" + dauerMs / 60000 + " min)");
		// Port erscheint erst nach dem Start von adbd (und ggf. der Bestaetigung) - etwas warten
		new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> portMelden(c), 8000);
		return an(c) ? null : "Eingeschaltet, Android meldet es aber noch nicht als aktiv";
	}

	public static void ausschalten(Context c) {
		try {
			c.getSystemService(AlarmManager.class).cancel(ausIntent(c));
			if (Sperre.istDeviceOwner(c) && an(c)) {
				Sperre.dpm(c).setGlobalSetting(Sperre.admin(c), Settings.Global.ADB_ENABLED, "0");
				Log.i(TAG, "adb aus");
			}
			c.getSystemService(AlarmManager.class).cancel(pruefIntent(c));
			melden(c, "", 0);
		} catch (Exception e) {
			Log.w(TAG, "ausschalten: " + e);
		}
	}

	private static PendingIntent ausIntent(Context c) {
		return PendingIntent.getBroadcast(c, 4311, new Intent(c, WlanDebug.class).setAction(AUS),
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
	}

	/** IPv4-Adresse im WLAN (zum Anzeigen), sonst "". */
	public static String adresse(Context c) {
		try {
			ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
			LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
			if (lp != null) for (LinkAddress la : lp.getLinkAddresses()) {
				if (la.getAddress() instanceof java.net.Inet4Address) return la.getAddress().getHostAddress();
			}
		} catch (Exception ignored) { }
		return "";
	}

	private static PendingIntent pruefIntent(Context c) {
		return PendingIntent.getBroadcast(c, 4312, new Intent(c, WlanDebug.class).setAction(PRUEFEN),
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
	}

	private static void pruefenPlanen(Context c) {
		c.getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() + PRUEFEN_MS, pruefIntent(c));
	}

	/** Alle 5 Min., solange zentral an: wieder einschalten, falls Android es abgestellt hat; Port melden. */
	private static void pruefen(Context c) {
		long bis = prefs(c).getLong("zentral_bis", 0);
		if (bis <= System.currentTimeMillis() / 1000) return;
		if (!an(c)) {
			String f = einschalten(c, bis * 1000L - System.currentTimeMillis());
			Log.i(TAG, "war aus, wieder an" + (f == null ? "" : " - " + f));
		} else {
			portMelden(c);
		}
		tcpPruefen(c, 10_000);
		pruefenPlanen(c);
	}

	private static android.database.ContentObserver beobachter;

	/**
	 * Android stellt "Debugging ueber WLAN" bei jedem Netzwechsel ab ("Detected wifi network
	 * change. Disabling adbwifi." - schon beim Wechsel 2,4 -> 5 GHz am selben Zugangspunkt,
	 * typisch kurz nach dem Ausschalten des Bildschirms). Solange es zentral an ist: sofort
	 * wieder einschalten (hoechstens alle 30 s) und den neuen Port melden. Geht auch bei
	 * gesperrtem Bildschirm (Device Owner).
	 */
	public static synchronized void beobachten(Context c) {
		if (beobachter != null) return;
		final Context app = c.getApplicationContext();
		final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
		beobachter = new android.database.ContentObserver(h) {
			@Override
			public void onChange(boolean selbst) {
				long bis = prefs(app).getLong("zentral_bis", 0);
				if (bis <= System.currentTimeMillis() / 1000) return;
				if (an(app)) {
					h.postDelayed(() -> portMelden(app), 5000);
					return;
				}
				// kurz warten, bis das neue Netz steht
				h.postDelayed(() -> {
					if (an(app) || System.currentTimeMillis() - prefs(app).getLong("versuch_um", 0) < WIEDER_MS) return;
					String f = einschalten(app, bis * 1000L - System.currentTimeMillis());
					Log.i(TAG, "von Android abgestellt (Netzwechsel), wieder an" + (f == null ? "" : " - " + f));
				}, 3000);
			}
		};
		try {
			app.getContentResolver().registerContentObserver(
					Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, beobachter);
		} catch (Exception e) {
			Log.w(TAG, "beobachten: " + e.getClass().getSimpleName());
		}
	}

	/**
	 * C: adb laeuft (zentral an), Port 5555 ist aber zu (nach einem Neustart): Rosenweg Geraet
	 * stellt ihn selbst wieder her (AdbSelbst). Im Hintergrund, nach kurzer Wartezeit.
	 */
	static void tcpPruefen(Context c, long warteMs) {
		final Context app = c.getApplicationContext();
		new Thread(() -> {
			try { Thread.sleep(warteMs); } catch (InterruptedException e) { return; }
			if (!zentralAn(app) || !an(app) || tcpOffen()) return;
			String f = AdbSelbst.tcpWiederherstellen(app);
			Log.i(TAG, "Port " + TCP_PORT + " war zu: " + (f == null ? "wiederhergestellt" : f));
		}, "TcpPruefen").start();
	}

	// ─── Port per mDNS finden (adbd meldet "_adb-tls-connect._tcp") ──────────────────

	private static volatile boolean sucht;

	/** Eigenen Debug-Port suchen und an die Anlage melden (nur bei Aenderung, sonst alle 10 Min.). */
	static void portMelden(Context c) {
		if (sucht || !an(c)) return;
		sucht = true;
		final Context app = c.getApplicationContext();
		final String eigene = adresse(app);
		// Normalfall: Port 5555 (adb tcpip); sonst ein von Hand eingeschaltetes "Debugging ueber WLAN"
		new Thread(() -> {
			if (tcpOffen()) {
				sucht = false;
				melden(app, eigene, TCP_PORT);
			} else {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> mdnsSuchen(app, eigene));
			}
		}).start();
	}

	private static void mdnsSuchen(final Context app, final String eigene) {
		final android.net.nsd.NsdManager nsd = app.getSystemService(android.net.nsd.NsdManager.class);
		final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
		final android.net.nsd.NsdManager.DiscoveryListener[] dl = new android.net.nsd.NsdManager.DiscoveryListener[1];
		final Runnable ende = () -> {
			if (!sucht) return;
			sucht = false;
			try { nsd.stopServiceDiscovery(dl[0]); } catch (Exception ignored) { }
		};
		dl[0] = new android.net.nsd.NsdManager.DiscoveryListener() {
			boolean geloest;
			@Override public void onStartDiscoveryFailed(String t, int e) { sucht = false; Log.w(TAG, "mDNS: " + e); }
			@Override public void onStopDiscoveryFailed(String t, int e) { }
			@Override public void onDiscoveryStarted(String t) { }
			@Override public void onDiscoveryStopped(String t) { }
			@Override public void onServiceLost(android.net.nsd.NsdServiceInfo i) { }
			@Override public void onServiceFound(android.net.nsd.NsdServiceInfo i) {
				if (geloest) return;
				geloest = true;   // nur eine Aufloesung gleichzeitig erlaubt
				nsd.resolveService(i, new android.net.nsd.NsdManager.ResolveListener() {
					@Override public void onResolveFailed(android.net.nsd.NsdServiceInfo s, int e) { geloest = false; }
					@Override public void onServiceResolved(android.net.nsd.NsdServiceInfo s) {
						String host = s.getHost() == null ? "" : s.getHost().getHostAddress();
						// Andere Telefone im selben WLAN melden sich auch: nur der eigene Dienst zaehlt
						if (!eigene.isEmpty() && !host.isEmpty() && !host.equals(eigene) && !host.startsWith("127.")) {
							geloest = false;
							return;
						}
						h.post(ende);
						melden(app, eigene, s.getPort());
					}
				});
			}
		};
		try {
			nsd.discoverServices("_adb-tls-connect._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, dl[0]);
			h.postDelayed(ende, 20_000);
		} catch (Exception e) {
			sucht = false;
			Log.w(TAG, "mDNS: " + e.getClass().getSimpleName());
		}
	}

	private static void melden(Context c, String adresse, int port) {
		android.content.SharedPreferences p = prefs(c);
		String neu = adresse + ":" + port;
		if (neu.equals(p.getString("gemeldet", "")) && System.currentTimeMillis() - p.getLong("gemeldet_um", 0) < 600_000) return;
		new Thread(() -> {
			try {
				Anlage.adbMelden(c, adresse, port);
				p.edit().putString("gemeldet", neu).putLong("gemeldet_um", System.currentTimeMillis()).apply();
				Log.i(TAG, "Port gemeldet: " + (port == 0 ? "aus" : String.valueOf(port)));
			} catch (Exception e) {
				Log.w(TAG, "Port melden: " + e.getClass().getSimpleName());
			}
		}).start();
	}

	@Override
	public void onReceive(Context c, Intent i) {
		if (AUS.equals(i.getAction())) {
			prefs(c).edit().remove("zentral_bis").apply();
			ausschalten(c);
		} else if (PRUEFEN.equals(i.getAction())) {
			pruefen(c);
		}
	}
}
