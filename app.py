#!/usr/bin/env python3
"""
app.py – Webserver (FastAPI) für "Notizen", einen selbst gehosteten Notizdienst.

Start:
    python app.py                       # http://127.0.0.1:8000
    python app.py --host 0.0.0.0 --port 8080

Echtzeit-Zusammenarbeit läuft über einen WebSocket (/ws). Clients senden nur
Befehle (siehe ot.py); der Server ordnet sie, transformiert gleichzeitige Befehle
gegeneinander und verteilt sie an alle Teilnehmer.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import logging
import os
import re
import secrets
import sys
import threading
import time
import uuid
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from typing import List, Optional
from urllib.parse import urlparse

from fastapi import Depends, FastAPI, HTTPException, Request, Response, WebSocket, WebSocketDisconnect
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field, field_validator

import core
import ot

log = logging.getLogger("notizen")
keyring = core.KeyRing(core.KEY_PATH)
STATIC_DIR = core.BASE_DIR / "static"
COOKIE_NAME = "notizen_session"
CSRF_HEADER_VALUE = "notizen"
CLIENT_RE = re.compile(r"^[A-Za-z0-9_-]{1,40}$")
COLORS = {"default", "red", "orange", "yellow", "green", "teal", "blue",
          "darkblue", "purple", "pink", "brown", "gray"}
MAX_IMAGES_PER_NOTE = 50


# --------------------------------------------------------------------------- #
# Echtzeit-Hub: verwaltet WebSocket-Verbindungen und verteilt Nachrichten
# --------------------------------------------------------------------------- #
class Conn:
    def __init__(self, ws: WebSocket, user, client_id: str, token_hash: str):
        self.ws = ws
        self.id = secrets.token_hex(6)
        self.user_id = user["id"]
        self.username = user["username"]
        self.avatar = user["avatar_id"]
        self.client_id = client_id
        self.token_hash = token_hash
        self.checked = time.monotonic()
        self.queue: asyncio.Queue[str] = asyncio.Queue(maxsize=10000)
        self.open_note: int | None = None
        self.cursor: dict | None = None
        self.closed = False

    def user_info(self) -> dict:
        return {"id": self.user_id, "username": self.username, "avatar": self.avatar}

    def push(self, data: str) -> None:
        if self.closed:
            return
        try:
            self.queue.put_nowait(data)
        except asyncio.QueueFull:  # Client liest nicht mehr → Verbindung trennen
            self.closed = True
            asyncio.ensure_future(self.ws.close(code=1013))

    async def writer(self) -> None:
        try:
            while True:
                data = await self.queue.get()
                await self.ws.send_text(data)
        except Exception:  # noqa: BLE001 – Verbindung beendet
            self.closed = True


class Hub:
    def __init__(self):
        self.loop: asyncio.AbstractEventLoop | None = None
        self.by_user: dict[int, set[Conn]] = defaultdict(set)
        self._locks: dict[int, asyncio.Lock] = {}

    def lock(self, note_id: int) -> asyncio.Lock:
        lk = self._locks.get(note_id)
        if lk is None:
            lk = self._locks[note_id] = asyncio.Lock()
        return lk

    def add(self, conn: Conn) -> None:
        self.by_user[conn.user_id].add(conn)

    def remove(self, conn: Conn) -> None:
        conns = self.by_user.get(conn.user_id)
        if conns:
            conns.discard(conn)
            if not conns:
                del self.by_user[conn.user_id]

    def all_conns(self):
        for conns in list(self.by_user.values()):
            yield from list(conns)

    def _deliver(self, user_ids, msg, exclude_client=None, exclude_conn=None) -> None:
        data = json.dumps(msg, ensure_ascii=False, separators=(",", ":"))
        targets = self.all_conns() if user_ids is None else (
            c for uid in set(user_ids) for c in list(self.by_user.get(uid, ())))
        for conn in targets:
            if conn is exclude_conn or (exclude_client and conn.client_id == exclude_client):
                continue
            conn.push(data)

    def publish(self, user_ids, msg, exclude_client=None, exclude_conn=None) -> None:
        """Thread-sicher: darf aus normalen (synchronen) Endpunkten aufgerufen werden."""
        if self.loop is None:
            return
        try:
            running = asyncio.get_running_loop()
        except RuntimeError:
            running = None
        ids = None if user_ids is None else list(user_ids)
        if running is self.loop:
            self._deliver(ids, msg, exclude_client, exclude_conn)
        else:
            self.loop.call_soon_threadsafe(self._deliver, ids, msg, exclude_client, exclude_conn)

    def to_viewers(self, note_id: int, msg: dict, exclude_conn=None) -> None:
        data = json.dumps(msg, ensure_ascii=False, separators=(",", ":"))
        for conn in self.all_conns():
            if conn.open_note == note_id and conn is not exclude_conn:
                conn.push(data)


hub = Hub()


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
        core.prepare_db(conn, keyring)
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
    hub.loop = asyncio.get_running_loop()
    yield


app = FastAPI(title="Notizen", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


@app.middleware("http")
async def security_middleware(request: Request, call_next):
    path = request.url.path
    # CSRF-Schutz: alle schreibenden API-Aufrufe brauchen einen eigenen Header,
    # den ein fremdes Formular/Bild nicht setzen kann (zusätzlich SameSite=Strict).
    if path.startswith("/api/") and request.method in ("POST", "PUT", "PATCH", "DELETE"):
        if request.headers.get("x-requested-with") != CSRF_HEADER_VALUE:
            return JSONResponse({"detail": "CSRF-Prüfung fehlgeschlagen"}, status_code=403)
    response = await call_next(request)
    host = request.headers.get("host", "")
    ws_src = f" ws://{host} wss://{host}" if re.fullmatch(r"[A-Za-z0-9.\-:\[\]]+", host) else ""
    response.headers["Content-Security-Policy"] = (
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; "
        f"connect-src 'self'{ws_src}; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; "
        "object-src 'none'"
    )
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    if path == "/" or path.startswith("/static/"):
        # Neuladen erzwingen: HTML, Skripte und Styles nie aus dem Cache verwenden
        response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate, max-age=0"
        response.headers["Pragma"] = "no-cache"
        response.headers["Expires"] = "0"
    elif path.startswith("/api/") and "cache-control" not in response.headers:
        response.headers["Cache-Control"] = "no-store"
        response.headers["Pragma"] = "no-cache"
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


def _session_user(conn, token: str | None):
    if not token:
        return None
    return conn.execute(
        "SELECT u.id, u.username, u.avatar_id, u.settings, s.token_hash FROM sessions s "
        "JOIN users u ON u.id = s.user_id WHERE s.token_hash = ? AND s.expires_at > ?",
        (_token_hash(token), core.now()),
    ).fetchone()


def current_user(request: Request, conn=Depends(get_db)):
    row = _session_user(conn, request.cookies.get(COOKIE_NAME))
    if row:
        return row
    raise HTTPException(401, "Nicht angemeldet")


def client_of(request: Request) -> str | None:
    cid = request.headers.get("x-client-id", "")
    return cid if CLIENT_RE.match(cid) else None


async def read_limited(request: Request, limit: int) -> bytes:
    length = request.headers.get("content-length")
    if length and length.isdigit() and int(length) > limit:
        raise HTTPException(413, f"Die Datei ist zu groß (max. {limit // (1024 * 1024)} MB).")
    size, chunks = 0, []
    async for chunk in request.stream():
        size += len(chunk)
        if size > limit:
            raise HTTPException(413, f"Die Datei ist zu groß (max. {limit // (1024 * 1024)} MB).")
        chunks.append(chunk)
    if not size:
        raise HTTPException(400, "Leere Datei")
    return b"".join(chunks)


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


class SettingsIn(BaseModel):
    settings: dict


class NoteIn(BaseModel):
    title: str = ""
    body: list = Field(default_factory=lambda: [{"insert": "\n"}])
    items: list = Field(default_factory=list)
    is_list: bool = False
    color: str = "default"
    pinned: bool = False
    archived: bool = False
    labels: List[int] = Field(default_factory=list, max_length=200)
    collaborators: List[int] = Field(default_factory=list, max_length=200)

    _color = field_validator("color")(_check_color)


class NotePatch(BaseModel):
    color: Optional[str] = None
    pinned: Optional[bool] = None
    archived: Optional[bool] = None
    trashed: Optional[bool] = None
    labels: Optional[List[int]] = Field(None, max_length=200)

    _color = field_validator("color")(_check_color)


class ReorderIn(BaseModel):
    ids: List[int] = Field(max_length=20000)


class CollaboratorsIn(BaseModel):
    user_ids: List[int] = Field(max_length=200)


class LabelIn(BaseModel):
    name: str = Field(min_length=1, max_length=100)


# --------------------------------------------------------------------------- #
# Notizen: Laden, Speichern, Ansichten
# --------------------------------------------------------------------------- #
def load_doc(row) -> dict:
    return json.loads(keyring.decrypt(row["payload"]))


def store_doc(doc: dict) -> str:
    return keyring.encrypt(json.dumps(doc, ensure_ascii=False, separators=(",", ":")))


def user_view(row) -> dict:
    return {"id": row["id"], "username": row["username"], "avatar": row["avatar_id"]}


def participants(conn, note_id: int) -> list[int]:
    return [r[0] for r in conn.execute("SELECT user_id FROM note_users WHERE note_id = ?", (note_id,))]


def note_views(conn, user_id: int, note_id: int | None = None) -> list[dict]:
    where, params = "nu.user_id = ?", [user_id]
    sub, sub_params = "SELECT note_id FROM note_users WHERE user_id = ?", [user_id]
    if note_id is not None:
        where += " AND n.id = ?"
        params.append(note_id)
        sub += " AND note_id = ?"
        sub_params.append(note_id)
    rows = conn.execute(
        f"SELECT n.*, nu.role, nu.pinned, nu.archived, nu.position FROM notes n "
        f"JOIN note_users nu ON nu.note_id = n.id WHERE {where}", params).fetchall()
    members: dict[int, list] = defaultdict(list)
    for r in conn.execute(
            f"SELECT nu.note_id, nu.role, u.id, u.username, u.avatar_id FROM note_users nu "
            f"JOIN users u ON u.id = nu.user_id WHERE nu.note_id IN ({sub}) "
            f"ORDER BY nu.role DESC, nu.added_at, u.id", sub_params):
        members[r["note_id"]].append({**user_view(r), "role": r["role"]})
    images: dict[int, list] = defaultdict(list)
    for r in conn.execute(
            f"SELECT id, note_id, width, height FROM blobs WHERE kind = 'image' AND note_id IN ({sub}) "
            f"ORDER BY id", sub_params):
        images[r["note_id"]].append({"id": r["id"], "w": r["width"], "h": r["height"]})
    labels: dict[int, list] = defaultdict(list)
    for r in conn.execute(
            f"SELECT nl.note_id, nl.label_id FROM note_labels nl JOIN labels l ON l.id = nl.label_id "
            f"WHERE l.user_id = ? AND nl.note_id IN ({sub})", [user_id, *sub_params]):
        labels[r["note_id"]].append(r["label_id"])
    out = []
    for r in rows:
        if r["trashed_at"] is not None and r["role"] != "owner":
            continue  # vom Inhaber gelöschte Notizen sehen Mitbearbeiter nicht mehr
        doc = load_doc(r)
        out.append({
            "id": r["id"], "rev": r["rev"],
            "title": doc["title"], "body": doc["body"], "items": doc["items"], "is_list": doc["is_list"],
            "color": r["color"], "images": images.get(r["id"], []), "members": members.get(r["id"], []),
            "owner_id": r["owner_id"], "role": r["role"],
            "pinned": bool(r["pinned"]), "archived": bool(r["archived"]), "position": r["position"],
            "trashed": r["trashed_at"] is not None, "trashed_at": r["trashed_at"],
            "labels": sorted(labels.get(r["id"], [])),
            "created_at": r["created_at"], "updated_at": r["updated_at"], "updated_by": r["updated_by"],
        })
    return out


def one_view(conn, user_id: int, note_id: int) -> dict:
    views = note_views(conn, user_id, note_id)
    if not views:
        raise HTTPException(404, "Notiz nicht gefunden")
    return views[0]


def publish_note(conn, note_id: int, user_ids, exclude_client: str | None = None) -> None:
    """Schickt jedem Teilnehmer seine persönliche Sicht auf die Notiz (oder 'gone')."""
    for uid in set(user_ids):
        views = note_views(conn, uid, note_id)
        msg = {"t": "note", "note": views[0]} if views else {"t": "gone", "id": note_id}
        hub.publish([uid], msg, exclude_client=exclude_client)
        if not views:
            for c in hub.by_user.get(uid, ()):
                if c.open_note == note_id:
                    c.open_note = None


def member_row(conn, user_id: int, note_id: int):
    row = conn.execute(
        "SELECT n.*, nu.role FROM notes n JOIN note_users nu ON nu.note_id = n.id AND nu.user_id = ? "
        "WHERE n.id = ?", (user_id, note_id)).fetchone()
    if not row or (row["trashed_at"] is not None and row["role"] != "owner"):
        raise HTTPException(404, "Notiz nicht gefunden")
    return row


def next_position(conn, user_id: int) -> float:
    cur = conn.execute("SELECT MAX(position) FROM note_users WHERE user_id = ?", (user_id,)).fetchone()[0]
    return (cur or 0) + 1


def set_labels(conn, user_id: int, note_id: int, label_ids: list[int]) -> None:
    ids = sorted(set(label_ids))
    if ids:
        marks = ",".join("?" * len(ids))
        valid = {r[0] for r in conn.execute(
            f"SELECT id FROM labels WHERE user_id = ? AND id IN ({marks})", (user_id, *ids))}
        if len(valid) != len(ids):
            raise HTTPException(400, "Unbekanntes Label")
    conn.execute("DELETE FROM note_labels WHERE note_id = ? AND label_id IN "
                 "(SELECT id FROM labels WHERE user_id = ?)", (note_id, user_id))
    conn.executemany("INSERT INTO note_labels(note_id, label_id) VALUES (?, ?)", [(note_id, i) for i in ids])


def save_version(conn, note_id: int, user_id: int, doc: dict, t: int) -> None:
    """Eine Version = eine Bearbeitungssitzung: neue Version nach 2 Min. Pause oder spätestens alle 10 Min."""
    last = conn.execute("SELECT id, payload, created_at, updated_at FROM note_versions WHERE note_id = ? "
                        "ORDER BY id DESC LIMIT 1", (note_id,)).fetchone()
    editors = [user_id]
    if last and t - last["updated_at"] < core.VERSION_IDLE and t - last["created_at"] < core.VERSION_WINDOW:
        prev = json.loads(keyring.decrypt(last["payload"])).get("editors", [])
        editors = prev + [user_id] if user_id not in prev else prev
        conn.execute("UPDATE note_versions SET payload = ?, updated_at = ?, user_id = ? WHERE id = ?",
                     (store_doc({**doc, "editors": editors}), t, user_id, last["id"]))
    else:
        conn.execute("INSERT INTO note_versions(note_id, user_id, created_at, updated_at, payload) "
                     "VALUES (?,?,?,?,?)", (note_id, user_id, t, t, store_doc({**doc, "editors": editors})))
        conn.execute("DELETE FROM note_versions WHERE note_id = ? AND id NOT IN (SELECT id FROM note_versions "
                     "WHERE note_id = ? ORDER BY id DESC LIMIT ?)", (note_id, note_id, core.VERSIONS_KEEP))


def commit_change(conn, row, user_id: int, client: str, cid: str, change: list, doc: dict) -> tuple[int, dict]:
    """Wendet eine (bereits transformierte) Änderung an und protokolliert sie. Gibt (rev, doc) zurück."""
    new_doc = ot.apply_change(doc, change)
    t = core.now()
    rev = row["rev"] + 1
    conn.execute("UPDATE notes SET payload = ?, rev = ?, updated_at = ?, updated_by = ? WHERE id = ?",
                 (store_doc(new_doc), rev, t, user_id, row["id"]))
    conn.execute("INSERT INTO note_ops(note_id, rev, user_id, client, cid, created_at, op) VALUES (?,?,?,?,?,?,?)",
                 (row["id"], rev, user_id, client, cid, t,
                  keyring.encrypt(json.dumps(change, ensure_ascii=False, separators=(",", ":")))))
    conn.execute("DELETE FROM note_ops WHERE note_id = ? AND rev <= ?", (row["id"], rev - core.OPLOG_KEEP))
    save_version(conn, row["id"], user_id, new_doc, t)
    return rev, new_doc


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
def me_view(row) -> dict:
    try:
        settings = json.loads(row["settings"] or "{}")
    except json.JSONDecodeError:
        settings = {}
    return {"id": row["id"], "username": row["username"], "avatar": row["avatar_id"], "settings": settings}


@app.post("/api/login")
def login(body: LoginIn, request: Request, response: Response, conn=Depends(get_db)):
    key = _throttle_key(request, body.username)
    if _too_many(key):
        raise HTTPException(429, "Zu viele Fehlversuche. Bitte in einigen Minuten erneut versuchen.")
    row = conn.execute("SELECT * FROM users WHERE username = ?", (body.username.strip(),)).fetchone()
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
    return me_view(row)


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
    return me_view(user)


@app.put("/api/me/settings")
def save_settings(body: SettingsIn, request: Request, user=Depends(current_user), conn=Depends(get_db)):
    data = json.dumps(body.settings, ensure_ascii=False, separators=(",", ":"))
    if len(data) > 20000:
        raise HTTPException(400, "Einstellungen zu groß")
    conn.execute("UPDATE users SET settings = ? WHERE id = ?", (data, user["id"]))
    conn.commit()
    hub.publish([user["id"]], {"t": "settings", "settings": body.settings}, exclude_client=client_of(request))
    return {"settings": body.settings}


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


@app.put("/api/me/avatar")
async def upload_avatar(request: Request, user=Depends(current_user), conn=Depends(get_db)):
    data = await read_limited(request, 10 * 1024 * 1024)
    try:
        jpeg = await run_in_threadpool(core.process_avatar, data)
    except core.ImageError as exc:
        raise HTTPException(400, str(exc)) from exc
    kid, blob = keyring.encrypt_bytes(jpeg)
    cur = conn.execute("INSERT INTO blobs(owner_id, note_id, kind, mime, width, height, size, key_id, data, "
                       "created_at) VALUES (?, NULL, 'avatar', 'image/jpeg', ?, ?, ?, ?, ?, ?)",
                       (user["id"], core.AVATAR_SIDE, core.AVATAR_SIDE, len(jpeg), kid, blob, core.now()))
    old = user["avatar_id"]
    conn.execute("UPDATE users SET avatar_id = ? WHERE id = ?", (cur.lastrowid, user["id"]))
    if old:
        conn.execute("DELETE FROM blobs WHERE id = ? AND kind = 'avatar'", (old,))
    conn.commit()
    info = {"id": user["id"], "username": user["username"], "avatar": cur.lastrowid}
    hub.publish(None, {"t": "user", "user": info})
    return info


@app.delete("/api/me/avatar")
def delete_avatar(user=Depends(current_user), conn=Depends(get_db)):
    if user["avatar_id"]:
        conn.execute("UPDATE users SET avatar_id = NULL WHERE id = ?", (user["id"],))
        conn.execute("DELETE FROM blobs WHERE id = ? AND kind = 'avatar'", (user["avatar_id"],))
        conn.commit()
    info = {"id": user["id"], "username": user["username"], "avatar": None}
    hub.publish(None, {"t": "user", "user": info})
    return info


@app.get("/api/avatars/{user_id}")
def get_avatar(user_id: int, _user=Depends(current_user), conn=Depends(get_db)):
    row = conn.execute("SELECT b.key_id, b.data FROM users u JOIN blobs b ON b.id = u.avatar_id "
                       "WHERE u.id = ?", (user_id,)).fetchone()
    if not row:
        raise HTTPException(404, "Kein Profilbild")
    return Response(keyring.decrypt_bytes(row["key_id"], row["data"]), media_type="image/jpeg",
                    headers={"Cache-Control": "private, max-age=31536000, immutable"})


@app.get("/api/users")
def list_users(_user=Depends(current_user), conn=Depends(get_db)):
    return [user_view(r) for r in conn.execute(
        "SELECT id, username, avatar_id FROM users ORDER BY username COLLATE NOCASE")]


# --------------------------------------------------------------------------- #
# Routen: Notizen
# --------------------------------------------------------------------------- #
@app.get("/api/notes")
def list_notes(user=Depends(current_user), conn=Depends(get_db)):
    conn.execute("DELETE FROM notes WHERE owner_id = ? AND trashed_at IS NOT NULL AND trashed_at < ?",
                 (user["id"], core.now() - core.TRASH_DAYS * 86400))
    conn.commit()
    return note_views(conn, user["id"])


@app.get("/api/notes/{note_id}")
def get_note(note_id: int, user=Depends(current_user), conn=Depends(get_db)):
    return one_view(conn, user["id"], note_id)


@app.post("/api/notes", status_code=201)
def create_note(body: NoteIn, request: Request, user=Depends(current_user), conn=Depends(get_db)):
    try:
        doc = ot.validate_doc(body.model_dump(include={"title", "body", "items", "is_list"}))
    except ot.InvalidOp as exc:
        raise HTTPException(400, str(exc)) from exc
    uid, t = user["id"], core.now()
    cur = conn.execute("INSERT INTO notes(owner_id, payload, color, rev, created_at, updated_at, updated_by) "
                       "VALUES (?,?,?,0,?,?,?)", (uid, store_doc(doc), body.color, t, t, uid))
    nid = cur.lastrowid
    conn.execute("INSERT INTO note_users(note_id, user_id, role, pinned, archived, position, added_at) "
                 "VALUES (?,?,'owner',?,?,?,?)",
                 (nid, uid, int(body.pinned and not body.archived), int(body.archived),
                  next_position(conn, uid), t))
    set_labels(conn, uid, nid, body.labels)
    collab = {i for i in body.collaborators if i != uid}
    if collab:
        marks = ",".join("?" * len(collab))
        found = [r[0] for r in conn.execute(f"SELECT id FROM users WHERE id IN ({marks})", tuple(collab))]
        for other in found:
            conn.execute("INSERT INTO note_users(note_id, user_id, role, position, added_at) VALUES (?,?,'editor',?,?)",
                         (nid, other, next_position(conn, other), t))
        collab = set(found)
    save_version(conn, nid, uid, doc, t)
    conn.commit()
    publish_note(conn, nid, collab | {uid}, exclude_client=client_of(request))
    return one_view(conn, uid, nid)


@app.patch("/api/notes/{note_id}")
def update_note(note_id: int, body: NotePatch, request: Request, user=Depends(current_user), conn=Depends(get_db)):
    uid = user["id"]
    row = member_row(conn, uid, note_id)
    changes = {k: v for k, v in body.model_dump(exclude_unset=True).items() if v is not None}
    shared_change = False
    if "color" in changes:
        conn.execute("UPDATE notes SET color = ? WHERE id = ?", (changes["color"], note_id))
        shared_change = True
    if "trashed" in changes:
        if row["role"] != "owner":
            raise HTTPException(403, "Nur der Inhaber kann die Notiz löschen.")
        conn.execute("UPDATE notes SET trashed_at = ? WHERE id = ?",
                     (core.now() if changes["trashed"] else None, note_id))
        shared_change = True
    sets, params = [], []
    for col in ("pinned", "archived"):
        if col in changes:
            sets.append(f"{col} = ?")
            params.append(int(changes[col]))
    if sets:
        conn.execute(f"UPDATE note_users SET {', '.join(sets)} WHERE note_id = ? AND user_id = ?",
                     (*params, note_id, uid))
    if "labels" in changes:
        set_labels(conn, uid, note_id, changes["labels"])
    conn.commit()
    targets = participants(conn, note_id) if shared_change else [uid]
    publish_note(conn, note_id, targets, exclude_client=client_of(request))
    return one_view(conn, uid, note_id)


@app.delete("/api/notes/{note_id}", status_code=204)
def delete_note(note_id: int, request: Request, user=Depends(current_user), conn=Depends(get_db)):
    row = member_row(conn, user["id"], note_id)
    if row["role"] != "owner":
        raise HTTPException(403, "Nur der Inhaber kann die Notiz endgültig löschen.")
    members = participants(conn, note_id)
    conn.execute("DELETE FROM notes WHERE id = ?", (note_id,))
    conn.commit()
    hub.publish(members, {"t": "gone", "id": note_id}, exclude_client=client_of(request))
    return Response(status_code=204)


@app.post("/api/notes/{note_id}/leave", status_code=204)
def leave_note(note_id: int, request: Request, user=Depends(current_user), conn=Depends(get_db)):
    row = member_row(conn, user["id"], note_id)
    if row["role"] == "owner":
        raise HTTPException(400, "Der Inhaber kann eine Notiz nicht verlassen.")
    _remove_member(conn, note_id, user["id"])
    conn.commit()
    hub.publish([user["id"]], {"t": "gone", "id": note_id}, exclude_client=client_of(request))
    publish_note(conn, note_id, participants(conn, note_id))
    return Response(status_code=204)


def _remove_member(conn, note_id: int, user_id: int) -> None:
    conn.execute("DELETE FROM note_users WHERE note_id = ? AND user_id = ?", (note_id, user_id))
    conn.execute("DELETE FROM note_labels WHERE note_id = ? AND label_id IN "
                 "(SELECT id FROM labels WHERE user_id = ?)", (note_id, user_id))


@app.put("/api/notes/{note_id}/collaborators")
def set_collaborators(note_id: int, body: CollaboratorsIn, request: Request, user=Depends(current_user),
                      conn=Depends(get_db)):
    uid = user["id"]
    row = member_row(conn, uid, note_id)
    if row["trashed_at"] is not None:
        raise HTTPException(400, "Notizen im Papierkorb können nicht geteilt werden.")
    current = set(participants(conn, note_id))
    wanted = set(body.user_ids) | {row["owner_id"]}
    if wanted - current:
        marks = ",".join("?" * len(wanted - current))
        found = {r[0] for r in conn.execute(f"SELECT id FROM users WHERE id IN ({marks})", tuple(wanted - current))}
        if found != wanted - current:
            raise HTTPException(400, "Unbekannter Benutzer")
    removed = current - wanted
    if row["role"] != "owner" and removed - {uid}:
        raise HTTPException(403, "Nur der Inhaber kann andere Mitbearbeiter entfernen.")
    t = core.now()
    for other in wanted - current:
        conn.execute("INSERT INTO note_users(note_id, user_id, role, position, added_at) VALUES (?,?,'editor',?,?)",
                     (note_id, other, next_position(conn, other), t))
    for other in removed:
        _remove_member(conn, note_id, other)
    conn.commit()
    publish_note(conn, note_id, current | wanted, exclude_client=client_of(request))
    if uid in removed:
        return {"left": True}
    return one_view(conn, uid, note_id)


@app.post("/api/notes/{note_id}/copy", status_code=201)
def copy_note(note_id: int, request: Request, user=Depends(current_user), conn=Depends(get_db)):
    uid, t = user["id"], core.now()
    row = member_row(conn, uid, note_id)
    doc = load_doc(row)
    cur = conn.execute("INSERT INTO notes(owner_id, payload, color, rev, created_at, updated_at, updated_by) "
                       "VALUES (?,?,?,0,?,?,?)", (uid, store_doc(doc), row["color"], t, t, uid))
    nid = cur.lastrowid
    conn.execute("INSERT INTO note_users(note_id, user_id, role, position, added_at) VALUES (?,?,'owner',?,?)",
                 (nid, uid, next_position(conn, uid), t))
    conn.execute("INSERT INTO note_labels(note_id, label_id) SELECT ?, nl.label_id FROM note_labels nl "
                 "JOIN labels l ON l.id = nl.label_id WHERE nl.note_id = ? AND l.user_id = ?", (nid, note_id, uid))
    conn.execute("INSERT INTO blobs(owner_id, note_id, kind, mime, width, height, size, key_id, data, created_at) "
                 "SELECT ?, ?, kind, mime, width, height, size, key_id, data, ? FROM blobs "
                 "WHERE note_id = ? AND kind = 'image' ORDER BY id", (uid, nid, t, note_id))
    save_version(conn, nid, uid, doc, t)
    conn.commit()
    publish_note(conn, nid, [uid], exclude_client=client_of(request))
    return one_view(conn, uid, nid)


@app.post("/api/notes/reorder")
def reorder_notes(body: ReorderIn, request: Request, user=Depends(current_user), conn=Depends(get_db)):
    """Ordnet die übergebenen Notizen neu, indem ihre bisherigen Positionen umverteilt werden."""
    uid = user["id"]
    ids = list(dict.fromkeys(body.ids))
    if not ids:
        return {"positions": {}}
    marks = ",".join("?" * len(ids))
    rows = conn.execute(f"SELECT note_id, position FROM note_users WHERE user_id = ? AND note_id IN ({marks})",
                        (uid, *ids)).fetchall()
    if len(rows) != len(ids):
        raise HTTPException(404, "Notiz nicht gefunden")
    positions = sorted((r["position"] for r in rows), reverse=True)
    if len(set(positions)) != len(positions):  # doppelte Positionen auflösen
        top = max(positions)
        positions = [top - i for i in range(len(positions))]
    conn.executemany("UPDATE note_users SET position = ? WHERE note_id = ? AND user_id = ?",
                     [(p, i, uid) for p, i in zip(positions, ids)])
    conn.commit()
    result = {str(i): p for p, i in zip(positions, ids)}
    hub.publish([uid], {"t": "positions", "positions": result}, exclude_client=client_of(request))
    return {"positions": result}


@app.delete("/api/trash", status_code=204)
def empty_trash(user=Depends(current_user), conn=Depends(get_db)):
    conn.execute("DELETE FROM notes WHERE owner_id = ? AND trashed_at IS NOT NULL", (user["id"],))
    conn.commit()
    return Response(status_code=204)


# --------------------------------------------------------------------------- #
# Routen: Bilder
# --------------------------------------------------------------------------- #
@app.post("/api/notes/{note_id}/images", status_code=201)
async def upload_image(note_id: int, request: Request, user=Depends(current_user), conn=Depends(get_db)):
    row = member_row(conn, user["id"], note_id)
    if row["trashed_at"] is not None:
        raise HTTPException(400, "Notizen im Papierkorb können nicht bearbeitet werden.")
    count = conn.execute("SELECT COUNT(*) FROM blobs WHERE note_id = ? AND kind = 'image'", (note_id,)).fetchone()[0]
    if count >= MAX_IMAGES_PER_NOTE:
        raise HTTPException(400, f"Maximal {MAX_IMAGES_PER_NOTE} Bilder pro Notiz.")
    data = await read_limited(request, core.MAX_IMAGE_BYTES)
    try:
        out, mime, w, h = await run_in_threadpool(core.process_image, data)
    except core.ImageError as exc:
        raise HTTPException(400, str(exc)) from exc
    kid, blob = keyring.encrypt_bytes(out)
    t = core.now()
    cur = conn.execute("INSERT INTO blobs(owner_id, note_id, kind, mime, width, height, size, key_id, data, "
                       "created_at) VALUES (?,?,'image',?,?,?,?,?,?,?)",
                       (user["id"], note_id, mime, w, h, len(out), kid, blob, t))
    conn.execute("UPDATE notes SET updated_at = ?, updated_by = ? WHERE id = ?", (t, user["id"], note_id))
    conn.commit()
    publish_note(conn, note_id, participants(conn, note_id), exclude_client=client_of(request))
    return {"id": cur.lastrowid, "w": w, "h": h}


@app.delete("/api/notes/{note_id}/images/{image_id}", status_code=204)
def delete_image(note_id: int, image_id: int, request: Request, user=Depends(current_user), conn=Depends(get_db)):
    member_row(conn, user["id"], note_id)
    conn.execute("DELETE FROM blobs WHERE id = ? AND note_id = ? AND kind = 'image'", (image_id, note_id))
    conn.commit()
    publish_note(conn, note_id, participants(conn, note_id), exclude_client=client_of(request))
    return Response(status_code=204)


@app.get("/api/images/{image_id}")
def get_image(image_id: int, user=Depends(current_user), conn=Depends(get_db)):
    row = conn.execute(
        "SELECT b.mime, b.key_id, b.data FROM blobs b JOIN note_users nu ON nu.note_id = b.note_id "
        "WHERE b.id = ? AND b.kind = 'image' AND nu.user_id = ?", (image_id, user["id"])).fetchone()
    if not row:
        raise HTTPException(404, "Bild nicht gefunden")
    return Response(keyring.decrypt_bytes(row["key_id"], row["data"]), media_type=row["mime"],
                    headers={"Cache-Control": "private, max-age=31536000, immutable"})


# --------------------------------------------------------------------------- #
# Routen: Versionsverlauf
# --------------------------------------------------------------------------- #
@app.get("/api/notes/{note_id}/versions")
def list_versions(note_id: int, user=Depends(current_user), conn=Depends(get_db)):
    member_row(conn, user["id"], note_id)
    users = {r["id"]: user_view(r) for r in conn.execute("SELECT id, username, avatar_id FROM users")}
    out = []
    for r in conn.execute("SELECT id, user_id, created_at, updated_at, payload FROM note_versions "
                          "WHERE note_id = ? ORDER BY id DESC", (note_id,)):
        ids = json.loads(keyring.decrypt(r["payload"])).get("editors") or [r["user_id"]]
        editors = [users.get(i) or {"id": i, "username": "Gelöschter Benutzer", "avatar": None} for i in ids]
        out.append({"id": r["id"], "created_at": r["created_at"], "updated_at": r["updated_at"], "editors": editors})
    return out


@app.get("/api/notes/{note_id}/versions/{version_id}")
def get_version(note_id: int, version_id: int, user=Depends(current_user), conn=Depends(get_db)):
    member_row(conn, user["id"], note_id)
    row = conn.execute("SELECT payload FROM note_versions WHERE id = ? AND note_id = ?",
                       (version_id, note_id)).fetchone()
    if not row:
        raise HTTPException(404, "Version nicht gefunden")
    doc = load_doc(row)
    doc.pop("editors", None)
    return doc


@app.post("/api/notes/{note_id}/versions/{version_id}/restore")
async def restore_version(note_id: int, version_id: int, user=Depends(current_user)):
    async with hub.lock(note_id):
        conn = core.connect()
        try:
            row = member_row(conn, user["id"], note_id)
            if row["trashed_at"] is not None:
                raise HTTPException(400, "Notizen im Papierkorb können nicht bearbeitet werden.")
            v = conn.execute("SELECT payload FROM note_versions WHERE id = ? AND note_id = ?",
                             (version_id, note_id)).fetchone()
            if not v:
                raise HTTPException(404, "Version nicht gefunden")
            doc = load_doc(row)
            target = load_doc(v)
            target.pop("editors", None)
            change = ot.parse_change(ot.replace_change(doc, target))
            if not change:
                return {"rev": row["rev"]}
            cid = uuid.uuid4().hex
            rev, _ = commit_change(conn, row, user["id"], "server", cid, change, doc)
            conn.commit()
            members = participants(conn, note_id)
        finally:
            conn.close()
        hub.publish(members, {"t": "op", "id": note_id, "rev": rev, "change": change, "by": user["id"],
                              "client": "server", "cid": cid})
    return {"rev": rev}


# --------------------------------------------------------------------------- #
# Routen: Labels
# --------------------------------------------------------------------------- #
def _label_list(conn, user_id: int) -> list[dict]:
    labels = [{"id": r["id"], "name": keyring.decrypt(r["name"])}
              for r in conn.execute("SELECT id, name FROM labels WHERE user_id = ?", (user_id,))]
    return sorted(labels, key=lambda lab: lab["name"].casefold())


def _ensure_unique_label(conn, user_id: int, name: str, exclude: int | None = None) -> None:
    key = name.casefold()
    for lab in _label_list(conn, user_id):
        if lab["id"] != exclude and lab["name"].casefold() == key:
            raise HTTPException(409, "Ein Label mit diesem Namen existiert bereits.")


@app.get("/api/labels")
def list_labels(user=Depends(current_user), conn=Depends(get_db)):
    return _label_list(conn, user["id"])


@app.post("/api/labels", status_code=201)
def create_label(body: LabelIn, request: Request, user=Depends(current_user), conn=Depends(get_db)):
    name = body.name.strip()
    if not name:
        raise HTTPException(400, "Der Labelname darf nicht leer sein.")
    _ensure_unique_label(conn, user["id"], name)
    cur = conn.execute("INSERT INTO labels(user_id, name) VALUES (?, ?)", (user["id"], keyring.encrypt(name)))
    conn.commit()
    hub.publish([user["id"]], {"t": "labels"}, exclude_client=client_of(request))
    return {"id": cur.lastrowid, "name": name}


@app.patch("/api/labels/{label_id}")
def rename_label(label_id: int, body: LabelIn, request: Request, user=Depends(current_user), conn=Depends(get_db)):
    name = body.name.strip()
    if not name:
        raise HTTPException(400, "Der Labelname darf nicht leer sein.")
    if not conn.execute("SELECT 1 FROM labels WHERE id = ? AND user_id = ?", (label_id, user["id"])).fetchone():
        raise HTTPException(404, "Label nicht gefunden")
    _ensure_unique_label(conn, user["id"], name, exclude=label_id)
    conn.execute("UPDATE labels SET name = ? WHERE id = ?", (keyring.encrypt(name), label_id))
    conn.commit()
    hub.publish([user["id"]], {"t": "labels"}, exclude_client=client_of(request))
    return {"id": label_id, "name": name}


@app.delete("/api/labels/{label_id}", status_code=204)
def delete_label(label_id: int, request: Request, user=Depends(current_user), conn=Depends(get_db)):
    conn.execute("DELETE FROM labels WHERE id = ? AND user_id = ?", (label_id, user["id"]))
    conn.commit()
    hub.publish([user["id"]], {"t": "labels"}, exclude_client=client_of(request))
    return Response(status_code=204)


# --------------------------------------------------------------------------- #
# WebSocket: Befehle, Synchronisation, Live-Cursor
# --------------------------------------------------------------------------- #
def _process_op(conn_ws: Conn, note_id: int, base: int, cid: str, raw_change) -> tuple[list, list]:
    """Verarbeitet einen Befehl. Gibt (Antworten an den Absender, Broadcast-Nachrichten) zurück."""
    db = core.connect()
    try:
        row = db.execute("SELECT n.*, nu.role FROM notes n JOIN note_users nu ON nu.note_id = n.id "
                         "AND nu.user_id = ? WHERE n.id = ?", (conn_ws.user_id, note_id)).fetchone()
        if not row or row["trashed_at"] is not None:
            return [{"t": "gone", "id": note_id}], []
        dup = db.execute("SELECT rev FROM note_ops WHERE note_id = ? AND client = ? AND cid = ?",
                         (note_id, conn_ws.client_id, cid)).fetchone()
        if dup:  # Befehl wurde schon verarbeitet (z. B. nach Verbindungsabbruch erneut gesendet)
            return [{"t": "ack", "id": note_id, "rev": dup["rev"], "cid": cid}], []
        rev = row["rev"]
        if base > rev or base < 0:
            return [{"t": "resync", "id": note_id}], []
        try:
            change = ot.parse_change(raw_change)
            if base < rev:
                ops = db.execute("SELECT op FROM note_ops WHERE note_id = ? AND rev > ? ORDER BY rev",
                                 (note_id, base)).fetchall()
                if len(ops) != rev - base:
                    return [{"t": "resync", "id": note_id}], []
                for o in ops:
                    change, _ = ot.xform(change, json.loads(keyring.decrypt(o["op"])))
            if not change:
                return [{"t": "ack", "id": note_id, "rev": rev, "cid": cid}], []
            new_rev, _ = commit_change(db, row, conn_ws.user_id, conn_ws.client_id, cid, change, load_doc(row))
        except ot.InvalidOp as exc:
            log.warning("Ungültiger Befehl von %s für Notiz %s: %s", conn_ws.username, note_id, exc)
            return [{"t": "error", "id": note_id, "message": str(exc)}, {"t": "resync", "id": note_id}], []
        db.commit()
        members = participants(db, note_id)
    finally:
        db.close()
    op_msg = {"t": "op", "id": note_id, "rev": new_rev, "change": change, "by": conn_ws.user_id,
              "client": conn_ws.client_id, "cid": cid}
    return [{"t": "ack", "id": note_id, "rev": new_rev, "cid": cid}], [(members, op_msg)]


def _catchup(conn_ws: Conn, note_id: int, since: int) -> list:
    db = core.connect()
    try:
        row = db.execute("SELECT n.rev, n.trashed_at, nu.role FROM notes n JOIN note_users nu "
                         "ON nu.note_id = n.id AND nu.user_id = ? WHERE n.id = ?",
                         (conn_ws.user_id, note_id)).fetchone()
        if not row or (row["trashed_at"] is not None and row["role"] != "owner"):
            return [{"t": "gone", "id": note_id}]
        rows = db.execute("SELECT rev, user_id, client, cid, op FROM note_ops WHERE note_id = ? AND rev > ? "
                          "ORDER BY rev", (note_id, since)).fetchall()
        if len(rows) != row["rev"] - since:
            return [{"t": "resync", "id": note_id}]
        out = [{"t": "op", "id": note_id, "rev": r["rev"], "change": json.loads(keyring.decrypt(r["op"])),
                "by": r["user_id"], "client": r["client"], "cid": r["cid"]} for r in rows]
        out.append({"t": "caught", "id": note_id, "rev": row["rev"]})
        return out
    finally:
        db.close()


def _can_access(user_id: int, note_id: int) -> bool:
    db = core.connect()
    try:
        return db.execute("SELECT 1 FROM note_users nu JOIN notes n ON n.id = nu.note_id WHERE nu.note_id = ? "
                          "AND nu.user_id = ?", (note_id, user_id)).fetchone() is not None
    finally:
        db.close()


def _send(conn: Conn, msg: dict) -> None:
    conn.push(json.dumps(msg, ensure_ascii=False, separators=(",", ":")))


def _close_note(conn: Conn) -> None:
    if conn.open_note is not None:
        hub.to_viewers(conn.open_note, {"t": "presence", "id": conn.open_note, "conn": conn.id,
                                        "user": conn.user_info(), "open": False}, exclude_conn=conn)
    conn.open_note = None
    conn.cursor = None


async def _handle_ws(conn: Conn, msg: dict) -> None:
    t = msg.get("t")
    note_id = msg.get("id")
    if t == "ping":
        _send(conn, {"t": "pong"})
        return
    if not isinstance(note_id, int) or isinstance(note_id, bool):
        return
    if t == "op":
        base, cid = msg.get("rev"), msg.get("cid")
        if not isinstance(base, int) or not isinstance(cid, str) or not CLIENT_RE.match(cid):
            return
        async with hub.lock(note_id):
            replies, broadcasts = _process_op(conn, note_id, base, cid, msg.get("change"))
            for r in replies:
                _send(conn, r)
            for members, m in broadcasts:
                hub.publish(members, m, exclude_conn=conn)
    elif t == "catchup":
        since = msg.get("rev")
        if isinstance(since, int) and since >= 0:
            async with hub.lock(note_id):
                for r in _catchup(conn, note_id, since):
                    _send(conn, r)
    elif t == "open":
        _close_note(conn)
        if not _can_access(conn.user_id, note_id):
            return
        conn.open_note = note_id
        for other in hub.all_conns():
            if other is not conn and other.open_note == note_id:
                _send(conn, {"t": "presence", "id": note_id, "conn": other.id, "user": other.user_info(),
                             "open": True})
                if other.cursor:
                    _send(conn, {"t": "cursor", "id": note_id, "conn": other.id, "user": other.user_info(),
                                 **other.cursor})
        hub.to_viewers(note_id, {"t": "presence", "id": note_id, "conn": conn.id, "user": conn.user_info(),
                                 "open": True}, exclude_conn=conn)
    elif t == "close":
        if conn.open_note == note_id:
            _close_note(conn)
    elif t == "cursor":
        if conn.open_note != note_id:
            return
        f, i, ln = msg.get("f"), msg.get("i"), msg.get("l")
        if f is None:
            conn.cursor = None
            payload = {"f": None, "i": 0, "l": 0}
        else:
            if not isinstance(f, str) or not re.fullmatch(r"title|body|item:[A-Za-z0-9_-]{1,40}", f):
                return
            if not isinstance(i, int) or not isinstance(ln, int) or i < 0 or ln < 0:
                return
            payload = {"f": f, "i": i, "l": ln, "rev": msg.get("rev") if isinstance(msg.get("rev"), int) else 0}
            conn.cursor = payload
        hub.to_viewers(note_id, {"t": "cursor", "id": note_id, "conn": conn.id, "user": conn.user_info(),
                                 **payload}, exclude_conn=conn)


@app.websocket("/ws")
async def websocket(ws: WebSocket):
    origin = ws.headers.get("origin")
    if origin and urlparse(origin).netloc != ws.headers.get("host"):
        await ws.close(code=4403)  # Schutz gegen Cross-Site-WebSocket-Hijacking
        return
    token = ws.cookies.get(COOKIE_NAME)
    db = core.connect()
    try:
        user = _session_user(db, token)
    finally:
        db.close()
    if not user:
        await ws.close(code=4401)
        return
    client_id = ws.query_params.get("client", "")
    if not CLIENT_RE.match(client_id):
        client_id = secrets.token_hex(8)
    await ws.accept()
    conn = Conn(ws, user, client_id, user["token_hash"])
    hub.add(conn)
    writer = asyncio.create_task(conn.writer())
    _send(conn, {"t": "hello", "conn": conn.id})
    try:
        while not conn.closed:
            text = await ws.receive_text()
            if time.monotonic() - conn.checked > 30:  # Sitzung regelmäßig erneut prüfen
                db = core.connect()
                try:
                    valid = db.execute("SELECT 1 FROM sessions WHERE token_hash = ? AND expires_at > ?",
                                       (conn.token_hash, core.now())).fetchone()
                finally:
                    db.close()
                if not valid:
                    await ws.close(code=4401)
                    break
                conn.checked = time.monotonic()
            try:
                msg = json.loads(text)
            except json.JSONDecodeError:
                continue
            if isinstance(msg, dict):
                await _handle_ws(conn, msg)
    except WebSocketDisconnect:
        pass
    except Exception:  # noqa: BLE001
        log.exception("WebSocket-Fehler")
    finally:
        conn.closed = True
        hub.remove(conn)
        if conn.open_note is not None:
            hub.to_viewers(conn.open_note, {"t": "cursor", "id": conn.open_note, "conn": conn.id,
                                            "user": conn.user_info(), "f": None, "i": 0, "l": 0})
        _close_note(conn)
        writer.cancel()


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
    uvicorn.run(app, host=args.host, port=args.port, proxy_headers=True, server_header=False,
                log_level="info", ws_max_size=4 * 1024 * 1024)


if __name__ == "__main__":
    main()
