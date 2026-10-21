"""
core.py – gemeinsame Basis für den Webserver (app.py) und das Verwaltungsprogramm (admin.py).

Enthält:
  * Konfiguration (über Umgebungsvariablen überschreibbar)
  * SQLite-Schema, Verbindungsaufbau und automatische Migration
  * AES-256-GCM-Verschlüsselung mit Schlüsselring (ermöglicht Schlüsselrotation)
  * Argon2id-Passwort-Hashing (Fallback: scrypt)
  * Bildverarbeitung (Pillow): Prüfen, Drehen, Verkleinern, Metadaten entfernen
"""
from __future__ import annotations

import base64
import hashlib
import io
import json
import os
import re
import secrets
import sqlite3
import threading
import time
import warnings
from datetime import datetime
from pathlib import Path

try:  # Argon2id ist empfohlen; ohne das Paket wird automatisch scrypt verwendet
    from argon2 import PasswordHasher
    from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError
except ImportError:  # pragma: no cover
    PasswordHasher = None  # type: ignore[assignment,misc]
    InvalidHashError = VerificationError = VerifyMismatchError = Exception  # type: ignore[misc]
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

import ot

# --------------------------------------------------------------------------- #
# Konfiguration
# --------------------------------------------------------------------------- #
BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = Path(os.environ.get("NOTIZEN_DATA_DIR", BASE_DIR / "data"))
DB_PATH = Path(os.environ.get("NOTIZEN_DB", DATA_DIR / "notizen.db"))
KEY_PATH = Path(os.environ.get("NOTIZEN_KEY_FILE", DATA_DIR / "master.key"))
BACKUP_DIR = Path(os.environ.get("NOTIZEN_BACKUP_DIR", DATA_DIR / "backups"))
SESSION_DAYS = int(os.environ.get("NOTIZEN_SESSION_DAYS", "30"))
TRASH_DAYS = 7            # wie im Original: Papierkorb wird nach 7 Tagen geleert
OPLOG_KEEP = 2000         # so viele Befehle je Notiz werden für die Synchronisation aufbewahrt
VERSION_WINDOW = 10 * 60  # eine Version umfasst höchstens 10 Minuten Bearbeitung …
VERSION_IDLE = 2 * 60     # … und endet nach 2 Minuten ohne Änderung
VERSIONS_KEEP = 100       # maximale Anzahl Versionen je Notiz
MAX_IMAGE_BYTES = 20 * 1024 * 1024
MAX_IMAGE_SIDE = 2560
AVATAR_SIDE = 256
SCHEMA_VERSION = 2

USERNAME_RE = re.compile(r"^[A-Za-z0-9_.@-]{3,64}$")
MIN_PASSWORD_LEN = 8
MAX_PASSWORD_LEN = 1024

# Textspalten mit verschlüsseltem Inhalt "<schlüssel-id>:<daten>" (für Rotation & Prüfungen)
ENCRYPTED_COLUMNS = (("notes", "payload"), ("labels", "name"), ("note_ops", "op"),
                     ("note_versions", "payload"))
# Binärdaten (Bilder, Profilbilder) stehen in blobs.data, die Schlüssel-ID in blobs.key_id


def now() -> int:
    return int(time.time())


def stamp() -> str:
    return datetime.now().strftime("%Y%m%d-%H%M%S")


# --------------------------------------------------------------------------- #
# Datenbank
# --------------------------------------------------------------------------- #
SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    username            TEXT NOT NULL UNIQUE COLLATE NOCASE,
    password_hash       TEXT NOT NULL,
    created_at          INTEGER NOT NULL,
    password_changed_at INTEGER NOT NULL,
    settings            TEXT NOT NULL DEFAULT '{}',
    avatar_id           INTEGER
);

CREATE TABLE IF NOT EXISTS sessions (
    token_hash  TEXT PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);

-- payload = AES-256-GCM-verschlüsseltes JSON {"title","body","items","is_list"}
CREATE TABLE IF NOT EXISTS notes (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    payload     TEXT NOT NULL,
    color       TEXT NOT NULL DEFAULT 'default',
    rev         INTEGER NOT NULL DEFAULT 0,
    trashed_at  INTEGER,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    updated_by  INTEGER
);
CREATE INDEX IF NOT EXISTS idx_notes_owner ON notes(owner_id);

