/* dialogs.js – Dialoge und Popover-Inhalte */
import { Delta } from './delta.js';
import { renderHTML } from './editor.js';
import { docFromView } from './sync.js';
import {
  h, icon, iconBtn, avatar, openModal, openPopover, closePopover, menu, toast, confirmDialog, fmtDate, fmtDateLong, animate,
} from './dom.js';
import {
  state, app, api, AuthError, setting, updateSettings, SHORTCUTS, bindingsFor, comboFromEvent, comboLabel,
} from './state.js';

export const COLORS = [
  ['default', 'Standard'], ['red', 'Koralle'], ['orange', 'Pfirsich'], ['yellow', 'Sand'],
  ['green', 'Minze'], ['teal', 'Salbei'], ['blue', 'Nebel'], ['darkblue', 'Sturm'],
  ['purple', 'Lavendel'], ['pink', 'Blüte'], ['brown', 'Lehm'], ['gray', 'Kreide'],
];

const byName = (a, b) => a.name.localeCompare(b.name, 'de', { sensitivity: 'base' });
export const sortedLabels = () => [...state.labels].sort(byName);

// ---------------------------------------------------------------------------
// Farbpalette, Label-Auswahl, Label-Chips
// ---------------------------------------------------------------------------
export function palette(current, onPick) {
  const wrap = h('div', { class: 'palette', role: 'menu', 'aria-label': 'Hintergrundfarbe' });
  for (const [color, name] of COLORS) {
    const btn = h('button', {
      type: 'button', class: 'swatch' + (color === current ? ' selected' : ''), dataset: { color },
      title: name, 'aria-label': name, onclick: () => { closePopover(); onPick(color); },
    });
    if (color === 'default') btn.append(icon('format_color_reset', 18));
    if (color === current) btn.append(h('span', { class: 'swatch-check' }, icon('check', 14)));
    wrap.append(btn);
  }
  return wrap;
}

export function labelPicker(getIds, setIds) {
  const input = h('input', { type: 'text', placeholder: 'Labelnamen eingeben', maxlength: '100', 'aria-label': 'Label suchen oder erstellen' });
  const list = h('div', { class: 'label-picker-list' });
  const draw = () => {
    const q = input.value.trim();
    const ql = q.toLocaleLowerCase();
    const ids = getIds();
    list.replaceChildren();
    for (const lab of sortedLabels()) {
      if (ql && !lab.name.toLocaleLowerCase().includes(ql)) continue;
      const checked = ids.includes(lab.id);
      list.append(h('button', {
        type: 'button', class: 'check-row', role: 'menuitemcheckbox', 'aria-checked': String(checked),
        onclick: () => { const cur = getIds(); setIds(checked ? cur.filter(x => x !== lab.id) : [...cur, lab.id]); draw(); },
      }, icon(checked ? 'check_box' : 'check_box_o', 18), h('span', { text: lab.name })));
    }
    if (q && !state.labels.some(l => l.name.toLocaleLowerCase() === ql)) {
      list.append(h('button', {
        type: 'button', class: 'create-row',
        onclick: async () => {
          try {
            const lab = await createLabel(q);
            setIds([...getIds(), lab.id]);
            input.value = '';
            draw();
          } catch (e) { app.handleError(e); }
        },
      }, icon('add', 18), h('span', {}, 'Erstellen: ', h('strong', { text: `„${q}“` }))));
    }
  };
  input.addEventListener('input', draw);
  input.addEventListener('keydown', e => {
    if (e.key === 'Enter') {
      e.preventDefault();
      const target = list.querySelector('.create-row') || (input.value.trim() && list.querySelector('.check-row'));
      if (target) { target.click(); input.value = ''; draw(); }
    }
  });
  draw();
  setTimeout(() => input.focus(), 0);
  return h('div', { class: 'label-picker' },
    h('div', { class: 'popover-title', text: 'Notiz mit Label versehen' }),
    h('div', { class: 'label-picker-search' }, input, icon('search', 18)),
    list);
}

export function labelChips(ids, onRemove, navigate = true) {
  const labs = ids.map(id => state.labels.find(l => l.id === id)).filter(Boolean).sort(byName);
  if (!labs.length) return null;
  return h('div', { class: 'chips' }, labs.map(lab => {
    const chip = h('span', {
      class: 'chip', title: lab.name,
      onclick: e => { e.stopPropagation(); if (navigate) app.setView('label', lab.id); },
    }, h('span', { class: 'chip-text', text: lab.name }));
    if (onRemove) {
      chip.append(h('button', {
        type: 'button', class: 'chip-x', title: 'Label entfernen', 'aria-label': `Label ${lab.name} entfernen`,
        onclick: e => { e.stopPropagation(); onRemove(lab.id); },
      }, icon('close', 14)));
    }
    return chip;
  }));
}

