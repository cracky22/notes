#!/usr/bin/env python3
"""
admin.py – zentrales Verwaltungsprogramm für den Notizen-Server.

Beispiele:
    python admin.py init                       Datenverzeichnis, Schlüssel und Datenbank anlegen
    python admin.py user add anna              Benutzer anlegen (Passwort wird abgefragt)
    python admin.py user list                  Benutzer auflisten
    python admin.py user passwd anna           Passwort zurücksetzen
    python admin.py user delete anna           Benutzer samt Notizen löschen
    python admin.py user logout anna           alle Sitzungen eines Benutzers beenden
    python admin.py key status                 Schlüsselring und Nutzung anzeigen
    python admin.py key rotate                 neuen Master-Schlüssel erzeugen und alles umschlüsseln
    python admin.py key export schluessel.key  Schlüsselring exportieren
    python admin.py key import schluessel.key  Schlüssel (z. B. für alte Backups) einspielen
    python admin.py backup create              Backup erstellen (ohne Schlüssel)
    python admin.py backup list                Backups auflisten
    python admin.py backup restore DATEI.zip   Backup wiederherstellen

Pfade lassen sich über NOTIZEN_DATA_DIR, NOTIZEN_DB, NOTIZEN_KEY_FILE und
NOTIZEN_BACKUP_DIR anpassen (gleiche Variablen wie beim Server).
"""
from __future__ import annotations

import argparse
import getpass
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import zipfile
from datetime import datetime
from pathlib import Path

import core


# --------------------------------------------------------------------------- #
# Hilfsfunktionen
# --------------------------------------------------------------------------- #
def die(msg: str, code: int = 1) -> None:
    print(f"Fehler: {msg}", file=sys.stderr)
    sys.exit(code)


def info(msg: str = "") -> None:
    print(msg)


def confirm(question: str, assume_yes: bool = False) -> bool:
    if assume_yes:
        return True
    try:
        answer = input(f"{question} [j/N] ").strip().lower()
    except EOFError:
        return False
    return answer in ("j", "ja", "y", "yes")


def stamp() -> str:
    return datetime.now().strftime("%Y%m%d-%H%M%S")


def fmt_ts(ts: int | None) -> str:
    return datetime.fromtimestamp(ts).strftime("%d.%m.%Y %H:%M") if ts else "–"


def fmt_size(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n} B"


def open_db(must_exist: bool = True) -> sqlite3.Connection:
    if must_exist and not core.DB_PATH.exists():
        die(f"Datenbank {core.DB_PATH} existiert nicht. Zuerst 'python admin.py init' ausführen.")
    core.DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = core.connect()
    try:
        core.prepare_db(conn)
    except core.CryptoError as exc:
        die(f"Datenbank kann nicht aktualisiert werden: {exc}")
    return conn


def load_keys() -> dict:
    try:
        return core.load_keyfile(core.KEY_PATH)
    except core.CryptoError as exc:
        die(str(exc))
        raise  # für Typprüfer


def read_new_password(args) -> str:
    if getattr(args, "password_stdin", False):
        password = sys.stdin.readline().rstrip("\r\n")
    else:
        password = getpass.getpass("Neues Passwort: ")
        if password != getpass.getpass("Passwort wiederholen: "):
            die("Die Passwörter stimmen nicht überein.")
    try:
        core.validate_password(password)
    except ValueError as exc:
        die(str(exc))
    return password


def get_user(conn, username: str):
    row = conn.execute("SELECT id, username FROM users WHERE username = ?", (username,)).fetchone()
    if not row:
        die(f"Benutzer '{username}' existiert nicht.")
    return row


def archive_keyfile(tag: str) -> Path | None:
    """Legt eine Kopie der aktuellen Schlüsseldatei im Backup-Ordner ab."""
    if not core.KEY_PATH.exists():
        return None
    folder = core.BACKUP_DIR / "keys"
    folder.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(folder, 0o700)
    except OSError:
        pass
    dest = folder / f"master-{stamp()}-{tag}.key"
    core.write_private(dest, core.KEY_PATH.read_bytes())
    return dest


