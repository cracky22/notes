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
import base64
import hashlib
import json
import logging
import os
import re
import secrets
import sqlite3
import sys
import subprocess
import threading
import time
import uuid
import zlib
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from typing import List, Optional
from urllib.parse import urlparse

from fastapi import Depends, FastAPI, HTTPException, Request, Response, WebSocket, WebSocketDisconnect
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, JSONResponse, Response as FastAPIResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field, field_validator

import core
import ot
import search_index as sidx

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

    def close_sessions(self, token_hashes) -> None:
        hashes = set(token_hashes)
        if not hashes or self.loop is None:
            return
        def close_now():
            for c in list(self.all_conns()):
                if c.token_hash in hashes:
                    c.closed = True
                    asyncio.create_task(c.ws.close(code=4401))
        self.loop.call_soon_threadsafe(close_now)


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


# --------------------------------------------------------------------------- #
# Suchindex-Worker: zieht geänderte Notizen nach und baut zurückgesetzte Indizes neu auf
# --------------------------------------------------------------------------- #
_index_wakeup: asyncio.Event | None = None
_index_stop = threading.Event()


def wake_indexer() -> None:
    if hub.loop is not None and _index_wakeup is not None:
        hub.loop.call_soon_threadsafe(_index_wakeup.set)


def _index_housekeeping() -> int | None:
    conn = core.connect()
    try:
        sidx.ensure_rows(conn, keyring)
        conn.commit()
        return sidx.next_pending_user(conn)
    finally:
        conn.close()


async def index_worker() -> None:
    global _index_wakeup
    _index_wakeup = asyncio.Event()
    while not _index_stop.is_set():
        try:
            while await run_in_threadpool(sidx.process_dirty, keyring) >= sidx.DIRTY_BATCH:
                await asyncio.sleep(0)
            uid = await run_in_threadpool(_index_housekeeping)
            if uid is not None:
                result = await run_in_threadpool(sidx.build_user, keyring, uid, _index_stop.is_set, 0.01)
                log.info("Suchindex für Benutzer %s: %s", uid, result)
                continue
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            log.exception("Suchindex-Worker")
        _index_wakeup.clear()
        try:
            await asyncio.wait_for(_index_wakeup.wait(), timeout=2)
        except asyncio.TimeoutError:
            pass


@asynccontextmanager
async def lifespan(_app: FastAPI):
    preflight()
    hub.loop = asyncio.get_running_loop()
    _index_stop.clear()
    compressor = asyncio.create_task(compress_existing_archived_notes())
    indexer = asyncio.create_task(index_worker())
    try:
        yield
    finally:
        _index_stop.set()
        for task in (compressor, indexer):
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass


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
        "SELECT u.id, u.username, u.avatar_id, u.settings, u.is_admin, u.storage_limit, s.token_hash FROM sessions s "
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


class MagicRedeemIn(BaseModel):
    token: str = Field(min_length=32, max_length=256)


class MagicLinkIn(BaseModel):
    label: str = Field(default="Dieses Gerät", min_length=1, max_length=80)
    days: int = Field(default=30, ge=1, le=365)


class DeviceChallengeIn(BaseModel):
    challenge: str = Field(min_length=32, max_length=256)


class AdminLimitIn(BaseModel):
    storage_limit: int | None = Field(default=None, ge=0, le=10**13)


class AdminUserCreateIn(BaseModel):
    username: str = Field(min_length=3, max_length=64)
    password: str = Field(min_length=8, max_length=core.MAX_PASSWORD_LEN)


class AdminPasswordResetIn(BaseModel):
    new_password: str = Field(min_length=8, max_length=core.MAX_PASSWORD_LEN)


class SearchQueryIn(BaseModel):
    q: str = Field(default="", max_length=500)


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
COMPRESSED_PREFIX = core.COMPRESSED_PAYLOAD_PREFIX


def _decrypted_payload_text(token: str) -> str:
    return core.decompress_payload_text(keyring.decrypt(token))


def load_doc(row) -> dict:
    return json.loads(_decrypted_payload_text(row["payload"]))


def _compressed_envelope(text: str) -> str | None:
    return core.compress_payload_text(text,0.05)

def store_doc(doc: dict, compress: bool=False) -> str:
    text=json.dumps(doc,ensure_ascii=False,separators=(",",":"))
    wrapped=_compressed_envelope(text) if compress else None
    return keyring.encrypt(wrapped or text)


def store_note_doc(doc: dict, compress: bool) -> tuple[str, bool]:
    text=json.dumps(doc,ensure_ascii=False,separators=(",",":"))
    wrapped=_compressed_envelope(text) if compress else None
    return keyring.encrypt(wrapped or text),bool(wrapped)


def sync_version_compression(conn, note_id: int, compress: bool) -> bool:
    rows=conn.execute("SELECT id,payload FROM note_versions WHERE note_id=?",(note_id,)).fetchall()
    replacements=[]
    growth=0
    for r in rows:
        text=keyring.decrypt(r["payload"])
        is_compressed=text.startswith(COMPRESSED_PREFIX)
        if compress and not is_compressed:
            wrapped=_compressed_envelope(text)
            if not wrapped:
                continue
            replacement=keyring.encrypt(wrapped)
        elif not compress and is_compressed:
            replacement=keyring.encrypt(_decrypted_payload_text(r["payload"]))
            growth+=max(0,len(replacement.encode())-len(r["payload"].encode()))
        else:
            continue
        replacements.append((replacement,r["id"],r["payload"]))
    if growth:
        owner=conn.execute("SELECT owner_id FROM notes WHERE id=?",(note_id,)).fetchone()
        if owner:
            enforce_quota(conn,owner["owner_id"],growth)
    changed=False
    for replacement,version_id,old_payload in replacements:
        changed |= conn.execute("UPDATE note_versions SET payload=? WHERE id=? AND payload=?",
                                (replacement,version_id,old_payload)).rowcount==1
    return changed