export async function createLabel(name) {
  const lab = await api('POST', 'api/labels', { name });
  state.labels.push(lab);
  app.render();
  return lab;
}

// ---------------------------------------------------------------------------
// Labels bearbeiten
// ---------------------------------------------------------------------------
export function openLabelsDialog() {
  document.body.classList.remove('nav-open');
  const err = h('div', { class: 'labels-error', role: 'alert' });
  const list = h('div', { class: 'labels-list' });
  const newInput = h('input', { type: 'text', placeholder: 'Neues Label erstellen', maxlength: '100', 'aria-label': 'Neues Label erstellen' });
  const showErr = e => { if (!(e instanceof AuthError)) err.textContent = e.message; };
  const create = async () => {
    const name = newInput.value.trim();
    if (!name) return;
    err.textContent = '';
    try { await createLabel(name); newInput.value = ''; draw(); } catch (e) { showErr(e); }
  };
  newInput.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); create(); } });
  const draw = () => {
    list.replaceChildren();
    for (const lab of sortedLabels()) {
      const input = h('input', { type: 'text', maxlength: '100', 'aria-label': 'Labelname' });
      input.value = lab.name;
      const commit = async () => {
        const name = input.value.trim();
        if (!name || name === lab.name) { input.value = lab.name; return; }
        try {
          const upd = await api('PATCH', `api/labels/${lab.id}`, { name });
          lab.name = upd.name;
          err.textContent = '';
          app.render();
        } catch (e) { showErr(e); input.value = lab.name; }
      };
      input.addEventListener('keydown', e => {
        if (e.key === 'Enter') input.blur();
        if (e.key === 'Escape') { e.stopPropagation(); input.value = lab.name; input.blur(); }
      });
      input.addEventListener('blur', commit);
      const remove = async () => {
        if (!await confirmDialog('Dieses Label wird gelöscht und aus allen Notizen entfernt. Die Notizen selbst bleiben erhalten.', 'Löschen')) return;
        try {
          await api('DELETE', `api/labels/${lab.id}`);
          state.labels = state.labels.filter(l => l !== lab);
          for (const n of state.notes.values()) n.labels = n.labels.filter(x => x !== lab.id);
          if (state.view === 'label' && state.labelId === lab.id) app.setView('notes');
          draw();
          app.render();
        } catch (e) { showErr(e); }
      };
      list.append(h('div', { class: 'labels-row' },
        iconBtn('delete', 'Label löschen', remove), input, iconBtn('edit', 'Label umbenennen', () => input.focus())));
    }
  };
  draw();
  const content = h('div', { class: 'dialog labels-dialog' },
    h('h3', { class: 'dialog-title', text: 'Labels bearbeiten' }),
    h('div', { class: 'labels-row new' },
      iconBtn('close', 'Eingabe leeren', () => { newInput.value = ''; newInput.focus(); }),
      newInput,
      iconBtn('check', 'Label erstellen', create)),
    err, list,
    h('div', { class: 'dialog-actions' }, h('button', { type: 'button', class: 'text-btn', onclick: () => m.close() }, 'Fertig')));
  const m = openModal(content, { className: 'small', label: 'Labels bearbeiten' });
  newInput.focus();
}

