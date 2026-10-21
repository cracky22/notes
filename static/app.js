/* Notizen – Frontend (Vanilla JS, keine externen Abhängigkeiten) */
'use strict';

(() => {
  // ======================================================================
  // Icons (Pfade aus Material Icons, Apache-2.0-Lizenz)
  // ======================================================================
  const ICONS = {
    menu: 'M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z',
    search: 'M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
    close: 'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
    refresh: 'M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
    view_agenda: 'M20 13H3c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h17c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zm0-10H3c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h17c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1z',
    grid_view: 'M3 3v8h8V3H3zm6 6H5V5h4v4zm-6 4v8h8v-8H3zm6 6H5v-4h4v4zm4-16v8h8V3h-8zm6 6h-4V5h4v4zm-6 4v8h8v-8h-8zm6 6h-4v-4h4v4z',
    lightbulb: 'M9 21c0 .55.45 1 1 1h4c.55 0 1-.45 1-1v-1H9v1zm3-19C8.14 2 5 5.14 5 9c0 2.38 1.19 4.47 3 5.74V17c0 .55.45 1 1 1h6c.55 0 1-.45 1-1v-2.26c1.81-1.27 3-3.36 3-5.74 0-3.86-3.14-7-7-7zm2.85 11.1-.85.6V16h-4v-2.3l-.85-.6A4.997 4.997 0 0 1 7 9c0-2.76 2.24-5 5-5s5 2.24 5 5c0 1.63-.8 3.16-2.15 4.1z',
    label: 'M17.63 5.84C17.27 5.33 16.67 5 16 5L5 5.01C3.9 5.01 3 5.9 3 7v10c0 1.1.9 1.99 2 1.99L16 19c.67 0 1.27-.33 1.63-.84L22 12l-4.37-6.16zM16 17H5V7h11l3.55 5L16 17z',
    edit: 'M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a.996.996 0 0 0 0-1.41l-2.34-2.34a.996.996 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z',
    archive: 'M20.54 5.23l-1.39-1.68C18.88 3.21 18.47 3 18 3H6c-.47 0-.88.21-1.16.55L3.46 5.23C3.17 5.57 3 6.02 3 6.5V19c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V6.5c0-.48-.17-.93-.46-1.27zM12 17.5 6.5 12H10v-2h4v2h3.5L12 17.5zM5.12 5l.81-1h12l.94 1H5.12z',
    unarchive: 'M20.55 5.22l-1.39-1.68C18.88 3.21 18.47 3 18 3H6c-.47 0-.88.21-1.15.55L3.46 5.22C3.17 5.57 3 6.01 3 6.5V19c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V6.5c0-.49-.17-.93-.45-1.28zM12 9.5l5.5 5.5H14v2h-4v-2H6.5L12 9.5zM5.12 5l.82-1h12l.93 1H5.12z',
    delete: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
    delete_forever: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zm2.46-7.12 1.41-1.41L12 12.59l2.12-2.12 1.41 1.41L13.41 14l2.12 2.12-1.41 1.41L12 15.41l-2.12 2.12-1.41-1.41L10.59 14l-2.13-2.12zM15.5 4l-1-1h-5l-1 1H5v2h14V4z',
    restore_from_trash: 'M19 4h-3.5l-1-1h-5l-1 1H5v2h14zM6 7v12c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6zm8 7v4h-4v-4H8l4-4 4 4h-2z',
    palette: 'M12 3a9 9 0 0 0 0 18c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S5.67 9 6.5 9 8 9.67 8 10.5 7.33 12 6.5 12zm3-4C8.67 8 8 7.33 8 6.5S8.67 5 9.5 5s1.5.67 1.5 1.5S10.33 8 9.5 8zm5 0c-.83 0-1.5-.67-1.5-1.5S13.67 5 14.5 5s1.5.67 1.5 1.5S15.33 8 14.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S16.67 9 17.5 9s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z',
    more_vert: 'M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z',
    push_pin: 'M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3z',
    push_pin_outline: 'M14 4v5c0 1.12.37 2.16 1 3H9c.65-.86 1-1.9 1-3V4h4m3-2H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3V4h1c.55 0 1-.45 1-1s-.45-1-1-1z',
    check_box: 'M19 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.11 0 2-.9 2-2V5c0-1.1-.89-2-2-2zm-9 14-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z',
    check_box_outline_blank: 'M19 5v14H5V5h14m0-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z',
    check: 'M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z',
    add: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
    expand_more: 'M16.59 8.59 12 13.17 7.41 8.59 6 10l6 6 6-6z',
    format_color_reset: 'M18 14c0-4-6-10.8-6-10.8s-1.33 1.51-2.73 3.52l8.59 8.59c.09-.42.14-.86.14-1.31zm-.88 3.12L12.5 12.5 5.27 5.27 4 6.55l3.32 3.32C6.55 11.32 6 12.79 6 14c0 3.31 2.69 6 6 6 1.52 0 2.9-.57 3.96-1.5l2.63 2.63 1.27-1.27-2.74-2.74z',
    dark_mode: 'M12 3a9 9 0 1 0 9 9c0-.46-.04-.92-.1-1.36a5.389 5.389 0 0 1-4.4 2.26 5.403 5.403 0 0 1-3.14-9.8c-.44-.06-.9-.1-1.36-.1z',
    light_mode: 'M12 7c-2.76 0-5 2.24-5 5s2.24 5 5 5 5-2.24 5-5-2.24-5-5-5zM2 13h2c.55 0 1-.45 1-1s-.45-1-1-1H2c-.55 0-1 .45-1 1s.45 1 1 1zm18 0h2c.55 0 1-.45 1-1s-.45-1-1-1h-2c-.55 0-1 .45-1 1s.45 1 1 1zM11 2v2c0 .55.45 1 1 1s1-.45 1-1V2c0-.55-.45-1-1-1s-1 .45-1 1zm0 18v2c0 .55.45 1 1 1s1-.45 1-1v-2c0-.55-.45-1-1-1s-1 .45-1 1zM5.99 4.58a.996.996 0 0 0-1.41 0 .996.996 0 0 0 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0s.39-1.03 0-1.41L5.99 4.58zm12.37 12.37a.996.996 0 0 0-1.41 0 .996.996 0 0 0 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0a.996.996 0 0 0 0-1.41l-1.06-1.06zm1.06-10.96a.996.996 0 0 0 0-1.41.996.996 0 0 0-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06zM7.05 18.36a.996.996 0 0 0 0-1.41.996.996 0 0 0-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06z',
    brightness_auto: 'M10.85 12.65h2.3L12 9l-1.15 3.65zM20 8.69V4h-4.69L12 .69 8.69 4H4v4.69L.69 12 4 15.31V20h4.69L12 23.31 15.31 20H20v-4.69L23.31 12 20 8.69zM14.3 16l-.7-2h-3.2l-.7 2H7.8L11 7h2l3.2 9h-1.9z',
  };

  const COLORS = [
    ['default', 'Standard'], ['red', 'Koralle'], ['orange', 'Pfirsich'], ['yellow', 'Sand'],
    ['green', 'Minze'], ['teal', 'Salbei'], ['blue', 'Nebel'], ['darkblue', 'Sturm'],
    ['purple', 'Lavendel'], ['pink', 'Blüte'], ['brown', 'Lehm'], ['gray', 'Kreide'],
  ];
  const THEMES = {
    system: ['brightness_auto', 'Design: wie System'],
    light: ['light_mode', 'Design: hell'],
    dark: ['dark_mode', 'Design: dunkel'],
  };
  const CARD_W = 240;
  const GAP = 16;

  // ======================================================================
  // Hilfsfunktionen
  // ======================================================================
  const $ = (sel, root = document) => root.querySelector(sel);
  const SVG_NS = 'http://www.w3.org/2000/svg';

  function icon(name, size = 24) {
    const svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('width', size);
    svg.setAttribute('height', size);
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('class', 'icon');
    const path = document.createElementNS(SVG_NS, 'path');
    path.setAttribute('d', ICONS[name] || '');
    svg.append(path);
    return svg;
  }

  /** Erzeugt DOM-Elemente. Nutzertext immer über textContent (kein innerHTML → kein XSS). */
  function h(tag, attrs = {}, ...children) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'text') el.textContent = v;
      else if (k === 'dataset') Object.assign(el.dataset, v);
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else el.setAttribute(k, v === true ? '' : v);
    }
    for (const c of children.flat()) {
      if (c === null || c === undefined || c === false) continue;
      el.append(c instanceof Node ? c : document.createTextNode(String(c)));
    }
    return el;
  }

  function iconBtn(name, title, onclick, cls = '') {
    return h('button', { type: 'button', class: `icon-btn ${cls}`.trim(), title, 'aria-label': title, onclick }, icon(name));
  }

  function setIcon(el, name) { el.replaceChildren(icon(name)); }

  function uid() {
    if (window.crypto && crypto.getRandomValues) {
      return Array.from(crypto.getRandomValues(new Uint8Array(6)), b => b.toString(16).padStart(2, '0')).join('');
    }
    return Math.random().toString(16).slice(2, 14);
  }

  const store = {
    get(key, fallback) {
      try { const v = localStorage.getItem('notizen.' + key); return v === null ? fallback : v; } catch { return fallback; }
    },
    set(key, value) { try { localStorage.setItem('notizen.' + key, value); } catch { /* egal */ } },
  };

  function debounce(fn, ms) {
    let t;
    return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
  }

  function autoGrow(el) {
    if (!el || !el.isConnected) return;
    el.style.height = 'auto';
    el.style.height = el.scrollHeight + 'px';
  }

  function fmtDate(ts) {
    const d = new Date(ts * 1000);
    const now = new Date();
    const time = d.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
    if (d.toDateString() === now.toDateString()) return time;
    const yesterday = new Date(now);
    yesterday.setDate(now.getDate() - 1);
    if (d.toDateString() === yesterday.toDateString()) return `Gestern, ${time}`;
    const opts = { day: 'numeric', month: 'short' };
    if (d.getFullYear() !== now.getFullYear()) opts.year = 'numeric';
    return d.toLocaleDateString('de-DE', opts);
  }

  const byName = (a, b) => a.name.localeCompare(b.name, 'de', { sensitivity: 'base' });
  const sortedLabels = () => [...state.labels].sort(byName);
  const isMobile = () => window.matchMedia('(max-width: 720px)').matches;
  const findNote = id => state.notes.find(n => n.id === id);
  const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;

  function avatarColor(name) {
    let hash = 0;
    for (const ch of name) hash = (hash * 31 + ch.codePointAt(0)) >>> 0;
    const palette = ['#1a73e8', '#d93025', '#188038', '#e37400', '#9334e6', '#007b83', '#c5221f', '#b06000'];
    return palette[hash % palette.length];
  }

  // ======================================================================
  // Zustand
  // ======================================================================
  const state = {
    user: null,
    notes: [],
    labels: [],
    view: 'notes',          // notes | archive | trash | label
    labelId: null,
    search: '',
    layout: store.get('layout', 'grid') === 'list' ? 'list' : 'grid',
    theme: THEMES[store.get('theme', 'system')] ? store.get('theme', 'system') : 'system',
    navCollapsed: store.get('nav', '0') === '1',
  };
  let editor = null;          // { note } während eine Notiz im Modal bearbeitet wird
  let loginShown = false;

  // ======================================================================
  // API
  // ======================================================================
  class AuthError extends Error {}

  async function api(method, url, body, { auth = true } = {}) {
    const opts = { method, headers: { 'X-Requested-With': 'notizen' }, credentials: 'same-origin' };
    if (body !== undefined) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
    let res;
    try { res = await fetch(url, opts); } catch { throw new Error('Keine Verbindung zum Server'); }
    if (res.status === 401 && auth) {
      showLogin();
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

  function handleError(err) {
    if (err instanceof AuthError) return;
    console.error(err);
    toast(err.message || 'Unbekannter Fehler');
  }

  // ======================================================================
  // Design (hell/dunkel)
  // ======================================================================
  const darkMq = window.matchMedia('(prefers-color-scheme: dark)');

  function applyTheme() {
    const theme = state.theme === 'system' ? (darkMq.matches ? 'dark' : 'light') : state.theme;
    document.documentElement.dataset.theme = theme;
    $('meta[name="theme-color"]').setAttribute('content', theme === 'dark' ? '#202124' : '#ffffff');
    const btn = $('#theme-btn');
    const [ic, label] = THEMES[state.theme];
    setIcon(btn, ic);
    btn.title = label;
    btn.setAttribute('aria-label', label);
  }

  function cycleTheme() {
    const order = ['system', 'light', 'dark'];
    state.theme = order[(order.indexOf(state.theme) + 1) % order.length];
    store.set('theme', state.theme);
    applyTheme();
    toast(THEMES[state.theme][1]);
  }

  // ======================================================================
  // Modale Dialoge, Popover, Snackbar
  // ======================================================================
  const modals = [];

  function openModal(content, { onClose, className = '' } = {}) {
    const box = h('div', { class: `modal ${className}`.trim(), role: 'dialog', 'aria-modal': 'true' }, content);
    const backdrop = h('div', { class: 'modal-backdrop' }, box);
    const entry = { backdrop, box, onClose, close: () => closeModal(entry) };
    backdrop.addEventListener('mousedown', e => { if (e.target === backdrop) entry.close(); });
    $('#modal-root').append(backdrop);
    requestAnimationFrame(() => backdrop.classList.add('open'));
    modals.push(entry);
    document.body.classList.add('modal-open');
    return entry;
  }

  function closeModal(entry) {
    const i = modals.indexOf(entry);
    if (i < 0) return;
    modals.splice(i, 1);
    closePopover();
    entry.backdrop.remove();
    if (!modals.length) document.body.classList.remove('modal-open');
    if (entry.onClose) entry.onClose();
  }

  let popOwner = null;

  function openPopover(anchor, content) {
    const pop = $('#popover');
    if (popOwner === anchor) { closePopover(); return; }
    closePopover();
    pop.replaceChildren(content);
    pop.classList.remove('hidden');
    pop.style.left = '0px';
    pop.style.top = '0px';
    popOwner = anchor;
    anchor.classList.add('active');
    const r = anchor.getBoundingClientRect();
    const pw = pop.offsetWidth;
    const ph = pop.offsetHeight;
    let left = r.left;
    let top = r.bottom + 4;
    if (left + pw > window.innerWidth - 8) left = Math.max(8, window.innerWidth - pw - 8);
    if (top + ph > window.innerHeight - 8) top = Math.max(8, r.top - ph - 4);
    pop.style.left = `${left}px`;
    pop.style.top = `${top}px`;
  }

  function closePopover() {
    if (!popOwner) return;
    const pop = $('#popover');
    pop.classList.add('hidden');
    pop.replaceChildren();
    popOwner.classList.remove('active');
    popOwner = null;
  }

  function menuPopover(items) {
    return h('div', { class: 'menu', role: 'menu' }, items.map(it =>
      h('button', { type: 'button', class: 'menu-item', role: 'menuitem', onclick: () => { closePopover(); it.action(); } }, it.label)));
  }

  let snackTimer = null;

  function toast(text, actionLabel, action) {
    const sb = $('#snackbar');
    sb.replaceChildren(h('span', { class: 'snack-text', text }));
    if (action) sb.append(h('button', { type: 'button', class: 'snack-action', onclick: () => { hideSnack(); action(); } }, actionLabel));
    sb.append(iconBtn('close', 'Schließen', hideSnack));
    sb.classList.add('show');
    clearTimeout(snackTimer);
    snackTimer = setTimeout(hideSnack, 6000);
  }

  function hideSnack() {
    clearTimeout(snackTimer);
    $('#snackbar').classList.remove('show');
  }

  function confirmDialog(text, okLabel = 'OK') {
    return new Promise(resolve => {
      let result = false;
      const ok = h('button', { type: 'button', class: 'text-btn primary', onclick: () => { result = true; m.close(); } }, okLabel);
      const content = h('div', { class: 'dialog' },
        h('p', { class: 'dialog-text', text }),
        h('div', { class: 'dialog-actions' },
          h('button', { type: 'button', class: 'text-btn', onclick: () => m.close() }, 'Abbrechen'), ok));
      const m = openModal(content, { className: 'small', onClose: () => resolve(result) });
      ok.focus();
    });
  }

  // ======================================================================
  // Popover-Inhalte: Farbpalette und Label-Auswahl
  // ======================================================================
  function palettePopover(current, onPick) {
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

  function labelPopover(getIds, setIds) {
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
          onclick: () => {
            const cur = getIds();
            setIds(checked ? cur.filter(x => x !== lab.id) : [...cur, lab.id]);
            draw();
          },
        }, icon(checked ? 'check_box' : 'check_box_outline_blank', 18), h('span', { text: lab.name })));
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
            } catch (e) { handleError(e); }
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

  function labelChips(ids, onRemove, navigate = true) {
    const labs = ids.map(id => state.labels.find(l => l.id === id)).filter(Boolean).sort(byName);
    if (!labs.length) return null;
    return h('div', { class: 'chips' }, labs.map(lab => {
      const chip = h('span', {
        class: 'chip', title: lab.name,
        onclick: e => { e.stopPropagation(); if (navigate) setView('label', lab.id); },
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

  function pinButton(pinned, onPin) {
    return iconBtn(pinned ? 'push_pin' : 'push_pin_outline', pinned ? 'Notiz loslösen' : 'Notiz anheften',
      e => { e.stopPropagation(); onPin(); }, 'pin-btn' + (pinned ? ' on' : ''));
  }

  // ======================================================================
  // Aktionen auf Notizen
  // ======================================================================
  /** Ändert eine Notiz sofort lokal (optimistisch) und speichert sie auf dem Server. */
  async function updateNote(note, patch, { rerender = true } = {}) {
    Object.assign(note, patch);
    if ('trashed' in patch) note.trashed_at = patch.trashed ? Date.now() / 1000 : null;
    if (rerender) render();
    try {
      const fresh = await api('PATCH', `/api/notes/${note.id}`, patch);
      for (const k of Object.keys(patch)) note[k] = fresh[k];
      note.updated_at = fresh.updated_at;
      note.trashed_at = fresh.trashed_at;
      if (rerender) render();
    } catch (e) {
      handleError(e);
      reload();
    }
    return note;
  }

  function convertList(n) {
    if (n.is_list) return { is_list: false, text: n.items.map(i => i.text).join('\n'), items: [] };
    return {
      is_list: true, text: '',
      items: n.text.split('\n').filter(t => t.trim()).map(t => ({ id: uid(), text: t, checked: false })),
    };
  }

  function actTogglePin(note) {
    return updateNote(note, note.pinned ? { pinned: false } : { pinned: true, archived: false });
  }

  async function actArchive(note) {
    const prev = { archived: note.archived, pinned: note.pinned };
    await updateNote(note, { archived: !note.archived, pinned: false });
    toast(prev.archived ? 'Archivierung der Notiz aufgehoben' : 'Notiz archiviert', 'Rückgängig', () => updateNote(note, prev));
  }

  async function actTrash(note) {
    await updateNote(note, { trashed: true });
    toast('Notiz in den Papierkorb verschoben', 'Rückgängig', () => updateNote(note, { trashed: false }));
  }

  async function actRestore(note) {
    await updateNote(note, { trashed: false });
    toast('Notiz wiederhergestellt', 'Rückgängig', () => updateNote(note, { trashed: true }));
  }

  async function actDeleteForever(note) {
    if (!await confirmDialog('Notiz endgültig löschen?', 'Löschen')) return;
    try {
      await api('DELETE', `/api/notes/${note.id}`);
      state.notes = state.notes.filter(n => n !== note);
      render();
    } catch (e) { handleError(e); }
  }

  async function actCopy(note) {
    try {
      const copy = await api('POST', `/api/notes/${note.id}/copy`);
      state.notes.push(copy);
      render();
      toast('Kopie erstellt');
    } catch (e) { handleError(e); }
  }

  async function actEmptyTrash() {
    if (!await confirmDialog('Papierkorb leeren? Alle Notizen im Papierkorb werden endgültig gelöscht.', 'Papierkorb leeren')) return;
    try {
      await api('DELETE', '/api/trash');
      state.notes = state.notes.filter(n => !n.trashed);
      render();
    } catch (e) { handleError(e); }
  }

  function toggleItem(note, itemId) {
    const items = note.items.map(i => (i.id === itemId ? { ...i, checked: !i.checked } : i));
    updateNote(note, { items });
  }

  async function createLabel(name) {
    const lab = await api('POST', '/api/labels', { name });
    state.labels.push(lab);
    renderSidebar();
    return lab;
  }

  async function reorder(list, moved, target) {
    const ids = list.map(n => n.id);
    const positions = list.map(n => n.position);
    ids.splice(ids.indexOf(moved.id), 1);
    ids.splice(list.indexOf(target), 0, moved.id);
    ids.forEach((id, i) => { findNote(id).position = positions[i]; });
    render();
    try {
      await api('POST', '/api/notes/reorder', { ids });
    } catch (e) {
      handleError(e);
      reload();
    }
  }

  // ======================================================================
  // Editor (wird für das Eingabefeld oben und für das Bearbeiten-Modal genutzt)
  // ======================================================================
  function createEditor(draft, { mode, readOnly = false, onChange = () => {}, actions }) {
    const root = h('div', { class: `editor is-${mode}` });
    const titleEl = h('textarea', { class: 'ed-title', placeholder: 'Titel', rows: '1', maxlength: '1000', 'aria-label': 'Titel' });
    titleEl.value = draft.title;
    titleEl.readOnly = readOnly;
    const pinSlot = h('div', { class: 'ed-pin' });
    const body = h('div', { class: 'ed-body' });
    const chipsSlot = h('div', { class: 'ed-chips' });
    const meta = h('div', { class: 'ed-meta' });
    const toolbar = h('div', { class: 'ed-toolbar' });
    root.append(h('div', { class: 'editor-scroll' }, h('div', { class: 'ed-head' }, titleEl, pinSlot), body, chipsSlot, meta), toolbar);

    let textEl = null;
    let doneOpen = true;
    const itemEls = new Map();

    titleEl.addEventListener('input', () => { draft.title = titleEl.value.replace(/\n/g, ' '); autoGrow(titleEl); onChange(); });
    titleEl.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); focusBody(); } });

    function focusBody() {
      if (textEl) {
        textEl.focus();
        textEl.setSelectionRange(textEl.value.length, textEl.value.length);
      } else {
        const add = body.querySelector('.ed-add-input');
        if (add) add.focus();
      }
    }

    function removeItem(it) {
      draft.items.splice(draft.items.indexOf(it), 1);
      onChange();
    }

    function itemRow(it) {
      const ta = h('textarea', { class: 'ed-item-text', rows: '1', maxlength: '5000', 'aria-label': 'Listenelement' });
      ta.value = it.text;
      ta.readOnly = readOnly;
      itemEls.set(it.id, ta);
      const row = h('div', { class: 'ed-item' + (it.checked ? ' checked' : '') },
        h('button', {
          type: 'button', class: 'ed-check', disabled: readOnly,
          title: it.checked ? 'Als nicht erledigt markieren' : 'Als erledigt markieren',
          'aria-label': it.checked ? 'Als nicht erledigt markieren' : 'Als erledigt markieren',
          onclick: () => { it.checked = !it.checked; onChange(); renderBody(); },
        }, icon(it.checked ? 'check_box' : 'check_box_outline_blank', 20)),
        ta);
      if (readOnly) return row;

      row.append(iconBtn('close', 'Element löschen', () => { removeItem(it); renderBody(); }, 'ed-item-del'));
      ta.addEventListener('input', () => {
        if (ta.value.includes('\n')) { // eingefügter mehrzeiliger Text → mehrere Elemente
          const lines = ta.value.split(/\r?\n/);
          it.text = lines.shift();
          let idx = draft.items.indexOf(it);
          let last = it;
          for (const line of lines) {
            last = { id: uid(), text: line, checked: it.checked };
            draft.items.splice(++idx, 0, last);
          }
          onChange();
          renderBody({ id: last.id });
          return;
        }
        it.text = ta.value;
        autoGrow(ta);
        onChange();
      });
      ta.addEventListener('keydown', e => {
        if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
          e.preventDefault();
          const before = ta.value.slice(0, ta.selectionStart);
          const after = ta.value.slice(ta.selectionEnd);
          it.text = before;
          const next = { id: uid(), text: after, checked: it.checked };
          draft.items.splice(draft.items.indexOf(it) + 1, 0, next);
          onChange();
          renderBody({ id: next.id, pos: 0 });
        } else if (e.key === 'Backspace' && ta.selectionStart === 0 && ta.selectionEnd === 0) {
          const group = draft.items.filter(i => i.checked === it.checked);
          const prev = group[group.indexOf(it) - 1];
          if (prev) {
            e.preventDefault();
            const pos = prev.text.length;
            prev.text += it.text;
            removeItem(it);
            renderBody({ id: prev.id, pos });
          } else if (!it.text) {
            e.preventDefault();
            removeItem(it);
            renderBody(group[1] ? { id: group[1].id, pos: 0 } : { add: true });
          }
        } else if ((e.key === 'ArrowUp' || e.key === 'ArrowDown') && !e.shiftKey) {
          const rows = [...body.querySelectorAll('.ed-item-text, .ed-add-input')];
          const i = rows.indexOf(ta);
          const atStart = ta.selectionStart === 0;
          const atEnd = ta.selectionEnd === ta.value.length;
          const target = e.key === 'ArrowUp' ? (atStart ? rows[i - 1] : null) : (atEnd ? rows[i + 1] : null);
          if (target) { e.preventDefault(); target.focus(); }
        }
      });
      return row;
    }

    function renderBody(focus) {
      body.replaceChildren();
      itemEls.clear();
      textEl = null;

      if (!draft.is_list) {
        textEl = h('textarea', { class: 'ed-text', placeholder: 'Notiz schreiben…', 'aria-label': 'Notiz', maxlength: '100000' });
        textEl.value = draft.text;
        textEl.readOnly = readOnly;
        textEl.addEventListener('input', () => { draft.text = textEl.value; autoGrow(textEl); onChange(); });
        body.append(textEl);
        requestAnimationFrame(() => autoGrow(textEl));
        return;
      }

      const open = draft.items.filter(i => !i.checked);
      const done = draft.items.filter(i => i.checked);
      const list = h('div', { class: 'ed-list' }, open.map(itemRow));
      if (!readOnly) {
        const addInput = h('input', { type: 'text', class: 'ed-add-input', placeholder: 'Listenelement', 'aria-label': 'Neues Listenelement' });
        addInput.addEventListener('input', () => {
          if (!addInput.value) return;
          const it = { id: uid(), text: addInput.value, checked: false };
          const lastOpen = [...draft.items].reverse().find(i => !i.checked);
          draft.items.splice(lastOpen ? draft.items.indexOf(lastOpen) + 1 : 0, 0, it);
          onChange();
          renderBody({ id: it.id });
        });
        addInput.addEventListener('keydown', e => {
          if (e.key === 'ArrowUp') {
            const items = body.querySelectorAll('.ed-list:not(.done) .ed-item-text');
            if (items.length) { e.preventDefault(); items[items.length - 1].focus(); }
          }
        });
        list.append(h('div', { class: 'ed-add' }, icon('add', 20), addInput));
      }
      body.append(list);

      if (done.length) {
        body.append(
          h('div', { class: 'ed-divider' }),
          h('button', {
            type: 'button', class: 'ed-done-toggle' + (doneOpen ? ' open' : ''), 'aria-expanded': String(doneOpen),
            onclick: () => { doneOpen = !doneOpen; renderBody(); },
          }, icon('expand_more', 20), h('span', { text: plural(done.length, 'erledigtes Element', 'erledigte Elemente') })));
        if (doneOpen) body.append(h('div', { class: 'ed-list done' }, done.map(itemRow)));
      }

      if (focus) {
        const ta = focus.id && itemEls.get(focus.id);
        if (ta) {
          ta.focus();
          const pos = focus.pos ?? ta.value.length;
          ta.setSelectionRange(pos, pos);
        } else if (focus.add) {
          const add = body.querySelector('.ed-add-input');
          if (add) add.focus();
        }
      }
      requestAnimationFrame(() => itemEls.forEach(autoGrow));
    }

    function toggleList() {
      Object.assign(draft, convertList(draft));
      onChange();
      renderBody(draft.is_list ? { add: true } : null);
      if (!draft.is_list) focusBody();
      renderToolbar();
    }

    function renderToolbar() {
      const tools = h('div', { class: 'ed-tools' });
      if (readOnly) {
        tools.append(
          iconBtn('delete_forever', 'Endgültig löschen', () => actions.deleteForever()),
          iconBtn('restore_from_trash', 'Wiederherstellen', () => actions.restore()));
      } else {
        const more = [];
        if (actions.trash) more.push({ label: 'Notiz löschen', action: actions.trash });
        if (actions.copy) more.push({ label: 'Kopie erstellen', action: actions.copy });
        more.push({ label: draft.is_list ? 'Kästchen ausblenden' : 'Kästchen anzeigen', action: toggleList });
        tools.append(
          iconBtn('palette', 'Hintergrundoptionen', e => openPopover(e.currentTarget, palettePopover(draft.color, c => actions.setColor(c)))),
          iconBtn('label', 'Label hinzufügen', e => openPopover(e.currentTarget, labelPopover(() => draft.labels, ids => actions.setLabels(ids)))),
          iconBtn(draft.archived ? 'unarchive' : 'archive', draft.archived ? 'Archivierung aufheben' : 'Archivieren', () => actions.archive()),
          iconBtn('more_vert', 'Mehr', e => openPopover(e.currentTarget, menuPopover(more))));
      }
      toolbar.replaceChildren(tools, h('button', { type: 'button', class: 'text-btn ed-close', onclick: () => actions.close() }, 'Schließen'));
    }

    function refreshMeta() {
      if (readOnly) meta.textContent = 'Notizen im Papierkorb können nicht bearbeitet werden';
      else if (mode === 'modal' && draft.updated_at) {
        meta.textContent = `Bearbeitet: ${fmtDate(draft.updated_at)}`;
        meta.title = `Erstellt: ${new Date(draft.created_at * 1000).toLocaleString('de-DE')}`;
      } else meta.textContent = '';
    }

    function refresh(withToolbar = true) {
      root.dataset.color = draft.color;
      pinSlot.replaceChildren();
      if (!readOnly) pinSlot.append(pinButton(draft.pinned, () => actions.pin()));
      chipsSlot.replaceChildren();
      const chips = labelChips(draft.labels, readOnly ? null : id => actions.setLabels(draft.labels.filter(x => x !== id)), false);
      if (chips) chipsSlot.append(chips);
      refreshMeta();
      if (withToolbar) renderToolbar();
    }

    function grow() {
      autoGrow(titleEl);
      if (textEl) autoGrow(textEl);
      itemEls.forEach(autoGrow);
    }

    refresh();
    renderBody();
    return { root, refresh, refreshMeta, grow, focusBody, focusTitle: () => titleEl.focus() };
  }

  // ----------------------------------------------------------------------
  // Bearbeiten-Modal
  // ----------------------------------------------------------------------
  function openEditor(note) {
    closePopover();
    if (composer.expanded) closeComposer();
    const draft = JSON.parse(JSON.stringify(note));
    const readOnly = !!note.trashed;
    let timer = null;
    let dirty = false;
    let finished = Promise.resolve();

    const save = async () => {
      clearTimeout(timer);
      if (!dirty) return;
      dirty = false;
      const patch = { title: draft.title, text: draft.text, items: draft.items, is_list: draft.is_list };
      try {
        const fresh = await api('PATCH', `/api/notes/${note.id}`, patch);
        for (const k of ['title', 'text', 'items', 'is_list', 'updated_at']) note[k] = fresh[k];
        draft.updated_at = fresh.updated_at;
        ed.refreshMeta();
      } catch (e) {
        dirty = true;
        handleError(e);
      }
    };

    const local = (patch, withToolbar = true) => {
      Object.assign(draft, patch);
      ed.refresh(withToolbar);
      updateNote(note, patch, { rerender: false });
    };
    const after = async fn => { entry.close(); await finished; fn(); };

    const ed = createEditor(draft, {
      mode: 'modal',
      readOnly,
      onChange: () => { dirty = true; clearTimeout(timer); timer = setTimeout(save, 600); },
      actions: {
        pin: () => local(draft.pinned ? { pinned: false } : { pinned: true, archived: false }),
        setColor: c => local({ color: c }),
        setLabels: ids => local({ labels: ids }, false),
        archive: () => after(() => actArchive(note)),
        trash: () => after(() => actTrash(note)),
        copy: () => after(() => actCopy(note)),
        restore: () => after(() => actRestore(note)),
        deleteForever: () => after(() => actDeleteForever(note)),
        close: () => entry.close(),
      },
    });

    editor = { note };
    const entry = openModal(ed.root, {
      className: 'editor-modal',
      onClose: () => {
        finished = (async () => {
          await save();
          if (editor && editor.note === note) editor = null;
          render();
        })();
      },
    });
    ed.grow();
    const card = document.querySelector(`.note[data-id="${note.id}"]`);
    if (card) card.classList.add('editing');
  }

  // ----------------------------------------------------------------------
  // Eingabefeld "Notiz schreiben…"
  // ----------------------------------------------------------------------
  const composer = { expanded: false, draft: null, ed: null };

  function renderComposer() {
    const wrap = $('#composer-wrap');
    if (composer.expanded) {
      wrap.replaceChildren(h('div', { class: 'composer expanded' }, composer.ed.root));
      composer.ed.grow();
      return;
    }
    wrap.replaceChildren(h('div', { class: 'composer collapsed' },
      h('button', { type: 'button', class: 'composer-placeholder', onclick: () => expandComposer(false) }, 'Notiz schreiben…'),
      iconBtn('check_box', 'Neue Liste', () => expandComposer(true))));
  }

  function expandComposer(isList) {
    if (composer.expanded) return;
    const d = {
      title: '', text: '', items: [], is_list: isList, color: 'default', pinned: false, archived: false,
      labels: state.view === 'label' && state.labelId ? [state.labelId] : [],
    };
    composer.draft = d;
    composer.expanded = true;
    composer.ed = createEditor(d, {
      mode: 'composer',
      actions: {
        pin: () => { d.pinned = !d.pinned; composer.ed.refresh(); },
        setColor: c => { d.color = c; composer.ed.refresh(); },
        setLabels: ids => { d.labels = ids; composer.ed.refresh(false); },
        archive: () => { d.archived = true; d.pinned = false; closeComposer(); },
        close: () => closeComposer(),
      },
    });
    renderComposer();
    composer.ed.focusBody();
  }

  async function closeComposer() {
    if (!composer.expanded) return;
    const d = composer.draft;
    composer.expanded = false;
    composer.draft = null;
    composer.ed = null;
    closePopover();
    renderComposer();
    d.items = d.items.filter(i => i.text.trim());
    if (!d.title.trim() && !d.text.trim() && !d.items.length) return;
    try {
      const note = await api('POST', '/api/notes', {
        title: d.title, text: d.text, items: d.items, is_list: d.is_list, color: d.color,
        pinned: d.pinned, archived: d.archived, labels: d.labels,
      });
      state.notes.push(note);
      render();
      if (d.archived) toast('Notiz archiviert', 'Rückgängig', () => updateNote(note, { archived: false }));
    } catch (e) { handleError(e); }
  }

  // ======================================================================
  // Darstellung
  // ======================================================================
  function viewTitle() {
    if (state.view === 'archive') return 'Archiv';
    if (state.view === 'trash') return 'Papierkorb';
    if (state.view === 'label') {
      const lab = state.labels.find(l => l.id === state.labelId);
      return lab ? lab.name : 'Notizen';
    }
    return 'Notizen';
  }

  function render() {
    renderSidebar();
    renderHeader();
    renderMain();
  }

  function renderHeader() {
    const title = viewTitle();
    $('#brand-title').textContent = title;
    $('#brand-logo').classList.toggle('hidden', state.view !== 'notes');
    document.title = state.view === 'notes' ? 'Notizen' : `${title} – Notizen`;
    const lb = $('#layout-btn');
    setIcon(lb, state.layout === 'grid' ? 'view_agenda' : 'grid_view');
    const label = state.layout === 'grid' ? 'Listenansicht' : 'Rasteransicht';
    lb.title = label;
    lb.setAttribute('aria-label', label);
  }

  function renderSidebar() {
    const nav = $('#sidebar');
    const item = (iconName, label, onclick, active) => h('button', {
      type: 'button', class: 'nav-item' + (active ? ' active' : ''), title: label, onclick,
      'aria-current': active ? 'page' : null,
    }, icon(iconName), h('span', { class: 'nav-label', text: label }));
    nav.replaceChildren(
      item('lightbulb', 'Notizen', () => setView('notes'), state.view === 'notes'),
      ...sortedLabels().map(lab => item('label', lab.name, () => setView('label', lab.id), state.view === 'label' && state.labelId === lab.id)),
      item('edit', 'Labels bearbeiten', openLabelsDialog, false),
      item('archive', 'Archiv', () => setView('archive'), state.view === 'archive'),
      item('delete', 'Papierkorb', () => setView('trash'), state.view === 'trash'));
  }

  function noteHaystack(n) {
    const labels = n.labels.map(id => (state.labels.find(l => l.id === id) || {}).name || '');
    return [n.title, n.text, ...n.items.map(i => i.text), ...labels].join('\n').toLocaleLowerCase();
  }

  function visibleNotes() {
    const q = state.search.trim().toLocaleLowerCase();
    let list = state.notes.filter(n => {
      if (state.view === 'trash') return n.trashed;
      if (n.trashed) return false;
      if (state.view === 'archive') return n.archived;
      if (state.view === 'label') return n.labels.includes(state.labelId);
      return q ? true : !n.archived; // bei der Suche auch archivierte Notizen finden
    });
    if (q) {
      const words = q.split(/\s+/);
      list = list.filter(n => { const hay = noteHaystack(n); return words.every(w => hay.includes(w)); });
    }
    return list.sort((a, b) => b.position - a.position || b.id - a.id);
  }

  function renderMain() {
    const area = $('#notes-area');
    const notice = $('#notice');
    area.replaceChildren();
    notice.replaceChildren();
    const searching = state.search.trim() !== '';
    $('#composer-wrap').classList.toggle('hidden', searching || !(state.view === 'notes' || state.view === 'label'));

    if (state.view === 'trash' && state.notes.some(n => n.trashed)) {
      notice.append(h('div', { class: 'trash-notice' },
        h('span', { text: 'Notizen im Papierkorb werden nach 7 Tagen gelöscht.' }),
        h('button', { type: 'button', class: 'text-btn primary', onclick: actEmptyTrash }, 'Papierkorb leeren')));
    }

    const notes = visibleNotes();
    if (!notes.length) {
      const [ic, text] = searching ? ['search', 'Keine passenden Ergebnisse'] : {
        notes: ['lightbulb', 'Hier werden deine Notizen angezeigt'],
        archive: ['archive', 'Hier werden archivierte Notizen angezeigt'],
        trash: ['delete', 'Keine Notizen im Papierkorb'],
        label: ['label', 'Keine Notizen mit diesem Label'],
      }[state.view];
      area.append(h('div', { class: 'empty' }, icon(ic, 120), h('p', { text })));
      return;
    }

    const sections = [];
    if (state.view === 'trash' || state.view === 'archive') {
      sections.push([null, notes]);
    } else {
      const pinned = notes.filter(n => n.pinned && !n.archived);
      const others = notes.filter(n => !(n.pinned && !n.archived));
      if (pinned.length) sections.push(['Angeheftet', pinned]);
      if (others.length) sections.push([pinned.length ? 'Andere' : null, others]);
    }
    for (const [title, list] of sections) {
      const section = h('section', { class: 'notes-section' });
      if (title) section.append(h('h2', { class: 'section-title', text: title }));
      const grid = h('div', { class: 'grid' });
      section.append(grid);
      area.append(section);
      layoutGrid(section, grid, list.map(n => buildCard(n, list)));
    }
  }

  /** Masonry-Layout: jede Karte kommt in die aktuell kürzeste Spalte (Reihenfolge links → rechts). */
  function layoutGrid(section, grid, cards) {
    const width = $('#notes-area').clientWidth;
    let cols;
    if (state.layout === 'list') {
      cols = 1;
      section.classList.add('list');
    } else if (width < 2 * CARD_W + 3 * GAP) {
      cols = width < 300 ? 1 : 2;
      section.classList.add('fluid');
    } else {
      cols = Math.max(1, Math.floor((width + GAP) / (CARD_W + GAP)));
    }
    const columns = Array.from({ length: cols }, () => h('div', { class: 'col' }));
    grid.append(...columns);
    const heights = new Array(cols).fill(0);
    for (const card of cards) {
      const i = heights.indexOf(Math.min(...heights));
      columns[i].append(card);
      heights[i] = columns[i].offsetHeight;
    }
  }

  function buildCard(note, sectionList) {
    const card = h('div', {
      class: 'note', dataset: { id: note.id, color: note.color }, tabindex: '0', role: 'button',
      'aria-label': note.title || 'Notiz öffnen',
    });
    if (editor && editor.note.id === note.id) card.classList.add('editing');
    if (!note.trashed) card.append(pinButton(note.pinned, () => actTogglePin(note)));

    const hasTitle = note.title.trim() !== '';
    if (hasTitle) card.append(h('div', { class: 'note-title', text: note.title }));
    if (note.is_list && note.items.length) {
      card.append(checklistPreview(note));
    } else if (!note.is_list && note.text.trim()) {
      const big = !hasTitle && note.text.length <= 60 && note.text.split('\n').length <= 2;
      card.append(h('div', { class: 'note-text' + (big ? ' big' : ''), text: note.text.length > 3000 ? note.text.slice(0, 3000) + '…' : note.text }));
    } else if (!hasTitle) {
      card.append(h('div', { class: 'note-text empty-note', text: 'Leere Notiz' }));
    }
    const chips = labelChips(note.labels, note.trashed ? null : id => updateNote(note, { labels: note.labels.filter(x => x !== id) }));
    if (chips) card.append(chips);
    card.append(cardToolbar(note));

    card.addEventListener('click', e => { if (!e.target.closest('button, .chip')) openEditor(note); });
    card.addEventListener('keydown', e => { if (e.key === 'Enter' && e.target === card) openEditor(note); });
    if (!note.trashed && state.view !== 'archive' && !state.search.trim()) enableDrag(card, note, sectionList);
    return card;
  }

  function checklistPreview(note) {
    const open = note.items.filter(i => !i.checked);
    const done = note.items.filter(i => i.checked);
    const wrap = h('div', { class: 'note-list' });
    for (const it of open.slice(0, 12)) {
      wrap.append(h('div', { class: 'li' },
        h('button', {
          type: 'button', class: 'li-check', disabled: note.trashed, title: 'Abhaken', 'aria-label': `„${it.text}“ abhaken`,
          onclick: e => { e.stopPropagation(); toggleItem(note, it.id); },
        }, icon('check_box_outline_blank', 18)),
        h('span', { class: 'li-text', text: it.text })));
    }
    if (open.length > 12) wrap.append(h('div', { class: 'li-more', text: '…' }));
    if (done.length) wrap.append(h('div', { class: 'li-done', text: '+ ' + plural(done.length, 'erledigtes Element', 'erledigte Elemente') }));
    return wrap;
  }

  function cardToolbar(note) {
    const bar = h('div', { class: 'note-toolbar' });
    const stop = fn => e => { e.stopPropagation(); fn(e); };
    if (note.trashed) {
      bar.append(
        iconBtn('delete_forever', 'Endgültig löschen', stop(() => actDeleteForever(note))),
        iconBtn('restore_from_trash', 'Wiederherstellen', stop(() => actRestore(note))));
      return bar;
    }
    bar.append(
      iconBtn('palette', 'Hintergrundoptionen', stop(e => openPopover(e.currentTarget, palettePopover(note.color, c => updateNote(note, { color: c }))))),
      iconBtn('label', 'Label hinzufügen', stop(e => openPopover(e.currentTarget, labelPopover(() => note.labels, ids => updateNote(note, { labels: ids }))))),
      iconBtn(note.archived ? 'unarchive' : 'archive', note.archived ? 'Archivierung aufheben' : 'Archivieren', stop(() => actArchive(note))),
      iconBtn('more_vert', 'Mehr', stop(e => openPopover(e.currentTarget, menuPopover([
        { label: 'Notiz löschen', action: () => actTrash(note) },
        { label: 'Kopie erstellen', action: () => actCopy(note) },
        { label: note.is_list ? 'Kästchen ausblenden' : 'Kästchen anzeigen', action: () => updateNote(note, convertList(note)) },
      ])))));
    return bar;
  }

  // Drag & Drop zum Umsortieren (Desktop)
  let drag = null;
  function enableDrag(card, note, list) {
    card.draggable = true;
    card.addEventListener('dragstart', e => {
      drag = { note, list };
      card.classList.add('dragging');
      e.dataTransfer.effectAllowed = 'move';
      try { e.dataTransfer.setData('text/plain', String(note.id)); } catch { /* egal */ }
    });
    card.addEventListener('dragend', () => {
      card.classList.remove('dragging');
      drag = null;
      document.querySelectorAll('.drop-target').forEach(el => el.classList.remove('drop-target'));
    });
    const accepts = () => drag && drag.list === list && drag.note !== note;
    card.addEventListener('dragover', e => { if (accepts()) { e.preventDefault(); card.classList.add('drop-target'); } });
    card.addEventListener('dragleave', () => card.classList.remove('drop-target'));
    card.addEventListener('drop', e => {
      if (!accepts()) return;
      e.preventDefault();
      const moved = drag.note;
      drag = null;
      reorder(list, moved, note);
    });
  }

  // ======================================================================
  // Dialoge: Labels bearbeiten, Passwort ändern, Konto
  // ======================================================================
  function openLabelsDialog() {
    document.body.classList.remove('nav-open');
    const err = h('div', { class: 'labels-error', role: 'alert' });
    const list = h('div', { class: 'labels-list' });
    const newInput = h('input', { type: 'text', placeholder: 'Neues Label erstellen', maxlength: '100', 'aria-label': 'Neues Label erstellen' });
    const showErr = e => { if (!(e instanceof AuthError)) err.textContent = e.message; };

    const create = async () => {
      const name = newInput.value.trim();
      if (!name) return;
      err.textContent = '';
      try {
        await createLabel(name);
        newInput.value = '';
        draw();
        render();
      } catch (e) { showErr(e); }
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
            const upd = await api('PATCH', `/api/labels/${lab.id}`, { name });
            lab.name = upd.name;
            err.textContent = '';
            render();
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
            await api('DELETE', `/api/labels/${lab.id}`);
            state.labels = state.labels.filter(l => l !== lab);
            state.notes.forEach(n => { n.labels = n.labels.filter(x => x !== lab.id); });
            if (state.view === 'label' && state.labelId === lab.id) setView('notes');
            draw();
            render();
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
    const m = openModal(content, { className: 'small' });
    newInput.focus();
  }

  function openPasswordDialog() {
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
        await api('POST', '/api/password', { old_password: oldPw.value, new_password: newPw.value });
        m.close();
        toast('Passwort geändert. Andere Geräte wurden abgemeldet.');
      } catch (e2) { if (!(e2 instanceof AuthError)) err.textContent = e2.message; }
    });
    const m = openModal(form, { className: 'small' });
    oldPw.focus();
  }

  function openAccount(anchor) {
    const name = state.user.username;
    const av = h('div', { class: 'avatar big', text: (name[0] || '?').toUpperCase() });
    av.style.background = avatarColor(name);
    openPopover(anchor, h('div', { class: 'account-pop' },
      h('div', { class: 'account-head' }, av, h('div', { class: 'account-name', text: name })),
      menuPopover([
        { label: THEMES[state.theme][1] + ' (wechseln)', action: cycleTheme },
        { label: 'Passwort ändern', action: openPasswordDialog },
        { label: 'Abmelden', action: logout },
      ])));
  }

  // ======================================================================
  // Navigation (über die URL, damit Zurück-Taste & Lesezeichen funktionieren)
  // ======================================================================
  function syncRoute() {
    const hash = location.hash.slice(1);
    const m = hash.match(/^label\/(\d+)$/);
    if (hash === 'archive' || hash === 'trash') { state.view = hash; state.labelId = null; }
    else if (m && state.labels.some(l => l.id === Number(m[1]))) { state.view = 'label'; state.labelId = Number(m[1]); }
    else { state.view = 'notes'; state.labelId = null; }
  }

  function setView(view, labelId = null) {
    const hash = view === 'notes' ? '' : view === 'label' ? `label/${labelId}` : view;
    if (location.hash.slice(1) === hash) applyRoute();
    else location.hash = hash;
  }

  function applyRoute() {
    if (!state.user) return;
    closeComposer();
    syncRoute();
    document.body.classList.remove('nav-open');
    window.scrollTo(0, 0);
    render();
  }

  // ======================================================================
  // Anmeldung & Laden
  // ======================================================================
  async function reload({ spin = false } = {}) {
    const btn = $('#refresh-btn');
    if (spin) btn.classList.add('spin');
    try {
      const [notes, labels] = await Promise.all([api('GET', '/api/notes'), api('GET', '/api/labels')]);
      state.notes = editor ? notes.map(n => (n.id === editor.note.id ? editor.note : n)) : notes;
      state.labels = labels;
      syncRoute();
      render();
    } catch (e) {
      handleError(e);
    } finally {
      if (spin) setTimeout(() => btn.classList.remove('spin'), 300);
    }
  }

  function renderAvatar() {
    const btn = $('#account-btn');
    const name = state.user.username;
    btn.textContent = (name[0] || '?').toUpperCase();
    btn.style.background = avatarColor(name);
    btn.title = `Konto: ${name}`;
  }

  async function showApp() {
    loginShown = false;
    $('#login').classList.add('hidden');
    $('#app').classList.remove('hidden');
    renderAvatar();
    renderComposer();
    await reload();
  }

  function showLogin() {
    if (loginShown) return;
    loginShown = true;
    state.user = null;
    state.notes = [];
    state.labels = [];
    editor = null;
    composer.expanded = false;
    composer.draft = null;
    closePopover();
    while (modals.length) modals.pop().backdrop.remove();
    document.body.classList.remove('modal-open', 'nav-open');
    $('#app').classList.add('hidden');
    $('#login').classList.remove('hidden');
    $('#login-user').focus();
  }

  async function logout() {
    try { await api('POST', '/api/logout', undefined, { auth: false }); } catch { /* egal */ }
    showLogin();
  }

  async function boot() {
    try {
      state.user = await api('GET', '/api/me', undefined, { auth: false });
      await showApp();
    } catch (e) {
      if (e.status !== 401) console.error(e);
      showLogin();
      if (e.status !== 401) $('#login-error').textContent = e.message;
    }
  }

  // ======================================================================
  // Initialisierung & globale Ereignisse
  // ======================================================================
  function init() {
    setIcon($('#menu-btn'), 'menu');
    setIcon($('#search-icon'), 'search');
    setIcon($('#search-clear'), 'close');
    setIcon($('#refresh-btn'), 'refresh');
    applyTheme();
    document.body.classList.toggle('nav-collapsed', state.navCollapsed);
    darkMq.addEventListener('change', applyTheme);

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
        state.user = await api('POST', '/api/login', { username, password }, { auth: false });
        $('#login-pass').value = '';
        await showApp();
      } catch (e2) {
        err.textContent = e2.message;
      } finally {
        btn.disabled = false;
      }
    });

    $('#menu-btn').addEventListener('click', () => {
      if (isMobile()) {
        document.body.classList.toggle('nav-open');
      } else {
        state.navCollapsed = !state.navCollapsed;
        store.set('nav', state.navCollapsed ? '1' : '0');
        document.body.classList.toggle('nav-collapsed', state.navCollapsed);
        setTimeout(renderMain, 220); // Spaltenanzahl an neue Breite anpassen
      }
    });
    $('#sidebar-scrim').addEventListener('click', () => document.body.classList.remove('nav-open'));

    const searchInput = $('#search-input');
    const clearSearch = () => {
      searchInput.value = '';
      state.search = '';
      $('#search-clear').classList.add('hidden');
      renderMain();
    };
    searchInput.addEventListener('input', () => {
      state.search = searchInput.value;
      $('#search-clear').classList.toggle('hidden', !state.search);
      if (composer.expanded && state.search.trim()) closeComposer();
      renderMain();
    });
    $('#search-icon').addEventListener('click', () => searchInput.focus());
    $('#search-clear').addEventListener('click', () => { clearSearch(); searchInput.focus(); });

    $('#refresh-btn').addEventListener('click', () => reload({ spin: true }));
    $('#layout-btn').addEventListener('click', () => {
      state.layout = state.layout === 'grid' ? 'list' : 'grid';
      store.set('layout', state.layout);
      renderHeader();
      renderMain();
    });
    $('#theme-btn').addEventListener('click', cycleTheme);
    $('#account-btn').addEventListener('click', e => openAccount(e.currentTarget));

    // Klick außerhalb: zuerst Popover schließen, sonst Eingabefeld schließen (speichert die Notiz)
    document.addEventListener('mousedown', e => {
      const pop = $('#popover');
      if (popOwner) {
        if (!pop.contains(e.target) && !popOwner.contains(e.target)) closePopover();
        return;
      }
      if (composer.expanded && !$('#composer-wrap').contains(e.target) && !$('#modal-root').contains(e.target)
        && !$('#snackbar').contains(e.target)) {
        closeComposer();
      }
    }, true);

    document.addEventListener('keydown', e => {
      if (e.key === 'Escape') {
        if (popOwner) { closePopover(); return; }
        if (modals.length) { modals[modals.length - 1].close(); return; }
        if (composer.expanded) { closeComposer(); return; }
        if (document.activeElement === searchInput && searchInput.value) clearSearch();
        return;
      }
      const t = e.target;
      if (!state.user || modals.length || e.ctrlKey || e.metaKey || e.altKey) return;
      if (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable) return;
      const composerVisible = !$('#composer-wrap').classList.contains('hidden');
      if (e.key === '/') { e.preventDefault(); searchInput.focus(); }
      else if (e.key === 'c' && composerVisible) { e.preventDefault(); expandComposer(false); }
      else if (e.key === 'l' && composerVisible) { e.preventDefault(); expandComposer(true); }
    });

    window.addEventListener('hashchange', applyRoute);
    window.addEventListener('scroll', () => $('#topbar').classList.toggle('scrolled', window.scrollY > 0), { passive: true });
    window.addEventListener('resize', debounce(() => {
      if (state.user) renderMain();
      if (!isMobile()) document.body.classList.remove('nav-open');
    }, 120));
    // Beim Zurückkehren in den Tab Änderungen von anderen Geräten laden
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && state.user && !editor && !composer.expanded && !modals.length) reload();
    });

    boot();
  }

  init();
})();
