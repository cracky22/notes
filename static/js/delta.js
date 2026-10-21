/*
 * delta.js – Operational Transformation (Gegenstück zu ot.py auf dem Server).
 *
 * Textänderungen im Delta-Format (kompatibel zu quill-delta):
 *   [{retain: 5}, {insert: 'Hallo', attributes: {bold: true}}, {delete: 3}]
 * Eine Änderung ("change") ist eine Liste von Teiloperationen:
 *   {k:'text', f:'title'|'body'|'item:<id>', d:[…]}
 *   {k:'item_add', id, pos, checked, text}
 *   {k:'item_del', id}
 *   {k:'item_set', id, [checked], [pos]}
 *   {k:'set', is_list}
 */

const INF = Infinity;
const isObj = v => v !== null && typeof v === 'object';

function attrsEqual(a, b) {
  const ka = a ? Object.keys(a) : [];
  const kb = b ? Object.keys(b) : [];
  if (ka.length !== kb.length) return false;
  return ka.every(k => a[k] === b[k]);
}

export const Attr = {
  compose(a = {}, b = {}, keepNull = false) {
    a = a || {}; b = b || {};
    let attrs = { ...b };
    if (!keepNull) attrs = Object.fromEntries(Object.entries(attrs).filter(([, v]) => v != null));
    for (const k of Object.keys(a)) if (a[k] !== undefined && b[k] === undefined) attrs[k] = a[k];
    return Object.keys(attrs).length ? attrs : undefined;
  },
  transform(a, b, priority) {
    if (!isObj(a)) return b;
    if (!isObj(b)) return undefined;
    if (!priority) return b;
    const attrs = Object.fromEntries(Object.entries(b).filter(([k]) => a[k] === undefined));
    return Object.keys(attrs).length ? attrs : undefined;
  },
  invert(attr = {}, base = {}) {
    attr = attr || {}; base = base || {};
    const out = {};
    for (const k of Object.keys(base)) if (base[k] !== attr[k] && attr[k] !== undefined) out[k] = base[k];
    for (const k of Object.keys(attr)) if (attr[k] !== base[k] && base[k] === undefined) out[k] = null;
    return out;
  },
};

export function opLen(op) {
  if (typeof op.delete === 'number') return op.delete;
  if (typeof op.retain === 'number') return op.retain;
  return op.insert.length;
}

class OpIter {
  constructor(ops) { this.ops = ops; this.index = 0; this.offset = 0; }
  hasNext() { return this.peekLength() < INF; }
  peek() { return this.ops[this.index]; }
  peekLength() { return this.index < this.ops.length ? opLen(this.ops[this.index]) - this.offset : INF; }
  peekType() {
    const op = this.ops[this.index];
    if (!op) return 'retain';
    if (typeof op.delete === 'number') return 'delete';
    if (typeof op.retain === 'number') return 'retain';
    return 'insert';
  }
  next(length = INF) {
    const op = this.ops[this.index];
    if (!op) return { retain: INF };
    const offset = this.offset;
    const len = opLen(op);
    if (length >= len - offset) { length = len - offset; this.index += 1; this.offset = 0; } else this.offset += length;
    if (typeof op.delete === 'number') return { delete: length };
    const out = typeof op.retain === 'number' ? { retain: length } : { insert: op.insert.substr(offset, length) };
    if (op.attributes) out.attributes = op.attributes;
    return out;
  }
  rest() {
    if (!this.hasNext()) return [];
    if (this.offset === 0) return this.ops.slice(this.index);
    const offset = this.offset, index = this.index;
    const first = this.next();
    const rest = this.ops.slice(this.index);
    this.offset = offset; this.index = index;
    return [first, ...rest];
  }
}

export class Delta {
  constructor(ops) {
    this.ops = [];
    if (Array.isArray(ops)) for (const op of ops) this.push(op);
    else if (ops && Array.isArray(ops.ops)) for (const op of ops.ops) this.push(op);
  }

