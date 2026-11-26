package org.sipdroid.sipua.rosenweg;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import org.sipdroid.sipua.R;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.journeyapps.barcodescanner.camera.CameraSettings;

import org.json.JSONObject;

import java.util.Collections;

/**
 * Sperrbildschirm des geteilten Telefons: Kamera fuer den Anmelde-QR, NFC-Karte, Notruf.
 *
 * Die Person oeffnet rosenweg4303.ch/smartphones auf dem EIGENEN Handy, meldet
 * sich mit dem Rosenweg-Konto an und haelt den dort angezeigten QR vor die
 * Kamera. Auf diesem Geraet wird nichts eingegeben.
 *
 * Oder: die eigene Rosenweg-Karte (UniFi Access, dieselbe wie fuer die Tueren) hinten
 * ans Telefon halten. Solange der Sperrbildschirm vorne ist, liest er Karten selbst im
 * Reader-Modus (NfcKarte.anmelden).
 */
public class SperrActivity extends Activity {
	private static final String TAG = "RosenwegSperre";
	private static final String QR_PRAEFIX = "RWTEL1:";
	private static final long ABFRAGE_MS = 5000;
	private static final int RECHT_KAMERA = 43;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private BarcodeView kamera;
	private TextView meldung;
	private volatile boolean laeuft;
	private volatile boolean prueft;     // Status-Abfrage laeuft
	private volatile boolean loestEin;   // gescannter QR wird eingeloest
	private String zuletztGescannt;
	private long zuletztGescanntUm;
	private View suchEbene;
	static volatile SperrActivity aktiv;

	/** Suche im Sperrbildschirm zeigen; false, wenn er nicht laeuft (dann eigenes Suchfenster). */
	static boolean zeigeSuche() {
		SperrActivity a = aktiv;
		if (a == null || a.isFinishing()) return false;
		a.runOnUiThread(() -> { if (a.suchEbene != null) a.suchEbene.setVisibility(View.VISIBLE); });
		return true;
	}

	static void versteckeSuche() {
		SperrActivity a = aktiv;
		if (a != null) a.runOnUiThread(() -> { if (a.suchEbene != null) a.suchEbene.setVisibility(View.GONE); });
	}

	@Override
	protected void onDestroy() {
		if (aktiv == this) aktiv = null;
		super.onDestroy();
	}

