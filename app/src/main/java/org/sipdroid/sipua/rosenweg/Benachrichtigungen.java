package org.sipdroid.sipua.rosenweg;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Entfernt Samsung-Aufforderungen, die auf einem geteilten Telefon nur verwirren (Stefan
 * 25.11.2026: "Galaxy-Einrichtung muessen wir auf dem A15 unterdruecken"). Die Meldung
 * "Beenden der Galaxy-Einrichtung" kommt aus den Einstellungen (Kanal samsung_account_push_*);
 * deren Benachrichtigungen lassen sich nicht sperren und der Samsung Account nicht abschalten.
 * Nur genau diese Kanaele, alles andere bleibt.
 *
 * Freigabe einmal per adb (geteiltes-telefon-einrichten.sh):
 *   cmd notification allow_listener ch.rosenweg4303.telefon/org.sipdroid.sipua.rosenweg.Benachrichtigungen
 */
public class Benachrichtigungen extends NotificationListenerService {
	private static final String TAG = "RosenwegBenachrichtigung";

	static boolean weg(StatusBarNotification sbn) {
		if (!"com.android.settings".equals(sbn.getPackageName())) return false;
		String kanal = sbn.getNotification().getChannelId();
		return kanal != null && kanal.startsWith("samsung_account_push");
	}

	@Override
	public void onListenerConnected() {
		try {
			StatusBarNotification[] alle = getActiveNotifications();
			if (alle != null) for (StatusBarNotification sbn : alle) onNotificationPosted(sbn);
		} catch (Exception e) {
			Log.w(TAG, "Bestand: " + e.getClass().getSimpleName());
		}
	}

	@Override
	public void onNotificationPosted(StatusBarNotification sbn) {
		if (sbn == null || !weg(sbn)) return;
		try {
			cancelNotification(sbn.getKey());
			Log.i(TAG, "entfernt: " + sbn.getPackageName() + " / " + sbn.getNotification().getChannelId());
		} catch (Exception e) {
			Log.w(TAG, "entfernen: " + e.getClass().getSimpleName());
		}
	}
}
