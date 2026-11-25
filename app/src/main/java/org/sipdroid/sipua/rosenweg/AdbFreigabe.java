package org.sipdroid.sipua.rosenweg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Notausgang fuer die Wartung per USB: hebt das Installationsverbot fuer
 * 10 Minuten auf, damit "adb install" geht.
 *
 *   adb shell am broadcast -a ch.rosenweg4303.telefon.ADB_INSTALLATION \
 *       -n ch.rosenweg4303.telefon/org.sipdroid.sipua.rosenweg.AdbFreigabe
 *
 * Abgleich sofort (statt Neustart):
 *   adb shell am broadcast -a ch.rosenweg4303.telefon.ADB_ABGLEICH \\
 *       -n ch.rosenweg4303.telefon/org.sipdroid.sipua.rosenweg.AdbFreigabe
 *
 * Nebenstelle umstellen (Nummernplan, 24.11.2026: Geraete 2100-2199), Passwort bleibt:
 *   adb shell am broadcast -a ch.rosenweg4303.telefon.ADB_NEBENSTELLE --es ext 2102 \
 *       -n ch.rosenweg4303.telefon/org.sipdroid.sipua.rosenweg.AdbFreigabe
 *
 * Geschuetzt durch android.permission.DUMP: die hat die adb-Shell, normale
 * Apps bekommen sie nicht. Wer das senden kann, hat ohnehin USB-Debugging
 * und das Geraet in der Hand.
 */
public class AdbFreigabe extends BroadcastReceiver {
	static final String ABGLEICH = "ch.rosenweg4303.telefon.ADB_ABGLEICH";
	static final String NEBENSTELLE = "ch.rosenweg4303.telefon.ADB_NEBENSTELLE";
	/** C: eigenen adb-Schluessel einmal freigeben (Einrichtungsskript, Port 5555 offen). */
	static final String SELBST_FREIGEBEN = "ch.rosenweg4303.telefon.ADB_SELBST_FREIGEBEN";
	/** C zum Testen: Port 5555 jetzt selbst wiederherstellen. */
	static final String SELBST_WIEDERHERSTELLEN = "ch.rosenweg4303.telefon.ADB_SELBST_WIEDERHERSTELLEN";

	@Override
	public void onReceive(Context c, Intent i) {
		if (SELBST_FREIGEBEN.equals(i.getAction()) || SELBST_WIEDERHERSTELLEN.equals(i.getAction())) {
			final boolean freigeben = SELBST_FREIGEBEN.equals(i.getAction());
			final Context app = c.getApplicationContext();
			// Kein goAsync: die Freigabe wartet bis 90 s auf die Rueckfrage, ein Empfaenger darf
			// so lange nicht blockieren (sonst "reagiert nicht"). Ergebnis steht im Log.
			new Thread(() -> {
				String f = freigeben ? AdbSelbst.freigeben(app) : AdbSelbst.tcpWiederherstellen(app);
				Log.i("RosenwegAdbSelbst", (freigeben ? "freigeben: " : "wiederherstellen: ") + (f == null ? "OK" : f));
			}).start();
			return;
		}
		if (NEBENSTELLE.equals(i.getAction())) {
			String ext = i.getStringExtra("ext");
			if (ext == null || !ext.matches("[0-9]{2,8}")) {
				Log.w("RosenwegKatalog", "ADB_NEBENSTELLE: ungueltige Nummer");
				return;
			}
			Anlage.prefs(c).edit().putString(org.sipdroid.sipua.ui.Settings.PREF_USERNAME, ext).commit();
			// mit der neuen Nummer neu am SIP-Server anmelden (wie nach einer Aenderung in den Einstellungen)
			org.sipdroid.sipua.ui.Receiver.engine(c).halt();
			org.sipdroid.sipua.ui.Receiver.engine(c).StartEngine();
			Log.i("RosenwegKatalog", "Nebenstelle per adb umgestellt auf " + ext);
			return;
		}
		if (ABGLEICH.equals(i.getAction())) {
			// Katalog, Ausblendliste, Telefonbuch sofort holen — statt Neustart.
			Log.i("RosenwegKatalog", "Abgleich per adb angestossen");
			Katalog.vielleichtAbgleichen(c, true);
			return;
		}
		Log.w("RosenwegKatalog", "Installationsverbot per adb fuer 10 Minuten aufgehoben");
		Katalog.installationErlauben(c, true, 10 * 60 * 1000L);
	}
}
