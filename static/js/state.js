/* state.js – gemeinsamer Zustand, API, Einstellungen, Tastenkürzel */

export const state = {
  user: null,
  users: new Map(),        // id → {id, username, avatar}
  notes: new Map(),        // id → Notiz-Ansicht (Meta-Daten) + .ns (NoteState)
  labels: [],
  view: 'notes',           // notes | archive | trash | label | search
  labelId: null,
  search: '',
  filter: null,            // {kind: 'type'|'label'|'thing'|'color', value, label}
  searching: false,
  layout: 'grid',
  selected: new Set(),
  focusedId: null,
  online: false,
  settings: {},
  clientId: '',
};

/** Registry für Funktionen, die Module untereinander aufrufen (vermeidet zirkuläre Imports). */
export const app = {};

export const store = {
  get(key, fallback) {
    try { const v = localStorage.getItem('notizen.' + key); return v === null ? fallback : v; } catch { return fallback; }
  },
  set(key, value) { try { localStorage.setItem('notizen.' + key, value); } catch { /* egal */ } },
};

export class AuthError extends Error {}

export async function api(method, url, body, { auth = true, raw = false, contentType } = {}) {
  const opts = { method, headers: { 'X-Requested-With': 'notizen' }, credentials: 'same-origin' };
  if (state.clientId) opts.headers['X-Client-Id'] = state.clientId;
  if (raw) {
    opts.body = body;
    opts.headers['Content-Type'] = contentType || body.type || 'application/octet-stream';
  } else if (body !== undefined) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  let res;
  try { res = await fetch(url, opts); } catch { throw new Error('Keine Verbindung zum Server'); }
  if (res.status === 401 && auth) {
    if (app.showLogin) app.showLogin();
    throw new AuthError('Nicht angemeldet');
  }
  if (!res.ok) {
    let msg = `Fehler ${res.status}`;
    try {
      const data = await res.json();
      if (typeof data.detail === 'string') msg = data.detail;
      else if (Array.isArray(data.detail)) msg = 'Ungültige Eingabe';
    } catch { /* kein JSON */ }
    const err = new Error(msg);
    err.status = res.status;
    throw err;
  }
  return res.status === 204 ? null : res.json();
}

// ---------------------------------------------------------------------------
// Einstellungen (werden pro Benutzer auf dem Server gespeichert)
// ---------------------------------------------------------------------------
export const DEFAULT_SETTINGS = {
  theme: 'system',            // system | light | dark
  addItemsBottom: true,       // neue Listenelemente unten hinzufügen
  checkedBottom: true,        // abgehakte Elemente nach unten verschieben
  shortcutsEnabled: true,
  shortcuts: {},              // Aktion → [Tastenkombinationen] (nur Abweichungen vom Standard)
};

export const setting = key => (key in state.settings ? state.settings[key] : DEFAULT_SETTINGS[key]);

let saveTimer = null;
export function updateSettings(patch) {
  state.settings = { ...state.settings, ...patch };
  if (app.applySettings) app.applySettings();
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    api('PUT', '/api/me/settings', { settings: state.settings }).catch(e => app.handleError && app.handleError(e));
  }, 300);
}