// ---------------------------------------------------------------------------
// Mitbearbeiter (Teilen)
// ---------------------------------------------------------------------------
export async function openShareDialog(note, { onSave } = {}) {
  try {
    const users = await api('GET', 'api/users');
    for (const u of users) state.users.set(u.id, u);
  } catch (e) { app.handleError(e); }
  const me = state.user;
  const isDraft = !note.id;
  const owner = isDraft ? me : ((note.members || []).find(m => m.role === 'owner') || me);
  let members = isDraft ? [...(note.collaborators || [])] : (note.members || []).filter(m => m.role !== 'owner');
  const isOwner = owner.id === me.id;
  const list = h('div', { class: 'share-list' });
  const input = h('input', { type: 'text', class: 'share-input', placeholder: 'Person zum Teilen eingeben', 'aria-label': 'Person zum Teilen eingeben', autocomplete: 'off' });
  const sugg = h('div', { class: 'share-sugg', role: 'listbox' });
  let active = 0;

  const draw = () => {
    list.replaceChildren(h('div', { class: 'share-row' }, avatar(owner, 36),
      h('div', { class: 'share-name' }, h('strong', { text: owner.username }), h('span', { class: 'muted', text: ' (Inhaber)' }))));
    for (const u of members) {
      const canRemove = isOwner || u.id === me.id;
      list.append(h('div', { class: 'share-row' }, avatar(u, 36),
        h('div', { class: 'share-name', text: u.username + (u.id === me.id ? ' (du)' : '') }),
        canRemove ? iconBtn('close', 'Entfernen', () => { members = members.filter(x => x.id !== u.id); draw(); }) : null));
    }
    list.append(h('div', { class: 'share-row add' }, h('span', { class: 'share-add-icon' }, icon('person_add_o', 20)), input));
  };
  const candidates = () => {
    const q = input.value.trim().toLocaleLowerCase();
    if (!q) return [];
    const taken = new Set([owner.id, ...members.map(m => m.id)]);
    return [...state.users.values()].filter(u => !taken.has(u.id) && u.username.toLocaleLowerCase().includes(q)).slice(0, 8);
  };
  const add = u => { members.push(u); input.value = ''; draw(); drawSugg(); input.focus(); };
  const drawSugg = () => {
    const c = candidates();
    sugg.replaceChildren(...c.map((u, i) => h('button', {
      type: 'button', class: 'sugg-item' + (i === active ? ' active' : ''), role: 'option',
      onmousedown: e => { e.preventDefault(); add(u); },
    }, avatar(u, 28), h('span', { text: u.username }))));
    if (input.value.trim() && !c.length) sugg.append(h('div', { class: 'sugg-empty', text: 'Kein Benutzer gefunden' }));
    sugg.classList.toggle('hidden', !input.value.trim());
  };
  input.addEventListener('input', () => { active = 0; drawSugg(); });
  input.addEventListener('keydown', e => {
    const c = candidates();
    if (e.key === 'ArrowDown') { e.preventDefault(); active = Math.min(active + 1, c.length - 1); drawSugg(); }
    if (e.key === 'ArrowUp') { e.preventDefault(); active = Math.max(active - 1, 0); drawSugg(); }
    if (e.key === 'Enter') { e.preventDefault(); if (c[active]) add(c[active]); else save(); }
  });
  const save = async () => {
    if (isDraft) { onSave && onSave(members); m.close(); return; }
    const leaving = !isOwner && !members.some(x => x.id === me.id);
    if (leaving && !await confirmDialog('Du wirst als Mitbearbeiter entfernt und siehst diese Notiz nicht mehr.', 'Entfernen')) return;
    try {
      const res = await api('PUT', `api/notes/${note.id}/collaborators`, { user_ids: members.map(x => x.id) });
      m.close();
      if (res && res.left) app.removeNote(note.id, 'Du bist kein Mitbearbeiter dieser Notiz mehr');
      else if (res) app.upsertNote(res);
    } catch (e) { app.handleError(e); }
  };
  draw();
  const content = h('div', { class: 'dialog share-dialog' },
    h('h3', { class: 'dialog-title', text: 'Mitbearbeiter' }),
    h('div', { class: 'share-body' }, list, sugg),
    h('div', { class: 'dialog-actions' },
      h('button', { type: 'button', class: 'text-btn', onclick: () => m.close() }, 'Abbrechen'),
      h('button', { type: 'button', class: 'text-btn primary', onclick: save }, 'Speichern')));
  drawSugg();
  const m = openModal(content, { className: 'small share', label: 'Mitbearbeiter' });
  input.focus();
}

