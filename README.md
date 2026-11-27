<p align="center"><img src="assets/logo/rosenweg-geraet-512.png" width="96" alt="Logo"></p>
<h1 align="center">Rosenweg Gerät</h1>
<p align="center">Geräteverwaltung der geteilten Rosenweg-Smartphones: Anmelden per Karte, Rosenweg-Apps, Zugänge der Person – und die Nebenstelle an der Telefonanlage.</p>
<p align="center"><a href="https://www.rosenweg4303.ch/smartphones">Geteilte Telefone</a> · <a href="docs/anleitung.md">Anleitung</a></p>

## Inhalt

- [Überblick](#überblick)
- [Plattformen](#plattformen)
- [Rechte und Gruppen](#rechte-und-gruppen)
- [Bedienung](#bedienung)
- [Zugänge für die Rosenweg-Apps](#zugänge-für-die-rosenweg-apps)
- [Wartung und Updates](#wartung-und-updates)
- [Aufbau](#aufbau)
- [Bauen](#bauen)
- [CI und Release](#ci-und-release)
- [Konfiguration](#konfiguration)
- [Herkunft und Lizenz](#herkunft-und-lizenz)

## Überblick

Rosenweg Gerät ist der Device Owner der geteilten Smartphones im Rosenweg. Es sperrt das Telefon, bis sich eine Person anmeldet, installiert die Rosenweg-Apps aus dem Katalog der Telefonanlage und gibt den Apps die Zugänge der angemeldeten Person. Gleichzeitig ist es die SIP-Nebenstelle des Telefons an der Rosenweg-Telefonanlage. Grundlage ist Android SIP, eine modernisierte Fassung von Sipdroid.

Was die App kann:

- **Anmelden und Abmelden:** Sperrbildschirm mit Anmeldung per NFC-Karte (UniFi Access), QR-Code oder Rosenweg-Konto; persönliche PIN als Bildschirmsperre.
- **Sauber abmelden:** Daten persönlicher Apps und Web-Sitzungen werden gelöscht – erst, wenn alle Apps ihre Dateien hochgeladen haben.
- **App-Katalog:** installiert die freigegebenen Apps still von der Telefonanlage, blendet sie ohne Anmeldung aus, entfernt verschwundene; andere Installationen sind gesperrt.
- **Zugänge für die Apps:** Nextcloud, persönliche Telefonnummer, Messenger, Einstellungen und Startseite pro Person.
- **Telefon:** Nebenstelle des Geräts, Telefonbuch der Siedlung im Android-Adressbuch, „Telefon suchen“ (klingelt laut, auch stumm oder bei „Nicht stören“).
- **Startansicht:** Kacheln für die Rosenweg-Web-Apps und die installierten Apps; Sperrbild mit dem Logo der Gruppe oder des Hauses.
- **WLAN:** richtet das Rosenweg-WLAN mit persönlichem Schlüssel ein.

## Plattformen

| Plattform | Stand |
|---|---|
| Geteilte Rosenweg-Telefone, Android 5 und neuer (API 21–35; im Einsatz auf Samsung Galaxy A15) | ja, als Device Owner auf einem frisch zurückgesetzten Telefon; Einrichtung durch die Technik |
| Eigenes Handy | nein – dort genügen die einzelnen Rosenweg-Apps von [rosenweg4303.ch/apps](https://www.rosenweg4303.ch/apps) |
| /apps | nein, Rosenweg Gerät kommt nur über den App-Katalog der Telefonanlage |

## Rechte und Gruppen

Die Rechte vergibt die Anlage nach den Gruppen der angemeldeten Person (live aus Authentik).

| Wer | darf |
|---|---|
| alle mit Rosenweg-Karte oder -Konto | sich an einem geteilten Telefon anmelden; eigene Sitzungen und Anmelde-QR auf [/smartphones](https://www.rosenweg4303.ch/smartphones) |
| Gruppen je Katalog-Eintrag | die freigegebenen Apps sehen (z. B. Rosenweg Karten nur `technik`, `präsident`) |
| Technik, Präsident | Karten nachschlagen, zuordnen, entziehen (über [Rosenweg Karten](https://github.com/Rosenweg/rosenweg-karten)); geteilte Telefone, App-Katalog, Ausblendlisten, Wartung und adb-Updater auf /smartphones bzw. im PBX-Admin |
| Rosenweg-signierte Apps | den Provider von Rosenweg Gerät nutzen (`ch.rosenweg4303.permission.ANLAGE`) |

## Bedienung

| Schritt | So geht’s |
|---|---|
| Anmelden | Rosenweg-Karte hinten ans Telefon halten, QR-Code scannen oder Rosenweg-Konto. Die persönliche PIN dient danach als Bildschirmsperre. |
| Schritt für Schritt | Ein eigener Bildschirm zeigt die Schritte (Anlage, WLAN, Apps und Rechte, Telefonkonto, Nextcloud, PIN, Startseite) mit Stand; bei einem Fehler: Nochmals versuchen / Abbrechen. |
| Angemeldet | Startansicht mit Kacheln; angezeigt werden die interne Telefonnummer der Person und die Nebenstelle des Geräts. |
| Telefon suchen | über die Anlage; das Telefon klingelt laut. |
| Abmelden | Rosenweg Gerät wartet auf das Hochladen, löscht dann persönliche Daten und Web-Sitzungen. |

Mehr zur Nutzung: [rosenweg4303.ch/smartphones](https://www.rosenweg4303.ch/smartphones). Ausführlich: [docs/anleitung.md](docs/anleitung.md).

## Zugänge für die Rosenweg-Apps

Alles läuft über einen Provider (`AnlageProvider`), den nur Rosenweg-signierte Apps erreichen.

| Zugang | für |
|---|---|
| Nextcloud | Kamera, Galerie, Dateien … |
| persönliche Telefonnummer (`sip_person`) | Rosenweg Telefon – zweites SIP-Konto neben der Nebenstelle |
| Messenger-Zugang | Rosenweg Messenger |
| Einstellungen und Startseite pro Person | alle Apps |
| `portal_anfrage` (ab v236) | Flutter-Apps: Anfragen an die Rosenweg-API im Namen der Person über die Anlage; die App bekommt kein Token. Mit Grössen- und Mengengrenzen. |
| `karten_*` | Rosenweg Karten (nur Technik, Präsident) |

Beim An- und Abmelden schickt Rosenweg Gerät `ch.rosenweg4303.ANGEMELDET` bzw. `ABGEMELDET`; Apps wie der Messenger löschen dann selbst und behalten ihre Sonderrechte.

## Wartung und Updates

| Weg | Was |
|---|---|
| Eigener Abgleich | Katalog beim Start, bei der Anmeldung, sonst höchstens alle 6 Stunden; still installiert als Device Owner (Prüfsumme kontrolliert) |
| Wartung per USB | `ADB_ABGLEICH`, `ADB_INSTALLATION`, `ADB_INSTALLATION_ENDE` per `adb shell am broadcast` |
| Wartung übers Netz (ab v217) | adb auf Port 5555, zentral auf /smartphones ein- und ausschaltbar; nach einem Neustart stellt Gerät den Port selbst wieder her (ab v224) |
| adb-Updater der Anlage (ab v237) | spielt neue Versionen selbst ein, nur bei abgemeldetem Telefon; der adb-Schlüssel der Anlage wird je Telefon einmal zugelassen (nur genau dieser Fingerabdruck), danach ist das Installationsverbot sofort wieder gesetzt |

Geräte nicht per `adb reboot` neu starten, solange jemand daran arbeitet. Einzelheiten: Rosenweg/Website, `pbx/GETEILTE-TELEFONE.md`.

## Aufbau

| Teil | Inhalt |
|---|---|
| `app/src/main/java/org/sipdroid/`, `org/zoolu/`, `com/jstun/` | SIP-Kern aus Sipdroid / Android SIP (mjsip, STUN, Codecs) |
| `…/sipua/rosenweg/SperrActivity.java`, `Sperre.java`, `NfcKarte.java`, `AnmeldeDienst.java` | Sperrbildschirm, Kartenleser, An- und Abmelden |
| `…/rosenweg/Katalog.java`, `Ausblenden.java`, `InstallEmpfaenger.java` | App-Katalog, Ausblendlisten, stille Installation |
| `…/rosenweg/AnlageProvider.java`, `Anlage.java`, `KontoDienst.java` | Provider für die Apps, Verbindung zur Anlage |
| `…/rosenweg/Kontakte.java`, `Suchen.java` | Telefonbuch, Telefon suchen |
| `…/rosenweg/Wlan.java`, `AdbFreigabe.java`, `AdbSelbst.java`, `Nachlauf.java` | WLAN, Wartung, Aufräumen nach dem Abmelden |
| `…/rosenweg/RosenwegActivity.java`, `WebAppActivity.java`, `Sperrbild.java` | Startansicht, Web-Apps, Sperrbild |
| `…/rosenweg/Fernwartung.java`, `FernwartungDienst.java`, `FernwartungBand.java` | Fernwartung: dauerhafter adb-Tunnel zur adb-zentrale, Band „Fernwartung aktiv“, Not-Aus |
| `app/src/main/jniLibs/*/librwtunnel_jni.so`, `…/ch/rosenweg4303/rwtunnel/` | Telefonseite des Tunnels (rwtunnel aus Rosenweg/rosenweg-vm, WireGuard im Prozess); Herkunft in `jniLibs/RWTUNNEL.txt`, erneuern mit `werkzeuge/rwtunnel-holen.sh` |
| `app/src/main/jni` (NDK) | native Codecs |
| `assets/logo/` | Logo aus dem Baukasten von rosenweg-basis: Turm, Weg und Rose mit Telefon-Schloss-Plakette, dazu ein monochromes Designsymbol (Android 13+) |

Paketname `ch.rosenweg4303.telefon` (Sipdroid verlangt einen eigenen Paketnamen).

## Bauen

```bash
./gradlew assembleRelease
```

Braucht das Android NDK (native Codecs über `ndkBuild`). Signiert wird mit `keystore.properties` (nicht im Repo). Der Schlüssel muss derselbe sein wie bei den übrigen Rosenweg-Apps, weil diese nur Rosenweg-signierten Apps Zugang geben. Logo neu erzeugen (rosenweg-basis daneben): `sh assets/logo/erzeugen.sh`.

Mehr: [docs/entwickeln.md](docs/entwickeln.md).

## CI und Release

| Workflow | Auslöser | Ergebnis |
|---|---|---|
| `apps.yml` | GitHub-Release veröffentlicht, oder von Hand `gh workflow run apps.yml --ref <tag>` | APK geht in den App-Katalog der Telefonanlage und von dort auf die geteilten Telefone – nicht auf /apps |

Ablauf einer neuen Version:

1. APK bauen und mit dem Rosenweg-Schlüssel signieren.
2. `gh release create v<versionName> <datei.apk> --notes "Was ist neu"` – Tag = `versionName` der APK, eine APK je Release, Tag auf `main`.
3. `apps.yml` ruft `apps-release.yml` aus Rosenweg/Website auf. Anmeldung per GitHub-OIDC, kein gespeichertes Geheimnis; die Anlage prüft Tag, Prüfsumme, Rosenweg-Signatur und Paketnamen.

## Konfiguration

| Wo | Name | Wozu |
|---|---|---|
| Repo-Wurzel (nicht im Repo) | `keystore.properties` | Rosenweg-Signierschlüssel |
| `app/build.gradle` | `applicationId`, `versionCode`, `versionName` | Paketname und Version |
| Anlage (Rosenweg/Website, pbx-api) | `PBX_SIP_TRANSPORT`, `PBX_ADB_NETZE`, `PBX_ADB_NACHT` | SIP-Transport der geteilten Telefone; Netze und Nachtfenster des adb-Updaters |

Für `apps.yml` braucht es kein GitHub-Secret (OIDC).

## Herkunft und Lizenz

Fork von [Android SIP](https://github.com/hell3lazer/Android-SIP), einer modernisierten Fassung von [Sipdroid](https://github.com/i-p-tel/sipdroid). Die Rosenweg-Anpassungen sind in `main` gemergt; `main` ist der Hauptzweig. Herzlichen Dank an die Sipdroid-Entwickler (Sipdroid Open Source Project, Hughes Systique, mjsip/Luca Veltri, Android Open Source Project) und an den Autor von Android SIP.

Lizenz: GNU General Public License v3.0, siehe [LICENSE.TXT](LICENSE.TXT), mit den zusätzlichen Bedingungen von Sipdroid in [ADDITIONAL_TERMS.txt](ADDITIONAL_TERMS.txt). Der Info-Dialog mit den Sipdroid-Hinweisen bleibt deshalb unverändert; die Rosenweg-Angaben stehen an seinem Ende.
