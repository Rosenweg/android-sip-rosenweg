# Rosenweg Gerät – Anleitung

Geräteverwaltung (Device Owner) der geteilten Smartphones der Siedlung Rosenweg in Kaiseraugst. Rosenweg Gerät sperrt das Telefon, bis sich eine Person anmeldet, installiert die Rosenweg-Apps aus dem Katalog der Telefonanlage und gibt den Apps die Zugänge der angemeldeten Person. Gleichzeitig ist es die SIP-Nebenstelle des Telefons an der Rosenweg-Telefonanlage. Grundlage ist Android SIP, eine modernisierte Fassung von Sipdroid.

## Was Rosenweg Gerät macht

- **Anmelden und Abmelden:** Sperrbildschirm mit Anmeldung per NFC-Karte (UniFi Access), QR-Code oder Rosenweg-Konto; persönliche PIN als Bildschirmsperre. Beim Abmelden werden die Daten persönlicher Apps und Web-Sitzungen gelöscht; abgemeldet wird erst, wenn alle Apps ihre Dateien hochgeladen haben.
- **App-Katalog:** Installiert die freigegebenen Apps still von der Telefonanlage (Prüfsumme kontrolliert), blendet sie ohne Anmeldung aus und entfernt Apps, die aus dem Katalog verschwinden. Andere Installationen sind gesperrt.
- **Zugänge für die Rosenweg-Apps:** Nextcloud-Zugang, persönliche Telefonnummer (Rosenweg Telefon), Messenger-Zugang (Rosenweg Messenger) sowie Einstellungen und Startseite pro Person, alles über einen nur für Rosenweg-signierte Apps zugänglichen Provider.
- **Telefon:** Nebenstelle des Geräts, Telefonbuch der Siedlung als eigenes Konto im Android-Adressbuch, "Telefon suchen" (klingelt laut, auch stumm oder bei "Nicht stören").
- **Sperrbild und Startansicht:** Rosenweg-Logo bzw. Logo der Gruppe oder des Hauses der angemeldeten Person; Startansicht mit Kacheln für die Rosenweg-Web-Apps und die installierten Apps.
- **WLAN und Wartung:** richtet das Rosenweg-WLAN mit persönlichem Schlüssel ein; Wartung über adb, zentral von der Anlage aus einschaltbar.
- **Karten:** Schnittstelle für [Rosenweg Karten](https://github.com/Rosenweg/rosenweg-karten) (NFC-Karten nachschlagen, zuordnen, entziehen).
- **An- und Abmelden Schritt für Schritt:** eigener Bildschirm mit den Schritten (Anlage, WLAN, Apps und Rechte, Telefonkonto, Nextcloud, PIN, Startseite) und ihrem Stand; bei einem Fehler hält er an (Nochmals versuchen / Abbrechen).

Mehr zur Nutzung der geteilten Telefone: [www.rosenweg4303.ch/smartphones](https://www.rosenweg4303.ch/smartphones).

## Installation

Rosenweg Gerät wird nur auf den geteilten Rosenweg-Telefonen eingerichtet, und zwar als Device Owner (auf einem frisch zurückgesetzten Telefon). Das macht die Technik. Für ein eigenes Handy ist die App nicht gedacht; dort genügen die einzelnen Rosenweg-Apps von [www.rosenweg4303.ch/apps](https://www.rosenweg4303.ch/apps).
