package org.sipdroid.sipua.rosenweg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Direkt nach dem Neustart, noch vor dem ersten Entsperren (Direct Boot):
 * - Kiosk setzen (Sperrbildschirm als HOME, Statusleiste aus), damit nach dem
 *   Entsperren nicht kurz der Startbildschirm der Person offen liegt.
 * - persoenliche PIN entfernen und den Neustart vormerken.
 * Abgemeldet wird dabei nicht mehr (v239): die SperrActivity prueft die Sitzung an der
 * Anlage, stellt sie wieder her und sperrt mit der PIN der Person (Bildschirmsperre).
 */
public class BootEntsperren extends BroadcastReceiver {
	@Override
	public void onReceive(Context c, Intent intent) {
		if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) return;
		Sperre.kioskSofort(c);
		Bildschirmsperre.nachNeustart(c);
	}
}