def all_participants_archived(conn, note_id: int) -> bool:
    row=conn.execute("SELECT COUNT(*),COALESCE(SUM(CASE WHEN archived=0 THEN 1 ELSE 0 END),0) "
                     "FROM note_users WHERE note_id=?",(note_id,)).fetchone()
    return bool(row and row[0]>0 and row[1]==0)


def sync_note_compression(conn, note_id: int) -> bool:
    """Compress only when every current participant archived the note; otherwise keep it plain."""
    row=conn.execute("SELECT payload,payload_compressed FROM notes WHERE id=?",(note_id,)).fetchone()
    if not row:
        return False
    all_archived=all_participants_archived(conn,note_id)
    versions_changed=sync_version_compression(conn,note_id,all_archived)
    if all_archived and not row["payload_compressed"]:
        text=keyring.decrypt(row["payload"])
        wrapped=_compressed_envelope(text)
        if not wrapped:
            return versions_changed
        encrypted=keyring.encrypt(wrapped)
        changed=conn.execute("UPDATE notes SET payload=?,payload_compressed=1 WHERE id=? AND payload=? "
            "AND payload_compressed=0 AND NOT EXISTS (SELECT 1 FROM note_users nu WHERE nu.note_id=? AND nu.archived=0)",
            (encrypted,note_id,row["payload"],note_id)).rowcount
        return changed==1 or versions_changed
    if not all_archived and row["payload_compressed"]:
        text=_decrypted_payload_text(row["payload"])
        encrypted=keyring.encrypt(text)
        growth=max(0,len(encrypted.encode())-len(row["payload"].encode()))
        if growth:
            owner=conn.execute("SELECT owner_id FROM notes WHERE id=?",(note_id,)).fetchone()
            if owner:
                enforce_quota(conn,owner["owner_id"],growth)
        changed=conn.execute("UPDATE notes SET payload=?,payload_compressed=0 WHERE id=? AND payload=? "
            "AND payload_compressed=1 AND EXISTS (SELECT 1 FROM note_users nu WHERE nu.note_id=? AND nu.archived=0)",
            (encrypted,note_id,row["payload"],note_id)).rowcount
        return changed==1 or versions_changed
    return versions_changed


def compress_archived_batch(after_id: int=0, limit: int=50) -> tuple[int,int]:
    conn=core.connect()
    try:
        rows=conn.execute("SELECT n.id FROM notes n WHERE n.id>? AND n.payload_compressed=0 "
            "AND EXISTS (SELECT 1 FROM note_users nu WHERE nu.note_id=n.id) "
            "AND NOT EXISTS (SELECT 1 FROM note_users nu WHERE nu.note_id=n.id AND nu.archived=0) "
            "ORDER BY n.id LIMIT ?",(after_id,limit)).fetchall()
        if not rows:
            return after_id,0
        last_id=rows[-1]["id"]
        for r in rows:
            sync_note_compression(conn,r["id"])
        conn.commit()
        return last_id,len(rows)
    finally:
        conn.close()


async def compress_existing_archived_notes() -> None:
    cursor=0
    while True:
        cursor,count=await run_in_threadpool(compress_archived_batch,cursor)
        if count==0:
            break
        await asyncio.sleep(0.05)


def user_view(row) -> dict:
    return {"id": row["id"], "username": row["username"], "avatar": row["avatar_id"]}


def participants(conn, note_id: int) -> list[int]:
    return [r[0] for r in conn.execute("SELECT user_id FROM note_users WHERE note_id = ?", (note_id,))]


