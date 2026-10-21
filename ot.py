"""
ot.py – Operational Transformation für die gemeinsame Bearbeitung von Notizen.

Clients senden nur Befehle (Einfügen, Löschen, Formatieren, Listenelement anlegen/
löschen/ändern) statt ganzer Notizen. Der Server ordnet gleichzeitige Befehle,
transformiert sie gegeneinander (Jupiter-/Google-Wave-Modell mit zentraler
Revision) und alle Teilnehmer landen garantiert beim selben Ergebnis – ohne
Mergekonflikte.

Textänderungen werden im Delta-Format beschrieben (kompatibel zu quill-delta):
    [{"retain": 5}, {"insert": "Hallo", "attributes": {"bold": true}}, {"delete": 3}]
Längen und Positionen zählen in UTF-16-Codeeinheiten, genau wie JavaScript.

Eine Änderung ("change") ist eine Liste von Teil-Operationen:
    {"k": "text", "f": "title" | "body" | "item:<id>", "d": <delta>}
    {"k": "item_add", "id": str, "pos": float, "checked": bool, "text": str}
    {"k": "item_del", "id": str}
    {"k": "item_set", "id": str, ["checked": bool], ["pos": float]}
    {"k": "set", "is_list": bool}

Dieselbe Logik existiert spiegelbildlich in static/js/delta.js.
"""
from __future__ import annotations

import copy
import math
import re

INF = float("inf")

MAX_TITLE = 1000
MAX_BODY = 100_000
MAX_ITEM_TEXT = 5000
MAX_ITEMS = 1000
MAX_SUBOPS = 2000
ITEM_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,40}$")

INLINE_ATTRS = ("bold", "italic", "underline")
LINE_ATTRS = ("header",)


class InvalidOp(ValueError):
    pass


# --------------------------------------------------------------------------- #
# UTF-16-Hilfen (JavaScript zählt Zeichen in UTF-16-Einheiten)
# --------------------------------------------------------------------------- #
def u16len(s: str) -> int:
    n = len(s)
    if s.isascii():
        return n
    return n + sum(1 for ch in s if ord(ch) > 0xFFFF)


def u16slice(s: str, start: int, end: int | float | None = None) -> str:
    if s.isascii():
        return s[start:None if end is None or end == INF else int(end)]
    raw = s.encode("utf-16-le", "surrogatepass")
    e = len(raw) if end is None or end == INF else int(end) * 2
    return raw[start * 2:e].decode("utf-16-le", "surrogatepass")


def is_wellformed(s: str) -> bool:
    try:
        s.encode("utf-8")
        return True
    except UnicodeEncodeError:  # einzelne Surrogate → ungültig
        return False


# --------------------------------------------------------------------------- #
# Attribute
# --------------------------------------------------------------------------- #
def _attrs_compose(a, b, keep_null):
    a = a or {}
    b = b or {}
    attrs = dict(b)
    if not keep_null:
        attrs = {k: v for k, v in attrs.items() if v is not None}
    for k, v in a.items():
        if k not in b:
            attrs[k] = v
    return attrs or None


def _attrs_transform(a, b, priority):
    if not isinstance(a, dict):
        return b
    if not isinstance(b, dict):
        return None
    if not priority:
        return b
    attrs = {k: v for k, v in b.items() if k not in a}
    return attrs or None


# --------------------------------------------------------------------------- #
# Delta
# --------------------------------------------------------------------------- #
def op_len(op) -> int:
    if "delete" in op:
        return op["delete"]
    if "retain" in op:
        return op["retain"]
    return u16len(op["insert"])


class _Iter:
    def __init__(self, ops):
        self.ops = ops
        self.index = 0
        self.offset = 0

    def has_next(self):
        return self.peek_length() < INF

    def peek(self):
        return self.ops[self.index] if self.index < len(self.ops) else None

    def peek_length(self):
        if self.index < len(self.ops):
            return op_len(self.ops[self.index]) - self.offset
        return INF

    def peek_type(self):
        op = self.peek()
        if op is None:
            return "retain"
        if "delete" in op:
            return "delete"
        if "retain" in op:
            return "retain"
        return "insert"

    def next(self, length=INF):
        if self.index >= len(self.ops):
            return {"retain": INF}
        op = self.ops[self.index]
        offset = self.offset
        olen = op_len(op)
        if length >= olen - offset:
            length = olen - offset
            self.index += 1
            self.offset = 0
        else:
            self.offset += length
        if "delete" in op:
            return {"delete": length}
        out = {}
        if op.get("attributes"):
            out["attributes"] = op["attributes"]
        if "retain" in op:
            out["retain"] = length
        else:
            out["insert"] = u16slice(op["insert"], offset, offset + length)
        return out