// ---------------------------------------------------------------------------
// Versionsverlauf
// ---------------------------------------------------------------------------
export async function openVersions(note) {
  let versions;
  try { versions = await api('GET', `api/notes/${note.id}/versions`); } catch (e) { app.handleError(e); return; }
  const preview = h('div', { class: 'ver-preview', 'data-color': note.color || 'default' });
  const listEl = h('div', { class: 'ver-list', role: 'listbox', 'aria-label': 'Versionen' });
  const restoreBtn = h('button', { type: 'button', class: 'btn-primary' }, 'Wiederherstellen');
  let current = null;
  const cache = new Map();

  const showDoc = (doc) => {
    const d = docFromView(doc);
    const parts = [];
    if (d.title) parts.push(h('div', { class: 'ver-title', text: d.title }));
    if (d.is_list) {
      const ul = h('div', { class: 'ver-items' });
      for (const it of d.items) ul.append(h('div', { class: 'li' + (it.checked ? ' done' : '') }, icon(it.checked ? 'check_box' : 'check_box_o', 18), h('span', { text: it.text })));
      parts.push(ul);
    } else {
      const body = h('div', { class: 'ver-body note-body' });
      body.innerHTML = renderHTML(new Delta(d.body), { rich: true, links: true });
      parts.push(body);
    }
    preview.replaceChildren(...parts);
  };
  const select = async (v, idx) => {
    current = v;
    for (const el of listEl.children) el.classList.toggle('active', el.dataset.id === String(v.id));
    restoreBtn.disabled = idx === 0;
    restoreBtn.textContent = idx === 0 ? 'Aktuelle Version' : 'Wiederherstellen';
    try {
      if (!cache.has(v.id)) cache.set(v.id, await api('GET', `api/notes/${note.id}/versions/${v.id}`));
      if (current === v) showDoc(cache.get(v.id));
    } catch (e) { app.handleError(e); }
  };
  versions.forEach((v, idx) => {
    const editors = h('div', { class: 'ver-editors' }, v.editors.map(u => h('span', { class: 'ver-editor' }, avatar(u, 18), h('span', { text: u.username }))));
    listEl.append(h('button', {
      type: 'button', class: 'ver-item', role: 'option', dataset: { id: v.id }, onclick: () => select(v, idx),
    }, h('div', { class: 'ver-time', text: idx === 0 ? `${fmtDateLong(v.updated_at)} · Aktuell` : fmtDateLong(v.updated_at) }), editors));
  });
  restoreBtn.addEventListener('click', async () => {
    if (!current) return;
    try {
      await api('POST', `api/notes/${note.id}/versions/${current.id}/restore`);
      m.close();
      toast(`Version vom ${fmtDate(current.updated_at)} wiederhergestellt`);
    } catch (e) { app.handleError(e); }
  });
  const content = h('div', { class: 'versions' },
    h('div', { class: 'ver-head' }, h('h3', { class: 'dialog-title', text: 'Versionsverlauf' }), iconBtn('close', 'Schließen', () => m.close())),
    h('div', { class: 'ver-main' }, preview, h('div', { class: 'ver-side' }, listEl)),
    h('div', { class: 'dialog-actions ver-actions' }, h('span', { class: 'muted ver-hint', text: 'Wiederherstellen ersetzt den Inhalt für alle Mitbearbeiter.' }),
      h('button', { type: 'button', class: 'text-btn', onclick: () => m.close() }, 'Abbrechen'), restoreBtn));
  const m = openModal(content, { className: 'versions-modal', label: 'Versionsverlauf' });
  if (versions.length) select(versions[0], 0); else preview.textContent = 'Keine Versionen vorhanden.';
}

// ---------------------------------------------------------------------------
// Bildbetrachter
// ---------------------------------------------------------------------------
export function openLightbox(images, index, { onDelete } = {}) {
  let i = index;
  const img = h('img', { class: 'lb-img', alt: '' });
  const counter = h('span', { class: 'lb-count' });
  const prev = iconBtn('arrow_back', 'Vorheriges Bild', () => go(-1), 'lb-nav lb-prev');
  const next = iconBtn('arrow_back', 'Nächstes Bild', () => go(1), 'lb-nav lb-next');
  const go = d => { i = (i + d + images.length) % images.length; show(); };
  const show = () => {
    const it = images[i];
    img.src = it.url || `api/images/${it.id}`;
    counter.textContent = images.length > 1 ? `${i + 1} / ${images.length}` : '';
    prev.classList.toggle('hidden', images.length < 2);
    next.classList.toggle('hidden', images.length < 2);
    animate(img, [{ opacity: 0, transform: 'scale(.98)' }, { opacity: 1, transform: 'none' }], { duration: 160 });
  };
  const bar = h('div', { class: 'lb-bar' }, iconBtn('arrow_back', 'Schließen', () => m.close()), counter, h('span', { class: 'grow' }),
    onDelete ? iconBtn('delete', 'Bild entfernen', async () => { const it = images[i]; m.close(); onDelete(it); }) : null);
  const content = h('div', { class: 'lightbox' }, bar, h('div', { class: 'lb-stage', onclick: e => { if (e.target.classList.contains('lb-stage')) m.close(); } }, prev, img, next));
  content.addEventListener('keydown', e => { if (e.key === 'ArrowLeft') go(-1); if (e.key === 'ArrowRight') go(1); });
  content.tabIndex = -1;
  const m = openModal(content, { className: 'lightbox-modal', label: 'Bild' });
  show();
  content.focus();
}