def note_views(conn, user_id: int, note_id: int | None = None, note_ids: list[int] | None = None,
               defer_archived_content: bool = False) -> list[dict]:
    where, params = "nu.user_id = ?", [user_id]
    sub, sub_params = "SELECT note_id FROM note_users WHERE user_id = ?", [user_id]
    if note_id is not None:
        where += " AND n.id = ?"
        params.append(note_id)
        sub += " AND note_id = ?"
        sub_params.append(note_id)
    if note_ids is not None:
        if not note_ids:
            return []
        marks = ",".join("?" for _ in note_ids)
        where += f" AND n.id IN ({marks})"
        params.extend(note_ids)
        sub += f" AND note_id IN ({marks})"
        sub_params.extend(note_ids)
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
        deferred=defer_archived_content and bool(r["archived"])
        doc=({"title":"","body":[{"insert":"\n"}],"items":[],"is_list":False} if deferred else load_doc(r))
        out.append({
            "id": r["id"], "rev": r["rev"],
            "title": doc["title"], "body": doc["body"], "items": doc["items"], "is_list": doc["is_list"],
            "content_deferred": deferred, "compressed": bool(r["payload_compressed"]),
            "color": r["color"], "images": [] if deferred else images.get(r["id"], []), "members": members.get(r["id"], []),
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
        prev = json.loads(_decrypted_payload_text(last["payload"])).get("editors", [])
        editors = prev + [user_id] if user_id not in prev else prev
        conn.execute("UPDATE note_versions SET payload = ?, updated_at = ?, user_id = ? WHERE id = ?",
                     (store_doc({**doc, "editors": editors},compress=all_participants_archived(conn,note_id)), t, user_id, last["id"]))
    else:
        conn.execute("INSERT INTO note_versions(note_id, user_id, created_at, updated_at, payload) "
                     "VALUES (?,?,?,?,?)", (note_id, user_id, t, t, store_doc({**doc, "editors": editors},compress=all_participants_archived(conn,note_id))))
        conn.execute("DELETE FROM note_versions WHERE note_id = ? AND id NOT IN (SELECT id FROM note_versions "
                     "WHERE note_id = ? ORDER BY id DESC LIMIT ?)", (note_id, note_id, core.VERSIONS_KEEP))


def commit_change(conn, row, user_id: int, client: str, cid: str, change: list, doc: dict) -> tuple[int, dict]:
    """Wendet eine (bereits transformierte) Änderung an und protokolliert sie. Gibt (rev, doc) zurück."""
    new_doc = ot.apply_change(doc, change)
    t = core.now()
    rev = row["rev"] + 1
    new_payload, compressed = store_note_doc(new_doc,all_participants_archived(conn,row["id"]))
    owner = conn.execute("SELECT owner_id,payload FROM notes WHERE id=?", (row["id"],)).fetchone()
    op_payload = keyring.encrypt(json.dumps(change, ensure_ascii=False, separators=(",", ":")))
    last = conn.execute("SELECT payload,created_at,updated_at FROM note_versions WHERE note_id=? ORDER BY id DESC LIMIT 1",
                        (row["id"],)).fetchone()
    editors=[user_id]
    if last and t-last["updated_at"] < core.VERSION_IDLE and t-last["created_at"] < core.VERSION_WINDOW:
        editors=json.loads(_decrypted_payload_text(last["payload"])).get("editors",[])
        if user_id not in editors: editors=editors+[user_id]
        version_payload=store_doc({**new_doc,"editors":editors},compress=all_participants_archived(conn,row["id"]))
        version_delta=len(version_payload.encode())-len(last["payload"].encode())
    else:
        version_payload=store_doc({**new_doc,"editors":editors},compress=all_participants_archived(conn,row["id"]))
        version_delta=len(version_payload.encode())
    growth=(len(new_payload.encode())-len(owner["payload"].encode())
            +len(op_payload.encode())+version_delta)
    enforce_quota(conn, owner["owner_id"], max(0,growth))
    conn.execute("UPDATE notes SET payload = ?, payload_compressed = ?, rev = ?, updated_at = ?, updated_by = ? WHERE id = ?",
                 (new_payload,int(compressed),rev,t,user_id,row["id"]))
    conn.execute("INSERT INTO note_ops(note_id, rev, user_id, client, cid, created_at, op) VALUES (?,?,?,?,?,?,?)",
                 (row["id"], rev, user_id, client, cid, t, op_payload))
    conn.execute("DELETE FROM note_ops WHERE note_id = ? AND rev <= ?", (row["id"], rev - core.OPLOG_KEEP))
    save_version(conn, row["id"], user_id, new_doc, t)
    sidx.mark_dirty(conn, row["id"])
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
    return {"id": row["id"], "username": row["username"], "avatar": row["avatar_id"], "settings": settings,
            "is_admin": bool(row["is_admin"]) if "is_admin" in row.keys() else False}


def storage_usage(conn, user_id: int) -> int:
    notes = conn.execute("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM notes WHERE owner_id=?",
                         (user_id,)).fetchone()[0]
    ops = conn.execute("SELECT COALESCE(SUM(length(CAST(o.op AS BLOB))),0) FROM note_ops o "
                       "JOIN notes n ON n.id=o.note_id WHERE n.owner_id=?", (user_id,)).fetchone()[0]
    versions = conn.execute("SELECT COALESCE(SUM(length(CAST(v.payload AS BLOB))),0) FROM note_versions v "
                            "JOIN notes n ON n.id=v.note_id WHERE n.owner_id=?", (user_id,)).fetchone()[0]
    blobs = conn.execute("SELECT COALESCE(SUM(size),0) FROM blobs WHERE owner_id=?", (user_id,)).fetchone()[0]
    return int(notes + ops + versions + blobs)


def enforce_quota(conn, user_id: int, extra_bytes: int) -> None:
    limit = conn.execute("SELECT storage_limit FROM users WHERE id=?", (user_id,)).fetchone()
    if limit and limit[0] is not None and storage_usage(conn, user_id) + max(0, extra_bytes) > limit[0]:
        raise HTTPException(413, "Speicherkontingent erreicht. Bitte Daten entfernen oder den Administrator kontaktieren.")


def admin_user(user=Depends(current_user)):
    if not user["is_admin"]:
        raise HTTPException(403, "Administratorrechte erforderlich.")
    return user


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
    token_hash = _token_hash(token)
    conn.execute("INSERT INTO sessions(token_hash, user_id, created_at, expires_at, user_agent, last_seen) "
                 "VALUES (?,?,?,?,?,?)", (token_hash, row["id"], t, t + core.SESSION_DAYS * 86400,
                                            request.headers.get("user-agent", "")[:300], t))
    conn.execute("DELETE FROM sessions WHERE expires_at < ?", (t,))
    conn.commit()
    response.set_cookie(COOKIE_NAME, token, max_age=core.SESSION_DAYS * 86400, httponly=True,
                        samesite="strict", secure=_is_https(request), path="/")
    return me_view(row)


@app.get("/api/me/usage")
def me_usage(user=Depends(current_user), conn=Depends(get_db)):
    return {"used_bytes": storage_usage(conn, user["id"]), "limit_bytes": user["storage_limit"]}


# --------------------------------------------------------------------------- #
# Routen: Suchindex
# --------------------------------------------------------------------------- #
@app.post("/api/search")
def search_notes(body: SearchQueryIn, user=Depends(current_user), conn=Depends(get_db)):
    """Serverseitige Volltextsuche über den eigenen Index (inkl. nicht geladener Archivnotizen)."""
    q = body.q
    st = sidx.status(conn, user["id"])
    ids = sidx.search(conn, keyring, user["id"], q) if q.strip() else []
    return {"state": st["state"], "ready": st["state"] == "ready", "done": st["done"], "total": st["total"],
            "ids": ids}


@app.get("/api/me/search-index")
def my_search_index(user=Depends(current_user), conn=Depends(get_db)):
    st = sidx.status(conn, user["id"])
    if st["state"] == "error":
        st["error"] = "Neuaufbau fehlgeschlagen. Bitte den Administrator kontaktieren."
    return st


@app.post("/api/me/search-index/rebuild")
def rebuild_my_search_index(user=Depends(current_user), conn=Depends(get_db)):
    st = sidx.status(conn, user["id"])
    if st["state"] in ("pending", "building"):
        return st
    left = sidx.user_cooldown_left(conn, user["id"])
    if left and st["state"] != "error":
        raise HTTPException(429, f"Neuindizierung ist in {max(1, round(left / 60))} Min. wieder möglich.")
    sidx.reset_user(conn, user["id"], "user", keyring)
    conn.commit()
    wake_indexer()
    return sidx.status(conn, user["id"])


@app.get("/api/me/sessions")
def list_sessions(user=Depends(current_user), conn=Depends(get_db)):
    rows = conn.execute("SELECT token_hash, created_at, expires_at, user_agent, last_seen FROM sessions "
                        "WHERE user_id=? AND expires_at>? ORDER BY last_seen DESC", (user["id"], core.now())).fetchall()
    return [{"id": r["token_hash"], "created_at": r["created_at"], "expires_at": r["expires_at"],
             "last_seen": r["last_seen"], "user_agent": r["user_agent"],
             "current": r["token_hash"] == user["token_hash"]} for r in rows]


@app.delete("/api/me/sessions/{session_id}", status_code=204)
def revoke_session(session_id: str, user=Depends(current_user), conn=Depends(get_db)):
    row = conn.execute("SELECT token_hash FROM sessions WHERE user_id=? AND token_hash=?",
                       (user["id"], session_id)).fetchone()
    if not row:
        raise HTTPException(404, "Sitzung nicht gefunden")
    conn.execute("DELETE FROM sessions WHERE token_hash=?", (session_id,))
    conn.commit()
    hub.close_sessions([session_id])
    return Response(status_code=204)


@app.get("/api/magic-links")
def list_magic_links(user=Depends(current_user), conn=Depends(get_db)):
    return [dict(id=r["id"], label=r["label"], created_at=r["created_at"], expires_at=r["expires_at"],
                 last_used_at=r["last_used_at"]) for r in conn.execute(
        "SELECT id,label,created_at,expires_at,last_used_at FROM magic_links WHERE user_id=? ORDER BY id DESC",
        (user["id"],))]


@app.post("/api/magic-links", status_code=201)
def create_magic_link(body: MagicLinkIn, request: Request, user=Depends(current_user), conn=Depends(get_db)):
    token = secrets.token_urlsafe(40)
    t = core.now()
    cur = conn.execute("INSERT INTO magic_links(user_id,token_hash,label,created_at,expires_at) VALUES (?,?,?,?,?)",
                       (user["id"], _token_hash(token), body.label.strip(), t, t + body.days * 86400))
    conn.commit()
    public = os.environ.get("NOTIZEN_PUBLIC_URL", str(request.base_url).rstrip("/"))
    return {"id": cur.lastrowid, "token": token, "url": f"{public}/#?magic={token}",
            "expires_at": t + body.days * 86400}


@app.delete("/api/magic-links/{link_id}", status_code=204)
def delete_magic_link(link_id: int, user=Depends(current_user), conn=Depends(get_db)):
    conn.execute("DELETE FROM magic_links WHERE id=? AND user_id=?", (link_id, user["id"]))
    conn.commit()
    return Response(status_code=204)


@app.post("/api/magic-links/redeem")
def redeem_magic_link(body: MagicRedeemIn, request: Request, response: Response, conn=Depends(get_db)):
    t = core.now()
    row = conn.execute("SELECT u.*,m.id AS magic_id FROM magic_links m JOIN users u ON u.id=m.user_id "
                       "WHERE m.token_hash=? AND m.expires_at>?", (_token_hash(body.token), t)).fetchone()
    if not row:
        raise HTTPException(401, "Anmeldelink ungültig oder abgelaufen.")
    token = secrets.token_urlsafe(32)
    token_hash = _token_hash(token)
    conn.execute("INSERT INTO sessions(token_hash,user_id,created_at,expires_at,user_agent,last_seen) "
                 "VALUES (?,?,?,?,?,?)", (token_hash,row["id"],t,t+core.SESSION_DAYS*86400,
                                            request.headers.get("user-agent","")[:300],t))
    conn.execute("UPDATE magic_links SET last_used_at=? WHERE id=?", (t,row["magic_id"]))
    conn.commit()
    response.set_cookie(COOKIE_NAME,token,max_age=core.SESSION_DAYS*86400,httponly=True,samesite="strict",
                        secure=_is_https(request),path="/")
    return me_view(row)


@app.post("/api/device-login/challenge", status_code=201)
def create_device_challenge(conn=Depends(get_db)):
    token = secrets.token_urlsafe(40)
    t = core.now()
    conn.execute("DELETE FROM device_challenges WHERE expires_at<? OR consumed_at<?",(t,t-600))
    conn.execute("INSERT INTO device_challenges(challenge_hash,created_at,expires_at) VALUES (?,?,?)",
                 (_token_hash(token),t,t+180))
    conn.commit()
    return {"challenge":token,"expires_at":t+180}


@app.get("/api/device-login/qr")
def device_challenge_qr(challenge: str, request: Request, conn=Depends(get_db)):
    if len(challenge) < 32 or len(challenge) > 256:
        raise HTTPException(400,"Ungültige Anmeldung")
    if not conn.execute("SELECT 1 FROM device_challenges WHERE challenge_hash=? AND expires_at>?",
                        (_token_hash(challenge),core.now())).fetchone():
        raise HTTPException(410,"Anmeldung abgelaufen.")
    try:
        import qrcode
        import qrcode.image.svg
        qr = qrcode.make(f"{os.environ.get('NOTIZEN_PUBLIC_URL', str(request.base_url).rstrip('/'))}/#?pair={challenge}",
                         image_factory=qrcode.image.svg.SvgPathImage, box_size=5, border=3)
        data = qr.to_string()
    except ImportError as exc:
        raise HTTPException(503,"QR-Code-Unterstützung fehlt (qrcode installieren).") from exc
    return FastAPIResponse(data, media_type="image/svg+xml", headers={"Cache-Control":"no-store"})


@app.post("/api/device-login/approve")
def approve_device_login(body: DeviceChallengeIn, request: Request, user=Depends(current_user), conn=Depends(get_db)):
    t=core.now(); ch=_token_hash(body.challenge)
    row=conn.execute("SELECT * FROM device_challenges WHERE challenge_hash=? AND expires_at>? AND approved_user_id IS NULL",
                     (ch,t)).fetchone()
    if not row:
        raise HTTPException(410,"Anmeldung abgelaufen oder bereits bestätigt.")
    session=secrets.token_urlsafe(32); session_hash=_token_hash(session)
    changed=conn.execute("UPDATE device_challenges SET approved_user_id=?,session_hash=?,session_token_enc=? "
                         "WHERE challenge_hash=? AND expires_at>? AND approved_user_id IS NULL",
                         (user["id"],session_hash,keyring.encrypt(session),ch,t)).rowcount
    if changed != 1:
        conn.rollback()
        raise HTTPException(410,"Anmeldung abgelaufen oder bereits bestätigt.")
    conn.execute("INSERT INTO sessions(token_hash,user_id,created_at,expires_at,user_agent,last_seen) "
                 "VALUES (?,?,?,?,?,?)",(session_hash,user["id"],t,t+core.SESSION_DAYS*86400,
                                          request.headers.get("user-agent","")[:300],t))
    conn.commit()
    return {"ok":True}


@app.post("/api/device-login/status")
def device_login_status(body: DeviceChallengeIn, request: Request, response: Response, conn=Depends(get_db)):
    t=core.now(); ch=_token_hash(body.challenge)
    row=conn.execute("SELECT * FROM device_challenges WHERE challenge_hash=?",(ch,)).fetchone()
    if not row or row["expires_at"]<t:
        raise HTTPException(410,"Anmeldung abgelaufen.")
    if not row["approved_user_id"]:
        return {"status":"pending"}
    if row["consumed_at"]:
        raise HTTPException(410,"Anmeldung wurde bereits verwendet.")
    token=keyring.decrypt(row["session_token_enc"])
    consumed=conn.execute("UPDATE device_challenges SET consumed_at=?,session_token_enc=NULL "
                          "WHERE challenge_hash=? AND consumed_at IS NULL",(t,ch)).rowcount
    if consumed != 1:
        conn.rollback()
        raise HTTPException(410,"Anmeldung wurde bereits verwendet.")
    conn.commit()
    user=conn.execute("SELECT * FROM users WHERE id=?",(row["approved_user_id"],)).fetchone()
    response.set_cookie(COOKIE_NAME,token,max_age=core.SESSION_DAYS*86400,httponly=True,samesite="strict",
                        secure=_is_https(request),path="/")
    return {"status":"approved","user":me_view(user)}


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
    old_size=conn.execute("SELECT size FROM blobs WHERE id=? AND kind='avatar'",(user["avatar_id"],)).fetchone() if user["avatar_id"] else None
    enforce_quota(conn,user["id"],len(jpeg)-(old_size[0] if old_size else 0))
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
def list_notes(offset: int | None = None, limit: int = 80, user=Depends(current_user), conn=Depends(get_db)):
    conn.execute("DELETE FROM notes WHERE owner_id = ? AND trashed_at IS NOT NULL AND trashed_at < ?",
                 (user["id"], core.now() - core.TRASH_DAYS * 86400))
    conn.commit()
    if offset is None:
        return note_views(conn, user["id"],defer_archived_content=True)
    if offset < 0 or limit < 1 or limit > 200:
        raise HTTPException(400,"Ungültige Seitengröße")
    uid=user["id"]
    total=conn.execute("SELECT COUNT(*) FROM notes n JOIN note_users nu ON nu.note_id=n.id "
                        "WHERE nu.user_id=? AND (n.trashed_at IS NULL OR nu.role='owner')",(uid,)).fetchone()[0]
    ids=[r[0] for r in conn.execute("SELECT n.id FROM notes n JOIN note_users nu ON nu.note_id=n.id "
          "WHERE nu.user_id=? AND (n.trashed_at IS NULL OR nu.role='owner') "
          "ORDER BY nu.position DESC,n.id DESC LIMIT ? OFFSET ?",(uid,limit,offset)).fetchall()]
    views=note_views(conn,uid,note_ids=ids,defer_archived_content=True)
    by_id={v["id"]:v for v in views}
    return {"notes":[by_id[i] for i in ids if i in by_id],"total":total,
            "next_offset":offset+len(ids) if len(ids)==limit and offset+len(ids)<total else None}


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
    payload=store_doc(doc)
    version_size=len(store_doc({**doc,"editors":[uid]}).encode())
    enforce_quota(conn,uid,len(payload.encode())+version_size)
    cur = conn.execute("INSERT INTO notes(owner_id, payload, color, rev, created_at, updated_at, updated_by) "
                       "VALUES (?,?,?,0,?,?,?)", (uid, payload, body.color, t, t, uid))
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
    sync_note_compression(conn,nid)
    save_version(conn, nid, uid, doc, t)
    sidx.mark_dirty(conn, nid)
    conn.commit()
    wake_indexer()
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
    if "archived" in changes:
        sync_note_compression(conn,note_id)
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
    sync_note_compression(conn,note_id)
    conn.commit()
    hub.publish([user["id"]], {"t": "gone", "id": note_id}, exclude_client=client_of(request))
    publish_note(conn, note_id, participants(conn, note_id))
    return Response(status_code=204)


def _remove_member(conn, note_id: int, user_id: int) -> None:
    conn.execute("DELETE FROM note_users WHERE note_id = ? AND user_id = ?", (note_id, user_id))
    conn.execute("DELETE FROM note_labels WHERE note_id = ? AND label_id IN "
                 "(SELECT id FROM labels WHERE user_id = ?)", (note_id, user_id))
    sidx.drop_member(conn, note_id, user_id)


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
    sync_note_compression(conn,note_id)
    if wanted - current:
        sidx.mark_dirty(conn, note_id)   # neue Teilnehmer erhalten einen eigenen Indexeintrag
    conn.commit()
    if wanted - current:
        wake_indexer()
    publish_note(conn, note_id, current | wanted, exclude_client=client_of(request))
    if uid in removed:
        return {"left": True}
    return one_view(conn, uid, note_id)


@app.post("/api/notes/{note_id}/copy", status_code=201)
def copy_note(note_id: int, request: Request, user=Depends(current_user), conn=Depends(get_db)):
    uid, t = user["id"], core.now()
    row = member_row(conn, uid, note_id)
    doc = load_doc(row)
    payload=store_doc(doc)
    copy_bytes=conn.execute("SELECT COALESCE(SUM(size),0) FROM blobs WHERE note_id=? AND kind='image'",(note_id,)).fetchone()[0]
    version_size=len(store_doc({**doc,"editors":[uid]}).encode())
    enforce_quota(conn,uid,len(payload.encode())+version_size+copy_bytes)
    cur = conn.execute("INSERT INTO notes(owner_id, payload, color, rev, created_at, updated_at, updated_by) "
                       "VALUES (?,?,?,0,?,?,?)", (uid, payload, row["color"], t, t, uid))
    nid = cur.lastrowid
    conn.execute("INSERT INTO note_users(note_id, user_id, role, position, added_at) VALUES (?,?,'owner',?,?)",
                 (nid, uid, next_position(conn, uid), t))
    conn.execute("INSERT INTO note_labels(note_id, label_id) SELECT ?, nl.label_id FROM note_labels nl "
                 "JOIN labels l ON l.id = nl.label_id WHERE nl.note_id = ? AND l.user_id = ?", (nid, note_id, uid))
    conn.execute("INSERT INTO blobs(owner_id, note_id, kind, mime, width, height, size, key_id, data, created_at) "
                 "SELECT ?, ?, kind, mime, width, height, size, key_id, data, ? FROM blobs "
                 "WHERE note_id = ? AND kind = 'image' ORDER BY id", (uid, nid, t, note_id))
    save_version(conn, nid, uid, doc, t)
    sidx.mark_dirty(conn, nid)
    conn.commit()
    wake_indexer()
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
    enforce_quota(conn,user["id"],len(out))
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
        ids = json.loads(_decrypted_payload_text(r["payload"])).get("editors") or [r["user_id"]]
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
# Administrator: Kontingente, Verbrauch, Sitzungen
# --------------------------------------------------------------------------- #
def _host_memory() -> dict | None:
    try:
        values={}
        with open("/proc/meminfo",encoding="ascii") as f:
            for line in f:
                key, value = line.split(":",1)
                parts=value.split()
                if parts and key in ("MemTotal","MemAvailable"):
                    values[key]=int(parts[0])*1024
        total=values.get("MemTotal")
        available=values.get("MemAvailable")
        if not total or available is None:
            return None
        used=max(0,total-available)
        return {"total_bytes":total,"available_bytes":available,"used_bytes":used,
                "used_percent":round(used/total*100,1)}
    except (OSError,ValueError):
        return None


def _cpu_ticks() -> tuple[int,int] | None:
    try:
        with open("/proc/stat",encoding="ascii") as f:
            fields=f.readline().split()
        if not fields or fields[0] != "cpu":
            return None
        ticks=[int(x) for x in fields[1:]]
        return sum(ticks), ticks[3]+(ticks[4] if len(ticks)>4 else 0)
    except (OSError,ValueError,IndexError):
        return None


def _process_rss(pid: int | None) -> int | None:
    target=pid or os.getpid()
    try:
        with open(f"/proc/{target}/status",encoding="ascii") as f:
            for line in f:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1])*1024
    except (OSError,ValueError,IndexError):
        return None
    return None


