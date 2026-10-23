/* app.js – Hauptanwendung: Laden, Raster, Suche, Auswahl, Tastenkürzel, Echtzeit-Updates */
import { renderHTML } from './editor.js';
import { Connection, NoteState, rid } from './sync.js';
import { NoteEditor } from './noteeditor.js';
import * as D from './dialogs.js';
import {
  $, h, icon, iconBtn, setIcon, avatar, openModal, modals, openPopover, closePopover, popoverOwner, menu, toast, hideToast,
  confirmDialog, captureLayout, playLayout, debounce, plural, pickFiles, shrinkImage, animate,
} from './dom.js';
import {
  state, app, store, api, AuthError, setting, updateSettings, keymap, comboFromEvent, SHORTCUT_SCOPE, hintFor,
} from './state.js';

const META_KEYS = ['color', 'images', 'members', 'owner_id', 'role', 'pinned', 'archived', 'position', 'trashed',
  'trashed_at', 'labels', 'created_at', 'updated_at', 'updated_by'];
const THEMES = { system: ['brightness_auto', 'Design: wie System'], light: ['light_mode', 'Design: hell'], dark: ['dark_mode', 'Design: dunkel'] };
const URL_RE = /\bhttps?:\/\/\S+/i;
const isTouch = () => window.matchMedia('(pointer: coarse)').matches;
const isNarrow = () => window.matchMedia('(max-width: 720px)').matches;

let conn = null;
let ready = false;
let queue = [];
let editor = null;          // {ed, note, modal}
const composer = { expanded: false, ed: null, ns: null, meta: null };

// ===========================================================================
// Kategorien für die Suche (wie „Typen“, „Dinge“, „Farben“ im Original)
// ===========================================================================
const TYPES = [
  ['list', 'Listen', 'check_box', n => n.ns.doc.is_list],
  ['image', 'Bilder', 'image_o', n => n.images.length > 0],
  ['url', 'Links', 'link', n => URL_RE.test(noteText(n))],
  ['shared', 'Geteilt', 'group', n => n.members.length > 1],
];
const THINGS = [
  ['books', 'Bücher', 'book', ['buch', 'bücher', 'roman', 'autor', 'lesen', 'lektüre', 'hörbuch', 'isbn', 'book', 'novel', 'author', 'bestseller']],
  ['food', 'Essen', 'restaurant', ['rezept', 'kochen', 'backen', 'zutat', 'essen', 'mehl', 'zucker', 'butter', 'pizza', 'pasta', 'nudel', 'salat', 'suppe', 'kuchen', 'frühstück', 'abendessen', 'mittagessen', 'restaurant', 'recipe', 'dinner', 'lunch', 'gemüse', 'käse']],
  ['places', 'Orte', 'place', ['adresse', 'straße', 'museum', 'park', 'café', 'cafe', 'hotel', 'strand', 'stadt', 'address', 'street', 'platz']],
  ['movies', 'Filme', 'movie', ['film', 'kino', 'movie', 'regie', 'trailer', 'netflix']],
  ['music', 'Musik', 'music', ['musik', 'song', 'lied', 'album', 'band', 'konzert', 'playlist', 'spotify', 'music']],
  ['tv', 'Serien', 'tv', ['serie', 'staffel', 'episode', 'fernseh', 'season', 'tv-']],
  ['travel', 'Reisen', 'flight', ['reise', 'urlaub', 'flug', 'flughafen', 'zug', 'bahn', 'koffer', 'packliste', 'visum', 'trip', 'travel', 'flight', 'vacation']],
  ['shopping', 'Einkaufen', 'cart', ['einkauf', 'kaufen', 'supermarkt', 'drogerie', 'bestellen', 'shopping', 'grocer']],
  ['work', 'Arbeit', 'work', ['projekt', 'meeting', 'besprechung', 'kunde', 'angebot', 'rechnung', 'deadline', 'präsentation', 'büro', 'office', 'team']],
  ['sport', 'Sport', 'sport', ['sport', 'training', 'laufen', 'joggen', 'fitness', 'yoga', 'fußball', 'radfahren', 'schwimmen', 'workout', 'gym']],
  ['quotes', 'Zitate', 'quote', ['zitat', 'quote']],
];