// ---------------------------------------------------------------------------
// Tastenkürzel
// ---------------------------------------------------------------------------
export const SHORTCUTS = [
  // [Aktion, Beschreibung, Standard, Bereich, Gruppe]
  ['newNote', 'Neue Notiz', ['C'], 'global', 'Navigation'],
  ['newList', 'Neue Liste', ['L'], 'global', 'Navigation'],
  ['search', 'Notizen durchsuchen', ['/'], 'global', 'Navigation'],
  ['next', 'Nächste Notiz', ['J'], 'global', 'Navigation'],
  ['prev', 'Vorherige Notiz', ['K'], 'global', 'Navigation'],
  ['moveNext', 'Notiz nach hinten verschieben', ['Shift+J'], 'global', 'Navigation'],
  ['movePrev', 'Notiz nach vorne verschieben', ['Shift+K'], 'global', 'Navigation'],
  ['open', 'Fokussierte Notiz öffnen', ['Enter', 'O'], 'global', 'Navigation'],
  ['toggleLayout', 'Raster-/Listenansicht', ['Mod+G'], 'global', 'Ansicht'],
  ['toggleTheme', 'Hell/Dunkel wechseln', ['Shift+D'], 'global', 'Ansicht'],
  ['refresh', 'Aktualisieren', ['Shift+R'], 'global', 'Ansicht'],
  ['help', 'Tastenkürzel anzeigen', ['?'], 'global', 'Ansicht'],
  ['settings', 'Einstellungen öffnen', ['Mod+,'], 'global', 'Ansicht'],
  ['select', 'Notiz auswählen', ['X'], 'global', 'Aktionen'],
  ['selectAll', 'Alle Notizen auswählen', ['Mod+A'], 'global', 'Aktionen'],
  ['archive', 'Archivieren / Archivierung aufheben', ['E'], 'global', 'Aktionen'],
  ['trash', 'In den Papierkorb', ['#'], 'global', 'Aktionen'],
  ['pin', 'Anheften / Loslösen', ['F'], 'global', 'Aktionen'],
  ['closeEditor', 'Notiz schließen', ['Mod+Enter'], 'editor', 'Editor'],
  ['toggleChecks', 'Kästchen anzeigen/ausblenden', ['Mod+Shift+8'], 'editor', 'Editor'],
  ['undo', 'Rückgängig', ['Mod+Z'], 'editor', 'Editor'],
  ['redo', 'Wiederholen', ['Mod+Shift+Z', 'Mod+Y'], 'editor', 'Editor'],
  ['bold', 'Fett', ['Mod+B'], 'editor', 'Formatierung'],
  ['italic', 'Kursiv', ['Mod+I'], 'editor', 'Formatierung'],
  ['underline', 'Unterstrichen', ['Mod+U'], 'editor', 'Formatierung'],
  ['h1', 'Überschrift 1', ['Mod+Alt+1'], 'editor', 'Formatierung'],
  ['h2', 'Überschrift 2', ['Mod+Alt+2'], 'editor', 'Formatierung'],
  ['normalText', 'Normaler Text', ['Mod+Alt+0'], 'editor', 'Formatierung'],
  ['clearFormat', 'Formatierung entfernen', ['Mod+\\'], 'editor', 'Formatierung'],
];
export const SHORTCUT_SCOPE = Object.fromEntries(SHORTCUTS.map(s => [s[0], s[3]]));

const IS_MAC = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

export function comboFromEvent(e) {
  let key = e.key;
  if (!key || ['Control', 'Meta', 'Alt', 'Shift', 'AltGraph', 'CapsLock', 'Dead', 'Unidentified', 'Process'].includes(key)) return null;
  const mods = [];
  const mod = IS_MAC ? e.metaKey : e.ctrlKey;
  // AltGr (Strg+Alt unter Windows) erzeugt Sonderzeichen – nicht als Kürzel werten
  const altGr = e.getModifierState && e.getModifierState('AltGraph');
  if (mod && !altGr) mods.push('Mod');
  if (e.altKey && !altGr) mods.push('Alt');
  const useCode = (mods.length > 0) && /^(Key[A-Z]|Digit\d)$/.test(e.code || '');
  if (useCode) key = e.code.replace(/^Key|^Digit/, '');
  else if (key.length === 1) key = key.toUpperCase();
  if (key === ' ') key = 'Space';
  const symbol = key.length === 1 && !/[A-Z0-9]/.test(key);
  if (e.shiftKey && (!symbol || useCode)) mods.push('Shift');
  return [...mods, key].join('+');
}

export function comboLabel(combo) {
  return combo.split('+').map(p => ({
    Mod: IS_MAC ? '⌘' : 'Strg', Alt: IS_MAC ? '⌥' : 'Alt', Shift: IS_MAC ? '⇧' : 'Umschalt',
    Escape: 'Esc', ArrowUp: '↑', ArrowDown: '↓', ArrowLeft: '←', ArrowRight: '→', Space: 'Leertaste',
  }[p] || p)).join(IS_MAC ? '' : '+');
}

export function bindingsFor(action) {
  const custom = (setting('shortcuts') || {})[action];
  if (Array.isArray(custom)) return custom;
  const def = SHORTCUTS.find(s => s[0] === action);
  return def ? def[2] : [];
}

export function keymap() {
  const map = new Map();
  for (const [action] of SHORTCUTS) for (const c of bindingsFor(action)) if (!map.has(c)) map.set(c, action);
  return map;
}

export const hintFor = action => { const b = bindingsFor(action); return b.length ? comboLabel(b[0]) : ''; };