def _systemd_service_metrics() -> dict:
    unit="notizen.service"
    status_text=""
    status_ok=False
    status_error=None
    try:
        result=subprocess.run(["systemctl","status","notizen","--no-pager","--full"],
                             capture_output=True,text=True,timeout=3,check=False)
        status_text=(result.stdout or "")+"\n"+(result.stderr or "")
        status_ok=result.returncode in (0,3) and bool(status_text.strip())
    except (OSError,subprocess.TimeoutExpired) as exc:
        status_error="systemctl status notizen konnte nicht ausgeführt werden"

    props={}
    try:
        result=subprocess.run(["systemctl","show",unit,"--no-pager",
                               "--property=ActiveState,SubState,MainPID,MemoryCurrent,CPUUsageNSec,TasksCurrent"],
                             capture_output=True,text=True,timeout=3,check=False)
        for line in result.stdout.splitlines():
            key,sep,value=line.partition("=")
            if sep:
                props[key]=value.strip()
    except (OSError,subprocess.TimeoutExpired):
        pass

    memory=None
    source=None
    match=re.search(r"^\s*Memory:\s*([0-9]+(?:\.[0-9]+)?)\s*([KMGTP]?)(?:i?B)?",status_text,re.I|re.M)
    if match:
        power={"":0,"K":1,"M":2,"G":3,"T":4,"P":5}[match.group(2).upper()]
        memory=int(float(match.group(1))*(1024**power))
        source="systemctl status notizen"
    if memory is None:
        try:
            raw=props.get("MemoryCurrent","")
            if raw.isdigit() and int(raw)<2**63-1:
                memory=int(raw)
                source="systemctl show (MemoryCurrent)"
        except (ValueError,OverflowError):
            pass
    pid=int(props["MainPID"]) if props.get("MainPID","").isdigit() else None
    if memory is None:
        memory=_process_rss(pid)
        if memory is not None:
            source="Prozess-RSS (Fallback)"
    cpu_ns=int(props["CPUUsageNSec"]) if props.get("CPUUsageNSec","").isdigit() else None
    tasks=int(props["TasksCurrent"]) if props.get("TasksCurrent","").isdigit() else None
    return {"unit":unit,"active_state":props.get("ActiveState"),"sub_state":props.get("SubState"),
            "pid":pid,"memory_bytes":memory,"cpu_time_seconds":round(cpu_ns/1_000_000_000,1) if cpu_ns is not None else None,
            "tasks":tasks,"source":source,"systemctl_status_ok":status_ok,
            "error":status_error or (None if status_ok else "systemctl status notizen lieferte keinen lesbaren Dienststatus")}


