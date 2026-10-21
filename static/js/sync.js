/*
 * sync.js – Echtzeit-Synchronisation über WebSocket (Client-Seite des OT-Protokolls).
 *
 * Pro Notiz gibt es einen NoteState mit drei Zuständen (wie bei Google Docs / ot.js):
 *   synchron          → lokale Änderung wird sofort gesendet ("inflight")
 *   wartet auf Ack    → weitere lokale Änderungen sammeln sich im "buffer"
 *   wartet + Puffer   → nach dem Ack wird der Puffer gesendet
 * Eingehende fremde Befehle werden gegen inflight und buffer transformiert.
 */
import { Delta, applyChange, xform, composeChanges, sortItems } from './delta.js';

export const rid = (n = 12) => {
  const a = new Uint8Array(n);
  crypto.getRandomValues(a);
  return Array.from(a, b => 'abcdefghijklmnopqrstuvwxyz0123456789'[b % 36]).join('');
};

export function docFromView(v) {
  return {
    title: v.title || '',
    body: new Delta(v.body && v.body.length ? v.body : [{ insert: '\n' }]),
    items: sortItems((v.items || []).map(i => ({ ...i }))),
    is_list: !!v.is_list,
  };
}

export class Connection {
  constructor(handlers) {
    this.h = handlers;
    this.clientId = rid(16);
    this.ws = null;
    this.online = false;
    this.backoff = 500;
    this.stopped = false;
    this.pingTimer = null;
  }

  connect() {
    this.stopped = false;
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(`${proto}://${location.host}/ws?client=${this.clientId}`);
    this.ws = ws;
    ws.onopen = () => {
      this.online = true;
      this.backoff = 500;
      clearInterval(this.pingTimer);
      this.pingTimer = setInterval(() => this.send({ t: 'ping' }), 25000);
      this.h.onStatus(true);
      this.h.onOpen();
    };
    ws.onmessage = ev => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      this.h.onMessage(msg);
    };
    ws.onclose = ev => {
      clearInterval(this.pingTimer);
      const was = this.online;
      this.online = false;
      if (this.ws === ws) this.ws = null;
      if (was) this.h.onStatus(false);
      if (this.stopped) return;
      if (ev.code === 4401) { this.h.onAuthLost(); return; }
      setTimeout(() => { if (!this.stopped && !this.ws) this.connect(); }, this.backoff);
      this.backoff = Math.min(this.backoff * 2, 15000);
    };
  }

  stop() {
    this.stopped = true;
    if (this.ws) this.ws.close();
    this.ws = null;
  }

  send(msg) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg));
      return true;
    }
    return false;
  }
}

export class NoteState {
  /** conn = Connection oder null (lokaler Entwurf ohne Server) */
  constructor(conn, view) {
    this.conn = conn;
    this.id = view ? view.id : null;
    this.rev = view ? view.rev : 0;
    this.doc = docFromView(view || {});
    this.inflight = null;
    this.buffer = null;
    this.catching = false;
    this.listeners = new Set();
    this.beforeRemote = new Set();
  }

  on(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  emit(ev) { for (const fn of [...this.listeners]) fn(ev); }
  pending() { return !!(this.inflight || this.buffer); }

  applyLocal(change) {
    if (!change || !change.length) return;
    applyChange(this.doc, change);
    if (this.conn) {
      if (this.inflight) this.buffer = this.buffer ? composeChanges(this.buffer, change) : change;
      else { this.inflight = { cid: rid(), change }; this.sendInflight(); }
    }
    this.emit({ type: 'local', change });
  }

  sendInflight() {
    if (!this.inflight || !this.conn) return;
    this.conn.send({ t: 'op', id: this.id, rev: this.rev, cid: this.inflight.cid, change: this.inflight.change });
  }

  ack(rev, cid) {
    if (!this.inflight || this.inflight.cid !== cid) return;
    this.rev = rev;
    this.inflight = null;
    if (this.buffer) {
      this.inflight = { cid: rid(), change: this.buffer };
      this.buffer = null;
      this.sendInflight();
    }
    this.emit({ type: 'synced' });
  }

  remote(msg) {
    if (msg.rev <= this.rev) return;
    if (msg.rev > this.rev + 1) { this.catchup(); return; }
    if (this.inflight && msg.client === this.conn.clientId && msg.cid === this.inflight.cid) {
      this.ack(msg.rev, msg.cid);  // eigener Befehl, dessen Bestätigung verloren ging
      return;
    }
    for (const fn of [...this.beforeRemote]) fn();
    let s = msg.change;
    if (this.inflight) [this.inflight.change, s] = xform(this.inflight.change, s);
    if (this.buffer) [this.buffer, s] = xform(this.buffer, s);
    applyChange(this.doc, s);
    this.rev = msg.rev;
    this.emit({ type: 'remote', change: s, by: msg.by });
  }

  catchup() {
    if (this.catching || !this.conn) return;
    this.catching = this.conn.send({ t: 'catchup', id: this.id, rev: this.rev });
  }

  caught() {
    this.catching = false;
    if (this.inflight) this.sendInflight();
    else if (this.buffer) { this.inflight = { cid: rid(), change: this.buffer }; this.buffer = null; this.sendInflight(); }
  }

  /** Serverstand übernehmen (ausstehende lokale Änderungen gehen verloren). */
  reset(view) {
    const lost = this.pending();
    this.rev = view.rev;
    this.doc = docFromView(view);
    this.inflight = null;
    this.buffer = null;
    this.catching = false;
    this.emit({ type: 'reset', lost });
  }
}