// ---------------------------------------------------------------------------
// Einstellungen inkl. Tastenkürzel
// ---------------------------------------------------------------------------
function toggleRow(label, key, help) {
  const input = h('input', { type: 'checkbox', role: 'switch' });
  input.checked = !!setting(key);
  input.addEventListener('change', () => updateSettings({ [key]: input.checked }));
  return h('label', { class: 'set-row' }, h('span', { class: 'set-label' }, h('span', { text: label }), help ? h('small', { text: help }) : null), h('span', { class: 'switch' }, input, h('span', { class: 'track' })));
}

export function openSettings() {
  closePopover();
  const themeRow = h('div', { class: 'set-row' }, h('span', { class: 'set-label', text: 'Design' }),
    h('div', { class: 'seg' }, [['system', 'System'], ['light', 'Hell'], ['dark', 'Dunkel']].map(([v, l]) => {
      const b = h('button', { type: 'button', class: setting('theme') === v ? 'on' : '', onclick: () => { updateSettings({ theme: v }); for (const x of b.parentNode.children) x.classList.toggle('on', x === b); } }, l);
      return b;
    })));
  const scList = h('div', { class: 'sc-list' });
  const drawShortcuts = () => {
    scList.replaceChildren();
    let group = null;
    for (const [action, label, , , g] of SHORTCUTS) {
      if (g !== group) { group = g; scList.append(h('div', { class: 'sc-group', text: g })); }
      const keys = bindingsFor(action);
      const custom = (setting('shortcuts') || {})[action];
      const keysEl = h('span', { class: 'sc-keys' }, keys.length ? keys.map(k => h('kbd', { text: comboLabel(k) })) : h('span', { class: 'muted', text: 'keins' }));
      const change = h('button', { type: 'button', class: 'text-btn small', onclick: () => record(action, change) }, 'Ändern');
      const reset = custom ? iconBtn('refresh', 'Standard wiederherstellen', () => {
        const sc = { ...(setting('shortcuts') || {}) };
        delete sc[action];
        updateSettings({ shortcuts: sc });
        drawShortcuts();
      }, 'small') : null;
      scList.append(h('div', { class: 'sc-row' }, h('span', { class: 'sc-label', text: label }), keysEl, change, reset));
    }
  };
  const record = (action, btn) => {
    btn.textContent = 'Taste drücken…';
    btn.classList.add('recording');
    const onKey = e => {
      e.preventDefault();
      e.stopPropagation();
      if (e.key === 'Escape') { cleanup(); drawShortcuts(); return; }
      const combo = comboFromEvent(e);
      if (!combo) return;
      cleanup();
      const sc = { ...(setting('shortcuts') || {}) };
      // Konflikte auflösen: Kombination bei anderen Aktionen entfernen
      for (const [other] of SHORTCUTS) {
        if (other === action) continue;
        const cur = bindingsFor(other);
        if (cur.includes(combo)) sc[other] = cur.filter(c => c !== combo);
      }
      sc[action] = [combo];
      updateSettings({ shortcuts: sc });
      drawShortcuts();
    };
    const cleanup = () => { window.removeEventListener('keydown', onKey, true); btn.classList.remove('recording'); };
    window.addEventListener('keydown', onKey, true);
  };
  drawShortcuts();
  const content = h('div', { class: 'dialog settings-dialog' },
    h('h3', { class: 'dialog-title', text: 'Einstellungen' }),
    h('div', { class: 'set-scroll' },
      h('div', { class: 'set-section', text: 'Notizen und Listen' }),
      toggleRow('Neue Listenelemente unten hinzufügen', 'addItemsBottom'),
      toggleRow('Abgehakte Elemente nach unten verschieben', 'checkedBottom'),
      h('div', { class: 'set-section', text: 'Darstellung' }),
      themeRow,
      h('div', { class: 'set-section', text: 'Tastenkürzel' }),
      toggleRow('Tastenkürzel aktivieren', 'shortcutsEnabled', 'Einzeltasten wie C, J, K, E gelten nur außerhalb von Textfeldern.'),
      scList,
      h('div', { class: 'set-reset' }, h('button', { type: 'button', class: 'text-btn', onclick: () => { updateSettings({ shortcuts: {} }); drawShortcuts(); } }, 'Alle Tastenkürzel zurücksetzen'))),
    h('div', { class: 'dialog-actions' }, h('button', { type: 'button', class: 'text-btn primary', onclick: () => m.close() }, 'Fertig')));
  const m = openModal(content, { className: 'small settings', label: 'Einstellungen' });
}

