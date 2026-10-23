/* noteeditor.js – Bearbeitungsansicht einer Notiz (Modal und Eingabefeld „Notiz schreiben…“) */
import { Delta, invertChange, composeChanges, xform, transformIndex, sortItems } from './delta.js';
import { TextField } from './editor.js';
import { rid } from './sync.js';
import { h, icon, iconBtn, setIcon, avatar, userColor, openPopover, closePopover, menu, captureLayout, playLayout, throttle, fmtDate, fmtDateLong, plural } from './dom.js';
import { state, app, setting, hintFor } from './state.js';

// Positionen erhalten einen kleinen Zufallsversatz, damit gleichzeitig angelegte Elemente
// verschiedener Nutzer nie dieselbe Position haben (sonst entschiede nur die ID die Reihenfolge)
const jitter = () => (Math.random() - 0.5) * 0.4;
const between = (a, b) => (a == null && b == null ? 1 + jitter() : a == null ? b - 1 + jitter() : b == null ? a + 1 + jitter()
  : (a + b) / 2 + (b - a) * jitter());

/** Position direkt hinter den Änderungen eines Deltas (für den Cursor nach Rückgängig). */
function caretAfter(ops) {
  let pos = 0;
  let caret = 0;
  for (const op of ops) {
    if (op.retain) pos += op.retain;
    else if (op.insert) { pos += op.insert.length; caret = pos; } else if (op.delete) caret = pos;
  }
  return caret;
}

export class NoteEditor {
  /**
   * ns: NoteState, meta: Notiz-Ansicht bzw. Entwurf, opts: {mode: 'modal'|'composer', actions, readOnly}
   */
  constructor(ns, meta, opts) {
    this.ns = ns;
    this.meta = meta;
    this.opts = opts;
    this.mode = opts.mode;
    this.readOnly = !!opts.readOnly;
    this.actions = opts.actions || {};
    this.undoStack = [];
    this.redoStack = [];
    this.itemFields = new Map();
    this.itemRows = new Map();
    this.doneOpen = true;
    this.cursors = new Map();   // connId → {user, f, i, l, shownAt}
    this.viewers = new Map();   // connId → user
    this.activeField = null;
    this.cursorDirty = false;
    this.formatOpen = false;
    this.createdIds = new Set();   // in dieser Sitzung angelegte Listenelemente
    this.build();
    this.offNs = ns.on(ev => this.onNoteEvent(ev));
    this.flushFn = () => this.flush();
    ns.beforeRemote.add(this.flushFn);
    this.sendCursor = throttle(() => this.doSendCursor(), 80);
    this.onResize = () => this.renderCursors();
    window.addEventListener('resize', this.onResize);
  }

  destroy() {
    this.flush();
    // Selbst angelegte, leer gebliebene Listenelemente beim Schließen entfernen (wie im Original)
    const empty = this.ns.doc.items.filter(i => this.createdIds.has(i.id) && !i.text.trim());
    if (empty.length && !this.readOnly && this.mode === 'modal') this.ns.applyLocal(empty.map(i => ({ k: 'item_del', id: i.id })));
    this.offNs();
    this.ns.beforeRemote.delete(this.flushFn);
    window.removeEventListener('resize', this.onResize);
    if (this.mode === 'modal' && state.online && app.conn) app.conn.send({ t: 'close', id: this.ns.id });
  }