@app.get("/api/admin/metrics")
def admin_metrics(_admin=Depends(admin_user)):
    before=_cpu_ticks()
    time.sleep(0.12)
    after=_cpu_ticks()
    cpu_percent=None
    if before and after:
        total=after[0]-before[0]
        idle=after[1]-before[1]
        if total>0:
            cpu_percent=round(max(0,min(100,(total-idle)/total*100)),1)
    try:
        load_avg=list(os.getloadavg())
    except (AttributeError,OSError):
        load_avg=None
    cores=os.cpu_count() or 1
    return {"sampled_at":core.now(),"cpu":{"current_percent":cpu_percent,"cores":cores},
            "load_avg":{"one":load_avg[0],"five":load_avg[1],"fifteen":load_avg[2]} if load_avg else None,
            "host_memory":_host_memory(),"service":_systemd_service_metrics()}


@app.get("/api/admin/users")
def admin_list_users(_admin=Depends(admin_user), conn=Depends(get_db)):
    out=[]
    index_status=sidx.all_status(conn)
    for r in conn.execute("SELECT id,username,is_admin,storage_limit,created_at FROM users ORDER BY username COLLATE NOCASE"):
        note_count=conn.execute("SELECT COUNT(*) FROM notes WHERE owner_id=?",(r["id"],)).fetchone()[0]
        out.append({"id":r["id"],"username":r["username"],"is_admin":bool(r["is_admin"]),
                    "storage_limit":r["storage_limit"],"used_bytes":storage_usage(conn,r["id"]),
                    "notes":note_count,"sessions":conn.execute("SELECT COUNT(*) FROM sessions WHERE user_id=? AND expires_at>?",
                    (r["id"],core.now())).fetchone()[0],
                    "search_index":index_status.get(r["id"],{"state":"pending","entries":0,"bytes":0,"done":0,"total":0,
                                                              "built_at":None,"requested_at":None,"requested_by":None,"error":None})})
    return out