export function openShortcutHelp() {
  const groups = new Map();
  for (const [action, label, , , g] of SHORTCUTS) {
    if (!groups.has(g)) groups.set(g, []);
    groups.get(g).push([action, label]);
  }
  const fixed = [['Esc', 'Schließen / Auswahl aufheben'], ['↑ ↓', 'Zwischen Titel und Listenelementen wechseln']];
  const content = h('div', { class: 'dialog help-dialog' },
    h('div', { class: 'ver-head' }, h('h3', { class: 'dialog-title', text: 'Tastenkürzel' }), iconBtn('close', 'Schließen', () => m.close())),
    h('div', { class: 'help-grid' },
      [...groups].map(([g, rows]) => h('section', {},
        h('h4', { text: g }),
        rows.map(([action, label]) => h('div', { class: 'help-row' }, h('span', { text: label }), h('span', { class: 'sc-keys' }, bindingsFor(action).map(k => h('kbd', { text: comboLabel(k) })))))),
      ),
      h('section', {}, h('h4', { text: 'Allgemein' }), fixed.map(([k, l]) => h('div', { class: 'help-row' }, h('span', { text: l }), h('span', { class: 'sc-keys' }, h('kbd', { text: k })))))),
    h('div', { class: 'dialog-actions' },
      setting('shortcutsEnabled') ? null : h('span', { class: 'muted', text: 'Tastenkürzel sind derzeit deaktiviert.' }),
      h('button', { type: 'button', class: 'text-btn', onclick: () => { m.close(); openSettings(); } }, 'Anpassen'),
      h('button', { type: 'button', class: 'text-btn primary', onclick: () => m.close() }, 'Fertig')));
  const m = openModal(content, { className: 'help', label: 'Tastenkürzel' });
}

// ---------------------------------------------------------------------------
// Konto: Passwort, Profilbild
// ---------------------------------------------------------------------------
export function openPasswordDialog() {
  const field = (label, autocomplete) => {
    const input = h('input', { type: 'password', autocomplete, required: true, maxlength: '1024' });
    return [h('label', { class: 'field' }, h('span', { text: label }), input), input];
  };
  const [f1, oldPw] = field('Aktuelles Passwort', 'current-password');
  const [f2, newPw] = field('Neues Passwort (mind. 8 Zeichen)', 'new-password');
  const [f3, newPw2] = field('Neues Passwort wiederholen', 'new-password');
  const err = h('div', { class: 'form-error', role: 'alert' });
  const form = h('form', { class: 'dialog', novalidate: true },
    h('h3', { class: 'dialog-title', text: 'Passwort ändern' }), f1, f2, f3, err,
    h('div', { class: 'dialog-actions' },
      h('button', { type: 'button', class: 'text-btn', onclick: () => m.close() }, 'Abbrechen'),
      h('button', { type: 'submit', class: 'text-btn primary' }, 'Speichern')));
  form.addEventListener('submit', async e => {
    e.preventDefault();
    err.textContent = '';
    if (newPw.value !== newPw2.value) { err.textContent = 'Die neuen Passwörter stimmen nicht überein.'; return; }
    try {
      await api('POST', 'api/password', { old_password: oldPw.value, new_password: newPw.value });
      m.close();
      toast('Passwort geändert. Andere Geräte wurden abgemeldet.');
    } catch (e2) { if (!(e2 instanceof AuthError)) err.textContent = e2.message; }
  });
  const m = openModal(form, { className: 'small', label: 'Passwort ändern' });
  oldPw.focus();
}

