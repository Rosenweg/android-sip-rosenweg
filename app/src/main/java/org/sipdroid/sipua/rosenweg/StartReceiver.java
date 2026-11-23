package org.sipdroid.sipua.rosenweg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Nach dem Neustart: geteiltes Telefon zuerst sperren. Ob noch jemand
 * angemeldet ist, prueft danach die SperrActivity bei der Anlage — und gibt
 * dann sofort wieder frei. So ist ein Telefon ohne Netz nie offen.
 */
public class StartReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context c, Intent intent) {
		String a = intent.getAction();
		if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
		// SIP-Engine starten: im Kiosk oeffnet niemand die Einstellungen, und
		// PACKAGE_REPLACED (alter Sipdroid-Weg) erreicht Manifest-Empfaenger seit
		// Android 8 nicht mehr — nach einem Update waere das Telefon sonst stumm.
		org.sipdroid.sipua.ui.Receiver.engine(c).register();
		if (!Sperre.istDeviceOwner(c)) return;
		Sperre.grundregeln(c);
		Sperre.sperren(c);
	}
}
