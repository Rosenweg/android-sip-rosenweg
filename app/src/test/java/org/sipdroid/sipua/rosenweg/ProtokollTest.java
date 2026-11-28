package org.sipdroid.sipua.rosenweg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProtokollTest {
	@Test
	public void nummernUndMailsWeg() {
		String s = Protokoll.maskieren("Anruf von +41 79 123 45 67 an sip:stefan@rosenweg4303.ch fertig", null, null);
		assertFalse(s, s.contains("123 45"));
		assertFalse(s, s.contains("stefan@"));
		assertTrue(s, s.contains("<nr>") && s.contains("<mail>"));
	}

	@Test
	public void nameDerPersonWeg() {
		String s = Protokoll.maskieren("Angemeldet: Stefan Mueller (Nr 4303)", "Stefan Mueller", "");
		assertFalse(s, s.toLowerCase().contains("stefan"));
		assertFalse(s, s.contains("Mueller"));
	}

	@Test
	public void geheimnisseWeg() {
		String s = Protokoll.maskieren("password=hunter2 Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123456789ABCD", null, null);
		assertFalse(s, s.contains("hunter2"));
		assertFalse(s, s.contains("abcdefghijklmnop"));
	}

	@Test
	public void technischesBleibt() {
		String s = Protokoll.maskieren("HOME zeigte nicht auf den Rosenweg Launcher (com.sec.android.app.launcher) - neu gesetzt, versionCode=2101 ch.rosenweg4303.home 2026-11-28 09:04", null, null);
		assertEquals("HOME zeigte nicht auf den Rosenweg Launcher (com.sec.android.app.launcher) - neu gesetzt, versionCode=2101 ch.rosenweg4303.home 2026-11-28 09:04", s);
	}

	@Test
	public void zeitOhneJahr() {
		long jetzt = java.time.ZonedDateTime.of(2026, 11, 28, 12, 0, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		long t = Protokoll.epoch("11-28 09:04:12.345", jetzt);
		assertEquals(jetzt - (2 * 3600 + 55 * 60 + 48) * 1000L + 345, t);
		long vorjahr = Protokoll.epoch("12-31 23:00:00.000", jetzt);
		assertTrue(vorjahr < jetzt - 300L * 86400 * 1000);
	}

	@Test
	public void eigeneTags() {
		assertTrue(Protokoll.eigenesTag("RosenwegSperre"));
		assertTrue(Protokoll.eigenesTag("AndroidRuntime"));
		assertFalse(Protokoll.eigenesTag("chatty"));
	}
}
