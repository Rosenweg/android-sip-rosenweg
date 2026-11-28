package org.sipdroid.sipua.rosenweg;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ./gradlew testDebugUnitTest --tests '*HintergrundRegelTest' */
public class HintergrundRegelTest {
	private static final String ANNA = "anna@example.org|2026-11-27 10:00:00";
	private static final String BERT = "bert@example.org|2026-11-27 11:00:00";

	@Test
	public void eigenesSetzenLoestNichtsAus() {
		// Geraet hat ID 7 gesetzt und gemerkt: das Ereignis dazu laedt nichts hoch (keine Schleife)
		assertEquals(HintergrundRegel.Aktion.NICHTS, HintergrundRegel.system(true, ANNA, ANNA, 7, 7));
		assertEquals(HintergrundRegel.Aktion.NICHTS, HintergrundRegel.system(false, "", "", 7, 7));
	}

	@Test
	public void personSetztNeuesBild() {
		assertEquals(HintergrundRegel.Aktion.HOCHLADEN, HintergrundRegel.system(true, ANNA, ANNA, 8, 7));
	}

	@Test
	public void vorDemBildDerPersonNichtsHochladen() {
		// Bert ist angemeldet, Geraet hat sein Bild noch nicht gesetzt (sitzung = Anna)
		assertEquals(HintergrundRegel.Aktion.NICHTS, HintergrundRegel.system(true, BERT, ANNA, 8, 7));
		assertEquals(HintergrundRegel.Aktion.NICHTS, HintergrundRegel.system(true, "", ANNA, 8, 7));
	}

	@Test
	public void abgemeldetZurueckAufStandard() {
		assertEquals(HintergrundRegel.Aktion.STANDARD, HintergrundRegel.system(false, "", "", 8, 7));
	}

	@Test
	public void sperrbildFremd() {
		assertFalse(HintergrundRegel.sperrbildFremd(12, 12, 7));
		assertTrue(HintergrundRegel.sperrbildFremd(13, 12, 7));    // Galerie "Sperrbildschirm"
		assertTrue(HintergrundRegel.sperrbildFremd(-1, 12, 7));    // "Start- und Sperrbildschirm": geteilt
		assertTrue(HintergrundRegel.sperrbildFremd(7, 7, 7));      // geteilt, gleiche ID wie System
		assertTrue(HintergrundRegel.sperrbildFremd(5, Integer.MIN_VALUE, 7));   // nach dem Update: einmal neu
	}

	@Test
	public void statusNachEigenemHochladenIgnorieren() {
		long jetzt = 1_000_000;
		// Status zeigt noch die alte Fassung "a", eben durch "b" ersetzt
		assertFalse(HintergrundRegel.statusAnwenden(ANNA, "a", ANNA, "b", "a", jetzt - 5000, jetzt));
		// gleiche Fassung: nichts
		assertFalse(HintergrundRegel.statusAnwenden(ANNA, "b", ANNA, "b", "a", jetzt - 5000, jetzt));
		// auf einem anderen Telefon geaendert / auf Mein Telefon zurueckgesetzt
		assertTrue(HintergrundRegel.statusAnwenden(ANNA, "c", ANNA, "b", "a", jetzt - 5000, jetzt));
		assertTrue(HintergrundRegel.statusAnwenden(ANNA, "", ANNA, "b", "a", jetzt - 5000, jetzt));
		// neue Sitzung: immer
		assertTrue(HintergrundRegel.statusAnwenden(BERT, "", ANNA, "", "", 0, jetzt));
		assertTrue(HintergrundRegel.statusAnwenden(ANNA, "a", null, "", "", 0, jetzt));
	}

	@Test
	public void zielGroesse() {
		// 4000x3000 Foto auf 720x1600: deckt ab (Hoehe 1600), nie kleiner als der Schirm
		assertArrayEquals(new int[]{2133, 1600}, HintergrundRegel.zielGroesse(4000, 3000, 720, 1600));
		assertArrayEquals(new int[]{720, 1600}, HintergrundRegel.zielGroesse(1440, 3200, 1600, 720));
		// kleiner als der Schirm: nicht vergroessern
		assertArrayEquals(new int[]{500, 800}, HintergrundRegel.zielGroesse(500, 800, 720, 1600));
	}

	@Test
	public void teiler() {
		assertEquals(1, HintergrundRegel.teiler(1439, 3199, 720, 1600));
		assertEquals(2, HintergrundRegel.teiler(1440, 3200, 720, 1600));
		assertEquals(2, HintergrundRegel.teiler(1441, 3201, 720, 1600));
		assertEquals(4, HintergrundRegel.teiler(4000, 6400, 720, 1600));
	}

	@Test
	public void bremse() {
		HintergrundRegel.Bremse b = new HintergrundRegel.Bremse(3, 600_000);
		assertTrue(b.darf(0));
		assertTrue(b.darf(1));
		assertTrue(b.darf(2));
		assertFalse(b.darf(3));
		assertTrue(b.darf(600_001));
	}
}
