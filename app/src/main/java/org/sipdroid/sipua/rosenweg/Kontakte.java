package org.sipdroid.sipua.rosenweg;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Telefonbuch des geteilten Telefons: das Rosenweg-Verzeichnis
 * (/api/telefon/kontakte) unter dem eigenen Konto "Rosenweg" im
 * Android-Adressbuch. Die Telefon-App zeigt damit Namen statt Nummern.
 *
 * Abgleich = komplett ersetzen, aber nur wenn sich die Version geaendert hat.
 * Bei ~200 Eintraegen ist das schneller und robuster als ein Diff.
 */
public final class Kontakte {
	private static final String TAG = "RosenwegKontakte";
	static final String KONTOTYP = "ch.rosenweg4303.telefon.kontakte";
	private static final String KONTONAME = "Rosenweg";
	private static final String PREF_VERSION = "rosenweg_kontakte_version";

	private Kontakte() {}

	private static Account konto(Context c) {
		Account a = new Account(KONTONAME, KONTOTYP);
		AccountManager am = AccountManager.get(c);
		if (am.getAccountsByType(KONTOTYP).length == 0) {
			am.addAccountExplicitly(a, null, null);
			// Kontakte ohne Gruppe sichtbar schalten, sonst blendet die
			// Telefon-App das Konto aus.
			ContentValues v = new ContentValues();
			v.put(ContactsContract.Settings.ACCOUNT_NAME, KONTONAME);
			v.put(ContactsContract.Settings.ACCOUNT_TYPE, KONTOTYP);
			v.put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1);
			v.put(ContactsContract.Settings.SHOULD_SYNC, 0);
			c.getContentResolver().insert(ContactsContract.Settings.CONTENT_URI, v);
		}
		return a;
	}

	private static Uri alsSyncAdapter(Uri u) {
		return u.buildUpon()
				.appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
				.appendQueryParameter(RawContacts.ACCOUNT_NAME, KONTONAME)
				.appendQueryParameter(RawContacts.ACCOUNT_TYPE, KONTOTYP)
				.build();
	}

	/** Blockiert (Netz + Datenbank): nie im UI-Thread. */
	public static void abgleichen(Context c) {
		try {
			String alt = Anlage.prefs(c).getString(PREF_VERSION, "");
			JSONObject d = Anlage.kontakte(c, alt);
			if (d.optBoolean("unveraendert", false)) return;
			JSONArray liste = d.getJSONArray("kontakte");
			konto(c);
			ContentResolver cr = c.getContentResolver();
			int weg = cr.delete(alsSyncAdapter(RawContacts.CONTENT_URI),
					RawContacts.ACCOUNT_TYPE + "=? AND " + RawContacts.ACCOUNT_NAME + "=?",
					new String[]{KONTOTYP, KONTONAME});
			ArrayList<ContentProviderOperation> ops = new ArrayList<>();
			int n = 0;
			for (int i = 0; i < liste.length(); i++) {
				JSONObject k = liste.getJSONObject(i);
				int basis = ops.size();
				ops.add(ContentProviderOperation.newInsert(alsSyncAdapter(RawContacts.CONTENT_URI))
						.withValue(RawContacts.ACCOUNT_TYPE, KONTOTYP)
						.withValue(RawContacts.ACCOUNT_NAME, KONTONAME)
						.build());
				ops.add(daten(basis, StructuredName.CONTENT_ITEM_TYPE)
						.withValue(StructuredName.DISPLAY_NAME, k.optString("name")).build());
				if (!k.optString("org").isEmpty()) {
					ops.add(daten(basis, Organization.CONTENT_ITEM_TYPE)
							.withValue(Organization.COMPANY, k.optString("org")).build());
				}
				JSONArray nummern = k.optJSONArray("nummern");
				for (int j = 0; nummern != null && j < nummern.length(); j++) {
					JSONObject nr = nummern.getJSONObject(j);
					ContentProviderOperation.Builder tel = daten(basis, Phone.CONTENT_ITEM_TYPE)
							.withValue(Phone.NUMBER, nr.optString("nummer"))
							.withValue(Phone.TYPE, telefonTyp(nr.optString("typ")));
					// Interne Nummer der Anlage (persoenlich 3HHWWP, Verwaltung 4XXX):
					// eigene Beschriftung "Rosenweg" statt "Andere"
					if ("rosenweg".equals(nr.optString("typ"))) tel.withValue(Phone.LABEL, "Rosenweg");
					ops.add(tel.build());
				}
				JSONArray mails = k.optJSONArray("emails");
				for (int j = 0; mails != null && j < mails.length(); j++) {
					ops.add(daten(basis, Email.CONTENT_ITEM_TYPE)
							.withValue(Email.ADDRESS, mails.getString(j))
							.withValue(Email.TYPE, Email.TYPE_WORK).build());
				}
				n++;
				// In Paketen schreiben: eine einzelne Transaktion mit tausenden
				// Operationen sprengt das Binder-Limit.
				if (ops.size() > 300) {
					cr.applyBatch(ContactsContract.AUTHORITY, ops);
					ops.clear();
				}
			}
			if (!ops.isEmpty()) cr.applyBatch(ContactsContract.AUTHORITY, ops);
			Anlage.prefs(c).edit().putString(PREF_VERSION, d.optString("version", "")).apply();
			Log.i(TAG, "Telefonbuch: " + weg + " ersetzt durch " + n + " Kontakte");
		} catch (Exception e) {
			Log.w(TAG, "abgleichen: " + e);
		}
	}

	private static ContentProviderOperation.Builder daten(int rawIndex, String mime) {
		return ContentProviderOperation.newInsert(alsSyncAdapter(Data.CONTENT_URI))
				.withValueBackReference(Data.RAW_CONTACT_ID, rawIndex)
				.withValue(Data.MIMETYPE, mime);
	}

	private static int telefonTyp(String t) {
		switch (t) {
			case "mobil": return Phone.TYPE_MOBILE;
			case "arbeit": return Phone.TYPE_WORK;
			case "privat": return Phone.TYPE_HOME;
			case "rosenweg": return Phone.TYPE_CUSTOM;
			default: return Phone.TYPE_OTHER;
		}
	}
}
