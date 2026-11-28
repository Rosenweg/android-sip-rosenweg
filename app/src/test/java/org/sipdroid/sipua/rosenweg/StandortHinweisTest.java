package org.sipdroid.sipua.rosenweg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.admin.DevicePolicyManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class StandortHinweisTest {
	private static final String PC = "com.google.android.permissioncontroller";
	private static final String KANAL = Benachrichtigungen.RECHTE_KANAL;
	private static final String GRUPPE = "auto granted permission group id";

	private static Benachrichtigungen.Eintrag e(String key, String paket, String tag, String kanal, String gruppe,
			boolean sammel, boolean bleibend) {
		return new Benachrichtigungen.Eintrag(key, paket, tag, kanal, gruppe, sammel, bleibend);
	}

	@Test
	public void nurErteilenWennNochNichtErteilt() {
		assertFalse(Rechte.noetig(DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED));
		assertTrue(Rechte.noetig(DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT));
		assertTrue(Rechte.noetig(DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED));
	}

	@Test
	public void wlanNameOhneStandortIstLeer() {
		assertEquals("", WlanDebug.wlanName("<unknown ssid>"));
		assertEquals("", WlanDebug.wlanName(null));
		assertEquals("Rosenweg", WlanDebug.wlanName("\"Rosenweg\""));
	}

	@Test
	public void xcoverHinweisFuerRosenwegGeraetWeg() {
		// Smartphone 1 (Android 13): id=1 tag=ch.rosenweg4303.telefon, flags=0
		List<String> weg = Benachrichtigungen.wegraeumen(Collections.singletonList(
				e("k1", PC, "ch.rosenweg4303.telefon", KANAL, null, false, false)));
		assertEquals(Collections.singletonList("k1"), weg);
	}

	@Test
	public void a15HinweisUndSammelmeldungWeg() {
		// A15 (Android 16): Hinweis id=6 + Sammelmeldung "Erteilte Berechtigungen" id=7
		List<String> weg = Benachrichtigungen.wegraeumen(Arrays.asList(
				e("k6", PC, "ch.rosenweg4303.telefon", KANAL, GRUPPE, false, false),
				e("k7", PC, null, KANAL, GRUPPE, true, false)));
		assertEquals(Arrays.asList("k6", "k7"), weg);
	}

	@Test
	public void fremdeAppBleibtSamtSammelmeldung() {
		List<String> weg = Benachrichtigungen.wegraeumen(Arrays.asList(
				e("k6", PC, "ch.rosenweg4303.telefon", KANAL, GRUPPE, false, false),
				e("k8", PC, "com.example.fremd", KANAL, GRUPPE, false, false),
				e("k7", PC, null, KANAL, GRUPPE, true, false)));
		assertEquals(Collections.singletonList("k6"), weg);
	}

	@Test
	public void andereKanaeleUndBleibendeBleiben() {
		List<String> weg = Benachrichtigungen.wegraeumen(Arrays.asList(
				e("a", PC, "ch.rosenweg4303.telefon", "location access", null, false, false),
				e("b", PC, "ch.rosenweg4303.telefon", KANAL, null, false, true),
				e("c", "com.example", "ch.rosenweg4303.telefon", KANAL, null, false, false)));
		assertTrue(weg.isEmpty());
	}

	@Test
	public void samsungEinrichtungWieBisher() {
		List<String> weg = Benachrichtigungen.wegraeumen(Arrays.asList(
				e("s", "com.android.settings", null, "samsung_account_push_x", null, false, false),
				e("t", "com.android.settings", null, "andere", null, false, false)));
		assertEquals(Collections.singletonList("s"), weg);
	}
}
