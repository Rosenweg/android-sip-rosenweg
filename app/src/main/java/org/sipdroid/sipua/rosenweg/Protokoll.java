package org.sipdroid.sipua.rosenweg;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Protokoll zur Anlage ("logcat von geteilten Phones gehoert zurueck ins System", 28.11.2026).
 *
 * Liest die Rosenweg-Zeilen aus dem eigenen logcat (ohne Berechtigung nur die der eigenen App),
 * dazu Systemzeilen zu HOME, Launcher und Installationen ueber den eigenen adb-Zugang, und
 * schickt sie gebuendelt an POST /api/telefon/&lt;ext&gt;/log. Vorher werden Telefonnummern,
 * Mailadressen, lange Schluessel und der Name der angemeldeten Person unkenntlich gemacht.
 * Nicht erreichbar: die Zeilen bleiben in einem Ringpuffer auf dem Geraet (hoechstens 2 MB).
 */
public final class Protokoll {
	private static final String TAG = "RosenwegProtokoll";
	static final long PUFFER_MAX = 2L * 1024 * 1024;
	private static final int PRO_SENDUNG = 400;
	private static final long SYSTEM_ALLE_MS = 5 * 60_000L;
	private static final String PREF = "rosenweg_protokoll";

	/** Systemzeilen, die fuer HOME/Launcher und Installationen der eigenen Pakete von Belang sind. */
	static final String SYSTEM_FILTER = "ch\\.rosenweg4303|com\\.sec\\.android\\.app\\.launcher|CATEGORY_HOME|"
			+ "Background execution not allowed|PackageInstaller|PackageManager.*(replac|install|Package)|"
			+ "ActivityTaskManager.*(HOME|Launcher)|ROLE_HOME|FATAL EXCEPTION";

	private static final Object SPERRE = new Object();
	private static long systemZuletzt;

	private Protokoll() {}

	// ─── Unkenntlich machen (rein, getestet) ──────────────────────────────────

