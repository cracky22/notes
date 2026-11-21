"""Tests: Miniaturen, PDF-Vorschau (Seiten als Bilder) und geteilte Labels.

Aufruf: python tests/test_features.py   (oder pytest tests/test_features.py)
"""
import io
import os
import sqlite3
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
_TMP = tempfile.TemporaryDirectory()
os.environ["NOTIZEN_DATA_DIR"] = _TMP.name
os.environ.pop("NOTIZEN_DB", None)
os.environ.pop("NOTIZEN_KEY_FILE", None)
os.environ.pop("NOTIZEN_BACKUP_DIR", None)

import core  # noqa: E402

CSRF = {"x-requested-with": "notizen"}
PASSWORD = "geheimes-passwort"


def _photo(w, h):
    from PIL import Image
    im = Image.new("RGB", (w, h))
    px = im.load()
    for x in range(0, w, 7):
        for y in range(0, h, 7):
            px[x, y] = ((x * 255) // w, (y * 255) // h, 128)
    out = io.BytesIO()
    im.save(out, "PNG")
    return out.getvalue()


def _pdf(pages=3):
    from PIL import Image, ImageDraw
    imgs = []
    for i in range(pages):
        im = Image.new("RGB", (600, 800), "white")
        ImageDraw.Draw(im).rectangle((50, 50, 550, 150), fill=(30 + i * 60, 80, 160))
        imgs.append(im)
    out = io.BytesIO()
    imgs[0].save(out, "PDF", save_all=True, append_images=imgs[1:], resolution=72)
    return out.getvalue()


def _setup():
    from fastapi.testclient import TestClient
    kid, entry = core.generate_key_entry()
    core.save_keyfile(core.KEY_PATH, {"version": 1, "active": kid, "keys": {kid: entry}})
    core.DATA_DIR.mkdir(parents=True, exist_ok=True)
    conn = core.connect()
    core.prepare_db(conn)
    t = core.now()
    for name in ("anna", "ben", "cleo"):
        conn.execute("INSERT INTO users(username,password_hash,created_at,password_changed_at) VALUES (?,?,?,?)",
                     (name, core.hash_password(PASSWORD), t, t))
    conn.commit()
    ids = {r["username"]: r["id"] for r in conn.execute("SELECT id, username FROM users")}
    conn.close()
    import app as appmod
    return TestClient(appmod.app), ids


def _login(c, name):
    c.cookies.clear()
    r = c.post("/api/login", json={"username": name, "password": PASSWORD}, headers=CSRF)
    assert r.status_code == 200, r.text


def _note(c, **body):
    r = c.post("/api/notes", json=body, headers=CSRF)
    assert r.status_code == 201, r.text
    return r.json()["id"]


def _view(c, nid):
    r = c.get(f"/api/notes/{nid}")
    return r.json() if r.status_code == 200 else None


def test_all():
    from PIL import Image
    c, uid = _setup()
    with c:
        _login(c, "anna")
        nid = _note(c, title="Dateien")

        # ---- Miniaturen -------------------------------------------------
        big = c.post(f"/api/notes/{nid}/images", content=_photo(1600, 1000), headers={**CSRF, "content-type": "image/png"})
        assert big.status_code == 201, big.text
        small = c.post(f"/api/notes/{nid}/images", content=_photo(200, 100), headers={**CSRF, "content-type": "image/png"})
        big_id, small_id = big.json()["id"], small.json()["id"]
        th = c.get(f"/api/images/{big_id}/thumb")
        assert th.status_code == 200 and th.headers["content-type"] == "image/jpeg"
        assert "immutable" in th.headers["cache-control"]
        tw, tl = Image.open(io.BytesIO(th.content)).size
        assert max(tw, tl) == core.THUMB_SIDE and abs(tw / tl - 1.6) < 0.02
        full = c.get(f"/api/images/{big_id}")
        assert len(th.content) < len(full.content) / 2
        assert Image.open(io.BytesIO(full.content)).size == (1600, 1000)
        # Miniatur wurde schon beim Upload abgelegt; fehlt sie (Altbestand), wird sie nachträglich erzeugt
        db = sqlite3.connect(str(core.DB_PATH))
        assert db.execute("SELECT COUNT(*) FROM blob_thumbs WHERE blob_id=?", (big_id,)).fetchone()[0] == 1
        db.execute("DELETE FROM blob_thumbs"); db.commit()
        again = c.get(f"/api/images/{big_id}/thumb")
        assert again.status_code == 200 and Image.open(io.BytesIO(again.content)).size == (tw, tl)
        assert db.execute("SELECT COUNT(*) FROM blob_thumbs WHERE blob_id=?", (big_id,)).fetchone()[0] == 1
        # kleine Bilder werden unverändert ausgeliefert
        assert Image.open(io.BytesIO(c.get(f"/api/images/{small_id}/thumb").content)).size == (200, 100)
        # Miniatur ist nicht im Klartext in der Datenbank
        assert b"\xff\xd8\xff" not in b"".join(r[0] for r in db.execute("SELECT data FROM blob_thumbs"))
        db.close()
        _login(c, "cleo")
        assert c.get(f"/api/images/{big_id}/thumb").status_code == 404     # kein Zugriff

        # ---- PDF-Vorschau -----------------------------------------------
        _login(c, "anna")
        up = c.post(f"/api/notes/{nid}/pdfs?name=Skript", content=_pdf(3), headers=CSRF)
        assert up.status_code == 201, up.text
        pid = up.json()["id"]
        info = c.get(f"/api/pdfs/{pid}/info").json()
        assert info["pages"] == 3 and info["sizes"][0] == [600.0, 800.0]
        page = c.get(f"/api/pdfs/{pid}/pages/1?w=600")
        assert page.status_code == 200 and page.headers["content-type"] == "image/jpeg"
        assert Image.open(io.BytesIO(page.content)).size == (600, 800)
        assert c.get(f"/api/pdfs/{pid}/pages/3?w=600").status_code == 404
        assert c.get(f"/api/pdfs/{pid}/pages/-1").status_code == 404
        wide = Image.open(io.BytesIO(c.get(f"/api/pdfs/{pid}/pages/0?w=99999").content)).size
        assert wide[0] == 2000                                                # Obergrenze
        pt = c.get(f"/api/pdfs/{pid}/thumb")
        assert pt.status_code == 200 and Image.open(io.BytesIO(pt.content)).size[0] == core.PDF_THUMB_WIDTH
        _login(c, "cleo")
        for path in ("info", "thumb", "pages/0"):
            assert c.get(f"/api/pdfs/{pid}/{path}").status_code == 404

        # ---- geteilte Labels --------------------------------------------
        _login(c, "anna")
        lab = c.post("/api/labels", json={"name": "Projekt"}, headers=CSRF).json()
        assert lab["role"] == "owner" and lab["members"] == [] and lab["shared"] is False
        shared_note = _note(c, title="Gemeinsam", labels=[lab["id"]])
        private_note = _note(c, title="Privat", labels=[lab["id"]])
        assert c.put(f"/api/notes/{shared_note}/collaborators", json={"user_ids": [uid["ben"]]}, headers=CSRF).status_code == 200

        _login(c, "ben")
        assert c.get("/api/labels").json() == []                                    # noch nichts geteilt
        assert _view(c, shared_note)["labels"] == []
        own = c.post("/api/labels", json={"name": "Projekt"}, headers=CSRF).json()  # Namenskonflikt vorbereiten
        _login(c, "anna")
        clash = c.put(f"/api/labels/{lab['id']}/members", json={"members": [{"id": uid["ben"]}]}, headers=CSRF)
        assert clash.status_code == 409 and "ben" in clash.json()["detail"]
        _login(c, "ben")
        assert c.patch(f"/api/labels/{own['id']}", json={"name": "Ben privat"}, headers=CSRF).status_code == 200
        _login(c, "anna")
        assert c.put(f"/api/labels/{lab['id']}/members", json={"members": [{"id": 99999}]}, headers=CSRF).status_code == 400
        r = c.put(f"/api/labels/{lab['id']}/members", json={"members": [{"id": uid["ben"], "can_edit": True}]}, headers=CSRF)
        assert r.status_code == 200 and [m["id"] for m in r.json()["members"]] == [uid["ben"]] and r.json()["shared"]

        _login(c, "ben")
        mine = {l["name"]: l for l in c.get("/api/labels").json()}
        assert mine["Projekt"]["role"] == "member" and mine["Projekt"]["owner"]["id"] == uid["anna"]
        assert _view(c, shared_note)["labels"] == [lab["id"]]                       # sichtbar an geteilter Notiz
        assert _view(c, private_note) is None                                       # Teilen gibt keinen Notizzugriff
        assert c.get("/api/notes").json() is not None
        assert c.patch(f"/api/labels/{lab['id']}", json={"name": "X"}, headers=CSRF).status_code == 403
        assert c.delete(f"/api/labels/{lab['id']}", headers=CSRF).status_code == 403
        assert c.put(f"/api/labels/{lab['id']}/members", json={"members": []}, headers=CSRF).status_code == 403
        # Mitglied darf das Label an Notizen setzen und entfernen – das gilt für alle Teilnehmer
        assert c.patch(f"/api/notes/{shared_note}", json={"labels": [mine["Ben privat"]["id"]]}, headers=CSRF).status_code == 200
        _login(c, "anna")
        assert _view(c, shared_note)["labels"] == []
        assert c.patch(f"/api/notes/{shared_note}", json={"labels": [lab["id"]]}, headers=CSRF).status_code == 200
        # Leserecht: sichtbar, aber nicht vergeben/entfernen
        assert c.put(f"/api/labels/{lab['id']}/members", json={"members": [{"id": uid["ben"], "can_edit": False}]}, headers=CSRF).json()["members"][0]["can_edit"] is False
        _login(c, "ben")
        assert {l["name"]: l for l in c.get("/api/labels").json()}["Projekt"]["can_edit"] is False
        assert _view(c, shared_note)["labels"] == [lab["id"], mine["Ben privat"]["id"]] or lab["id"] in _view(c, shared_note)["labels"]
        assert c.patch(f"/api/notes/{shared_note}", json={"labels": []}, headers=CSRF).status_code == 200
        assert lab["id"] in _view(c, shared_note)["labels"]                          # nicht entfernt
        extra = _note(c, title="Ben")
        assert c.patch(f"/api/notes/{extra}", json={"labels": [lab["id"]]}, headers=CSRF).status_code == 403
        _login(c, "anna")
        c.put(f"/api/labels/{lab['id']}/members", json={"members": [{"id": uid["ben"], "can_edit": True}]}, headers=CSRF)
        _login(c, "cleo")
        assert c.patch(f"/api/notes/{shared_note}", json={"labels": [lab["id"]]}, headers=CSRF).status_code == 404
        assert c.post(f"/api/labels/{lab['id']}/leave", headers=CSRF).status_code == 404
        assert c.patch(f"/api/labels/{lab['id']}", json={"name": "X"}, headers=CSRF).status_code == 404

        # Mitglied entfernt -> Label verschwindet bei ihm
        _login(c, "anna")
        assert c.put(f"/api/labels/{lab['id']}/members", json={"members": []}, headers=CSRF).json()["members"] == []
        _login(c, "ben")
        assert [l["name"] for l in c.get("/api/labels").json()] == ["Ben privat"]
        assert _view(c, shared_note)["labels"] == []                                # das geteilte Label ist weg
        # erneut teilen, Mitglied verlässt das Label selbst
        _login(c, "anna")
        c.put(f"/api/labels/{lab['id']}/members", json={"members": [{"id": uid["ben"], "can_edit": True}]}, headers=CSRF)
        _login(c, "ben")
        assert c.post(f"/api/labels/{lab['id']}/leave", headers=CSRF).status_code == 204
        assert [l["name"] for l in c.get("/api/labels").json()] == ["Ben privat"]
        # Inhaber löscht das Label: auch bei Mitgliedern weg
        _login(c, "anna")
        c.put(f"/api/labels/{lab['id']}/members", json={"members": [{"id": uid["ben"], "can_edit": True}]}, headers=CSRF)
        assert c.delete(f"/api/labels/{lab['id']}", headers=CSRF).status_code == 204
        _login(c, "ben")
        assert [l["name"] for l in c.get("/api/labels").json()] == ["Ben privat"]
        _login(c, "anna")
        assert c.get("/api/labels").json() == []
        # Verlässt der Inhaber die gemeinsame Notiz nicht, bleibt alles konsistent: Rotation kennt die neue Tabelle
        conn = core.connect()
        assert core.key_ids_in_db(conn)
        conn.close()


if __name__ == "__main__":
    test_all()
    print("OK")
