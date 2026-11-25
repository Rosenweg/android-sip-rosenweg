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
 * Startbildschirm gehoert dem Rosenweg Launcher. Einmal pro Fassung (VERSION), damit eine
 * Aenderung von Hand nicht bei jedem Start ueberschrieben wird - ausser die Fassung steigt.
 */
public final class Sperrbild {
	private static final String TAG = "RosenwegSperrbild";
	private static final String PREF = "rosenweg_sperrbild";
	private static final int VERSION = 1;
	static final int BLAU = Color.parseColor("#1E3A8A");

	private Sperrbild() {}

	public static void setzen(Context c) {
		Context app = c.getApplicationContext();
		android.content.SharedPreferences p = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
		if (p.getInt("version", 0) >= VERSION) return;
		new Thread(() -> {
			try {
				DisplayMetrics dm = app.getResources().getDisplayMetrics();
				int b = Math.min(dm.widthPixels, dm.heightPixels), h = Math.max(dm.widthPixels, dm.heightPixels);
				Bitmap bild = Bitmap.createBitmap(b, h, Bitmap.Config.ARGB_8888);
				Canvas cv = new Canvas(bild);
				cv.drawColor(BLAU);
				Bitmap logo = BitmapFactory.decodeResource(app.getResources(), R.drawable.rosenweg_logo);
				int breite = (int) (b * 0.6f);
				int hoehe = breite * logo.getHeight() / logo.getWidth();
				int x = (b - breite) / 2, y = (h - hoehe) / 2;
				cv.drawBitmap(logo, null, new Rect(x, y, x + breite, y + hoehe), new Paint(Paint.FILTER_BITMAP_FLAG));
				WallpaperManager.getInstance(app).setBitmap(bild, null, true, WallpaperManager.FLAG_LOCK);
				p.edit().putInt("version", VERSION).apply();
				Log.i(TAG, "Sperrbild gesetzt");
			} catch (Exception e) {
				Log.w(TAG, "Sperrbild: " + e);
			}
		}, "Sperrbild").start();
	}
}