  // =====================================================================
  // Aufbau
  // =====================================================================
  build() {
    this.root = h('div', { class: `editor is-${this.mode}` });
    this.imagesEl = h('div', { class: 'ed-images' });
    this.title = new TextField({
      placeholder: 'Titel', className: 'ed-title', label: 'Titel', readOnly: this.readOnly,
      onChange: (d, info) => this.onFieldChange('title', d, info),
      onEnter: () => this.focusBodyStart(),
      onKeyDown: (e, f) => this.navKey(e, f, 'title'),
      onSelect: (sel, f) => this.onSelect(sel, f, 'title'),
      onFocus: f => this.onFocusField(f, 'title'),
      onRender: () => this.scheduleCursors(),
      onUndo: () => this.undo(), onRedo: () => this.redo(),
      onFiles: files => this.addImages(files),
    });
    this.pinBtn = iconBtn('push_pin_o', 'Notiz anheften', () => this.actions.pin && this.actions.pin(), 'pin-btn');
    this.body = new TextField({
      rich: true, placeholder: 'Notiz schreiben…', className: 'ed-text', label: 'Notiz', readOnly: this.readOnly,
      onChange: (d, info) => this.onFieldChange('body', d, info),
      onKeyDown: (e, f) => this.navKey(e, f, 'body'),
      onSelect: (sel, f) => this.onSelect(sel, f, 'body'),
      onFocus: f => this.onFocusField(f, 'body'),
      onRender: () => this.scheduleCursors(),
      onUndo: () => this.undo(), onRedo: () => this.redo(),
      onFiles: files => this.addImages(files),
      onFormatState: () => this.updateFormatBar(),
      onBackspaceStart: () => this.title.focus(),
    });
    this.listEl = h('div', { class: 'ed-list' });
    this.doneEl = h('div', { class: 'ed-done' });
    this.addInput = h('input', { type: 'text', class: 'ed-add-input', placeholder: 'Listenelement', 'aria-label': 'Neues Listenelement', maxlength: '5000' });
    this.addInput.addEventListener('input', () => this.onAddInput());
    this.addInput.addEventListener('keydown', e => {
      if (e.key === 'ArrowUp') { const rows = this.openRows(); if (rows.length) { e.preventDefault(); this.itemFields.get(rows[rows.length - 1].id).focus(); } }
      if (e.key === 'Backspace' && !this.addInput.value) { const rows = this.openRows(); if (rows.length) { e.preventDefault(); this.itemFields.get(rows[rows.length - 1].id).focus(); } }
    });
    this.addRow = h('div', { class: 'ed-add' }, icon('add', 20), this.addInput);
    this.bodyWrap = h('div', { class: 'ed-body' });
    this.chipsEl = h('div', { class: 'ed-chips' });
    this.metaEl = h('div', { class: 'ed-meta' });
    this.cursorLayer = h('div', { class: 'rc-layer', 'aria-hidden': 'true' });
    this.scroll = h('div', { class: 'editor-scroll' },
      this.imagesEl,
      h('div', { class: 'ed-head' }, this.title.el, this.readOnly ? null : this.pinBtn),
      this.bodyWrap, this.chipsEl, this.metaEl, this.cursorLayer);
    this.formatBar = h('div', { class: 'ed-formatbar', role: 'toolbar', 'aria-label': 'Formatierung' });
    this.toolbar = h('div', { class: 'ed-toolbar' });
    this.root.append(this.scroll, this.formatBar, this.toolbar);
    this.root.addEventListener('dragover', e => { if (!this.readOnly && [...(e.dataTransfer.types || [])].includes('Files')) { e.preventDefault(); this.root.classList.add('drop'); } });
    this.root.addEventListener('dragleave', e => { if (!this.root.contains(e.relatedTarget)) this.root.classList.remove('drop'); });
    this.root.addEventListener('drop', e => {
      this.root.classList.remove('drop');
      const files = [...(e.dataTransfer.files || [])].filter(f => f.type.startsWith('image/'));
      if (files.length && !this.readOnly) { e.preventDefault(); this.addImages(files); }
    });
    this.loadFields();
    this.refreshMeta();
    this.renderToolbar();
  }

  loadFields() {
    const d = this.ns.doc;
    this.title.setText(d.title);
    this.body.setDoc(d.body);
    this.renderBody();
  }

  renderBody() {
    const isList = this.ns.doc.is_list;
    this.root.classList.toggle('is-checklist', isList);
    if (isList) {
      this.bodyWrap.replaceChildren(this.listEl, this.doneEl);
      this.renderList();
    } else {
      this.bodyWrap.replaceChildren(this.body.el);
    }
    this.renderToolbar();
    this.scheduleCursors();
  }

  // =====================================================================
  // Checklisten
  // =====================================================================
  openRows() {
    const items = this.ns.doc.items;
    return setting('checkedBottom') ? items.filter(i => !i.checked) : items;
  }

  itemField(it) {
    let f = this.itemFields.get(it.id);
    if (f) return f;
    const id = it.id;
    f = new TextField({
      className: 'ed-item-text', label: 'Listenelement', readOnly: this.readOnly,
      onChange: (d, info) => this.onFieldChange('item:' + id, d, info),
      onEnter: (field, sel) => this.splitItem(id, sel),
      onBackspaceStart: () => this.mergeIntoPrev(id),
      onDeleteEnd: () => this.mergeNext(id),
      onKeyDown: (e, field) => this.navKey(e, field, 'item:' + id),
      onSelect: (sel, field) => this.onSelect(sel, field, 'item:' + id),
      onFocus: field => this.onFocusField(field, 'item:' + id),
      onPasteText: (text, sel) => this.pasteLines(id, text, sel),
      onRender: () => this.scheduleCursors(),
      onUndo: () => this.undo(), onRedo: () => this.redo(),
      onFiles: files => this.addImages(files),
    });
    f.setText(it.text);
    this.itemFields.set(id, f);
    return f;
  }

  itemRow(it) {
    let row = this.itemRows.get(it.id);
    const f = this.itemField(it);
    if (!row) {
      const id = it.id;
      const handle = h('span', { class: 'ed-drag', title: 'Ziehen zum Verschieben', 'aria-hidden': 'true' }, icon('drag', 20));
      const check = h('button', { type: 'button', class: 'ed-check', onclick: () => this.toggleItem(id) });
      const del = iconBtn('close', 'Element löschen', () => this.deleteItem(id), 'ed-item-del', 20);
      row = h('div', { class: 'ed-item', dataset: { key: id } }, handle, check, f.el, this.readOnly ? null : del);
      row.check = check;
      if (!this.readOnly) this.enableItemDrag(row, handle, id);
      this.itemRows.set(id, row);
    }
    row.classList.toggle('checked', it.checked);
    row.check.replaceChildren(icon(it.checked ? 'check_box' : 'check_box_o', 20));
    row.check.title = it.checked ? 'Als nicht erledigt markieren' : 'Als erledigt markieren';
    row.check.setAttribute('aria-label', row.check.title);
    row.check.disabled = this.readOnly;
    if (f.text() !== it.text) f.setText(it.text);
    return row;
  }