export function openQrLogin() {
  let timer = null;
  let challenge = null;
  const status = h('p', { class: 'muted qr-status', text: 'Sichere Verbindung wird vorbereitet …', role: 'status', 'aria-live': 'polite' });
  const image = h('img', { class: 'qr-image hidden', alt: 'QR-Code zur Geräteanmeldung' });
  const content = h('div', { class: 'dialog qr-dialog' },
    h('div', { class: 'ver-head' }, h('h3', { class: 'dialog-title', text: 'Mit Gerät anmelden' }),
      iconBtn('close', 'Schließen', () => m.close())),
    image,
    status,
    h('p', { class: 'muted', text: 'Scanne den Code mit einem bereits angemeldeten Gerät und bestätige die Anmeldung dort.' }),
    h('div', { class: 'dialog-actions' }, h('button', { type: 'button', class: 'text-btn', onclick: () => m.close() }, 'Abbrechen')));
  const m = openModal(content, { className: 'small', label: 'Mit Gerät anmelden', onClose: () => clearInterval(timer) });
  (async () => {
    try {
      const data = await api('POST', '/api/device-login/challenge');
      challenge = data.challenge;
      image.src = `/api/device-login/qr?challenge=${encodeURIComponent(challenge)}`;
      image.classList.remove('hidden');
      status.textContent = 'Warte auf Bestätigung auf deinem anderen Gerät …';
      timer = setInterval(async () => {
        try {
          const result = await api('POST', '/api/device-login/status', { challenge });
          if (result.status === 'approved') {
            clearInterval(timer);
            m.close();
            location.reload();
          }
        } catch (e) {
          if (e.status === 410) {
            clearInterval(timer);
            status.textContent = 'Der QR-Code ist abgelaufen. Bitte starte die Anmeldung erneut.';
          } else if (!(e instanceof AuthError)) status.textContent = 'Verbindung unterbrochen – Anmeldung wird weiter geprüft …';
        }
      }, 1400);
    } catch (e) { status.textContent = e.message; }
  })();
}

export async function openSessionsDialog() {
  const rows = h('div', { class: 'session-list' });
  const content = h('div', { class: 'dialog account-manage-dialog' },
    h('div', { class: 'ver-head' }, h('h3', { class: 'dialog-title', text: 'Angemeldete Geräte' }),
      iconBtn('close', 'Schließen', () => m.close())),
    h('p', { class: 'muted', text: 'Beende Sitzungen auf Geräten, die du nicht mehr verwendest.' }),
    rows,
    h('div', { class: 'dialog-actions' }, h('button', { type: 'button', class: 'text-btn primary', onclick: () => m.close() }, 'Fertig')));
  const m = openModal(content, { className: 'small', label: 'Angemeldete Geräte' });
  rows.textContent = 'Lade Sitzungen …';
  const draw = async () => {
    try {
      const sessions = await api('GET', '/api/me/sessions');
      rows.replaceChildren();
      if (!sessions.length) { rows.append(h('p', { class: 'muted', text: 'Keine aktiven Sitzungen.' })); return; }
      for (const s of sessions) {
        const label = s.current ? 'Dieses Gerät' : (s.user_agent || 'Unbekanntes Gerät').slice(0, 140);
        const detail = s.current ? 'Aktuelle Sitzung' : `Zuletzt aktiv: ${s.last_seen ? fmtDateLong(s.last_seen) : 'unbekannt'}`;
        const revoke = s.current ? null : h('button', { type: 'button', class: 'text-btn danger', onclick: async () => {
          if (!await confirmDialog('Dieses Gerät abmelden?', 'Abmelden')) return;
          try { await api('DELETE', `/api/me/sessions/${encodeURIComponent(s.id)}`); await draw(); }
          catch (e) { toast(e.message); }
        } }, 'Abmelden');
        rows.append(h('div', { class: 'session-row' }, h('div', { class: 'session-copy' },
          h('strong', { text: label }), h('small', { text: detail }),
          h('small', { text: `Angemeldet: ${fmtDateLong(s.created_at)}` })), revoke));
      }
    } catch (e) { rows.replaceChildren(h('p', { class: 'form-error', text: e.message })); }
  };
  await draw();
}

