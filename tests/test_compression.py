"""Compression-envelope round-trip and encryption compatibility."""
import tempfile
import sys
import sqlite3
import re
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parent.parent))
import core


def test_payload_compression_roundtrip():
    text='{"title":"Notes","body":"' + ('repetitive note text ' * 1000) + '"}'
    wrapped=core.compress_payload_text(text)
    assert wrapped is not None
    assert core.decompress_payload_text(wrapped)==text
    assert core.compress_payload_text('{"title":"x"}') is None
    assert core.decompress_payload_text('{"title":"legacy"}')=='{"title":"legacy"}'


def test_compressed_text_survives_encryption():
    with tempfile.TemporaryDirectory() as tmp:
        kid,entry=core.generate_key_entry()
        path=Path(tmp)/'master.key'
        core.save_keyfile(path,{"keys":{kid:entry},"active":kid})
        ring=core.KeyRing(path)
        text='archived body '*500
        wrapped=core.compress_payload_text(text)
        assert wrapped is not None
        assert core.decompress_payload_text(ring.decrypt(ring.encrypt(wrapped)))==text


def test_schema_v4_creates_search_index_tables():
    conn=sqlite3.connect(':memory:')
    try:
        conn.row_factory=sqlite3.Row
        legacy=re.sub(r"-- Suchindex: Status und eigener, verschlüsselter Bereich je Benutzer.*?CREATE TABLE IF NOT EXISTS search_dirty \(.*?\);", "", core.SCHEMA, flags=re.S)
        assert 'CREATE TABLE IF NOT EXISTS search_entries' not in legacy
        conn.executescript(legacy)
        conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES ('schema_version','4')")
        conn.commit()
        core._migrate_4_to_5(conn)
        assert core.schema_version(conn)==5
        for table in ('search_index','search_entries','search_terms','search_dirty'):
            assert conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",(table,)).fetchone()
    finally:
        conn.close()


def test_schema_v2_migrates_through_v5():
    conn=sqlite3.connect(':memory:')
    try:
        conn.row_factory=sqlite3.Row
        old_schema=core.SCHEMA.replace('    payload_compressed INTEGER NOT NULL DEFAULT 0,\n','')
        assert old_schema != core.SCHEMA
        conn.executescript(old_schema)
        conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES ('schema_version','2')")
        conn.commit()
        core._migrate_2_to_3(conn)
        assert core.schema_version(conn)==3
        assert 'payload_compressed' not in {r[1] for r in conn.execute('PRAGMA table_info(notes)')}
        core._migrate_3_to_4(conn)
        assert core.schema_version(conn)==4
        assert 'payload_compressed' in {r[1] for r in conn.execute('PRAGMA table_info(notes)')}
        core._migrate_4_to_5(conn)
        assert core.schema_version(conn)==5
        assert conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='search_entries'").fetchone()
    finally:
        conn.close()


if __name__=='__main__':
    test_payload_compression_roundtrip()
    test_compressed_text_survives_encryption()
    test_schema_v2_migrates_through_v5()
    test_schema_v4_creates_search_index_tables()
    print('Compression and schema migration tests OK')