@app.post("/api/admin/users/{user_id}/search-index/reset")
def admin_reset_search_index(user_id:int, _admin=Depends(admin_user), conn=Depends(get_db)):
    if not conn.execute("SELECT 1 FROM users WHERE id=?",(user_id,)).fetchone():
        raise HTTPException(404,"Benutzer nicht gefunden")
    sidx.reset_user(conn,user_id,"admin",keyring)
    conn.commit()
    wake_indexer()
    return sidx.status(conn,user_id)


@app.post("/api/admin/users", status_code=201)
def admin_create_user(body:AdminUserCreateIn, _admin=Depends(admin_user), conn=Depends(get_db)):
    try:
        username=core.validate_username(body.username)
        core.validate_password(body.password)
    except ValueError as exc:
        raise HTTPException(400,str(exc)) from exc
    if conn.execute("SELECT 1 FROM users WHERE username=?",(username,)).fetchone():
        raise HTTPException(409,"Dieser Benutzername ist bereits vergeben.")
    t=core.now()
    try:
        cur=conn.execute("INSERT INTO users(username,password_hash,created_at,password_changed_at) VALUES (?,?,?,?)",
                         (username,core.hash_password(body.password),t,t))
    except sqlite3.IntegrityError as exc:
        raise HTTPException(409,"Dieser Benutzername ist bereits vergeben.") from exc
    conn.commit()
    wake_indexer()
    return {"id":cur.lastrowid,"username":username}