	private boolean frontkamera = false;   // hintere Kamera; die vordere war nur fuer den Emulator
	// Kamera nur auf Wunsch: laeuft die hintere Kamera, liest das A15 keine NFC-Karten
	// (Antenne neben der Kamera). Standard ist darum der Kartenleser.
	private boolean kameraAn;
	private View kameraRahmen;
	private TextView kartenHinweis;
	private View logo;
	private Button qrKnopf, wechselKnopf, kontoKnopf;
	private TextView anmeldeHinweis;
	private TextView kopfText;
	private LinearLayout schrittListe;
	private android.widget.ScrollView schrittRahmen;
	private LinearLayout ablaufKnoepfe;
	private Button nochmalsKnopf, abbrechenKnopf;
	private View obenPlatz, untenPlatz;
	private LinearLayout pinSeite;
	private android.widget.ScrollView pinRollen;
	private final java.util.List<TextView> schrittZeilen = new java.util.ArrayList<>();
	private volatile boolean ablaufAktiv;
	/** Anlage erreichbar? Nur dann Anmelde-Moeglichkeiten zeigen (Stefan 25.11.2026). */
	private volatile boolean anlageDa;
	private int fehlversuche;
	private final Runnable kameraZeitAus = this::kameraStoppen;
	private static final long KAMERA_MS = 60_000;

	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		if (Build.VERSION.SDK_INT >= 27) {
			setShowWhenLocked(true);
			setTurnScreenOn(true);
		}
		// Ebene fuer "Telefon suchen" ueber dem Sperrbildschirm (ohne Aufgabenwechsel, der
		// Kiosk-Modus bleibt dicht; ein eigenes Fenster wurde hier alle paar Sekunden verdraengt)
		android.widget.FrameLayout wurzel = new android.widget.FrameLayout(this);
		wurzel.addView(Raender.freihalten(baueOberflaeche()));
		suchEbene = SuchAktivitaet.ansicht(this, () -> {
			suchEbene.setVisibility(View.GONE);
			Suchen.gefunden(getApplicationContext());
		});
		suchEbene.setVisibility(View.GONE);
		wurzel.addView(suchEbene, new android.widget.FrameLayout.LayoutParams(
				android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
		setContentView(wurzel);
		aktiv = this;
		// Sicherstellen, dass die SIP-Engine laeuft (startet sie, falls noch nicht).
		org.sipdroid.sipua.ui.Receiver.engine(this);
		Katalog.vielleichtAbgleichen(this, false);
	}

	@Override
	protected void onResume() {
		super.onResume();
		// Neustart: Sitzung zuerst lokal beenden (sperren), bevor hier irgendetwas freigibt
		Neustart.abschliessen(this);
		// Einrichtung beim Anmelden laeuft (oder wartet auf "Nochmals versuchen"): sie gehoert der Bildschirm
		if (anmeldungLaeuft) return;
		// Zurueck aus dem Anmeldefenster und schon angemeldet: nicht wieder sperren.
		if (!netzWartet && Anlage.angemeldet(this)) {
			entsperrt(Anlage.name(this), null);
			return;
		}
		Sperre.grundregeln(this);
		if (Sperre.istDeviceOwner(this) && !imLockTask()) {
			try {
				startLockTask();
			} catch (Exception e) {
				Log.w(TAG, "startLockTask: " + e.getMessage());
			}
		}
		laeuft = true;
		kameraAnzeigen(false);
		if (!netzWartet && (Sperre.abmeldungOffen(this) || Wlan.wechselOffen(this, false))) {
			// Stefan 25.11.2026: nach dem Abmelden jeden Schritt pruefen (Anlage, Apps, PIN, Uploads,
			// WLAN, Startseite); Karte und QR erst, wenn alles sauber ist.
			abmeldenPruefen();
		} else if (!netzWartet) {
			kartenleserEin();
		}
		handler.post(takt);
	}

	/** Abmelde-Pruefung laeuft (inkl. WLAN-Wechsel), Kartenleser erst danach. */
	private volatile boolean netzWartet;
	/** Einrichtung beim Anmelden laeuft oder steht bei einem Fehler. */
	private volatile boolean anmeldungLaeuft;

	@Override
	protected void onPause() {
		laeuft = false;
		handler.removeCallbacks(takt);
		handler.removeCallbacks(kameraZeitAus);
		kamera.pause();
		kameraAnzeigen(false);
		kartenleserAus();
		super.onPause();
	}

	// Fokus zurueck (z.B. nach Samsungs Ausschaltmenue): Android gibt dabei den Lesemodus frei und
	// nimmt unseren mit (Stefan 25.11.2026: "komme nicht mehr mit dem Chip rein"). Kurz danach neu ein.
	private final Runnable leserNeu = () -> { if (laeuft && !kameraAn && !netzWartet) kartenleserEin(); };

	@Override
	public void onWindowFocusChanged(boolean fokus) {
		super.onWindowFocusChanged(fokus);
		handler.removeCallbacks(leserNeu);
		if (fokus) handler.postDelayed(leserNeu, 600);
	}

	/**
	 * Anmelde-Moeglichkeiten (Karte, QR, Rosenweg-Konto) nur zeigen, wenn die Anlage erreichbar ist
	 * (Stefan 25.11.2026). Notruf bleibt immer.
	 */
	private void anmeldenZeigen(boolean an, String meldungText) {
		anlageDa = an;
		if (ablaufAktiv) return;   // waehrend An-/Abmelden zeigt der zweite Bildschirm nur die Schritte
		int sicht = an ? View.VISIBLE : View.GONE;
		if (kartenHinweis != null) kartenHinweis.setVisibility(sicht);
		if (anmeldeHinweis != null) anmeldeHinweis.setVisibility(sicht);
		if (kontoKnopf != null) kontoKnopf.setVisibility(sicht);
		if (qrKnopf != null) qrKnopf.setVisibility(sicht);
		if (!an) {
			if (kameraAn) kameraStoppen();
			if (wechselKnopf != null) wechselKnopf.setVisibility(View.GONE);
			kartenleserAus();
			if (meldungText != null) zeige(meldungText, true);
		} else {
			zeige("", false);
			if (laeuft && !kameraAn && !netzWartet) kartenleserEin();
		}
	}

	// ─── Einrichtungsbildschirm beim An-/Abmelden ───────────────────────────
	//
	// Stefan 25.11.2026: "Ein Einrichtungsschirm, der prueft, ob die einzelnen User-Sachen sauber
	// geladen werden - und nicht weitermacht, wenn der letzte Schritt nicht erfolgreich war. Beim
	// Abmelden das gleiche." Die Schritte laufen nacheinander; jeder fuehrt aus und prueft danach.
	// Pflicht-Schritt gescheitert: anhalten, rot mit Ursache, "Nochmals versuchen" (ab diesem
	// Schritt) und beim Anmelden "Abbrechen". Andere Schritte: gelb mit Ursache und weiter.

	static final int LAEUFT = 0, OK = 1, FEHLER = 2, WARNUNG = 3;

	/** Ergebnis eines Schritts. danach != null: Ablauf hier still beenden und das ausfuehren (UI-Thread). */
	static final class Ergebnis {
		final int stand;
		final String text;
		Runnable danach;

		Ergebnis(int stand, String text) {
			this.stand = stand;
			this.text = text;
		}
	}

	static Ergebnis ok(String t) { return new Ergebnis(OK, t); }
	static Ergebnis warnung(String t) { return new Ergebnis(WARNUNG, t); }
	static Ergebnis fehler(String t) { return new Ergebnis(FEHLER, t); }

	/** Ausfuehren und pruefen; laeuft im Ablauf-Thread, darf blockieren. i = Zeile fuer Zwischenstaende. */
	interface Ausfuehren {
		Ergebnis lauf(int i) throws Exception;
	}

	static final class Schritt {
		final String titel;
		final boolean pflicht;
		final Ausfuehren tun;

		Schritt(String titel, boolean pflicht, Ausfuehren tun) {
			this.titel = titel;
			this.pflicht = pflicht;
			this.tun = tun;
		}
	}

	private static String[] titel(Schritt[] s) {
		String[] t = new String[s.length];
		for (int i = 0; i < s.length; i++) t[i] = s[i].titel;
		return t;
	}

	/** Kurze Ursache fuer die Anzeige (keine Geheimnisse, keine Stapel). */
	static String ursache(Exception e) {
		if (e instanceof Anlage.AnlageFehler) return e.getMessage();
		if (e instanceof java.io.IOException) return "keine Verbindung zur Telefonanlage";
		String m = e.getMessage();
		return e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
	}

	/**
	 * Schritte ab [ab] nacheinander ausfuehren. Alles gruen/gelb: [fertig] (UI-Thread).
	 * Pflicht rot: anhalten mit "Nochmals versuchen" und, falls [abbrechen] != null, "Abbrechen".
	 */
	private void ablaufLaufen(String name, Schritt[] s, int ab, Runnable fertig, Runnable abbrechen) {
		runOnUiThread(() -> ablaufKnoepfe.setVisibility(View.GONE));
		new Thread(() -> {
			for (int i = ab; i < s.length; i++) {
				if (isFinishing()) return;
				schritt(i, LAEUFT, null);
				Ergebnis e;
				try {
					e = s[i].tun.lauf(i);
				} catch (Exception x) {
					Log.w(TAG, name + ": " + s[i].titel + ": " + x);
					e = fehler(ursache(x));
				}
				if (e == null) e = ok(null);
				if (e.danach != null) {
					schritt(i, e.stand, e.text);
					runOnUiThread(e.danach);
					return;
				}
				if (e.stand == FEHLER && s[i].pflicht) {
					Log.w(TAG, name + ": angehalten bei \"" + s[i].titel + "\": " + e.text);
					schritt(i, FEHLER, e.text);
					final int bei = i;
					runOnUiThread(() -> knoepfeZeigen(() -> ablaufLaufen(name, s, bei, fertig, abbrechen), abbrechen));
					return;
				}
				int stand = e.stand == FEHLER ? WARNUNG : e.stand;
				Log.i(TAG, name + ": " + s[i].titel + (stand == OK ? " ok" : " WARNUNG") + (e.text != null ? " (" + e.text + ")" : ""));
				schritt(i, stand, e.text);
			}
			runOnUiThread(fertig);
		}, name).start();
	}

	private void knoepfeZeigen(Runnable nochmals, Runnable abbrechen) {
		nochmalsKnopf.setOnClickListener(v -> {
			ablaufKnoepfe.setVisibility(View.GONE);
			nochmals.run();
		});
		abbrechenKnopf.setVisibility(abbrechen != null ? View.VISIBLE : View.GONE);
		abbrechenKnopf.setOnClickListener(v -> {
			ablaufKnoepfe.setVisibility(View.GONE);
			if (abbrechen != null) abbrechen.run();
		});
		ablaufKnoepfe.setVisibility(View.VISIBLE);
	}

	/** Titel + Schritte zeigen; Hinweise und Anmelde-Knoepfe weg (Notruf bleibt). */
	private void ablaufStarten(String titel, String... schritte) {
		runOnUiThread(() -> {
			ablaufAktiv = true;
			kopfText.setText(titel);
			kopfText.setTextSize(26);
			kopfText.setTextColor(Color.WHITE);
			for (View v : new View[]{kartenHinweis, anmeldeHinweis, kontoKnopf, qrKnopf, wechselKnopf, meldung,
					obenPlatz, untenPlatz, ablaufKnoepfe, pinSeite}) {
				if (v != null) v.setVisibility(View.GONE);
			}
			logoGroesse(110);
			schrittListe.removeAllViews();
			schrittZeilen.clear();
			for (String t : schritte) {
				TextView z = text(17, Color.parseColor("#BFDBFE"), false);
				z.setGravity(Gravity.START);
				z.setPadding(0, dp(5), 0, dp(5));
				z.setText("○  " + t);
				z.setTag(t);
				schrittListe.addView(z);
				schrittZeilen.add(z);
			}
			schrittRahmen.scrollTo(0, 0);
			schrittRahmen.setVisibility(View.VISIBLE);
		});
	}

	private void logoGroesse(int dpGross) {
		if (logo == null) return;
		android.view.ViewGroup.LayoutParams p = logo.getLayoutParams();
		p.width = dp(dpGross);
		p.height = dp(dpGross);
		logo.setLayoutParams(p);
	}

	/** Stand eines Schritts; bei FEHLER/WARNUNG mit kurzer Erklaerung darunter. */
	private void schritt(int i, int stand, String erklaerung) {
		runOnUiThread(() -> {
			if (i < 0 || i >= schrittZeilen.size()) return;
			TextView z = schrittZeilen.get(i);
			String t = (String) z.getTag();
			String zeichen = stand == LAEUFT ? "⏳" : stand == OK ? "✓" : stand == WARNUNG ? "⚠" : "✗";
			int farbe = stand == LAEUFT ? Color.WHITE : stand == OK ? Color.parseColor("#BBF7D0")
					: stand == WARNUNG ? Color.parseColor("#FDE68A") : Color.parseColor("#FCA5A5");
			z.setText(zeichen + "  " + t + (erklaerung == null || erklaerung.isEmpty() ? "" : "\n     " + erklaerung));
			z.setTextColor(farbe);
			z.setTypeface(null, stand == LAEUFT ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
			// aktuelle Zeile sichtbar halten (8 Schritte mit Erklaerungen passen nicht immer)
			schrittRahmen.post(() -> schrittRahmen.smoothScrollTo(0, Math.max(0, z.getTop() - dp(60))));
		});
	}

	/** Zurueck zum normalen Sperrbildschirm (nach einem Fehler oder nach dem Abmelden). */
	private void ablaufEnde() {
		runOnUiThread(() -> {
			ablaufAktiv = false;
			schrittRahmen.setVisibility(View.GONE);
			ablaufKnoepfe.setVisibility(View.GONE);
			pinSeite.setVisibility(View.GONE);
			obenPlatz.setVisibility(View.VISIBLE);
			untenPlatz.setVisibility(View.VISIBLE);
			logoGroesse(230);
			kopfText.setText("Gesperrt · zum Benutzen anmelden");
			kopfText.setTextSize(15);
			kopfText.setTextColor(Color.parseColor("#BFDBFE"));
			anmeldenZeigen(anlageDa, anlageDa ? null
					: "Keine Verbindung zur Telefonanlage – Anmelden ist gerade nicht möglich.\nNotrufe gehen immer.");
		});
	}

	private static final String[] ANMELDE_SCHRITTE = {"Anmeldung bei der Telefonanlage", "Dein WLAN und die Telefonanlage",
			"Apps freigeben", "Rechte einrichten", "Persönliches Telefonkonto", "Nextcloud-Zugang",
			"Persönliche PIN", "Startseite laden"};

	private static final String[] ABMELDE_SCHRITTE = {"Abgemeldet an der Telefonanlage", "Persönliche Apps gesperrt",
			"Persönliche PIN entfernt", "Uploads der Person", "Allgemeines WLAN und Telefonanlage",
			"Startseite auf Standard"};

	/** Anmeldung gescheitert (Karte/QR): Schritt rot, nach 8 s zurueck zum Sperrbildschirm. */
	private void anmeldungFehler(int schrittNr, String meldungText) {
		schritt(schrittNr, FEHLER, meldungText + " – bitte nochmals versuchen.");
		handler.postDelayed(() -> {
			ablaufEnde();
			laeuft = true;
			if (!kameraAn && !netzWartet) kartenleserEin();
		}, 8000);
	}

	// ─── Pruefungen ─────────────────────────────────────────────────────────

	private static String appName(Context c, String paket) {
		try {
			int f = Build.VERSION.SDK_INT >= 24 ? PackageManager.MATCH_UNINSTALLED_PACKAGES : 0;
			return String.valueOf(c.getPackageManager().getApplicationLabel(
					c.getPackageManager().getApplicationInfo(paket, f))).replace("Rosenweg ", "");
		} catch (Exception e) {
			return paket;
		}
	}

	private static String namen(Context c, java.util.List<String> pakete) {
		StringBuilder sb = new StringBuilder();
		for (String p : pakete) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(appName(c, p));
		}
		return sb.toString();
	}

	private static boolean geraetGesichert(Context c) {
		KeyguardManager k = c.getSystemService(KeyguardManager.class);
		return k != null && Build.VERSION.SDK_INT >= 23 && k.isDeviceSecure();
	}

	/** Persoenliche Katalog-Apps, die abgemeldet versteckt sein muessten, aber sichtbar sind. */
	private static java.util.List<String> nochSichtbar(Context c) {
		java.util.List<String> l = new java.util.ArrayList<>();
		DevicePolicyManager d = Sperre.dpm(c);
		android.content.ComponentName a = Sperre.admin(c);
		org.json.JSONArray alle = Katalog.katalog(c);
		for (int i = 0; i < alle.length(); i++) {
			JSONObject app = alle.optJSONObject(i);
			if (app == null || !"apk".equals(app.optString("art")) || app.optBoolean("immer", false)) continue;
			String p = app.optString("paket");
			if (!Katalog.installiert(c, p) || Nachlauf.enthaelt(c, p)) continue;   // Nachlauf: eigener Schritt
			try {
				if (!d.isApplicationHidden(a, p)) l.add(p);
			} catch (Exception e) {
				Log.w(TAG, "isApplicationHidden " + p + ": " + e);
			}
		}
		return l;
	}

	/** Startseite an den Launcher senden und auf seine Rueckmeldung warten (StartseiteGeladen). */
	private static Ergebnis startseitePruefen(Context c, String was) throws InterruptedException {
		if (!Katalog.installiert(c, Sperre.LAUNCHER_PAKET)) return warnung("Rosenweg Launcher nicht installiert");
		long seit = SystemClock.elapsedRealtime();
		Sperre.startseite(c, was);
		StartseiteGeladen.Antwort r = StartseiteGeladen.warten(was, seit, 15_000);
		if (r == null) return warnung("Launcher meldet sich nicht (ältere Version?)");
		if ("abmelden".equals(was)) return ok("zurückgesetzt");
		if (r.symbole > 0) return ok(r.symbole + " Symbole" + (r.standard ? " (Standard)" : ""));
		if (r.standard) return ok("Standard-Startseite");
		return fehler(r.fehler != null && !r.fehler.isEmpty() ? r.fehler : "Startseite ist leer");
	}

	// ─── Anmelden ───────────────────────────────────────────────────────────

	private Schritt[] anmeldeSchritte(String name, org.json.JSONArray appsAnmeldung) {
		final Context app = getApplicationContext();
		final JSONObject[] stand = {null};   // Status der Anlage aus Schritt 2
		return new Schritt[]{
				new Schritt(ANMELDE_SCHRITTE[0], true, i -> {
					Sperre.sperrbildschirmText(app);
					return ok(name == null || name.isEmpty() ? null : "angemeldet als " + name);
				}),
				new Schritt(ANMELDE_SCHRITTE[1], true, i -> {
					boolean neu = Wlan.fuerAnmeldung(app);
					if (neu) {
						schritt(i, LAEUFT, "neu verbunden, warte auf das Netz …");
						Thread.sleep(4000);
					}
					long bis = System.currentTimeMillis() + 45_000;
					String letzter;
					while (true) {
						try {
							JSONObject st = Anlage.status(app);
							if (!st.optBoolean("angemeldet", false)) return fehler("die Telefonanlage kennt die Anmeldung nicht (mehr)");
							stand[0] = st;
							return ok(neu ? "neu verbunden" : null);
						} catch (Anlage.AnlageFehler e) {
							if (e.status == 401) return fehler("Telefon ist an der Anlage nicht als geteiltes Telefon freigegeben");
							letzter = e.getMessage();
						} catch (Exception e) {
							letzter = ursache(e);
						}
						if (System.currentTimeMillis() > bis) return fehler("Telefonanlage nach 45 s nicht erreichbar (" + letzter + ")");
						schritt(i, LAEUFT, "warte auf das WLAN …");
						Thread.sleep(3000);
					}
				}),
				new Schritt(ANMELDE_SCHRITTE[2], true, i -> {
					org.json.JSONArray apps = stand[0] != null && stand[0].optJSONArray("apps") != null ? stand[0].optJSONArray("apps")
							: appsAnmeldung != null ? appsAnmeldung : Katalog.freigegeben(app);
					Katalog.freigeben(app, apps);
					if (!Sperre.istDeviceOwner(app)) return ok("ohne Device Owner nicht prüfbar");
					DevicePolicyManager d = Sperre.dpm(app);
					android.content.ComponentName a = Sperre.admin(app);
					java.util.List<String> versteckt = new java.util.ArrayList<>(), fehlt = new java.util.ArrayList<>();
					int frei = 0;
					for (int k = 0; k < apps.length(); k++) {
						JSONObject x = apps.optJSONObject(k);
						if (x == null || !"apk".equals(x.optString("art"))) continue;
						String p = x.optString("paket");
						if (!Katalog.installiert(app, p)) fehlt.add(x.optString("name", p));
						else if (d.isApplicationHidden(a, p)) versteckt.add(p);
						else frei++;
					}
					if (!versteckt.isEmpty()) return fehler("noch versteckt: " + namen(app, versteckt));
					if (!fehlt.isEmpty()) {
						Katalog.vielleichtAbgleichen(app, true);
						return warnung(frei + " Apps frei; noch nicht installiert: " + String.join(", ", fehlt));
					}
					return ok(frei + " Apps");
				}),
				new Schritt(ANMELDE_SCHRITTE[3], true, i -> {
					boolean technik = stand[0] != null ? stand[0].optBoolean("technik")
							: Anlage.prefs(app).getBoolean(Sperre.PREF_TECHNIK, false);
					Anlage.prefs(app).edit().putBoolean(Sperre.PREF_TECHNIK, technik).apply();
					Sperre.einstellungenSperren(app, !technik);
					if (technik) Sperre.entwickleroptionen(app, true);
					if (!Sperre.istDeviceOwner(app)) return ok("ohne Device Owner nicht prüfbar");
					UserManager um = app.getSystemService(UserManager.class);
					boolean gesperrt = um != null && um.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES);
					if (gesperrt == technik) {
						return fehler(technik ? "Technik: die Einstellungen sind noch gesperrt" : "die Einstellungen sind nicht gesperrt");
					}
					return ok(technik ? "Technik: Einstellungen und Entwickleroptionen offen" : "Einstellungen geschützt");
				}),
				new Schritt(ANMELDE_SCHRITTE[4], true, i -> {
					org.sipdroid.sipua.ui.Receiver.updatePhoneAccount(app);
					JSONObject k;
					try {
						k = Anlage.sipPerson(app);
					} catch (Anlage.AnlageFehler e) {
						if (e.status == 404 || Anlage.nummer(app).isEmpty()) {
							Sperre.sipPersonGeaendert(app);
							return ok("keine persönliche Nummer");
						}
						throw e;
					}
					Sperre.sipPersonGeaendert(app);   // Rosenweg Telefon holt den Zugang jetzt (zwischengespeichert)
					if (k == null) return fehler("nicht angemeldet");
					if (k.optString("user").isEmpty()) {
						return Anlage.nummer(app).isEmpty() ? ok("keine persönliche Nummer") : fehler("die Anlage liefert keinen Zugang");
					}
					String nr = k.optString("nummer", Anlage.nummer(app));
					return ok(nr.isEmpty() ? null : "Nummer " + nr);
				}),
				new Schritt(ANMELDE_SCHRITTE[5], false, i -> {
					JSONObject k = Anlage.nextcloudKonto(app);
					return k != null && k.length() > 0 ? ok(null) : warnung("kein Zugang erhalten");
				}),
				new Schritt(ANMELDE_SCHRITTE[6], false, this::pinSchritt),
				new Schritt(ANMELDE_SCHRITTE[7], true, i -> startseitePruefen(app, "anmelden")),
		};
	}

