#!/usr/bin/env python3
"""
Selbsttest der Konfliktfreiheit: Zufällige gleichzeitige Änderungen zweier Nutzer werden
gegeneinander transformiert; beide Reihenfolgen müssen exakt dasselbe Dokument ergeben.

    python tests/test_ot.py            # 5000 Zufallsfälle
"""
import json
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import ot  # noqa: E402

R = random.Random(int(sys.argv[1]) if len(sys.argv) > 1 else 1)
CHARS = "abü \n"


def text(n, newlines=True):
    return "".join(R.choice(CHARS if newlines else CHARS.replace("\n", "")) for _ in range(n))


def rand_delta(length, rich):
    d, pos = ot.Delta(), 0
    while pos < length and R.random() < 0.8:
        step, r = R.randint(0, length - pos), R.random()
        if r < 0.4 and step:
            d.push({"retain": step, **({"attributes": {"bold": R.choice([True, None])}} if rich and R.random() < .3 else {})})
            pos += step
        elif r < 0.7:
            d.push({"insert": text(R.randint(1, 3), rich)})
        elif step:
            d.push({"delete": step})
            pos += step
    return d.chop()


def rand_doc():
    items = [{"id": f"i{k}", "pos": float(k), "checked": R.random() < .3, "text": text(R.randint(0, 4), False)}
             for k in range(R.randint(0, 3))]
    return {"title": text(R.randint(0, 5), False), "body": [{"insert": text(R.randint(0, 6)) + "\n"}],
            "items": items, "is_list": False}


def rand_change(doc):
    change = []
    for _ in range(R.randint(1, 3)):
        r = R.random()
        if r < .25:
            sub = {"k": "text", "f": "title", "d": rand_delta(ot.u16len(doc["title"]), False).ops}
        elif r < .5:
            sub = {"k": "text", "f": "body", "d": rand_delta(ot.Delta(doc["body"]).length() - 1, True).ops}
        elif r < .65 and doc["items"]:
            it = R.choice(doc["items"])
            sub = {"k": "text", "f": "item:" + it["id"], "d": rand_delta(ot.u16len(it["text"]), False).ops}
        elif r < .75:
            sub = {"k": "item_add", "id": f"n{R.randint(0, 10**9)}", "pos": R.random() * 5, "checked": False, "text": "x"}
        elif r < .85 and doc["items"]:
            sub = {"k": "item_del", "id": R.choice(doc["items"])["id"]}
        elif r < .95 and doc["items"]:
            sub = {"k": "item_set", "id": R.choice(doc["items"])["id"], "checked": R.random() < .5}
        else:
            sub = {"k": "set", "is_list": R.random() < .5}
        if sub.get("d") == []:
            continue
        change.append(sub)
        doc = ot.apply_change(doc, ot.parse_change([sub]))
    return change


def main():
    n = 5000
    for _ in range(n):
        doc = rand_doc()
        c, s = ot.parse_change(rand_change(doc)), ot.parse_change(rand_change(doc))
        c2, s2 = ot.xform(c, s)
        a = ot.apply_change(ot.apply_change(doc, s), c2)
        b = ot.apply_change(ot.apply_change(doc, c), s2)
        if a != b:
            print("FEHLER: keine Konvergenz\n", json.dumps({"doc": doc, "c": c, "s": s}, ensure_ascii=False))
            sys.exit(1)
    print(f"OK – {n} zufällige gleichzeitige Änderungen konvergieren.")


if __name__ == "__main__":
    main()
