"""Tests für isolierte, verschlüsselte Benutzerindizes."""
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import core
import search_index as sidx


def test_separate_user_index_build_search_reset():
    with tempfile.TemporaryDirectory() as tmp:
        old_db, old_backup = core.DB_PATH, core.BACKUP_DIR
        root = Path(tmp)
        core.DB_PATH, core.BACKUP_DIR = root / 'notes.db', root / 'backups'
        kid, entry = core.generate_key_entry()
        key_path = root / 'master.key'
        core.save_keyfile(key_path, {'keys': {kid: entry}, 'active': kid})
        ring = core.KeyRing(key_path)
        try:
            conn = core.connect()
            conn.executescript(core.SCHEMA)
            conn.execute("INSERT INTO users(username,password_hash,created_at,password_changed_at) VALUES ('anna','x',1,1)")
            conn.execute("INSERT INTO users(username,password_hash,created_at,password_changed_at) VALUES ('ben','x',1,1)")
            now = core.now()
            doc = {'title': 'Klinik Projekt', 'body': [{'insert': 'München Patientenplanung\n'}],
                   'items': [{'id': '1', 'pos': 1, 'checked': False, 'text': 'Operationssaal'}], 'is_list': False}
            payload = ring.encrypt(json.dumps(doc, ensure_ascii=False, separators=(',', ':')))
            conn.execute("INSERT INTO notes(owner_id,payload,created_at,updated_at) VALUES (1,?, ?, ?)",
                         (payload, now, now))
            conn.execute("INSERT INTO note_users(note_id,user_id,role,added_at) VALUES (1,1,'owner',?)", (now,))
            conn.execute("INSERT INTO note_users(note_id,user_id,role,added_at) VALUES (1,2,'editor',?)", (now,))
            sidx.ensure_rows(conn, ring)
            conn.commit()
            conn.close()

            assert sidx.build_user(ring, 1) == 'ready'
            assert sidx.build_user(ring, 2) == 'ready'
            conn = core.connect()
            try:
                assert sidx.search(conn, ring, 1, 'münchen patienten') == [1]
                assert sidx.search(conn, ring, 1, 'entenplan') == [1]
                assert sidx.search(conn, ring, 2, 'operationssaal') == [1]
                new_kid, new_entry = core.generate_key_entry()
                key_data = core.load_keyfile(key_path)
                key_data['keys'][new_kid] = new_entry
                key_data['active'] = new_kid
                core.save_keyfile(key_path, key_data)
                core.reencrypt_all(conn, ring, new_kid)
                conn.commit()
                assert sidx.search(conn, ring, 1, 'münchen patienten') == [1]
                rows = conn.execute('SELECT user_id,payload FROM search_entries ORDER BY user_id').fetchall()
                assert len(rows) == 2 and rows[0]['user_id'] == 1 and rows[1]['user_id'] == 2
                assert all('München' not in r['payload'] and 'Operationssaal' not in r['payload'] for r in rows)
                hashes = {uid: {r[0] for r in conn.execute('SELECT token_hash FROM search_terms WHERE user_id=?', (uid,))}
                          for uid in (1, 2)}
                assert hashes[1] and hashes[2] and hashes[1].isdisjoint(hashes[2])
                sidx.reset_user(conn, 1, 'admin', ring)
                conn.commit()
                assert sidx.status(conn, 1)['entries'] == 0
                assert sidx.status(conn, 1)['state'] == 'pending'
                assert sidx.status(conn, 2)['entries'] == 1
                assert conn.execute('SELECT COUNT(*) FROM search_terms WHERE user_id=1').fetchone()[0] == 0
                assert conn.execute('SELECT COUNT(*) FROM search_terms WHERE user_id=2').fetchone()[0] > 0
            finally:
                conn.close()
        finally:
            core.DB_PATH, core.BACKUP_DIR = old_db, old_backup


if __name__ == '__main__':
    test_separate_user_index_build_search_reset()
    print('Suchindex-Tests OK')
