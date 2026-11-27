package org.sipdroid.sipua.rosenweg;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Bestaetigt den Android-Dialog "Debugging ueber WLAN in diesem Netzwerk zulassen?"
 * (Stefan 25.11.2026: niemand soll am Telefon tippen; nur WLANs mit dem Namen "Rosenweg").
 *
 * Greift nur, wenn
 *  - Rosenweg Geraet das Debugging in den letzten 2 Minuten selbst eingeschaltet hat
 *    (WlanDebug.erwartetBestaetigung), und
 *  - der Dialog als Netzwerknamen genau "Rosenweg" zeigt.
 * Dann: "Immer in diesem Netzwerk zulassen" ankreuzen, "Zulassen" tippen. Sonst nichts.
 * Eingeschaltet wird die Bedienungshilfe einmal per USB im Einrichtungsskript.
 */
public class WlanBestaetiger extends AccessibilityService {
	private static final String TAG = "RosenwegWlanDebug";

	@Override
	public void onAccessibilityEvent(AccessibilityEvent e) {
		if (AdbSelbst.erwartetFreigabe(this)) schluesselFreigeben(AdbSelbst.eigenerFingerabdruck(this), false);
		String dienst = AdbSelbst.dienstFingerabdruck(this);
		if (!dienst.isEmpty()) schluesselFreigeben(dienst, true);
		fernwartungRueckfrage();
		if (!WlanDebug.erwartetBestaetigung(this)) return;
		AccessibilityNodeInfo wurzel = getRootInActiveWindow();
		if (wurzel == null) return;
		// nur der Dialog mit dem Netz "Rosenweg" (erwartetBestaetigung prueft zusaetzlich das aktuelle WLAN).
		// Samsung zeigt den Namen nicht als eigenes Textfeld - es genuegt, dass er im Dialog steht.
		if (!enthaeltTeil(wurzel, WlanDebug.SSID)) return;
		if (wurzel.findAccessibilityNodeInfosByText("Debugging").isEmpty()
				&& wurzel.findAccessibilityNodeInfosByText("debugging").isEmpty()) return;
		// "immer in diesem Netzwerk": AOSP-Text oder das einzige ankreuzbare Element (Samsung)
		AccessibilityNodeInfo kaestchen = ankreuzbar(wurzel);
		if (kaestchen != null && !kaestchen.isChecked()) {
			kaestchen.performAction(AccessibilityNodeInfo.ACTION_CLICK);
			return;   // beim naechsten Ereignis (angekreuzt) auf Erlauben tippen
		}
		for (String t : new String[]{"Erlauben", "Zulassen", "Allow"}) {
			for (AccessibilityNodeInfo n : wurzel.findAccessibilityNodeInfosByText(t)) {
				CharSequence txt = n.getText();
				if (txt != null && txt.toString().trim().equalsIgnoreCase(t) && n.isClickable()) {
					n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
					WlanDebug.bestaetigt(this);
					Log.i(TAG, "WLAN-Debugging im Netz " + WlanDebug.SSID + " bestaetigt");
					return;
				}
			}
		}
	}

	private static boolean enthaeltTeil(AccessibilityNodeInfo w, String text) {
		for (AccessibilityNodeInfo n : w.findAccessibilityNodeInfosByText(text)) {
			CharSequence t = n.getText();
			if (t != null && t.toString().contains(text)) return true;
		}
		return false;
	}

	/**
	 * C: "USB-Debugging zulassen?" fuer den EIGENEN Schluessel von Rosenweg Geraet (AdbSelbst.
	 * freigeben) oder - ab v237 - fuer den Schluessel des adb-Updaters der Anlage (AdbSelbst.
	 * dienstErwarten, nur waehrend "Updater zulassen" und zentral eingeschalteter Wartung).
	 * Nur wenn der angezeigte Fingerabdruck genau der erwartete ist - fremde Rechner bestaetigt
	 * die Bedienungshilfe nie.
	 */
	private void schluesselFreigeben(String fp, boolean dienst) {
		schluesselFreigeben(fp, dienst, dienst ? "Schluessel des adb-Updaters der Anlage" : "eigener adb-Schluessel von Rosenweg Geraet");
	}

	private boolean schluesselFreigeben(String fp, boolean dienst, String wer) {
		if (fp.isEmpty()) return false;
		AccessibilityNodeInfo wurzel = getRootInActiveWindow();
		if (wurzel == null) return false;
		boolean passt = false;
		for (AccessibilityNodeInfo n : wurzel.findAccessibilityNodeInfosByText(fp.substring(0, 11))) {
			CharSequence t = n.getText();
			if (t != null && t.toString().toUpperCase(java.util.Locale.ROOT).contains(fp)) passt = true;
		}
		if (!passt) return false;
		// "Immer zulassen" ankreuzen. Samsung (A15: "Von diesem Computer immer zulassen" / "Erlauben")
		// hat das Kaestchen als eigenes Element neben dem Text: das einzige ankreuzbare im Dialog.
		AccessibilityNodeInfo kaestchen = ankreuzbar(wurzel);
		if (kaestchen == null) return false;   // Dialog noch nicht ganz aufgebaut, naechstes Ereignis
		if (!kaestchen.isChecked()) {
			kaestchen.performAction(AccessibilityNodeInfo.ACTION_CLICK);
			return false;   // erst wenn das Ereignis "angekreuzt" kommt, auf Erlauben tippen
		}
		for (String t : new String[]{"Erlauben", "Zulassen", "Allow", "OK"}) {
			for (AccessibilityNodeInfo n : wurzel.findAccessibilityNodeInfosByText(t)) {
				CharSequence txt = n.getText();
				if (txt != null && txt.toString().trim().equalsIgnoreCase(t) && n.isClickable()) {
					n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
					Log.i(TAG, wer + " zugelassen (" + fp + ")");
					if (dienst) AdbSelbst.dienstEnde(this);
					return true;
				}
			}
		}
		return false;
	}


