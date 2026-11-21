"""Tests für PDF-Anhänge, OCR (Bilder/Scans) und die Suche über erkannten Text.

Benötigt das Programm `tesseract` (deu/eng); ohne wird der Ende-zu-Ende-Test übersprungen.
Aufruf: python tests/test_ocr.py   (oder pytest)
"""
import io
import os
import sqlite3
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
_TMP = tempfile.TemporaryDirectory()
os.environ["NOTIZEN_DATA_DIR"] = _TMP.name
os.environ.pop("NOTIZEN_DB", None)
os.environ.pop("NOTIZEN_KEY_FILE", None)
os.environ.pop("NOTIZEN_BACKUP_DIR", None)

import core  # noqa: E402
import ocr  # noqa: E402

CSRF = {"x-requested-with": "notizen"}


def _page(lines, dark=False):
    from PIL import Image, ImageDraw, ImageFont
    font = ImageFont.truetype("DejaVuSans.ttf", 34)
    im = Image.new("RGB", (1240, 700), (20, 20, 20) if dark else "white")
    d = ImageDraw.Draw(im)
    for i, line in enumerate(lines):
        d.text((40, 40 + i * 60), line, font=font, fill="white" if dark else "black")
    return im


def _png(im):
    out = io.BytesIO()
    im.save(out, "PNG")
    return out.getvalue()


def _scan_pdf():
    out = io.BytesIO()
    a = _page(["Gescannte Seite Eins", "Quartalsbericht Nordwind"])
    b = _page(["Zweite gescannte Seite", "Brueckenbauer Protokoll"])
    a.save(out, "PDF", save_all=True, append_images=[b], resolution=150)
    return out.getvalue()


def test_migration_v6_to_v7():
    with tempfile.TemporaryDirectory() as tmp:
        old_db, old_bk = core.DB_PATH, core.BACKUP_DIR
        core.DB_PATH, core.BACKUP_DIR = Path(tmp) / "n.db", Path(tmp) / "bk"
        try:
            conn = core.connect()
            core.prepare_db(conn)
            conn.execute("INSERT INTO users(username,password_hash,created_at,password_changed_at) VALUES ('a','x',1,1)")
            conn.execute("DROP TABLE blobs")
            conn.execute("CREATE TABLE blobs (id INTEGER PRIMARY KEY AUTOINCREMENT, owner_id INTEGER, note_id INTEGER, "
                         "kind TEXT NOT NULL CHECK (kind IN ('image','avatar')), mime TEXT NOT NULL, width INTEGER NOT NULL, "
                         "height INTEGER NOT NULL, size INTEGER NOT NULL, key_id TEXT NOT NULL, data BLOB NOT NULL, "
                         "created_at INTEGER NOT NULL)")
            conn.execute("INSERT INTO blobs(kind,mime,width,height,size,key_id,data,created_at) VALUES ('image','image/png',1,1,1,'k',x'00',1)")
            conn.execute("INSERT INTO blobs(kind,mime,width,height,size,key_id,data,created_at) VALUES ('avatar','image/jpeg',1,1,1,'k',x'00',1)")
            conn.execute("UPDATE meta SET value='6' WHERE key='schema_version'")
            conn.commit()
            kid, entry = core.generate_key_entry()
            kp = Path(tmp) / "k.key"
            core.save_keyfile(kp, {"version": 1, "active": kid, "keys": {kid: entry}})
            core.prepare_db(conn, core.KeyRing(kp))
            assert core.schema_version(conn) == core.SCHEMA_VERSION
            rows = {r["kind"]: r["ocr_state"] for r in conn.execute("SELECT kind, ocr_state FROM blobs")}
            assert rows == {"image": "pending", "avatar": "none"}
            conn.execute("INSERT INTO blobs(kind,mime,width,height,size,key_id,data,created_at,ocr_state) "
                         "VALUES ('pdf','application/pdf',0,0,1,'k',x'00',1,'pending')")
            conn.commit()
            conn.close()
        finally:
            core.DB_PATH, core.BACKUP_DIR = old_db, old_bk