class Delta:
    def __init__(self, ops=None):
        self.ops: list[dict] = []
        for op in ops or []:
            self.push(op)

    def __eq__(self, other):
        return isinstance(other, Delta) and self.ops == other.ops

    def __repr__(self):
        return f"Delta({self.ops!r})"

    # -- Aufbau -------------------------------------------------------------- #
    def push(self, new_op):
        new_op = {k: v for k, v in new_op.items() if not (k == "attributes" and not v)}
        if op_len(new_op) <= 0:
            return self
        new_op = copy.deepcopy(new_op)
        index = len(self.ops)
        last = self.ops[index - 1] if index else None
        if last is not None:
            if "delete" in new_op and "delete" in last:
                self.ops[index - 1] = {"delete": last["delete"] + new_op["delete"]}
                return self
            if "delete" in last and "insert" in new_op:
                index -= 1
                last = self.ops[index - 1] if index else None
                if last is None:
                    self.ops.insert(0, new_op)
                    return self
            if new_op.get("attributes") == last.get("attributes"):
                merged = None
                if "insert" in new_op and "insert" in last:
                    merged = {"insert": last["insert"] + new_op["insert"]}
                elif "retain" in new_op and "retain" in last:
                    merged = {"retain": last["retain"] + new_op["retain"]}
                if merged is not None:
                    if new_op.get("attributes"):
                        merged["attributes"] = new_op["attributes"]
                    self.ops[index - 1] = merged
                    return self
        self.ops.insert(index, new_op)
        return self

    def chop(self):
        if self.ops and "retain" in self.ops[-1] and not self.ops[-1].get("attributes"):
            self.ops.pop()
        return self

    def length(self) -> int:
        return sum(op_len(op) for op in self.ops)

    def base_length(self) -> int:
        """Länge des Dokuments, auf das dieses Delta angewendet werden kann (mindestens)."""
        return sum(op_len(op) for op in self.ops if "insert" not in op)

    def is_document(self) -> bool:
        return all("insert" in op for op in self.ops)

    def text(self) -> str:
        return "".join(op["insert"] for op in self.ops)

    # -- Algebra ------------------------------------------------------------- #
    def compose(self, other: "Delta") -> "Delta":
        a, b = _Iter(self.ops), _Iter(other.ops)
        out = Delta()
        while a.has_next() or b.has_next():
            if b.peek_type() == "insert":
                out.push(b.next())
            elif a.peek_type() == "delete":
                out.push(a.next())
            else:
                length = min(a.peek_length(), b.peek_length())
                op_a, op_b = a.next(length), b.next(length)
                if "retain" in op_b:
                    new = {"retain": length} if "retain" in op_a else {"insert": op_a["insert"]}
                    attrs = _attrs_compose(op_a.get("attributes"), op_b.get("attributes"), "retain" in op_a)
                    if attrs:
                        new["attributes"] = attrs
                    out.push(new)
                elif "delete" in op_b and "retain" in op_a:
                    out.push(op_b)
                # insert + delete heben sich auf
        return out.chop()

    def transform(self, other: "Delta", priority: bool) -> "Delta":
        """Transformiert `other` gegen dieses Delta. priority=True: dieses Delta war zuerst."""
        a, b = _Iter(self.ops), _Iter(other.ops)
        out = Delta()
        while a.has_next() or b.has_next():
            if a.peek_type() == "insert" and (priority or b.peek_type() != "insert"):
                out.push({"retain": op_len(a.next())})
            elif b.peek_type() == "insert":
                out.push(b.next())
            else:
                length = min(a.peek_length(), b.peek_length())
                op_a, op_b = a.next(length), b.next(length)
                if "delete" in op_a:
                    continue
                if "delete" in op_b:
                    out.push(op_b)
                else:
                    attrs = _attrs_transform(op_a.get("attributes"), op_b.get("attributes"), priority)
                    new = {"retain": length}
                    if attrs:
                        new["attributes"] = attrs
                    out.push(new)
        return out.chop()

    def transform_position(self, index: int, priority: bool = False) -> int:
        it = _Iter(self.ops)
        offset = 0
        while it.has_next() and offset <= index:
            length = it.peek_length()
            typ = it.peek_type()
            it.next()
            if typ == "delete":
                index -= min(length, index - offset)
                continue
            if typ == "insert" and (offset < index or not priority):
                index += length
            offset += length
        return index