	private static final Pattern MAIL = Pattern.compile("[\\w.+%-]+@[\\w-]+(\\.[\\w-]+)+");
	private static final Pattern NUMMER = Pattern.compile("\\+?\\d(?:[ ./-]?\\d){8,}");
	private static final Pattern LANG = Pattern.compile("[A-Za-z0-9_\\-+=]{32,}");
	private static final Pattern GEHEIM = Pattern.compile(
			"(?i)(passwor[dt]|secret|token|pin|authorization|bearer|psk)([\"'=: ]+)([^\\s\"',;]+)");
	private static final Pattern DATUM = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}.*");

	/** Entfernt Personendaten aus einer Zeile. {@code name} und {@code nummer}: die angemeldete Person. */
	static String maskieren(String text, String name, String nummer) {
		if (text == null) return "";
		String s = text;
		if (name != null && name.trim().length() >= 3) {
			for (String teil : name.trim().split("\\s+")) {
				if (teil.length() >= 3) s = s.replaceAll("(?i)" + Pattern.quote(teil), "<name>");
			}
		}
		if (nummer != null && nummer.trim().length() >= 3) s = s.replace(nummer.trim(), "<nr>");
		s = MAIL.matcher(s).replaceAll("<mail>");
		s = GEHEIM.matcher(s).replaceAll("$1$2<geheim>");
		s = LANG.matcher(s).replaceAll("<geheim>");
		s = ersetzenAusser(NUMMER, s, "<nr>");
		return s;
	}

	/** Datumsangaben (2026-11-28 09:04) bleiben stehen. */
	private static String ersetzenAusser(Pattern p, String s, String ersatz) {
		Matcher m = p.matcher(s);
		StringBuffer b = new StringBuffer();
		while (m.find()) {
			String t = m.group();
			m.appendReplacement(b, Matcher.quoteReplacement(DATUM.matcher(t).matches() ? t : ersatz));
		}
		m.appendTail(b);
		return b.toString();
	}

	/** Eine Zeile im Format "logcat -v threadtime" -> Datensatz, oder null. */
	static JSONObject zeile(String roh, long jetzt, String name, String nummer) {
		Matcher m = THREADTIME.matcher(roh);
		if (!m.matches()) return null;
		try {
			return new JSONObject()
					.put("t", epoch(m.group(1), jetzt))
					.put("stufe", m.group(2))
					.put("tag", m.group(3).trim())
					.put("text", maskieren(m.group(4), name, nummer));
		} catch (Exception e) {
			return null;
		}
	}

	private static final Pattern THREADTIME = Pattern.compile(
			"^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+(.+?)\\s*: (.*)$");

	/** "MM-dd HH:mm:ss.SSS" (ohne Jahr) -> Millisekunden; Jahr der aktuellen Zeit, bei Zukunft das Vorjahr. */
	static long epoch(String s, long jetzt) {
		Calendar c = Calendar.getInstance();
		c.setTimeInMillis(jetzt);
		int jahr = c.get(Calendar.YEAR);
		c.clear();
		c.set(jahr, Integer.parseInt(s.substring(0, 2)) - 1, Integer.parseInt(s.substring(3, 5)),
				Integer.parseInt(s.substring(6, 8)), Integer.parseInt(s.substring(9, 11)),
				Integer.parseInt(s.substring(12, 14)));
		c.set(Calendar.MILLISECOND, Integer.parseInt(s.substring(15, 18)));
		long t = c.getTimeInMillis();
		if (t > jetzt + 86_400_000L) {
			c.add(Calendar.YEAR, -1);
			t = c.getTimeInMillis();
		}
		return t;
	}

	static boolean eigenesTag(String tag) {
		return tag.startsWith("Rosenweg") || tag.equals("StartReceiver") || tag.equals("Bildschirmsperre")
				|| tag.equals("AndroidRuntime");
	}

	// ─── Ringpuffer ────────────────────────────────────────────────────────────

	private static File datei(Context c) {
		return new File(c.getFilesDir(), "protokoll.jsonl");
	}

	/** Haengt Datensaetze an; ueber 2 MB faellt die aeltere Haelfte weg. */
	static void anhaengen(Context c, List<JSONObject> neu) {
		if (neu.isEmpty()) return;
		synchronized (SPERRE) {
			try {
				File f = datei(c);
				try (FileOutputStream o = new FileOutputStream(f, true)) {
					for (JSONObject j : neu) o.write((j.toString() + "\n").getBytes(StandardCharsets.UTF_8));
				}
				if (f.length() > PUFFER_MAX) kuerzen(f);
			} catch (Exception e) {
				Log.w(TAG, "Puffer: " + e);
			}
		}
	}

	private static void kuerzen(File f) throws Exception {
		List<String> alle = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(new FileReader(f))) {
			String l;
			while ((l = r.readLine()) != null) alle.add(l);
		}
		try (FileOutputStream o = new FileOutputStream(f, false)) {
			for (int i = alle.size() / 2; i < alle.size(); i++) o.write((alle.get(i) + "\n").getBytes(StandardCharsets.UTF_8));
		}
	}

	// ─── Sammeln ───────────────────────────────────────────────────────────────

	private static SharedPreferences prefs(Context c) {
		return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
	}

	/** Neue Zeilen aus logcat (eigene Tags, ab Stufe I) und, alle 5 Minuten, Systemzeilen. Blockiert. */
	static void sammeln(Context c) {
		long jetzt = System.currentTimeMillis();
		String name = Anlage.name(c), nummer = Anlage.nummer(c);
		List<JSONObject> neu = new ArrayList<>();
		String zuletzt = prefs(c).getString("eigen_zeit", "");
		String aus = logcat("logcat -d -v threadtime -t 1500 *:I");
		String letzte = zuletzt;
		for (String l : aus.split("\n")) {
			if (l.length() < 20) continue;
			String z = l.substring(0, 18);
			if (z.compareTo(zuletzt) <= 0 && !(zuletzt.startsWith("12-") && z.startsWith("01-"))) continue;
			JSONObject j = zeile(l, jetzt, name, nummer);
			if (j == null) continue;
			if (eigenesTag(j.optString("tag"))) neu.add(j);
			if (z.compareTo(letzte) > 0) letzte = z;
		}
		if (!letzte.equals(zuletzt)) prefs(c).edit().putString("eigen_zeit", letzte).apply();
		if (jetzt - systemZuletzt >= SYSTEM_ALLE_MS) {
			systemZuletzt = jetzt;
			String sz = prefs(c).getString("system_zeit", "");
			String sletzte = sz;
			for (String l : AdbSelbst.logcatSystem(c, SYSTEM_FILTER).split("\n")) {
				if (l.length() < 20) continue;
				String z = l.substring(0, 18);
				if (z.compareTo(sz) <= 0 && !(sz.startsWith("12-") && z.startsWith("01-"))) continue;
				JSONObject j = zeile(l, jetzt, name, nummer);
				if (j == null) continue;
				try { j.put("tag", "System/" + j.optString("tag")); } catch (Exception ignored) { }
				neu.add(j);
				if (z.compareTo(sletzte) > 0) sletzte = z;
			}
			if (!sletzte.equals(sz)) prefs(c).edit().putString("system_zeit", sletzte).apply();
		}
		anhaengen(c, neu);
	}

	private static String logcat(String befehl) {
		try {
			Process p = Runtime.getRuntime().exec(befehl.split(" "));
			StringBuilder sb = new StringBuilder();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String l;
				while ((l = r.readLine()) != null && sb.length() < 600_000) sb.append(l).append('\n');
			}
			p.destroy();
			return sb.toString();
		} catch (Exception e) {
			Log.w(TAG, "logcat: " + e);
			return "";
		}
	}

	// ─── Senden ────────────────────────────────────────────────────────────────

	/** Alles Gesammelte an die Anlage. Gelingt das nicht, bleibt es im Puffer. Blockiert. */
	static void senden(Context c) {
		if (!Anlage.eingerichtet(c)) return;
		synchronized (SPERRE) {
			File f = datei(c);
			if (!f.exists() || f.length() == 0) return;
			try {
				List<String> alle = new ArrayList<>();
				try (BufferedReader r = new BufferedReader(new FileReader(f))) {
					String l;
					while ((l = r.readLine()) != null) alle.add(l);
				}
				int pos = 0;
				while (pos < alle.size()) {
					int bis = Math.min(alle.size(), pos + PRO_SENDUNG);
					JSONArray a = new JSONArray();
					for (int i = pos; i < bis; i++) {
						try { a.put(new JSONObject(alle.get(i))); } catch (Exception ignored) { }
					}
					if (a.length() > 0) Anlage.protokollSenden(c, a);
					pos = bis;
					List<String> rest = alle.subList(pos, alle.size());
					try (FileOutputStream o = new FileOutputStream(f, false)) {
						for (String l : rest) o.write((l + "\n").getBytes(StandardCharsets.UTF_8));
					}
				}
			} catch (Exception e) {
				Log.w(TAG, "senden: " + e);   // bleibt im Puffer
			}
		}
	}

	/** Absturz: sofort in den Puffer und, kurz, an die Anlage. Aus dem Uncaught-Exception-Handler. */
	public static void absturz(Context c, Thread t, Throwable e) {
		try {
			java.io.StringWriter sw = new java.io.StringWriter();
			e.printStackTrace(new java.io.PrintWriter(sw));
			String s = sw.toString();
			if (s.length() > 3000) s = s.substring(0, 3000);
			List<JSONObject> l = new ArrayList<>();
			l.add(new JSONObject().put("t", System.currentTimeMillis()).put("stufe", "F").put("tag", "Absturz")
					.put("text", maskieren("Thread " + t.getName() + ": " + s, Anlage.name(c), Anlage.nummer(c))));
			anhaengen(c, l);
			final Context app = c.getApplicationContext();
			Thread s2 = new Thread(() -> senden(app), "ProtokollAbsturz");
			s2.start();
			s2.join(3000);
		} catch (Throwable ignored) {
			// der Handler darf nie selbst scheitern
		}
	}
}