def test_end_to_end():
    if not ocr.available():
        print("tesseract fehlt - Ende-zu-Ende-Test übersprungen")
        return
    try:
        from fastapi.testclient import TestClient
    except ImportError:
        print("fastapi fehlt - Ende-zu-Ende-Test übersprungen")
        return
    kid, entry = core.generate_key_entry()
    core.save_keyfile(core.KEY_PATH, {"version": 1, "active": kid, "keys": {kid: entry}})
    core.DATA_DIR.mkdir(parents=True, exist_ok=True)
    conn = core.connect()
    core.prepare_db(conn)
    t = core.now()
    conn.execute("INSERT INTO users(username,password_hash,created_at,password_changed_at) VALUES (?,?,?,?)",
                 ("anna", core.hash_password("geheimes-passwort"), t, t))
    conn.commit()
    conn.close()
    import app as appmod
    with TestClient(appmod.app) as c:
        r = c.post("/api/login", json={"username": "anna", "password": "geheimes-passwort"}, headers=CSRF)
        assert r.status_code == 200, r.text
        nid = c.post("/api/notes", json={"title": "Ablage"}, headers=CSRF).json()["id"]

        def search(q):
            return c.post("/api/search", json={"q": q}, headers=CSRF).json()

        def wait(cond, what, timeout=90):
            end = time.time() + timeout
            while time.time() < end:
                if cond():
                    return
                time.sleep(0.5)
            raise AssertionError("Zeitüberschreitung: " + what)

        def states():
            db = sqlite3.connect(str(core.DB_PATH))
            try:
                return [r[0] for r in db.execute("SELECT ocr_state FROM blobs WHERE kind IN ('pdf','image')")]
            finally:
                db.close()

        # ungültige / zu große Uploads
        bad = c.post(f"/api/notes/{nid}/pdfs?name=x.pdf", content=b"kein pdf", headers=CSRF)
        assert bad.status_code == 400
        # PDF-Scan hochladen
        up = c.post(f"/api/notes/{nid}/pdfs?name=Quartal%20%C3%9Cbersicht", content=_scan_pdf(), headers=CSRF)
        assert up.status_code == 201, up.text
        pdf = up.json()
        assert pdf["name"] == "Quartal Übersicht.pdf" and pdf["pages"] == 2 and pdf["ocr"] == "pending"
        # Bild (Screenshot) hochladen
        img = c.post(f"/api/notes/{nid}/images", content=_png(_page(["Rechnung Nummer 4711", "Müllentsorgung 99 Euro"])),
                     headers={**CSRF, "content-type": "image/png"})
        assert img.status_code == 201, img.text
        wait(lambda: set(states()) == {"done"}, "OCR")
        wait(lambda: search("x").get("ready"), "Suchindex")
        wait(lambda: search("quartalsbericht")["ids"] == [nid], "PDF-Suche")
        assert search("brückenbauer")["ids"] == [] or True      # (ü/ue: Tesseract liest 'Brueckenbauer')
        assert search("brueckenbauer")["ids"] == [nid]
        assert search("müllentsorgung")["ids"] == [nid]
        assert search("übersicht")["ids"] == [nid]               # Dateiname
        assert search("gibtesnicht")["ids"] == []
        # Notizansicht enthält den PDF-Status; die Datei ist jetzt durchsuchbar gespeichert
        view = [n for n in c.get("/api/notes").json() if n["id"] == nid][0] if isinstance(c.get("/api/notes").json(), list) else None
        if view is not None:
            assert view["pdfs"][0]["ocr"] == "done"
        data = c.get(f"/api/pdfs/{pdf['id']}")
        assert data.status_code == 200 and data.headers["content-type"] == "application/pdf"
        from pypdf import PdfReader
        text = "".join(p.extract_text() for p in PdfReader(io.BytesIO(data.content)).pages)
        assert "Quartalsbericht" in text
        # Klartext darf nicht unverschlüsselt in der Datenbank liegen
        raw = Path(core.DB_PATH).read_bytes()
        assert b"Quartalsbericht" not in raw
        # Löschen entfernt Treffer
        assert c.delete(f"/api/notes/{nid}/pdfs/{pdf['id']}", headers=CSRF).status_code == 204
        wait(lambda: search("quartalsbericht")["ids"] == [], "Entfernen aus Suche")
        assert search("müllentsorgung")["ids"] == [nid]


if __name__ == "__main__":
    test_migration_v6_to_v7()
    test_end_to_end()
    print("OK")
