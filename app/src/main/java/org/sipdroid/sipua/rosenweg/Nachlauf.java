package org.sipdroid.sipua.rosenweg;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Abmelden erst, wenn alles hochgeladen ist (Entscheid Stefan 24.11.2026).
 *
 * Jede Rosenweg-App mit Cloud-Bibliothek (ab 0.6.0) beantwortet ueber
 * content://<paket>.rosenweg.hochladen, wie viele Dateien noch warten.
 *
 * - Abmelden per Knopf: geht erst, wenn nichts mehr wartet (RosenwegActivity).
 * - Abmelden ohne Knopf (Web, Admin, Neustart): gesperrt wird sofort, aber Apps
 *   mit offenen Dateien behalten ihre Daten und duerfen mit dem Nextcloud-Zugang
 *   der Person weiter hochladen (Nachlauf). Ist alles oben, werden sie
 *   ausgeblendet und geloescht, und die Anlage widerruft den Zugang. Spaetestens
 *   nach 24 h widerruft die Anlage ihn ohnehin; dann wird auch hier aufgeraeumt.
 * - Waehrend des Nachlaufs kann sich niemand anmelden.
 */
public class Nachlauf extends Service {
	private static final String TAG = "RosenwegNachlauf";
	private static final String PREFS = "rosenweg_nachlauf";
	private static final String APPS = "apps";
	private static final String SEIT = "seit";
	private static final long TAKT_MS = 20000;
	private static final long HOECHSTENS_MS = 24L * 3600 * 1000;
	private static final ExecutorService FRAGEN = Executors.newCachedThreadPool();

	// ---- Upload-Stand der Apps ----

	/** Offene Dateien (wartend + fehlgeschlagen) einer App; 0 ohne Cloud-Bibliothek. */
	public static int offen(Context c, String paket) {
		Bundle b = rufen(c, paket, "status", 4);
		return b == null ? 0 : b.getInt("ausstehend") + b.getInt("fehlgeschlagen");
	}

	public static void anstossen(Context c, String paket) {
		rufen(c, paket, "anstossen", 4);
	}

	private static Bundle rufen(Context c, String paket, String methode, int sekunden) {
		try {
			return FRAGEN.submit(() -> c.getContentResolver().call(
					Uri.parse("content://" + paket + ".rosenweg.hochladen"), methode, null, null))
					.get(sekunden, TimeUnit.SECONDS);
		} catch (Exception e) {   // App ohne Provider (keine Cloud-Bibliothek) oder ausgeblendet
			return null;
		}
	}

	/** Persoenliche Katalog-Apps, die gerade installiert sind. */
	static Set<String> persoenlicheApps(Context c) {
		Set<String> s = new HashSet<>();
		JSONArray alle = Katalog.katalog(c);
		for (int i = 0; i < alle.length(); i++) {
			JSONObject app = alle.optJSONObject(i);
			if (app == null || !"apk".equals(app.optString("art")) || app.optBoolean("immer", false)) continue;
			if (app.optBoolean("persoenlich", true) && Katalog.installiert(c, app.optString("paket"))) {
				s.add(app.optString("paket"));
			}
		}
		return s;
	}

