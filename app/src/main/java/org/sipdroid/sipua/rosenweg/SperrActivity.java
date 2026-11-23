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
import android.os.Build;
import android.os.Bundle;
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
 * Sperrbildschirm des geteilten Telefons: Kamera fuer den Anmelde-QR, Notruf.
 *
 * Die Person oeffnet pbx.rosenweg4303.ch/telefon auf dem EIGENEN Handy, meldet
 * sich mit dem Rosenweg-Konto an und haelt den dort angezeigten QR vor die
 * Kamera. Auf diesem Geraet wird nichts eingegeben.
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
	private boolean frontkamera = true;

	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		if (Build.VERSION.SDK_INT >= 27) {
			setShowWhenLocked(true);
			setTurnScreenOn(true);
		}
		setContentView(baueOberflaeche());
		// Sicherstellen, dass die SIP-Engine laeuft (startet sie, falls noch nicht).
		org.sipdroid.sipua.ui.Receiver.engine(this);
	}

	@Override
	protected void onResume() {
		super.onResume();
		Sperre.grundregeln(this);
		if (Sperre.istDeviceOwner(this) && !imLockTask()) {
			try {
				startLockTask();
			} catch (Exception e) {
				Log.w(TAG, "startLockTask: " + e.getMessage());
			}
		}
		laeuft = true;
		kameraStarten();
		handler.post(takt);
	}

	@Override
	protected void onPause() {
		laeuft = false;
		handler.removeCallbacks(takt);
		kamera.pause();
		super.onPause();
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
				zeige("Das ist kein Rosenweg-Anmelde-QR. Öffne pbx.rosenweg4303.ch/telefon auf deinem Handy.", true);
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
				runOnUiThread(() -> entsperrt(name));
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
			JSONObject s = Anlage.status(this);
			if (s.optBoolean("angemeldet", false)) {
				String name = s.optString("name", "");
				runOnUiThread(() -> entsperrt(name));
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

	private void entsperrt(String name) {
		if (isFinishing()) return;
		laeuft = false;
		handler.removeCallbacks(takt);
		kamera.pause();
		Sperre.freigeben(this, name);
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
		k.setBackground(g);
		return k;
	}

	private View baueOberflaeche() {
		int hell = Color.parseColor("#E5E7EB"), grau = Color.parseColor("#94A3B8");
		LinearLayout l = new LinearLayout(this);
		l.setOrientation(LinearLayout.VERTICAL);
		l.setGravity(Gravity.CENTER_HORIZONTAL);
		l.setBackgroundColor(Color.parseColor("#0F172A"));
		l.setPadding(dp(24), dp(40), dp(24), dp(24));

		TextView titel = text(26, hell, true);
		titel.setText("Rosenweg Telefon");
		l.addView(titel);

		TextView unter = text(15, grau, false);
		unter.setText("Gesperrt · zum Telefonieren anmelden");
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

		TextView hinweis = text(16, hell, false);
		hinweis.setText("Auf deinem Handy pbx.rosenweg4303.ch/telefon öffnen\nund den Anmelde-QR vor die Kamera halten.");
		hinweis.setPadding(0, dp(18), 0, 0);
		l.addView(hinweis);

		meldung = text(15, Color.parseColor("#FCA5A5"), false);
		meldung.setPadding(0, dp(12), 0, 0);
		meldung.setVisibility(View.GONE);
		l.addView(meldung);

		View platz = new View(this);
		l.addView(platz, new LinearLayout.LayoutParams(1, 0, 1f));

		Button wechseln = knopf("Kamera wechseln", "#334155");
		wechseln.setOnClickListener(v -> {
			frontkamera = !frontkamera;
			kameraStarten();
		});
		LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
		wp.bottomMargin = dp(10);
		l.addView(wechseln, wp);

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