-- Teilnehmer einer Notiz. Anheften, Archiv und Reihenfolge sind wie im Original je Nutzer
CREATE TABLE IF NOT EXISTS note_users (
    note_id   INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role      TEXT NOT NULL CHECK (role IN ('owner', 'editor')),
    pinned    INTEGER NOT NULL DEFAULT 0,
    archived  INTEGER NOT NULL DEFAULT 0,
    position  REAL NOT NULL DEFAULT 0,
    added_at  INTEGER NOT NULL,
    PRIMARY KEY (note_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_note_users_user ON note_users(user_id);

-- Befehlsprotokoll für die Echtzeit-Synchronisation (op verschlüsselt)
CREATE TABLE IF NOT EXISTS note_ops (
    note_id     INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    rev         INTEGER NOT NULL,
    user_id     INTEGER,
    client      TEXT NOT NULL,
    cid         TEXT NOT NULL,
    created_at  INTEGER NOT NULL,
    op          TEXT NOT NULL,
    PRIMARY KEY (note_id, rev)
);
CREATE INDEX IF NOT EXISTS idx_note_ops_cid ON note_ops(note_id, client, cid);

-- Versionsverlauf (payload verschlüsselt)
CREATE TABLE IF NOT EXISTS note_versions (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    note_id     INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    user_id     INTEGER,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    payload     TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_versions_note ON note_versions(note_id);

-- Bilder und Profilbilder (data verschlüsselt: nonce || ciphertext || tag)
CREATE TABLE IF NOT EXISTS blobs (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id    INTEGER REFERENCES users(id) ON DELETE SET NULL,
    note_id     INTEGER REFERENCES notes(id) ON DELETE CASCADE,
    kind        TEXT NOT NULL CHECK (kind IN ('image', 'avatar')),
    mime        TEXT NOT NULL,
    width       INTEGER NOT NULL,
    height      INTEGER NOT NULL,
    size        INTEGER NOT NULL,
    key_id      TEXT NOT NULL,
    data        BLOB NOT NULL,
    created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_blobs_note ON blobs(note_id);

-- name = verschlüsselter Labelname
CREATE TABLE IF NOT EXISTS labels (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id  INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name     TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_labels_user ON labels(user_id);

CREATE TABLE IF NOT EXISTS note_labels (
    note_id   INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    label_id  INTEGER NOT NULL REFERENCES labels(id) ON DELETE CASCADE,
    PRIMARY KEY (note_id, label_id)
);

CREATE TABLE IF NOT EXISTS meta (
    key    TEXT PRIMARY KEY,
    value  TEXT NOT NULL
);
"""


def connect(path: Path | str | None = None) -> sqlite3.Connection:
    conn = sqlite3.connect(str(path or DB_PATH), timeout=15, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 15000")
    return conn


def schema_version(conn: sqlite3.Connection) -> int:
    has_meta = conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='meta'").fetchone()
    if not has_meta:
        has_users = conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='users'").fetchone()
        return 1 if has_users else 0
    row = conn.execute("SELECT value FROM meta WHERE key='schema_version'").fetchone()
    return int(row[0]) if row else 0


def prepare_db(conn: sqlite3.Connection, keyring: "KeyRing | None" = None) -> None:
    """Legt eine neue Datenbank an oder migriert eine ältere (dafür wird der Schlüssel benötigt)."""
    conn.execute("PRAGMA journal_mode = WAL")
    version = schema_version(conn)
    if version == 0:
        conn.executescript(SCHEMA)
        conn.execute("INSERT OR REPLACE INTO meta(key, value) VALUES ('schema_version', ?)", (str(SCHEMA_VERSION),))
        conn.commit()
    elif version < SCHEMA_VERSION:
        if keyring is None:
            keyring = KeyRing(KEY_PATH)
        backup = BACKUP_DIR / f"notizen-vor-migration-v{SCHEMA_VERSION}-{stamp()}.db"
        backup.parent.mkdir(parents=True, exist_ok=True)
        target = sqlite3.connect(str(backup))
        conn.backup(target)
        target.close()
        os.chmod(backup, 0o600)
        print(f"Datenbank wird auf Schema v{SCHEMA_VERSION} aktualisiert (Sicherung: {backup}) …")
        _migrate_1_to_2(conn, keyring)
    elif version > SCHEMA_VERSION:
        raise RuntimeError("Die Datenbank stammt von einer neueren Programmversion.")
    try:
        os.chmod(DB_PATH, 0o600)
    except OSError:
        pass


def _migrate_1_to_2(conn: sqlite3.Connection, keyring: "KeyRing") -> None:
    conn.commit()
    conn.execute("PRAGMA foreign_keys = OFF")
    try:
        conn.execute("BEGIN")
        cols = {r[1] for r in conn.execute("PRAGMA table_info(users)")}
        if "settings" not in cols:
            conn.execute("ALTER TABLE users ADD COLUMN settings TEXT NOT NULL DEFAULT '{}'")
        if "avatar_id" not in cols:
            conn.execute("ALTER TABLE users ADD COLUMN avatar_id INTEGER")
        conn.execute("""CREATE TABLE notes_v2 (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            payload TEXT NOT NULL, color TEXT NOT NULL DEFAULT 'default',
            rev INTEGER NOT NULL DEFAULT 0, trashed_at INTEGER,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, updated_by INTEGER)""")
        members, versions = [], []
        for r in conn.execute("SELECT * FROM notes").fetchall():
            old = json.loads(keyring.decrypt(r["payload"]))
            doc = {
                "title": (old.get("title") or "").replace("\n", " "),
                "body": ot.text_to_body(old.get("text") or ""),
                "items": [{"id": (it.get("id") or secrets.token_hex(6))[:40], "pos": float(i + 1),
                           "checked": bool(it.get("checked")), "text": (it.get("text") or "").replace("\n", " ")}
                          for i, it in enumerate(old.get("items") or [])],
                "is_list": bool(r["is_list"]),
            }
            payload = keyring.encrypt(json.dumps(doc, ensure_ascii=False))
            conn.execute("INSERT INTO notes_v2(id, owner_id, payload, color, rev, trashed_at, created_at, "
                         "updated_at, updated_by) VALUES (?,?,?,?,0,?,?,?,?)",
                         (r["id"], r["user_id"], payload, r["color"], r["trashed_at"], r["created_at"],
                          r["updated_at"], r["user_id"]))
            members.append((r["id"], r["user_id"], "owner", r["pinned"], r["archived"], r["position"], r["created_at"]))
            versions.append((r["id"], r["user_id"], r["updated_at"], r["updated_at"],
                             keyring.encrypt(json.dumps(doc, ensure_ascii=False))))
        conn.execute("DROP TABLE notes")
        conn.execute("ALTER TABLE notes_v2 RENAME TO notes")
        for stmt in SCHEMA.split(";"):
            if stmt.strip():
                conn.execute(stmt)
        conn.executemany("INSERT INTO note_users(note_id, user_id, role, pinned, archived, position, added_at) "
                         "VALUES (?,?,?,?,?,?,?)", members)
        conn.executemany("INSERT INTO note_versions(note_id, user_id, created_at, updated_at, payload) "
                         "VALUES (?,?,?,?,?)", versions)
        conn.execute("INSERT OR REPLACE INTO meta(key, value) VALUES ('schema_version', '2')")
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.execute("PRAGMA foreign_keys = ON")
    print(f"Migration abgeschlossen: {len(members)} Notizen übernommen.")


def key_ids_in_db(conn: sqlite3.Connection) -> dict[str, int]:
    """Zählt, wie viele verschlüsselte Datensätze mit welcher Schlüssel-ID existieren."""
    counts: dict[str, int] = {}
    tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    for table, col in ENCRYPTED_COLUMNS:
        if table not in tables:
            continue
        for kid, n in conn.execute(f"SELECT substr({col}, 1, instr({col}, ':') - 1) AS kid, COUNT(*) "
                                   f"FROM {table} GROUP BY kid"):
            counts[kid] = counts.get(kid, 0) + n
    if "blobs" in tables:
        for kid, n in conn.execute("SELECT key_id, COUNT(*) FROM blobs GROUP BY key_id"):
            counts[kid] = counts.get(kid, 0) + n
    return counts


# --------------------------------------------------------------------------- #
# Verschlüsselung (AES-256-GCM, Schlüsselring)
# --------------------------------------------------------------------------- #
class CryptoError(Exception):
    pass


def _b64e(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _b64d(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def write_private(path: Path, data: bytes) -> None:
    """Schreibt eine Datei atomar und nur für den Besitzer lesbar (0600)."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as fh:
        fh.write(data)
        fh.flush()
        os.fsync(fh.fileno())
    os.replace(tmp, path)
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def key_fingerprint(key_b64: str) -> str:
    return hashlib.sha256(b"notizen-fp:" + _b64d(key_b64)).hexdigest()[:16]


def generate_key_entry() -> tuple[str, dict]:
    kid = secrets.token_hex(4)
    return kid, {"key": _b64e(AESGCM.generate_key(bit_length=256)), "created": now()}


def validate_keyfile(data: dict) -> None:
    if not isinstance(data, dict) or not isinstance(data.get("keys"), dict) or "active" not in data:
        raise CryptoError("Ungültiges Schlüsseldatei-Format")
    if data["active"] not in data["keys"]:
        raise CryptoError("Aktiver Schlüssel fehlt im Schlüsselring")
    for kid, entry in data["keys"].items():
        if not re.fullmatch(r"[0-9a-f]{8}", kid):
            raise CryptoError(f"Ungültige Schlüssel-ID: {kid!r}")
        try:
            raw = _b64d(entry["key"])
        except Exception as exc:  # noqa: BLE001
            raise CryptoError(f"Schlüssel {kid} ist nicht lesbar") from exc
        if len(raw) != 32:
            raise CryptoError(f"Schlüssel {kid} ist kein 256-Bit-Schlüssel")


def load_keyfile(path: Path | str = KEY_PATH) -> dict:
    try:
        data = json.loads(Path(path).read_text("utf-8"))
    except FileNotFoundError as exc:
        raise CryptoError(f"Schlüsseldatei nicht gefunden: {path}") from exc
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise CryptoError(f"Schlüsseldatei ist beschädigt: {path}") from exc
    validate_keyfile(data)
    return data


def save_keyfile(path: Path | str, data: dict) -> None:
    data.setdefault("version", 1)
    validate_keyfile(data)
    write_private(Path(path), json.dumps(data, indent=2).encode("utf-8"))


class KeyRing:
    """
    Hält alle Schlüssel aus der Schlüsseldatei. Verschlüsselt wird immer mit dem
    aktiven Schlüssel, entschlüsselt mit dem Schlüssel, dessen ID beim Chiffrat steht.
    Ändert sich die Datei (z. B. durch 'admin.py key rotate'), wird sie automatisch
    neu geladen – der Server muss dafür nicht neu gestartet werden.

    Text:   <schlüssel-id>:<base64url(nonce[12] || ciphertext || tag[16])>
    Binär:  key_id-Spalte + (nonce[12] || ciphertext || tag[16])
    """

    def __init__(self, path: Path | str):
        self.path = Path(path)
        self._lock = threading.Lock()
        self._sig = None
        self._state: tuple[str, dict[str, AESGCM]] | None = None

    def _current(self) -> tuple[str, dict[str, AESGCM]]:
        try:
            st = self.path.stat()
        except FileNotFoundError as exc:
            raise CryptoError(f"Schlüsseldatei nicht gefunden: {self.path}") from exc
        sig = (st.st_mtime_ns, st.st_size, st.st_ino)
        if sig != self._sig:
            with self._lock:
                if sig != self._sig:
                    data = load_keyfile(self.path)
                    keys = {kid: AESGCM(_b64d(e["key"])) for kid, e in data["keys"].items()}
                    self._state = (data["active"], keys)
                    self._sig = sig
        return self._state  # type: ignore[return-value]

    def key_ids(self) -> set[str]:
        return set(self._current()[1])

    def encrypt(self, plaintext: str) -> str:
        active, keys = self._current()
        nonce = os.urandom(12)
        ct = keys[active].encrypt(nonce, plaintext.encode("utf-8"), active.encode())
        return f"{active}:{_b64e(nonce + ct)}"

    def decrypt(self, token: str) -> str:
        _, keys = self._current()
        kid, sep, blob = token.partition(":")
        if not sep or kid not in keys:
            raise CryptoError(f"Kein Schlüssel mit der ID '{kid}' vorhanden")
        raw = _b64d(blob)
        try:
            return keys[kid].decrypt(raw[:12], raw[12:], kid.encode()).decode("utf-8")
        except InvalidTag as exc:
            raise CryptoError("Entschlüsselung fehlgeschlagen (falscher Schlüssel oder manipulierte Daten)") from exc

    def encrypt_bytes(self, data: bytes) -> tuple[str, bytes]:
        active, keys = self._current()
        nonce = os.urandom(12)
        return active, nonce + keys[active].encrypt(nonce, data, (active + ":blob").encode())

    def decrypt_bytes(self, kid: str, blob: bytes) -> bytes:
        _, keys = self._current()
        if kid not in keys:
            raise CryptoError(f"Kein Schlüssel mit der ID '{kid}' vorhanden")
        try:
            return keys[kid].decrypt(blob[:12], blob[12:], (kid + ":blob").encode())
        except InvalidTag as exc:
            raise CryptoError("Entschlüsselung fehlgeschlagen (falscher Schlüssel oder manipulierte Daten)") from exc


def reencrypt_all(conn: sqlite3.Connection, ring: KeyRing, new_kid: str) -> int:
    """Verschlüsselt alle Datensätze mit dem aktiven Schlüssel neu (für Rotation)."""
    changed = 0
    for table, col in ENCRYPTED_COLUMNS:
        for rid, value in conn.execute(f"SELECT rowid, {col} FROM {table}").fetchall():
            if not value.startswith(new_kid + ":"):
                conn.execute(f"UPDATE {table} SET {col} = ? WHERE rowid = ?",
                             (ring.encrypt(ring.decrypt(value)), rid))
                changed += 1
    for rid, kid in conn.execute("SELECT id, key_id FROM blobs WHERE key_id <> ?", (new_kid,)).fetchall():
        data = conn.execute("SELECT data FROM blobs WHERE id = ?", (rid,)).fetchone()[0]
        nkid, blob = ring.encrypt_bytes(ring.decrypt_bytes(kid, data))
        conn.execute("UPDATE blobs SET key_id = ?, data = ? WHERE id = ?", (nkid, blob, rid))
        changed += 1
    return changed


# --------------------------------------------------------------------------- #
# Passwörter: Argon2id (argon2-cffi), Fallback scrypt aus der Standardbibliothek
# --------------------------------------------------------------------------- #
_ph = PasswordHasher() if PasswordHasher else None  # Argon2id, Standardparameter (RFC 9106)
_dummy_hash: str | None = None

# scrypt-Parameter nach OWASP-Empfehlung (N=2^15, r=8, p=3 ≈ 32 MiB pro Hash)
_SCRYPT_N, _SCRYPT_R, _SCRYPT_P = 2 ** 15, 8, 3


def _scrypt(password: str, salt: bytes, n: int, r: int, p: int) -> bytes:
    return hashlib.scrypt(password.encode("utf-8"), salt=salt, n=n, r=r, p=p,
                          maxmem=256 * 1024 * 1024, dklen=32)


def hash_password(password: str) -> str:
    if _ph:
        return _ph.hash(password)
    salt = os.urandom(16)
    digest = _scrypt(password, salt, _SCRYPT_N, _SCRYPT_R, _SCRYPT_P)
    return f"scrypt${_SCRYPT_N}${_SCRYPT_R}${_SCRYPT_P}${_b64e(salt)}${_b64e(digest)}"


def verify_password(stored_hash: str, password: str) -> tuple[bool, bool]:
    """Gibt (korrekt, muss_neu_gehasht_werden) zurück."""
    if stored_hash.startswith("scrypt$"):
        try:
            _, n, r, p, salt, digest = stored_hash.split("$")
            ok = secrets.compare_digest(_scrypt(password, _b64d(salt), int(n), int(r), int(p)),
                                        _b64d(digest))
        except (ValueError, TypeError):
            return False, False
        # Wenn argon2 inzwischen installiert ist, beim nächsten Login auf Argon2id umstellen
        return ok, ok and (_ph is not None or (int(n), int(r), int(p)) != (_SCRYPT_N, _SCRYPT_R, _SCRYPT_P))
    if _ph is None:
        raise RuntimeError("Argon2-Hash gefunden, aber das Paket 'argon2-cffi' ist nicht installiert.")
    try:
        _ph.verify(stored_hash, password)
    except (VerifyMismatchError, VerificationError, InvalidHashError):
        return False, False
    return True, _ph.check_needs_rehash(stored_hash)


def dummy_verify(password: str) -> None:
    """Gleicht die Laufzeit bei unbekannten Benutzernamen an (gegen User-Enumeration)."""
    global _dummy_hash
    if _dummy_hash is None:
        _dummy_hash = hash_password(secrets.token_hex(16))
    verify_password(_dummy_hash, password)


def validate_username(name: str) -> str:
    name = name.strip()
    if not USERNAME_RE.fullmatch(name):
        raise ValueError("Benutzername: 3–64 Zeichen, erlaubt sind Buchstaben, Ziffern und _ . @ -")
    return name


def validate_password(password: str) -> None:
    if len(password) < MIN_PASSWORD_LEN:
        raise ValueError(f"Das Passwort muss mindestens {MIN_PASSWORD_LEN} Zeichen lang sein.")
    if len(password) > MAX_PASSWORD_LEN:
        raise ValueError("Das Passwort ist zu lang.")


# --------------------------------------------------------------------------- #
# Bilder (Pillow)
# --------------------------------------------------------------------------- #
class ImageError(ValueError):
    pass


def _open_image(data: bytes):
    try:
        from PIL import Image, ImageOps
    except ImportError as exc:  # pragma: no cover
        raise ImageError("Für Bilder wird das Paket 'Pillow' benötigt (pip install Pillow).") from exc
    Image.MAX_IMAGE_PIXELS = 60_000_000
    with warnings.catch_warnings():
        warnings.simplefilter("error", Image.DecompressionBombWarning)
        try:
            im = Image.open(io.BytesIO(data))
            fmt = im.format
            if fmt not in ("JPEG", "PNG", "WEBP", "GIF", "BMP"):
                raise ImageError("Nicht unterstütztes Bildformat (erlaubt: JPG, PNG, WebP, GIF).")
            im.load()
            im = ImageOps.exif_transpose(im)
        except ImageError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise ImageError("Die Datei ist kein gültiges Bild oder zu groß.") from exc
    return im


def process_image(data: bytes) -> tuple[bytes, str, int, int]:
    """Prüft ein hochgeladenes Bild, verkleinert es und entfernt alle Metadaten (EXIF/GPS)."""
    from PIL import Image
    im = _open_image(data)
    alpha = im.mode in ("RGBA", "LA", "PA") or (im.mode == "P" and "transparency" in im.info)
    im.thumbnail((MAX_IMAGE_SIDE, MAX_IMAGE_SIDE), Image.LANCZOS)
    out = io.BytesIO()
    if alpha:
        im = im.convert("RGBA")
        im.save(out, "PNG", optimize=True)
        mime = "image/png"
    else:
        im = im.convert("RGB")
        im.save(out, "JPEG", quality=86, optimize=True, progressive=True)
        mime = "image/jpeg"
    return out.getvalue(), mime, im.width, im.height


def process_avatar(data: bytes) -> bytes:
    """Schneidet ein Profilbild quadratisch zu und skaliert es auf 256×256 Pixel (JPEG)."""
    from PIL import Image, ImageOps
    im = _open_image(data)
    if im.mode in ("RGBA", "LA", "PA") or (im.mode == "P" and "transparency" in im.info):
        bg = Image.new("RGB", im.size, (255, 255, 255))
        bg.paste(im.convert("RGBA"), mask=im.convert("RGBA").split()[-1])
        im = bg
    im = ImageOps.fit(im.convert("RGB"), (AVATAR_SIDE, AVATAR_SIDE), Image.LANCZOS)
    out = io.BytesIO()
    im.save(out, "JPEG", quality=88, optimize=True, progressive=True)
    return out.getvalue()
