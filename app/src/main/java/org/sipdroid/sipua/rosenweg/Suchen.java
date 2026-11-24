package org.sipdroid.sipua.rosenweg;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

/**
 * Telefon suchen (24.11.2026): Die Anlage ruft ein verlegtes Telefon mit 2099
 * "Telefon suchen" an (POST /api/telefon/<ext>/suchen). Fuer diesen Anruf klingelt
 * es mit voller Lautstaerke, auch wenn es leise oder auf Vibration gestellt ist;
 * danach gilt wieder die alte Einstellung.
 */
public final class Suchen {
	public static final String NUMMER = "2099";
	private static final String TAG = "RosenwegSuchen";
	private static int alterModus = -1, alteLautstaerke = -1, alterFilter = -1;

	private Suchen() {}

	public static boolean istSuchanruf(String nummer) {
		return NUMMER.equals(nummer);
	}

	public static synchronized void laut(Context c) {
		try {
			AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
			// "Nicht stoeren" kurz aus (Zugriff einmalig per adb: cmd notification allow_dnd)
			android.app.NotificationManager nm = (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
			if (nm.isNotificationPolicyAccessGranted()) {
				if (alterFilter < 0) alterFilter = nm.getCurrentInterruptionFilter();
				nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL);
			}
			if (alterModus < 0) {
				alterModus = am.getRingerMode();
				alteLautstaerke = am.getStreamVolume(AudioManager.STREAM_RING);
			}
			am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
			am.setStreamVolume(AudioManager.STREAM_RING, am.getStreamMaxVolume(AudioManager.STREAM_RING), 0);
			Log.i(TAG, "Suchanruf: Klingeln auf voller Lautstaerke");
		} catch (Exception e) {   // z.B. "Nicht stoeren" ohne Zugriff
			Log.w(TAG, "laut: " + e);
		}
	}

	public static synchronized void zurueck(Context c) {
		if (alterModus < 0 && alterFilter < 0) return;
		try {
			AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
			if (alterModus >= 0) {
				am.setStreamVolume(AudioManager.STREAM_RING, alteLautstaerke, 0);
				am.setRingerMode(alterModus);
			}
			if (alterFilter > 0) {
				((android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).setInterruptionFilter(alterFilter);
			}
		} catch (Exception e) {
			Log.w(TAG, "zurueck: " + e);
		} finally {
			alterModus = alteLautstaerke = alterFilter = -1;
		}
	}
}
