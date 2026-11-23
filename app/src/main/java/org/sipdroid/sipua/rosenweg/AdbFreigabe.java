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
 * Geschuetzt durch android.permission.DUMP: die hat die adb-Shell, normale
 * Apps bekommen sie nicht. Wer das senden kann, hat ohnehin USB-Debugging
 * und das Geraet in der Hand.
 */
public class AdbFreigabe extends BroadcastReceiver {
	static final String ABGLEICH = "ch.rosenweg4303.telefon.ADB_ABGLEICH";

	@Override
	public void onReceive(Context c, Intent i) {
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