	/**
	 * Persoenliche PIN (wie Sperre.persoenlichePinSetzen, aber synchron und geprueft). Hat die Person
	 * noch keine, fragt eine eigene Seite danach (Stefan 25.11.2026: empfohlen, nicht Pflicht).
	 */
	private Ergebnis pinSchritt(int i) throws Exception {
		Context app = getApplicationContext();
		Bildschirmsperre.vorbereiten(app);
		if (Bildschirmsperre.abmeldungAusstehend(app)) return warnung("Abmeldung nach Neustart ausstehend – PIN folgt später");
		JSONObject p = Anlage.pin(app);
		String pin;
		if (p.optBoolean("gesetzt")) {
			pin = p.optString("pin");
		} else {
			String fehlerText = null;
			while (true) {
				schritt(i, LAEUFT, "bitte PIN festlegen");
				pin = pinAbfragen(fehlerText);
				if (pin == null) return warnung("ohne PIN – jeder mit dem Telefon in der Hand kommt an deine Sachen");
				schritt(i, LAEUFT, "PIN wird gespeichert …");
				try {
					Anlage.pinSetzen(app, pin);
					break;
				} catch (Exception e) {
					fehlerText = ursache(e);
				}
			}
		}
		if (!Anlage.angemeldet(app)) return warnung("nicht mehr angemeldet");
		if (!Bildschirmsperre.bereit(app)) return warnung("Bildschirmsperre auf diesem Telefon nicht bereit");
		if (!Bildschirmsperre.setzen(app, pin)) return warnung("PIN konnte auf diesem Telefon nicht gesetzt werden");
		return geraetGesichert(app) ? ok("PIN aktiv") : warnung("PIN gesetzt, das Telefon meldet aber keine Sperre");
	}

