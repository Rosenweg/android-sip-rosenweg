package org.sipdroid.sipua.rosenweg;

/**
 * Entscheidungen fuer Hintergrund und Sperrbild ohne Android-Klassen (Unit-Tests in
 * app/src/test). Grundlage sind die Wallpaper-IDs (WallpaperManager.getWallpaperId): jede
 * Aenderung des Bildes gibt eine neue ID. Rosenweg Geraet merkt sich die IDs der Bilder, die
 * es selbst gesetzt (oder schon hochgeladen) hat - eine andere ID heisst "jemand anderes hat
 * das Bild geaendert". So loest das eigene Setzen nie ein Hochladen aus (keine Schleife).
 */
final class HintergrundRegel {
	private HintergrundRegel() {}

	enum Aktion { NICHTS, HOCHLADEN, STANDARD }

	/**
	 * Was mit dem Startbildschirm-Hintergrund (FLAG_SYSTEM) geschehen soll.
	 *
	 * @param angemeldet  jemand ist am Telefon angemeldet
	 * @param aktuell     Sitzungsmarke der angemeldeten Person ("" = niemand)
	 * @param sitzung     Sitzung, zu der das zuletzt von Geraet gesetzte/bekannte Bild gehoert
	 * @param idIst       getWallpaperId(FLAG_SYSTEM) jetzt
	 * @param idEigen     ID, die Geraet zuletzt gesetzt oder hochgeladen hat
	 */
	static Aktion system(boolean angemeldet, String aktuell, String sitzung, int idIst, int idEigen) {
		if (idIst == idEigen) return Aktion.NICHTS;
		if (!angemeldet) return Aktion.STANDARD;
		if (aktuell == null || aktuell.isEmpty()) return Aktion.NICHTS;   // Status der Sitzung noch nicht da
		// Erst hochladen, wenn das Bild der Person schon gesetzt ist; sonst koennte das Bild der
		// vorigen Sitzung (oder ein halbfertiger Wechsel) bei der neuen Person landen.
		return aktuell.equals(sitzung) ? Aktion.HOCHLADEN : Aktion.NICHTS;
	}

	/**
	 * Gehoert das Sperrbild (FLAG_LOCK) nicht mehr Geraet? Jede fremde ID, auch -1/0 (kein
	 * eigenes Sperrbild: "Start- und Sperrbildschirm" teilen sich ein Bild, z.B. Samsung mWhich=0x5)
	 * oder dieselbe ID wie der Startbildschirm.
	 */
	static boolean sperrbildFremd(int lockIst, int lockEigen, int systemIst) {
		if (lockIst <= 0) return true;
		if (lockIst == systemIst) return true;
		return lockIst != lockEigen;
	}

	/**
	 * Soll eine neue Fassung aus dem Status uebernommen werden? Nicht, wenn sie gleich der
	 * gesetzten ist, und nicht, wenn der Status noch die Fassung zeigt, die Geraet gerade eben
	 * durch ein Hochladen ersetzt hat (Status war schon unterwegs).
	 */
	static boolean statusAnwenden(String marke, String version, String sitzung, String versionGesetzt,
								  String versionVorher, long hochgeladenMs, long jetztMs) {
		if (marke.equals(sitzung) && version.equals(versionGesetzt)) return false;
		if (marke.equals(sitzung) && version.equals(versionVorher) && jetztMs - hochgeladenMs < 120_000) return false;
		return true;
	}

	/** Zielgroesse: Bildschirm abdecken (wie Android den Hintergrund zeigt), nie vergroessern. */
	static int[] zielGroesse(int breite, int hoehe, int schirmB, int schirmH) {
		if (breite <= 0 || hoehe <= 0) return new int[]{0, 0};
		int sb = Math.min(schirmB, schirmH), sh = Math.max(schirmB, schirmH);
		if (sb <= 0) return new int[]{breite, hoehe};
		double f = Math.max((double) sb / breite, (double) sh / hoehe);
		if (f >= 1) return new int[]{breite, hoehe};
		return new int[]{Math.max(1, (int) Math.round(breite * f)), Math.max(1, (int) Math.round(hoehe * f))};
	}

	/** inSampleSize (Zweierpotenz), so dass das Bild noch mindestens ziel gross bleibt. */
	static int teiler(int breite, int hoehe, int zielB, int zielH) {
		int t = 1;
		if (zielB <= 0 || zielH <= 0) return t;
		while (breite / (t * 2) >= zielB && hoehe / (t * 2) >= zielH) t *= 2;
		return t;
	}

	/** Schleifenschutz: hoechstens max Ereignisse je Fenster (ms). */
	static final class Bremse {
		private final int max;
		private final long fensterMs;
		private final long[] zeiten;
		private int n;

		Bremse(int max, long fensterMs) {
			this.max = max;
			this.fensterMs = fensterMs;
			this.zeiten = new long[max];
		}

		synchronized boolean darf(long jetztMs) {
			int behalten = 0;
			for (int i = 0; i < n; i++) if (jetztMs - zeiten[i] < fensterMs) zeiten[behalten++] = zeiten[i];
			n = behalten;
			if (n >= max) return false;
			zeiten[n++] = jetztMs;
			return true;
		}
	}
}
