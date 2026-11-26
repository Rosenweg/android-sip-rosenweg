package org.sipdroid.sipua.rosenweg;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.NetworkErrorException;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

/**
 * Minimaler Konto-Dienst fuer das Konto "Rosenweg" (Telefonbuch). Android
 * verlangt ihn fuer eigene Kontotypen; anmelden kann man sich darueber nicht.
 */
public class KontoDienst extends Service {
	private Stub stub;

	@Override
	public void onCreate() {
		stub = new Stub(this);
	}

	@Override
	public IBinder onBind(Intent intent) {
		return stub.getIBinder();
	}

	static class Stub extends AbstractAccountAuthenticator {
		Stub(Context c) {
			super(c);
		}

		@Override public Bundle editProperties(AccountAuthenticatorResponse r, String t) { return null; }
		@Override public Bundle addAccount(AccountAuthenticatorResponse r, String t, String a, String[] f, Bundle o) { return null; }
		@Override public Bundle confirmCredentials(AccountAuthenticatorResponse r, Account a, Bundle o) { return null; }
		@Override public Bundle getAuthToken(AccountAuthenticatorResponse r, Account a, String t, Bundle o) throws NetworkErrorException { return null; }
		@Override public String getAuthTokenLabel(String t) { return null; }
		@Override public Bundle updateCredentials(AccountAuthenticatorResponse r, Account a, String t, Bundle o) { return null; }
		@Override public Bundle hasFeatures(AccountAuthenticatorResponse r, Account a, String[] f) { return null; }
	}
}
