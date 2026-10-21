"""
core.py – gemeinsame Basis für den Webserver (app.py) und das Verwaltungsprogramm (admin.py).

Enthält:
  * Konfiguration (über Umgebungsvariablen überschreibbar)
  * SQLite-Schema und Verbindungsaufbau
  * AES-256-GCM-Verschlüsselung mit Schlüsselring (ermöglicht Schlüsselrotation)
  * Argon2id-Passwort-Hashing
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import secrets
import sqlite3
import threading
import time
from pathlib import Path

try:  # Argon2id ist empfohlen; ohne das Paket wird automatisch scrypt verwendet
    from argon2 import PasswordHasher
    from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError
except ImportError:  # pragma: no cover
    PasswordHasher = None  # type: ignore[assignment,misc]
    InvalidHashError = VerificationError = VerifyMismatchError = Exception  # type: ignore[misc]
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# --------------------------------------------------------------------------- #
# Konfiguration
# --------------------------------------------------------------------------- #
BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = Path(os.environ.get("NOTIZEN_DATA_DIR", BASE_DIR / "data"))
DB_PATH = Path(os.environ.get("NOTIZEN_DB", DATA_DIR / "notizen.db"))
KEY_PATH = Path(os.environ.get("NOTIZEN_KEY_FILE", DATA_DIR / "master.key"))
BACKUP_DIR = Path(os.environ.get("NOTIZEN_BACKUP_DIR", DATA_DIR / "backups"))
SESSION_DAYS = int(os.environ.get("NOTIZEN_SESSION_DAYS", "30"))
TRASH_DAYS = 7  # wie im Original: Papierkorb wird nach 7 Tagen geleert

USERNAME_RE = re.compile(r"^[A-Za-z0-9_.@-]{3,64}$")
MIN_PASSWORD_LEN = 8
MAX_PASSWORD_LEN = 1024

# Tabellen/Spalten, deren Inhalt verschlüsselt ist (für Rotation & Prüfungen)
ENCRYPTED_COLUMNS = (("notes", "payload"), ("labels", "name"))


def now() -> int:
    return int(time.time())


# --------------------------------------------------------------------------- #
# Datenbank
# --------------------------------------------------------------------------- #
SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    username            TEXT NOT NULL UNIQUE COLLATE NOCASE,
    password_hash       TEXT NOT NULL,
    created_at          INTEGER NOT NULL,
    password_changed_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
    token_hash  TEXT PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);

-- payload = AES-256-GCM-verschlüsseltes JSON {"title","text","items"}
CREATE TABLE IF NOT EXISTS notes (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    payload     TEXT NOT NULL,
    is_list     INTEGER NOT NULL DEFAULT 0,
    color       TEXT NOT NULL DEFAULT 'default',
    pinned      INTEGER NOT NULL DEFAULT 0,
    archived    INTEGER NOT NULL DEFAULT 0,
    trashed_at  INTEGER,
    position    REAL NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_notes_user ON notes(user_id);

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


def init_db(conn: sqlite3.Connection) -> None:
    conn.execute("PRAGMA journal_mode = WAL")
    conn.executescript(SCHEMA)
    conn.execute("INSERT OR IGNORE INTO meta(key, value) VALUES ('schema_version', '1')")
    conn.commit()
    try:
        os.chmod(DB_PATH, 0o600)
    except OSError:
        pass


def key_ids_in_db(conn: sqlite3.Connection) -> dict[str, int]:
    """Zählt, wie viele verschlüsselte Datensätze mit welcher Schlüssel-ID existieren."""
    counts: dict[str, int] = {}
    for table, col in ENCRYPTED_COLUMNS:
        rows = conn.execute(
            f"SELECT substr({col}, 1, instr({col}, ':') - 1) AS kid, COUNT(*) "
            f"FROM {table} GROUP BY kid"
        ).fetchall()
        for kid, n in rows:
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
    aktiven Schlüssel, entschlüsselt mit dem Schlüssel, dessen ID im Chiffrat steht.
    Ändert sich die Datei (z. B. durch 'admin.py key rotate'), wird sie automatisch
    neu geladen – der Server muss dafür nicht neu gestartet werden.

    Chiffrat-Format:  <schlüssel-id>:<base64url(nonce[12] || ciphertext || tag[16])>
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