  insert(text, attributes) {
    if (typeof text !== 'string' || !text.length) return this;
    const op = { insert: text };
    if (attributes && Object.keys(attributes).length) op.attributes = attributes;
    return this.push(op);
  }
  retain(length, attributes) {
    if (length <= 0) return this;
    const op = { retain: length };
    if (attributes && Object.keys(attributes).length) op.attributes = attributes;
    return this.push(op);
  }
  delete(length) { return length <= 0 ? this : this.push({ delete: length }); }

  push(newOp) {
    newOp = { ...newOp };
    if (newOp.attributes && !Object.keys(newOp.attributes).length) delete newOp.attributes;
    if (newOp.attributes) newOp.attributes = { ...newOp.attributes };
    if (opLen(newOp) <= 0) return this;
    let index = this.ops.length;
    let last = this.ops[index - 1];
    if (last) {
      if (typeof newOp.delete === 'number' && typeof last.delete === 'number') {
        this.ops[index - 1] = { delete: last.delete + newOp.delete };
        return this;
      }
      if (typeof last.delete === 'number' && newOp.insert != null) {
        index -= 1;
        last = this.ops[index - 1];
        if (!last) { this.ops.unshift(newOp); return this; }
      }
      if (attrsEqual(newOp.attributes, last.attributes)) {
        let merged = null;
        if (typeof newOp.insert === 'string' && typeof last.insert === 'string') merged = { insert: last.insert + newOp.insert };
        else if (typeof newOp.retain === 'number' && typeof last.retain === 'number') merged = { retain: last.retain + newOp.retain };
        if (merged) {
          if (newOp.attributes) merged.attributes = newOp.attributes;
          this.ops[index - 1] = merged;
          return this;
        }
      }
    }
    if (index === this.ops.length) this.ops.push(newOp); else this.ops.splice(index, 0, newOp);
    return this;
  }

  chop() {
    const last = this.ops[this.ops.length - 1];
    if (last && typeof last.retain === 'number' && !last.attributes) this.ops.pop();
    return this;
  }

  length() { return this.ops.reduce((n, op) => n + opLen(op), 0); }
  changeLength() {
    return this.ops.reduce((n, op) => (op.insert != null ? n + op.insert.length : typeof op.delete === 'number' ? n - op.delete : n), 0);
  }
  isEmpty() { return this.ops.length === 0; }
  text() { return this.ops.map(op => op.insert || '').join(''); }

  slice(start = 0, end = INF) {
    const ops = [];
    const it = new OpIter(this.ops);
    let index = 0;
    while (index < end && it.hasNext()) {
      let next;
      if (index < start) next = it.next(start - index);
      else { next = it.next(end - index); ops.push(next); }
      index += opLen(next);
    }
    return new Delta(ops);
  }

  concat(other) {
    const d = new Delta(this.ops);
    for (const op of other.ops) d.push(op);
    return d;
  }

  compose(other) {
    const a = new OpIter(this.ops);
    const b = new OpIter(other.ops);
    const out = new Delta();
    while (a.hasNext() || b.hasNext()) {
      if (b.peekType() === 'insert') out.push(b.next());
      else if (a.peekType() === 'delete') out.push(a.next());
      else {
        const length = Math.min(a.peekLength(), b.peekLength());
        const opA = a.next(length);
        const opB = b.next(length);
        if (typeof opB.retain === 'number') {
          const neu = typeof opA.retain === 'number' ? { retain: length } : { insert: opA.insert };
          const attrs = Attr.compose(opA.attributes, opB.attributes, typeof opA.retain === 'number');
          if (attrs) neu.attributes = attrs;
          out.push(neu);
        } else if (typeof opB.delete === 'number' && typeof opA.retain === 'number') {
          out.push(opB);
        }
      }
    }
    return out.chop();
  }

  /** Transformiert `other` gegen dieses Delta. priority=true: dieses Delta war zuerst. */
  transform(other, priority) {
    const a = new OpIter(this.ops);
    const b = new OpIter(other.ops);
    const out = new Delta();
    while (a.hasNext() || b.hasNext()) {
      if (a.peekType() === 'insert' && (priority || b.peekType() !== 'insert')) out.retain(opLen(a.next()));
      else if (b.peekType() === 'insert') out.push(b.next());
      else {
        const length = Math.min(a.peekLength(), b.peekLength());
        const opA = a.next(length);
        const opB = b.next(length);
        if (opA.delete) continue;
        if (opB.delete) out.push(opB);
        else out.retain(length, Attr.transform(opA.attributes, opB.attributes, priority));
      }
    }
    return out.chop();
  }

