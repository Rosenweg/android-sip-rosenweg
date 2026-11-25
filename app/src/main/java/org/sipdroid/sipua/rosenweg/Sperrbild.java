package org.sipdroid.sipua.rosenweg;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Log;

import org.sipdroid.sipua.R;

/**
 * Android-Sperrbildschirm wie der Ladebildschirm der Stationen (Stefan 25.11.2026):
 * blauer Hintergrund, Rosenweg-Logo in der Mitte. Nur das Sperrbild (FLAG_LOCK), der
 * Startbildschirm gehoert dem Rosenweg Launcher. Angemeldet: Logo der Gruppe/des Hauses
 * (Anlage entscheidet), abgemeldet: Rosenweg-Logo.
 */
public final class Sperrbild {
	private static final String TAG = "RosenwegSperrbild";
	private static final String PREF = "rosenweg_sperrbild";
	private static final int VERSION = 4;
	private static volatile String laeuft;
	static final int BLAU = Color.parseColor("#1E3A8A");

	private Sperrbild() {}

	/** Beim Start: Rosenweg-Logo, falls noch nie gesetzt (Fassung VERSION). */
	public static void setzen(Context c) {
		Context app = c.getApplicationContext();
		if (prefs(app).getInt("version", 0) >= VERSION) return;
		fuer(app, "");
	}

	private static android.content.SharedPreferences prefs(Context c) {
		return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
	}

	/**
	 * Sperrbild fuer die angemeldete Person (Logo-Datei von der Anlage, "sperrbild" in
	 * /api/telefon/status: Gruppe vor Haus) oder "" = Rosenweg-Logo (abgemeldet). Laedt das
	 * Logo von www.rosenweg4303.ch und merkt es sich im App-Speicher; ohne Netz bleibt das
	 * Rosenweg-Logo.
	 */
	public static void fuer(Context c, String datei) {
		Context app = c.getApplicationContext();
		String soll = datei != null && datei.matches("logo-[a-z0-9-]{1,60}\\.png") ? datei : "";
		android.content.SharedPreferences p = prefs(app);
		if (soll.equals(p.getString("datei", null)) && p.getInt("version", 0) >= VERSION) return;
		if (soll.equals(laeuft)) return;   // wird gerade gesetzt (Status kommt mehrfach)
		laeuft = soll;
		new Thread(() -> {
			synchronized (Sperrbild.class) {
				try {
					Bitmap logo = soll.isEmpty() || soll.equals("logo-rosenweg.png") ? null : laden(app, soll);
					if (logo == null) logo = BitmapFactory.decodeResource(app.getResources(), R.drawable.rosenweg_logo);
					DisplayMetrics dm = app.getResources().getDisplayMetrics();
					int b = Math.min(dm.widthPixels, dm.heightPixels), h = Math.max(dm.widthPixels, dm.heightPixels);
					Bitmap bild = Bitmap.createBitmap(b, h, Bitmap.Config.ARGB_8888);
					Canvas cv = new Canvas(bild);
					cv.drawColor(BLAU);
					int breite = (int) (b * 0.6f);
					int hoehe = breite * logo.getHeight() / logo.getWidth();
					int x = (b - breite) / 2, y = (h - hoehe) / 2;
					cv.drawBitmap(logo, null, new Rect(x, y, x + breite, y + hoehe), new Paint(Paint.FILTER_BITMAP_FLAG));
					WallpaperManager.getInstance(app).setBitmap(bild, null, true, WallpaperManager.FLAG_LOCK);
					p.edit().putInt("version", VERSION).putString("datei", soll).apply();
					Log.i(TAG, "Sperrbild gesetzt: " + (soll.isEmpty() ? "Rosenweg" : soll));
				} catch (Exception e) {
					Log.w(TAG, "Sperrbild: " + e);
				} finally {
					laeuft = null;
				}
			}
		}, "Sperrbild").start();
	}

