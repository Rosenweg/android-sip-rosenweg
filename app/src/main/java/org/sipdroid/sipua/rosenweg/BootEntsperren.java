package org.sipdroid.sipua.rosenweg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Direkt nach dem Neustart, noch vor dem ersten Entsperren: persoenliche PIN der
 * zuletzt angemeldeten Person entfernen und die Abmeldung vormerken. Die
 * SperrActivity meldet die Person dann an der Anlage ab, statt die Sitzung
 * wiederherzustellen; wer neu startet, kommt so nicht ohne PIN hinein.
 */
public class BootEntsperren extends BroadcastReceiver {
	@Override
	public void onReceive(Context c, Intent intent) {
		if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) return;
		Bildschirmsperre.nachNeustart(c);
	}
}