	/**
	 * Vollseitige PIN-Abfrage (Schrittliste weg, Notruf bleibt). Blockiert den Ablauf-Thread, bis
	 * die Person waehlt. @return PIN oder null fuer "Später"
	 */
	private String pinAbfragen(String fehlerText) throws InterruptedException {
		final java.util.concurrent.LinkedBlockingQueue<String> antwort = new java.util.concurrent.LinkedBlockingQueue<>();
		runOnUiThread(() -> {
			kopfText.setText("Deine PIN für die geteilten Telefone");
			kopfText.setTextSize(22);
			schrittRahmen.setVisibility(View.GONE);
			ablaufKnoepfe.setVisibility(View.GONE);
			pinEins.setText("");
			pinZwei.setText("");
			pinZwei.setError(null);
			pinEins.setError(fehlerText);
			pinFertig = pin -> {
				android.view.inputmethod.InputMethodManager imm =
						(android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
				if (imm != null) imm.hideSoftInputFromWindow(pinSeite.getWindowToken(), 0);
				pinFertig = null;
				pinSeite.setVisibility(View.GONE);
				if (logo != null) logo.setVisibility(View.VISIBLE);
				kopfText.setText("Anmelden");
				kopfText.setTextSize(26);
				schrittRahmen.setVisibility(View.VISIBLE);
				antwort.offer(pin);
			};
			// Logo weg, solange die PIN-Seite offen ist: sonst bleibt neben Tastatur und Notruf
			// kein Platz fuer die beiden Felder
			if (logo != null) logo.setVisibility(View.GONE);
			pinSeite.setVisibility(View.VISIBLE);
			pinRollen.scrollTo(0, 0);
			pinEins.requestFocus();
		});
		String r = antwort.take();
		return r.isEmpty() ? null : r;
	}

	/** Antwort der PIN-Seite ("" = Später); nur gesetzt, solange die Seite offen ist. */
	private java.util.function.Consumer<String> pinFertig;
	private android.widget.EditText pinEins, pinZwei;

	private void pinFestlegen() {
		String p = pinEins.getText().toString();
		if (!p.matches("\\d{4,12}")) {
			pinEins.setError("4 bis 12 Ziffern");
			return;
		}
		if (!p.equals(pinZwei.getText().toString())) {
			pinZwei.setError("Die beiden PINs sind verschieden.");
			return;
		}
		if (pinFertig != null) pinFertig.accept(p);
	}

	/** Abbrechen beim Anmelden: Sitzung an der Anlage beenden, aufraeumen, Abmelde-Pruefung zeigen. */
	private void anmeldungAbbrechen() {
		final Context app = getApplicationContext();
		kopfText.setText("Abbrechen …");
		new Thread(() -> {
			try {
				Anlage.abmelden(app);
			} catch (Exception e) {
				Log.w(TAG, "Abbrechen: Abmelden an der Anlage: " + e);   // die Abmelde-Pruefung versucht es nochmals
			}
			Aufraeumen.gemeinsamenSpeicherLeeren(app);
			runOnUiThread(() -> {
				Sperre.abmeldungVormerken(app, true);
				anmeldungLaeuft = false;
				Sperre.sperren(this);
				laeuft = true;
				handler.removeCallbacks(takt);
				handler.post(takt);
				if (!netzWartet) abmeldenPruefen();
			});
		}, "Abbrechen").start();
	}

	// ─── Abmelden ───────────────────────────────────────────────────────────

	/** Nach Sperre.sperren: jeden Schritt pruefen, erst dann die Anmelde-Moeglichkeiten zeigen. */
	private void abmeldenPruefen() {
		netzWartet = true;
		anmeldenZeigen(false, null);
		Schritt[] s = abmeldeSchritte();
		ablaufStarten("Abmelden", titel(s));
		ablaufLaufen("Abmeldung", s, 0, () -> handler.postDelayed(() -> {
			Sperre.abmeldungErledigt(getApplicationContext());
			netzWartet = false;
			anlageDa = true;
			ablaufEnde();
		}, 1500), null);
	}

	private Schritt[] abmeldeSchritte() {
		final Context app = getApplicationContext();
		return new Schritt[]{
				new Schritt(ABMELDE_SCHRITTE[0], true, i -> {
					boolean ausdruecklich = Sperre.abmeldungAusdruecklich(app);
					boolean nochmals = false;
					long bis = System.currentTimeMillis() + 45_000;
					String letzter;
					while (true) {
						try {
							Bildschirmsperre.abmeldungAusfuehren(app);   // Neustart mit PIN: vorgemerkte Abmeldung
							JSONObject st = Anlage.status(app);
							if (!st.optBoolean("angemeldet", false)) return ok(null);
							if (!ausdruecklich && !Bildschirmsperre.abmeldungAusstehend(app)) {
								// Neustart/Update ohne Abmelden: die Sitzung laeuft an der Anlage weiter -
								// wie bisher wiederherstellen (mit dem Einrichtungsbildschirm).
								String n = st.optString("name", "");
								org.json.JSONArray apps = st.optJSONArray("apps");
								Ergebnis e = ok("die Sitzung läuft an der Anlage weiter");
								e.danach = () -> {
									Sperre.abmeldungErledigt(app);
									netzWartet = false;
									ablaufEnde();
									entsperrt(n, apps);
								};
								return e;
							}
							if (!nochmals) {
								nochmals = true;
								schritt(i, LAEUFT, "melde nochmals ab …");
								Anlage.abmelden(app);
								continue;
							}
							Anlage.merke(app, false, null);
							return fehler("die Telefonanlage meldet noch „angemeldet“");
						} catch (Anlage.AnlageFehler e) {
							if (e.status == 401) return ok("Telefon an der Anlage nicht freigegeben – keine Sitzung");
							letzter = e.getMessage();
						} catch (Exception e) {
							letzter = ursache(e);
						}
						// Nach einem Neustart ohne Zeitlimit warten: bis die Anlage die alte Sitzung beendet
						// hat, ist keine neue Anmeldung moeglich (Notruf geht immer)
						boolean neustart = Bildschirmsperre.abmeldungAusstehend(app);
						if (!neustart && System.currentTimeMillis() > bis) return fehler("Telefonanlage nicht erreichbar (" + letzter + ")");
						schritt(i, LAEUFT, neustart ? "Neustart: warte auf die Telefonanlage, um die letzte Sitzung zu beenden …"
								: "warte auf die Telefonanlage …");
						Thread.sleep(3000);
					}
				}),
				new Schritt(ABMELDE_SCHRITTE[1], true, i -> {
					if (!Sperre.istDeviceOwner(app)) return ok("ohne Device Owner nicht prüfbar");
					// Apps, die ihre Daten selbst loeschen (Messenger), blenden erst nach der Bestaetigung aus (bis 10 s)
					long bis = System.currentTimeMillis() + 15_000;
					boolean nachgeholfen = false;
					while (true) {
						java.util.List<String> sichtbar = nochSichtbar(app);
						if (sichtbar.isEmpty()) return ok(nachgeholfen ? "nachträglich gesperrt" : null);
						if (System.currentTimeMillis() > bis) {
							if (nachgeholfen || Anlage.angemeldet(app)) return fehler("noch sichtbar: " + namen(app, sichtbar));
							nachgeholfen = true;
							Log.w(TAG, "Abmeldung: noch sichtbar, sperre nach: " + sichtbar);
							for (String p : sichtbar) {
								try {
									Sperre.dpm(app).setApplicationHidden(Sperre.admin(app), p, true);
									if (Build.VERSION.SDK_INT >= 28) {
										Sperre.dpm(app).clearApplicationUserData(Sperre.admin(app), p,
												java.util.concurrent.Executors.newSingleThreadExecutor(),
												(x, ok) -> Log.i(TAG, "Daten geloescht " + x + ": " + ok));
									}
								} catch (Exception e) {
									Log.w(TAG, "nachsperren " + p + ": " + e);
								}
							}
							Thread.sleep(1000);
							continue;
						}
						schritt(i, LAEUFT, "warte auf " + sichtbar.size() + " App(s) …");
						Thread.sleep(1000);
					}
				}),
				new Schritt(ABMELDE_SCHRITTE[2], true, i -> {
					if (!geraetGesichert(app)) return ok(null);
					Bildschirmsperre.entfernen(app);
					Thread.sleep(500);
					return geraetGesichert(app) ? fehler("die Bildschirmsperre ist noch aktiv") : ok("nachträglich entfernt");
				}),
				new Schritt(ABMELDE_SCHRITTE[3], false, i -> {
					if (!Nachlauf.aktiv(app)) return ok("nichts offen");
					long bis = System.currentTimeMillis() + 60_000;
					while (Nachlauf.aktiv(app)) {
						int n = 0;
						for (int x : Nachlauf.alleOffen(app).values()) n += x;
						if (System.currentTimeMillis() > bis) {
							return warnung("noch " + n + " Datei(en) – werden im Hintergrund hochgeladen; Anmelden geht erst danach");
						}
						schritt(i, LAEUFT, n > 0 ? "noch " + n + " Datei(en) …" : "räume auf …");
						Thread.sleep(3000);
					}
					return ok("alles hochgeladen");
				}),
				new Schritt(ABMELDE_SCHRITTE[4], true, i -> {
					boolean neu = Wlan.fuerAbmeldung(app);
					if (neu) {
						schritt(i, LAEUFT, "neu verbunden, warte auf das Netz …");
						Thread.sleep(4000);
					}
					long bis = System.currentTimeMillis() + 45_000;
					while (true) {
						try {
							Anlage.status(app);
							return ok(neu ? "neu verbunden" : null);
						} catch (Anlage.AnlageFehler e) {
							return ok(neu ? "neu verbunden" : null);   // Anlage antwortet (z.B. 401): Netz steht
						} catch (Exception e) {
							if (System.currentTimeMillis() > bis) {
								return fehler("Telefonanlage nach 45 s nicht erreichbar – Anmelden geht erst wieder, wenn die Verbindung steht");
							}
							schritt(i, LAEUFT, "warte auf das WLAN …");
							Thread.sleep(3000);
						}
					}
				}),
				new Schritt(ABMELDE_SCHRITTE[5], false, i -> startseitePruefen(app, "abmelden")),
		};
	}

	// ─── NFC-Karte ──────────────────────────────────────────────────────────

	private void kartenleserEin() {
		if (!anlageDa) return;
		NfcAdapter nfc = NfcAdapter.getDefaultAdapter(this);
		if (nfc == null) return;
		try {
			nfc.enableReaderMode(this, tag -> {
				String uid = NfcKarte.uid(tag);
				Log.i(TAG, "Kartenleser: Karte da, uid=" + (uid == null ? "keine" : uid.length() / 2 + " Byte")
						+ " loestEin=" + loestEin + " laeuft=" + laeuft);
				if (uid == null || loestEin || !laeuft || ablaufAktiv) return;
				ablaufStarten("Anmelden", ANMELDE_SCHRITTE);
				schritt(0, LAEUFT, "Karte erkannt");
				NfcKarte.anmelden(getApplicationContext(), uid, new NfcKarte.Ergebnis() {
					@Override
					public void angemeldet(JSONObject r) {
						// derselbe Weg wie nach dem QR (einloesen)
						entsperrt(r.optString("name", ""), r.optJSONArray("apps"));
					}

					@Override
					public void fehler(String meldung) {
						anmeldungFehler(0, meldung);
					}
				});
			}, NfcKarte.LESER_FLAGS, null);
			Log.i(TAG, "Kartenleser ein");
		} catch (Exception e) {
			Log.w(TAG, "Kartenleser: " + e);
		}
	}

	private void kartenleserAus() {
		NfcAdapter nfc = NfcAdapter.getDefaultAdapter(this);
		if (nfc == null) return;
		try {
			nfc.disableReaderMode(this);
		} catch (Exception e) {
			Log.w(TAG, "Kartenleser aus: " + e);
		}
	}

	@SuppressWarnings("deprecation")
	@Override
	public void onBackPressed() {
		// Gesperrt heisst gesperrt.
	}

	private boolean imLockTask() {
		ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
		return am != null && Build.VERSION.SDK_INT >= 23
				&& am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
	}

	// ─── Kamera ─────────────────────────────────────────────────────────────

	/** QR-Scan auf Wunsch: Kamera an, Kartenleser aus; nach 60 s wieder zurueck. */
	private void kameraOeffnen() {
		kartenleserAus();
		kameraAnzeigen(true);
		kameraStarten();
		handler.removeCallbacks(kameraZeitAus);
		handler.postDelayed(kameraZeitAus, KAMERA_MS);
	}

	private void kameraStoppen() {
		handler.removeCallbacks(kameraZeitAus);
		if (kamera != null) kamera.pause();
		kameraAnzeigen(false);
		if (laeuft && !netzWartet) kartenleserEin();
	}

	private void kameraAnzeigen(boolean an) {
		kameraAn = an;
		if (kameraRahmen != null) kameraRahmen.setVisibility(an ? View.VISIBLE : View.GONE);
		if (kartenHinweis != null) kartenHinweis.setVisibility(an ? View.GONE : View.VISIBLE);
		if (logo != null) logo.setVisibility(an ? View.GONE : View.VISIBLE);
		if (wechselKnopf != null) wechselKnopf.setVisibility(an ? View.VISIBLE : View.GONE);
		if (qrKnopf != null) qrKnopf.setText(an ? "Kamera aus" : "QR-Code scannen");
	}

	private void kameraStarten() {
		if (Build.VERSION.SDK_INT >= 23
				&& checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
			// Als Device Owner ist das Recht schon erteilt (Sperre.grundregeln);
			// ohne fragt Android einmal nach.
			requestPermissions(new String[]{Manifest.permission.CAMERA}, RECHT_KAMERA);
			return;
		}
		CameraSettings s = new CameraSettings();
		s.setRequestedCameraId(kameraId(frontkamera));
		s.setAutoFocusEnabled(true);
		s.setContinuousFocusEnabled(true);
		kamera.pause();
		kamera.setCameraSettings(s);
		kamera.resume();
	}

	@Override
	public void onRequestPermissionsResult(int code, String[] rechte, int[] ergebnis) {
		if (code == RECHT_KAMERA && ergebnis.length > 0 && ergebnis[0] == PackageManager.PERMISSION_GRANTED) {
			kameraStarten();
		} else {
			zeige("Ohne Kamera kann sich niemand anmelden.", true);
		}
	}

	@SuppressWarnings("deprecation")
	private static int kameraId(boolean front) {
		int gesucht = front ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;
		Camera.CameraInfo info = new Camera.CameraInfo();
		for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
			Camera.getCameraInfo(i, info);
			if (info.facing == gesucht) return i;
		}
		return -1;  // Standardkamera
	}