  transformPosition(index, priority = false) {
    const it = new OpIter(this.ops);
    let offset = 0;
    while (it.hasNext() && offset <= index) {
      const length = it.peekLength();
      const type = it.peekType();
      it.next();
      if (type === 'delete') { index -= Math.min(length, index - offset); continue; }
      if (type === 'insert' && (offset < index || !priority)) index += length;
      offset += length;
    }
    return index;
  }

  invert(base) {
    const out = new Delta();
    let baseIndex = 0;
    for (const op of this.ops) {
      if (op.insert != null) out.delete(op.insert.length);
      else if (typeof op.retain === 'number' && !op.attributes) { out.retain(op.retain); baseIndex += op.retain; }
      else {
        const length = op.delete || op.retain;
        for (const baseOp of base.slice(baseIndex, baseIndex + length).ops) {
          if (op.delete) out.push(baseOp);
          else out.retain(opLen(baseOp), Attr.invert(op.attributes, baseOp.attributes));
        }
        baseIndex += length;
      }
    }
    return out.chop();
  }

  /** Iteriert über Zeilen eines Dokuments: fn(lineDelta, lineAttrs, index) */
  eachLine(fn) {
    const it = new OpIter(this.ops);
    let line = new Delta();
    let i = 0;
    while (it.hasNext()) {
      if (it.peekType() !== 'insert') return;
      const op = it.peek();
      const start = opLen(op) - it.peekLength();
      const nl = op.insert.indexOf('\n', start) - start;
      if (nl < 0) line.push(it.next());
      else if (nl > 0) line.push(it.next(nl));
      else {
        if (fn(line, it.next(1).attributes || {}, i) === false) return;
        i += 1;
        line = new Delta();
      }
    }
    if (line.length() > 0) fn(line, {}, i);
  }
}

// ---------------------------------------------------------------------------
// Dokumente und Änderungen
// ---------------------------------------------------------------------------
export const plainDelta = text => new Delta(text ? [{ insert: text }] : []);