# --------------------------------------------------------------------------- #
# Validierung
# --------------------------------------------------------------------------- #
def _check_attrs(attrs, allowed_inline: bool, allowed_line: bool, retain: bool):
    if attrs is None:
        return None
    if not isinstance(attrs, dict) or len(attrs) > 4:
        raise InvalidOp("Ungültige Attribute")
    clean = {}
    for k, v in attrs.items():
        if k in INLINE_ATTRS and allowed_inline:
            if v is True or (v is None and retain):
                clean[k] = v
                continue
        elif k in LINE_ATTRS and allowed_line:
            if v in (1, 2) or (v is None and retain):
                clean[k] = v
                continue
        raise InvalidOp(f"Attribut nicht erlaubt: {k}={v!r}")
    return clean or None


def parse_delta(raw, rich: bool) -> Delta:
    if not isinstance(raw, list) or len(raw) > 10000:
        raise InvalidOp("Delta muss eine Liste sein")
    d = Delta()
    for op in raw:
        if not isinstance(op, dict):
            raise InvalidOp("Ungültige Operation")
        keys = set(op) - {"attributes"}
        if len(keys) != 1:
            raise InvalidOp("Ungültige Operation")
        (kind,) = keys
        val = op[kind]
        attrs = op.get("attributes")
        if kind == "insert":
            if not isinstance(val, str) or not val:
                raise InvalidOp("insert erwartet Text")
            if not rich and "\n" in val:
                raise InvalidOp("Zeilenumbruch in einzeiligem Feld")
            new = {"insert": val}
            a = _check_attrs(attrs, rich, rich, False)
        elif kind in ("retain", "delete"):
            if isinstance(val, bool) or not isinstance(val, int) or val <= 0 or val > 10_000_000:
                raise InvalidOp(f"{kind} erwartet eine positive Zahl")
            new = {kind: val}
            if kind == "delete" and attrs is not None:
                raise InvalidOp("delete hat keine Attribute")
            a = _check_attrs(attrs, rich, rich, True) if kind == "retain" else None
        else:
            raise InvalidOp(f"Unbekannter Operationstyp: {kind}")
        if a:
            new["attributes"] = a
        d.push(new)
    return d


def _num(v, name):
    if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v):
        raise InvalidOp(f"{name} muss eine Zahl sein")
    return float(v)


def _item_id(v):
    if not isinstance(v, str) or not ITEM_ID_RE.match(v):
        raise InvalidOp("Ungültige Element-ID")
    return v


def parse_change(raw) -> list[dict]:
    """Prüft die Struktur einer Änderung und gibt eine bereinigte Kopie zurück."""
    if not isinstance(raw, list) or len(raw) > MAX_SUBOPS:
        raise InvalidOp("Änderung muss eine Liste sein")
    out = []
    for sub in raw:
        if not isinstance(sub, dict):
            raise InvalidOp("Ungültige Teiloperation")
        k = sub.get("k")
        if k == "text":
            f = sub.get("f")
            if f in ("title", "body"):
                field = f
            elif isinstance(f, str) and f.startswith("item:"):
                field = "item:" + _item_id(f[5:])
            else:
                raise InvalidOp("Unbekanntes Feld")
            d = parse_delta(sub.get("d"), rich=(field == "body"))
            if d.ops:
                out.append({"k": "text", "f": field, "d": d.ops})
        elif k == "item_add":
            text = sub.get("text", "")
            if not isinstance(text, str) or "\n" in text or u16len(text) > MAX_ITEM_TEXT:
                raise InvalidOp("Ungültiger Elementtext")
            out.append({"k": "item_add", "id": _item_id(sub.get("id")), "pos": _num(sub.get("pos"), "pos"),
                        "checked": bool(sub.get("checked", False)), "text": text})
        elif k == "item_del":
            out.append({"k": "item_del", "id": _item_id(sub.get("id"))})
        elif k == "item_set":
            new = {"k": "item_set", "id": _item_id(sub.get("id"))}
            if "checked" in sub:
                if not isinstance(sub["checked"], bool):
                    raise InvalidOp("checked muss true/false sein")
                new["checked"] = sub["checked"]
            if "pos" in sub:
                new["pos"] = _num(sub["pos"], "pos")
            if len(new) > 2:
                out.append(new)
        elif k == "set":
            if not isinstance(sub.get("is_list"), bool):
                raise InvalidOp("set erwartet is_list")
            out.append({"k": "set", "is_list": sub["is_list"]})
        else:
            raise InvalidOp(f"Unbekannte Teiloperation: {k!r}")
    return out