	private final BarcodeCallback gescannt = new BarcodeCallback() {
		@Override
		public void barcodeResult(BarcodeResult r) {
			String inhalt = r.getText();
			if (inhalt == null) return;
			if (!inhalt.startsWith(QR_PRAEFIX)) {
				zeige("Das ist kein Rosenweg-Anmelde-QR. Öffne rosenweg4303.ch/smartphones auf deinem Handy.", true);
				return;
			}
			if (loestEin) return;
			long jetzt = SystemClock.elapsedRealtime();
			// Derselbe QR wird pro Sekunde mehrfach erkannt — nur einmal einloesen.
			if (inhalt.equals(zuletztGescannt) && jetzt - zuletztGescanntUm < 5000) return;
			zuletztGescannt = inhalt;
			zuletztGescanntUm = jetzt;
			loestEin = true;
			kameraStoppen();
			ablaufStarten("Anmelden", ANMELDE_SCHRITTE);
			schritt(0, LAEUFT, "QR-Code erkannt");
			new Thread(() -> einloesen(inhalt)).start();
		}
	};

	private void einloesen(String inhalt) {
		try {
			JSONObject r = Anlage.einloesen(this, inhalt);
			if (r.optBoolean("angemeldet", false)) {
				String name = r.optString("name", "");
				runOnUiThread(() -> entsperrt(name, r.optJSONArray("apps")));
			}
		} catch (Anlage.AnlageFehler e) {
			anmeldungFehler(0, e.status == 401
					? "Dieses Telefon ist an der Anlage nicht als geteiltes Telefon freigegeben"
					: e.getMessage());
		} catch (Exception e) {
			Log.w(TAG, "einloesen: " + e);
			anmeldungFehler(0, "Keine Verbindung zur Telefonanlage");
		} finally {
			loestEin = false;
		}
	}

