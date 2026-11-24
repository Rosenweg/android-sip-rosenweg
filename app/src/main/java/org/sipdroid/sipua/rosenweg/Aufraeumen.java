package org.sipdroid.sipua.rosenweg;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.File;

/**
 * Beim ABMELDEN den gemeinsamen Speicher leeren: Fotos, Videos, Downloads,
 * Dokumente, Aufnahmen, Musik. Die App-Daten loescht Katalog.sperren(); diese
 * Dateien liegen aber ausserhalb der Apps und blieben sonst fuer die naechste
 * Person sichtbar.
 *
 * Nur beim echten Abmelden (Knopf, Abmelden im Browser/Admin) — NICHT beim
 * Sperren nach einem Neustart, wo die Person angemeldet bleibt.
 *
 * Spaeter (Apps laden selbst hoch): erst loeschen, wenn alles in der
 * Nextcloud der Person angekommen ist.
 *
 * Braucht "Zugriff auf alle Dateien" (MANAGE_EXTERNAL_STORAGE), einmalig per
 *   adb shell appops set ch.rosenweg4303.telefon MANAGE_EXTERNAL_STORAGE allow
 */
public final class Aufraeumen {
	private static final String TAG = "RosenwegAufraeumen";

	private Aufraeumen() {}

	static boolean darf() {
		return Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager();
	}

	/** Im Hintergrund; blockiert nicht. */
	static void gemeinsamenSpeicherLeeren(Context c) {
		if (!Sperre.istDeviceOwner(c)) return;
		if (!darf()) {
			Log.w(TAG, "Kein Zugriff auf alle Dateien — gemeinsamer Speicher bleibt (adb appops ... MANAGE_EXTERNAL_STORAGE allow)");
			return;
		}
		new Thread(() -> {
			// Fotos, Videos und Aufnahmen bleiben, bis das Hochladen in die Nextcloud
			// der Person steht (die Apps laden selbst hoch) — sonst gingen sie beim
			// Abmelden verloren. Dann: erst hochladen, dann auch diese Ordner leeren.
			String[] ordner = {
					Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS, Environment.DIRECTORY_MUSIC,
					Environment.DIRECTORY_PODCASTS, Environment.DIRECTORY_AUDIOBOOKS,
			};
			int geloescht = 0;
			for (String o : ordner) {
				File d = Environment.getExternalStoragePublicDirectory(o);
				geloescht += inhaltLoeschen(d);
			}
			Log.i(TAG, geloescht + " Dateien aus dem gemeinsamen Speicher geloescht");
		}, "rosenweg-aufraeumen").start();
	}

	/** Loescht den Inhalt eines Ordners (nicht den Ordner selbst). */
	private static int inhaltLoeschen(File d) {
		int n = 0;
		File[] liste = d == null ? null : d.listFiles();
		if (liste == null) return 0;
		for (File f : liste) {
			if (f.isDirectory()) {
				n += inhaltLoeschen(f);
				if (f.delete()) n += 0;
			} else if (f.delete()) {
				n++;
			}
		}
		return n;
	}
}
