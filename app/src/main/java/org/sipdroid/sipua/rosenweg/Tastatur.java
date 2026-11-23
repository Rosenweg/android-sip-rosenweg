package org.sipdroid.sipua.rosenweg;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.util.Collections;

/**
 * Rosenweg Tastatur als Standard setzen, sobald sie installiert ist — ohne dass
 * jemand in den Einstellungen umstellen muss. Nur als Device Owner.
 *
 * Android-Grenze: System-Tastaturen (Samsung) bleiben trotz Erlaubnisliste
 * immer erlaubt; die Samsung-Tastatur verschwindet erst ueber die Ausblendliste,
 * und das erst, wenn die Rosenweg Tastatur wirklich aktiv ist (Ausblenden
 * schuetzt die jeweils aktive Tastatur).
 */
public final class Tastatur {
	static final String PAKET = "ch.rosenweg4303.keyboard";
	static final String DIENST = PAKET + "/org.fossify.keyboard.services.SimpleKeyboardIME";
	private static final String TAG = "RosenwegTastatur";

	private Tastatur() {}

	static void anwenden(Context c) {
		if (!Sperre.istDeviceOwner(c) || !Katalog.installiert(c, PAKET)) return;
		DevicePolicyManager d = Sperre.dpm(c);
		ComponentName a = Sperre.admin(c);
		try {
			d.setPermittedInputMethods(a, Collections.singletonList(PAKET));
		} catch (Exception e) {
			Log.w(TAG, "setPermittedInputMethods: " + e);
		}
		String aktiv = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
		if (DIENST.equals(aktiv)) return;
		try {
			d.setSecureSetting(a, Settings.Secure.DEFAULT_INPUT_METHOD, DIENST);
			Log.i(TAG, "Standard-Tastatur gesetzt (vorher " + aktiv + ")");
		} catch (Exception e) {
			Log.w(TAG, "DEFAULT_INPUT_METHOD: " + e);
		}
	}
}