  renderList() {
    const doc = this.ns.doc;
    sortItems(doc.items);
    const focused = TextField.active && [...this.itemFields.entries()].find(([, f]) => f === TextField.active);
    const sel = focused ? focused[1].getSel() : null;
    const addFocused = document.activeElement === this.addInput;
    const before = captureLayout(this.bodyWrap, '.ed-item');
    const ids = new Set(doc.items.map(i => i.id));
    for (const id of [...this.itemFields.keys()]) if (!ids.has(id)) { this.itemFields.delete(id); this.itemRows.delete(id); }

    const bottom = setting('checkedBottom');
    const open = bottom ? doc.items.filter(i => !i.checked) : doc.items;
    const done = bottom ? doc.items.filter(i => i.checked) : [];
    this.listEl.replaceChildren(...open.map(it => this.itemRow(it)), this.readOnly ? '' : this.addRow);
    this.doneEl.replaceChildren();
    if (done.length) {
      const toggle = h('button', {
        type: 'button', class: 'ed-done-toggle' + (this.doneOpen ? ' open' : ''), 'aria-expanded': String(this.doneOpen),
        onclick: () => { this.doneOpen = !this.doneOpen; this.renderList(); },
      }, icon('expand_more', 20), h('span', { text: plural(done.length, 'erledigtes Element', 'erledigte Elemente') }));
      this.doneEl.append(h('div', { class: 'ed-divider' }), toggle);
      if (this.doneOpen) this.doneEl.append(h('div', { class: 'ed-list done' }, done.map(it => this.itemRow(it))));
    }
    if (focused) {
      const f = this.itemFields.get(focused[0]);
      if (f && f.el.isConnected) { f.el.focus({ preventScroll: true }); if (sel) f.setSel(sel.index, sel.length); }
    } else if (addFocused) this.addInput.focus({ preventScroll: true });
    playLayout(this.bodyWrap, '.ed-item', before, { exit: false });
    this.scheduleCursors();
  }

  newItemPos() {
    const items = this.ns.doc.items;
    if (!items.length) return 1;
    if (setting('addItemsBottom')) return Math.max(...items.map(i => i.pos)) + 1 + Math.abs(jitter());
    return Math.min(...items.map(i => i.pos)) - 1 - Math.abs(jitter());
  }

  onAddInput() {
    const text = this.addInput.value.replace(/\n/g, ' ');
    if (!text) return;
    this.addInput.value = '';
    const id = rid();
    this.commit([{ k: 'item_add', id, pos: this.newItemPos(), checked: false, text }], { structural: true });
    const f = this.itemFields.get(id);
    if (f) f.focus();
  }

  neighbors(id) {
    const list = this.openRows().some(i => i.id === id) ? this.openRows() : this.ns.doc.items.filter(i => i.checked);
    const i = list.findIndex(x => x.id === id);
    return { list, i, prev: list[i - 1], next: list[i + 1], item: list[i] };
  }

  splitItem(id, sel) {
    const { item, next } = this.neighbors(id);
    if (!item) return;
    const f = this.itemFields.get(id);
    const at = sel ? sel.index : f.length;
    const tail = item.text.slice(at + (sel ? sel.length : 0));
    const newId = rid();
    const change = [];
    if (item.text.length - at > 0) change.push({ k: 'text', f: 'item:' + id, d: new Delta().retain(at).delete(item.text.length - at).ops });
    change.push({ k: 'item_add', id: newId, pos: between(item.pos, next && next.pos), checked: item.checked, text: tail });
    this.commit(change, { structural: true });
    const nf = this.itemFields.get(newId);
    if (nf) nf.focus(0);
  }

  mergeIntoPrev(id) {
    const { prev, item, list } = this.neighbors(id);
    if (!item) return;
    if (!prev) {
      if (!item.text) { this.commit([{ k: 'item_del', id }], { structural: true }); const n = list[1]; if (n) this.itemFields.get(n.id).focus(0); else this.addInput.focus(); }
      else this.title.focus();
      return;
    }
    const change = [];
    if (item.text) change.push({ k: 'text', f: 'item:' + prev.id, d: new Delta().retain(prev.text.length).insert(item.text).ops });
    const caret = prev.text.length;
    change.push({ k: 'item_del', id });
    this.commit(change, { structural: true });
    const pf = this.itemFields.get(prev.id);
    if (pf) pf.focus(caret);
  }

  mergeNext(id) {
    const { next, item } = this.neighbors(id);
    if (!item || !next) return;
    const caret = item.text.length;
    const change = [];
    if (next.text) change.push({ k: 'text', f: 'item:' + id, d: new Delta().retain(item.text.length).insert(next.text).ops });
    change.push({ k: 'item_del', id: next.id });
    this.commit(change, { structural: true });
    const f = this.itemFields.get(id);
    if (f) f.focus(caret);
  }

  pasteLines(id, text, sel) {
    const lines = text.replace(/\r\n?/g, '\n').split('\n').filter(l => l.trim() !== '');
    if (lines.length < 2) return false;
    const { item, next } = this.neighbors(id);
    const change = [{ k: 'text', f: 'item:' + id, d: new Delta().retain(sel.index).delete(sel.length).insert(lines[0]).ops }];
    let pos = item.pos;
    const step = ((next ? next.pos : item.pos + lines.length) - item.pos) / lines.length;
    let lastId = id;
    for (const line of lines.slice(1)) {
      pos += step;
      lastId = rid();
      change.push({ k: 'item_add', id: lastId, pos, checked: item.checked, text: line });
    }
    this.commit(change, { structural: true });
    const f = this.itemFields.get(lastId);
    if (f) f.focus();
    return true;
  }

