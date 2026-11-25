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
		if (!WlanDebug.erwartetBestaetigung(this)) return;
		AccessibilityNodeInfo wurzel = getRootInActiveWindow();
		if (wurzel == null) return;
		// nur der Dialog mit dem SSID "Rosenweg"
		if (!enthaelt(wurzel, "\"" + WlanDebug.SSID + "\"") && !nurText(wurzel, WlanDebug.SSID)) return;
		if (wurzel.findAccessibilityNodeInfosByText("Debugging").isEmpty()
				&& wurzel.findAccessibilityNodeInfosByText("debugging").isEmpty()) return;
		for (String t : new String[]{"Immer in diesem Netzwerk zulassen", "Always allow on this network"}) {
			for (AccessibilityNodeInfo n : wurzel.findAccessibilityNodeInfosByText(t)) {
				AccessibilityNodeInfo k = n.isCheckable() ? n : n.getParent();
				if (k != null && k.isCheckable() && !k.isChecked()) k.performAction(AccessibilityNodeInfo.ACTION_CLICK);
				else if (!n.isChecked()) n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
			}
		}
		for (String t : new String[]{"Zulassen", "Allow"}) {
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
