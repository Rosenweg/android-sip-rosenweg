package org.sipdroid.sipua.rosenweg;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

/**
 * Laeuft, solange jemand am geteilten Telefon angemeldet ist.
 *
 * - Dauerhafte Benachrichtigung "Angemeldet: Name" mit Knopf "Abmelden".
 * - Fragt alle 30 s bei der Anlage nach. Ist die Sitzung dort weg (Abmelden im
 *   Browser oder im Admin), sperrt das Geraet wieder.
 *
 * Abgemeldet wird nur von Hand. Bildschirm sperren/entsperren aendert nichts;
 * dafuer gilt der normale Android-Sperrbildschirm.
 */
public class AnmeldeDienst extends Service {
	private static final String TAG = "RosenwegAnmeldung";
	private static final String KANAL = "rosenweg_anmeldung";
	private static final int NR = 4303;
	private static final long ABFRAGE_MS = 30000;
	static final String AKTION_ABMELDEN = "ch.rosenweg4303.telefon.ABMELDEN";

	private final Handler handler = new Handler(Looper.getMainLooper());
	private boolean aktiv;
	private int fehlerInFolge;

	@Override
	public IBinder onBind(Intent i) {
		return null;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		zeigeBenachrichtigung();
		if (intent != null && AKTION_ABMELDEN.equals(intent.getAction())) {
			new Thread(this::abmelden).start();
			return START_STICKY;
		}
		if (!aktiv) {
			aktiv = true;
			handler.postDelayed(takt, ABFRAGE_MS);
		}
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		aktiv = false;
		handler.removeCallbacks(takt);
		super.onDestroy();
	}

	private final Runnable takt = new Runnable() {
		@Override
		public void run() {
			if (!aktiv) return;
			new Thread(AnmeldeDienst.this::pruefen).start();
			handler.postDelayed(this, ABFRAGE_MS);
		}
	};

	private void pruefen() {
		try {
			JSONObject s = Anlage.status(this);
			fehlerInFolge = 0;
			if (!s.optBoolean("angemeldet", false)) {
				// Im Browser oder Admin abgemeldet: echtes Abmelden -> aufraeumen.
				Aufraeumen.gemeinsamenSpeicherLeeren(this);
				handler.post(() -> Sperre.sperren(this));
			} else {
				// Freigaben koennen sich im Admin geaendert haben: neu anwenden.
				org.json.JSONArray apps = s.optJSONArray("apps");
				if (apps != null && !apps.toString().equals(Katalog.freigegeben(this).toString())) {
					handler.post(() -> Katalog.freigeben(this, apps));
				}
				handler.post(this::zeigeBenachrichtigung);  // Name koennte gewechselt haben
				Katalog.vielleichtAbgleichen(this, false);
			}
		} catch (Anlage.AnlageFehler e) {
			// 401: Geraet nicht mehr als geteilt gefuehrt -> sperren und aufraeumen.
			if (e.status == 401) {
				Aufraeumen.gemeinsamenSpeicherLeeren(this);
				handler.post(() -> Sperre.sperren(this));
			}
		} catch (Exception e) {
			// Netz weg: angemeldet bleiben (nur manuelle Abmeldung). Nur loggen.
			fehlerInFolge++;
			Log.w(TAG, "Status nicht erreichbar (" + fehlerInFolge + "x): " + e);
		}
	}

	private void abmelden() {
		try {
			Anlage.abmelden(this);
		} catch (Exception e) {
			Log.w(TAG, "Abmelden an der Anlage fehlgeschlagen: " + e);
			// Trotzdem lokal sperren: wer abmelden drueckt, will das Geraet zu haben.
		}
		Aufraeumen.gemeinsamenSpeicherLeeren(this);
		// Abmelden per Knopf: kennt die Anlage die Sitzung danach noch, meldet der Sperrbildschirm nochmals ab
		Sperre.abmeldungVormerken(this, true);
		handler.post(() -> Sperre.sperren(this));
	}

	private void zeigeBenachrichtigung() {
		NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (Build.VERSION.SDK_INT >= 26 && nm != null) {
			NotificationChannel k = new NotificationChannel(KANAL, "Anmeldung", NotificationManager.IMPORTANCE_LOW);
			k.setShowBadge(false);
			nm.createNotificationChannel(k);
		}
		Intent ab = new Intent(this, AnmeldeDienst.class).setAction(AKTION_ABMELDEN);
		int pf = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
		PendingIntent abPi = PendingIntent.getService(this, 1, ab, pf);

		String name = Anlage.name(this);
		Notification.Builder b = Build.VERSION.SDK_INT >= 26
				? new Notification.Builder(this, KANAL) : new Notification.Builder(this);
		b.setSmallIcon(android.R.drawable.stat_sys_phone_call)
				.setContentTitle("Angemeldet: " + (name.isEmpty() ? "…" : name))
				.setContentText((Anlage.nummer(this).isEmpty() ? "" : "Tel. " + Anlage.nummer(this) + " · ")
						+ "Gerät " + Anlage.nebenstelle(this))
				.setOngoing(true)
				.setShowWhen(false)
				.addAction(new Notification.Action.Builder(null, "Abmelden", abPi).build());
		Notification n = b.build();
		if (Build.VERSION.SDK_INT >= 34) {
			startForeground(NR, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
		} else {
			startForeground(NR, n);
		}
	}
}