# --------------------------------------------------------------------------- #
# init
# --------------------------------------------------------------------------- #
def cmd_init(args) -> None:
    core.DATA_DIR.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(core.DATA_DIR, 0o700)
    except OSError:
        pass
    if core.KEY_PATH.exists():
        data = load_keys()
        info(f"Master-Schlüssel bereits vorhanden: {core.KEY_PATH} (aktiv: {data['active']})")
    else:
        kid, entry = core.generate_key_entry()
        core.save_keyfile(core.KEY_PATH, {"version": 1, "active": kid, "keys": {kid: entry}})
        info(f"Neuer AES-256-Master-Schlüssel erzeugt: {core.KEY_PATH} (ID {kid})")
        info("WICHTIG: Diese Datei getrennt von der Datenbank sichern – ohne sie sind alle Notizen unlesbar!")
    open_db(must_exist=False).close()
    info(f"Datenbank bereit: {core.DB_PATH} (Schema v{core.SCHEMA_VERSION})")
    info("Nächster Schritt: python admin.py user add <benutzername>")


# --------------------------------------------------------------------------- #
# user
# --------------------------------------------------------------------------- #
def cmd_user_add(args) -> None:
    conn = open_db()
    try:
        name = core.validate_username(args.username)
    except ValueError as exc:
        die(str(exc))
    if conn.execute("SELECT 1 FROM users WHERE username = ?", (name,)).fetchone():
        die(f"Benutzer '{name}' existiert bereits.")
    password = read_new_password(args)
    t = core.now()
    conn.execute("INSERT INTO users(username, password_hash, created_at, password_changed_at) "
                 "VALUES (?,?,?,?)", (name, core.hash_password(password), t, t))
    conn.commit()
    info(f"Benutzer '{name}' angelegt.")


def cmd_user_list(args) -> None:
    conn = open_db()
    rows = conn.execute(
        "SELECT u.id, u.username, u.created_at, u.password_changed_at, "
        "(SELECT COUNT(*) FROM notes n WHERE n.owner_id = u.id) AS notes, "
        "(SELECT COUNT(*) FROM note_users nu WHERE nu.user_id = u.id AND nu.role = 'editor') AS shared, "
        "(u.avatar_id IS NOT NULL) AS avatar, "
        "(SELECT COUNT(*) FROM sessions s WHERE s.user_id = u.id AND s.expires_at > ?) AS sessions "
        "FROM users u ORDER BY u.username COLLATE NOCASE", (core.now(),)).fetchall()
    if not rows:
        info("Keine Benutzer vorhanden.")
        return
    info(f"{'ID':>4}  {'Benutzer':<24} {'Notizen':>7} {'Geteilt':>7} {'Sitzungen':>9} {'Bild':>4}  "
         f"{'Angelegt':<16}  Passwort geändert")
    for r in rows:
        info(f"{r['id']:>4}  {r['username']:<24} {r['notes']:>7} {r['shared']:>7} {r['sessions']:>9} "
             f"{'ja' if r['avatar'] else '–':>4}  {fmt_ts(r['created_at']):<16}  {fmt_ts(r['password_changed_at'])}")


def cmd_user_passwd(args) -> None:
    conn = open_db()
    user = get_user(conn, args.username)
    password = read_new_password(args)
    conn.execute("UPDATE users SET password_hash = ?, password_changed_at = ? WHERE id = ?",
                 (core.hash_password(password), core.now(), user["id"]))
    conn.execute("DELETE FROM sessions WHERE user_id = ?", (user["id"],))
    conn.commit()
    info(f"Passwort für '{user['username']}' zurückgesetzt, alle Sitzungen wurden beendet.")


def cmd_user_delete(args) -> None:
    conn = open_db()
    user = get_user(conn, args.username)
    n = conn.execute("SELECT COUNT(*) FROM notes WHERE owner_id = ?", (user["id"],)).fetchone()[0]
    if not confirm(f"Benutzer '{user['username']}' mit {n} eigenen Notizen endgültig löschen? "
                   "(Auch für Mitbearbeiter geteilte Notizen dieses Benutzers werden gelöscht.)", args.yes):
        info("Abgebrochen.")
        return
    conn.execute("DELETE FROM blobs WHERE kind = 'avatar' AND owner_id = ?", (user["id"],))
    conn.execute("DELETE FROM users WHERE id = ?", (user["id"],))
    conn.commit()
    conn.execute("VACUUM")
    info(f"Benutzer '{user['username']}' gelöscht.")


