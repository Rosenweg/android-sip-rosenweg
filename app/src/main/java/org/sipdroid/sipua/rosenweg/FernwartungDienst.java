package org.sipdroid.sipua.rosenweg;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import ch.rosenweg4303.rwtunnel.RwFernwartung;

/**
 * Haelt den Fernwartungs-Tunnel offen (Vordergrunddienst, Typ specialUse).
 *
 * - Stille Benachrichtigung "Fernwartung bereit" (dauerhaft, Stefan: kein Geheimnis).
 * - Waehrend einer adb-Sitzung: Band oben ueber allem und Benachrichtigung
 *   "Fernwartung aktiv - Technik (Name)". Ausloeser ist die Verbindung im eigenen Netzstapel
 *   (rwtunnel-Ereignis "auf"), nicht die Meldung der Zentrale - die liefert nur den Namen.
 * - Netzwechsel (anderes VLAN beim An-/Abmelden, Zugangspunkt): sofort neu binden + Handshake.
 * - Ruhezustand: Keepalive 25 s im Tunnel; dazu alle 10 Min. ein Wecker, der anstoesst (Doze
 *   laesst Go-Timer schlafen). Wecken bei Bedarf ueber den Weckanruf 2098 (Status -> anstossen).
 * - Endpunkt: zuerst der interne (CT im Haus), ohne Handshake nach 2 Min. der oeffentliche und
 *   umgekehrt - so geht es im Haus ohne DNS-Eintrag und ausserhalb ueber adb.rosenweg4303.ch.
 */
public class FernwartungDienst extends Service {
	private static final String TAG = Fernwartung.TAG;
	static final String START = "ch.rosenweg4303.telefon.FERNWARTUNG_START";
	static final String NEU = "ch.rosenweg4303.telefon.FERNWARTUNG_NEU";
	static final String ANSTOSSEN = "ch.rosenweg4303.telefon.FERNWARTUNG_ANSTOSSEN";
	private static final String KANAL_BEREIT = "rosenweg_fernwartung";
	private static final String KANAL_AKTIV = "rosenweg_fernwartung_aktiv";
	private static final int NR = 4320;
	private static final long WECKER_MS = 10 * 60_000L;
	private static final long WECHSEL_S = 120;
	private static final long NACHLAUF_MS = 10_000L;

	private static volatile boolean laeuft;
	private static volatile FernwartungDienst dienst;

	private final Handler haupt = new Handler(Looper.getMainLooper());
	private Thread ereignisse;
	private volatile boolean aktiv;
	private boolean intern = true;
	private long gestartet;
	private ConnectivityManager.NetworkCallback netz;
	private PowerManager.WakeLock wach;
	private String person = "";
	private String zweck = "";
	private long seit;
	private boolean bandSichtbar;

	static boolean laeuft() { return laeuft; }

	/** Weckanruf 2098 / Status: sofort einen Handshake ausloesen. */
	static void anstossen() {
		if (laeuft && RwFernwartung.verfuegbar()) RwFernwartung.anstossen();
	}