export function sortItems(items) {
  return items.sort((a, b) => a.pos - b.pos || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

export function cloneDoc(doc) {
  return {
    title: doc.title,
    body: new Delta(doc.body.ops || doc.body),
    items: doc.items.map(it => ({ ...it })),
    is_list: !!doc.is_list,
  };
}

/** Wendet eine Änderung auf ein Dokument an (verändert das Dokument direkt). */
export function applyChange(doc, change) {
  for (const sub of change) {
    if (sub.k === 'text') {
      const d = new Delta(sub.d);
      if (sub.f === 'body') doc.body = doc.body.compose(d);
      else if (sub.f === 'title') doc.title = plainDelta(doc.title).compose(d).text();
      else {
        const it = doc.items.find(x => x.id === sub.f.slice(5));
        if (it) it.text = plainDelta(it.text).compose(d).text();
      }
    } else if (sub.k === 'item_add') {
      if (!doc.items.some(x => x.id === sub.id)) doc.items.push({ id: sub.id, pos: sub.pos, checked: !!sub.checked, text: sub.text || '' });
    } else if (sub.k === 'item_del') {
      const i = doc.items.findIndex(x => x.id === sub.id);
      if (i >= 0) doc.items.splice(i, 1);
    } else if (sub.k === 'item_set') {
      const it = doc.items.find(x => x.id === sub.id);
      if (it) { if ('checked' in sub) it.checked = sub.checked; if ('pos' in sub) it.pos = sub.pos; }
    } else if (sub.k === 'set') {
      doc.is_list = sub.is_list;
    }
  }
  sortItems(doc.items);
  return doc;
}

function itemOf(sub) {
  if (sub.k === 'text' && sub.f.startsWith('item:')) return sub.f.slice(5);
  if (sub.k === 'item_add' || sub.k === 'item_del' || sub.k === 'item_set') return sub.id;
  return null;
}

/** s wurde zuerst (vom Server) angewendet. Ergebnis [c', s'] – null = entfällt. */
export function xform1(c, s) {
  if (c.k === 'text' && s.k === 'text') {
    if (c.f !== s.f) return [c, s];
    const dc = new Delta(c.d);
    const ds = new Delta(s.d);
    const c2 = ds.transform(dc, true).ops;
    const s2 = dc.transform(ds, false).ops;
    return [c2.length ? { ...c, d: c2 } : null, s2.length ? { ...s, d: s2 } : null];
  }
  if (c.k === 'set' && s.k === 'set') return [c, null];
  const ic = itemOf(c), is = itemOf(s);
  if (ic == null || is == null || ic !== is) return [c, s];
  if (s.k === 'item_del') return c.k === 'item_del' ? [null, null] : [null, s];
  if (c.k === 'item_del') return [c, null];
  if (c.k === 'item_add' && s.k === 'item_add') return [null, s];
  if (c.k === 'item_set' && s.k === 'item_set') {
    const rest = { k: 'item_set', id: s.id };
    if ('checked' in s && !('checked' in c)) rest.checked = s.checked;
    if ('pos' in s && !('pos' in c)) rest.pos = s.pos;
    return [c, Object.keys(rest).length > 2 ? rest : null];
  }
  return [c, s];
}

export function xform(cs, ss) {
  const outC = [];
  for (const c of cs) {
    let cur = c;
    const newS = [];
    for (let i = 0; i < ss.length; i++) {
      if (cur == null) { newS.push(...ss.slice(i)); break; }
      const [c2, s2] = xform1(cur, ss[i]);
      cur = c2;
      if (s2 != null) newS.push(s2);
    }
    ss = newS;
    if (cur != null) outC.push(cur);
  }
  return [outC, ss];
}

/** Hängt b an a an und fasst aufeinanderfolgende Textänderungen desselben Feldes zusammen. */
export function composeChanges(a, b) {
  const out = a.map(x => ({ ...x }));
  for (const sub of b) {
    const last = out[out.length - 1];
    if (last && last.k === 'text' && sub.k === 'text' && last.f === sub.f) {
      const d = new Delta(last.d).compose(new Delta(sub.d)).ops;
      if (d.length) last.d = d; else out.pop();
    } else out.push({ ...sub });
  }
  return out;
}

/** Erzeugt die Umkehrung einer Änderung relativ zu `doc` (Zustand VOR der Änderung). */
export function invertChange(doc, change) {
  const work = cloneDoc(doc);
  const inv = [];
  for (const sub of change) {
    if (sub.k === 'text') {
      let base;
      if (sub.f === 'body') base = work.body;
      else if (sub.f === 'title') base = plainDelta(work.title);
      else { const it = work.items.find(x => x.id === sub.f.slice(5)); base = plainDelta(it ? it.text : ''); }
      inv.unshift({ k: 'text', f: sub.f, d: new Delta(sub.d).invert(base).ops });
    } else if (sub.k === 'item_add') {
      inv.unshift({ k: 'item_del', id: sub.id });
    } else if (sub.k === 'item_del') {
      const it = work.items.find(x => x.id === sub.id);
      if (it) inv.unshift({ k: 'item_add', id: it.id, pos: it.pos, checked: it.checked, text: it.text });
    } else if (sub.k === 'item_set') {
      const it = work.items.find(x => x.id === sub.id);
      if (it) {
        const r = { k: 'item_set', id: it.id };
        if ('checked' in sub) r.checked = it.checked;
        if ('pos' in sub) r.pos = it.pos;
        inv.unshift(r);
      }
    } else if (sub.k === 'set') {
      inv.unshift({ k: 'set', is_list: work.is_list });
    }
    applyChange(work, [sub]);
  }
  return inv.filter(x => x.k !== 'text' || x.d.length);
}

/** Verschiebt eine Cursorposition in Feld f durch eine Änderung. */
export function transformIndex(change, f, index, priority = false) {
  for (const sub of change) {
    if (sub.k === 'text' && sub.f === f) index = new Delta(sub.d).transformPosition(index, priority);
  }
  return index;
}

export function docText(doc) {
  return doc.is_list ? doc.items.map(i => i.text).join('\n') : doc.body.text().replace(/\n$/, '');
}