	// ─── Status (z.B. nach Neustart noch angemeldet) ────────────────────────

	private final Runnable takt = new Runnable() {
		@Override
		public void run() {
			if (!laeuft) return;
			Sperre.homeSichern(SperrActivity.this);
			if (!prueft && !loestEin) {
				prueft = true;
				new Thread(SperrActivity.this::pruefen).start();
			}
			handler.postDelayed(this, ABFRAGE_MS);
		}
	};

	private void pruefen() {
		try {
			if (netzWartet || ablaufAktiv) return;   // An-/Abmelden laeuft, das zeigt eigene Schritte
			if (!Anlage.eingerichtet(this)) {
				zeige("Dieses Telefon ist noch nicht eingerichtet.", true);
				return;
			}
			// Neustart mit persoenlicher PIN: erst abmelden, nie wiederherstellen
			if (!Bildschirmsperre.abmeldungAusfuehren(this)) return;
			JSONObject s = Anlage.status(this);
			fehlversuche = 0;
			if (!anlageDa) runOnUiThread(() -> anmeldenZeigen(true, null));
			if (s.optBoolean("angemeldet", false) && Nachlauf.aktiv(this)) {
				zeige("Bitte kurz warten: Dateien der letzten Person werden noch hochgeladen.", true);
				return;
			}
			if (s.optBoolean("angemeldet", false)) {
				String name = s.optString("name", "");
				runOnUiThread(() -> entsperrt(name, s.optJSONArray("apps")));
			}
		} catch (Anlage.AnlageFehler e) {
			if (e.status == 401) runOnUiThread(() -> anmeldenZeigen(false,
					"Dieses Telefon ist an der Anlage nicht als geteiltes Telefon freigegeben."));
		} catch (Exception e) {
			Log.w(TAG, "pruefen: " + e);
			// erst nach zwei Fehlversuchen ausblenden (kein Flackern bei kurzen Aussetzern)
			if (++fehlversuche >= 2 && (anlageDa || fehlversuche == 2)) runOnUiThread(() -> anmeldenZeigen(false,
					"Keine Verbindung zur Telefonanlage – Anmelden ist gerade nicht möglich.\nNotrufe gehen immer."));
		} finally {
			prueft = false;
		}
	}

