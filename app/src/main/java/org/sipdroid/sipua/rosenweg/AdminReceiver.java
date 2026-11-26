package org.sipdroid.sipua.rosenweg;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Device-Admin-Empfaenger. Wird die App Device Owner
 * (adb shell dpm set-device-owner ch.rosenweg4303.telefon/org.sipdroid.sipua.rosenweg.AdminReceiver),
 * setzt sie die Grundregeln und sperrt das Geraet, bis sich jemand anmeldet.
 */
public class AdminReceiver extends DeviceAdminReceiver {
	@Override
	public void onEnabled(Context c, Intent intent) {
		Sperre.grundregeln(c);
		Sperre.sperren(c);
	}
}
