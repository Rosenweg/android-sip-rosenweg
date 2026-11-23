package org.sipdroid.sipua.rosenweg;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONArray;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Blendet vorinstallierte Apps aus, die auf dem geteilten Telefon nichts zu
 * suchen haben (Play Store, Google- und Hersteller-Apps, durch Rosenweg-/Fossify-
 * Apps ersetzte Apps). Die Liste kommt von der Anlage (telefon_ausblenden).
 *
 * Geschuetzt — wird nie ausgeblendet, auch wenn es auf der Liste steht:
 * diese App, der WebView-Anbieter (Web-Kacheln + Anmeldefenster laufen darauf),
 * Telefonie/Anruf-Oberflaeche/Notruf, die aktive Tastatur, Einstellungen und
 * Systemoberflaeche. Sonst koennte eine falsche Liste das Telefon unbrauchbar machen.
 */
public final class Ausblenden {
	private static final String TAG = "RosenwegAusblenden";
	static final String PREF_LISTE = "rosenweg_ausblenden";
	private static final String PREF_AUSGEBLENDET = "rosenweg_ausgeblendet";

	private static final Set<String> IMMER_GESCHUETZT = new HashSet<>(Arrays.asList(
			"android", "com.android.systemui", "com.android.settings", "com.android.phone",
			"com.android.server.telecom", "com.android.providers.telephony", "com.android.providers.contacts",
			"com.android.permissioncontroller", "com.google.android.permissioncontroller",
			"com.android.packageinstaller", "com.google.android.packageinstaller",
			"com.android.webview", "com.google.android.webview",
			"com.samsung.android.incallui", "com.samsung.android.emergency", "com.android.emergency",
			"com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer",
			"com.sec.android.app.launcher", "com.google.android.apps.nexuslauncher",
			"com.android.networkstack", "com.google.android.networkstack"
	));

	private Ausblenden() {}

	static void merkeListe(Context c, JSONArray liste) {
		Anlage.prefs(c).edit().putString(PREF_LISTE, liste == null ? "[]" : liste.toString()).apply();
	}

	private static Set<String> geschuetzt(Context c) {
		Set<String> s = new HashSet<>(IMMER_GESCHUETZT);
		s.add(c.getPackageName());
		try {
			if (Build.VERSION.SDK_INT >= 26 && WebView.getCurrentWebViewPackage() != null) {
				s.add(WebView.getCurrentWebViewPackage().packageName);
			}
		} catch (Exception ignored) {}
		try {
			TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
			if (tm != null && tm.getDefaultDialerPackage() != null) s.add(tm.getDefaultDialerPackage());
		} catch (Exception ignored) {}
		try {
			String ime = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
			if (ime != null) s.add(ComponentName.unflattenFromString(ime).getPackageName());
		} catch (Exception ignored) {}
		try {
			Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
			for (ResolveInfo r : c.getPackageManager().queryIntentActivities(home, 0)) {
				s.add(r.activityInfo.packageName);
			}
		} catch (Exception ignored) {}
		return s;
	}

	private static boolean nachinstalliert(Context c, String p) {
		try {
			int flags = Build.VERSION.SDK_INT >= 24 ? android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES : 0;
			android.content.pm.ApplicationInfo ai = c.getPackageManager().getApplicationInfo(p, flags);
			return (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0;
		} catch (Exception e) {
			return false;
		}
	}

	private static void deinstallieren(Context c, String p) {
		try {
			Intent fertig = new Intent(c, InstallEmpfaenger.class).putExtra(InstallEmpfaenger.EXTRA_PAKET, p)
					.putExtra(InstallEmpfaenger.EXTRA_ENTFERNT, true);
			int f = android.app.PendingIntent.FLAG_UPDATE_CURRENT
					| (Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_MUTABLE : 0);
			c.getPackageManager().getPackageInstaller().uninstall(p,
					android.app.PendingIntent.getBroadcast(c, p.hashCode(), fertig, f).getIntentSender());
			Log.i(TAG, "deinstalliere: " + p);
		} catch (Exception e) {
			Log.w(TAG, "deinstallieren " + p + ": " + e);
		}
	}

	/** Liste anwenden: ausblenden, was draufsteht; wieder einblenden, was nicht mehr draufsteht. */
	static void anwenden(Context c) {
		if (!Sperre.istDeviceOwner(c)) return;
		DevicePolicyManager d = Sperre.dpm(c);
		ComponentName a = Sperre.admin(c);
		Set<String> soll = new HashSet<>();
		try {
			JSONArray l = new JSONArray(Anlage.prefs(c).getString(PREF_LISTE, "[]"));
			for (int i = 0; i < l.length(); i++) soll.add(l.getString(i));
		} catch (Exception ignored) {}
		soll.removeAll(geschuetzt(c));
		Set<String> bisher = new HashSet<>(Anlage.prefs(c).getStringSet(PREF_AUSGEBLENDET, new HashSet<>()));
		Set<String> jetzt = new HashSet<>();
		for (String p : bisher) {
			if (soll.contains(p)) continue;
			try {
				d.setApplicationHidden(a, p, false);
				Log.i(TAG, "wieder eingeblendet: " + p);
			} catch (Exception e) {
				Log.w(TAG, "einblenden " + p + ": " + e);
			}
		}
		for (String p : soll) {
			if (!Katalog.installiert(c, p)) continue;
			if (nachinstalliert(c, p)) {
				// Nicht Teil des Systems (Store-/OMC-Beigaben): ganz entfernen.
				deinstallieren(c, p);
				continue;
			}
			try {
				if (d.setApplicationHidden(a, p, true) || d.isApplicationHidden(a, p)) {
					jetzt.add(p);
				}
			} catch (Exception e) {
				Log.w(TAG, "ausblenden " + p + ": " + e);
			}
		}
		Anlage.prefs(c).edit().putStringSet(PREF_AUSGEBLENDET, jetzt).apply();
		Log.i(TAG, jetzt.size() + " Apps ausgeblendet");
	}
}
