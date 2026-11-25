package org.sipdroid.sipua.rosenweg;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * C (Stefan 25.11.2026, "unbedingt"): Rosenweg Geraet spricht selbst adb mit dem eigenen Telefon
 * (libadb-android), damit nach einem Neustart niemand USB einstecken muss.
 *
 * Wartung laeuft ueber klassisches adb auf Port 5555 (WlanDebug). Der Port ist nach jedem
 * Neustart weg, und Seriengeraete lassen ihn nicht dauerhaft setzen (persist.adb.tcp.port
 * verweigert). Deshalb nach dem Start:
 *   1. kurz "Debugging ueber WLAN" ein (Device Owner; im WLAN "Rosenweg" bestaetigt die
 *      Bedienungshilfe die Netz-Rueckfrage, in anderen WLANs ein Mensch - Stefan so gewollt),
 *   2. dessen TLS-Port per mDNS bei sich selbst finden,
 *   3. mit dem eigenen Schluessel verbinden und "tcpip:5555" schicken,
 *   4. "Debugging ueber WLAN" wieder aus; adb selbst wie vorher (zentral aus = aus, der Port
 *      gilt dann beim naechsten Einschalten).
 *
 * Der eigene Schluessel muss dafuer EINMAL freigegeben sein ("USB-Debugging zulassen", immer):
 * adbd prueft beim TLS-Debugging gegen dieselben vertrauten Schluessel (AOSP
 * daemon/auth.cpp adbd_tls_verify_cert -> adbd_auth_get_public_keys), eine Kopplung mit Code
 * braucht es nicht. Freigegeben wird, solange Port 5555 offen ist (freigeben()); die
 * Rueckfrage bestaetigt die Bedienungshilfe nur, wenn der Fingerabdruck dieser Schluessel ist.
 *
 * Der Schluessel liegt nur in noBackupFilesDir dieser App (Device Owner, nicht gesichert).
 */
public class AdbSelbst {
	private static final String TAG = "RosenwegAdbSelbst";
	private static final String NAME = "rosenweg-geraet";

	static SharedPreferences prefs(Context c) {
		return c.getSharedPreferences("rosenweg_adb_selbst", Context.MODE_PRIVATE);
	}

	// ─── Schluessel ───────────────────────────────────────────────────────────

	private static final class Verbindung extends AbsAdbConnectionManager {
		private final PrivateKey schluessel;
		private final Certificate zertifikat;

		Verbindung(PrivateKey p, Certificate z) {
			schluessel = p;
			zertifikat = z;
			setApi(Build.VERSION.SDK_INT);
			setTimeout(20, TimeUnit.SECONDS);
		}

		@Override protected PrivateKey getPrivateKey() { return schluessel; }
		@Override protected Certificate getCertificate() { return zertifikat; }
		@Override protected String getDeviceName() { return NAME; }
	}

