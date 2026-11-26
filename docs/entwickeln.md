# Rosenweg Gerät – Für Entwickler

## Bauen

```bash
# Zweig rosenweg
./gradlew assembleRelease
```

Braucht das Android NDK (native Codecs über `ndkBuild`). Signiert wird mit `keystore.properties` (nicht im Repo). Der Schlüssel muss derselbe sein wie bei den übrigen Rosenweg-Apps, weil diese nur Rosenweg-signierten Apps Zugang geben. Paketname: `ch.rosenweg4303.telefon`.

Logo: Rosenweg-Wappen (Turm, Weg, Rose auf Rot) mit dem Zeichen der App rechts unten, erzeugt mit dem Logo-Baukasten aus rosenweg-basis; dazu ein monochromes Designsymbol (Android 13+).

Logo neu erzeugen (aus dem Repo-Wurzelverzeichnis, rosenweg-basis daneben): `sh assets/logo/erzeugen.sh`. Welches Zeichen die App trägt, steht in rosenweg-basis `werkzeug/logo/android-apps.txt`.

## Schnittstellen für andere Apps

- **Portal-Anfragen für die Flutter-Apps** (ab v236): `portal_anfrage` im Provider schickt Anfragen an die Rosenweg-API im Namen der angemeldeten Person über die Telefonanlage; die App selbst bekommt kein Token. Nur für Rosenweg-signierte Apps, mit Grössen- und Mengengrenzen.
- **adb-Updater der Anlage** (ab v237): Die Telefonanlage kann neue App-Versionen selbst per adb einspielen. Ihr adb-Schlüssel wird je Telefon einmal zugelassen (nur genau dieser Fingerabdruck); nach der Installation ist das Installationsverbot sofort wieder gesetzt.

## Neue Version

APK wie oben bauen und signieren, dann `gh release create v<versionName> <datei.apk> --notes "Was ist neu"` (Tag = versionName der APK, eine APK je Release). Der Workflow `.github/workflows/apps.yml` (wiederverwendbarer Workflow `apps-release.yml` aus Rosenweg/Website) schickt sie an den App-Katalog der Telefonanlage, von dort kommt sie auf die geteilten Telefone (Rosenweg Gerät steht nicht auf www.rosenweg4303.ch/apps). Anmeldung per GitHub-OIDC, kein gespeichertes Geheimnis; die Anlage prüft Tag, Prüfsumme, Rosenweg-Signatur und Paketnamen. Der Workflow liegt im Standardzweig `master`; `gh release create` ohne `--target` setzt den Tag dort hin. Erneut senden: `gh workflow run apps.yml --ref <tag>`.

## Zweige, Herkunft und Lizenz

Fork von [Android SIP](https://github.com/hell3lazer/Android-SIP), einer modernisierten Fassung von [Sipdroid](https://github.com/i-p-tel/sipdroid). Der Zweig `master` folgt dem Original, die Rosenweg-Anpassungen liegen im Zweig `rosenweg`. Herzlichen Dank an die Sipdroid-Entwickler (Sipdroid Open Source Project, Hughes Systique, mjsip/Luca Veltri, Android Open Source Project) und an den Autor von Android SIP.

Lizenz: GNU General Public License v3.0, siehe [LICENSE.TXT](../LICENSE.TXT), mit den zusätzlichen Bedingungen von Sipdroid in [ADDITIONAL_TERMS.txt](../ADDITIONAL_TERMS.txt). Der Info-Dialog mit den Sipdroid-Hinweisen bleibt deshalb unverändert; die Rosenweg-Angaben stehen an seinem Ende.
