# Notizen – selbst gehosteter Notizdienst im Stil von Google Notizen

Kachel-Layout (Masonry), farbige Notizen, Checklisten, Anheften, Labels, Live-Suche,
Archiv, Papierkorb (7 Tage), Drag & Drop, Rückgängig-Leiste, Hell/Dunkel-Modus, mobil nutzbar.
Backend: Python + FastAPI + SQLite. Inhalte werden mit AES-256-GCM verschlüsselt gespeichert.

## Dateien

```
notizen/
├── app.py            Webserver (FastAPI, direkt startbar)
├── admin.py          Verwaltungsprogramm (Benutzer, Schlüssel, Backups)
├── core.py           gemeinsame Basis: Datenbank, Verschlüsselung, Passwort-Hashing
├── requirements.txt
└── static/           Frontend (index.html, style.css, app.js, logo.svg)
```

## Installation (ohne Docker)

Voraussetzung: Python 3.10 oder neuer.

```bash
cd notizen
python3 -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

Benötigte Pakete: `fastapi`, `uvicorn`, `pydantic`, `cryptography`, `argon2-cffi`.

## Einrichten und starten

```bash
python admin.py init               # erzeugt data/master.key und data/notizen.db
python admin.py user add anna      # Passwort wird abgefragt (mind. 8 Zeichen)
python app.py                      # http://127.0.0.1:8000
```

Im ganzen Netzwerk erreichbar: `python app.py --host 0.0.0.0 --port 8000`.
Für den Zugriff über das Internet einen Reverse-Proxy mit HTTPS davorschalten (z. B. Caddy:
`caddy reverse-proxy --from notizen.example.de --to 127.0.0.1:8000`). Das Sitzungs-Cookie
wird hinter HTTPS automatisch als `Secure` markiert.

Tastenkürzel: `c` neue Notiz, `l` neue Liste, `/` Suche, `Esc` schließen.

## Verwaltungsprogramm

| Befehl | Zweck |
|---|---|
| `python admin.py user add NAME` | Benutzer anlegen |
| `python admin.py user list` | Benutzer mit Notiz- und Sitzungsanzahl |
| `python admin.py user passwd NAME` | Passwort zurücksetzen (beendet alle Sitzungen) |
| `python admin.py user delete NAME` | Benutzer inkl. Notizen löschen |
| `python admin.py user logout NAME` / `--all` | Sitzungen beenden |
| `python admin.py key status` | Schlüsselring, Fingerabdrücke, Datensätze je Schlüssel |
| `python admin.py key rotate` | neuen Master-Schlüssel erzeugen, alle Daten umschlüsseln |
| `python admin.py key export DATEI` | Schlüsselring exportieren (separat sichern!) |
| `python admin.py key import DATEI` | Schlüssel einspielen (z. B. für alte Backups) |
| `python admin.py backup create [--include-key] [--keep N]` | Backup als ZIP (läuft auch bei laufendem Server) |
| `python admin.py backup list` | Backups anzeigen |
| `python admin.py backup restore DATEI [--with-key]` | Backup zurückspielen (Server vorher stoppen) |

Für Skripte: `echo "Passwort" | python admin.py user add NAME --password-stdin`.
Alle Befehle zeigen mit `-h` ihre Optionen.

Automatisches tägliches Backup per cron (behält die letzten 14):
```
15 3 * * * cd /pfad/zu/notizen && .venv/bin/python admin.py backup create --keep 14
```

## Sicherheit

- **Verschlüsselung:** AES-256-GCM (authentifiziert, zufälliger Nonce pro Speichervorgang) für
  Titel, Text, Checklisten und Labelnamen. `cryptography.fernet` wurde bewusst nicht verwendet,
  da Fernet intern nur AES-128 nutzt. Unverschlüsselt bleiben nur Metadaten
  (Farbe, angeheftet, archiviert, Zeitstempel, Reihenfolge) und Benutzernamen.
- **Master-Schlüssel:** `data/master.key` (Rechte 600). Ohne ihn sind die Daten verloren –
  getrennt von der Datenbank sichern. Bei `key rotate` wird der alte Schlüsselring unter
  `data/backups/keys/` archiviert, damit ältere Backups wiederherstellbar bleiben.
  Der Server übernimmt einen rotierten Schlüssel ohne Neustart.
- **Passwörter:** Argon2id (`argon2-cffi`). Fehlt das Paket, wird automatisch scrypt aus der
  Python-Standardbibliothek verwendet und beim nächsten Login auf Argon2id umgestellt.
- **Sitzungen:** zufällige Tokens, in der DB nur als SHA-256-Hash; Cookie `HttpOnly`,
  `SameSite=Strict`. Zusätzlicher CSRF-Header, Content-Security-Policy, Sperre nach
  10 Fehlversuchen in 15 Minuten.

Hinweis: Das ist Verschlüsselung „at rest“ – wer Zugriff auf Datenbank **und** Schlüsseldatei
hat (z. B. root auf dem Server), kann die Notizen lesen.

## Konfiguration (Umgebungsvariablen, optional)

`NOTIZEN_DATA_DIR`, `NOTIZEN_DB`, `NOTIZEN_KEY_FILE`, `NOTIZEN_BACKUP_DIR`,
`NOTIZEN_SESSION_DAYS` (Standard 30), `NOTIZEN_HOST`, `NOTIZEN_PORT`,
`NOTIZEN_SECURE_COOKIE=1` (Secure-Cookie erzwingen).
