package org.sipdroid.sipua.rosenweg;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hintergrundbild des Startbildschirms pro Person (Stefan 27.11.2026: "Pro Person
 * synchronisieren"). Der Startbildschirm (FLAG_SYSTEM) gehoert der Person, das Sperrbild
 * (FLAG_LOCK) Rosenweg Geraet (Sperrbild).
 *
 * - Setzt die angemeldete Person einen Hintergrund (Galerie "Als Hintergrund", Einstellungen,
 *   irgendeine App), merkt Geraet das, verkleinert ihn auf Bildschirmgroesse und laedt ihn als
 *   JPEG (hoechstens ~500 KB) auf die Anlage (PUT /api/telefon/hintergrund, Geraetezugang,
 *   Person der Sitzung).
 * - Nach dem Anmelden an irgendeinem geteilten Telefon setzt Geraet das Bild der Person
 *   ("hintergrund" im Status = Fassung, "" = keines -> Standard). Beim Abmelden: Standard
 *   (WallpaperManager.clear(FLAG_SYSTEM) = was ein frisch eingerichtetes Telefon zeigt).
 * - Aendert jemand das Sperrbild (z.B. "Start- und Sperrbildschirm" in der Galerie), setzt
 *   Geraet sofort wieder das Logo (Sperrbild.pruefen).
 *
 * Erkennen (Android 13-15): WallpaperManager.OnColorsChangedListener (ab 8.1, meldet System
 * und Sperrbild mit "which") und ACTION_WALLPAPER_CHANGED (nur zur Laufzeit registriert;
 * im Manifest kommt der Broadcast seit Android 8 nicht an). Beides stoesst nur pruefen() an;
 * entschieden wird an den Wallpaper-IDs (getWallpaperId), siehe HintergrundRegel. Zur
 * Sicherheit prueft jeder Status der Anlage (alle 30 s, solange jemand angemeldet ist) mit.
 *
 * Nur als Device Owner (geteiltes Telefon). Alles laeuft nacheinander in einem Faden und
 * unter Hintergrund.WALLPAPER (auch Sperrbild setzt darunter): die eigene ID ist gemerkt,
 * bevor pruefen() das Ereignis des eigenen Setzens sieht.
 */
public final class Hintergrund {
	private static final String TAG = "RosenwegHintergrund";
	private static final String PREF = "rosenweg_hintergrund";
	/** Gemeinsames Schloss fuer alle Aenderungen am Hintergrund und am Sperrbild. */
	static final Object WALLPAPER = new Object();
	static final int JPEG_MAX = 500 * 1024;
	private static final ExecutorService LAUF = Executors.newSingleThreadExecutor(r -> new Thread(r, "Hintergrund"));
	private static final Handler HAUPT = new Handler(Looper.getMainLooper());
	private static volatile boolean beobachtet;
	private static Context appKontext;

	private Hintergrund() {}

	private static SharedPreferences prefs(Context c) {
		return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
	}

	private static boolean geht(Context c) {
		return Build.VERSION.SDK_INT >= 24 && Sperre.istDeviceOwner(c);
	}

	// ─── Erkennen ────────────────────────────────────────────────────────────

	private static final Runnable PRUEFEN = () -> {
		if (appKontext != null) LAUF.execute(() -> pruefenJetzt(appKontext));
	};

	/** Einmal je Prozess (SipdroidApp, StartReceiver): auf Aenderungen hoeren. */
	public static synchronized void beobachten(Context c) {
		if (beobachtet || !geht(c)) return;
		beobachtet = true;
		appKontext = c.getApplicationContext();
		try {
			WallpaperManager wm = WallpaperManager.getInstance(appKontext);
			if (Build.VERSION.SDK_INT >= 27) {
				wm.addOnColorsChangedListener((farben, welche) -> {
					Log.d(TAG, "Farben geaendert, which=0x" + Integer.toHexString(welche));
					spaeter();
				}, HAUPT);
			}
			IntentFilter f = new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED);
			BroadcastReceiver r = new BroadcastReceiver() {
				@Override
				public void onReceive(Context k, Intent i) {
					spaeter();
				}
			};
			if (Build.VERSION.SDK_INT >= 33) appKontext.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
			else appKontext.registerReceiver(r, f);
		} catch (Exception e) {
			Log.w(TAG, "beobachten: " + e);
		}
		spaeter();   // Stand nach dem Start einmal pruefen (z.B. Sperrbild nach einem Update)
	}

	/** Mehrere Ereignisse kurz hintereinander (Farben + Broadcast, System + Sperrbild) = einmal pruefen. */
	private static void spaeter() {
		HAUPT.removeCallbacks(PRUEFEN);
		HAUPT.postDelayed(PRUEFEN, 1500);
	}

	// ─── Anlage-Status / Abmelden ────────────────────────────────────────────

	/** Aus Anlage.status (Netz-Faden): Bild der Person setzen, wenn sich Sitzung oder Fassung aendert. */
	static void ausStatus(Context c, JSONObject s) {
		if (!geht(c)) return;
		final Context app = c.getApplicationContext();
		beobachten(app);
		boolean angemeldet = s.optBoolean("angemeldet", false);
		// Aeltere Anlage ohne das Feld: nichts aendern (wie bisher)
		if (angemeldet && !s.has("hintergrund")) return;
		final String marke = angemeldet ? s.optString("email", "").toLowerCase(java.util.Locale.ROOT) + "|" + s.optString("seit", "") : "";
		final String version = angemeldet ? s.optString("hintergrund", "") : "";
		LAUF.execute(() -> {
			try {
				prefs(app).edit().putString("aktuell", marke).apply();
				// Zuerst eigene Aenderungen hochladen, die noch nicht gemeldet sind (Status als Rueckfall)
				if (pruefenJetzt(app)) return;   // gerade hochgeladen: dieser Status ist schon alt
				SharedPreferences p = prefs(app);
				if (!HintergrundRegel.statusAnwenden(marke, version, p.getString("sitzung", null),
						p.getString("version", ""), p.getString("vorher", ""), p.getLong("hochgeladen", 0),
						System.currentTimeMillis())) return;
				anwenden(app, marke, version);
			} catch (Exception e) {
				Log.w(TAG, "Status: " + e);
			}
		});
	}

	/** Beim Abmelden (Sperre.sperren): zurueck auf den Standard. */
	static void abgemeldet(Context c) {
		if (!geht(c)) return;
		final Context app = c.getApplicationContext();
		LAUF.execute(() -> {
			prefs(app).edit().putString("aktuell", "").apply();
			SharedPreferences p = prefs(app);
			int ist = WallpaperManager.getInstance(app).getWallpaperId(WallpaperManager.FLAG_SYSTEM);
			if ("".equals(p.getString("sitzung", null)) && ist == p.getInt("system_id", Integer.MIN_VALUE)) return;
			anwenden(app, "", "");
		});
	}

	/** Bild der Sitzung setzen (oder Standard). Im Faden LAUF. */
	private static void anwenden(Context app, String marke, String version) {
		synchronized (WALLPAPER) {
			WallpaperManager wm = WallpaperManager.getInstance(app);
			try {
				byte[] jpeg = marke.isEmpty() || version.isEmpty() ? null : Anlage.hintergrundHolen(app);
				Bitmap b = jpeg == null ? null : BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
				if (b != null) {
					wm.setBitmap(b, null, true, WallpaperManager.FLAG_SYSTEM);
					Log.i(TAG, "Hintergrund der Person gesetzt (" + jpeg.length / 1024 + " KB, " + b.getWidth() + "x" + b.getHeight() + ")");
				} else {
					wm.clear(WallpaperManager.FLAG_SYSTEM);
					Log.i(TAG, "Hintergrund: Standard" + (marke.isEmpty() ? " (abgemeldet)" : " (keiner gespeichert)"));
				}
				merken(app, wm, marke, b != null ? version : "");
			} catch (Anlage.AnlageFehler e) {
				// 409: inzwischen abgemeldet - der naechste Status/abgemeldet() raeumt auf
				Log.w(TAG, "Hintergrund holen: Anlage " + e.status);
				return;
			} catch (Exception e) {
				Log.w(TAG, "Hintergrund setzen: " + e.getClass().getSimpleName());   // naechster Status versucht es wieder
				return;
			}
			// clear()/setBitmap koennen je nach Hersteller das Sperrbild mitnehmen
			Sperrbild.pruefen(app);
		}
	}

	/** Die jetzige System-ID gehoert Geraet (gesetzt oder hochgeladen). Zurueckgelesen, nicht der Rueckgabewert. */
	private static void merken(Context app, WallpaperManager wm, String marke, String version) {
		prefs(app).edit()
				.putInt("system_id", wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM))
				.putString("sitzung", marke)
				.putString("version", version)
				.apply();
	}

	/**
	 * Sperrbild.setzen hat das Sperrbild ersetzt; manche Android-Fassungen vergeben dabei dem
	 * Startbildschirm eine neue ID (geteiltes Bild wird aufgetrennt). War die alte ID die eigene,
	 * ist es weiterhin dasselbe Bild: neue ID uebernehmen statt hochladen.
	 */
	static void systemIdNachSperrbild(Context app, int vorher, int nachher) {
		if (vorher == nachher) return;
		SharedPreferences p = prefs(app);
		if (p.getInt("system_id", Integer.MIN_VALUE) == vorher) {
			p.edit().putInt("system_id", nachher).apply();
			Log.i(TAG, "Startbildschirm-ID nach Sperrbild " + vorher + " -> " + nachher + " (gleiches Bild)");
		}
	}

	// ─── Pruefen und hochladen ───────────────────────────────────────────────

	/** @return true, wenn gerade ein neues Bild hochgeladen wurde. Im Faden LAUF. */
	private static boolean pruefenJetzt(Context app) {
		boolean hoch = false;
		synchronized (WALLPAPER) {
			try {
				WallpaperManager wm = WallpaperManager.getInstance(app);
				SharedPreferences p = prefs(app);
				int ist = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
				int eigen = p.getInt("system_id", Integer.MIN_VALUE);
				String aktuell = Anlage.angemeldet(app) ? p.getString("aktuell", "") : "";
				switch (HintergrundRegel.system(Anlage.angemeldet(app), aktuell, p.getString("sitzung", null), ist, eigen)) {
					case HOCHLADEN:
						hoch = hochladen(app, wm, aktuell, eigen, ist);
						break;
					case STANDARD:
						// Abgemeldet (oder erster Lauf nach dem Update): fremder Hintergrund bleibt nicht stehen
						Log.i(TAG, "Hintergrund ohne Anmeldung geaendert (ID " + eigen + " -> " + ist + "): Standard");
						wm.clear(WallpaperManager.FLAG_SYSTEM);
						merken(app, wm, "", "");
						break;
					default:
						break;
				}
			} catch (Exception e) {
				Log.w(TAG, "pruefen: " + e);
			}
			Sperrbild.pruefen(app);
		}
		return hoch;
	}

	private static boolean hochladen(Context app, WallpaperManager wm, String marke, int eigen, int ist) {
		if (wm.getWallpaperInfo() != null) {
			// Live-Hintergrund (eine App zeichnet ihn): kein Bild zum Mitnehmen
			Log.i(TAG, "Live-Hintergrund " + wm.getWallpaperInfo().getPackageName() + ": nicht synchronisiert");
			merken(app, wm, marke, prefs(app).getString("version", ""));
			return false;
		}
		byte[] jpeg;
		try {
			Bitmap b = lesen(app, wm);
			if (b == null) {
				Log.w(TAG, "Hintergrund nicht lesbar (Zugriff auf alle Dateien / Fotos erlaubt?): nicht synchronisiert");
				merken(app, wm, marke, prefs(app).getString("version", ""));
				return false;
			}
			jpeg = verkleinern(app, b);
		} catch (OutOfMemoryError e) {
			Log.w(TAG, "Hintergrund zu gross zum Lesen");
			merken(app, wm, marke, prefs(app).getString("version", ""));
			return false;
		}
		try {
			JSONObject r = Anlage.hintergrundSenden(app, jpeg);
			SharedPreferences p = prefs(app);
			p.edit().putString("vorher", p.getString("version", ""))
					.putLong("hochgeladen", System.currentTimeMillis()).apply();
			merken(app, wm, marke, r.optString("version", ""));
			Log.i(TAG, "Neuer Hintergrund der Person hochgeladen (ID " + eigen + " -> " + ist + ", " + jpeg.length / 1024 + " KB)");
			return true;
		} catch (Anlage.AnlageFehler e) {
			Log.w(TAG, "Hochladen: Anlage " + e.status + " " + e.getMessage());
			if (e.status == 400 || e.status == 413) merken(app, wm, marke, prefs(app).getString("version", ""));
		} catch (Exception e) {
			Log.w(TAG, "Hochladen: " + e.getClass().getSimpleName() + " (naechster Status versucht es wieder)");
		}
		return false;
	}

	/**
	 * Jetziges Startbildschirm-Bild. getWallpaperFile/getDrawable brauchen ab Android 13
	 * "Fotos" (READ_MEDIA_IMAGES, setzt Sperre als Device Owner) bzw. ab 14 "Zugriff auf alle
	 * Dateien" (MANAGE_EXTERNAL_STORAGE, geteiltes-telefon-einrichten.sh) - sonst liefert
	 * Android stillschweigend das Standardbild oder wirft SecurityException.
	 */
	private static Bitmap lesen(Context app, WallpaperManager wm) {
		// Ab Android 14 liefert getDrawable ohne das Recht stillschweigend das Standardbild
		if (Build.VERSION.SDK_INT >= 34 && !android.os.Environment.isExternalStorageManager()) {
			Log.w(TAG, "Kein \"Zugriff auf alle Dateien\": Hintergrund wird nicht gelesen");
			return null;
		}
		int[] schirm = schirm(app);
		try {
			BitmapFactory.Options o = new BitmapFactory.Options();
			o.inJustDecodeBounds = true;
			try (ParcelFileDescriptor fd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)) {
				if (fd != null) BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(), null, o);
			}
			if (o.outWidth > 0 && o.outHeight > 0) {
				int[] ziel = HintergrundRegel.zielGroesse(o.outWidth, o.outHeight, schirm[0], schirm[1]);
				BitmapFactory.Options l = new BitmapFactory.Options();
				l.inSampleSize = HintergrundRegel.teiler(o.outWidth, o.outHeight, ziel[0], ziel[1]);
				try (ParcelFileDescriptor fd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)) {
					Bitmap b = fd == null ? null : BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(), null, l);
					if (b != null) return b;
				}
			}
		} catch (SecurityException e) {
			Log.w(TAG, "getWallpaperFile: kein Recht");
		} catch (Exception e) {
			Log.w(TAG, "getWallpaperFile: " + e.getClass().getSimpleName());
		}
		try {
			Drawable d = Build.VERSION.SDK_INT >= 34 ? wm.getDrawable(WallpaperManager.FLAG_SYSTEM) : wm.getDrawable();
			if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
			if (d != null && d.getIntrinsicWidth() > 0 && d.getIntrinsicHeight() > 0) {
				Bitmap b = Bitmap.createBitmap(d.getIntrinsicWidth(), d.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
				d.setBounds(0, 0, b.getWidth(), b.getHeight());
				d.draw(new Canvas(b));
				return b;
			}
		} catch (SecurityException e) {
			Log.w(TAG, "getDrawable: kein Recht");
		} catch (Exception e) {
			Log.w(TAG, "getDrawable: " + e.getClass().getSimpleName());
		}
		return null;
	}

	/** Auf Bildschirmgroesse verkleinern und als JPEG <= JPEG_MAX. */
	static byte[] verkleinern(Context app, Bitmap roh) {
		int[] schirm = schirm(app);
		int[] ziel = HintergrundRegel.zielGroesse(roh.getWidth(), roh.getHeight(), schirm[0], schirm[1]);
		Bitmap b = ziel[0] == roh.getWidth() && ziel[1] == roh.getHeight() ? roh
				: Bitmap.createScaledBitmap(roh, ziel[0], ziel[1], true);
		byte[] jpeg = null;
		for (int runde = 0; runde < 4; runde++) {
			for (int q = 85; q >= 50; q -= 10) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				b.compress(Bitmap.CompressFormat.JPEG, q, out);
				jpeg = out.toByteArray();
				if (jpeg.length <= JPEG_MAX) return jpeg;
			}
			b = Bitmap.createScaledBitmap(b, Math.max(1, b.getWidth() * 4 / 5), Math.max(1, b.getHeight() * 4 / 5), true);
		}
		return jpeg;
	}

	private static int[] schirm(Context app) {
		DisplayMetrics dm = new DisplayMetrics();
		try {
			android.hardware.display.DisplayManager d = app.getSystemService(android.hardware.display.DisplayManager.class);
			d.getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealMetrics(dm);
		} catch (Exception e) {
			dm = app.getResources().getDisplayMetrics();
		}
		return new int[]{dm.widthPixels, dm.heightPixels};
	}
}