def cmd_user_logout(args) -> None:
    conn = open_db()
    if args.all:
        n = conn.execute("DELETE FROM sessions").rowcount
        info(f"{n} Sitzung(en) aller Benutzer beendet.")
    else:
        if not args.username:
            die("Benutzername oder --all angeben.")
        user = get_user(conn, args.username)
        n = conn.execute("DELETE FROM sessions WHERE user_id = ?", (user["id"],)).rowcount
        info(f"{n} Sitzung(en) von '{user['username']}' beendet.")
    conn.commit()


# --------------------------------------------------------------------------- #
# key
# --------------------------------------------------------------------------- #
def cmd_key_status(args) -> None:
    data = load_keys()
    counts = core.key_ids_in_db(open_db()) if core.DB_PATH.exists() else {}
    info(f"Schlüsseldatei: {core.KEY_PATH}")
    info("Verfahren:      AES-256-GCM (256-Bit-Schlüssel, 96-Bit-Nonce, authentifiziert)")
    info()
    info(f"  {'ID':<10} {'Fingerabdruck':<18} {'Erzeugt':<17} {'Datensätze':>10}")
    for kid, entry in data["keys"].items():
        mark = "*" if kid == data["active"] else " "
        info(f"{mark} {kid:<10} {core.key_fingerprint(entry['key']):<18} "
             f"{fmt_ts(entry.get('created')):<17} {counts.get(kid, 0):>10}")
    info("\n* = aktiver Schlüssel (wird für neue Daten verwendet)")
    missing = set(counts) - set(data["keys"])
    if missing:
        info(f"\nWARNUNG: Für Daten mit den IDs {', '.join(sorted(missing))} fehlt der Schlüssel!")


def cmd_key_rotate(args) -> None:
    data = load_keys()
    conn = open_db()
    archived = archive_keyfile("vor-rotation")
    new_kid, entry = core.generate_key_entry()
    data["keys"][new_kid] = entry
    old_active, data["active"] = data["active"], new_kid
    # 1) neuen Schlüssel aktiv setzen (alte bleiben zum Entschlüsseln erhalten)
    core.save_keyfile(core.KEY_PATH, data)
    ring = core.KeyRing(core.KEY_PATH)

    # 2) alle Datensätze in einer Transaktion umschlüsseln
    changed = 0
    try:
        conn.execute("BEGIN IMMEDIATE")
        changed = core.reencrypt_all(conn, ring, new_kid)
        conn.commit()
    except Exception as exc:  # noqa: BLE001
        conn.rollback()
        die(f"Umschlüsseln fehlgeschlagen, Daten unverändert ({exc}). "
            "Der neue Schlüssel bleibt zusätzlich im Schlüsselring.")
    info(f"Neuer aktiver Schlüssel: {new_kid} (vorher {old_active}), {changed} Datensätze umgeschlüsselt.")

    # 3) nicht mehr benötigte Schlüssel entfernen
    if not args.keep_old:
        still_used = set(core.key_ids_in_db(conn))
        data = load_keys()
        removed = [k for k in data["keys"] if k != new_kid and k not in still_used]
        for k in removed:
            del data["keys"][k]
        core.save_keyfile(core.KEY_PATH, data)
        if removed:
            info(f"Alte Schlüssel aus dem Schlüsselring entfernt: {', '.join(removed)}")
    if archived:
        info(f"Der bisherige Schlüsselring wurde archiviert: {archived}")
        info("Er wird benötigt, um ältere Backups wiederherzustellen ('key import').")


def cmd_key_export(args) -> None:
    load_keys()
    dest = Path(args.file)
    if dest.exists() and not args.force:
        die(f"{dest} existiert bereits (mit --force überschreiben).")
    core.write_private(dest, core.KEY_PATH.read_bytes())
    info(f"Schlüsselring exportiert nach {dest} (Dateirechte 600). Sicher aufbewahren!")


