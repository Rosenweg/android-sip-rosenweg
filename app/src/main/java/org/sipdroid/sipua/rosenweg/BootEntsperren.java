package org.sipdroid.sipua.rosenweg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Direkt nach dem Neustart, noch vor dem ersten Entsperren: Sperrbildschirm als HOME,
 * Statusleiste aus, persoenliche PIN der zuletzt angemeldeten Person entfernen und die
 * Abmeldung vormerken. Jede Sitzung endet beim Neustart (Stefan 27.11.2026); die
 * SperrActivity meldet an der Anlage ab, eine neue Anmeldung geht erst danach.
 */
public class BootEntsperren extends BroadcastReceiver {
	@Override
	public void onReceive(Context c, Intent intent) {
		if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) return;
		if (!Sperre.istDeviceOwner(c)) return;
		// Zuerst sperren (Sperrbildschirm als HOME, Statusleiste aus), dann PIN weg + Abmeldung vormerken
		Sperre.sperrenFrueh(c);
		// Nur einmal je Boot (Boot-Zaehler): kaeme der Broadcast spaet, nach einer neuen
		// Anmeldung, bliebe diese unberuehrt.
		if (Neustart.offen(c)) Bildschirmsperre.nachNeustart(c);
	}
}