function noteText(n) {
  const d = n.ns.doc;
  return [d.title, d.is_list ? d.items.map(i => i.text).join('\n') : d.body.text()].join('\n');
}
function noteWords(n) {
  if (n._wordsRev === n.ns.rev && n._words && !n.ns.pending()) return n._words;
  n._words = noteText(n).toLocaleLowerCase().split(/[^\p{L}\p{N}-]+/u).filter(Boolean);
  n._wordsRev = n.ns.rev;
  return n._words;
}
function isThing(n, thing) {
  const kws = thing[3];
  if (thing[0] === 'quotes' && /[„“"»«][^„“"»«]{8,}[“"«»]/.test(noteText(n))) return true;
  return noteWords(n).some(w => kws.some(k => w.startsWith(k)));
}
function matchesFilter(n, f) {
  if (!f) return true;
  if (f.kind === 'type') return TYPES.find(t => t[0] === f.value)[3](n);
  if (f.kind === 'label') return n.labels.includes(f.value);
  if (f.kind === 'color') return n.color === f.value;
  if (f.kind === 'thing') return isThing(n, THINGS.find(t => t[0] === f.value));
  return true;
}
function haystack(n) {
  const labels = n.labels.map(id => (state.labels.find(l => l.id === id) || {}).name || '');
  const people = n.members.map(m => m.username);
  return [noteText(n), ...labels, ...people].join('\n').toLocaleLowerCase();
}

// ===========================================================================
// Notizen verwalten
// ===========================================================================
function metaOf(view) {
  const m = {};
  for (const k of META_KEYS) if (k in view) m[k] = view[k];
  return m;
}

function upsertNote(view) {
  let n = state.notes.get(view.id);
  if (!n) {
    n = { id: view.id, ...metaOf(view) };
    n.ns = new NoteState(conn, view);
    n.ns.on(ev => { if (ev.type !== 'synced') noteChanged(n.id); if (ev.type === 'reset' && ev.lost) toast('Einige Änderungen konnten nicht synchronisiert werden.'); });
    state.notes.set(n.id, n);
  } else {
    Object.assign(n, metaOf(view));
    if (view.rev > n.ns.rev) {
      if (!n.ns.pending()) n.ns.reset(view);
      else n.ns.catchup();
    }
  }
  for (const m of n.members || []) if (!state.users.has(m.id)) state.users.set(m.id, { id: m.id, username: m.username, avatar: m.avatar });
  if (editor && editor.note === n) {
    editor.ed.refreshMeta();
    if (n.trashed !== editor.ed.readOnly) closeEditor();
  }
  scheduleRender();
  return n;
}
app.upsertNote = upsertNote;

function removeNote(id, message) {
  const n = state.notes.get(id);
  if (!n) return;
  state.notes.delete(id);
  state.selected.delete(id);
  if (editor && editor.note === n) {
    editor.modal.close();
    if (message) toast(message);
  }
  scheduleRender();
}
app.removeNote = removeNote;

let dirtyCards = new Set();
let cardFrame = null;
function noteChanged(id) {
  dirtyCards.add(id);
  if (cardFrame) return;
  cardFrame = requestAnimationFrame(() => {
    cardFrame = null;
    const ids = dirtyCards;
    dirtyCards = new Set();
    let relayout = false;
    for (const nid of ids) {
      const old = document.querySelector(`.note-card[data-key="${nid}"]`);
      const n = state.notes.get(nid);
      if (!old || !n) { relayout = true; continue; }
      const fresh = buildCard(n, old._list);
      fresh._list = old._list;
      const hBefore = old.offsetHeight;
      old.replaceWith(fresh);
      if (fresh.offsetHeight !== hBefore) relayout = true;
    }
    if (relayout) relayoutSoon();
  });
}
app.noteChanged = noteChanged;
const relayoutSoon = debounce(() => renderMain(), 350);

let renderFrame = null;
function scheduleRender() {
  if (renderFrame) return;
  renderFrame = requestAnimationFrame(() => { renderFrame = null; render(); });
}

// ===========================================================================
// Aktionen
// ===========================================================================
function handleError(err) {
  if (err instanceof AuthError) return;
  console.error(err);
  toast(err.message || 'Unbekannter Fehler');
}
app.handleError = handleError;

async function patchNote(n, patch) {
  Object.assign(n, patch);
  if ('trashed' in patch) n.trashed_at = patch.trashed ? Date.now() / 1000 : null;
  if (editor && editor.note === n) editor.ed.refreshMeta();
  scheduleRender();
  try {
    const fresh = await api('PATCH', `api/notes/${n.id}`, patch);
    Object.assign(n, metaOf(fresh));
    if (editor && editor.note === n) editor.ed.refreshMeta();
    scheduleRender();
  } catch (e) {
    handleError(e);
    reloadAll();
  }
}

const targets = n => (n ? [n] : selectedNotes());
function selectedNotes() { return [...state.selected].map(id => state.notes.get(id)).filter(Boolean); }

function actPin(list) {
  const pin = list.some(n => !n.pinned || n.archived);
  list.forEach(n => patchNote(n, pin ? { pinned: true, archived: false } : { pinned: false }));
  if (list.length > 1) toast(pin ? `${plural(list.length, 'Notiz', 'Notizen')} angeheftet` : `${plural(list.length, 'Notiz', 'Notizen')} losgelöst`);
}
function actColor(list, color) { list.forEach(n => patchNote(n, { color })); }
function actArchive(list) {
  const archive = list.some(n => !n.archived);
  const prev = list.map(n => ({ n, archived: n.archived, pinned: n.pinned }));
  list.forEach(n => patchNote(n, { archived: archive, pinned: false }));
  clearSelection();
  const label = list.length > 1 ? `${plural(list.length, 'Notiz', 'Notizen')} ${archive ? 'archiviert' : 'wiederhergestellt'}`
    : archive ? 'Notiz archiviert' : 'Archivierung der Notiz aufgehoben';
  toast(label, 'Rückgängig', () => prev.forEach(p => patchNote(p.n, { archived: p.archived, pinned: p.pinned })));
}
async function actTrash(list) {
  const own = list.filter(n => n.role === 'owner');
  const shared = list.filter(n => n.role !== 'owner');
  if (shared.length) {
    const ok = await confirmDialog(shared.length === 1
      ? 'Du bist Mitbearbeiter dieser Notiz. Möchtest du sie aus deinen Notizen entfernen? Für die anderen bleibt sie erhalten.'
      : `${shared.length} geteilte Notizen aus deinen Notizen entfernen?`, 'Entfernen');
    if (ok) for (const n of shared) { try { await api('POST', `api/notes/${n.id}/leave`); removeNote(n.id); } catch (e) { handleError(e); } }
  }
  if (!own.length) { clearSelection(); return; }
  if (own.some(n => n.members.length > 1)) {
    if (!await confirmDialog('Diese Notiz ist geteilt. Wenn du sie löschst, wird sie auch für alle Mitbearbeiter gelöscht.', 'Löschen')) return;
  }
  own.forEach(n => patchNote(n, { trashed: true }));
  clearSelection();
  toast(own.length > 1 ? `${plural(own.length, 'Notiz', 'Notizen')} in den Papierkorb verschoben` : 'Notiz in den Papierkorb verschoben',
    'Rückgängig', () => own.forEach(n => patchNote(n, { trashed: false })));
}
function actRestore(list) {
  list.forEach(n => patchNote(n, { trashed: false }));
  clearSelection();
  toast(list.length > 1 ? `${plural(list.length, 'Notiz', 'Notizen')} wiederhergestellt` : 'Notiz wiederhergestellt',
    'Rückgängig', () => list.forEach(n => patchNote(n, { trashed: true })));
}
async function actDeleteForever(list) {
  if (!await confirmDialog(list.length > 1 ? `${list.length} Notizen endgültig löschen?` : 'Notiz endgültig löschen?', 'Löschen')) return;
  for (const n of list) {
    try { await api('DELETE', `api/notes/${n.id}`); removeNote(n.id); } catch (e) { handleError(e); }
  }
  clearSelection();
}
async function actCopy(list) {
  for (const n of list) {
    try { upsertNote(await api('POST', `api/notes/${n.id}/copy`)); } catch (e) { handleError(e); }
  }
  clearSelection();
  toast(list.length > 1 ? `${list.length} Kopien erstellt` : 'Kopie erstellt');
}
async function actEmptyTrash() {
  if (!await confirmDialog('Papierkorb leeren? Alle Notizen im Papierkorb werden endgültig gelöscht.', 'Papierkorb leeren')) return;
  try {
    await api('DELETE', 'api/trash');
    for (const n of [...state.notes.values()]) if (n.trashed) state.notes.delete(n.id);
    render();
  } catch (e) { handleError(e); }
}

async function uploadImages(n, files) {
  for (const file of files.filter(f => f.type.startsWith('image/'))) {
    const ph = { uploading: true, url: URL.createObjectURL(file), w: 4, h: 3 };
    n.images = [...n.images, ph];
    if (editor && editor.note === n) editor.ed.refreshMeta();
    noteChanged(n.id);
    try {
      const blob = await shrinkImage(file, 2560, file.type === 'image/png' ? 'image/png' : 'image/jpeg', 0.9);
      const img = await api('POST', `api/notes/${n.id}/images`, blob, { raw: true, contentType: blob.type || file.type });
      n.images = n.images.map(x => (x === ph ? img : x));
    } catch (e) {
      n.images = n.images.filter(x => x !== ph);
      handleError(e);
    }
    URL.revokeObjectURL(ph.url);
    if (editor && editor.note === n) editor.ed.refreshMeta();
    noteChanged(n.id);
  }
}

async function removeImage(n, img) {
  if (!await confirmDialog('Bild aus der Notiz entfernen?', 'Entfernen')) return;
  n.images = n.images.filter(x => x.id !== img.id);
  if (editor && editor.note === n) editor.ed.refreshMeta();
  noteChanged(n.id);
  try { await api('DELETE', `api/notes/${n.id}/images/${img.id}`); } catch (e) { handleError(e); reloadAll(); }
}

function toggleItemFromCard(n, itemId) {
  const it = n.ns.doc.items.find(i => i.id === itemId);
  if (it) n.ns.applyLocal([{ k: 'item_set', id: itemId, checked: !it.checked }]);
}

async function reorder(list, moved, target) {
  const ids = list.map(n => n.id);
  const positions = list.map(n => n.position);
  ids.splice(ids.indexOf(moved.id), 1);
  ids.splice(list.indexOf(target), 0, moved.id);
  ids.forEach((id, i) => { state.notes.get(id).position = positions[i]; });
  renderMain();
  try { await api('POST', 'api/notes/reorder', { ids }); } catch (e) { handleError(e); reloadAll(); }
}

function moveFocused(dir) {
  const n = state.notes.get(state.focusedId);
  if (!n) return;
  const card = document.querySelector(`.note-card[data-key="${n.id}"]`);
  const list = card && card._list;
  if (!list) return;
  const i = list.indexOf(n);
  const target = list[i + dir];
  if (target) reorder(list, n, target).then(() => focusNote(n.id));
}

// ===========================================================================
// Auswahl (Mehrfachauswahl wie im Original)
// ===========================================================================
let lastSelected = null;
function toggleSelect(id, range = false) {
  if (range && lastSelected != null) {
    const order = visibleOrder();
    const a = order.indexOf(lastSelected);
    const b = order.indexOf(id);
    if (a >= 0 && b >= 0) {
      for (let i = Math.min(a, b); i <= Math.max(a, b); i++) state.selected.add(order[i]);
      lastSelected = id;
      renderSelection();
      return;
    }
  }
  if (state.selected.has(id)) state.selected.delete(id); else state.selected.add(id);
  lastSelected = id;
  renderSelection();
}
function clearSelection() {
  if (!state.selected.size) return;
  state.selected.clear();
  renderSelection();
}
function selectAll() {
  for (const id of visibleOrder()) state.selected.add(id);
  renderSelection();
}
function renderSelection() {
  for (const card of document.querySelectorAll('.note-card')) card.classList.toggle('selected', state.selected.has(Number(card.dataset.key)));
  document.body.classList.toggle('selecting', state.selected.size > 0);
  const bar = $('#selbar');
  if (!state.selected.size) { bar.classList.remove('show'); return; }
  const list = selectedNotes();
  const count = h('span', { class: 'sel-count', text: `${list.length} ausgewählt` });
  const tools = h('div', { class: 'sel-tools' });
  if (state.view === 'trash') {
    tools.append(iconBtn('delete_forever', 'Endgültig löschen', () => actDeleteForever(list)), iconBtn('restore_from_trash', 'Wiederherstellen', () => actRestore(list)));
  } else {
    const allPinned = list.every(n => n.pinned && !n.archived);
    const pal = iconBtn('palette', 'Hintergrundoptionen', () => openPopover(pal, D.palette(null, c => actColor(list, c)), { align: 'end' }));
    const more = iconBtn('more_vert', 'Mehr', () => openPopover(more, menu([
      { label: 'Löschen', action: () => actTrash(list) },
      { label: 'Label hinzufügen', action: () => openPopover(more, bulkLabelPicker(list), { align: 'end' }) },
      { label: 'Kopie erstellen', action: () => actCopy(list) },
    ]), { align: 'end' }));
    tools.append(
      iconBtn(allPinned ? 'push_pin' : 'push_pin_o', allPinned ? 'Loslösen' : 'Anheften', () => actPin(list)),
      pal,
      iconBtn(list.every(n => n.archived) ? 'unarchive' : 'archive', list.every(n => n.archived) ? 'Archivierung aufheben' : 'Archivieren', () => actArchive(list)),
      more);
  }
  bar.replaceChildren(iconBtn('close', 'Auswahl aufheben', clearSelection), count, h('span', { class: 'grow' }), tools);
  if (!bar.classList.contains('show')) {
    bar.classList.add('show');
    animate(bar, [{ transform: 'translateY(-100%)' }, { transform: 'none' }], { duration: 180 });
  }
}
function bulkLabelPicker(list) {
  const common = () => state.labels.map(l => l.id).filter(id => list.every(n => n.labels.includes(id)));
  return D.labelPicker(common, ids => {
    const before = common();
    const added = ids.filter(x => !before.includes(x));
    const removed = before.filter(x => !ids.includes(x));
    for (const n of list) {
      const next = [...new Set([...n.labels.filter(x => !removed.includes(x)), ...added])];
      patchNote(n, { labels: next });
    }
  });
}

// ===========================================================================
// Ansicht & Navigation
// ===========================================================================
function viewTitle() {
  if (state.view === 'archive') return 'Archiv';
  if (state.view === 'trash') return 'Papierkorb';
  if (state.view === 'label') {
    const lab = state.labels.find(l => l.id === state.labelId);
    return lab ? lab.name : 'Notizen';
  }
  return 'Notizen';
}

function syncRoute() {
  const hash = location.hash.slice(1);
  const m = hash.match(/^label\/(\d+)$/);
  if (hash === 'archive' || hash === 'trash') { state.view = hash; state.labelId = null; }
  else if (m && state.labels.some(l => l.id === Number(m[1]))) { state.view = 'label'; state.labelId = Number(m[1]); }
  else { state.view = 'notes'; state.labelId = null; }
}

function setView(view, labelId = null) {
  exitSearch(false);
  const hash = view === 'notes' ? '' : view === 'label' ? `label/${labelId}` : view;
  if (location.hash.slice(1) === hash) applyRoute(); else location.hash = hash;
}
app.setView = setView;

function applyRoute() {
  if (!state.user) return;
  closeComposer();
  clearSelection();
  syncRoute();
  document.body.classList.remove('nav-open');
  window.scrollTo(0, 0);
  render();
}

function visibleNotes() {
  const q = state.search.trim().toLocaleLowerCase();
  let list = [...state.notes.values()].filter(n => {
    if (state.searching) return !n.trashed;
    if (state.view === 'trash') return n.trashed;
    if (n.trashed) return false;
    if (state.view === 'archive') return n.archived;
    if (state.view === 'label') return n.labels.includes(state.labelId);
    return !n.archived;
  });
  if (state.searching) {
    list = list.filter(n => matchesFilter(n, state.filter));
    if (q) {
      const words = q.split(/\s+/);
      list = list.filter(n => { const hay = haystack(n); return words.every(w => hay.includes(w)); });
    }
  }
  return list.sort((a, b) => b.position - a.position || b.id - a.id);
}

function sections() {
  const notes = visibleNotes();
  if (state.searching || state.view === 'trash' || state.view === 'archive') return [[null, notes]];
  const pinned = notes.filter(n => n.pinned && !n.archived);
  const others = notes.filter(n => !(n.pinned && !n.archived));
  const out = [];
  if (pinned.length) out.push(['Angeheftet', pinned]);
  if (others.length) out.push([pinned.length ? 'Andere' : null, others]);
  return out;
}

function visibleOrder() {
  return [...document.querySelectorAll('#notes-area .note-card')]
    .sort((a, b) => Number(a.dataset.order) - Number(b.dataset.order)).map(c => Number(c.dataset.key));
}

// ===========================================================================
// Darstellung
// ===========================================================================
function render() {
  renderSidebar();
  renderHeader();
  renderMain();
}
app.render = render;

function renderHeader() {
  const title = state.searching ? 'Suche' : viewTitle();
  $('#brand-title').textContent = title;
  $('#brand-logo').classList.toggle('hidden', state.view !== 'notes' || state.searching);
  document.title = state.view === 'notes' && !state.searching ? 'Notizen' : `${title} – Notizen`;
  const lb = $('#layout-btn');
  setIcon(lb, state.layout === 'grid' ? 'view_agenda' : 'grid_view');
  const label = `${state.layout === 'grid' ? 'Listenansicht' : 'Rasteransicht'} (${hintFor('toggleLayout')})`;
  lb.title = label;
  lb.setAttribute('aria-label', label);
  const rb = $('#refresh-btn');
  setIcon(rb, state.online ? 'refresh' : 'cloud_off');
  rb.title = state.online ? `Aktualisieren (${hintFor('refresh')})` : 'Offline – Änderungen werden synchronisiert, sobald die Verbindung wieder besteht';
  rb.classList.toggle('offline', !state.online);
  // Suchfeld
  document.body.classList.toggle('searching', state.searching);
  setIcon($('#search-icon'), state.searching ? 'arrow_back' : 'search');
  $('#search-icon').title = state.searching ? 'Suche beenden' : 'Suchen';
  const chip = $('#search-chip');
  chip.replaceChildren();
  chip.classList.toggle('hidden', !state.filter);
  if (state.filter) {
    chip.append(icon(state.filter.icon || 'label', 18), h('span', { text: state.filter.label }),
      h('button', { type: 'button', class: 'chip-x', 'aria-label': 'Filter entfernen', onclick: () => { state.filter = null; renderHeader(); renderMain(); $('#search-input').focus(); } }, icon('close', 14)));
  }
  $('#search-clear').classList.toggle('hidden', !state.search && !state.filter);
  renderAvatar();
}

function renderAvatar() {
  const btn = $('#account-btn');
  btn.replaceChildren(avatar(state.user, 32));
  btn.title = `Konto: ${state.user.username}`;
}

function renderSidebar() {
  const nav = $('#sidebar');
  const item = (iconName, label, onclick, active) => h('button', {
    type: 'button', class: 'nav-item' + (active ? ' active' : ''), title: label, onclick,
    'aria-current': active ? 'page' : null,
  }, icon(iconName), h('span', { class: 'nav-label', text: label }));
  const s = state.searching;
  nav.replaceChildren(
    item('lightbulb', 'Notizen', () => setView('notes'), !s && state.view === 'notes'),
    ...D.sortedLabels().map(lab => item('label', lab.name, () => setView('label', lab.id), !s && state.view === 'label' && state.labelId === lab.id)),
    item('edit', 'Labels bearbeiten', D.openLabelsDialog, false),
    item('archive', 'Archiv', () => setView('archive'), !s && state.view === 'archive'),
    item('delete', 'Papierkorb', () => setView('trash'), !s && state.view === 'trash'));
}

function renderMain() {
  const area = $('#notes-area');
  const notice = $('#notice');
  const before = captureLayout(area, '.note-card');
  area.replaceChildren();
  notice.replaceChildren();
  const q = state.search.trim();
  const composerVisible = !state.searching && (state.view === 'notes' || state.view === 'label');
  $('#composer-wrap').classList.toggle('hidden', !composerVisible);

  if (state.searching && !q && !state.filter) {
    renderSearchPanel(area);
    return;
  }
  if (!state.searching && state.view === 'trash' && [...state.notes.values()].some(n => n.trashed)) {
    notice.append(h('div', { class: 'trash-notice' },
      h('span', { text: 'Notizen im Papierkorb werden nach 7 Tagen gelöscht.' }),
      h('button', { type: 'button', class: 'text-btn primary', onclick: actEmptyTrash }, 'Papierkorb leeren')));
  }
  const secs = sections();
  if (!secs.length) {
    const [ic, text] = state.searching ? ['search', 'Keine passenden Ergebnisse'] : {
      notes: ['lightbulb', 'Hier werden deine Notizen angezeigt'],
      archive: ['archive', 'Hier werden archivierte Notizen angezeigt'],
      trash: ['delete', 'Keine Notizen im Papierkorb'],
      label: ['label', 'Keine Notizen mit diesem Label'],
    }[state.view];
    const empty = h('div', { class: 'empty-state' }, icon(ic, 120), h('p', { text }));
    area.append(empty);
    animate(empty, [{ opacity: 0 }, { opacity: 1 }], { duration: 250 });
    return;
  }
  let order = 0;
  for (const [title, list] of secs) {
    const section = h('section', { class: 'notes-section' });
    if (title) section.append(h('h2', { class: 'section-title', text: title }));
    const grid = h('div', { class: 'grid' });
    section.append(grid);
    area.append(section);
    const cards = list.map(n => { const c = buildCard(n, list); c._list = list; c.dataset.order = String(order++); return c; });
    layoutGrid(section, grid, cards);
  }
  playLayout(area, '.note-card', before);
}

function gridMetrics(width) {
  if (state.layout === 'list') return { cols: 1, w: Math.min(width, 600), gap: 16 };
  const small = width < 600;
  const gap = small ? 8 : 16;
  const min = small ? 150 : 240;
  let cols = Math.max(1, Math.floor((width + gap) / (min + gap)));
  if (width < 300) cols = 1;
  const w = Math.min(small ? width : 300, Math.floor((width - gap * (cols - 1)) / cols));
  return { cols, w, gap };
}

/** Masonry: jede Karte kommt in die aktuell kürzeste Spalte (Reihenfolge links → rechts). */
function layoutGrid(section, grid, cards) {
  const width = $('#notes-area').clientWidth;
  const { cols, w, gap } = gridMetrics(width);
  section.style.setProperty('--card-w', `${w}px`);
  section.style.setProperty('--gap', `${gap}px`);
  section.classList.toggle('list', state.layout === 'list');
  const columns = Array.from({ length: cols }, () => h('div', { class: 'col' }));
  grid.append(...columns);
  const heights = new Array(cols).fill(0);
  for (const card of cards) {
    const i = heights.indexOf(Math.min(...heights));
    columns[i].append(card);
    heights[i] = columns[i].offsetHeight;
  }
}

function imageGrid(images, cls) {
  const shown = images.slice(0, 6);
  const wrap = h('div', { class: cls, dataset: { count: String(shown.length) } });
  for (const img of shown) {
    const ratio = img.w && img.h ? img.w / img.h : 4 / 3;
    wrap.append(h('div', { class: 'img-tile' + (img.uploading ? ' uploading' : ''), style: { aspectRatio: shown.length === 1 ? String(Math.max(0.5, Math.min(ratio, 3))) : '' } },
      h('img', { src: img.url || `api/images/${img.id}`, alt: '', loading: 'lazy', draggable: 'false' })));
  }
  return wrap;
}

function buildCard(n, list) {
  const doc = n.ns.doc;
  const card = h('div', {
    class: 'note-card', dataset: { key: n.id, color: n.color }, tabindex: '0', role: 'article',
    'aria-label': doc.title || 'Notiz',
  });
  if (state.selected.has(n.id)) card.classList.add('selected');
  if (state.focusedId === n.id) card.classList.add('kbd-focus');
  if (editor && editor.note === n) card.classList.add('editing');
  card.append(h('button', {
    type: 'button', class: 'sel-check', title: 'Notiz auswählen', 'aria-label': 'Notiz auswählen',
    onclick: e => { e.stopPropagation(); toggleSelect(n.id, e.shiftKey); },
  }, icon('check_circle', 24)));
  if (!n.trashed) {
    card.append(iconBtn(n.pinned && !n.archived ? 'push_pin' : 'push_pin_o', n.pinned ? 'Notiz loslösen' : 'Notiz anheften',
      e => { e.stopPropagation(); actPin([n]); }, 'pin-btn' + (n.pinned && !n.archived ? ' on' : '')));
  }
  if (n.images.length) card.append(imageGrid(n.images, 'card-images'));
  const hasTitle = doc.title.trim() !== '';
  if (hasTitle) card.append(h('div', { class: 'note-title', text: doc.title }));
  if (doc.is_list && doc.items.length) {
    card.append(checklistPreview(n));
  } else if (!doc.is_list && doc.body.length() > 1) {
    const body = h('div', { class: 'note-body' });
    const text = doc.body.text();
    const big = !hasTitle && !n.images.length && text.length <= 60 && text.split('\n').length <= 3 && !doc.body.ops.some(o => o.attributes);
    if (big) body.classList.add('big');
    body.innerHTML = renderHTML(doc.body, { rich: true, links: true, maxChars: 1500 });
    for (const a of body.querySelectorAll('a')) a.addEventListener('click', e => e.stopPropagation());
    card.append(body);
  } else if (!hasTitle && !n.images.length) {
    card.append(h('div', { class: 'note-body empty-note', text: 'Leere Notiz' }));
  }
  const chips = D.labelChips(n.labels, n.trashed ? null : id => patchNote(n, { labels: n.labels.filter(x => x !== id) }));
  const others = n.members.filter(m => m.id !== state.user.id);
  if (chips || others.length) {
    const row = h('div', { class: 'card-meta' });
    if (chips) row.append(chips);
    if (others.length) {
      row.append(h('div', { class: 'collab-avatars' }, others.slice(0, 4).map(u => avatar(u, 26)),
        others.length > 4 ? h('span', { class: 'avatar more', text: `+${others.length - 4}` }) : null));
    }
    card.append(row);
  }
  card.append(cardToolbar(n));

  card.addEventListener('click', e => {
    if (e.target.closest('button, .chip, a')) return;
    if (state.selected.size) { toggleSelect(n.id, e.shiftKey); return; }
    openEditor(n, card);
  });
  card.addEventListener('focus', () => { state.focusedId = n.id; });
  if (!n.trashed && !state.searching && state.view !== 'archive') enableDrag(card, n, list);
  return card;
}

function checklistPreview(n) {
  const items = n.ns.doc.items;
  const bottom = setting('checkedBottom');
  const open = bottom ? items.filter(i => !i.checked) : items;
  const done = bottom ? items.filter(i => i.checked) : [];
  const wrap = h('div', { class: 'note-list' });
  for (const it of open.slice(0, 10)) {
    wrap.append(h('div', { class: 'li' + (it.checked ? ' done' : '') },
      h('button', {
        type: 'button', class: 'li-check', disabled: n.trashed, title: it.checked ? 'Nicht erledigt' : 'Erledigt', 'aria-label': `„${it.text}“ ${it.checked ? 'nicht erledigt' : 'erledigt'}`,
        onclick: e => { e.stopPropagation(); toggleItemFromCard(n, it.id); },
      }, icon(it.checked ? 'check_box' : 'check_box_o', 18)),
      h('span', { class: 'li-text', text: it.text })));
  }
  if (open.length > 10) wrap.append(h('div', { class: 'li-more', text: '…' }));
  if (done.length) wrap.append(h('div', { class: 'li-done', text: '+ ' + plural(done.length, 'erledigtes Element', 'erledigte Elemente') }));
  return wrap;
}

function cardToolbar(n) {
  const bar = h('div', { class: 'card-toolbar' });
  const stop = fn => e => { e.stopPropagation(); fn(e); };
  if (n.trashed) {
    bar.append(iconBtn('delete_forever', 'Endgültig löschen', stop(() => actDeleteForever([n])), '', 18),
      iconBtn('restore_from_trash', 'Wiederherstellen', stop(() => actRestore([n])), '', 18));
    return bar;
  }
  const pal = iconBtn('palette', 'Hintergrundoptionen', stop(() => openPopover(pal, D.palette(n.color, c => actColor([n], c)))), '', 18);
  const more = iconBtn('more_vert', 'Mehr', stop(() => openPopover(more, menu([
    { label: n.role === 'owner' ? 'Notiz löschen' : 'Notiz entfernen', action: () => actTrash([n]) },
    { label: n.labels.length ? 'Label ändern' : 'Label hinzufügen', action: () => openPopover(more, D.labelPicker(() => n.labels, ids => patchNote(n, { labels: ids }))) },
    { label: 'Kopie erstellen', action: () => actCopy([n]) },
    { label: n.ns.doc.is_list ? 'Kästchen ausblenden' : 'Kästchen anzeigen', action: () => convertFromCard(n) },
    { label: 'Versionsverlauf', action: () => D.openVersions(n) },
  ]))), '', 18);
  bar.append(
    iconBtn('person_add_o', 'Mitbearbeiter', stop(() => D.openShareDialog(n)), '', 18),
    pal,
    iconBtn('image_o', 'Bild hinzufügen', stop(async () => { const f = await pickFiles(); if (f.length) uploadImages(n, f); }), '', 18),
    iconBtn(n.archived ? 'unarchive' : 'archive', n.archived ? 'Archivierung aufheben' : 'Archivieren', stop(() => actArchive([n])), '', 18),
    more);
  return bar;
}

function convertFromCard(n) {
  // Gleiche Logik wie im Editor; ein unsichtbarer Editor erzeugt die Befehle
  const tmp = new NoteEditor(n.ns, n, { mode: 'composer', actions: {} });
  tmp.toggleChecks();
  tmp.destroy();
}

// Drag & Drop zum Umsortieren
let drag = null;
function enableDrag(card, n, list) {
  card.draggable = true;
  card.addEventListener('dragstart', e => {
    if (state.selected.size) { e.preventDefault(); return; }
    drag = { n, list };
    card.classList.add('dragging');
    e.dataTransfer.effectAllowed = 'move';
    try { e.dataTransfer.setData('text/plain', String(n.id)); } catch { /* egal */ }
  });
  card.addEventListener('dragend', () => {
    card.classList.remove('dragging');
    drag = null;
    document.querySelectorAll('.drop-target').forEach(el => el.classList.remove('drop-target'));
  });
  const accepts = () => drag && drag.list === list && drag.n !== n;
  card.addEventListener('dragover', e => { if (accepts()) { e.preventDefault(); card.classList.add('drop-target'); } });
  card.addEventListener('dragleave', () => card.classList.remove('drop-target'));
  card.addEventListener('drop', e => {
    if (!accepts()) return;
    e.preventDefault();
    const moved = drag.n;
    drag = null;
    reorder(list, moved, n);
  });
}

// ===========================================================================
// Suche mit Kategorien
// ===========================================================================
function renderSearchPanel(area) {
  const notes = [...state.notes.values()].filter(n => !n.trashed);
  const tile = (label, ic, onclick, cls = '') => h('button', { type: 'button', class: `cat-tile ${cls}`, onclick }, h('span', { class: 'cat-icon' }, icon(ic, 32)), h('span', { class: 'cat-label', text: label }));
  const set = f => { state.filter = f; renderHeader(); renderMain(); };
  const panel = h('div', { class: 'search-panel' });
  const section = (title, items) => { if (items.length) panel.append(h('section', { class: 'cat-section' }, h('h3', { text: title }), h('div', { class: 'cat-grid' }, items))); };
  section('Typen', TYPES.filter(t => notes.some(t[3])).map(t => tile(t[1], t[2], () => set({ kind: 'type', value: t[0], label: t[1], icon: t[2] }), 'type')));
  section('Labels', D.sortedLabels().filter(l => notes.some(n => n.labels.includes(l.id))).map(l => tile(l.name, 'label', () => set({ kind: 'label', value: l.id, label: l.name, icon: 'label' }), 'lab')));
  section('Dinge', THINGS.filter(t => notes.some(n => isThing(n, t))).map(t => tile(t[1], t[2], () => set({ kind: 'thing', value: t[0], label: t[1], icon: t[2] }), 'thing')));
  const colors = D.COLORS.filter(([c]) => notes.some(n => n.color === c));
  if (colors.length) {
    panel.append(h('section', { class: 'cat-section' }, h('h3', { text: 'Farben' }), h('div', { class: 'color-row' },
      colors.map(([c, name]) => h('button', { type: 'button', class: 'swatch big', dataset: { color: c }, title: name, 'aria-label': name, onclick: () => set({ kind: 'color', value: c, label: name, icon: 'palette' }) },
        c === 'default' ? icon('format_color_reset', 20) : null)))));
  }
  if (!panel.children.length) panel.append(h('div', { class: 'empty-state' }, icon('search', 120), h('p', { text: 'Noch nichts zu durchsuchen' })));
  area.append(panel);
  animate(panel, [{ opacity: 0, transform: 'translateY(8px)' }, { opacity: 1, transform: 'none' }], { duration: 220 });
}

function enterSearch() {
  if (state.searching) return;
  closeComposer();
  clearSelection();
  state.searching = true;
  render();
}

function exitSearch(rerender = true) {
  if (!state.searching) return;
  state.searching = false;
  state.search = '';
  state.filter = null;
  $('#search-input').value = '';
  $('#search-input').blur();
  if (rerender) render();
}

// ===========================================================================
// Eingabefeld „Notiz schreiben…“
// ===========================================================================
function renderComposer() {
  const wrap = $('#composer-wrap');
  if (composer.expanded) {
    wrap.replaceChildren(h('div', { class: 'composer expanded' }, composer.ed.root));
    return;
  }
  wrap.replaceChildren(h('div', { class: 'composer collapsed' },
    h('button', { type: 'button', class: 'composer-placeholder', onclick: () => expandComposer() }, 'Notiz schreiben…'),
    iconBtn('check_box', `Neue Liste (${hintFor('newList')})`, () => expandComposer({ list: true })),
    iconBtn('image_o', 'Neue Notiz mit Bild', async () => { const f = await pickFiles(); if (f.length) expandComposer({ files: f }); })));
}

function expandComposer({ list = false, files = null, draft = null } = {}) {
  if (composer.expanded) return;
  exitSearch(false);
  const ns = draft ? draft.ns : new NoteState(null, null);
  if (!draft && list) ns.doc.is_list = true;
  const meta = draft ? draft.meta : {
    color: 'default', pinned: false, archived: false, collaborators: [], images: [],
    labels: state.view === 'label' && state.labelId ? [state.labelId] : [],
  };
  composer.ns = ns;
  composer.meta = meta;
  composer.expanded = true;
  const refresh = () => composer.ed && composer.ed.refreshMeta();
  const addFiles = fs => {
    for (const file of fs.filter(f => f.type.startsWith('image/'))) {
      const img = { file, url: URL.createObjectURL(file), w: 4, h: 3 };
      meta.images.push(img);
      const probe = new Image();
      probe.onload = () => { img.w = probe.naturalWidth; img.h = probe.naturalHeight; refresh(); };
      probe.src = img.url;
    }
    refresh();
  };
  composer.ed = new NoteEditor(ns, meta, {
    mode: 'composer',
    actions: {
      pin: () => { meta.pinned = !meta.pinned; refresh(); },
      setColor: c => { meta.color = c; refresh(); },
      setLabels: ids => { meta.labels = ids; refresh(); },
      archive: () => { meta.archived = true; meta.pinned = false; closeComposer(); },
      trash: () => { discardComposer(); },
      share: () => D.openShareDialog(meta, { onSave: members => { meta.collaborators = members; refresh(); } }),
      pickImages: async () => addFiles(await pickFiles()),
      addImages: addFiles,
      removeImage: img => { meta.images = meta.images.filter(x => x !== img); URL.revokeObjectURL(img.url); refresh(); },
      viewImage: i => D.openLightbox(meta.images, i),
      close: () => closeComposer(),
    },
  });
  renderComposer();
  if (files) addFiles(files);
  composer.ed.focusStart();
  animate(composer.ed.root, [{ opacity: 0.4, transform: 'scaleY(.9)' }, { opacity: 1, transform: 'none' }], { duration: 160 });
}

function discardComposer() {
  if (!composer.expanded) return;
  for (const img of composer.meta.images) URL.revokeObjectURL(img.url);
  composer.ed.destroy();
  composer.expanded = false;
  composer.ed = composer.ns = composer.meta = null;
  renderComposer();
}

async function closeComposer() {
  if (!composer.expanded) return;
  composer.ed.flush();
  const { ns, meta, ed } = composer;
  ed.destroy();
  composer.expanded = false;
  composer.ed = composer.ns = composer.meta = null;
  closePopover();
  renderComposer();
  const d = ns.doc;
  const items = d.items.filter(i => i.text.trim());
  const empty = !d.title.trim() && (d.is_list ? !items.length : !d.body.text().trim()) && !meta.images.length;
  if (empty) return;
  try {
    const view = await api('POST', 'api/notes', {
      title: d.title, body: d.body.ops, items, is_list: d.is_list, color: meta.color,
      pinned: meta.pinned, archived: meta.archived, labels: meta.labels, collaborators: meta.collaborators.map(u => u.id),
    });
    const n = upsertNote(view);
    if (meta.archived) toast('Notiz archiviert', 'Rückgängig', () => patchNote(n, { archived: false }));
    if (meta.images.length) await uploadImages(n, meta.images.map(i => i.file));
    for (const img of meta.images) URL.revokeObjectURL(img.url);
  } catch (e) {
    handleError(e);
    if (!(e instanceof AuthError)) { meta.archived = false; expandComposer({ draft: { ns, meta } }); }
  }
}

// ===========================================================================
// Bearbeiten-Dialog
// ===========================================================================
function cardOf(n) { return document.querySelector(`#notes-area .note-card[data-key="${n.id}"]`); }

function openEditor(n, cardEl = null) {
  closePopover();
  if (composer.expanded) closeComposer();
  if (editor) editor.modal.close();
  const card = cardEl || cardOf(n);
  const from = card ? card.getBoundingClientRect() : null;
  const actions = {
    pin: () => actPin([n]),
    setColor: c => actColor([n], c),
    setLabels: ids => patchNote(n, { labels: ids }),
    archive: () => { closeEditor(); actArchive([n]); },
    trash: () => { closeEditor(); actTrash([n]); },
    copy: () => { closeEditor(); actCopy([n]); },
    restore: () => { closeEditor(); actRestore([n]); },
    deleteForever: () => { closeEditor(); actDeleteForever([n]); },
    share: () => D.openShareDialog(n),
    pickImages: async () => { const f = await pickFiles(); if (f.length) uploadImages(n, f); },
    addImages: files => uploadImages(n, files),
    removeImage: img => removeImage(n, img),
    viewImage: i => D.openLightbox(n.images, i, { onDelete: n.trashed ? null : img => removeImage(n, img) }),
    versions: () => D.openVersions(n),
    close: () => closeEditor(),
  };
  const ed = new NoteEditor(n.ns, n, { mode: 'modal', readOnly: n.trashed, actions });
  editor = { ed, note: n };
  if (card) card.classList.add('editing');
  editor.modal = openModal(ed.root, {
    className: 'editor-modal', from, label: 'Notiz bearbeiten',
    onClose: () => {
      ed.destroy();
      if (editor && editor.ed === ed) editor = null;
      const c = cardOf(n);
      if (c) c.classList.remove('editing');
      noteChanged(n.id);
      relayoutSoon();
    },
  });
  if (state.online) conn.send({ t: 'open', id: n.id });
  if (!isTouch() && !n.trashed) ed.focusStart();
}

function closeEditor() {
  if (!editor) return;
  const c = cardOf(editor.note);
  editor.modal.close(c ? c.getBoundingClientRect() : null);
}

// ===========================================================================
// Tastenkürzel (global über das ganze Dokument)
// ===========================================================================
const isTyping = t => t && (t.isContentEditable || t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT');

function activeNoteEditor(target) {
  if (editor && editor.ed.root.contains(target)) return { ed: editor.ed, close: closeEditor };
  if (composer.expanded && composer.ed.root.contains(target)) return { ed: composer.ed, close: closeComposer };
  if (editor && modals[modals.length - 1] === editor.modal) return { ed: editor.ed, close: closeEditor };
  return null;
}

function focusNote(id) {
  state.focusedId = id;
  document.querySelectorAll('.note-card.kbd-focus').forEach(c => c.classList.remove('kbd-focus'));
  const c = document.querySelector(`#notes-area .note-card[data-key="${id}"]`);
  if (c) { c.classList.add('kbd-focus'); c.focus({ preventScroll: true }); c.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); }
}

function stepFocus(dir) {
  const order = visibleOrder();
  if (!order.length) return;
  const i = order.indexOf(state.focusedId);
  const next = i < 0 ? order[0] : order[Math.max(0, Math.min(order.length - 1, i + dir))];
  focusNote(next);
}

function runAction(action) {
  const focused = state.notes.get(state.focusedId);
  const sel = state.selected.size ? selectedNotes() : focused ? [focused] : [];
  switch (action) {
    case 'newNote': if (state.view === 'notes' || state.view === 'label') expandComposer(); else { setView('notes'); setTimeout(() => expandComposer(), 50); } break;
    case 'newList': if (state.view === 'notes' || state.view === 'label') expandComposer({ list: true }); else { setView('notes'); setTimeout(() => expandComposer({ list: true }), 50); } break;
    case 'search': $('#search-input').focus(); break;
    case 'next': stepFocus(1); break;
    case 'prev': stepFocus(-1); break;
    case 'moveNext': moveFocused(1); break;
    case 'movePrev': moveFocused(-1); break;
    case 'open': if (focused) openEditor(focused); break;
    case 'select': if (focused) toggleSelect(focused.id); break;
    case 'selectAll': selectAll(); break;
    case 'archive': if (sel.length && state.view !== 'trash') actArchive(sel); break;
    case 'trash': if (sel.length && state.view !== 'trash') actTrash(sel); break;
    case 'pin': if (sel.length && state.view !== 'trash') actPin(sel); break;
    case 'toggleLayout': toggleLayout(); break;
    case 'toggleTheme': cycleTheme(); break;
    case 'refresh': reloadAll(true); break;
    case 'help': D.openShortcutHelp(); break;
    case 'settings': D.openSettings(); break;
    default: break;
  }
}

function handleEscape(e) {
  if (popoverOwner()) { closePopover(); e.preventDefault(); return; }
  if (modals.length) { const top = modals[modals.length - 1]; if (editor && top === editor.modal) closeEditor(); else top.close(); e.preventDefault(); return; }
  if (composer.expanded) { closeComposer(); e.preventDefault(); return; }
  if (state.selected.size) { clearSelection(); e.preventDefault(); return; }
  if (document.body.classList.contains('nav-open')) { document.body.classList.remove('nav-open'); return; }
  if (state.searching) { exitSearch(); e.preventDefault(); }
}

function onGlobalKey(e) {
  if (!state.user || e.defaultPrevented) return;
  if (e.key === 'Escape') { handleEscape(e); return; }
  if (e.isComposing) return;
  const combo = comboFromEvent(e);
  if (!combo) return;
  const action = keymap().get(combo);
  if (!action) return;
  const typing = isTyping(e.target);
  if (SHORTCUT_SCOPE[action] === 'editor') {
    const target = activeNoteEditor(e.target);
    if (!target) return;
    if (action === 'closeEditor') { e.preventDefault(); target.close(); return; }
    if ((action === 'undo' || action === 'redo') && e.target.tagName === 'INPUT') return;
    if (target.ed.exec(action)) { e.preventDefault(); e.stopPropagation(); }
    return;
  }
  if (!setting('shortcutsEnabled')) return;
  const plain = !combo.includes('Mod') && !combo.includes('Alt');
  if (typing && (plain || action === 'selectAll')) return;
  if (modals.length && action !== 'help') return;
  e.preventDefault();
  e.stopPropagation();
  runAction(action);
}

// ===========================================================================
// Einstellungen, Design, Layout
// ===========================================================================
const darkMq = window.matchMedia('(prefers-color-scheme: dark)');
function applyTheme() {
  const pref = THEMES[setting('theme')] ? setting('theme') : 'system';
  const theme = pref === 'system' ? (darkMq.matches ? 'dark' : 'light') : pref;
  document.documentElement.dataset.theme = theme;
  $('meta[name="theme-color"]').setAttribute('content', theme === 'dark' ? '#202124' : '#ffffff');
}
function cycleTheme() {
  const order = ['system', 'light', 'dark'];
  const next = order[(order.indexOf(setting('theme')) + 1) % order.length];
  updateSettings({ theme: next });
  toast(THEMES[next][1]);
}
function applySettings() {
  applyTheme();
  if (state.user) {
    renderHeader();
    renderMain();
    if (editor) editor.ed.renderBody();
  }
}
app.applySettings = applySettings;

function toggleLayout() {
  state.layout = state.layout === 'grid' ? 'list' : 'grid';
  store.set('layout', state.layout);
  renderHeader();
  renderMain();
}

async function changeAvatar() {
  const [file] = await pickFiles({ accept: 'image/jpeg,image/png,image/webp', multiple: false });
  if (!file) return;
  try {
    const jpeg = await shrinkImage(file, 512, 'image/jpeg', 0.9, true);
    const info = await api('PUT', 'api/me/avatar', jpeg, { raw: true, contentType: 'image/jpeg' });
    updateUser(info);
    toast('Profilbild aktualisiert');
  } catch (e) { handleError(e); }
}
async function removeAvatar() {
  try { updateUser(await api('DELETE', 'api/me/avatar')); } catch (e) { handleError(e); }
}
app.changeAvatar = changeAvatar;
app.removeAvatar = removeAvatar;

function updateUser(info) {
  state.users.set(info.id, info);
  if (state.user && info.id === state.user.id) state.user = { ...state.user, avatar: info.avatar };
  for (const n of state.notes.values()) for (const m of n.members) if (m.id === info.id) m.avatar = info.avatar;
  if (editor) editor.ed.refreshMeta();
  if (state.user) { renderAvatar(); renderMain(); }
}

// ===========================================================================
// Server-Verbindung
// ===========================================================================
function onMessage(msg) {
  if (!ready && msg.t !== 'hello' && msg.t !== 'pong') { queue.push(msg); return; }
  const n = msg.id != null ? state.notes.get(msg.id) : null;
  switch (msg.t) {
    case 'op': if (n) n.ns.remote(msg); else if (msg.client !== conn.clientId) fetchNote(msg.id); break;
    case 'ack': if (n) n.ns.ack(msg.rev, msg.cid); break;
    case 'caught': if (n) n.ns.caught(); break;
    case 'resync': fetchNote(msg.id, true); break;
    case 'note': upsertNote(msg.note); break;
    case 'gone': removeNote(msg.id, 'Die Notiz wurde gelöscht oder nicht mehr mit dir geteilt.'); break;
    case 'cursor': if (editor && editor.note.id === msg.id) editor.ed.remoteCursor(msg); break;
    case 'presence': if (editor && editor.note.id === msg.id) editor.ed.presence(msg); break;
    case 'user': updateUser(msg.user); break;
    case 'labels': api('GET', 'api/labels').then(l => { state.labels = l; render(); }).catch(handleError); break;
    case 'settings': state.settings = msg.settings || {}; applySettings(); break;
    case 'positions':
      for (const [id, pos] of Object.entries(msg.positions)) { const x = state.notes.get(Number(id)); if (x) x.position = pos; }
      scheduleRender();
      break;
    case 'error': toast(`Synchronisationsfehler: ${msg.message}`); break;
    default: break;
  }
}

async function fetchNote(id, reset = false) {
  try {
    const view = await api('GET', `api/notes/${id}`);
    const n = state.notes.get(id);
    if (n && reset) { Object.assign(n, metaOf(view)); n.ns.reset(view); scheduleRender(); } else upsertNote(view);
  } catch (e) {
    if (e.status === 404) removeNote(id); else handleError(e);
  }
}

async function reloadAll(spin = false) {
  const btn = $('#refresh-btn');
  if (spin) btn.classList.add('spin');
  try {
    const [notes, labels] = await Promise.all([api('GET', 'api/notes'), api('GET', 'api/labels')]);
    state.labels = labels;
    const ids = new Set(notes.map(v => v.id));
    for (const v of notes) upsertNote(v);
    for (const id of [...state.notes.keys()]) if (!ids.has(id)) removeNote(id);
    syncRoute();
    render();
  } catch (e) {
    handleError(e);
  } finally {
    if (spin) setTimeout(() => btn.classList.remove('spin'), 400);
  }
}

function onOpen() {
  state.online = true;
  if (!ready) return;
  for (const n of state.notes.values()) if (n.ns.pending()) n.ns.catchup();
  reloadAll();
  if (editor) conn.send({ t: 'open', id: editor.note.id });
}

// ===========================================================================
// Anmeldung & Start
// ===========================================================================
async function showApp() {
  $('#login').classList.add('hidden');
  $('#app').classList.remove('hidden');
  state.layout = store.get('layout', 'grid') === 'list' ? 'list' : 'grid';
  applyTheme();
  renderAvatar();
  renderComposer();
  ready = false;
  queue = [];
  conn = new Connection({
    onMessage,
    onOpen,
    onStatus: online => { state.online = online; if (state.user) renderHeader(); if (!online && ready) toast('Verbindung unterbrochen – Änderungen werden gespeichert, sobald sie wieder besteht.'); },
    onAuthLost: () => showLogin(),
  });
  app.conn = conn;
  state.clientId = conn.clientId;
  conn.connect();
  try {
    const users = await api('GET', 'api/users');
    for (const u of users) state.users.set(u.id, u);
  } catch (e) { handleError(e); }
  await reloadAll();
  ready = true;
  const pending = queue;
  queue = [];
  for (const m of pending) onMessage(m);
}

function showLogin() {
  if (conn) { conn.stop(); conn = null; app.conn = null; }
  state.user = null;
  state.notes.clear();
  state.labels = [];
  state.selected.clear();
  editor = null;
  composer.expanded = false;
  closePopover();
  while (modals.length) { const m = modals.pop(); m.backdrop.remove(); }
  document.body.classList.remove('modal-open', 'nav-open', 'selecting', 'searching');
  $('#app').classList.add('hidden');
  $('#login').classList.remove('hidden');
  $('#login-user').focus();
}
app.showLogin = showLogin;

async function logout() {
  try { await api('POST', 'api/logout', undefined, { auth: false }); } catch { /* egal */ }
  showLogin();
}
app.logout = logout;

async function boot() {
  try {
    const me = await api('GET', 'api/me', undefined, { auth: false });
    state.user = me;
    state.settings = me.settings || {};
    await showApp();
  } catch (e) {
    showLogin();
    if (e.status !== 401) $('#login-error').textContent = e.message;
  }
}

app.labelChips = D.labelChips;
app.labelPicker = D.labelPicker;
app.palette = D.palette;

function init() {
  setIcon($('#menu-btn'), 'menu');
  setIcon($('#search-icon'), 'search');
  setIcon($('#search-clear'), 'close');
  setIcon($('#refresh-btn'), 'refresh');
  setIcon($('#settings-btn'), 'settings');
  applyTheme();
  darkMq.addEventListener('change', applyTheme);
  // Auf Tablets standardmäßig nur die schmale Icon-Leiste zeigen (mehr Platz für Notizen)
  document.body.classList.toggle('nav-collapsed', store.get('nav', window.innerWidth <= 1100 ? '1' : '0') === '1');

  $('#login-form').addEventListener('submit', async e => {
    e.preventDefault();
    const err = $('#login-error');
    const btn = $('#login-submit');
    const username = $('#login-user').value.trim();
    const password = $('#login-pass').value;
    err.textContent = '';
    if (!username || !password) { err.textContent = 'Bitte Benutzername und Passwort eingeben.'; return; }
    btn.disabled = true;
    try {
      const me = await api('POST', 'api/login', { username, password }, { auth: false });
      state.user = me;
      state.settings = me.settings || {};
      $('#login-pass').value = '';
      await showApp();
    } catch (e2) {
      err.textContent = e2.message;
      animate($('.login-card'), [{ transform: 'translateX(0)' }, { transform: 'translateX(-8px)' }, { transform: 'translateX(8px)' }, { transform: 'translateX(0)' }], { duration: 260 });
    } finally {
      btn.disabled = false;
    }
  });

  $('#menu-btn').addEventListener('click', () => {
    if (isNarrow()) {
      document.body.classList.toggle('nav-open');
    } else {
      const collapsed = !document.body.classList.contains('nav-collapsed');
      store.set('nav', collapsed ? '1' : '0');
      document.body.classList.toggle('nav-collapsed', collapsed);
      setTimeout(renderMain, 220);
    }
  });
  $('#sidebar-scrim').addEventListener('click', () => document.body.classList.remove('nav-open'));

  const input = $('#search-input');
  input.addEventListener('focus', enterSearch);
  input.addEventListener('input', () => {
    state.search = input.value;
    $('#search-clear').classList.toggle('hidden', !state.search && !state.filter);
    if (!state.searching) enterSearch(); else renderMain();
  });
  input.addEventListener('keydown', e => {
    if (e.key === 'Backspace' && !input.value && state.filter) { state.filter = null; renderHeader(); renderMain(); }
  });
  $('#search-icon').addEventListener('click', () => { if (state.searching) exitSearch(); else input.focus(); });
  $('#search-clear').addEventListener('click', () => {
    input.value = '';
    state.search = '';
    state.filter = null;
    renderHeader();
    renderMain();
    input.focus();
  });

  $('#refresh-btn').addEventListener('click', () => reloadAll(true));
  $('#layout-btn').addEventListener('click', toggleLayout);
  $('#settings-btn').addEventListener('click', e => openPopover(e.currentTarget, menu([
    { label: 'Einstellungen', icon: 'settings', action: D.openSettings, hint: hintFor('settings') },
    { label: document.documentElement.dataset.theme === 'dark' ? 'Helles Design aktivieren' : 'Dunkles Design aktivieren', icon: 'dark_mode', action: () => updateSettings({ theme: document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark' }) },
    { label: 'Tastenkürzel', icon: 'keyboard', action: D.openShortcutHelp, hint: hintFor('help') },
  ]), { align: 'end' }));
  $('#account-btn').addEventListener('click', e => D.accountPopover(e.currentTarget));

  // Klick außerhalb: zuerst Popover schließen, sonst Eingabefeld schließen (speichert die Notiz)
  document.addEventListener('mousedown', e => {
    const pop = $('#popover');
    if (popoverOwner()) {
      if (!pop.contains(e.target) && !popoverOwner().contains(e.target)) closePopover();
      return;
    }
    if (composer.expanded && !$('#composer-wrap').contains(e.target) && !$('#modal-root').contains(e.target) && !$('#snackbar').contains(e.target)) {
      closeComposer();
    }
    if (state.searching && !state.search && !state.filter && !$('#search').contains(e.target) && !$('#notes-area').contains(e.target) && !$('#modal-root').contains(e.target) && e.target.closest('#topbar, #main')) {
      exitSearch();
    }
  }, true);

  document.addEventListener('keydown', onGlobalKey, true);
  window.addEventListener('hashchange', applyRoute);

  // Kopfzeile: Schatten beim Scrollen, auf kleinen Bildschirmen beim Runterscrollen ausblenden
  let lastY = 0;
  window.addEventListener('scroll', () => {
    const y = window.scrollY;
    const bar = $('#topbar');
    bar.classList.toggle('scrolled', y > 0);
    if (window.innerWidth <= 600 && !state.selected.size) bar.classList.toggle('hide-up', y > lastY && y > 80);
    else bar.classList.remove('hide-up');
    lastY = y;
  }, { passive: true });
  let lastWidth = window.innerWidth;
  window.addEventListener('resize', debounce(() => {
    if (state.user && window.innerWidth !== lastWidth) renderMain();
    lastWidth = window.innerWidth;
    if (!isNarrow()) document.body.classList.remove('nav-open');
  }, 120));
  // Sichtbarer Bereich bei eingeblendeter Bildschirmtastatur (Mobilgeräte)
  if (window.visualViewport) {
    const setVh = () => document.documentElement.style.setProperty('--vvh', `${window.visualViewport.height}px`);
    window.visualViewport.addEventListener('resize', setVh);
    setVh();
  }
  window.addEventListener('beforeunload', e => {
    const pending = [...state.notes.values()].some(n => n.ns.pending());
    if (pending || composer.expanded) { e.preventDefault(); e.returnValue = ''; }
  });
  boot();
}

init();
void hideToast;
void rid;