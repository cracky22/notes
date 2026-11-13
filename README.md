# Notizen - selbst gehosteter Notizdienst im Material 3 Stil

Kachel-Layout mit Animationen, farbige Notizen, Checklisten, Formatierung, Bilder, Anheften, Labels,
Archiv, Papierkorb, Mehrfachauswahl, Suche nach Typen/Labels/Dingen/Farben, frei belegbare
Tastenkürzel, Profilbilder, Versionsverlauf – und **Echtzeit-Zusammenarbeit** mit Live-Cursorn
zwischen allen Benutzern des Servers. Inhalte liegen AES-256-verschlüsselt in SQLite.

## Dateien

```
notizen/
├── app.py            Webserver (FastAPI + WebSocket, direkt startbar)
├── admin.py          Verwaltungsprogramm (Benutzer, Schlüssel, Backups)
├── core.py           Datenbank, Migration, Verschlüsselung, Passwörter, Bildverarbeitung
├── search_index.py   Getrennte, verschlüsselte Suchindizes je Benutzer
├── ot.py             Operational Transformation (konfliktfreie Zusammenarbeit)
├── requirements.txt
├── tests/            Tests für Konfliktfreiheit, Kompression, Migration und Suchindex
└── static/           Frontend ohne externe Abhängigkeiten
    ├── index.html, style.css, logo.svg
    └── js/  app.js · noteeditor.js · editor.js · delta.js · sync.js · dialogs.js · dom.js · state.js
```

## Installation (ohne Docker)

Voraussetzung: Python 3.10 oder neuer.

```bash
cd notizen
python3 -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

Pakete: `fastapi`, `uvicorn`, `websockets` (Echtzeit), `pydantic`, `cryptography`, `argon2-cffi`, `Pillow` (Bilder).

## Einrichten und starten

```bash
python admin.py init               # erzeugt data/master.key und data/notizen.db
python admin.py user add anna      # Passwort wird abgefragt (mind. 8 Zeichen)
python admin.py user add bob
python app.py                      # http://127.0.0.1:8000
```

Im ganzen Netzwerk erreichbar: `python app.py --host 0.0.0.0 --port 8000`.
Für den Zugriff über das Internet einen Reverse-Proxy mit HTTPS davorschalten, der WebSockets
durchreicht (z. B. Caddy: `caddy reverse-proxy --from notizen.example.de --to 127.0.0.1:8000`).

**Update:** Neue Dateien einspielen und starten. Die Datenbank wird automatisch auf das aktuelle
Schema migriert; vor einer Migration wird unter `data/backups/` eine Sicherung angelegt.

## Funktionen im Überblick

| Bereich | Bedienung |
|---|---|
| Teilen & Zusammenarbeit | Symbol „Mitbearbeiter“ (Person mit +) auf Karte/Notiz → Benutzer suchen → Speichern. Alle Teilnehmer sehen Änderungen sofort, mit Live-Cursor (Profilbild + Name) und farbigem Ring für „bearbeitet gerade“. Anheften, Archiv, Labels und Reihenfolge bleiben wie im Original pro Person. |
| Formatierung | Symbol „A“ in der Notiz: Überschrift 1/2, normaler Text, fett, kursiv, unterstrichen, Formatierung entfernen |
| Bilder | Bildsymbol, Einfügen aus der Zwischenablage oder Drag & Drop; Klick öffnet die Großansicht |
| Mehrfachauswahl | Häkchen oben links auf der Karte (beim Überfahren), Umschalt+Klick wählt Bereiche |
| Suche | Klick ins Suchfeld zeigt Typen (Listen, Bilder, Links, Geteilt), Labels, Dinge (Bücher, Essen, Reisen …) und Farben |
| Suchindex | Volltextsuche läuft über einen invertierten Index mit eigenem Bereich je Benutzer. Notizwortlisten und nutzerspezifische HMAC-Schlüssel sind AES-verschlüsselt; die Terme selbst werden nur als HMAC-Prüfsummen gespeichert. Der Server baut den Index beim Start im Hintergrund auf und hält Änderungen laufend nach. Im Kontomenü kann der eigene Index neu aufgebaut werden (höchstens alle 10 Minuten); Admins können den Index je Benutzer im Adminpanel zurücksetzen. Ein Reset löscht den bisherigen Index und startet den Neuaufbau. |
| Versionsverlauf | ⋮ → Versionsverlauf: Bearbeitungssitzungen mit Bearbeitern, Vorschau, Wiederherstellen |
| Tastenkürzel | `?` zeigt alle, Zahnrad → Einstellungen zum Umbelegen (wird pro Benutzer gespeichert) |
| Profilbild | Avatar oben rechts → Profilbild ändern (JPG/PNG/WebP, wird quadratisch auf 256 px skaliert) |

Standard-Tastenkürzel (wie im Original): `C` neue Notiz, `L` neue Liste, `/` suchen, `J`/`K`
nächste/vorige Notiz, `Umschalt+J/K` verschieben, `Enter` öffnen, `X` auswählen, `Strg+A` alle,
`E` archivieren, `#` löschen, `F` anheften, `Strg+G` Ansicht, `Strg+Umschalt+8` Kästchen,
`Strg+B/I/U` Formatierung, `Strg+Alt+1/2/0` Überschriften, `Strg+Z`/`Strg+Umschalt+Z`, `Esc` schließen.

