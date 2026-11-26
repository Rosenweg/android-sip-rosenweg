package org.sipdroid.sipua.rosenweg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

/**
 * Nach dem Neustart und nach einem Update der Geraete-App.
 *
 * Neustart mit angemeldeter Person (ab v239, Stefan 26.11.2026): nicht abmelden und nichts
 * loeschen. Kiosk bleibt an (schon in BootEntsperren gesetzt), die SperrActivity prueft die
 * Sitzung an der Anlage und stellt sie wieder her - PIN neu setzen und Bildschirm sperren
 * inklusive. Kennt die Anlage die Sitzung nicht mehr (Abmelden im Browser, Technik), raeumt
 * sie wie beim Abmelden auf. Bis v238 meldete ein Neustart bewusst ab (Entscheid 24.11.2026).
 *
 * Niemand angemeldet: sperren wie bisher.
 *
 * Nach einem Update der Geraete-App (waehrend jemand angemeldet ist) wird nicht
 * gesperrt: sonst waeren nach jedem Katalog-Abgleich Sitzung und persoenliche
 * Daten weg. Laeuft gerade die Wiederherstellung nach einem Neustart, geht sie weiter.
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
		Nachlauf.starten(c);   // nach Neustart/Update: offene Dateien der letzten Person fertig hochladen
		if (!Sperre.istDeviceOwner(c)) return;
		Katalog.vielleichtAbgleichen(c, true);
		// C: nach dem Neustart Port 5555 selbst wiederherstellen (kein USB noetig)
		if (Intent.ACTION_BOOT_COMPLETED.equals(a)) AdbSelbst.nachStart(c);
		Sperre.grundregeln(c);
		Sperrbild.setzen(c);
		if (Anlage.angemeldet(c) && !Sperre.abmeldungAusdruecklich(c)) {
			if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Bildschirmsperre.neustartOffen(c)) {
				// Neustart (oder Update mitten in der Wiederherstellung): angemeldet bleiben,
				// an der Anlage pruefen, PIN wieder setzen - ohne Karte, ohne Daten zu loeschen.
				Log.i("StartReceiver", "Neustart mit Sitzung: Wiederherstellung (" + a + ")");
				Sperre.nachNeustartWiederherstellen(c);
				return;
			}
			// Update der Geraete-App waehrend jemand angemeldet ist: nicht abmelden,
			// sondern die Anlage fragen und die Sitzung wiederherstellen.
			sitzungBehalten(c.getApplicationContext(), goAsync());
			return;
		}
		Sperre.sperren(c);
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