  toggleItem(id) {
    if (this.readOnly) return;
    const it = this.ns.doc.items.find(i => i.id === id);
    if (!it) return;
    this.commit([{ k: 'item_set', id, checked: !it.checked }], { structural: true });
  }

  deleteItem(id) {
    this.commit([{ k: 'item_del', id }], { structural: true });
  }

  enableItemDrag(row, handle, id) {
    handle.addEventListener('mousedown', () => { row.draggable = true; });
    row.addEventListener('dragstart', e => {
      if (!row.draggable) { e.preventDefault(); return; }
      this.dragItem = id;
      row.classList.add('dragging');
      e.dataTransfer.effectAllowed = 'move';
      try { e.dataTransfer.setData('text/x-notizen-item', id); } catch { /* egal */ }
    });
    row.addEventListener('dragend', () => {
      row.draggable = false;
      row.classList.remove('dragging');
      this.dragItem = null;
      this.bodyWrap.querySelectorAll('.drop-above, .drop-below').forEach(el => el.classList.remove('drop-above', 'drop-below'));
    });
    row.addEventListener('dragover', e => {
      if (!this.dragItem || this.dragItem === id) return;
      e.preventDefault();
      const r = row.getBoundingClientRect();
      const above = e.clientY < r.top + r.height / 2;
      row.classList.toggle('drop-above', above);
      row.classList.toggle('drop-below', !above);
    });
    row.addEventListener('dragleave', () => row.classList.remove('drop-above', 'drop-below'));
    row.addEventListener('drop', e => {
      if (!this.dragItem || this.dragItem === id) return;
      e.preventDefault();
      const above = row.classList.contains('drop-above');
      row.classList.remove('drop-above', 'drop-below');
      const moving = this.dragItem;
      const { list, i } = this.neighbors(id);
      const others = list.filter(x => x.id !== moving);
      const j = others.findIndex(x => x.id === id);
      const a = above ? others[j - 1] : others[j];
      const b = above ? others[j] : others[j + 1];
      const target = this.ns.doc.items.find(x => x.id === id);
      const change = [{ k: 'item_set', id: moving, pos: between(a && a.pos, b && b.pos) }];
      const mv = this.ns.doc.items.find(x => x.id === moving);
      if (mv && target && mv.checked !== target.checked) change[0].checked = target.checked;
      void i;
      this.commit(change, { structural: true });
    });
  }

  // =====================================================================
  // Änderungen
  // =====================================================================
  onFieldChange(f, delta, info) {
    const change = [{ k: 'text', f, d: delta.ops }];
    const inverse = invertChange(this.ns.doc, change);
    this.ns.applyLocal(change);
    this.record(inverse, f, true);
    this.transformCursors(change);
    this.markEdited();
    this.sendCursor();
    void info;
  }

  /** Strukturelle Änderung (Listenelemente, Umwandlung) lokal ausführen und senden. */
  commit(change, { structural = false, record = true } = {}) {
    if (!change.length || this.readOnly) return;
    this.flush();
    const inverse = invertChange(this.ns.doc, change);
    this.ns.applyLocal(change);
    for (const sub of change) if (sub.k === 'item_add') this.createdIds.add(sub.id);
    if (record) this.record(inverse, null, false);
    this.applyToFields(change, false);
    this.transformCursors(change);
    this.markEdited();
    void structural;
  }

  /** Überträgt eine bereits im Dokument angewendete Änderung auf die Eingabefelder. */
  applyToFields(change, remote) {
    let structural = false;
    let bodySwitch = false;
    for (const sub of change) {
      if (sub.k === 'text') {
        const d = new Delta(sub.d);
        if (sub.f === 'title') this.title.apply(d, remote ? 'keep' : null);
        else if (sub.f === 'body') this.body.apply(d, remote ? 'keep' : null);
        else {
          const f = this.itemFields.get(sub.f.slice(5));
          if (f) f.apply(d, remote ? 'keep' : null);
        }
      } else if (sub.k === 'set') bodySwitch = true;
      else structural = true;
    }
    if (bodySwitch) this.renderBody();
    else if (structural && this.ns.doc.is_list) this.renderList();
  }

  flush() {
    this.title.flush();
    if (!this.ns.doc.is_list) this.body.flush();
    for (const f of this.itemFields.values()) if (f.el.isConnected) f.flush();
  }

  record(inverse, field, typing) {
    if (!inverse.length) return;
    const now = Date.now();
    const last = this.undoStack[this.undoStack.length - 1];
    if (typing && last && last.typing && last.field === field && now - last.time < 1500) {
      last.inverse = composeChanges(inverse, last.inverse);
      last.time = now;
    } else {
      this.undoStack.push({ inverse, field, typing, time: now });
      if (this.undoStack.length > 300) this.undoStack.shift();
    }
    this.redoStack = [];
    this.updateUndoButtons();
  }

  undo() { this.history(this.undoStack, this.redoStack); }
  redo() { this.history(this.redoStack, this.undoStack); }