# --------------------------------------------------------------------------- #
# Dokument
# --------------------------------------------------------------------------- #
def empty_doc() -> dict:
    return {"title": "", "body": [{"insert": "\n"}], "items": [], "is_list": False}


def text_to_body(text: str) -> list[dict]:
    return Delta([{"insert": text + "\n"}]).ops


def body_text(body_ops) -> str:
    return "".join(op.get("insert", "") for op in body_ops)


def validate_doc(raw) -> dict:
    """Prüft ein vollständiges Notizdokument (z. B. beim Anlegen einer Notiz)."""
    if not isinstance(raw, dict):
        raise InvalidOp("Ungültiges Dokument")
    title = raw.get("title", "")
    if not isinstance(title, str) or "\n" in title or u16len(title) > MAX_TITLE or not is_wellformed(title):
        raise InvalidOp("Ungültiger Titel")
    body = parse_delta(raw.get("body") or [{"insert": "\n"}], rich=True)
    if not body.is_document() or not body.text().endswith("\n"):
        raise InvalidOp("Ungültiger Notiztext")
    if body.length() > MAX_BODY + 1 or not is_wellformed(body.text()):
        raise InvalidOp("Der Notiztext ist zu lang.")
    items_raw = raw.get("items") or []
    if not isinstance(items_raw, list) or len(items_raw) > MAX_ITEMS:
        raise InvalidOp("Zu viele Listenelemente")
    items, seen = [], set()
    for i, it in enumerate(items_raw):
        if not isinstance(it, dict):
            raise InvalidOp("Ungültiges Listenelement")
        iid = _item_id(it.get("id"))
        if iid in seen:
            continue
        seen.add(iid)
        text = it.get("text", "")
        if not isinstance(text, str) or "\n" in text or u16len(text) > MAX_ITEM_TEXT or not is_wellformed(text):
            raise InvalidOp("Ungültiger Elementtext")
        pos = _num(it.get("pos", i + 1), "pos")
        items.append({"id": iid, "pos": pos, "checked": bool(it.get("checked", False)), "text": text})
    return {"title": title, "body": body.ops, "items": items, "is_list": bool(raw.get("is_list", False))}


def apply_change(doc: dict, change: list[dict]) -> dict:
    """Wendet eine (bereits mit parse_change geprüfte) Änderung an. Wirft InvalidOp."""
    doc = copy.deepcopy(doc)
    items = {it["id"]: it for it in doc["items"]}
    for sub in change:
        k = sub["k"]
        if k == "text":
            d = Delta(sub["d"])
            f = sub["f"]
            if f == "body":
                base = Delta(doc["body"])
                if d.base_length() > base.length():
                    raise InvalidOp("Änderung passt nicht zum Dokument")
                new = base.compose(d)
                text = new.text()
                if not new.is_document() or not text.endswith("\n"):
                    raise InvalidOp("Der letzte Zeilenumbruch darf nicht gelöscht werden")
                if u16len(text) > MAX_BODY + 1:
                    raise InvalidOp("Der Notiztext ist zu lang.")
                doc["body"] = new.ops
            else:
                if f == "title":
                    cur = doc["title"]
                else:
                    it = items.get(f[5:])
                    if it is None:  # Element wurde inzwischen gelöscht → Befehl verfällt
                        continue
                    cur = it["text"]
                if d.base_length() > u16len(cur):
                    raise InvalidOp("Änderung passt nicht zum Text")
                new_text = Delta([{"insert": cur}] if cur else []).compose(d).text()
                limit = MAX_TITLE if f == "title" else MAX_ITEM_TEXT
                if u16len(new_text) > limit:
                    raise InvalidOp("Der Text ist zu lang.")
                if f == "title":
                    doc["title"] = new_text
                else:
                    it["text"] = new_text
        elif k == "item_add":
            if sub["id"] in items:
                continue
            if len(items) >= MAX_ITEMS:
                raise InvalidOp("Zu viele Listenelemente")
            it = {"id": sub["id"], "pos": sub["pos"], "checked": sub["checked"], "text": sub["text"]}
            doc["items"].append(it)
            items[it["id"]] = it
        elif k == "item_del":
            it = items.pop(sub["id"], None)
            if it is not None:
                doc["items"].remove(it)
        elif k == "item_set":
            it = items.get(sub["id"])
            if it is not None:
                for key in ("checked", "pos"):
                    if key in sub:
                        it[key] = sub[key]
        elif k == "set":
            doc["is_list"] = sub["is_list"]
    for s in (doc["title"], body_text(doc["body"]), *(it["text"] for it in doc["items"])):
        if not is_wellformed(s):
            raise InvalidOp("Ungültige Zeichenfolge (getrenntes Surrogatpaar)")
    doc["items"].sort(key=lambda it: (it["pos"], it["id"]))
    return doc