	/** Paket -> offene Dateien, nur Apps mit offenen. Blockiert (kurz). */
	public static Map<String, Integer> alleOffen(Context c) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (String p : persoenlicheApps(c)) {
			int n = offen(c, p);
			if (n > 0) m.put(p, n);
		}
		return m;
	}

	public static String beschreibung(Context c, Map<String, Integer> offen) {
		StringBuilder sb = new StringBuilder();
		int summe = 0;
		for (Map.Entry<String, Integer> e : offen.entrySet()) {
			summe += e.getValue();
			if (sb.length() > 0) sb.append(", ");
			sb.append(name(c, e.getKey())).append(" ").append(e.getValue());
		}
		return summe + " Datei(en) noch nicht in der Nextcloud (" + sb + ")";
	}

	private static String name(Context c, String paket) {
		try {
			return String.valueOf(c.getPackageManager().getApplicationLabel(
					c.getPackageManager().getApplicationInfo(paket, 0))).replace("Rosenweg ", "");
		} catch (Exception e) {
			return paket;
		}
	}

	// ---- Nachlauf-Zustand ----

	private static SharedPreferences prefs(Context c) {
		return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	public static boolean aktiv(Context c) {
		return !prefs(c).getStringSet(APPS, new HashSet<>()).isEmpty();
	}

	public static boolean enthaelt(Context c, String paket) {
		return paket != null && prefs(c).getStringSet(APPS, new HashSet<>()).contains(paket);
	}

	/** Beim Sperren: Apps mit offenen Dateien zuruecklegen (statt loeschen). */
	static void vormerken(Context c, Set<String> pakete) {
		if (pakete.isEmpty()) return;
		Set<String> s = new HashSet<>(prefs(c).getStringSet(APPS, new HashSet<>()));
		s.addAll(pakete);
		SharedPreferences.Editor e = prefs(c).edit().putStringSet(APPS, s);
		if (!prefs(c).contains(SEIT)) e.putLong(SEIT, System.currentTimeMillis());
		e.commit();
		starten(c);
	}

	public static void starten(Context c) {
		if (!aktiv(c)) return;
		Intent i = new Intent(c, Nachlauf.class);
		if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
		else c.startService(i);
	}

	/** Kein Nachlauf (mehr): Anlage darf den Nextcloud-Zugang widerrufen. */
	static void fertigMelden(Context c) {
		new Thread(() -> {
			try {
				Anlage.nextcloudFertig(c);
			} catch (Exception e) {
				Log.w(TAG, "fertig melden: " + e);   // die Anlage widerruft spaetestens nach 24 h
			}
		}).start();
	}

	// ---- Dienst ----

	private final Handler hand = new Handler(Looper.getMainLooper());
	private final Runnable takt = new Runnable() {
		@Override
		public void run() {
			new Thread(Nachlauf.this::schritt).start();
		}
	};

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (Build.VERSION.SDK_INT >= 26) {
			NotificationManager nm = getSystemService(NotificationManager.class);
			nm.createNotificationChannel(new NotificationChannel("nachlauf", "Hochladen nach dem Abmelden",
					NotificationManager.IMPORTANCE_LOW));
			Notification n = new Notification.Builder(this, "nachlauf")
					.setSmallIcon(android.R.drawable.stat_sys_upload)
					.setContentTitle("Dateien werden noch hochgeladen")
					.setContentText("Danach ist das Telefon frei für die nächste Person.")
					.setOngoing(true).build();
			if (Build.VERSION.SDK_INT >= 34) {
				startForeground(7, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
			} else {
				startForeground(7, n);
			}
		}
		hand.removeCallbacks(takt);
		hand.post(takt);
		return START_STICKY;
	}

	private void schritt() {
		Context c = getApplicationContext();
		Set<String> s = new HashSet<>(prefs(c).getStringSet(APPS, new HashSet<>()));
		boolean abgelaufen = System.currentTimeMillis() - prefs(c).getLong(SEIT, System.currentTimeMillis()) > HOECHSTENS_MS;
		for (String p : new HashSet<>(s)) {
			int n = offen(c, p);
			if (n > 0 && !abgelaufen) {
				anstossen(c, p);
				continue;
			}
			if (n > 0) Log.w(TAG, p + ": nach 24 h noch " + n + " Datei(en) offen, wird trotzdem geloescht");
			aufraeumen(c, p);
			s.remove(p);
		}
		if (s.isEmpty()) {
			prefs(c).edit().clear().commit();
			fertigMelden(c);
			Log.i(TAG, "Nachlauf fertig, alles hochgeladen");
			hand.post(() -> {
				stopForeground(true);
				stopSelf();
			});
		} else {
			prefs(c).edit().putStringSet(APPS, s).commit();
			hand.postDelayed(takt, TAKT_MS);
		}
	}

	/** Wie beim Abmelden: ausblenden, Daten loeschen. */
	static void aufraeumen(Context c, String paket) {
		if (!Sperre.istDeviceOwner(c)) return;
		DevicePolicyManager d = Sperre.dpm(c);
		ComponentName a = Sperre.admin(c);
		try {
			d.setApplicationRestrictions(a, paket, new Bundle());
			d.setApplicationHidden(a, paket, true);
			if (Build.VERSION.SDK_INT >= 28) {
				d.clearApplicationUserData(a, paket, Executors.newSingleThreadExecutor(),
						(p, ok) -> Log.i(TAG, "Nachlauf: Daten geloescht " + p + ": " + ok));
			}
		} catch (Exception e) {
			Log.w(TAG, "aufraeumen " + paket + ": " + e);
		}
	}

	@Override
	public void onDestroy() {
		hand.removeCallbacks(takt);
		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}