@app.post("/api/admin/users/{user_id}/password")
def admin_reset_user_password(user_id:int, body:AdminPasswordResetIn, _admin=Depends(admin_user), conn=Depends(get_db)):
    row=conn.execute("SELECT username FROM users WHERE id=?",(user_id,)).fetchone()
    if not row:
        raise HTTPException(404,"Benutzer nicht gefunden")
    try:
        core.validate_password(body.new_password)
    except ValueError as exc:
        raise HTTPException(400,str(exc)) from exc
    conn.execute("UPDATE users SET password_hash=?,password_changed_at=? WHERE id=?",
                 (core.hash_password(body.new_password),core.now(),user_id))
    if user_id==_admin["id"]:
        hashes=[r[0] for r in conn.execute("SELECT token_hash FROM sessions WHERE user_id=? AND token_hash<>?",
                                            (user_id,_admin["token_hash"]))]
        conn.execute("DELETE FROM sessions WHERE user_id=? AND token_hash<>?",(user_id,_admin["token_hash"]))
    else:
        hashes=[r[0] for r in conn.execute("SELECT token_hash FROM sessions WHERE user_id=?",(user_id,))]
        conn.execute("DELETE FROM sessions WHERE user_id=?",(user_id,))
    magic_links=conn.execute("DELETE FROM magic_links WHERE user_id=?",(user_id,)).rowcount
    conn.commit()
    hub.close_sessions(hashes)
    return {"username":row["username"],"revoked_sessions":len(hashes),"revoked_magic_links":magic_links}