	@Override
	public IBinder onBind(Intent i) { return null; }

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		benachrichtigen(false);
		String a = intent == null ? START : String.valueOf(intent.getAction());
		if (!Fernwartung.adbDauerhaft(this) || !RwFernwartung.verfuegbar()) {
			Log.i(TAG, "Tunnel nicht gestartet: " + (RwFernwartung.verfuegbar() ? "nicht bereit" : RwFernwartung.ladeFehler()));
			stopSelf();
			return START_NOT_STICKY;
		}
		if (ANSTOSSEN.equals(a) && laeuft) {
			RwFernwartung.anstossen();
			weckerStellen();
			return START_STICKY;
		}
		if (!laeuft || NEU.equals(a)) tunnelStarten();
		return START_STICKY;
	}

	private void tunnelStarten() {
		new Thread(() -> {
			synchronized (FernwartungDienst.class) {
				try {
					intern = Fernwartung.hatIntern(this);
					String r = RwFernwartung.start(Fernwartung.tunnelKonfig(this, intern).toString());
					if (new JSONObject(r).has("fehler") && intern) {
						intern = false;   // interner Endpunkt nicht aufloesbar: oeffentlich versuchen
						r = RwFernwartung.start(Fernwartung.tunnelKonfig(this, false).toString());
					}
					JSONObject o = new JSONObject(r);
					if (o.has("fehler")) {
						Log.w(TAG, "Tunnel: " + o.getString("fehler"));
						haupt.postDelayed(this::tunnelStarten, 60_000);
						return;
					}
					gestartet = SystemClock.elapsedRealtime();
					laeuft = true;
					dienst = this;
					Log.i(TAG, "Tunnel offen zu " + Fernwartung.endpunkt(this, intern));
				} catch (Exception e) {
					Log.w(TAG, "Tunnel starten: " + e);
					return;
				}
			}
			haupt.post(() -> {
				netzBeobachten();
				weckerStellen();
				ereignisseLesen();
			});
		}, "FernwartungStart").start();
	}

	private void ereignisseLesen() {
		if (ereignisse != null) return;
		aktiv = true;
		ereignisse = new Thread(() -> {
			while (aktiv) {
				try {
					JSONObject e = new JSONObject(RwFernwartung.warte(30_000));
					String art = e.optString("art", "");
					if (e.has("fehler") || "stop".equals(art)) {
						if (!aktiv) break;
						Thread.sleep(2000);
						continue;
					}
					if (art.isEmpty()) {
						endpunktPruefen();
						continue;
					}
					JSONObject anz = e.optJSONObject("anzeige");
					if (anz != null) {
						person = anz.optString("person", "");
						zweck = anz.optString("zweck", "");
					}
					int offen = e.optInt("offen", 0);
					haupt.post(() -> sitzungAnzeigen(offen, art));
				} catch (InterruptedException ie) {
					break;
				} catch (Exception ex) {
					Log.w(TAG, "Ereignis: " + ex.getClass().getSimpleName());
				}
			}
		}, "FernwartungEreignisse");
		ereignisse.start();
	}

	/** Ohne Handshake seit 2 Min.: anderen Endpunkt versuchen (intern <-> oeffentlich). */
	private void endpunktPruefen() {
		try {
			JSONObject z = new JSONObject(RwFernwartung.zustand());
			long hs = z.optLong("letzter_handshake", 0);
			long jetzt = System.currentTimeMillis() / 1000;
			boolean still = hs == 0 ? SystemClock.elapsedRealtime() - gestartet > WECHSEL_S * 1000 : jetzt - hs > 180 + WECHSEL_S;
			if (!still || !Fernwartung.hatIntern(this)) return;
			String ep = Fernwartung.endpunkt(this, !intern);
			if (ep.isEmpty()) return;
			JSONObject r = new JSONObject(RwFernwartung.endpunkt(ep));
			if (!r.has("fehler")) {
				intern = !intern;
				gestartet = SystemClock.elapsedRealtime();
				Log.i(TAG, "kein Handshake, jetzt ueber " + ep);
			}
		} catch (Exception ignored) { }
	}

	private final Runnable bandAus = () -> {
		bandSichtbar = false;
		FernwartungBand.verbergen(this);
		benachrichtigen(false);
		if (wach != null && wach.isHeld()) wach.release();
		Log.i(TAG, "Sitzung zu Ende");
	};

	private void sitzungAnzeigen(int offen, String art) {
		if (offen > 0 || "anzeige".equals(art)) {
			haupt.removeCallbacks(bandAus);
			if (!bandSichtbar) {
				seit = System.currentTimeMillis();
				Log.i(TAG, "Sitzung: " + (person.isEmpty() ? "unbekannt" : person) + " (" + zweck + ")");
			}
			bandSichtbar = true;
			FernwartungBand.zeigen(this, bandText());
			benachrichtigen(true);
			if (offen > 0) wachHalten();
			if ("auf".equals(art) && Fernwartung.rueckfrageNoetig(this)) {
				Log.i(TAG, "Schluessel der Zentrale noch nicht zugelassen: Rueckfrage 90 s sichtbar");
				AdbSelbst.rueckfrageErmoeglichen(this, 90_000);
			}
		} else if (bandSichtbar) {
			haupt.removeCallbacks(bandAus);
			haupt.postDelayed(bandAus, NACHLAUF_MS);
		}
	}

	/** "Fernwartung aktiv – Technik (Stefan) seit 14:02" */
	String bandText() {
		String wer;
		if ("updater".equals(zweck) || "App-Updater".equals(person)) wer = "App-Updater";
		else if (person.isEmpty()) wer = "Technik (unbekannt)";
		else wer = "Technik (" + Character.toUpperCase(person.charAt(0)) + person.substring(1) + ")";
		return "🛠 Fernwartung aktiv – " + wer + " seit "
				+ new SimpleDateFormat("HH:mm", Locale.GERMAN).format(new Date(seit == 0 ? System.currentTimeMillis() : seit));
	}

	/** Waehrend einer Sitzung CPU wach halten, sonst stockt adb im Ruhezustand. */
	private void wachHalten() {
		try {
			if (wach == null) {
				wach = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Rosenweg:Fernwartung");
				wach.setReferenceCounted(false);
			}
			wach.acquire(4 * 3600_000L);
		} catch (Exception e) {
			Log.w(TAG, "wach: " + e.getClass().getSimpleName());
		}
	}

	private void netzBeobachten() {
		if (netz != null || Build.VERSION.SDK_INT < 24) return;
		ConnectivityManager cm = getSystemService(ConnectivityManager.class);
		netz = new ConnectivityManager.NetworkCallback() {
			String alt = "";
			@Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
				String jetzt = String.valueOf(lp.getLinkAddresses());
				if (jetzt.equals(alt)) return;
				alt = jetzt;
				new Thread(() -> {
					if (laeuft) RwFernwartung.netzWechsel();
				}, "FernwartungNetz").start();
			}
		};
		try {
			cm.registerDefaultNetworkCallback(netz);
		} catch (Exception e) {
			Log.w(TAG, "Netz beobachten: " + e.getClass().getSimpleName());
			netz = null;
		}
	}

	private PendingIntent weckerIntent() {
		Intent i = new Intent(this, FernwartungDienst.class).setAction(ANSTOSSEN);
		int f = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
		return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(this, 4321, i, f)
				: PendingIntent.getService(this, 4321, i, f);
	}

	private void weckerStellen() {
		try {
			AlarmManager am = getSystemService(AlarmManager.class);
			long wann = SystemClock.elapsedRealtime() + WECKER_MS;
			if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
				am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, wann, weckerIntent());
			} else {
				am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, wann, weckerIntent());
			}
		} catch (Exception e) {
			Log.w(TAG, "Wecker: " + e.getClass().getSimpleName());
		}
	}

	private void benachrichtigen(boolean sitzung) {
		NotificationManager nm = getSystemService(NotificationManager.class);
		if (Build.VERSION.SDK_INT >= 26 && nm != null) {
			NotificationChannel b = new NotificationChannel(KANAL_BEREIT, "Fernwartung bereit", NotificationManager.IMPORTANCE_MIN);
			b.setShowBadge(false);
			b.setSound(null, null);
			nm.createNotificationChannel(b);
			NotificationChannel a = new NotificationChannel(KANAL_AKTIV, "Fernwartung aktiv", NotificationManager.IMPORTANCE_HIGH);
			a.setShowBadge(false);
			a.setSound(null, null);
			a.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
			nm.createNotificationChannel(a);
		}
		String kanal = sitzung ? KANAL_AKTIV : KANAL_BEREIT;
		Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, kanal) : new Notification.Builder(this);
		b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
				.setContentTitle(sitzung ? bandText().substring(3) : "Fernwartung bereit")
				.setContentText(sitzung ? "Technik ist über die Fernwartung verbunden (adb)."
						: "Technik kann dieses Telefon aus der Ferne warten. Jede Sitzung wird angezeigt.")
				.setOngoing(true)
				.setOnlyAlertOnce(true)
				.setVisibility(Notification.VISIBILITY_PUBLIC)
				.setShowWhen(sitzung);
		if (sitzung) b.setWhen(seit == 0 ? System.currentTimeMillis() : seit);
		Notification n = b.build();
		try {
			if (Build.VERSION.SDK_INT >= 34) startForeground(NR, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
			else startForeground(NR, n);
		} catch (Exception e) {
			Log.w(TAG, "Vordergrund: " + e.getClass().getSimpleName());
		}
	}

	@Override
	public void onDestroy() {
		aktiv = false;
		laeuft = false;
		dienst = null;
		haupt.removeCallbacksAndMessages(null);
		try { getSystemService(AlarmManager.class).cancel(weckerIntent()); } catch (Exception ignored) { }
		if (netz != null) {
			try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netz); } catch (Exception ignored) { }
			netz = null;
		}
		if (RwFernwartung.verfuegbar()) new Thread(RwFernwartung::stop, "FernwartungStop").start();   // weckt auch warte()
		if (ereignisse != null) ereignisse.interrupt();
		FernwartungBand.verbergen(this);
		if (wach != null && wach.isHeld()) wach.release();
		super.onDestroy();
	}
}