	// ─── Fernwartung: nur bekannte Schluessel (Plan adb-tunnel-telefone 2.1) ─────────

	private static final java.util.regex.Pattern FINGERABDRUCK = java.util.regex.Pattern.compile("([0-9A-F]{2}:){15}[0-9A-F]{2}");

	/**
	 * Solange die Fernwartung steht (adb dauerhaft an), gilt fuer "USB-Debugging zulassen?":
	 *  - eigener Schluessel, Zentrale (adbz) und Updater: zulassen ("immer"),
	 *  - jeder andere: "Ablehnen" tippen und der Anlage melden (Verlauf, NOC rot).
	 * Ausnahme Wartung vor Ort: ist auf /smartphones "1 Std." an (z. B. Mac am USB), bleibt die
	 * Rueckfrage fuer den Menschen stehen und wird nur gemeldet. Nur Dialoge der SystemUI.
	 */
	private void fernwartungRueckfrage() {
		if (!Fernwartung.adbDauerhaft(this)) return;
		AccessibilityNodeInfo w = getRootInActiveWindow();
		if (w == null || w.getPackageName() == null || !"com.android.systemui".contentEquals(w.getPackageName())) return;
		String fp = fingerabdruckIm(w, 0);
		if (fp == null) return;
		if (Fernwartung.erlaubteSchluessel(this).contains(fp)) {
			if (schluesselFreigeben(fp, false, "bekannter Schluessel (Fernwartung)")) {
				Fernwartung.zugelassen(this, fp);
				AdbSelbst.rueckfrageEnde(this);
			}
			return;
		}
		boolean vorOrt = WlanDebug.zentralAn(this);
		if (!vorOrt && !tippe(w, "Ablehnen", "Abbrechen", "Nicht zulassen", "Verweigern", "Deny", "Cancel")) return;
		fremdMelden(fp, !vorOrt);
	}

	private static String fingerabdruckIm(AccessibilityNodeInfo n, int tiefe) {
		if (n == null || tiefe > 12) return null;
		CharSequence t = n.getText();
		if (t != null) {
			java.util.regex.Matcher m = FINGERABDRUCK.matcher(t.toString().toUpperCase(java.util.Locale.ROOT));
			if (m.find()) return m.group();
		}
		for (int i = 0; i < n.getChildCount(); i++) {
			String f = fingerabdruckIm(n.getChild(i), tiefe + 1);
			if (f != null) return f;
		}
		return null;
	}

	private static boolean tippe(AccessibilityNodeInfo w, String... texte) {
		for (String t : texte) {
			for (AccessibilityNodeInfo n : w.findAccessibilityNodeInfosByText(t)) {
				CharSequence txt = n.getText();
				if (txt != null && txt.toString().trim().equalsIgnoreCase(t) && n.isClickable()) {
					n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
					return true;
				}
			}
		}
		return false;
	}

	private void fremdMelden(String fp, boolean abgelehnt) {
		android.content.SharedPreferences p = Fernwartung.prefs(this);
		if (fp.equals(p.getString("fremd_fp", "")) && System.currentTimeMillis() - p.getLong("fremd_um", 0) < 600_000) return;
		p.edit().putString("fremd_fp", fp).putLong("fremd_um", System.currentTimeMillis()).apply();
		Log.w(TAG, "fremde adb-Rueckfrage " + fp + (abgelehnt ? " abgelehnt" : " offen gelassen (Wartung vor Ort)"));
		final android.content.Context app = getApplicationContext();
		new Thread(() -> {
			try {
				Anlage.fremdenSchluesselMelden(app, fp, abgelehnt);
			} catch (Exception e) {
				Log.w(TAG, "fremde Rueckfrage melden: " + e.getClass().getSimpleName());
			}
		}, "FremdMelden").start();
	}

	private static AccessibilityNodeInfo ankreuzbar(AccessibilityNodeInfo n) {
		if (n == null) return null;
		if (n.isCheckable()) return n;
		for (int i = 0; i < n.getChildCount(); i++) {
			AccessibilityNodeInfo k = ankreuzbar(n.getChild(i));
			if (k != null) return k;
		}
		return null;
	}

	private static boolean enthaelt(AccessibilityNodeInfo w, String text) {
		for (AccessibilityNodeInfo n : w.findAccessibilityNodeInfosByText(text)) {
			CharSequence t = n.getText();
			if (t != null && t.toString().trim().equals(text)) return true;
		}
		return false;
	}

	private static boolean nurText(AccessibilityNodeInfo w, String text) {
		List<AccessibilityNodeInfo> l = w.findAccessibilityNodeInfosByText(text);
		for (AccessibilityNodeInfo n : l) {
			CharSequence t = n.getText();
			if (t != null && t.toString().trim().replace("\"", "").equals(text)) return true;
		}
		return false;
	}

	@Override
	public void onInterrupt() { }
}
