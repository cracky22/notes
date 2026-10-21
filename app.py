#!/usr/bin/env python3
"""
app.py – Webserver (FastAPI) für "Notizen", einen selbst gehosteten Notizdienst.

Start:
    python app.py                       # http://127.0.0.1:8000
    python app.py --host 0.0.0.0 --port 8080
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import sys
import threading
import uuid
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from typing import List, Optional

from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field, field_validator

import core

keyring = core.KeyRing(core.KEY_PATH)
STATIC_DIR = core.BASE_DIR / "static"
COOKIE_NAME = "notizen_session"
CSRF_HEADER_VALUE = "notizen"
COLORS = {"default", "red", "orange", "yellow", "green", "teal", "blue",
          "darkblue", "purple", "pink", "brown", "gray"}


# --------------------------------------------------------------------------- #
# Start-Prüfung
# --------------------------------------------------------------------------- #
def preflight() -> None:
    if not core.KEY_PATH.exists():
        raise RuntimeError(
            f"Kein Master-Schlüssel unter {core.KEY_PATH} gefunden.\n"
            "Bitte zuerst 'python admin.py init' ausführen."
        )
    available = keyring.key_ids()  # validiert die Schlüsseldatei
    core.DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = core.connect()
    try:
        core.init_db(conn)
        missing = set(core.key_ids_in_db(conn)) - available
        users = conn.execute("SELECT COUNT(*) FROM users").fetchone()[0]
    finally:
        conn.close()
    if missing:
        raise RuntimeError(
            "In der Datenbank gibt es Daten, für die kein Schlüssel vorhanden ist "
            f"(IDs: {', '.join(sorted(missing))}). Fehlenden Schlüssel mit "
            "'python admin.py key import <datei>' einspielen."
        )
    if users == 0:
        print("Hinweis: Es existiert noch kein Benutzer – 'python admin.py user add <name>'",
              file=sys.stderr)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    preflight()
    yield


app = FastAPI(title="Notizen", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


@app.middleware("http")
async def security_middleware(request: Request, call_next):
    # CSRF-Schutz: alle schreibenden API-Aufrufe brauchen einen eigenen Header,
    # den ein fremdes Formular/Bild nicht setzen kann (zusätzlich SameSite=Strict).
    if request.url.path.startswith("/api/") and request.method in ("POST", "PUT", "PATCH", "DELETE"):
        if request.headers.get("x-requested-with") != CSRF_HEADER_VALUE:
            return JSONResponse({"detail": "CSRF-Prüfung fehlgeschlagen"}, status_code=403)
    response = await call_next(request)
    response.headers["Content-Security-Policy"] = (
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
        "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    )
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    if request.url.path.startswith("/api/"):
        response.headers["Cache-Control"] = "no-store"
    return response


def get_db():
    conn = core.connect()
    try:
        yield conn
    finally:
        conn.close()


# --------------------------------------------------------------------------- #
# Anmeldung, Sitzungen, Brute-Force-Schutz
# --------------------------------------------------------------------------- #
_attempts: dict[str, deque] = defaultdict(deque)
_attempts_lock = threading.Lock()
FAIL_WINDOW = 15 * 60
FAIL_MAX = 10


def _throttle_key(request: Request, username: str) -> str:
    host = request.client.host if request.client else "?"
    return f"{host}|{username.strip().lower()}"


def _too_many(key: str) -> bool:
    with _attempts_lock:
        dq = _attempts[key]
        limit = core.now() - FAIL_WINDOW
        while dq and dq[0] < limit:
            dq.popleft()
        return len(dq) >= FAIL_MAX


def _record_fail(key: str) -> None:
    with _attempts_lock:
        _attempts[key].append(core.now())


def _token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _is_https(request: Request) -> bool:
    return (request.url.scheme == "https"
            or request.headers.get("x-forwarded-proto") == "https"
            or os.environ.get("NOTIZEN_SECURE_COOKIE") == "1")


def current_user(request: Request, conn=Depends(get_db)):
    token = request.cookies.get(COOKIE_NAME)
    if token:
        row = conn.execute(
            "SELECT u.id, u.username, s.token_hash FROM sessions s "
            "JOIN users u ON u.id = s.user_id WHERE s.token_hash = ? AND s.expires_at > ?",
            (_token_hash(token), core.now()),
        ).fetchone()
        if row:
            return row
    raise HTTPException(401, "Nicht angemeldet")


# --------------------------------------------------------------------------- #
# Datenmodelle
# --------------------------------------------------------------------------- #
def _check_color(v):
    if v is not None and v not in COLORS:
        raise ValueError("Unbekannte Farbe")
    return v


class LoginIn(BaseModel):
    username: str = Field(max_length=64)
    password: str = Field(max_length=core.MAX_PASSWORD_LEN)


class PasswordIn(BaseModel):
    old_password: str = Field(max_length=core.MAX_PASSWORD_LEN)
    new_password: str = Field(max_length=core.MAX_PASSWORD_LEN)


class Item(BaseModel):
    id: str = Field(default_factory=lambda: uuid.uuid4().hex[:12], max_length=64)
    text: str = Field("", max_length=5000)
    checked: bool = False


class NoteIn(BaseModel):
    title: str = Field("", max_length=1000)
    text: str = Field("", max_length=100_000)
    items: List[Item] = Field(default_factory=list, max_length=1000)
    is_list: bool = False
    color: str = "default"
    pinned: bool = False
    archived: bool = False
    labels: List[int] = Field(default_factory=list, max_length=200)

    _color = field_validator("color")(_check_color)


class NotePatch(BaseModel):
    title: Optional[str] = Field(None, max_length=1000)
    text: Optional[str] = Field(None, max_length=100_000)
    items: Optional[List[Item]] = Field(None, max_length=1000)
    is_list: Optional[bool] = None
    color: Optional[str] = None
    pinned: Optional[bool] = None
    archived: Optional[bool] = None
    trashed: Optional[bool] = None
    labels: Optional[List[int]] = Field(None, max_length=200)

    _color = field_validator("color")(_check_color)


class ReorderIn(BaseModel):
    ids: List[int] = Field(max_length=20000)


class LabelIn(BaseModel):
    name: str = Field(min_length=1, max_length=100)


# --------------------------------------------------------------------------- #
# Hilfsfunktionen Notizen/Labels
# --------------------------------------------------------------------------- #
def _encrypt_payload(title: str, text: str, items: list) -> str:
    items = [i if isinstance(i, dict) else i.model_dump() for i in items]
    return keyring.encrypt(json.dumps({"title": title, "text": text, "items": items},
                                      ensure_ascii=False))


def _note_out(row, label_ids: list[int]) -> dict:
    data = json.loads(keyring.decrypt(row["payload"]))
    return {
        "id": row["id"],
        "title": data.get("title", ""),
        "text": data.get("text", ""),
        "items": data.get("items", []),
        "is_list": bool(row["is_list"]),
        "color": row["color"],
        "pinned": bool(row["pinned"]),
        "archived": bool(row["archived"]),
        "trashed": row["trashed_at"] is not None,
        "trashed_at": row["trashed_at"],
        "position": row["position"],
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
        "labels": sorted(label_ids),
    }


def _note_row(conn, user_id: int, note_id: int):
    row = conn.execute("SELECT * FROM notes WHERE id = ? AND user_id = ?",
                       (note_id, user_id)).fetchone()
    if not row:
        raise HTTPException(404, "Notiz nicht gefunden")
    return row


def _one_note(conn, user_id: int, note_id: int) -> dict:
    row = _note_row(conn, user_id, note_id)
    labels = [r[0] for r in conn.execute("SELECT label_id FROM note_labels WHERE note_id = ?",
                                         (note_id,))]
    return _note_out(row, labels)


def _next_position(conn, user_id: int) -> float:
    cur = conn.execute("SELECT MAX(position) FROM notes WHERE user_id = ?", (user_id,)).fetchone()[0]
    return (cur or 0) + 1


def _set_labels(conn, user_id: int, note_id: int, label_ids: list[int]) -> None:
    ids = sorted(set(label_ids))
    if ids:
        marks = ",".join("?" * len(ids))
        valid = {r[0] for r in conn.execute(
            f"SELECT id FROM labels WHERE user_id = ? AND id IN ({marks})", (user_id, *ids))}
        if len(valid) != len(ids):
            raise HTTPException(400, "Unbekanntes Label")
    conn.execute("DELETE FROM note_labels WHERE note_id = ?", (note_id,))
    conn.executemany("INSERT INTO note_labels(note_id, label_id) VALUES (?, ?)",
                     [(note_id, i) for i in ids])


def _label_list(conn, user_id: int) -> list[dict]:
    labels = [{"id": r["id"], "name": keyring.decrypt(r["name"])}
              for r in conn.execute("SELECT id, name FROM labels WHERE user_id = ?", (user_id,))]
    return sorted(labels, key=lambda lab: lab["name"].casefold())


def _ensure_unique_label(conn, user_id: int, name: str, exclude: int | None = None) -> None:
    key = name.casefold()
    for lab in _label_list(conn, user_id):
        if lab["id"] != exclude and lab["name"].casefold() == key:
            raise HTTPException(409, "Ein Label mit diesem Namen existiert bereits.")


# --------------------------------------------------------------------------- #
# Routen: Frontend
# --------------------------------------------------------------------------- #
@app.api_route("/", methods=["GET", "HEAD"], include_in_schema=False)
def index():
    return FileResponse(STATIC_DIR / "index.html")


app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


# --------------------------------------------------------------------------- #
# Routen: Konto
# --------------------------------------------------------------------------- #
@app.post("/api/login")
def login(body: LoginIn, request: Request, response: Response, conn=Depends(get_db)):
    key = _throttle_key(request, body.username)
    if _too_many(key):
        raise HTTPException(429, "Zu viele Fehlversuche. Bitte in einigen Minuten erneut versuchen.")
    row = conn.execute("SELECT id, username, password_hash FROM users WHERE username = ?",
                       (body.username.strip(),)).fetchone()
    ok = rehash = False
    if row is None:
        core.dummy_verify(body.password)
    else:
        ok, rehash = core.verify_password(row["password_hash"], body.password)
    if not ok:
        _record_fail(key)
        raise HTTPException(401, "Benutzername oder Passwort ist falsch.")
    with _attempts_lock:
        _attempts.pop(key, None)
    if rehash:
        conn.execute("UPDATE users SET password_hash = ? WHERE id = ?",
                     (core.hash_password(body.password), row["id"]))

    token = secrets.token_urlsafe(32)
    t = core.now()
    conn.execute("INSERT INTO sessions(token_hash, user_id, created_at, expires_at) VALUES (?,?,?,?)",
                 (_token_hash(token), row["id"], t, t + core.SESSION_DAYS * 86400))
    conn.execute("DELETE FROM sessions WHERE expires_at < ?", (t,))
    conn.commit()
    response.set_cookie(COOKIE_NAME, token, max_age=core.SESSION_DAYS * 86400, httponly=True,
                        samesite="strict", secure=_is_https(request), path="/")
    return {"username": row["username"]}


@app.post("/api/logout")
def logout(request: Request, response: Response, conn=Depends(get_db)):
    token = request.cookies.get(COOKIE_NAME)
    if token:
        conn.execute("DELETE FROM sessions WHERE token_hash = ?", (_token_hash(token),))
        conn.commit()
    response.delete_cookie(COOKIE_NAME, path="/")
    return {"ok": True}


@app.get("/api/me")
def me(user=Depends(current_user)):
    return {"username": user["username"]}


@app.post("/api/password")
def change_password(body: PasswordIn, user=Depends(current_user), conn=Depends(get_db)):
    row = conn.execute("SELECT password_hash FROM users WHERE id = ?", (user["id"],)).fetchone()
    ok, _ = core.verify_password(row["password_hash"], body.old_password)
    if not ok:
        raise HTTPException(400, "Das aktuelle Passwort ist falsch.")
    try:
        core.validate_password(body.new_password)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    conn.execute("UPDATE users SET password_hash = ?, password_changed_at = ? WHERE id = ?",
                 (core.hash_password(body.new_password), core.now(), user["id"]))
    # alle anderen Sitzungen dieses Benutzers beenden
    conn.execute("DELETE FROM sessions WHERE user_id = ? AND token_hash <> ?",
                 (user["id"], user["token_hash"]))
    conn.commit()
    return {"ok": True}


# --------------------------------------------------------------------------- #
# Routen: Notizen
# --------------------------------------------------------------------------- #
@app.get("/api/notes")
def list_notes(user=Depends(current_user), conn=Depends(get_db)):
    uid = user["id"]
    conn.execute("DELETE FROM notes WHERE user_id = ? AND trashed_at IS NOT NULL AND trashed_at < ?",
                 (uid, core.now() - core.TRASH_DAYS * 86400))
    conn.commit()
    labels: dict[int, list[int]] = defaultdict(list)
    for note_id, label_id in conn.execute(
            "SELECT nl.note_id, nl.label_id FROM note_labels nl "
            "JOIN notes n ON n.id = nl.note_id WHERE n.user_id = ?", (uid,)):
        labels[note_id].append(label_id)
    rows = conn.execute("SELECT * FROM notes WHERE user_id = ? ORDER BY position DESC, id DESC", (uid,))
    return [_note_out(r, labels.get(r["id"], [])) for r in rows]


@app.post("/api/notes", status_code=201)
def create_note(body: NoteIn, user=Depends(current_user), conn=Depends(get_db)):
    uid, t = user["id"], core.now()
    cur = conn.execute(
        "INSERT INTO notes(user_id, payload, is_list, color, pinned, archived, position, created_at, updated_at) "
        "VALUES (?,?,?,?,?,?,?,?,?)",
        (uid, _encrypt_payload(body.title, body.text, body.items), int(body.is_list), body.color,
         int(body.pinned and not body.archived), int(body.archived), _next_position(conn, uid), t, t),
    )
    _set_labels(conn, uid, cur.lastrowid, body.labels)
    conn.commit()
    return _one_note(conn, uid, cur.lastrowid)


@app.patch("/api/notes/{note_id}")
def update_note(note_id: int, body: NotePatch, user=Depends(current_user), conn=Depends(get_db)):
    uid = user["id"]
    row = _note_row(conn, uid, note_id)
    changes = {k: v for k, v in body.model_dump(exclude_unset=True).items() if v is not None}
    sets, params = [], []
    content_changed = False

    content = {"title", "text", "items"} & changes.keys()
    if content:
        data = json.loads(keyring.decrypt(row["payload"]))
        for k in content:
            data[k] = changes[k]
        sets.append("payload = ?")
        params.append(_encrypt_payload(data.get("title", ""), data.get("text", ""), data.get("items", [])))
        content_changed = True
    for col in ("is_list", "pinned", "archived"):
        if col in changes:
            sets.append(f"{col} = ?")
            params.append(int(changes[col]))
    if "is_list" in changes:
        content_changed = True
    if "color" in changes:
        sets.append("color = ?")
        params.append(changes["color"])
    if "trashed" in changes:
        sets.append("trashed_at = ?")
        params.append(core.now() if changes["trashed"] else None)
    if "labels" in changes:
        _set_labels(conn, uid, note_id, changes["labels"])
        content_changed = True
    if content_changed:
        sets.append("updated_at = ?")
        params.append(core.now())
    if sets:
        conn.execute(f"UPDATE notes SET {', '.join(sets)} WHERE id = ? AND user_id = ?",
                     (*params, note_id, uid))
    conn.commit()
    return _one_note(conn, uid, note_id)


@app.delete("/api/notes/{note_id}", status_code=204)
def delete_note(note_id: int, user=Depends(current_user), conn=Depends(get_db)):
    _note_row(conn, user["id"], note_id)
    conn.execute("DELETE FROM notes WHERE id = ? AND user_id = ?", (note_id, user["id"]))
    conn.commit()
    return Response(status_code=204)


@app.post("/api/notes/{note_id}/copy", status_code=201)
def copy_note(note_id: int, user=Depends(current_user), conn=Depends(get_db)):
    uid, t = user["id"], core.now()
    row = _note_row(conn, uid, note_id)
    payload = keyring.encrypt(keyring.decrypt(row["payload"]))  # neuer Nonce, aktiver Schlüssel
    cur = conn.execute(
        "INSERT INTO notes(user_id, payload, is_list, color, pinned, archived, position, created_at, updated_at) "
        "VALUES (?,?,?,?,0,0,?,?,?)",
        (uid, payload, row["is_list"], row["color"], _next_position(conn, uid), t, t),
    )
    conn.execute("INSERT INTO note_labels(note_id, label_id) "
                 "SELECT ?, label_id FROM note_labels WHERE note_id = ?", (cur.lastrowid, note_id))
    conn.commit()
    return _one_note(conn, uid, cur.lastrowid)


@app.post("/api/notes/reorder")
def reorder_notes(body: ReorderIn, user=Depends(current_user), conn=Depends(get_db)):
    """Ordnet die übergebenen Notizen neu, indem ihre bisherigen Positionen umverteilt werden."""
    uid = user["id"]
    ids = list(dict.fromkeys(body.ids))
    if not ids:
        return {"positions": {}}
    marks = ",".join("?" * len(ids))
    rows = conn.execute(f"SELECT id, position FROM notes WHERE user_id = ? AND id IN ({marks})",
                        (uid, *ids)).fetchall()
    if len(rows) != len(ids):
        raise HTTPException(404, "Notiz nicht gefunden")
    positions = sorted((r["position"] for r in rows), reverse=True)
    conn.executemany("UPDATE notes SET position = ? WHERE id = ? AND user_id = ?",
                     [(p, i, uid) for p, i in zip(positions, ids)])
    conn.commit()
    return {"positions": {str(i): p for p, i in zip(positions, ids)}}


@app.delete("/api/trash", status_code=204)
def empty_trash(user=Depends(current_user), conn=Depends(get_db)):
    conn.execute("DELETE FROM notes WHERE user_id = ? AND trashed_at IS NOT NULL", (user["id"],))
    conn.commit()
    return Response(status_code=204)


# --------------------------------------------------------------------------- #
# Routen: Labels
# --------------------------------------------------------------------------- #
@app.get("/api/labels")
def list_labels(user=Depends(current_user), conn=Depends(get_db)):
    return _label_list(conn, user["id"])


@app.post("/api/labels", status_code=201)
def create_label(body: LabelIn, user=Depends(current_user), conn=Depends(get_db)):
    name = body.name.strip()
    if not name:
        raise HTTPException(400, "Der Labelname darf nicht leer sein.")
    _ensure_unique_label(conn, user["id"], name)
    cur = conn.execute("INSERT INTO labels(user_id, name) VALUES (?, ?)",
                       (user["id"], keyring.encrypt(name)))
    conn.commit()
    return {"id": cur.lastrowid, "name": name}


@app.patch("/api/labels/{label_id}")
def rename_label(label_id: int, body: LabelIn, user=Depends(current_user), conn=Depends(get_db)):
    name = body.name.strip()
    if not name:
        raise HTTPException(400, "Der Labelname darf nicht leer sein.")
    if not conn.execute("SELECT 1 FROM labels WHERE id = ? AND user_id = ?",
                        (label_id, user["id"])).fetchone():
        raise HTTPException(404, "Label nicht gefunden")
    _ensure_unique_label(conn, user["id"], name, exclude=label_id)
    conn.execute("UPDATE labels SET name = ? WHERE id = ?", (keyring.encrypt(name), label_id))
    conn.commit()
    return {"id": label_id, "name": name}


@app.delete("/api/labels/{label_id}", status_code=204)
def delete_label(label_id: int, user=Depends(current_user), conn=Depends(get_db)):
    conn.execute("DELETE FROM labels WHERE id = ? AND user_id = ?", (label_id, user["id"]))
    conn.commit()
    return Response(status_code=204)


# --------------------------------------------------------------------------- #
# Direktstart
# --------------------------------------------------------------------------- #
def main() -> None:
    parser = argparse.ArgumentParser(description="Notizen – Webserver")
    parser.add_argument("--host", default=os.environ.get("NOTIZEN_HOST", "127.0.0.1"),
                        help="Adresse (Standard 127.0.0.1, für das ganze Netz 0.0.0.0)")
    parser.add_argument("--port", type=int, default=int(os.environ.get("NOTIZEN_PORT", "8000")))
    args = parser.parse_args()
    try:
        preflight()
    except (RuntimeError, core.CryptoError) as exc:
        print(f"Fehler: {exc}", file=sys.stderr)
        sys.exit(1)

    import uvicorn

    print(f"Notizen läuft auf http://{args.host}:{args.port}  (Beenden mit Strg+C)")
    uvicorn.run(app, host=args.host, port=args.port, proxy_headers=True,
                server_header=False, log_level="info")


if __name__ == "__main__":
    main()