def cmd_key_import(args) -> None:
    src = Path(args.file)
    try:
        incoming = core.load_keyfile(src)
    except core.CryptoError as exc:
        die(str(exc))
    if not core.KEY_PATH.exists():
        core.save_keyfile(core.KEY_PATH, incoming)
        info(f"Schlüsselring aus {src} übernommen (aktiv: {incoming['active']}).")
        return
    current = load_keys()
    archived = archive_keyfile("vor-import")
    if args.replace:
        if not confirm("Den aktuellen Schlüsselring vollständig ersetzen?", args.yes):
            info("Abgebrochen.")
            return
        merged = incoming
    else:
        merged = {"version": 1, "active": current["active"],
                  "keys": {**incoming["keys"], **current["keys"]}}
        if args.activate:
            merged["active"] = incoming["active"]
    core.save_keyfile(core.KEY_PATH, merged)
    added = set(merged["keys"]) - set(current["keys"])
    info(f"Schlüssel importiert. Neu: {', '.join(sorted(added)) or 'keine'}; aktiv: {merged['active']}.")
    if archived:
        info(f"Vorheriger Schlüsselring archiviert: {archived}")


# --------------------------------------------------------------------------- #
# backup
# --------------------------------------------------------------------------- #
def create_backup(out: Path, include_key: bool) -> dict:
    conn = open_db()
    out.parent.mkdir(parents=True, exist_ok=True)
    tmpdir = Path(tempfile.mkdtemp(prefix="notizen-backup-"))
    try:
        snapshot = tmpdir / "notizen.db"
        target = sqlite3.connect(str(snapshot))
        conn.backup(target)  # konsistente Kopie, auch während der Server läuft
        target.close()
        manifest = {
            "format": "notizen-backup",
            "version": 1,
            "created": core.now(),
            "created_human": fmt_ts(core.now()),
            "users": conn.execute("SELECT COUNT(*) FROM users").fetchone()[0],
            "notes": conn.execute("SELECT COUNT(*) FROM notes").fetchone()[0],
            "key_ids": sorted(core.key_ids_in_db(conn)),
            "includes_key": include_key,
        }
        conn.close()
        part = out.with_name(out.name + ".part")
        fd = os.open(part, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "wb") as fh, zipfile.ZipFile(fh, "w", zipfile.ZIP_DEFLATED) as zf:
            zf.write(snapshot, "notizen.db")
            zf.writestr("manifest.json", json.dumps(manifest, indent=2, ensure_ascii=False))
            if include_key:
                zf.writestr("master.key", core.KEY_PATH.read_bytes())
        os.replace(part, out)
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)
    return manifest


def cmd_backup_create(args) -> None:
    if args.include_key:
        load_keys()
    default = args.output is None
    out = Path(args.output) if args.output else core.BACKUP_DIR / f"notizen-backup-{stamp()}.zip"
    manifest = create_backup(out, args.include_key)
    info(f"Backup erstellt: {out} ({fmt_size(out.stat().st_size)}, "
         f"{manifest['users']} Benutzer, {manifest['notes']} Notizen)")
    if args.include_key:
        info("ACHTUNG: Das Backup enthält den Master-Schlüssel – wie ein Klartext-Backup schützen!")
    else:
        ids = ", ".join(manifest["key_ids"]) or "–"
        info(f"Das Backup enthält KEINEN Schlüssel. Zur Wiederherstellung wird der Schlüssel "
             f"mit der ID {ids} benötigt ('key export' separat sichern).")
    if default and args.keep:
        backups = sorted(core.BACKUP_DIR.glob("notizen-backup-*.zip"))
        for old in backups[:-args.keep]:
            old.unlink()
            info(f"Altes Backup entfernt: {old.name}")


def cmd_backup_list(args) -> None:
    files = sorted(core.BACKUP_DIR.glob("*.zip"))
    if not files:
        info(f"Keine Backups in {core.BACKUP_DIR}")
        return
    info(f"Backups in {core.BACKUP_DIR}:")
    for f in files:
        try:
            with zipfile.ZipFile(f) as zf:
                m = json.loads(zf.read("manifest.json"))
            detail = (f"{m.get('users', '?')} Benutzer, {m.get('notes', '?')} Notizen, "
                      f"Schlüssel-IDs {','.join(m.get('key_ids', [])) or '–'}"
                      f"{', inkl. Schlüssel' if m.get('includes_key') else ''}")
        except Exception:  # noqa: BLE001
            detail = "kein gültiges Notizen-Backup"
        info(f"  {f.name:<44} {fmt_size(f.stat().st_size):>9}  {detail}")


