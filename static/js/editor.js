/*
 * editor.js – Textfeld auf Basis von contenteditable mit Delta-Modell.
 *
 * Jede Eingabe wird in ein Delta (Einfügen/Löschen/Formatieren) übersetzt und über
 * onChange gemeldet. Strukturelle Eingaben (Enter, Einfügen, Löschen über Zeilengrenzen,
 * Formatierung) werden direkt im Modell ausgeführt; normales Tippen und IME-Eingaben
 * erledigt der Browser, danach wird das DOM mit dem Modell verglichen (Diff).
 */
import { Delta, Attr } from './delta.js';

const BLOCK_TAGS = new Set(['DIV', 'P', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'LI', 'UL', 'OL', 'BLOCKQUOTE',
  'PRE', 'TABLE', 'TR', 'SECTION', 'ARTICLE', 'HEADER', 'FOOTER']);
const INLINE = ['bold', 'italic', 'underline'];
const URL_RE = /\bhttps?:\/\/[^\s<>"']+[^\s<>"'.,;:!?)\]]/g;

export const esc = t => t.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const escAttr = t => esc(t).replace(/"/g, '&quot;');

function inlineAttrs(a) {
  if (!a) return undefined;
  const o = {};
  for (const k of INLINE) if (a[k]) o[k] = true;
  return Object.keys(o).length ? o : undefined;
}
function lineAttrs(a) {
  return a && (a.header === 1 || a.header === 2) ? { header: a.header } : undefined;
}
const keyOf = a => (a ? INLINE.filter(k => a[k]).join(',') + (a.header ? `|h${a.header}` : '') : '');

/** Entfernt nicht darstellbare Attribute (Inline nur an Text, Überschrift nur an Zeilenumbrüchen). */
export function normalizeDoc(doc, rich) {
  const out = new Delta();
  for (const op of doc.ops) {
    if (typeof op.insert !== 'string') continue;
    if (!rich) { out.insert(op.insert); continue; }
    const parts = op.insert.split('\n');
    parts.forEach((part, i) => {
      if (part) out.insert(part, inlineAttrs(op.attributes));
      if (i < parts.length - 1) out.insert('\n', lineAttrs(op.attributes));
    });
  }
  const text = out.text();
  if (!text.endsWith('\n')) out.insert('\n');
  return out;
}

function linkify(text) {
  let html = '';
  let last = 0;
  for (const m of text.matchAll(URL_RE)) {
    html += esc(text.slice(last, m.index));
    html += `<a href="${escAttr(m[0])}" target="_blank" rel="noopener noreferrer">${esc(m[0])}</a>`;
    last = m.index + m[0].length;
  }
  return html + esc(text.slice(last));
}

/** Erzeugt das kanonische HTML eines Dokuments (eine <div class="ln"> pro Zeile). */
export function renderHTML(doc, { rich = true, links = false, maxChars = Infinity } = {}) {
  let html = '';
  let count = 0;
  doc.eachLine((line, attrs) => {
    const cls = rich && attrs.header ? ` h${attrs.header}` : '';
    let inner = '';
    for (const op of line.ops) {
      let t = op.insert;
      if (count + t.length > maxChars) t = t.slice(0, Math.max(0, maxChars - count)) + '…';
      count += t.length;
      let s = links ? linkify(t) : esc(t);
      const a = rich ? op.attributes : null;
      if (a && a.underline) s = `<u>${s}</u>`;
      if (a && a.italic) s = `<i>${s}</i>`;
      if (a && a.bold) s = `<b>${s}</b>`;
      inner += s;
      if (count >= maxChars) break;
    }
    html += `<div class="ln${cls}">${inner || '<br>'}</div>`;
    count += 1;
    return count < maxChars;
  });
  return html;
}

function flatten(doc) {
  let s = '';
  const keys = [];
  for (const op of doc.ops) {
    s += op.insert;
    const k = keyOf(op.attributes);
    for (let i = 0; i < op.insert.length; i++) keys.push(k);
  }
  return [s, keys];
}

/** Minimales Delta, das Dokument a in b überführt (gemeinsamer Anfang/Ende). */
export function diffDocs(a, b) {
  const [s1, k1] = flatten(a);
  const [s2, k2] = flatten(b);
  const min = Math.min(s1.length, s2.length);
  let p = 0;
  while (p < min && s1[p] === s2[p] && k1[p] === k2[p]) p++;
  if (p > 0 && /[\uD800-\uDBFF]/.test(s1[p - 1] || '')) p--;
  let q = 0;
  while (q < min - p && s1[s1.length - 1 - q] === s2[s2.length - 1 - q] && k1[k1.length - 1 - q] === k2[k2.length - 1 - q]) q++;
  if (q > 0 && /[\uDC00-\uDFFF]/.test(s1[s1.length - q] || '')) q--;
  const d = new Delta().retain(p).delete(s1.length - p - q);
  for (const op of b.slice(p, s2.length - q).ops) d.push(op);
  return d.chop();
}

export class TextField {
  static active = null;

  constructor(opts = {}) {
    this.opts = opts;
    this.rich = !!opts.rich;
    this.el = document.createElement('div');
    this.el.className = `tf ${this.rich ? 'tf-rich' : 'tf-plain'} ${opts.className || ''}`.trim();
    this.el.dataset.placeholder = opts.placeholder || '';
    this.el.setAttribute('role', 'textbox');
    if (this.rich) this.el.setAttribute('aria-multiline', 'true');
    if (opts.label) this.el.setAttribute('aria-label', opts.label);
    this.el.spellcheck = true;
    this.doc = new Delta().insert('\n');
    this.composing = false;
    this.pending = null;   // Formatierung für die nächste Eingabe (z. B. nach Strg+B ohne Auswahl)
    this.lastSel = null;
    this.setReadOnly(!!opts.readOnly);
    this.bind();
    this.render();
  }

  setReadOnly(ro) {
    this.readOnly = ro;
    this.el.contentEditable = ro ? 'false' : 'true';
    this.el.setAttribute('aria-readonly', String(ro));
  }

  // ----- Inhalt ---------------------------------------------------------------
  get length() { return this.doc.length() - 1; }
  text() { return this.doc.text().slice(0, -1); }

  setText(text) { this.doc = new Delta().insert((text || '').replace(/\n/g, ' ')).insert('\n'); this.render(); }
  setDoc(delta) { this.doc = normalizeDoc(new Delta(delta), this.rich); this.render(); }

  /** Externe Änderung anwenden (Remote, Undo). sel: 'keep' = Cursor mitverschieben, Objekt = setzen. */
  apply(delta, sel = 'keep') {
    const d = new Delta(delta);
    const cur = sel === 'keep' ? this.getSel() : null;
    this.doc = normalizeDoc(this.doc.compose(d), this.rich);
    this.render();
    if (cur) {
      const a = d.transformPosition(cur.index);
      const b = d.transformPosition(cur.index + cur.length);
      this.setSel(a, b - a);
    } else if (sel && typeof sel === 'object') {
      this.setSel(sel.index, sel.length || 0);
    }
  }

  html() { return renderHTML(this.doc, { rich: this.rich }); }

  render() {
    this.el.innerHTML = this.html();
    this.updateEmpty();
    if (this.opts.onRender) this.opts.onRender(this);
  }

  updateEmpty() { this.el.classList.toggle('empty', this.length === 0); }

  // ----- DOM lesen -------------------------------------------------------------
  readFrom(root) {
    const lines = [];
    let cur = null;
    const start = attrs => { cur = { runs: [], attrs }; lines.push(cur); };
    const visit = (node, fmt) => {
      if (node.nodeType === 3) {
        if (!cur) start(undefined);
        const t = node.data.replace(/[ ]/g, ' ').replace(/[\n\r]/g, ' ').replace(/​/g, '');
        if (t) cur.runs.push({ t, a: fmt });
        return;
      }
      if (node.nodeType !== 1 && node.nodeType !== 11) return;
      const tag = node.tagName;
      if (tag === 'BR') { if (!cur) start(undefined); return; }
      if (tag === 'STYLE' || tag === 'SCRIPT') return;
      let f = fmt;
      if (tag === 'B' || tag === 'STRONG') f = { ...f, bold: true };
      else if (tag === 'I' || tag === 'EM') f = { ...f, italic: true };
      else if (tag === 'U') f = { ...f, underline: true };
      if (tag && BLOCK_TAGS.has(tag) && node !== root) {
        let la;
        if (this.rich && node.classList) {
          if (node.classList.contains('h1') || tag === 'H1') la = { header: 1 };
          else if (node.classList.contains('h2') || tag === 'H2') la = { header: 2 };
        }
        start(la);
        for (const c of node.childNodes) visit(c, f);
        cur = null;
        return;
      }
      for (const c of node.childNodes) visit(c, f);
    };
    for (const c of root.childNodes) visit(c, {});
    if (!lines.length) lines.push({ runs: [], attrs: undefined });
    const d = new Delta();
    if (!this.rich) {
      d.insert(lines.map(l => l.runs.map(r => r.t).join('')).join(' '));
      return d.insert('\n');
    }
    for (const line of lines) {
      for (const r of line.runs) d.insert(r.t, inlineAttrs(r.a));
      d.insert('\n', line.attrs);
    }
    return d;
  }

  // ----- Auswahl ---------------------------------------------------------------
  pointToIndex(node, offset) {
    if (!this.el.contains(node)) return null;
    const range = document.createRange();
    range.setStart(this.el, 0);
    try { range.setEnd(node, offset); } catch { return null; }
    const len = this.readFrom(range.cloneContents()).length();
    if (node === this.el) return offset === 0 ? 0 : Math.min(len, this.length);
    return Math.max(0, Math.min(len - 1, this.length));
  }

  indexToPoint(index) {
    let rem = Math.max(0, Math.min(index, this.length));
    const lines = this.el.children;
    for (let li = 0; li < lines.length; li++) {
      const line = lines[li];
      const walker = document.createTreeWalker(line, NodeFilter.SHOW_TEXT);
      const texts = [];
      let len = 0;
      for (let n = walker.nextNode(); n; n = walker.nextNode()) { texts.push(n); len += n.data.length; }
      if (rem <= len || li === lines.length - 1) {
        for (const n of texts) {
          if (rem <= n.data.length) return [n, rem];
          rem -= n.data.length;
        }
        return [line, texts.length ? line.childNodes.length : 0];
      }
      rem -= len + 1;
    }
    return [this.el, 0];
  }

  getSel() {
    const sel = document.getSelection();
    if (!sel || !sel.rangeCount) return null;
    const r = sel.getRangeAt(0);
    if (!this.el.contains(r.startContainer) || !this.el.contains(r.endContainer)) return null;
    const a = this.pointToIndex(r.startContainer, r.startOffset);
    const b = r.collapsed ? a : this.pointToIndex(r.endContainer, r.endOffset);
    if (a == null || b == null) return null;
    return { index: Math.min(a, b), length: Math.abs(b - a) };
  }

  setSel(index, length = 0) {
    const a = Math.max(0, Math.min(index, this.length));
    const b = Math.max(a, Math.min(index + length, this.length));
    const range = document.createRange();
    range.setStart(...this.indexToPoint(a));
    range.setEnd(...this.indexToPoint(b));
    const sel = document.getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
    this.lastSel = { index: a, length: b - a };
  }

  focus(index = null) {
    if (document.activeElement !== this.el) this.el.focus({ preventScroll: true });
    this.setSel(index == null ? this.length : index, 0);
  }

  /** Bildschirm-Rechtecke eines Bereichs (für Live-Cursor anderer Nutzer). */
  rects(index, length) {
    const range = document.createRange();
    const [sn, so] = this.indexToPoint(index);
    range.setStart(sn, so);
    if (length > 0) range.setEnd(...this.indexToPoint(index + length));
    else range.collapse(true);
    let rects = [...range.getClientRects()];
    if (!rects.length) {
      const el = sn.nodeType === 1 ? sn : sn.parentElement;
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      const lh = parseFloat(cs.lineHeight) || r.height;
      rects = [new DOMRect(r.left + (parseFloat(cs.paddingLeft) || 0), r.top + (parseFloat(cs.paddingTop) || 0), 0, lh)];
    }
    return rects;
  }

  // ----- Änderungen ------------------------------------------------------------
  emit(delta, before, after) {
    if (!delta.ops.length) return;
    if (this.opts.onChange) this.opts.onChange(delta, { before, after, field: this });
  }

  applyLocal(delta, sel) {
    const before = this.lastSel;
    this.doc = normalizeDoc(this.doc.compose(delta), this.rich);
    this.render();
    if (sel) this.setSel(sel.index, sel.length || 0);
    this.emit(delta, before, sel || this.getSel());
  }

  /** Liest Änderungen aus dem DOM und meldet sie (nach nativer Eingabe). */
  sync(normalize = true) {
    const fresh = normalizeDoc(this.readFrom(this.el), this.rich);
    const sel = this.getSel();
    const delta = diffDocs(this.doc, fresh);
    if (delta.ops.length) {
      const before = this.lastSel;
      this.doc = fresh;
      this.emit(delta, before, sel);
    }
    if (normalize && this.el.innerHTML !== this.html()) {
      this.el.innerHTML = this.html();
      if (sel && document.activeElement === this.el) this.setSel(sel.index, sel.length);
    }
    if (sel) this.lastSel = sel;
    this.updateEmpty();
    if (delta.ops.length && this.opts.onRender) this.opts.onRender(this);
  }

  /** Vor dem Anwenden fremder Änderungen: ausstehende Eingaben übernehmen. */
  flush() { if (!this.readOnly) this.sync(!this.composing); }

  attrsAt(index) {
    if (index < 0) return {};
    const op = this.doc.slice(index, index + 1).ops[0];
    return (op && op.attributes) || {};
  }

  lineEnd(index) { return this.doc.text().indexOf('\n', index); }

  typingAttrs(index) {
    const prev = index > 0 && this.doc.text()[index - 1] !== '\n' ? inlineAttrs(this.attrsAt(index - 1)) : undefined;
    return this.pending ? inlineAttrs(Attr.compose(prev, this.pending, false)) : prev;
  }

  insertText(text, sel, paste = false) {
    let ins = text.replace(/\r\n?/g, '\n');
    if (!this.rich) ins = ins.replace(/\n+/g, ' ');
    const attrs = this.rich && !paste ? this.typingAttrs(sel.index) : undefined;
    const d = new Delta().retain(sel.index);
    this.deleteInto(d, sel.index, sel.length);
    d.insert(ins, attrs);
    this.pending = null;
    this.applyLocal(d.chop(), { index: sel.index + ins.length, length: 0 });
  }

  /** Löscht [index, index+length) und behält dabei die Zeilenformatierung der ersten Zeile. */
  deleteInto(d, index, length) {
    if (length <= 0) return d;
    d.delete(length);
    if (!this.rich) return d;
    const text = this.doc.text();
    const removed = text.slice(index, index + length);
    if (!removed.includes('\n')) return d;
    const firstEnd = text.indexOf('\n', index);
    const keep = this.attrsAt(firstEnd).header || null;
    const endLine = text.indexOf('\n', index + length);
    const have = this.attrsAt(endLine).header || null;
    if (keep !== have) d.retain(endLine - (index + length)).retain(1, { header: keep });
    return d;
  }

  deleteRange(index, length) {
    const d = new Delta().retain(index);
    this.deleteInto(d, index, length);
    this.applyLocal(d.chop(), { index, length: 0 });
  }

  insertNewline(sel) {
    const d = new Delta().retain(sel.index);
    this.deleteInto(d, sel.index, sel.length);
    const text = this.doc.text();
    const end = text.indexOf('\n', sel.index + sel.length);
    const header = this.attrsAt(end).header;
    const atEnd = end === sel.index + sel.length;
    if (header && atEnd) d.insert('\n', { header }).retain(1, { header: null });
    else d.insert('\n', header ? { header } : undefined);
    this.applyLocal(d.chop(), { index: sel.index + 1, length: 0 });
  }

  // ----- Formatierung ----------------------------------------------------------
  format(sel = this.getSel() || this.lastSel) {
    const out = { bold: false, italic: false, underline: false, header: 0 };
    if (!sel || !this.rich) return out;
    const text = this.doc.text();
    out.header = this.attrsAt(text.indexOf('\n', sel.index)).header || 0;
    if (sel.length === 0) {
      const a = this.typingAttrs(sel.index) || {};
      for (const k of INLINE) out[k] = !!a[k];
      return out;
    }
    const all = { bold: true, italic: true, underline: true };
    let any = false;
    for (const op of this.doc.slice(sel.index, sel.index + sel.length).ops) {
      if (op.insert.replace(/\n/g, '') === '') continue;
      any = true;
      for (const k of INLINE) if (!(op.attributes && op.attributes[k])) all[k] = false;
    }
    if (any) Object.assign(out, all);
    return out;
  }

  inlineDelta(index, length, attrs) {
    const d = new Delta().retain(index);
    for (const op of this.doc.slice(index, index + length).ops) {
      let start = 0;
      const t = op.insert;
      for (let i = 0; i <= t.length; i++) {
        if (i === t.length || t[i] === '\n') {
          if (i > start) d.retain(i - start, attrs);
          if (i < t.length) d.retain(1);
          start = i + 1;
        }
      }
    }
    return d.chop();
  }

  lineDelta(index, length, header) {
    const text = this.doc.text();
    const d = new Delta();
    let last = 0;
    const end = text.indexOf('\n', index + length);
    for (let p = text.indexOf('\n', index); p !== -1 && p <= end; p = text.indexOf('\n', p + 1)) {
      d.retain(p - last).retain(1, { header: header || null });
      last = p + 1;
    }
    return d.chop();
  }

  toggle(name) {
    if (!this.rich || this.readOnly) return;
    const sel = this.getSel() || this.lastSel;
    if (!sel) return;
    const on = !this.format(sel)[name];
    if (sel.length === 0) {
      this.pending = { ...(this.pending || {}), [name]: on ? true : null };
      this.pendingAt = sel.index;
      if (this.opts.onFormatState) this.opts.onFormatState(this);
      return;
    }
    this.applyLocal(this.inlineDelta(sel.index, sel.length, { [name]: on ? true : null }), sel);
  }

  setHeader(level) {
    if (!this.rich || this.readOnly) return;
    const sel = this.getSel() || this.lastSel;
    if (!sel) return;
    this.applyLocal(this.lineDelta(sel.index, sel.length, level), sel);
  }

  clearFormat() {
    if (!this.rich || this.readOnly) return;
    const sel = this.getSel() || this.lastSel;
    if (!sel) return;
    this.pending = null;
    const d = this.inlineDelta(sel.index, sel.length, { bold: null, italic: null, underline: null })
      .compose(this.lineDelta(sel.index, sel.length, 0));
    if (d.ops.length) this.applyLocal(d, sel);
  }

  // ----- Ereignisse ------------------------------------------------------------
  bind() {
    const el = this.el;
    el.addEventListener('focus', () => {
      TextField.active = this;
      if (this.opts.onFocus) this.opts.onFocus(this);
    });
    el.addEventListener('blur', () => {
      if (TextField.active === this) TextField.active = null;
      if (!this.composing) this.sync(true);
      if (this.opts.onSelect) this.opts.onSelect(null, this);
      if (this.opts.onBlur) this.opts.onBlur(this);
    });
    el.addEventListener('compositionstart', () => { this.composing = true; });
    el.addEventListener('compositionend', () => { this.composing = false; this.sync(true); });
    el.addEventListener('input', () => this.sync(!this.composing));
    el.addEventListener('beforeinput', e => this.onBeforeInput(e));
    el.addEventListener('keydown', e => this.onKeyDown(e));
    el.addEventListener('paste', e => this.onPaste(e));
    el.addEventListener('drop', e => {
      if (this.readOnly) return;
      const files = [...(e.dataTransfer && e.dataTransfer.files || [])].filter(f => f.type.startsWith('image/'));
      if (files.length && this.opts.onFiles) { e.preventDefault(); e.stopPropagation(); this.opts.onFiles(files); return; }
      e.preventDefault();
      const text = e.dataTransfer && e.dataTransfer.getData('text/plain');
      const sel = this.getSel();
      if (text && sel) this.insertText(text, sel, true);
    });
    el.addEventListener('dragover', e => { if (!this.readOnly) e.preventDefault(); });
    el.addEventListener('click', e => {
      const a = e.target.closest && e.target.closest('a[href]');
      if (a && (e.ctrlKey || e.metaKey)) window.open(a.href, '_blank', 'noopener');
    });
  }

  onSelectionChange() {
    if (this.composing) return;
    const sel = this.getSel();
    if (!sel) return;
    const prev = this.lastSel;
    if (prev && prev.index === sel.index && prev.length === sel.length) return;
    this.lastSel = sel;
    // vorgemerkte Formatierung nur verwerfen, wenn der Cursor wirklich woanders hin bewegt wurde
    if (this.pending && (sel.length || sel.index !== this.pendingAt)) this.pending = null;
    if (this.opts.onSelect) this.opts.onSelect(sel, this);
  }

  onKeyDown(e) {
    if (this.opts.onKeyDown && this.opts.onKeyDown(e, this)) { e.preventDefault(); return; }
    if (e.isComposing || e.keyCode === 229) return;
    if (e.key === 'Enter' && !this.rich && !this.readOnly) {
      e.preventDefault();
      this.sync(true);
      if (this.opts.onEnter) this.opts.onEnter(this, this.getSel());
    }
  }

  onPaste(e) {
    if (this.readOnly) { e.preventDefault(); return; }
    const cd = e.clipboardData;
    if (!cd) return;
    const files = [...cd.files || []].filter(f => f.type.startsWith('image/'));
    if (files.length && this.opts.onFiles) {
      e.preventDefault();
      this.opts.onFiles(files);
      return;
    }
    const text = cd.getData('text/plain');
    e.preventDefault();
    if (!text) return;
    this.sync(true);
    const sel = this.getSel();
    if (!sel) return;
    if (this.opts.onPasteText && this.opts.onPasteText(text, sel, this)) return;
    this.insertText(text, sel, true);
  }

  onBeforeInput(e) {
    if (this.readOnly) { e.preventDefault(); return; }
    const t = e.inputType;
    if (t === 'historyUndo' || t === 'historyRedo') {
      e.preventDefault();
      const fn = t === 'historyUndo' ? this.opts.onUndo : this.opts.onRedo;
      if (fn) fn();
      return;
    }
    if (t && t.startsWith('format')) {
      e.preventDefault();
      if (t === 'formatBold') this.toggle('bold');
      else if (t === 'formatItalic') this.toggle('italic');
      else if (t === 'formatUnderline') this.toggle('underline');
      return;
    }
    if (this.composing || t === 'insertCompositionText') return;
    const sel = this.getSel();
    if (!sel) return;
    const text = this.doc.text();

    if (t === 'insertParagraph' || t === 'insertLineBreak') {
      e.preventDefault();
      if (this.rich) this.insertNewline(sel);
      else if (this.opts.onEnter) { this.sync(true); this.opts.onEnter(this, this.getSel()); }
      return;
    }
    if (t === 'insertFromPaste' || t === 'insertFromPasteAsQuotation' || t === 'insertFromDrop' || t === 'insertFromYank') {
      e.preventDefault();
      const data = (e.dataTransfer && e.dataTransfer.getData('text/plain')) || e.data || '';
      if (data) this.insertText(data, sel, true);
      return;
    }
    if (t === 'insertText' || t === 'insertReplacementText') {
      if (sel.length > 0 || this.pending) {
        const data = e.data != null ? e.data : (e.dataTransfer && e.dataTransfer.getData('text/plain')) || '';
        if (data) { e.preventDefault(); this.insertText(data, sel); }
      }
      return;
    }
    if (t && t.startsWith('delete')) {
      if (sel.length > 0) { e.preventDefault(); this.deleteRange(sel.index, sel.length); return; }
      const backward = t.includes('Backward');
      if (backward) {
        const atLineStart = sel.index === 0 || text[sel.index - 1] === '\n';
        if (!atLineStart) return;
        e.preventDefault();
        const header = this.rich && this.attrsAt(text.indexOf('\n', sel.index)).header;
        if (header) { this.applyLocal(this.lineDelta(sel.index, 0, 0), sel); return; }
        if (sel.index === 0) { if (this.opts.onBackspaceStart) { this.sync(true); this.opts.onBackspaceStart(this); } return; }
        // Zeilen zusammenführen, Formatierung der oberen Zeile behalten
        this.deleteRange(sel.index - 1, 1);
        return;
      }
      const atLineEnd = sel.index >= this.length || text[sel.index] === '\n';
      if (!atLineEnd) return;
      e.preventDefault();
      if (sel.index >= this.length) { if (this.opts.onDeleteEnd) { this.sync(true); this.opts.onDeleteEnd(this); } return; }
      this.deleteRange(sel.index, 1);
    }
  }
}

// Eine gemeinsame selectionchange-Behandlung für alle Felder
document.addEventListener('selectionchange', () => {
  if (TextField.active) TextField.active.onSelectionChange();
});