	private void zeige(String t, boolean fehler) {
		runOnUiThread(() -> {
			meldung.setText(t);
			meldung.setTextColor(Color.parseColor(fehler ? "#FCA5A5" : "#BBF7D0"));
			meldung.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
		});
	}

	private void entsperrt(String name, org.json.JSONArray apps) {
		if (isFinishing() || anmeldungLaeuft) return;
		if (Nachlauf.aktiv(this)) {   // Daten der letzten Person sind noch da
			if (ablaufAktiv) ablaufEnde();
			zeige("Bitte kurz warten: Dateien der letzten Person werden noch hochgeladen.", true);
			loestEin = false;
			return;
		}
		anmeldungLaeuft = true;
		laeuft = false;
		handler.removeCallbacks(takt);
		kamera.pause();
		kartenleserAus();
		// Stefan 25.11.2026: Einrichtungsbildschirm - jeder Schritt wird ausgefuehrt und geprueft, kein
		// Startbildschirm, bevor alle Pflicht-Schritte gruen sind.
		Schritt[] s = anmeldeSchritte(name, apps);
		ablaufStarten("Anmelden", titel(s));
		ablaufLaufen("Anmeldung", s, 0, () -> handler.postDelayed(this::anmeldungAbschliessen, 800),
				this::anmeldungAbbrechen);
	}

	private void anmeldungAbschliessen() {
		anmeldungLaeuft = false;
		if (isFinishing()) return;
		// Alles davor haben die Schritte erledigt; hier nur noch Kiosk aus, Launcher als HOME, Dienst.
		Sperre.freigebenAbschluss(this);
		if (imLockTask()) {
			try {
				stopLockTask();
			} catch (Exception e) {
				Log.w(TAG, "stopLockTask: " + e.getMessage());
			}
		}
		Intent home = new Intent(Intent.ACTION_MAIN);
		home.addCategory(Intent.CATEGORY_HOME);
		home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		finish();
		try {
			startActivity(home);
		} catch (Exception e) {
			Log.w(TAG, "home: " + e.getMessage());
		}
	}

	private void notruf() {
		// Notruf-Waehler des Systems: laesst nur Notrufnummern zu. Die normale
		// Telefon-App bleibt gesperrt.
		Intent i = new Intent("com.android.phone.EmergencyDialer.DIAL");
		i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		try {
			startActivity(i);
		} catch (Exception e) {
			zeige("Notruf-Wähler nicht gefunden — 112 über die Ein/Aus-Taste wählen.", true);
		}
	}

	// ─── Oberflaeche (ohne Layout-Dateien, die App hat keine) ───────────────