  history(from, to) {
    if (this.readOnly) return;
    this.flush();
    const entry = from.pop();
    if (!entry) return;
    const change = entry.inverse;
    const back = invertChange(this.ns.doc, change);
    this.ns.applyLocal(change);
    to.push({ inverse: back, field: entry.field, typing: false, time: 0 });
    this.applyToFields(change, false);
    this.transformCursors(change);
    // Cursor an die Stelle der Änderung setzen
    const text = change.filter(s => s.k === 'text').pop();
    if (text) {
      const f = this.fieldFor(text.f);
      if (f && f.el.isConnected) { f.el.focus({ preventScroll: true }); f.setSel(caretAfter(text.d)); }
    }
    this.markEdited();
    this.updateUndoButtons();
  }

  onNoteEvent(ev) {
    if (ev.type === 'remote') {
      this.applyToFields(ev.change, true);
      for (const stack of [this.undoStack, this.redoStack]) {
        for (const e of stack) e.inverse = xform(e.inverse, ev.change)[0];
      }
      this.transformCursors(ev.change);
      this.markEdited(ev.by);
    } else if (ev.type === 'reset') {
      this.undoStack = [];
      this.redoStack = [];
      this.itemFields.clear();
      this.itemRows.clear();
      this.loadFields();
      this.updateUndoButtons();
    } else if (ev.type === 'synced' && this.cursorDirty) {
      this.doSendCursor();
    }
  }

  markEdited(by) {
    if (this.meta) {
      this.meta.updated_at = Math.floor(Date.now() / 1000);
      if (by !== undefined) this.meta.updated_by = by;
    }
    this.renderMetaLine();
    if (app.noteChanged && this.ns.id) app.noteChanged(this.ns.id);
  }

  // =====================================================================
  // Navigation, Fokus, Formatierung
  // =====================================================================
  fieldFor(f) {
    if (f === 'title') return this.title;
    if (f === 'body') return this.ns.doc.is_list ? null : this.body;
    return this.itemFields.get(f.slice(5)) || null;
  }

  focusBodyStart() {
    if (this.ns.doc.is_list) {
      const rows = this.openRows();
      if (rows.length) this.itemFields.get(rows[0].id).focus(0); else this.addInput.focus();
    } else this.body.focus(0);
  }

  focusStart() {
    if (this.readOnly) return;
    if (this.ns.doc.is_list) { if (!this.ns.doc.items.length) this.addInput.focus(); else this.focusBodyStart(); } else this.body.focus();
  }

  navKey(e, field, f) {
    if (e.isComposing) return false;
    const up = e.key === 'ArrowUp';
    const down = e.key === 'ArrowDown';
    if ((!up && !down) || e.shiftKey || e.altKey || e.ctrlKey || e.metaKey) return false;
    const sel = field.getSel();
    if (!sel || sel.length) return false;
    if (!this.atVisualEdge(field, up)) return false;
    let target = null;
    if (f === 'title') { if (down) { this.focusBodyStart(); return true; } return false; }
    if (f === 'body') { if (up) target = this.title; } else {
      const id = f.slice(5);
      const rows = [...this.bodyWrap.querySelectorAll('.ed-item')].map(r => r.dataset.key);
      const i = rows.indexOf(id);
      const nextId = rows[i + (down ? 1 : -1)];
      if (nextId) target = this.itemFields.get(nextId);
      else if (up) target = this.title;
      else if (down && !this.readOnly) { this.addInput.focus(); return true; }
    }
    if (!target) return false;
    target.focus(up ? target.length : 0);
    return true;
  }

  atVisualEdge(field, up) {
    const sel = field.getSel();
    if (!sel) return false;
    if (up && sel.index === 0) return true;
    if (!up && sel.index >= field.length) return true;
    const rects = field.rects(sel.index, 0);
    const r = rects[0];
    const box = field.el.getBoundingClientRect();
    if (!r) return false;
    const lh = r.height || 20;
    return up ? r.top - box.top < lh * 0.8 : box.bottom - r.bottom < lh * 0.8;
  }

  onFocusField(field, f) {
    this.activeField = field;
    this.activeKey = f;
    this.updateFormatBar();
  }

  onSelect(sel, field, f) {
    if (sel) { this.activeField = field; this.activeKey = f; }
    this.updateFormatBar();
    if (!sel) {
      clearTimeout(this.blurTimer);
      this.blurTimer = setTimeout(() => {
        if (!TextField.active || !this.root.contains(TextField.active.el)) { this.activeKey = null; this.sendCursor(); }
      }, 120);
      return;
    }
    this.sendCursor();
  }

  doSendCursor() {
    if (this.mode !== 'modal' || !app.conn || !this.ns.id) return;
    if (this.ns.pending()) { this.cursorDirty = true; return; }
    this.cursorDirty = false;
    const field = this.activeKey ? this.fieldFor(this.activeKey) : null;
    const sel = field && document.activeElement === field.el ? field.getSel() : null;
    if (!sel) app.conn.send({ t: 'cursor', id: this.ns.id, f: null });
    else app.conn.send({ t: 'cursor', id: this.ns.id, f: this.activeKey, i: sel.index, l: sel.length, rev: this.ns.rev });
  }