## Wie die konfliktfreie Zusammenarbeit funktioniert

Clients senden nie ganze Notizen, sondern nur **Befehle** – „füge an Position 12 ‚Hallo‘ ein“,
„lösche 3 Zeichen“, „formatiere fett“, „lege Listenelement an“, „hake Element ab“ (`ot.py`,
`static/js/delta.js`). Der Server vergibt fortlaufende Revisionen und transformiert gleichzeitige
Befehle gegeneinander (Operational Transformation). Dadurch landen alle
Teilnehmer garantiert beim identischen Ergebnis, ohne Sperren und ohne Mergekonflikte.
Verbindungsabbrüche werden abgefangen: Änderungen bleiben im Browser vorgemerkt und werden nach
der Wiederverbindung nachgereicht. `python tests/test_ot.py` prüft die Konvergenz mit 5000
Zufallsfällen.
Die Kompressions-/Migrationstests laufen mit `python tests/test_compression.py`, der Suchindex mit
`python tests/test_search_index.py`.

## Verwaltungsprogramm

| Befehl | Zweck |
|---|---|
| `python admin.py user add NAME` | Benutzer anlegen |
| `python admin.py user list` | Benutzer mit eigenen/geteilten Notizen, Sitzungen, Profilbild |
| `python admin.py user admin NAME` | Administratorrechte für die Web-Verwaltung vergeben (`--revoke` entzieht sie) |
| `python admin.py user passwd NAME` | Passwort zurücksetzen (beendet alle Sitzungen) |
| `python admin.py user delete NAME` | Benutzer inkl. eigener Notizen löschen |
| `python admin.py user logout NAME` / `--all` | Sitzungen beenden |
| `python admin.py key status` | Schlüsselring, Fingerabdrücke, Datensätze je Schlüssel |
| `python admin.py key rotate` | neuen Master-Schlüssel erzeugen, alles umschlüsseln (Notizen, Bilder, Verlauf, Protokoll, verschlüsselter Suchindex-Schlüssel) |
| `python admin.py key export DATEI` | Schlüsselring exportieren (separat sichern!) |
| `python admin.py key import DATEI` | Schlüssel einspielen (z. B. für alte Backups) |
| `python admin.py backup create [--include-key] [--keep N]` | Backup als ZIP (läuft auch bei laufendem Server) |
| `python admin.py backup list` | Backups anzeigen |
| `python admin.py backup restore DATEI [--with-key]` | Backup zurückspielen (Server vorher stoppen) |

Für Skripte: `echo "Passwort" | python admin.py user add NAME --password-stdin`.
Tägliches Backup per cron (behält die letzten 14):
```
15 3 * * * cd /pfad/zu/notizen && .venv/bin/python admin.py backup create --keep 14
```

## Neue Konto- und Verwaltungsfunktionen