	private int dp(float v) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
	}

	private TextView text(float sp, int farbe, boolean fett) {
		TextView t = new TextView(this);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(farbe);
		t.setGravity(Gravity.CENTER);
		if (fett) t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	private Button knopf(String beschriftung, String farbe) {
		Button k = new Button(this);
		k.setText(beschriftung);
		k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
		k.setTextColor(Color.WHITE);
		k.setAllCaps(false);
		GradientDrawable g = new GradientDrawable();
		g.setColor(Color.parseColor(farbe));
		g.setCornerRadius(dp(14));
		g.setStroke(Math.max(1, dp(1.2f)), Color.WHITE);   // feiner weisser Rand (Stefan 25.11.2026)
		k.setBackground(g);
		return k;
	}

	/** Vollseitige PIN-Abfrage im Einrichtungsbildschirm (Stefan 25.11.2026), wie die Schritte auf Blau. */
	private LinearLayout bauePinSeite() {
		LinearLayout p = new LinearLayout(this);
		p.setOrientation(LinearLayout.VERTICAL);
		p.setGravity(Gravity.CENTER_HORIZONTAL);
		p.setPadding(dp(8), dp(12), dp(8), 0);
		TextView warum = text(16, Color.parseColor("#BFDBFE"), false);
		warum.setText("Damit niemand an deine Sachen kommt, wenn du das Telefon liegen lässt.\n"
				+ "Gilt auf jedem Rosenweg-Telefon, solange du angemeldet bist.");
		warum.setPadding(0, 0, 0, dp(14));
		p.addView(warum);
		pinEins = pinFeld("Neue PIN (4–12 Ziffern)");
		pinZwei = pinFeld("PIN wiederholen");
		LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60));
		fp.bottomMargin = dp(12);
		p.addView(pinEins, fp);
		p.addView(pinZwei, new LinearLayout.LayoutParams(fp));
		pinZwei.setOnEditorActionListener((v, aktion, e) -> {
			pinFestlegen();
			return true;
		});
		Button fest = knopf("PIN festlegen", "#2563EB");
		fest.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
		fest.setOnClickListener(v -> pinFestlegen());
		LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
		kp.topMargin = dp(6);
		p.addView(fest, kp);
		Button spaeter = new Button(this);
		spaeter.setText("Später");
		spaeter.setAllCaps(false);
		spaeter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		spaeter.setTextColor(Color.parseColor("#BFDBFE"));
		spaeter.setBackgroundColor(Color.TRANSPARENT);
		spaeter.setOnClickListener(v -> { if (pinFertig != null) pinFertig.accept(""); });
		p.addView(spaeter, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)));
		// Rollbar und oben ausgerichtet: mit offener Tastatur bleibt auf dem A15 nur wenig Hoehe
		// uebrig, und die Felder lagen darunter (Stefan 25.11.2026)
		pinRollen = new android.widget.ScrollView(this);
		pinRollen.setFillViewport(true);
		pinRollen.addView(p, new android.widget.FrameLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		LinearLayout aussen = new LinearLayout(this);
		aussen.setOrientation(LinearLayout.VERTICAL);
		aussen.addView(pinRollen, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
		View.OnFocusChangeListener sichtbar = (v, fokus) -> {
			if (fokus) pinRollen.postDelayed(() -> pinRollen.smoothScrollTo(0, Math.max(0, v.getTop() - dp(8))), 300);
		};
		pinEins.setOnFocusChangeListener(sichtbar);
		pinZwei.setOnFocusChangeListener(sichtbar);
		return aussen;
	}

	private android.widget.EditText pinFeld(String hinweis) {
		android.widget.EditText e = new android.widget.EditText(this);
		e.setHint(hinweis);
		e.setHintTextColor(Color.parseColor("#93C5FD"));
		e.setTextColor(Color.WHITE);
		e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
		e.setGravity(Gravity.CENTER);
		e.setSingleLine(true);
		e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
		e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(12)});
		GradientDrawable g = new GradientDrawable();
		g.setColor(Color.parseColor("#1E40AF"));
		g.setCornerRadius(dp(12));
		g.setStroke(Math.max(1, dp(1.2f)), Color.parseColor("#93C5FD"));
		e.setBackground(g);
		return e;
	}

	private View baueOberflaeche() {
		// Wie der Ladebildschirm der Stationen: blauer Hintergrund, Rosenweg-Logo in der Mitte
		// (Stefan 25.11.2026); beim QR-Scannen steht dort das Kamerabild.
		int hell = Color.WHITE, grau = Color.parseColor("#BFDBFE");
		LinearLayout l = new LinearLayout(this);
		l.setOrientation(LinearLayout.VERTICAL);
		l.setGravity(Gravity.CENTER_HORIZONTAL);
		l.setBackgroundColor(Color.parseColor("#1E3A8A"));
		l.setPadding(dp(24), dp(40), dp(24), dp(24));

		TextView unter = text(15, grau, false);
		kopfText = unter;
		unter.setText("Gesperrt · zum Benutzen anmelden");
		unter.setPadding(0, dp(4), 0, dp(18));
		l.addView(unter);

		// Kamerabild mit abgerundetem Rahmen
		FrameLayout rahmen = new FrameLayout(this);
		GradientDrawable rand = new GradientDrawable();
		rand.setColor(Color.BLACK);
		rand.setCornerRadius(dp(18));
		rand.setStroke(dp(3), Color.parseColor("#2563EB"));
		rahmen.setBackground(rand);
		rahmen.setPadding(dp(3), dp(3), dp(3), dp(3));
		rahmen.setClipToOutline(true);
		kamera = new BarcodeView(this);
		kamera.setDecoderFactory(new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
		kamera.decodeContinuous(gescannt);
		rahmen.addView(kamera, new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
		l.addView(rahmen, new LinearLayout.LayoutParams(dp(280), dp(280)));
		kameraRahmen = rahmen;

		View oben = new View(this);
		obenPlatz = oben;
		l.addView(oben, new LinearLayout.LayoutParams(1, 0, 1f));
		android.widget.ImageView bild = new android.widget.ImageView(this);
		bild.setImageResource(R.drawable.rosenweg_logo);
		bild.setAdjustViewBounds(true);
		bild.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
		bild.setContentDescription("Rosenweg");
		l.addView(bild, new LinearLayout.LayoutParams(dp(230), dp(230)));
		logo = bild;

		// Einrichtungsbildschirm beim An-/Abmelden (Stefan 25.11.2026): Schritte mit Stand, rollbar,
		// nimmt den Platz zwischen Logo und Notruf (der Notruf bleibt immer unten sichtbar)
		schrittListe = new LinearLayout(this);
		schrittListe.setOrientation(LinearLayout.VERTICAL);
		schrittListe.setPadding(dp(8), dp(12), dp(8), dp(8));
		schrittRahmen = new android.widget.ScrollView(this);
		schrittRahmen.addView(schrittListe, new android.widget.FrameLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		schrittRahmen.setVisibility(View.GONE);
		l.addView(schrittRahmen, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

		// Pflicht-Schritt gescheitert: Nochmals versuchen / Abbrechen
		ablaufKnoepfe = new LinearLayout(this);
		ablaufKnoepfe.setOrientation(LinearLayout.VERTICAL);
		ablaufKnoepfe.setPadding(0, dp(8), 0, 0);
		nochmalsKnopf = knopf("Nochmals versuchen", "#2563EB");
		LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
		np.bottomMargin = dp(10);
		ablaufKnoepfe.addView(nochmalsKnopf, np);
		abbrechenKnopf = knopf("Abbrechen", "#1E40AF");
		LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
		ap.bottomMargin = dp(10);
		ablaufKnoepfe.addView(abbrechenKnopf, ap);
		ablaufKnoepfe.setVisibility(View.GONE);
		l.addView(ablaufKnoepfe, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

		pinSeite = bauePinSeite();
		pinSeite.setVisibility(View.GONE);
		l.addView(pinSeite, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

		kartenHinweis = text(20, hell, true);
		kartenHinweis.setText("Rosenweg-Karte\nhinten ans Telefon halten");
		kartenHinweis.setPadding(0, dp(20), 0, 0);
		l.addView(kartenHinweis);

		TextView hinweis = text(14, grau, false);
		anmeldeHinweis = hinweis;
		hinweis.setText("Oder: „QR-Code scannen“ und den Anmelde-QR von\nrosenweg4303.ch/smartphones auf deinem Handy zeigen,\noder unten mit dem Rosenweg-Konto anmelden.");
		hinweis.setPadding(0, dp(18), 0, 0);
		l.addView(hinweis);

		meldung = text(15, Color.parseColor("#FCA5A5"), false);
		meldung.setPadding(0, dp(12), 0, 0);
		meldung.setVisibility(View.GONE);
		l.addView(meldung);

		View platz = new View(this);
		untenPlatz = platz;
		l.addView(platz, new LinearLayout.LayoutParams(1, 0, 1f));

		Button konto = knopf("Mit Rosenweg-Konto anmelden", "#2563EB");
		kontoKnopf = konto;
		konto.setTextColor(Color.WHITE);
		konto.setOnClickListener(v -> startActivity(new Intent(this, WebAppActivity.class)
				.putExtra(WebAppActivity.EXTRA_ANMELDEN, true)));
		LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
		kp.bottomMargin = dp(10);
		l.addView(konto, kp);

		qrKnopf = knopf("QR-Code scannen", "#1E40AF");
		qrKnopf.setOnClickListener(v -> { if (kameraAn) kameraStoppen(); else kameraOeffnen(); });
		LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
		qp.bottomMargin = dp(10);
		l.addView(qrKnopf, qp);

		wechselKnopf = knopf("Kamera wechseln", "#1E40AF");
		wechselKnopf.setOnClickListener(v -> {
			frontkamera = !frontkamera;
			kameraStarten();
		});
		LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
		wp.bottomMargin = dp(10);
		l.addView(wechselKnopf, wp);

		Button notruf = knopf("Notruf", "#DC2626");
		notruf.setOnClickListener(v -> notruf());
		l.addView(notruf, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

		TextView fuss = text(12, grau, false);
		fuss.setText("Notrufe (112, 117, 118, 144) gehen immer.");
		fuss.setPadding(0, dp(8), 0, 0);
		l.addView(fuss);
		anmeldenZeigen(false, "Verbinde mit der Telefonanlage …");
		return l;
	}
}