  exec(action) {
    const f = this.activeField;
    const rich = f === this.body && !this.ns.doc.is_list;
    switch (action) {
      case 'bold': case 'italic': case 'underline':
        if (rich) { f.toggle(action); this.updateFormatBar(); } return true;
      case 'h1': if (rich) { f.setHeader(1); this.updateFormatBar(); } return true;
      case 'h2': if (rich) { f.setHeader(2); this.updateFormatBar(); } return true;
      case 'normalText': if (rich) { f.setHeader(0); this.updateFormatBar(); } return true;
      case 'clearFormat': if (rich) { f.clearFormat(); this.updateFormatBar(); } return true;
      case 'undo': this.undo(); return true;
      case 'redo': this.redo(); return true;
      case 'toggleChecks': this.toggleChecks(); return true;
      default: return false;
    }
  }

  /** Text ↔ Checkliste umwandeln – als explizite Befehle, damit es konfliktfrei bleibt. */
  toggleChecks() {
    if (this.readOnly) return;
    this.flush();
    const doc = this.ns.doc;
    const bodyLen = doc.body.length();
    const change = [];
    if (!doc.is_list) {
      const lines = doc.body.text().slice(0, -1).split('\n').filter(l => l.trim() !== '');
      for (const it of doc.items) change.push({ k: 'item_del', id: it.id });
      if (bodyLen > 1) change.push({ k: 'text', f: 'body', d: [{ delete: bodyLen - 1 }] });
      lines.forEach((t, i) => change.push({ k: 'item_add', id: rid(), pos: i + 1, checked: false, text: t }));
      change.push({ k: 'set', is_list: true });
    } else {
      const ordered = setting('checkedBottom') ? [...doc.items.filter(i => !i.checked), ...doc.items.filter(i => i.checked)] : doc.items;
      const text = ordered.map(i => i.text).join('\n');
      const d = new Delta().insert(text).delete(bodyLen - 1);
      if (d.ops.length) change.push({ k: 'text', f: 'body', d: d.ops });
      for (const it of doc.items) change.push({ k: 'item_del', id: it.id });
      change.push({ k: 'set', is_list: false });
    }
    this.commit(change, { structural: true });
    this.focusStart();
  }

  // =====================================================================
  // Live-Cursor anderer Nutzer (MS-Office-Stil)
  // =====================================================================
  remoteCursor(msg) {
    if (!msg.f) { this.cursors.delete(msg.conn); this.renderCursors(); return; }
    let i = msg.i;
    let end = msg.i + msg.l;
    // Position bezieht sich auf den Serverstand → durch eigene ausstehende Änderungen verschieben
    for (const pend of [this.ns.inflight && this.ns.inflight.change, this.ns.buffer]) {
      if (!pend) continue;
      i = transformIndex(pend, msg.f, i);
      end = transformIndex(pend, msg.f, end);
    }
    const prev = this.cursors.get(msg.conn);
    this.cursors.set(msg.conn, { user: msg.user, f: msg.f, i, l: Math.max(0, end - i), shownAt: prev && prev.f === msg.f ? prev.shownAt : Date.now(), movedAt: Date.now() });
    this.viewers.set(msg.conn, msg.user);
    this.renderCursors();
    this.renderPresence();
  }

  presence(msg) {
    if (msg.open) this.viewers.set(msg.conn, msg.user);
    else { this.viewers.delete(msg.conn); this.cursors.delete(msg.conn); this.renderCursors(); }
    this.renderPresence();
  }

  transformCursors(change) {
    if (!this.cursors.size) return;
    for (const c of this.cursors.values()) {
      const end = transformIndex(change, c.f, c.i + c.l);
      c.i = transformIndex(change, c.f, c.i);
      c.l = Math.max(0, end - c.i);
      if (change.some(s => s.k === 'item_del' && 'item:' + s.id === c.f)) c.gone = true;
    }
    this.scheduleCursors();
  }

  scheduleCursors() {
    if (this.cursorFrame) return;
    this.cursorFrame = requestAnimationFrame(() => { this.cursorFrame = null; this.renderCursors(); });
  }

  renderCursors() {
    const layer = this.cursorLayer;
    layer.replaceChildren();
    if (!this.cursors.size || !this.root.isConnected) return;
    const base = this.scroll.getBoundingClientRect();
    const ox = -base.left + this.scroll.scrollLeft;
    const oy = -base.top + this.scroll.scrollTop;
    for (const c of this.cursors.values()) {
      if (c.gone) continue;
      const field = this.fieldFor(c.f);
      if (!field || !field.el.isConnected) continue;
      const i = Math.min(c.i, field.length);
      const l = Math.min(c.l, field.length - i);
      const color = userColor(c.user);
      if (l > 0) {
        for (const r of field.rects(i, l)) {
          if (!r.width) continue;
          layer.append(h('div', { class: 'rc-sel', style: { left: `${r.left + ox}px`, top: `${r.top + oy}px`, width: `${r.width}px`, height: `${r.height}px`, background: color } }));
        }
      }
      const rects = field.rects(i + l, 0);
      const r = rects[rects.length - 1];
      if (!r) continue;
      const fresh = Date.now() - c.movedAt < 2500;
      const flag = h('div', { class: 'rc-flag' + (fresh ? ' named' : ''), style: { background: color } },
        avatar(c.user, 16, 'rc-avatar'), h('span', { class: 'rc-name', text: c.user.username }));
      const caret = h('div', { class: 'rc-caret', title: c.user.username, style: { left: `${r.left + ox}px`, top: `${r.top + oy}px`, height: `${r.height || 20}px`, background: color } }, flag);
      layer.append(caret);
      if (fresh) {
        clearTimeout(c.nameTimer);
        c.nameTimer = setTimeout(() => this.scheduleCursors(), 2600);
      }
    }
  }

