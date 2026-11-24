package org.sipdroid.sipua.rosenweg;

import android.app.Activity;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.Tag;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.os.Bundle;
import android.util.Log;

/**
 * NFC-Karte angetippt (Diagnose, Vorstufe zur Anmeldung per Karte).
 *
 * Rosenweg nutzt fuer UniFi Access normale NFC-Karten: Die Leser werten nur die
 * Seriennummer (UID) aus, und UniFi Access speichert dazu ein 64-stelliges Token
 * (vermutlich SHA-256 der UID). Diese Activity schreibt Kartentyp und UID ins Log,
 * damit sich pruefen laesst, wie das Token aus der UID entsteht. Sie hat keine
 * Oberflaeche und schliesst sich sofort.
 *
 * Spaeter: Anlage fragt "wem gehoert diese Karte" (UniFi Access ist die einzige
 * Quelle, gesperrte Karten gelten auch hier nicht mehr), Anmeldung mit Karte + PIN.
 */
public class NfcKarte extends Activity {
	private static final String TAG = "RosenwegNfc";

	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);
		auswerten(getIntent());
		finish();
	}

	@Override
	protected void onNewIntent(Intent i) {
		super.onNewIntent(i);
		auswerten(i);
		finish();
	}

	static void auswerten(Intent i) {
		if (i == null) return;
		Tag tag = i.getParcelableExtra(NfcAdapter.EXTRA_TAG);
		if (tag == null) return;
		melde(tag);
	}

	static void melde(Tag tag) {
		byte[] id = tag.getId();
		StringBuilder hex = new StringBuilder();
		for (byte x : id) hex.append(String.format("%02X", x));
		StringBuilder techs = new StringBuilder();
		for (String t : tag.getTechList()) techs.append(t.substring(t.lastIndexOf('.') + 1)).append(' ');
		Log.i(TAG, "Karte: uid=" + hex + " laenge=" + id.length + " techs=" + techs.toString().trim());
		// Nur lesen, nie schreiben: was steht sonst auf der Karte (UniFi liest evtl. nicht nur die UID)?
		new Thread(() -> {
			Ndef ndef = Ndef.get(tag);
			if (ndef != null) {
				try {
					ndef.connect();
					NdefMessage m = ndef.getNdefMessage();
					Log.i(TAG, "NDEF: typ=" + ndef.getType() + " max=" + ndef.getMaxSize() + " schreibbar=" + ndef.isWritable()
							+ " saetze=" + (m == null ? 0 : m.getRecords().length));
					if (m != null) for (NdefRecord r : m.getRecords()) {
						Log.i(TAG, "NDEF-Satz: tnf=" + r.getTnf() + " typ=" + new String(r.getType()) + " id=" + hexOf(r.getId())
								+ " laenge=" + r.getPayload().length + " daten=" + hexOf(r.getPayload()));
					}
				} catch (Exception e) {
					Log.w(TAG, "NDEF lesen: " + e);
				} finally {
					try { ndef.close(); } catch (Exception ignored) { }
				}
			}
			MifareUltralight ul = MifareUltralight.get(tag);
			if (ul != null) {
				try {
					ul.connect();
					for (int seite = 0; seite < 48; seite += 4) {
						Log.i(TAG, "Seiten " + seite + "-" + (seite + 3) + ": " + hexOf(ul.readPages(seite)));
					}
				} catch (Exception e) {
					Log.i(TAG, "Seiten lesen beendet: " + e.getClass().getSimpleName());
				} finally {
					try { ul.close(); } catch (Exception ignored) { }
				}
			}
		}).start();
	}

	static String hexOf(byte[] b) {
		if (b == null) return "";
		StringBuilder sb = new StringBuilder();
		for (byte x : b) sb.append(String.format("%02X", x));
		return sb.toString();
	}
}