def cmd_backup_restore(args) -> None:
    src = Path(args.file)
    if not src.is_file():
        die(f"Datei {src} nicht gefunden.")
    try:
        zf = zipfile.ZipFile(src)
    except zipfile.BadZipFile:
        die("Keine gültige Backup-Datei (ZIP erwartet).")
    with zf:
        names = set(zf.namelist())
        if not {"notizen.db", "manifest.json"} <= names:
            die("Das Backup ist unvollständig (notizen.db oder manifest.json fehlt).")
        manifest = json.loads(zf.read("manifest.json"))
        if manifest.get("format") != "notizen-backup":
            die("Unbekanntes Backup-Format.")
        backup_key = None
        if "master.key" in names:
            try:
                backup_key = json.loads(zf.read("master.key"))
                core.validate_keyfile(backup_key)
            except (ValueError, core.CryptoError) as exc:
                die(f"Der Schlüssel im Backup ist ungültig: {exc}")

        core.DB_PATH.parent.mkdir(parents=True, exist_ok=True)
        tmpdir = Path(tempfile.mkdtemp(prefix=".restore-", dir=core.DB_PATH.parent))
        try:
            tmp_db = tmpdir / "restore.db"
            with zf.open("notizen.db") as s, open(tmp_db, "wb") as d:
                shutil.copyfileobj(s, d)
            check = sqlite3.connect(str(tmp_db))
            try:
                result = check.execute("PRAGMA integrity_check").fetchone()[0]
                needed = set(core.key_ids_in_db(check))
                users = check.execute("SELECT COUNT(*) FROM users").fetchone()[0]
                notes = check.execute("SELECT COUNT(*) FROM notes").fetchone()[0]
            except sqlite3.DatabaseError as exc:
                die(f"Die Datenbank im Backup ist beschädigt: {exc}")
            finally:
                check.close()
            if result != "ok":
                die(f"Integritätsprüfung fehlgeschlagen: {result}")

            current = load_keys() if core.KEY_PATH.exists() else None
            available = set(current["keys"]) if current else set()
            if backup_key and args.with_key:
                available |= set(backup_key["keys"])
            missing = needed - available
            if missing:
                hint = (" Das Backup enthält einen Schlüssel – mit --with-key verwenden."
                        if backup_key and not args.with_key else
                        " Archivierte Schlüssel liegen unter backups/keys/ und lassen sich mit "
                        "'python admin.py key import <datei>' einspielen.")
                die(f"Für dieses Backup fehlen die Schlüssel {', '.join(sorted(missing))}.{hint}")

            info(f"Backup vom {manifest.get('created_human', '?')}: {users} Benutzer, {notes} Notizen.")
            if not confirm("Die aktuelle Datenbank wird ersetzt. Ist der Server gestoppt und soll "
                           "fortgefahren werden?", args.yes):
                info("Abgebrochen.")
                return

            if core.DB_PATH.exists():
                safety = core.BACKUP_DIR / f"notizen-vor-restore-{stamp()}.zip"
                create_backup(safety, include_key=core.KEY_PATH.exists())
                info(f"Sicherheitskopie des bisherigen Stands: {safety}")
            for suffix in ("-wal", "-shm"):
                Path(str(core.DB_PATH) + suffix).unlink(missing_ok=True)
            os.replace(tmp_db, core.DB_PATH)
            try:
                os.chmod(core.DB_PATH, 0o600)
            except OSError:
                pass

            if backup_key and args.with_key:
                archive_keyfile("vor-restore")
                merged = backup_key if current is None else {
                    "version": 1, "active": current["active"],
                    "keys": {**backup_key["keys"], **current["keys"]}}
                core.save_keyfile(core.KEY_PATH, merged)
                info("Schlüssel aus dem Backup in den Schlüsselring übernommen.")
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)
    info("Wiederherstellung abgeschlossen. Der Server kann jetzt (neu) gestartet werden.")


