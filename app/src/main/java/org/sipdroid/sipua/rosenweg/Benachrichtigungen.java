package org.sipdroid.sipua.rosenweg;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Entfernt Samsung-Aufforderungen, die auf einem geteilten Telefon nur verwirren (Stefan
 * 25.11.2026: "Galaxy-Einrichtung muessen wir auf dem A15 unterdruecken"). Die Meldung
 * "Beenden der Galaxy-Einrichtung" kommt aus den Einstellungen (Kanal samsung_account_push_*);
 * deren Benachrichtigungen lassen sich nicht sperren und der Samsung Account nicht abschalten.
 *
 * Ebenso den Hinweis des Permission Controllers, dass der Geraeteverwalter einer Rosenweg-App
 * den Standort erlaubt ("Standortzugriff moeglich - Dein IT-Administrator erlaubt der App
 * ‚Rosenweg Geraet' ...", Android 16: "Rosenweg Geraet hat Standortzugriff", dazu die
 * Sammelmeldung "Erteilte Berechtigungen"). Stefan 27.11.2026: stoert nur. Der Hinweis traegt
 * als Tag den Paketnamen der App - fremde Apps bleiben unberuehrt.
 *
 * Nur genau diese Kanaele, alles andere bleibt.
 *
 * Freigabe einmal per adb (geteiltes-telefon-einrichten.sh):
 *   cmd notification allow_listener ch.rosenweg4303.telefon/org.sipdroid.sipua.rosenweg.Benachrichtigungen
 */
public class Benachrichtigungen extends NotificationListenerService {
	private static final String TAG = "RosenwegBenachrichtigung";

	/** Google- und AOSP-Variante des Permission Controllers */
	static final String[] RECHTE_PAKETE = {"com.google.android.permissioncontroller", "com.android.permissioncontroller"};
	/** Kanal der Hinweise "Geraeteverwalter hat ... erlaubt" (AutoGrantPermissionsNotifier) */
	static final String RECHTE_KANAL = "alerting auto granted permissions";
	static final String ROSENWEG = "ch.rosenweg4303.";

	/** Das Noetige einer Benachrichtigung, ohne Android-Objekte (pruefbar). */
	static final class Eintrag {
		final String schluessel, paket, tag, kanal, gruppe;
		final boolean sammel, bleibend;

		Eintrag(String schluessel, String paket, String tag, String kanal, String gruppe, boolean sammel, boolean bleibend) {
			this.schluessel = schluessel;
			this.paket = paket;
			this.tag = tag;
			this.kanal = kanal;
			this.gruppe = gruppe;
			this.sammel = sammel;
			this.bleibend = bleibend;
		}

		static Eintrag von(StatusBarNotification sbn) {
			Notification n = sbn.getNotification();
			int f = n.flags;
			return new Eintrag(sbn.getKey(), sbn.getPackageName(), sbn.getTag(), n.getChannelId(), n.getGroup(),
					(f & Notification.FLAG_GROUP_SUMMARY) != 0,
					(f & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR)) != 0);
		}
	}

	static boolean samsungEinrichtung(Eintrag e) {
		return "com.android.settings".equals(e.paket) && e.kanal != null && e.kanal.startsWith("samsung_account_push");
	}

	static boolean rechteHinweis(Eintrag e) {
		if (e.bleibend || !RECHTE_KANAL.equals(e.kanal)) return false;
		for (String p : RECHTE_PAKETE) if (p.equals(e.paket)) return true;
		return false;
	}

	/** Hinweis "Standortzugriff" fuer eine Rosenweg-App (Tag = Paketname der App). */
	static boolean rosenwegStandortHinweis(Eintrag e) {
		return rechteHinweis(e) && !e.sammel && e.tag != null && e.tag.startsWith(ROSENWEG);
	}

	/**
	 * Was aus den aktiven Benachrichtigungen weg soll: Samsung-Einrichtung, Rosenweg-Standort-
	 * Hinweise und deren Sammelmeldung, sobald in ihrer Gruppe nur noch Rosenweg-Hinweise stehen
	 * (Wegraeumen der Sammelmeldung raeumte sonst fremde Hinweise mit ab).
	 */
	static List<String> wegraeumen(List<Eintrag> alle) {
		List<String> weg = new ArrayList<>();
		for (Eintrag e : alle) {
			if (samsungEinrichtung(e) || rosenwegStandortHinweis(e)) weg.add(e.schluessel);
		}
		for (Eintrag s : alle) {
			if (!s.sammel || !rechteHinweis(s)) continue;
			boolean fremde = false;
			for (Eintrag e : alle) {
				if (e == s || !s.paket.equals(e.paket)) continue;
				if (s.gruppe != null && !s.gruppe.equals(e.gruppe)) continue;
				if (!weg.contains(e.schluessel)) fremde = true;
			}
			if (!fremde) weg.add(s.schluessel);
		}
		return weg;
	}

	@Override
	public void onListenerConnected() {
		aufraeumen();
	}

	@Override
	public void onNotificationPosted(StatusBarNotification sbn) {
		if (sbn == null) return;
		Eintrag e = Eintrag.von(sbn);
		if (samsungEinrichtung(e) || rechteHinweis(e)) aufraeumen();
	}

	private void aufraeumen() {
		try {
			StatusBarNotification[] aktiv = getActiveNotifications();
			if (aktiv == null) return;
			List<Eintrag> alle = new ArrayList<>();
			for (StatusBarNotification sbn : aktiv) if (sbn != null) alle.add(Eintrag.von(sbn));
			for (String k : wegraeumen(alle)) {
				try {
					cancelNotification(k);
					Log.i(TAG, "entfernt: " + k);
				} catch (Exception x) {
					Log.w(TAG, "entfernen: " + x.getClass().getSimpleName());
				}
			}
		} catch (Exception x) {
			Log.w(TAG, "Bestand: " + x.getClass().getSimpleName());
		}
	}
}