# --------------------------------------------------------------------------- #
# Transformation
# --------------------------------------------------------------------------- #
def _item_of(sub):
    if sub["k"] == "text" and sub["f"].startswith("item:"):
        return sub["f"][5:]
    if sub["k"] in ("item_add", "item_del", "item_set"):
        return sub["id"]
    return None


def xform1(c: dict, s: dict):
    """
    Transformiert zwei gleichzeitige Teiloperationen. `s` wurde vom Server zuerst
    angewendet, `c` danach. Rückgabe (c', s'): c' gilt nach s, s' gilt nach c.
    None bedeutet: Operation entfällt.
    """
    kc, ks = c["k"], s["k"]
    if kc == "text" and ks == "text":
        if c["f"] != s["f"]:
            return c, s
        dc, ds = Delta(c["d"]), Delta(s["d"])
        c2 = ds.transform(dc, True).ops
        s2 = dc.transform(ds, False).ops
        return ({**c, "d": c2} if c2 else None), ({**s, "d": s2} if s2 else None)
    if kc == "set" and ks == "set":
        return c, None  # später angewendet gewinnt (last writer wins)
    ic, is_ = _item_of(c), _item_of(s)
    if ic is None or is_ is None or ic != is_:
        return c, s
    # beide betreffen dasselbe Listenelement
    if ks == "item_del":
        if kc == "item_del":
            return None, None
        return None, s
    if kc == "item_del":
        return c, None
    if kc == "item_add" and ks == "item_add":
        return None, s
    if kc == "item_set" and ks == "item_set":
        rest = {k: v for k, v in s.items() if k not in c}
        return c, (rest if len(rest) > 2 else None)
    return c, s


def xform(cs: list[dict], ss: list[dict]):
    """Transformiert zwei Änderungen (Listen von Teiloperationen) gegeneinander."""
    out_c = []
    for c in cs:
        new_ss = []
        cur = c
        for i, s in enumerate(ss):
            if cur is None:
                new_ss.extend(ss[i:])
                break
            cur, s2 = xform1(cur, s)
            if s2 is not None:
                new_ss.append(s2)
        ss = new_ss
        if cur is not None:
            out_c.append(cur)
    return out_c, ss


def replace_change(cur: dict, target: dict) -> list[dict]:
    """Erzeugt eine Änderung, die das Dokument `cur` in `target` überführt (Versionen)."""
    change: list[dict] = []
    if cur["title"] != target["title"]:
        d = Delta()
        if target["title"]:
            d.push({"insert": target["title"]})
        if cur["title"]:
            d.push({"delete": u16len(cur["title"])})
        change.append({"k": "text", "f": "title", "d": d.ops})
    if cur["body"] != target["body"]:
        d = Delta()
        for op in target["body"]:
            d.push(op)
        d.push({"delete": Delta(cur["body"]).length()})
        change.append({"k": "text", "f": "body", "d": d.ops})
    cur_items = {it["id"]: it for it in cur["items"]}
    tgt_items = {it["id"]: it for it in target["items"]}
    for iid, it in cur_items.items():
        t = tgt_items.get(iid)
        if t is None or t["text"] != it["text"]:
            change.append({"k": "item_del", "id": iid})
        elif t["checked"] != it["checked"] or t["pos"] != it["pos"]:
            change.append({"k": "item_set", "id": iid, "checked": t["checked"], "pos": t["pos"]})
    for iid, t in tgt_items.items():
        c = cur_items.get(iid)
        if c is None or c["text"] != t["text"]:
            change.append({"k": "item_add", "id": iid, "pos": t["pos"], "checked": t["checked"], "text": t["text"]})
    if cur["is_list"] != target["is_list"]:
        change.append({"k": "set", "is_list": target["is_list"]})
    return change
