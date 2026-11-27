package org.sipdroid.sipua.rosenweg;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;

/**
 * Laufzeitrechte ueber den Device Owner erteilen - aber nur, wenn sie noch nicht erteilt sind.
 *
 * Jedes setPermissionGrantState(..., GRANTED) fuer ein Standort-Recht laesst den Permission
 * Controller eine Benachrichtigung zeigen ("Standortzugriff moeglich - Dein IT-Administrator
 * erlaubt der App ...", Android 16: "... hat Standortzugriff"), auch wenn das Recht laengst
 * erteilt ist. Stefan 27.11.2026: auf Smartphone 1 kam sie bei jedem Katalog-Abgleich neu.
 */
final class Rechte {
	private Rechte() { }

	/** Muss erteilt werden? Nur wenn der Geraeteverwalter es noch nicht fest erteilt hat. */
	static boolean noetig(int zustand) {
		return zustand != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED;
	}

	/** Recht fest erteilen, falls noetig; true = ist (jetzt) erteilt. */
	static boolean erteilen(DevicePolicyManager d, ComponentName a, String paket, String recht) {
		if (!noetig(d.getPermissionGrantState(a, paket, recht))) return true;
		return d.setPermissionGrantState(a, paket, recht, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
	}
}