export async function openMagicLinksDialog() {
  const list = h('div', { class: 'magic-list' });
  const create = h('button', { type: 'button', class: 'btn-primary', text: 'Anmeldelink erstellen' });
  const content = h('div', { class: 'dialog account-manage-dialog' },
    h('div', { class: 'ver-head' }, h('h3', { class: 'dialog-title', text: 'Anmeldelinks' }),
      iconBtn('close', 'Schließen', () => m.close())),
    h('p', { class: 'muted', text: 'Ein Link funktioniert wie ein Passwort. Teile ihn nur mit dir selbst und lösche ihn, sobald du ihn nicht mehr brauchst.' }),
    list, create,
    h('div', { class: 'dialog-actions' }, h('button', { type: 'button', class: 'text-btn primary', onclick: () => m.close() }, 'Fertig')));
  const m = openModal(content, { className: 'small', label: 'Anmeldelinks' });
  const draw = async () => {
    list.replaceChildren();
    try {
      const links = await api('GET', '/api/magic-links');
      if (!links.length) list.append(h('p', { class: 'muted', text: 'Noch keine Anmeldelinks.' }));
      for (const item of links) list.append(h('div', { class: 'magic-row' },
        h('div', { class: 'session-copy' }, h('strong', { text: item.label }),
          h('small', { text: `Gültig bis ${fmtDateLong(item.expires_at)}` }),
          h('small', { text: item.last_used_at ? `Zuletzt verwendet: ${fmtDateLong(item.last_used_at)}` : 'Noch nicht verwendet' })),
        h('button', { type: 'button', class: 'text-btn danger', text: 'Löschen', onclick: async () => {
          if (!await confirmDialog('Diesen Anmeldelink unwiderruflich löschen?', 'Löschen')) return;
          try { await api('DELETE', `/api/magic-links/${item.id}`); await draw(); } catch (e) { toast(e.message); }
        } })));
    } catch (e) { list.append(h('p', { class: 'form-error', text: e.message })); }
  };
  create.addEventListener('click', async () => {
    const label = prompt('Name für diesen Anmeldelink', 'Neues Gerät');
    if (label === null) return;
    try {
      const item = await api('POST', '/api/magic-links', { label: label.trim() || 'Neues Gerät', days: 30 });
      const field = h('input', { type: 'text', readonly: true, value: item.url, class: 'magic-url', 'aria-label': 'Neuer Anmeldelink' });
      const copy = h('button', { type: 'button', class: 'text-btn primary', text: 'Link kopieren', onclick: async () => {
        try { await navigator.clipboard.writeText(item.url); copy.textContent = 'Kopiert'; } catch { field.select(); document.execCommand('copy'); copy.textContent = 'Kopiert'; }
      } });
      openModal(h('div', { class: 'dialog' }, h('h3', { class: 'dialog-title', text: 'Link sicher aufbewahren' }),
        h('p', { class: 'muted', text: 'Der Link wird nur jetzt vollständig angezeigt. Er ist 30 Tage gültig und gewährt direkten Kontozugriff.' }),
        field, h('div', { class: 'dialog-actions' }, copy,
          h('button', { type: 'button', class: 'text-btn', text: 'Schließen', onclick: () => { reveal.close(); draw(); } }))),
        { className: 'small', label: 'Neuer Anmeldelink' });
      const reveal = modals[modals.length - 1];
      field.focus(); field.select();
    } catch (e) { toast(e.message); }
  });
  await draw();
}

export function accountPopover(anchor) {
  const u = state.user;
  const big = avatar(u, 80, 'big');
  const cam = iconBtn('photo_camera', 'Profilbild ändern', () => { closePopover(); app.changeAvatar(); }, 'avatar-cam', 18);
  const usage = h('div', { class: 'account-usage' }, h('span', { text: 'Speicherbelegung wird geladen …' }));
  const items = [
    { label: 'Profilbild ändern', icon: 'photo_camera', action: () => app.changeAvatar() },
    u.avatar && { label: 'Profilbild entfernen', icon: 'delete', action: () => app.removeAvatar() },
    { label: 'Angemeldete Geräte', icon: 'devices', action: openSessionsDialog },
    { label: 'Anmeldelinks verwalten', icon: 'link', action: openMagicLinksDialog },
    { label: 'Einstellungen', icon: 'settings', action: openSettings },
    { label: 'Tastenkürzel', icon: 'keyboard', action: openShortcutHelp },
    { label: 'Passwort ändern', icon: 'edit', action: openPasswordDialog },
    u.is_admin && { label: 'Adminbereich', icon: 'settings', action: () => { location.href = '/notes/admin'; } },
    { label: 'Abmelden', icon: 'arrow_back', action: () => app.logout() },
  ];
  openPopover(anchor, h('div', { class: 'account-pop' },
    h('div', { class: 'account-head' }, h('div', { class: 'avatar-wrap' }, big, cam), h('div', { class: 'account-name', text: u.username })),
    usage, menu(items)), { align: 'end' });
  api('GET', '/api/me/usage').then(data => {
    const used = formatBytes(data.used_bytes);
    const limit = data.limit_bytes == null ? null : formatBytes(data.limit_bytes);
    usage.replaceChildren(h('div', { class: 'usage-label' }, h('strong', { text: used }),
      h('span', { text: limit ? `von ${limit}` : 'belegt' })),
      data.limit_bytes != null ? h('div', { class: 'usage-track' }, h('span', { style: { width: `${Math.min(100, data.used_bytes / Math.max(1, data.limit_bytes) * 100)}%` } })) : null);
  }).catch(() => { usage.textContent = 'Speicherbelegung nicht verfügbar'; });
}

function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let n = bytes, i = 0;
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
  return `${n.toLocaleString('de-DE', { maximumFractionDigits: i ? 1 : 0 })} ${units[i]}`;
}
