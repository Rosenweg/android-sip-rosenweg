package org.sipdroid.sipua.rosenweg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

/**
 * Nach dem Neustart: geteiltes Telefon sperren und die Sitzung beenden (Neustart,
 * Stefan 27.11.2026: "Jede Sitzung endet direkt beim Neustart"). Die Abmeldung an der
 * Anlage holt die SperrActivity nach; erst danach ist eine neue Anmeldung moeglich.
 *
 * Nach einem Update der Geraete-App (waehrend jemand angemeldet ist) wird nicht
 * gesperrt: sonst waeren nach jedem Katalog-Abgleich Sitzung und persoenliche
 * Daten weg.
 */
public class StartReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context c, Intent intent) {
		String a = intent.getAction();
		if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
		// SIP-Engine starten: im Kiosk oeffnet niemand die Einstellungen, und
		// PACKAGE_REPLACED (alter Sipdroid-Weg) erreicht Manifest-Empfaenger seit
		// Android 8 nicht mehr — nach einem Update waere das Telefon sonst stumm.
		// Neustart: jede Sitzung endet (Stefan 27.11.2026). Zuerst sperren, dann alles andere.
		if (Intent.ACTION_BOOT_COMPLETED.equals(a)) Sperre.sperrenFrueh(c);
		org.sipdroid.sipua.ui.Receiver.engine(c).register();
		Nachlauf.starten(c);   // nach Neustart/Update: offene Dateien der letzten Person fertig hochladen
		if (!Sperre.istDeviceOwner(c)) return;
		// Neuer Boot (Boot-Zaehler, einmal je Boot): lokal abmelden wie per Knopf und sperren
		boolean neustartGesperrt = Neustart.abschliessen(c);
		Katalog.vielleichtAbgleichen(c, true);
		// C: nach dem Neustart Port 5555 selbst wiederherstellen (kein USB noetig)
		if (Intent.ACTION_BOOT_COMPLETED.equals(a)) AdbSelbst.nachStart(c);
		Sperre.grundregeln(c);
		Sperrbild.setzen(c);
		if (!neustartGesperrt && Intent.ACTION_MY_PACKAGE_REPLACED.equals(a) && Anlage.angemeldet(c)
				&& !Bildschirmsperre.abmeldungAusstehend(c)) {
			// Update der Geraete-App waehrend jemand angemeldet ist: nicht abmelden,
			// sondern die Anlage fragen und die Sitzung wiederherstellen.
			sitzungBehalten(c.getApplicationContext(), goAsync());
			return;
		}
		if (!neustartGesperrt) Sperre.sperren(c);
	}

	private static void sitzungBehalten(Context c, PendingResult fertig) {
		Handler haupt = new Handler(Looper.getMainLooper());
		new Thread(() -> {
			try {
				JSONObject s = Anlage.status(c);
				haupt.post(() -> {
					if (s.optBoolean("angemeldet", false)) {
						Sperre.freigeben(c, s.optString("name", ""), s.optJSONArray("apps"));
					} else {
						Sperre.sperren(c);
					}
				});
			} catch (Exception e) {
				// Anlage nicht erreichbar: Sitzung bleibt, der AnmeldeDienst prueft weiter
				Log.w("StartReceiver", "Status nach Update: " + e);
				haupt.post(() -> Sperre.freigeben(c, Anlage.name(c), null));
			} finally {
				fertig.finish();
			}
		}).start();
	}
}
