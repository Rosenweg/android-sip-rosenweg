package org.sipdroid.sipua.rosenweg;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Browserfenster der Rosenweg-App, nur fuer https://*.rosenweg4303.ch.
 *
 * Zwei Verwendungen:
 * - Web-Apps aus dem Katalog (Portal, Messenger, Mail & Kalender).
 * - Anmelden direkt am gesperrten Telefon (EXTRA_ANMELDEN): /telefon mit
 *   Authentik; die Seite reicht den Anmelde-Code ueber die Bruecke
 *   "RosenwegTelefon" an die App, statt einen QR zu zeigen.
 *
 * Alle Fenster teilen sich Cookies: wer sich hier mit Authentik anmeldet, ist
 * in den Web-Apps schon angemeldet (Single Sign-on). Beim Abmelden loescht
 * Katalog.webDatenLoeschen() alles.
 */
public class WebAppActivity extends Activity {
	public static final String EXTRA_URL = "url";
	public static final String EXTRA_TITEL = "titel";
	public static final String EXTRA_ANMELDEN = "anmelden";
	private static final String TAG = "RosenwegWeb";

	private WebView web;
	private boolean anmelden;

	@SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);
		anmelden = getIntent().getBooleanExtra(EXTRA_ANMELDEN, false);
		String url = anmelden ? Anlage.prefs(this).getString(Anlage.PREF_API, Anlage.API_STANDARD) + "/telefon"
				: getIntent().getStringExtra(EXTRA_URL);
		if (url == null || !erlaubt(Uri.parse(url))) {
			finish();
			return;
		}
		setTitle(anmelden ? "Anmelden" : getIntent().getStringExtra(EXTRA_TITEL));
		web = new WebView(this);
		WebSettings s = web.getSettings();
		s.setJavaScriptEnabled(true);
		s.setDomStorageEnabled(true);
		s.setDatabaseEnabled(true);
		s.setAllowFileAccess(false);
		s.setAllowContentAccess(false);
		s.setMediaPlaybackRequiresUserGesture(false);
		CookieManager.getInstance().setAcceptCookie(true);
		CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
		web.setWebChromeClient(new WebChromeClient());
		web.setWebViewClient(new WebViewClient() {
			@Override
			public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
				Uri u = r.getUrl();
				if (erlaubt(u)) return false;
				// Im Kiosk darf kein Link ins offene Internet fuehren.
				Toast.makeText(WebAppActivity.this, "Nur Rosenweg-Seiten", Toast.LENGTH_SHORT).show();
				return true;
			}
		});
		if (anmelden) web.addJavascriptInterface(new Bruecke(), "RosenwegTelefon");
		android.widget.FrameLayout rahmen = new android.widget.FrameLayout(this);
		rahmen.setBackgroundColor(android.graphics.Color.parseColor("#0F172A"));
		rahmen.addView(web);
		setContentView(Raender.freihalten(rahmen));
		web.loadUrl(url);
	}

	/** Nur https und nur rosenweg4303.ch (inkl. authentik.rosenweg4303.ch). */
	static boolean erlaubt(Uri u) {
		if (u == null || !"https".equals(u.getScheme()) || u.getHost() == null) return false;
		String h = u.getHost().toLowerCase();
		return h.equals("rosenweg4303.ch") || h.endsWith(".rosenweg4303.ch");
	}

	@SuppressWarnings("deprecation")
	@Override
	public void onBackPressed() {
		if (web != null && web.canGoBack()) web.goBack();
		else super.onBackPressed();
	}

	@Override
	protected void onDestroy() {
		if (web != null) {
			CookieManager.getInstance().flush();
			web.destroy();
		}
		super.onDestroy();
	}

	/** Von /telefon aufgerufen, wenn die Seite im Anmeldefenster laeuft. */
	private class Bruecke {
		@JavascriptInterface
		public void anmelden(String inhalt) {
			// Nur die eigene Anmeldeseite darf das; die Navigation ist ohnehin
			// auf rosenweg4303.ch beschraenkt.
			runOnUiThread(() -> {
				if (web == null || !erlaubt(Uri.parse(String.valueOf(web.getUrl())))) return;
				new Thread(() -> einloesen(inhalt)).start();
			});
		}
	}

	private void einloesen(String inhalt) {
		try {
			JSONObject r = Anlage.einloesen(this, inhalt);
			if (r.optBoolean("angemeldet", false)) {
				runOnUiThread(() -> {
					Sperre.freigeben(this, r.optString("name", ""), r.optJSONArray("apps"));
					setResult(RESULT_OK);
					finish();
				});
			}
		} catch (Exception e) {
			Log.w(TAG, "einloesen: " + e);
			runOnUiThread(() -> Toast.makeText(this, "Anmelden fehlgeschlagen: " + e.getMessage(),
					Toast.LENGTH_LONG).show());
		}
	}

	static Intent oeffnen(android.content.Context c, String titel, String url) {
		return new Intent(c, WebAppActivity.class).putExtra(EXTRA_TITEL, titel).putExtra(EXTRA_URL, url)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
	}

	@SuppressWarnings("unused")
	private void verstecke(View v) {
		v.setVisibility(View.GONE);
	}
}