	/** Logo aus dem Zwischenspeicher oder von der Website (nur www.rosenweg4303.ch). */
	private static Bitmap laden(Context c, String datei) {
		java.io.File f = new java.io.File(c.getFilesDir(), "sperrbild-" + datei);
		// Zwischenspeicher: neue Fassung dieser Klasse (VERSION) oder aelter als 7 Tage ->
		// neu laden (Logos auf der Website koennen sich aendern, z.B. Transparenz 25.11.2026)
		boolean veraltet = prefs(c).getInt("version", 0) < VERSION
				|| System.currentTimeMillis() - f.lastModified() > 7L * 24 * 3600 * 1000;
		if (f.exists() && veraltet) f.delete();
		if (!f.exists()) {
			try {
				java.net.HttpURLConnection v = (java.net.HttpURLConnection)
						new java.net.URL("https://www.rosenweg4303.ch/" + datei).openConnection();
				v.setConnectTimeout(10000);
				v.setReadTimeout(20000);
				if (v.getResponseCode() != 200) return null;
				java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
				try (java.io.InputStream in = v.getInputStream(); java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
					byte[] puffer = new byte[16384];
					long n = 0;
					for (int r; (r = in.read(puffer)) > 0; ) {
						n += r;
						if (n > 8_000_000) return null;   // kein Logo ist so gross
						out.write(puffer, 0, r);
					}
				} finally {
					v.disconnect();
				}
				if (!tmp.renameTo(f)) return null;
			} catch (Exception e) {
				Log.w(TAG, "Logo laden: " + e.getClass().getSimpleName());
				return null;
			}
		}
		BitmapFactory.Options o = new BitmapFactory.Options();
		o.inSampleSize = 2;   // ~450 px reichen fuer 60 % Bildschirmbreite
		Bitmap roh = BitmapFactory.decodeFile(f.getPath(), o);
		return roh == null ? null : randFrei(roh);
	}

	/**
	 * Manche Logos haben einen grauen/weissen Kasten statt Transparenz: alles, was von den
	 * Ecken aus zusammenhaengend die Eckfarbe hat, wird durchsichtig (Flutfuellung).
	 */
	static Bitmap randFrei(Bitmap roh) {
		Bitmap b = roh.copy(Bitmap.Config.ARGB_8888, true);
		int w = b.getWidth(), h = b.getHeight();
		int[] px = new int[w * h];
		b.getPixels(px, 0, w, 0, 0, w, h);
		int ecke = px[0];
		if (Color.alpha(ecke) < 20) return roh;   // schon durchsichtig
		boolean[] gesehen = new boolean[w * h];
		int[] stapel = new int[w * h];
		int n = 0;
		int[] start = {0, w - 1, (h - 1) * w, h * w - 1};
		for (int i : start) { stapel[n++] = i; gesehen[i] = true; }
		while (n > 0) {
			int i = stapel[--n];
			if (!aehnlich(px[i], ecke)) continue;
			px[i] = 0;
			int x = i % w, y = i / w;
			if (x > 0 && !gesehen[i - 1]) { gesehen[i - 1] = true; stapel[n++] = i - 1; }
			if (x < w - 1 && !gesehen[i + 1]) { gesehen[i + 1] = true; stapel[n++] = i + 1; }
			if (y > 0 && !gesehen[i - w]) { gesehen[i - w] = true; stapel[n++] = i - w; }
			if (y < h - 1 && !gesehen[i + w]) { gesehen[i + w] = true; stapel[n++] = i + w; }
		}
		b.setHasAlpha(true);   // sonst zeichnet Android die durchsichtigen Pixel schwarz
		b.setPixels(px, 0, w, 0, 0, w, h);
		return b;
	}

	private static boolean aehnlich(int a, int b) {
		return Math.abs(Color.red(a) - Color.red(b)) < 28 && Math.abs(Color.green(a) - Color.green(b)) < 28
				&& Math.abs(Color.blue(a) - Color.blue(b)) < 28 && Color.alpha(a) > 20;
	}
}
