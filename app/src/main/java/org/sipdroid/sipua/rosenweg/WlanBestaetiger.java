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
		if (fp.isEmpty()) return;
		AccessibilityNodeInfo wurzel = getRootInActiveWindow();
		if (wurzel == null) return;
		boolean passt = false;
		for (AccessibilityNodeInfo n : wurzel.findAccessibilityNodeInfosByText(fp.substring(0, 11))) {
			CharSequence t = n.getText();
			if (t != null && t.toString().toUpperCase(java.util.Locale.ROOT).contains(fp)) passt = true;
		}
		if (!passt) return;
		// "Immer zulassen" ankreuzen. Samsung (A15: "Von diesem Computer immer zulassen" / "Erlauben")
		// hat das Kaestchen als eigenes Element neben dem Text: das einzige ankreuzbare im Dialog.
		AccessibilityNodeInfo kaestchen = ankreuzbar(wurzel);
		if (kaestchen == null) return;   // Dialog noch nicht ganz aufgebaut, naechstes Ereignis
		if (!kaestchen.isChecked()) {
			kaestchen.performAction(AccessibilityNodeInfo.ACTION_CLICK);
			return;   // erst wenn das Ereignis "angekreuzt" kommt, auf Erlauben tippen
		}
		for (String t : new String[]{"Erlauben", "Zulassen", "Allow", "OK"}) {
			for (AccessibilityNodeInfo n : wurzel.findAccessibilityNodeInfosByText(t)) {
				CharSequence txt = n.getText();
				if (txt != null && txt.toString().trim().equalsIgnoreCase(t) && n.isClickable()) {
					n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
					Log.i(TAG, (dienst ? "Schluessel des adb-Updaters der Anlage" : "eigener adb-Schluessel von Rosenweg Geraet")
							+ " zugelassen (" + fp + ")");
					if (dienst) AdbSelbst.dienstEnde(this);
					return;
				}
			}
		}
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