  renderPresence() {
    // Wer die Notiz gerade geöffnet hat, bekommt einen farbigen Ring um sein Profilbild
    const live = new Set([...this.viewers.values()].map(u => u.id));
    for (const el of this.chipsEl.querySelectorAll('.collab-avatars .avatar')) {
      const uid = Number(el.dataset.uid);
      const on = live.has(uid);
      el.classList.toggle('live', on);
      el.style.setProperty('--ring', on ? userColor(state.users.get(uid) || { username: el.title }) : 'transparent');
      el.title = `${el.dataset.name}${el.dataset.owner ? ' (Inhaber)' : ''}${on ? ' – bearbeitet gerade' : ''}`;
    }
    const extra = [...this.viewers.values()].filter(u => u.id !== state.user.id && !this.chipsEl.querySelector(`.avatar[data-uid="${u.id}"]`));
    this.presenceEl.replaceChildren(...[...new Map(extra.map(u => [u.id, u])).values()].map(u => {
      const a = avatar(u, 28, 'live');
      a.style.setProperty('--ring', userColor(u));
      a.title = `${u.username} – bearbeitet gerade`;
      return a;
    }));
  }

  // =====================================================================
  // Meta-Daten: Farbe, Bilder, Labels, Mitbearbeiter
  // =====================================================================
  refreshMeta() {
    const m = this.meta;
    this.root.dataset.color = m.color || 'default';
    if (!this.readOnly) {
      setIcon(this.pinBtn, m.pinned ? 'push_pin' : 'push_pin_o');
      this.pinBtn.title = m.pinned ? 'Notiz loslösen' : 'Notiz anheften';
      this.pinBtn.setAttribute('aria-label', this.pinBtn.title);
      this.pinBtn.classList.toggle('on', !!m.pinned);
    }
    this.renderImages();
    this.chipsEl.replaceChildren();
    const chips = app.labelChips(m.labels || [], this.readOnly ? null : id => this.actions.setLabels(m.labels.filter(x => x !== id)), false);
    const members = (m.members || []).filter(u => u.id !== state.user.id);
    const draftCollab = m.collaborators || [];
    const people = members.length ? members : draftCollab;
    this.presenceEl = h('div', { class: 'collab-avatars presence' });
    const row = h('div', { class: 'chips-row' });
    if (chips) row.append(chips);
    if (people.length) {
      row.append(h('div', { class: 'collab-avatars' }, people.map(u => {
        const a = avatar(u, 28);
        a.dataset.uid = String(u.id);
        a.dataset.name = u.username;
        if (u.role === 'owner') a.dataset.owner = '1';
        a.addEventListener('click', () => this.actions.share && this.actions.share());
        return a;
      })));
    }
    row.append(this.presenceEl);
    this.chipsEl.append(row);
    this.renderPresence();
    this.renderMetaLine();
    this.renderToolbar();
    this.scheduleCursors();
  }

  renderMetaLine() {
    const m = this.meta;
    this.metaEl.replaceChildren();
    if (this.readOnly) { this.metaEl.textContent = 'Notizen im Papierkorb können nicht bearbeitet werden'; return; }
    if (this.mode !== 'modal' || !m.updated_at) return;
    let who = '';
    if (m.updated_by && m.updated_by !== state.user.id && (m.members || []).length > 1) {
      const u = state.users.get(m.updated_by) || (m.members || []).find(x => x.id === m.updated_by);
      if (u) who = ` von ${u.username}`;
    }
    this.metaEl.textContent = `Bearbeitet${who}: ${fmtDate(m.updated_at)}`;
    this.metaEl.title = `Erstellt: ${fmtDateLong(m.created_at)}\nZuletzt bearbeitet: ${fmtDateLong(m.updated_at)}`;
  }

  renderImages() {
    const imgs = this.meta.images || [];
    this.imagesEl.replaceChildren();
    this.imagesEl.classList.toggle('hidden', !imgs.length);
    if (!imgs.length) return;
    this.imagesEl.dataset.count = String(Math.min(imgs.length, 6));
    imgs.forEach((img, idx) => {
      const src = img.url || `api/images/${img.id}`;
      const ratio = img.w && img.h ? img.w / img.h : 1.5;
      const tile = h('div', { class: 'ed-img' + (img.uploading ? ' uploading' : ''), style: { aspectRatio: imgs.length === 1 ? String(ratio) : '' } },
        h('img', { src, alt: '', loading: 'lazy', draggable: 'false', onclick: () => this.actions.viewImage && this.actions.viewImage(idx) }));
      if (!this.readOnly && !img.uploading) {
        tile.append(iconBtn('delete', 'Bild entfernen', e => { e.stopPropagation(); this.actions.removeImage(img); }, 'img-del', 20));
      }
      if (img.uploading) tile.append(h('div', { class: 'spinner' }));
      this.imagesEl.append(tile);
    });
  }

  addImages(files) {
    if (this.readOnly || !this.actions.addImages) return;
    this.actions.addImages(files);
  }

  // =====================================================================
  // Werkzeugleiste
  // =====================================================================
  updateUndoButtons() {
    if (this.undoBtn) this.undoBtn.disabled = !this.undoStack.length;
    if (this.redoBtn) this.redoBtn.disabled = !this.redoStack.length;
  }

