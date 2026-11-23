package org.sipdroid.sipua.rosenweg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/** Ergebnis einer stillen Installation aus dem Katalog. */
public class InstallEmpfaenger extends BroadcastReceiver {
	static final String EXTRA_PAKET = "rosenweg_paket";
	static final String EXTRA_SHA = "rosenweg_sha";

	@Override
	public void onReceive(Context c, Intent i) {
		int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
		String paket = i.getStringExtra(EXTRA_PAKET);
		if (status == PackageInstaller.STATUS_SUCCESS) {
			Log.i("RosenwegKatalog", "installiert: " + paket);
			Katalog.installiertGemerkt(c, paket, i.getStringExtra(EXTRA_SHA));
			Katalog.nachInstallation(c, paket);
		} else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
			// Sollte als Device Owner nicht vorkommen. Nicht nachfragen: ein
			// geteiltes Telefon hat niemanden, der das bestaetigt.
			Log.w("RosenwegKatalog", "Installation wollte Bestaetigung: " + paket);
		} else {
			Log.w("RosenwegKatalog", "Installation fehlgeschlagen: " + paket + " — "
					+ i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
		}
	}
}
