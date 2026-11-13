"""
search_index.py – verschlüsselter, invertierter Suchindex mit einem Bereich je Benutzer.

Notizwortlisten und der zufällige HMAC-Schlüssel jedes Nutzers liegen verschlüsselt in SQLite.
Der schnelle Termindex enthält nur HMAC-Prüfsummen (keine Klartextbegriffe); wegen eines
separaten Zufallsschlüssels pro Benutzer sind Begriffe zwischen Nutzerbereichen nicht verknüpfbar.
Änderungen werden gesammelt nachgezogen, manuelle Neuaufbauten laufen stapelweise im Hintergrund.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import re
import secrets
import time

import core

INDEX_VERSION = 1
BUILD_BATCH = 100
DIRTY_BATCH = 200
USER_COOLDOWN = 10 * 60
MAX_TOKEN_LEN = 64
MAX_TOKENS = 20000
MAX_QUERY_WORDS = 12

_TOKEN_SPLIT = re.compile(r"[^\w-]+|_+", re.UNICODE)


def tokens_of_text(text: str) -> list[str]:
    """NFKC-agnostische Unicode-Tokenisierung: wie die Browser-Suche, mit casefold()."""
    out = []
    for tok in _TOKEN_SPLIT.split(text.casefold()):
        tok = tok.strip("-_")
        if tok:
            out.append(tok[:MAX_TOKEN_LEN])
    return out


def doc_text(doc: dict) -> str:
    body = "".join(op["insert"] for op in (doc.get("body") or [])
                   if isinstance(op, dict) and isinstance(op.get("insert"), str))
    items = "\n".join(str(i.get("text") or "") for i in (doc.get("items") or []) if isinstance(i, dict))
    return "\n".join([str(doc.get("title") or ""), body, items])


def doc_tokens(doc: dict) -> list[str]:
    return sorted(dict.fromkeys(tokens_of_text(doc_text(doc))))[:MAX_TOKENS]


def query_words(q: str) -> list[str]:
    words = []
    for raw in q.split():
        words.extend(tokens_of_text(raw))
    return list(dict.fromkeys(words))[:MAX_QUERY_WORDS]


def _new_user_key(keyring) -> str:
    raw = secrets.token_bytes(32)
    return keyring.encrypt(base64.urlsafe_b64encode(raw).decode("ascii"))


def _decode_user_key(keyring, encrypted: str) -> bytes:
    return base64.urlsafe_b64decode(keyring.decrypt(encrypted).encode("ascii"))


def _grams(word: str) -> set[str]:
    """Alle 1- bis 3-Zeichen-Teilstücke, um auch Teilwortsuche zu unterstützen."""
    grams = set()
    for size in (1, 2, 3):
        grams.update(word[i:i + size] for i in range(max(0, len(word) - size + 1)))
    return grams


def _query_grams(word: str) -> set[str]:
    # Lange Wörter brauchen nur Trigramme. Das hält auch maximale Anfragen unter SQLite-Limits.
    if len(word) < 3:
        return _grams(word)
    return {word[i:i + 3] for i in range(len(word) - 2)}


def _term_hash(user_key: bytes, gram: str) -> str:
    return hmac.new(user_key, gram.encode("utf-8"), hashlib.sha256).hexdigest()


def _entry_payload(keyring, tokens: list[str]) -> str:
    text = "\n".join(tokens)
    return keyring.encrypt(core.compress_payload_text(text, 0.05) or text)


def _decode_entry(keyring, payload: str) -> str:
    return core.decompress_payload_text(keyring.decrypt(payload))


def _load_note_doc(keyring, payload: str) -> dict:
    return json.loads(core.decompress_payload_text(keyring.decrypt(payload)))


# --------------------------------------------------------------------------- #
# Status und Benutzerbereiche
# --------------------------------------------------------------------------- #
def ensure_rows(conn, keyring) -> int:
    """Legt fehlende Status- und HMAC-Schlüsselzeilen an."""
    t = core.now()
    changed = 0
    for r in conn.execute("SELECT id FROM users ORDER BY id").fetchall():
        uid = r[0]
        row = conn.execute("SELECT token_key_enc FROM search_index WHERE user_id=?", (uid,)).fetchone()
        if not row:
            conn.execute("INSERT INTO search_index(user_id,state,requested_at,requested_by,token_key_enc) "
                         "VALUES (?,'pending',?,'system',?)", (uid, t, _new_user_key(keyring)))
            changed += 1
        elif not row[0]:
            reset_user(conn, uid, "system", keyring)
            changed += 1
    stale = [r[0] for r in conn.execute("SELECT user_id FROM search_index WHERE version<>? "
                                        "AND state NOT IN ('pending','building')", (INDEX_VERSION,))]
    for uid in stale:
        reset_user(conn, uid, "system", keyring)
    return changed + len(stale)


def reset_user(conn, user_id: int, by: str, keyring) -> None:
    """Löscht genau diesen Nutzerindex, erneuert dessen Suchschlüssel und stößt den Neuaufbau an."""
    t = core.now()
    enc_key = _new_user_key(keyring)
    conn.execute("INSERT INTO search_index(user_id,state,requested_at,requested_by,token_key_enc) "
                 "VALUES (?,'pending',?,?,?) ON CONFLICT(user_id) DO NOTHING", (user_id, t, by, enc_key))
    conn.execute("DELETE FROM search_entries WHERE user_id=?", (user_id,))
    conn.execute("DELETE FROM search_terms WHERE user_id=?", (user_id,))
    conn.execute("UPDATE search_index SET state='pending', generation=generation+1, requested_at=?, requested_by=?, "
                 "token_key_enc=?, started_at=NULL, done=0, total=0, error=NULL, "
                 "user_reset_at=CASE WHEN ?='user' THEN ? ELSE user_reset_at END WHERE user_id=?",
                 (t, by, enc_key, by, t, user_id))


def user_cooldown_left(conn, user_id: int) -> int:
    row = conn.execute("SELECT user_reset_at FROM search_index WHERE user_id=?", (user_id,)).fetchone()
    if not row or not row[0]:
        return 0
    return max(0, row[0] + USER_COOLDOWN - core.now())


def _stats(conn, user_id: int) -> tuple[int, int]:
    entries = conn.execute("SELECT COUNT(*), COALESCE(SUM(length(CAST(payload AS BLOB))),0) "
                           "FROM search_entries WHERE user_id=?", (user_id,)).fetchone()
    terms = conn.execute("SELECT COUNT(*), COALESCE(SUM(length(token_hash)+8),0) FROM search_terms "
                         "WHERE user_id=?", (user_id,)).fetchone()
    return int(entries[0]), int(entries[1] + terms[1])


def status(conn, user_id: int) -> dict:
    row = conn.execute("SELECT * FROM search_index WHERE user_id=?", (user_id,)).fetchone()
    entries, size = _stats(conn, user_id)
    if not row:
        return {"state": "pending", "entries": entries, "bytes": size, "done": 0, "total": 0,
                "built_at": None, "requested_at": None, "requested_by": None, "error": None, "cooldown": 0}
    return {"state": row["state"], "entries": entries, "bytes": size, "done": row["done"],
            "total": row["total"], "built_at": row["built_at"], "requested_at": row["requested_at"],
            "requested_by": row["requested_by"], "error": row["error"],
            "cooldown": user_cooldown_left(conn, user_id)}


def all_status(conn) -> dict[int, dict]:
    entries = {r[0]: (r[1], r[2]) for r in conn.execute(
        "SELECT user_id, COUNT(*), COALESCE(SUM(length(CAST(payload AS BLOB))),0) "
        "FROM search_entries GROUP BY user_id")}
    terms = {r[0]: r[1] for r in conn.execute(
        "SELECT user_id, COALESCE(SUM(length(token_hash)+8),0) FROM search_terms GROUP BY user_id")}
    out = {}
    for r in conn.execute("SELECT * FROM search_index"):
        n, size = entries.get(r["user_id"], (0, 0))
        out[r["user_id"]] = {"state": r["state"], "entries": n, "bytes": size + terms.get(r["user_id"], 0),
                             "done": r["done"], "total": r["total"], "built_at": r["built_at"],
                             "requested_at": r["requested_at"], "requested_by": r["requested_by"],
                             "error": r["error"]}
    return out


# --------------------------------------------------------------------------- #
# Inkrementelle Pflege
# --------------------------------------------------------------------------- #
def mark_dirty(conn, note_id: int) -> None:
    conn.execute("INSERT INTO search_dirty(note_id,marked_at) VALUES (?,?) "
                 "ON CONFLICT(note_id) DO UPDATE SET marked_at=excluded.marked_at", (note_id, core.now()))


def drop_member(conn, note_id: int, user_id: int) -> None:
    conn.execute("DELETE FROM search_entries WHERE note_id=? AND user_id=?", (note_id, user_id))
    conn.execute("DELETE FROM search_terms WHERE note_id=? AND user_id=?", (note_id, user_id))


_UPSERT = ("INSERT INTO search_entries(user_id,note_id,rev,generation,payload) VALUES (?,?,?,?,?) "
           "ON CONFLICT(user_id,note_id) DO UPDATE SET rev=excluded.rev, generation=excluded.generation, "
           "payload=excluded.payload WHERE excluded.rev>=search_entries.rev "
           "OR excluded.generation>search_entries.generation")


def _index_note(conn, keyring, note_id: int, only_user: int | None = None) -> int:
    note = conn.execute("SELECT payload, rev FROM notes WHERE id=?", (note_id,)).fetchone()
    if not note:
        return 0
    members = conn.execute(
        "SELECT nu.user_id, COALESCE(s.generation,0), s.token_key_enc FROM note_users nu "
        "LEFT JOIN search_index s ON s.user_id=nu.user_id WHERE nu.note_id=?"
        + (" AND nu.user_id=?" if only_user is not None else ""),
        (note_id, only_user) if only_user is not None else (note_id,)).fetchall()
    members = [r for r in members if r[2]]
    if not members:
        return 0
    tokens = doc_tokens(_load_note_doc(keyring, note["payload"]))
    entry = _entry_payload(keyring, tokens)
    for uid, gen, enc_key in members:
        user_key = _decode_user_key(keyring, enc_key)
        conn.execute(_UPSERT, (uid, note_id, note["rev"], gen, entry))
        conn.execute("DELETE FROM search_terms WHERE user_id=? AND note_id=?", (uid, note_id))
        hashes = {_term_hash(user_key, gram) for token in tokens for gram in _grams(token)}
        conn.executemany("INSERT INTO search_terms(user_id,token_hash,note_id) VALUES (?,?,?)",
                         [(uid, digest, note_id) for digest in hashes])
    return len(members)


def process_dirty(keyring, limit: int = DIRTY_BATCH) -> int:
    conn = core.connect()
    try:
        if not conn.execute("SELECT 1 FROM search_dirty LIMIT 1").fetchone():
            return 0
        conn.execute("BEGIN IMMEDIATE")
        ensure_rows(conn, keyring)
        rows = conn.execute("SELECT note_id, marked_at FROM search_dirty ORDER BY marked_at LIMIT ?",
                            (limit,)).fetchall()
        for r in rows:
            nid = r["note_id"]
            conn.execute("SAVEPOINT dirty_note")
            try:
                _index_note(conn, keyring, nid)
                conn.execute("RELEASE dirty_note")
            except Exception as exc:  # noqa: BLE001
                conn.execute("ROLLBACK TO dirty_note")
                conn.execute("RELEASE dirty_note")
                conn.execute("UPDATE search_index SET state='error',version=?,error=? "
                             "WHERE user_id IN (SELECT user_id FROM note_users WHERE note_id=?)",
                             (INDEX_VERSION, str(exc)[:300], nid))
            conn.execute("DELETE FROM search_dirty WHERE note_id=? AND marked_at=?", (nid, r["marked_at"]))
        conn.commit()
        return len(rows)
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


# --------------------------------------------------------------------------- #
# Neuaufbau
# --------------------------------------------------------------------------- #
def next_pending_user(conn) -> int | None:
    row = conn.execute("SELECT user_id FROM search_index WHERE state IN ('pending','building') "
                       "ORDER BY requested_at, user_id LIMIT 1").fetchone()
    return row[0] if row else None


def build_user(keyring, user_id: int, should_stop=lambda: False, pause: float = 0.0) -> str:
    """Baut den Index eines Benutzers vollständig neu auf. Gibt den Endstatus zurück."""
    conn = core.connect()
    try:
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute("SELECT generation FROM search_index WHERE user_id=?", (user_id,)).fetchone()
        if not row:
            conn.rollback()
            return "missing"
        gen = row[0] + 1
        total = conn.execute("SELECT COUNT(*) FROM note_users WHERE user_id=?", (user_id,)).fetchone()[0]
        conn.execute("DELETE FROM search_entries WHERE user_id=?", (user_id,))
        conn.execute("DELETE FROM search_terms WHERE user_id=?", (user_id,))
        conn.execute("UPDATE search_index SET state='building', generation=?, started_at=?, done=0, total=?, "
                     "error=NULL WHERE user_id=?", (gen, core.now(), total, user_id))
        conn.commit()
        cursor, done = 0, 0
        while True:
            if should_stop():
                return "stopped"
            conn.execute("BEGIN IMMEDIATE")
            cur = conn.execute("SELECT generation, state FROM search_index WHERE user_id=?", (user_id,)).fetchone()
            if not cur or cur[0] != gen or cur[1] != "building":
                conn.rollback()
                return "superseded"
            ids = [r[0] for r in conn.execute("SELECT note_id FROM note_users WHERE user_id=? AND note_id>? "
                                              "ORDER BY note_id LIMIT ?", (user_id, cursor, BUILD_BATCH))]
            if not ids:
                conn.execute("UPDATE search_index SET state='ready', version=?, built_at=?, done=?, total=? "
                             "WHERE user_id=? AND generation=?", (INDEX_VERSION, core.now(), done, done, user_id, gen))
                conn.commit()
                return "ready"
            for nid in ids:
                _index_note(conn, keyring, nid, only_user=user_id)
            done += len(ids)
            cursor = ids[-1]
            conn.execute("UPDATE search_index SET done=? WHERE user_id=? AND generation=?", (done, user_id, gen))
            conn.commit()
            if pause:
                time.sleep(pause)
    except Exception as exc:
        try:
            conn.rollback()
            conn.execute("UPDATE search_index SET state='error', version=?, error=? WHERE user_id=?",
                         (INDEX_VERSION, str(exc)[:300], user_id))
            conn.commit()
        except Exception:  # noqa: BLE001
            pass
        return "error"
    finally:
        conn.close()


# --------------------------------------------------------------------------- #
# Suche
# --------------------------------------------------------------------------- #
def search(conn, keyring, user_id: int, q: str) -> list[int]:
    words = query_words(q)
    if not words:
        return []
    row = conn.execute("SELECT token_key_enc FROM search_index WHERE user_id=?", (user_id,)).fetchone()
    if not row or not row[0]:
        return []
    user_key = _decode_user_key(keyring, row[0])
    grams = set(gram for word in words for gram in _query_grams(word))
    hashes = sorted({_term_hash(user_key, gram) for gram in grams})
    marks = ",".join("?" for _ in hashes)
    candidates = [r[0] for r in conn.execute(
        f"SELECT note_id FROM search_terms WHERE user_id=? AND token_hash IN ({marks}) "
        "GROUP BY note_id HAVING COUNT(DISTINCT token_hash)=? ORDER BY note_id",
        (user_id, *hashes, len(hashes))).fetchall()]
    if not candidates:
        return []
    hits = []
    # Begrenze die Variablen je SQL-Abfrage; die Bestätigung filtert Trigramm-Treffer exakt.
    for start in range(0, len(candidates), 500):
        batch = candidates[start:start + 500]
        marks = ",".join("?" for _ in batch)
        rows = conn.execute(f"SELECT note_id,payload FROM search_entries WHERE user_id=? AND note_id IN ({marks})",
                            (user_id, *batch)).fetchall()
        for r in rows:
            text = _decode_entry(keyring, r["payload"])
            if all(word in text for word in words):
                hits.append(r["note_id"])
    return hits