- Im Kontomenü werden belegter Speicher und ggf. das Kontingent lesbar angezeigt.
- Geräteanmeldung per QR-Code ist nur nach ausdrücklicher Bestätigung auf einem bereits angemeldeten Gerät möglich.
- Angemeldete Geräte können einzeln abgemeldet werden. Anmeldelinks laufen standardmäßig nach 30 Tagen ab und sind im Kontomenü widerrufbar; sie sind wie Passwörter zu behandeln.
- Der Suchindex liegt in eigenen Datenbankeinträgen pro Nutzer; Notizwortlisten und nutzerspezifische HMAC-Schlüssel werden wie Notizinhalte AES-verschlüsselt gespeichert, Terme sind als HMAC-Prüfsummen abgelegt. Jede geteilte Notiz wird separat in den Indizes der jeweiligen Teilnehmer geführt. Bei einem Neuaufbau wird der bisherige Nutzerindex gelöscht und im Hintergrund stapelweise neu erzeugt; der Fortschritt ist im Profil und im Adminpanel sichtbar. Suchbegriffe werden serverseitig gegen den Index geprüft, sodass auch Archivnotizen gefunden werden, ohne ihren vollständigen Text zuerst in den Browser zu laden.
- Notizen werden in Seiten geladen; der erste Abschnitt erscheint sofort, weitere Seiten werden mit Fortschrittsanzeige nachgeladen. Archivierte Notizinhalte werden erst beim Anzeigen nachgeladen; bei einem noch nicht fertigen Index wird die Suche vorübergehend per Direktladen der Archivnotizen abgesichert. Ist eine Notiz bei allen Beteiligten archiviert, komprimiert der Server Inhalt und Versionsstände vor der bestehenden AES-Verschlüsselung (nur wenn die Kompression mindestens 5 % spart). Beim Öffnen oder Bearbeiten entpackt der Server sie transparent; beim Speichern wird sie wieder komprimiert. Wird die Notiz von einer Person entarchiviert oder ein aktiver Mitbearbeiter hinzugefügt, wird der aktuelle Inhalt wieder unkomprimiert gespeichert, sofern das Kompressionskriterium dann nicht mehr erfüllt ist. Bereits vorhandene passende Notizen komprimiert der Server nach dem Update schrittweise im Hintergrund.
- Links in Notizen erhalten eine lokale Vorschau aus Domain, Pfad und Notizkontext. Es wird dafür keine externe Vorschau-/Tracking-Anfrage ausgelöst.
- Administration unter `/notes/admin` (alternativ `/admin`). Admins können dort normale Nutzerkonten anlegen und deren Passwort zurücksetzen; dabei werden Sitzungen und vorhandene Anmeldelinks dieses Kontos widerrufen. Das eigene aktive Admin-Gerät bleibt beim Zurücksetzen des eigenen Passworts angemeldet. Nach dem ersten Benutzer `python admin.py user admin NAME` ausführen. Pro Nutzer kann dort ein Speicherkontingent gesetzt und der Verbrauch geprüft werden. Unbegrenzt bleibt der Standard. Das Dashboard zeigt zusätzlich die CPU-Momentaufnahme, Linux-Load-Averages für 1/5/15 Minuten, Host-RAM aus `/proc/meminfo` und nach Möglichkeit RAM/CPU-Zeit des `notizen.service` über `systemctl status notizen` bzw. `systemctl show`; wenn systemd-Werte fehlen, wird der Prozess-RSS als gekennzeichneter Fallback angezeigt. Diese Messwerte sind nur für Administratoren sichtbar.
- Falls die Anwendung hinter einem Proxy-Prefix veröffentlicht wird, `NOTIZEN_PUBLIC_URL` auf die vollständige externe Basis-URL setzen, z. B. `https://example.org/notizen`. Die Apache-Regel muss `/notes/admin` an den Backend-Pfad `/admin` weiterleiten (statt auf `/notizen` umzuleiten); `/api/` und `/static/` müssen wie für die Anwendung üblich erreichbar bleiben.

## Sicherheit

- **Verschlüsselung:** AES-256-GCM (authentifiziert, zufälliger Nonce je Speichervorgang) für
  Titel, Text, Checklisten, Labelnamen, Bilder, Profilbilder, Versionsverlauf und Befehlsprotokoll.
  `cryptography.fernet` wurde bewusst nicht verwendet, da Fernet intern nur AES-128 nutzt.
  Unverschlüsselt bleiben Metadaten (Farbe, Status, Zeitstempel, Freigaben) und Benutzernamen.
- **Master-Schlüssel:** `data/master.key` (Rechte 600) getrennt von der Datenbank sichern.
  Rotation läuft ohne Server-Neustart; alte Schlüssel werden archiviert.
- **Passwörter:** Argon2id (Fallback scrypt).
- **Bilder:** werden serverseitig mit Pillow geprüft, gedreht, verkleinert (max. 2560 px) und neu
  kodiert – dabei gehen EXIF-/GPS-Metadaten verloren.
- **Web:** HttpOnly-/SameSite=Strict-Cookies, CSRF-Header, strenge Content-Security-Policy,
  WebSocket-Origin-Prüfung, Sperre nach 10 Fehlversuchen. HTML, Skripte und Styles werden mit
  `Cache-Control: no-store` und `Pragma: no-cache` ausgeliefert, damit nach Updates immer die
  aktuelle Version lädt.

Hinweis: Das ist Verschlüsselung „at rest“ – wer Datenbank **und** Schlüsseldatei hat, kann lesen.

## Konfiguration (Umgebungsvariablen, optional)

`NOTIZEN_DATA_DIR`, `NOTIZEN_DB`, `NOTIZEN_KEY_FILE`, `NOTIZEN_BACKUP_DIR`,
`NOTIZEN_SESSION_DAYS` (Standard 30), `NOTIZEN_HOST`, `NOTIZEN_PORT`,
`NOTIZEN_SECURE_COOKIE=1` (Secure-Cookie erzwingen), `NOTIZEN_PUBLIC_URL` (öffentliche URL inkl. Proxy-Prefix für QR- und Anmeldelinks).
