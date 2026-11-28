package org.sipdroid.sipua.rosenweg;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;

/**
 * Regelmaessige Pruefungen, unabhaengig von Activity und AnmeldeDienst, angemeldet und abgemeldet
 * (28.11.2026, XCover: nach dem Launcher-Update war die Samsung-Startseite HOME, weil die einzige
 * Pruefung im AnmeldeDienst hing und der Broadcast PACKAGE_REPLACED an das Manifest-Empfaengerpaar
 * abgelehnt wurde: "Background execution not allowed").
 *
 * - jede Minute: HOME pruefen (angemeldet Rosenweg Launcher, abgemeldet Sperrbildschirm),
 *   Katalog (alle 15 Minuten, ETag), Protokoll an die Anlage.
 * - ein Wecker alle 5 Minuten startet die Schleife neu, falls der Prozess beendet wurde.
 * - PACKAGE_REPLACED/ADDED fuer die eigenen Pakete wird zur Laufzeit empfangen (nur im
 *   Vordergrund-Prozess zugestellt; im Manifest gilt das nicht fuer implizite Broadcasts).
 */
public class Wache extends BroadcastReceiver {
	private static final String TAG = "RosenwegWache";
	private static final String AKTION = "ch.rosenweg4303.telefon.WACHE";
	private static final long TAKT_MS = 60_000L;
	private static final long WECKER_MS = 5 * 60_000L;

	private static HandlerThread thread;
	private static Handler handler;
	private static BroadcastReceiver paketEmpfaenger;

	@Override
	public void onReceive(Context c, Intent i) {
		final Context app = c.getApplicationContext();
		starten(app);
		final PendingResult p = goAsync();
		new Thread(() -> {
			try {
				tick(app);
			} finally {
				p.finish();
			}
		}, "WacheWecker").start();
	}

	/** Idempotent: Schleife, Paket-Empfaenger und Wecker einrichten. */
	public static synchronized void starten(Context c) {
		final Context app = c.getApplicationContext();
		if (Build.VERSION.SDK_INT >= 24 && !app.getSystemService(UserManager.class).isUserUnlocked()) return;
		if (thread == null) {
			thread = new HandlerThread("RosenwegWache");
			thread.start();
			handler = new Handler(thread.getLooper());
			handler.postDelayed(new Runnable() {
				@Override
				public void run() {
					tick(app);
					handler.postDelayed(this, TAKT_MS);
				}
			}, 15_000);
			Log.i(TAG, "Wache gestartet");
		}
		if (paketEmpfaenger == null) {
			paketEmpfaenger = new BroadcastReceiver() {
				@Override
				public void onReceive(Context ctx, Intent in) {
					paketGeaendert(ctx.getApplicationContext(), in);
				}
			};
			IntentFilter f = new IntentFilter();
			f.addAction(Intent.ACTION_PACKAGE_REPLACED);
			f.addAction(Intent.ACTION_PACKAGE_ADDED);
			f.addDataScheme("package");
			if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(paketEmpfaenger, f, Context.RECEIVER_NOT_EXPORTED);
			else app.registerReceiver(paketEmpfaenger, f);
		}
		wecker(app);
	}

	private static void wecker(Context c) {
		try {
			AlarmManager am = c.getSystemService(AlarmManager.class);
			Intent i = new Intent(c, Wache.class).setAction(AKTION);
			PendingIntent pi = PendingIntent.getBroadcast(c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
			am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + WECKER_MS, pi);
		} catch (Exception e) {
			Log.w(TAG, "Wecker: " + e);
		}
	}

	private static final Object TICK = new Object();

	static void tick(Context c) {
		synchronized (TICK) {
			try {
				if (!Sperre.istDeviceOwner(c)) return;
				try {
					Sperre.homePruefen(c, false);
				} catch (Exception e) {
					Log.w(TAG, "HOME pruefen: " + e);
				}
				Katalog.vielleichtAbgleichen(c, false);
				try {
					Protokoll.sammeln(c);
					Protokoll.senden(c);
				} catch (Exception e) {
					Log.w(TAG, "Protokoll: " + e);
				}
			} finally {
				wecker(c);
			}
		}
	}

	/** Eines unserer Pakete wurde installiert/ersetzt: HOME und Festlegungen sofort nachziehen. */
	private static void paketGeaendert(Context c, Intent in) {
		if (in.getData() == null) return;
		final String paket = in.getData().getSchemeSpecificPart();
		if (paket == null || !paket.startsWith("ch.rosenweg4303.")) return;
		Log.i(TAG, (Intent.ACTION_PACKAGE_REPLACED.equals(in.getAction()) ? "Paket ersetzt: " : "Paket installiert: ") + paket);
		new Thread(() -> {
			try {
				if (Sperre.LAUNCHER_PAKET.equals(paket)) Sperre.homePruefen(c, true);
				else if (Sperre.TELEFON_APP.equals(paket) && Sperre.istDeviceOwner(c)) Sperre.telefonAppNeuFestlegen(c);
				Katalog.istMelden(c, true);
			} catch (Exception e) {
				Log.w(TAG, "Paket " + paket + ": " + e);
			}
		}, "WachePaket").start();
	}
}
