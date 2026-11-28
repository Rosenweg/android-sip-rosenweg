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

	/** Standort-Rechte: ihr Erteilen zeigt den Hinweis des Permission Controllers. */
	static boolean standort(String recht) {
		return recht != null && (recht.endsWith(".ACCESS_FINE_LOCATION") || recht.endsWith(".ACCESS_COARSE_LOCATION")
				|| recht.endsWith(".ACCESS_BACKGROUND_LOCATION"));
	}

	/** Muss erteilt werden? Nur wenn der Geraeteverwalter es noch nicht fest erteilt hat. */
	static boolean noetig(int zustand) {
		return zustand != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED;
	}

	/** Recht fest erteilen, falls noetig; true = ist (jetzt) erteilt. */
	static boolean erteilen(DevicePolicyManager d, ComponentName a, String paket, String recht) {
		int zustand = d.getPermissionGrantState(a, paket, recht);
		if (!noetig(zustand)) return true;
		// Protokoll: jede Erteilung eines Standort-Rechts zeigt den Hinweis des Permission Controllers
		android.util.Log.i("RosenwegRechte", "erteile " + paket + " " + recht + " (Zustand " + zustand + ")");
		return d.setPermissionGrantState(a, paket, recht, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
	}
}