	private static Verbindung verbindung(Context c) throws Exception {
		File dir = c.getNoBackupFilesDir();
		File fk = new File(dir, "adb_schluessel.pk8"), fz = new File(dir, "adb_zertifikat.der");
		if (fk.exists() && fz.exists()) {
			PrivateKey p = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(lesen(fk)));
			Certificate z;
			try (InputStream in = new FileInputStream(fz)) {
				z = CertificateFactory.getInstance("X.509").generateCertificate(in);
			}
			return new Verbindung(p, z);
		}
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		KeyPair kp = g.generateKeyPair();
		Date von = new Date(), bis = new Date(System.currentTimeMillis() + 3650L * 86400000L);
		X500Name name = new X500Name("CN=Rosenweg Geraet");
		X509v3CertificateBuilder bau = new JcaX509v3CertificateBuilder(name,
				new BigInteger(63, new java.security.SecureRandom()), von, bis, name, kp.getPublic());
		Certificate z = new JcaX509CertificateConverter().getCertificate(
				bau.build(new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate())));
		schreiben(fk, kp.getPrivate().getEncoded());
		schreiben(fz, z.getEncoded());
		Log.i(TAG, "eigener adb-Schluessel erzeugt, Fingerabdruck " + fingerabdruck((RSAPublicKey) kp.getPublic()));
		return new Verbindung(kp.getPrivate(), z);
	}

	/** Wie Android ihn in "USB-Debugging zulassen?" zeigt: MD5 des adb-Schluessels, XX:XX:... */
	static String fingerabdruck(RSAPublicKey k) throws Exception {
		byte[] md5 = MessageDigest.getInstance("MD5").digest(adbSchluessel(k));
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < md5.length; i++) {
			if (i > 0) sb.append(':');
			sb.append(String.format("%02X", md5[i] & 0xff));
		}
		return sb.toString();
	}

	/** Fingerabdruck des eigenen Schluessels (fuer die Bedienungshilfe), "" wenn keiner da ist. */
	static String eigenerFingerabdruck(Context c) {
		return prefs(c).getString("fingerabdruck", "");
	}

	/**
	 * Oeffentlicher Schluessel im adb-Format (AOSP libcrypto_utils android_pubkey_encode):
	 * u32 Laenge in Worten, u32 n0inv, Modulus (LE), R^2 mod n (LE), u32 Exponent.
	 */
	static byte[] adbSchluessel(RSAPublicKey k) {
		final int bytes = 256, worte = bytes / 4;
		BigInteger n = k.getModulus();
		BigInteger r32 = BigInteger.ONE.shiftLeft(32);
		BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
		BigInteger rr = BigInteger.ONE.shiftLeft(bytes * 8).modPow(BigInteger.valueOf(2), n);
		java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(4 + 4 + bytes + bytes + 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		b.putInt(worte);
		b.putInt(n0inv.intValue());
		b.put(le(n, bytes));
		b.put(le(rr, bytes));
		b.putInt(k.getPublicExponent().intValue());
		return b.array();
	}

	private static byte[] le(BigInteger v, int len) {
		byte[] be = v.toByteArray(), out = new byte[len];
		for (int i = 0; i < len && i < be.length; i++) out[i] = be[be.length - 1 - i];
		return out;
	}

	private static byte[] lesen(File f) throws Exception {
		try (InputStream in = new FileInputStream(f)) {
			byte[] b = new byte[(int) f.length()];
			int n = 0;
			while (n < b.length) {
				int r = in.read(b, n, b.length - n);
				if (r < 0) break;
				n += r;
			}
			return b;
		}
	}

	private static void schreiben(File f, byte[] b) throws Exception {
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(b);
		}
	}

	// ─── Kiosk: Rueckfragen der SystemUI kurz zulassen ────────────────────────

	private static String[] kioskVorher;

	/**
	 * Im Kiosk (Lock Task) blockiert Android die Rueckfragen "USB-Debugging zulassen?" und
	 * "Debugging ueber WLAN zulassen?" - sie sind Activities der SystemUI. Fuer die Dauer des
	 * Vorgangs die SystemUI zulassen, danach genau die alte Liste wiederherstellen.
	 */
	private static synchronized void systemUiZulassen(Context c, boolean an) {
		try {
			android.app.admin.DevicePolicyManager d = Sperre.dpm(c);
			if (an) {
				String[] alt = d.getLockTaskPackages(Sperre.admin(c));
				if (alt.length == 0) return;   // kein Kiosk aktiv
				for (String p : alt) if ("com.android.systemui".equals(p)) return;
				kioskVorher = alt;
				String[] neu = java.util.Arrays.copyOf(alt, alt.length + 1);
				neu[alt.length] = "com.android.systemui";
				d.setLockTaskPackages(Sperre.admin(c), neu);
			} else if (kioskVorher != null) {
				d.setLockTaskPackages(Sperre.admin(c), kioskVorher);
				kioskVorher = null;
			}
		} catch (Exception e) {
			Log.w(TAG, "Kiosk: " + e.getClass().getSimpleName());
		}
	}

	// ─── Freigabe (einmal, solange Port 5555 offen ist) ───────────────────────

	/** Die Bedienungshilfe soll die Rueckfrage "USB-Debugging zulassen?" bestaetigen (2 Min.). */
	static boolean erwartetFreigabe(Context c) {
		return System.currentTimeMillis() - prefs(c).getLong("freigabe_um", 0) < 120_000;
	}

	static boolean freigegeben(Context c) {
		return prefs(c).getBoolean("freigegeben", false);
	}

	/**
	 * Eigenen Schluessel freigeben: ueber Port 5555 verbinden, Android fragt einmal "USB-Debugging
	 * zulassen?", die Bedienungshilfe kreuzt "immer" an. Blockierend, nicht im UI-Thread.
	 * Gibt null zurueck, wenn es geklappt hat, sonst den Grund.
	 */
	public static synchronized String freigeben(Context c) {
		if (!WlanDebug.tcpOffen()) return "Port 5555 ist nicht offen (adb tcpip 5555 bzw. zentral einschalten)";
		Verbindung v = null;
		systemUiZulassen(c, true);
		try {
			v = verbindung(c);
			// Fingerabdruck fuer die Bedienungshilfe: sie bestaetigt nur genau diesen Schluessel
			prefs(c).edit().putString("fingerabdruck", fingerabdruck((RSAPublicKey) v.getCertificate().getPublicKey()))
					.putLong("freigabe_um", System.currentTimeMillis()).apply();
			v.setTimeout(90, TimeUnit.SECONDS);   // Zeit fuer die Rueckfrage
			if (!v.connect("127.0.0.1", WlanDebug.TCP_PORT)) return "keine Verbindung zu adbd";
			String aus = befehl(v, "shell:echo rosenweg-ok");
			if (!aus.contains("rosenweg-ok")) return "adb antwortet nicht wie erwartet";
			prefs(c).edit().putBoolean("freigegeben", true).remove("freigabe_um").apply();
			Log.i(TAG, "eigener Schluessel freigegeben");
			return null;
		} catch (Exception e) {
			Log.w(TAG, "freigeben: " + e);
			return e.getClass().getSimpleName() + ": " + e.getMessage();
		} finally {
			prefs(c).edit().remove("freigabe_um").apply();
			try { if (v != null) v.disconnect(); } catch (Exception ignored) { }
			systemUiZulassen(c, false);
		}
	}

	// ─── Einstellungen, die nur adb setzen darf, selbst pruefen ───────────────

	/**
	 * Seitentaste lang = Ausschaltmenue (Stefan 25.11.2026: Samsung stellte power_button_long_press
	 * still auf 101 zurueck, dann passierte beim langen Druecken nichts, auch kein Neustart).
	 * Global-Einstellungen, die der Device Owner nicht setzen darf: ueber den eigenen adb-Zugang.
	 * Nur wenn etwas abweicht; blockierend, nicht im UI-Thread.
	 */
	public static synchronized void einstellungenPruefen(Context c) {
		android.content.ContentResolver r = c.getContentResolver();
		StringBuilder cmd = new StringBuilder();
		if (Settings.Global.getInt(r, "power_button_long_press", 1) != 1) cmd.append("settings put global power_button_long_press 1;");
		if (Settings.Global.getInt(r, "function_key_config_longpress_type", 0) != 0) cmd.append("settings put global function_key_config_longpress_type 0;");
		if (cmd.length() == 0 || !freigegeben(c) || !WlanDebug.tcpOffen()) return;
		Verbindung v = null;
		try {
			v = verbindung(c);
			if (!v.connect("127.0.0.1", WlanDebug.TCP_PORT)) return;
			befehl(v, "shell:" + cmd);
			Log.i(TAG, "Seitentaste korrigiert: " + cmd);
		} catch (Exception e) {
			Log.w(TAG, "Einstellungen pruefen: " + e.getClass().getSimpleName());
		} finally {
			try { if (v != null) v.disconnect(); } catch (Exception ignored) { }
		}
	}

	// ─── Nach dem Neustart: Port 5555 selbst wiederherstellen ─────────────────

	/** Nach dem Start im Hintergrund versuchen (mehrmals, bis das WLAN steht). */
	public static void nachStart(Context c) {
		final Context app = c.getApplicationContext();
		new Thread(() -> {
			for (int versuch = 1; versuch <= 6; versuch++) {
				try { Thread.sleep(versuch == 1 ? 30_000 : 60_000); } catch (InterruptedException e) { return; }
				String f = tcpWiederherstellen(app);
				if (f == null) return;
				Log.i(TAG, "Versuch " + versuch + ": " + f);
				if (f.startsWith("nicht moeglich")) return;
			}
		}, "AdbSelbst").start();
	}

	/**
	 * Port 5555 ueber "Debugging ueber WLAN" wiederherstellen. Blockierend. null = Port offen,
	 * sonst der Grund ("nicht moeglich: ..." = weitere Versuche zwecklos).
	 */
	public static synchronized String tcpWiederherstellen(Context c) {
		if (WlanDebug.tcpOffen()) return null;
		if (!Sperre.istDeviceOwner(c)) return "nicht moeglich: kein Device Owner";
		if (!freigegeben(c)) return "nicht moeglich: eigener Schluessel noch nicht freigegeben (Einrichtungsskript)";
		if (WlanDebug.adresse(c).isEmpty()) return "kein WLAN";
		boolean adbVorher = WlanDebug.an(c);
		Verbindung v = null;
		systemUiZulassen(c, true);
		try {
			// eingeschaltet_um sorgt dafuer, dass die Bedienungshilfe die Netz-Rueckfrage
			// (nur im WLAN "Rosenweg") bestaetigt
			WlanDebug.prefs(c).edit().putLong("eingeschaltet_um", System.currentTimeMillis()).apply();
			Sperre.dpm(c).setGlobalSetting(Sperre.admin(c), Settings.Global.ADB_ENABLED, "1");
			Sperre.dpm(c).setGlobalSetting(Sperre.admin(c), "adb_wifi_enabled", "1");
			int port = tlsPort(c, 90);
			if (port <= 0) return "Debugging ueber WLAN kam nicht hoch (Netz-Rueckfrage nicht bestaetigt?)";
			v = verbindung(c);
			if (!v.connect("127.0.0.1", port)) return "keine Verbindung zum TLS-Port " + port;
			String aus = befehl(v, "tcpip:" + WlanDebug.TCP_PORT);
			Log.i(TAG, "tcpip: " + aus.trim());
		} catch (Exception e) {
			Log.w(TAG, "wiederherstellen: " + e);
			return e.getClass().getSimpleName();
		} finally {
			try { if (v != null) v.disconnect(); } catch (Exception ignored) { }
			try {
				Sperre.dpm(c).setGlobalSetting(Sperre.admin(c), "adb_wifi_enabled", "0");
			} catch (Exception ignored) { }
			systemUiZulassen(c, false);
		}
		// adbd startet neu und hoert dann auf 5555
		for (int i = 0; i < 15 && !WlanDebug.tcpOffen(); i++) {
			try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
		}
		boolean offen = WlanDebug.tcpOffen();
		// zentral nicht an: adb wieder wie vorher (der Port gilt beim naechsten Einschalten)
		if (!adbVorher && !WlanDebug.zentralAn(c)) {
			try { Sperre.dpm(c).setGlobalSetting(Sperre.admin(c), Settings.Global.ADB_ENABLED, "0"); } catch (Exception ignored) { }
		}
		if (offen) {
			Log.i(TAG, "Port " + WlanDebug.TCP_PORT + " wiederhergestellt");
			einstellungenPruefen(c);
			if (WlanDebug.zentralAn(c)) WlanDebug.portMelden(c);
			return null;
		}
		return "Port " + WlanDebug.TCP_PORT + " kam nicht";
	}

	private static String befehl(AbsAdbConnectionManager v, String dienst) throws Exception {
		StringBuilder sb = new StringBuilder();
		try (AdbStream s = v.openStream(dienst); InputStream in = s.openInputStream()) {
			byte[] b = new byte[1024];
			int n;
			while ((n = in.read(b)) > 0 && sb.length() < 4096) sb.append(new String(b, 0, n));
			return sb.toString();
		} catch (java.io.IOException e) {
			// libadb meldet das Ende der Ausgabe als "Stream closed" statt -1: was bis dahin kam, gilt.
			// tcpip: adbd startet neu, die Verbindung bricht dabei ab - das ist der Erfolg.
			if (sb.length() > 0) return sb.toString();
			if (dienst.startsWith("tcpip:")) return "(Verbindung beendet, adbd startet neu)";
			throw e;
		}
	}

	/** TLS-Port von "Debugging ueber WLAN" per mDNS bei sich selbst finden; 0 = nicht gefunden. */
	private static int tlsPort(Context c, int sekunden) throws InterruptedException {
		final String eigene = WlanDebug.adresse(c);
		final NsdManager nsd = c.getSystemService(NsdManager.class);
		final AtomicInteger port = new AtomicInteger();
		final CountDownLatch fertig = new CountDownLatch(1);
		final Handler h = new Handler(Looper.getMainLooper());
		final NsdManager.DiscoveryListener[] dl = new NsdManager.DiscoveryListener[1];
		dl[0] = new NsdManager.DiscoveryListener() {
			boolean loest;
			@Override public void onStartDiscoveryFailed(String t, int e) { fertig.countDown(); }
			@Override public void onStopDiscoveryFailed(String t, int e) { }
			@Override public void onDiscoveryStarted(String t) { }
			@Override public void onDiscoveryStopped(String t) { }
			@Override public void onServiceLost(NsdServiceInfo i) { }
			@Override public void onServiceFound(NsdServiceInfo i) {
				if (loest) return;
				loest = true;
				nsd.resolveService(i, new NsdManager.ResolveListener() {
					@Override public void onResolveFailed(NsdServiceInfo s, int e) { loest = false; }
					@Override public void onServiceResolved(NsdServiceInfo s) {
						String host = s.getHost() == null ? "" : s.getHost().getHostAddress();
						if (!eigene.isEmpty() && !host.isEmpty() && !host.equals(eigene) && !host.startsWith("127.")) {
							loest = false;
							return;
						}
						port.set(s.getPort());
						fertig.countDown();
					}
				});
			}
		};
		h.post(() -> {
			try {
				nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, dl[0]);
			} catch (Exception e) {
				fertig.countDown();
			}
		});
		fertig.await(sekunden, TimeUnit.SECONDS);
		h.post(() -> { try { nsd.stopServiceDiscovery(dl[0]); } catch (Exception ignored) { } });
		return port.get();
	}
}