@app.patch("/api/admin/users/{user_id}")
def admin_set_limit(user_id:int, body:AdminLimitIn, _admin=Depends(admin_user), conn=Depends(get_db)):
    if not conn.execute("SELECT 1 FROM users WHERE id=?",(user_id,)).fetchone():
        raise HTTPException(404,"Benutzer nicht gefunden")
    conn.execute("UPDATE users SET storage_limit=? WHERE id=?",(body.storage_limit,user_id))
    conn.commit()
    return {"storage_limit":body.storage_limit}


@app.get("/api/admin/users/{user_id}/sessions")
def admin_sessions(user_id:int, _admin=Depends(admin_user), conn=Depends(get_db)):
    if not conn.execute("SELECT 1 FROM users WHERE id=?",(user_id,)).fetchone():
        raise HTTPException(404,"Benutzer nicht gefunden")
    return [dict(id=r["token_hash"],created_at=r["created_at"],expires_at=r["expires_at"],
                 last_seen=r["last_seen"],user_agent=r["user_agent"]) for r in conn.execute(
        "SELECT token_hash,created_at,expires_at,last_seen,user_agent FROM sessions WHERE user_id=? ORDER BY last_seen DESC",
        (user_id,))]


@app.delete("/api/admin/users/{user_id}/sessions",status_code=204)
def admin_revoke_sessions(user_id:int, _admin=Depends(admin_user), conn=Depends(get_db)):
    hashes=[r[0] for r in conn.execute("SELECT token_hash FROM sessions WHERE user_id=?",(user_id,))]
    conn.execute("DELETE FROM sessions WHERE user_id=?",(user_id,)); conn.commit(); hub.close_sessions(hashes)
    return Response(status_code=204)


@app.get("/admin", include_in_schema=False)
@app.get("/notes/admin", include_in_schema=False)
def admin_page():
    return FileResponse(STATIC_DIR / "admin.html")


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
        except HTTPException as exc:
            return [{"t":"error","id":note_id,"message":str(exc.detail)}], []
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
                db.execute("UPDATE sessions SET last_seen=? WHERE token_hash=?",(core.now(),conn.token_hash))
                db.commit()
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