  updateFormatBar() {
    if (!this.formatOpen) { this.formatBar.classList.remove('open'); return; }
    const isRich = !this.ns.doc.is_list;
    const fmt = isRich ? this.body.format() : {};
    const btn = (action, iconName, label, active) => {
      const b = iconBtn(iconName, `${label}${hintFor(action) ? ` (${hintFor(action)})` : ''}`, () => { if (this.activeField !== this.body) { this.body.el.focus(); } this.activeField = this.body; this.exec(action); }, active ? 'on' : '', 20);
      b.disabled = !isRich || this.readOnly;
      b.setAttribute('aria-pressed', String(!!active));
      b.addEventListener('mousedown', e => e.preventDefault()); // Auswahl im Text behalten
      return b;
    };
    const txt = (action, label, cls, active) => {
      const b = h('button', { type: 'button', class: `fmt-text ${cls} ${active ? 'on' : ''}`, title: `${label}${hintFor(action) ? ` (${hintFor(action)})` : ''}`, 'aria-pressed': String(!!active), onclick: () => { this.activeField = this.body; this.exec(action); } }, label === 'Normaler Text' ? 'Aa' : label.replace('Überschrift ', 'H'));
      b.disabled = !isRich || this.readOnly;
      b.addEventListener('mousedown', e => e.preventDefault());
      return b;
    };
    this.formatBar.replaceChildren(
      txt('h1', 'Überschrift 1', 'h1', fmt.header === 1),
      txt('h2', 'Überschrift 2', 'h2', fmt.header === 2),
      txt('normalText', 'Normaler Text', 'normal', isRich && !fmt.header),
      h('span', { class: 'fmt-sep' }),
      btn('bold', 'format_bold', 'Fett', fmt.bold),
      btn('italic', 'format_italic', 'Kursiv', fmt.italic),
      btn('underline', 'format_underlined', 'Unterstrichen', fmt.underline),
      h('span', { class: 'fmt-sep' }),
      btn('clearFormat', 'format_clear', 'Formatierung entfernen', false),
    );
    this.formatBar.classList.add('open');
  }

  renderToolbar() {
    const a = this.actions;
    const tools = h('div', { class: 'ed-tools' });
    if (this.readOnly) {
      tools.append(
        iconBtn('delete_forever', 'Endgültig löschen', () => a.deleteForever(), '', 20),
        iconBtn('restore_from_trash', 'Wiederherstellen', () => a.restore(), '', 20));
    } else {
      const fmtBtn = iconBtn('text_format', 'Formatierungsoptionen', () => {
        this.formatOpen = !this.formatOpen;
        fmtBtn.classList.toggle('on', this.formatOpen);
        this.updateFormatBar();
      }, this.formatOpen ? 'on' : '', 20);
      fmtBtn.disabled = this.ns.doc.is_list;
      if (this.ns.doc.is_list && this.formatOpen) { this.formatOpen = false; this.updateFormatBar(); }
      fmtBtn.addEventListener('mousedown', e => e.preventDefault());
      const more = [
        a.trash && { label: this.meta.role && this.meta.role !== 'owner' ? 'Notiz entfernen' : 'Notiz löschen', action: a.trash },
        a.labels !== false && { label: (this.meta.labels || []).length ? 'Label ändern' : 'Label hinzufügen', action: () => openPopover(moreBtn, app.labelPicker(() => this.meta.labels || [], ids => a.setLabels(ids))) },
        a.copy && { label: 'Kopie erstellen', action: a.copy },
        { label: this.ns.doc.is_list ? 'Kästchen ausblenden' : 'Kästchen anzeigen', action: () => this.toggleChecks(), hint: hintFor('toggleChecks') },
        a.versions && { label: 'Versionsverlauf', action: a.versions },
      ];
      const moreBtn = iconBtn('more_vert', 'Mehr', () => openPopover(moreBtn, menu(more)), '', 20);
      const shareBtn = iconBtn('person_add_o', 'Mitbearbeiter', () => a.share && a.share(), '', 20);
      const palBtn = iconBtn('palette', 'Hintergrundoptionen', () => openPopover(palBtn, app.palette(this.meta.color, c => a.setColor(c))), '', 20);
      const imgBtn = iconBtn('image_o', 'Bild hinzufügen', () => a.pickImages && a.pickImages(), '', 20);
      const archBtn = iconBtn(this.meta.archived ? 'unarchive' : 'archive', this.meta.archived ? 'Archivierung aufheben' : 'Archivieren', () => a.archive(), '', 20);
      this.undoBtn = iconBtn('undo', `Rückgängig (${hintFor('undo')})`, () => this.undo(), 'undo-btn', 20);
      this.redoBtn = iconBtn('redo', `Wiederholen (${hintFor('redo')})`, () => this.redo(), 'redo-btn', 20);
      for (const b of [this.undoBtn, this.redoBtn]) b.addEventListener('mousedown', e => e.preventDefault());
      tools.append(fmtBtn, shareBtn, palBtn, imgBtn, archBtn, moreBtn, this.undoBtn, this.redoBtn);
      this.updateUndoButtons();
    }
    this.toolbar.replaceChildren(tools, h('button', { type: 'button', class: 'text-btn ed-close', onclick: () => a.close() }, 'Schließen'));
  }
}

export { closePopover };