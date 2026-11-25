package org.sipdroid.sipua.rosenweg;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
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
	private Button qrKnopf, wechselKnopf;
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
		// Zurueck aus dem Anmeldefenster und schon angemeldet: nicht wieder sperren.
		if (Anlage.angemeldet(this)) {
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
		kartenleserEin();
		handler.post(takt);
	}

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
	private final Runnable leserNeu = () -> { if (laeuft && !kameraAn) kartenleserEin(); };

	@Override
	public void onWindowFocusChanged(boolean fokus) {
		super.onWindowFocusChanged(fokus);
		handler.removeCallbacks(leserNeu);
		if (fokus) handler.postDelayed(leserNeu, 600);
	}

	// ─── NFC-Karte ──────────────────────────────────────────────────────────

	private void kartenleserEin() {
		NfcAdapter nfc = NfcAdapter.getDefaultAdapter(this);
		if (nfc == null) return;
		try {
			nfc.enableReaderMode(this, tag -> {
				String uid = NfcKarte.uid(tag);
				Log.i(TAG, "Kartenleser: Karte da, uid=" + (uid == null ? "keine" : uid.length() / 2 + " Byte")
						+ " loestEin=" + loestEin + " laeuft=" + laeuft);
				if (uid == null || loestEin || !laeuft) return;
				zeige("Karte erkannt — anmelden …", false);
				NfcKarte.anmelden(getApplicationContext(), uid, new NfcKarte.Ergebnis() {
					@Override
					public void angemeldet(JSONObject r) {
						// derselbe Weg wie nach dem QR (einloesen)
						entsperrt(r.optString("name", ""), r.optJSONArray("apps"));
					}

					@Override
					public void fehler(String meldung) {
						zeige(meldung, true);
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
		if (laeuft) kartenleserEin();
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
			zeige("Anmelden …", false);
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
			zeige(e.status == 401
					? "Dieses Telefon ist an der Anlage nicht als geteiltes Telefon freigegeben."
					: e.getMessage(), true);
		} catch (Exception e) {
			Log.w(TAG, "einloesen: " + e);
			zeige("Keine Verbindung zur Telefonanlage. Notrufe gehen trotzdem.", true);
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
			if (!Anlage.eingerichtet(this)) {
				zeige("Dieses Telefon ist noch nicht eingerichtet.", true);
				return;
			}
			// Neustart mit persoenlicher PIN: erst abmelden, nie wiederherstellen
			if (!Bildschirmsperre.abmeldungAusfuehren(this)) return;
			JSONObject s = Anlage.status(this);
			if (s.optBoolean("angemeldet", false) && Nachlauf.aktiv(this)) {
				zeige("Bitte kurz warten: Dateien der letzten Person werden noch hochgeladen.", true);
				return;
			}
			if (s.optBoolean("angemeldet", false)) {
				String name = s.optString("name", "");
				runOnUiThread(() -> entsperrt(name, s.optJSONArray("apps")));
			}
		} catch (Anlage.AnlageFehler e) {
			if (e.status == 401) zeige("Dieses Telefon ist an der Anlage nicht als geteiltes Telefon freigegeben.", true);
		} catch (Exception e) {
			Log.w(TAG, "pruefen: " + e);
		} finally {
			prueft = false;
		}
	}

	private void zeige(String t, boolean fehler) {
		runOnUiThread(() -> {
			meldung.setText(t);
			meldung.setTextColor(Color.parseColor(fehler ? "#FCA5A5" : "#BBF7D0"));
			meldung.setVisibility(View.VISIBLE);
		});
	}

	private void entsperrt(String name, org.json.JSONArray apps) {
		if (isFinishing()) return;
		if (Nachlauf.aktiv(this)) {   // Daten der letzten Person sind noch da
			zeige("Bitte kurz warten: Dateien der letzten Person werden noch hochgeladen.", true);
			loestEin = false;
			return;
		}
		laeuft = false;
		handler.removeCallbacks(takt);
		kamera.pause();
		kartenleserAus();
		// Stefan 25.11.2026: zuerst auf das WLAN der Person warten und das anzeigen. Vorher kam der
		// Startbildschirm halb (nur Telefon) und die Rechte falsch, weil das WLAN gerade wechselte.
		zeige("Angemeldet" + (name == null || name.isEmpty() ? "" : " als " + name) + " – verbinde mit deinem WLAN …", false);
		final android.content.Context app = getApplicationContext();
		new Thread(() -> {
			org.json.JSONArray a = apps;
			boolean neu = Wlan.fuerAnmeldung(app);
			long bis = System.currentTimeMillis() + 45_000;
			try { if (neu) Thread.sleep(4000); } catch (InterruptedException ignored) { }
			while (true) {
				try {
					org.json.JSONObject st = Anlage.status(app);
					if (st.optJSONArray("apps") != null) a = st.optJSONArray("apps");
					break;
				} catch (Exception e) {
					if (System.currentTimeMillis() > bis) {
						Log.w(TAG, "Anmeldung: Anlage nach 45 s noch nicht erreichbar, weiter mit dem Stand der Anmeldung");
						break;
					}
					runOnUiThread(() -> zeige("Warte auf das WLAN … gleich geht es weiter", false));
					try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
				}
			}
			final org.json.JSONArray fertig = a;
			runOnUiThread(() -> anmeldungAbschliessen(name, fertig));
		}, "Anmeldung").start();
	}

	private void anmeldungAbschliessen(String name, org.json.JSONArray apps) {
		if (isFinishing()) return;
		Sperre.freigeben(this, name, apps);
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
		l.addView(oben, new LinearLayout.LayoutParams(1, 0, 1f));
		android.widget.ImageView bild = new android.widget.ImageView(this);
		bild.setImageResource(R.drawable.rosenweg_logo);
		bild.setAdjustViewBounds(true);
		bild.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
		bild.setContentDescription("Rosenweg");
		l.addView(bild, new LinearLayout.LayoutParams(dp(230), dp(230)));
		logo = bild;

		kartenHinweis = text(20, hell, true);
		kartenHinweis.setText("Rosenweg-Karte\nhinten ans Telefon halten");
		kartenHinweis.setPadding(0, dp(20), 0, 0);
		l.addView(kartenHinweis);

		TextView hinweis = text(14, grau, false);
		hinweis.setText("Oder: „QR-Code scannen“ und den Anmelde-QR von\nrosenweg4303.ch/smartphones auf deinem Handy zeigen,\noder unten mit dem Rosenweg-Konto anmelden.");
		hinweis.setPadding(0, dp(18), 0, 0);
		l.addView(hinweis);

		meldung = text(15, Color.parseColor("#FCA5A5"), false);
		meldung.setPadding(0, dp(12), 0, 0);
		meldung.setVisibility(View.GONE);
		l.addView(meldung);

		View platz = new View(this);
		l.addView(platz, new LinearLayout.LayoutParams(1, 0, 1f));

		Button konto = knopf("Mit Rosenweg-Konto anmelden", "#2563EB");
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
		return l;
	}
}
