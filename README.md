# Notizen – selbst gehosteter Notizdienst im Stil von Google Notizen

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
├── ot.py             Operational Transformation (konfliktfreie Zusammenarbeit)
├── requirements.txt
├── tests/test_ot.py  Selbsttest der Konfliktfreiheit
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

**Update von der ersten Version:** Einfach die neuen Dateien einspielen und starten – die
Datenbank wird automatisch auf Schema v2 migriert, vorher wird unter `data/backups/` eine
Sicherung angelegt.

## Funktionen im Überblick

| Bereich | Bedienung |
|---|---|
| Teilen & Zusammenarbeit | Symbol „Mitbearbeiter“ (Person mit +) auf Karte/Notiz → Benutzer suchen → Speichern. Alle Teilnehmer sehen Änderungen sofort, mit Live-Cursor (Profilbild + Name) und farbigem Ring für „bearbeitet gerade“. Anheften, Archiv, Labels und Reihenfolge bleiben wie im Original pro Person. |
| Formatierung | Symbol „A“ in der Notiz: Überschrift 1/2, normaler Text, fett, kursiv, unterstrichen, Formatierung entfernen |
| Bilder | Bildsymbol, Einfügen aus der Zwischenablage oder Drag & Drop; Klick öffnet die Großansicht |
| Mehrfachauswahl | Häkchen oben links auf der Karte (beim Überfahren), Umschalt+Klick wählt Bereiche |
| Suche | Klick ins Suchfeld zeigt Typen (Listen, Bilder, Links, Geteilt), Labels, Dinge (Bücher, Essen, Reisen …) und Farben |
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
Befehle gegeneinander (Operational Transformation, Prinzip von Google Docs). Dadurch landen alle
Teilnehmer garantiert beim identischen Ergebnis, ohne Sperren und ohne Mergekonflikte.
Verbindungsabbrüche werden abgefangen: Änderungen bleiben im Browser vorgemerkt und werden nach
der Wiederverbindung nachgereicht. `python tests/test_ot.py` prüft die Konvergenz mit 5000
Zufallsfällen.

## Verwaltungsprogramm

| Befehl | Zweck |
|---|---|
| `python admin.py user add NAME` | Benutzer anlegen |
| `python admin.py user list` | Benutzer mit eigenen/geteilten Notizen, Sitzungen, Profilbild |
| `python admin.py user passwd NAME` | Passwort zurücksetzen (beendet alle Sitzungen) |
| `python admin.py user delete NAME` | Benutzer inkl. eigener Notizen löschen |
| `python admin.py user logout NAME` / `--all` | Sitzungen beenden |
| `python admin.py key status` | Schlüsselring, Fingerabdrücke, Datensätze je Schlüssel |
| `python admin.py key rotate` | neuen Master-Schlüssel erzeugen, alles umschlüsseln (Notizen, Bilder, Verlauf, Protokoll) |
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
`NOTIZEN_SECURE_COOKIE=1` (Secure-Cookie erzwingen).