# --------------------------------------------------------------------------- #
# Kommandozeile
# --------------------------------------------------------------------------- #
def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="admin.py", description="Verwaltung für den Notizen-Server",
        formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__.split("Pfade")[0])
    sub = p.add_subparsers(dest="cmd", required=True, metavar="BEFEHL")

    sub.add_parser("init", help="Datenverzeichnis, Master-Schlüssel und Datenbank anlegen") \
        .set_defaults(func=cmd_init)

    # user
    pu = sub.add_parser("user", help="Benutzer verwalten")
    su = pu.add_subparsers(dest="sub", required=True, metavar="AKTION")
    a = su.add_parser("add", help="Benutzer anlegen")
    a.add_argument("username")
    a.add_argument("--password-stdin", action="store_true", help="Passwort aus stdin lesen (Skripte)")
    a.set_defaults(func=cmd_user_add)
    su.add_parser("list", help="Benutzer auflisten").set_defaults(func=cmd_user_list)
    a = su.add_parser("passwd", help="Passwort zurücksetzen")
    a.add_argument("username")
    a.add_argument("--password-stdin", action="store_true")
    a.set_defaults(func=cmd_user_passwd)
    a = su.add_parser("delete", help="Benutzer inkl. aller Notizen löschen")
    a.add_argument("username")
    a.add_argument("-y", "--yes", action="store_true", help="ohne Rückfrage")
    a.set_defaults(func=cmd_user_delete)
    a = su.add_parser("logout", help="Sitzungen beenden")
    a.add_argument("username", nargs="?")
    a.add_argument("--all", action="store_true", help="Sitzungen aller Benutzer beenden")
    a.set_defaults(func=cmd_user_logout)

    # key
    pk = sub.add_parser("key", help="Master-Verschlüsselungsschlüssel verwalten")
    sk = pk.add_subparsers(dest="sub", required=True, metavar="AKTION")
    sk.add_parser("status", help="Schlüsselring anzeigen").set_defaults(func=cmd_key_status)
    a = sk.add_parser("rotate", help="neuen Schlüssel erzeugen und alle Daten umschlüsseln")
    a.add_argument("--keep-old", action="store_true", help="alte Schlüssel im Schlüsselring behalten")
    a.set_defaults(func=cmd_key_rotate)
    a = sk.add_parser("export", help="Schlüsselring in Datei exportieren")
    a.add_argument("file")
    a.add_argument("--force", action="store_true")
    a.set_defaults(func=cmd_key_export)
    a = sk.add_parser("import", help="Schlüssel aus Datei einspielen (Standard: zusammenführen)")
    a.add_argument("file")
    a.add_argument("--replace", action="store_true", help="aktuellen Schlüsselring komplett ersetzen")
    a.add_argument("--activate", action="store_true", help="importierten aktiven Schlüssel aktiv setzen")
    a.add_argument("-y", "--yes", action="store_true")
    a.set_defaults(func=cmd_key_import)

    # backup
    pb = sub.add_parser("backup", help="Backups erstellen und wiederherstellen")
    sb = pb.add_subparsers(dest="sub", required=True, metavar="AKTION")
    a = sb.add_parser("create", help="Backup erstellen")
    a.add_argument("-o", "--output", help="Zieldatei (Standard: data/backups/notizen-backup-<zeit>.zip)")
    a.add_argument("--include-key", action="store_true", help="Master-Schlüssel mit ins Backup legen")
    a.add_argument("--keep", type=int, default=0, metavar="N",
                   help="nur die N neuesten automatischen Backups behalten")
    a.set_defaults(func=cmd_backup_create)
    sb.add_parser("list", help="Backups auflisten").set_defaults(func=cmd_backup_list)
    a = sb.add_parser("restore", help="Backup wiederherstellen (Server vorher stoppen!)")
    a.add_argument("file")
    a.add_argument("--with-key", action="store_true", help="im Backup enthaltenen Schlüssel übernehmen")
    a.add_argument("-y", "--yes", action="store_true", help="ohne Rückfrage")
    a.set_defaults(func=cmd_backup_restore)
    return p


def main() -> None:
    args = build_parser().parse_args()
    try:
        args.func(args)
    except core.CryptoError as exc:
        die(str(exc))
    except KeyboardInterrupt:
        die("Abgebrochen.", 130)


if __name__ == "__main__":
    main()
