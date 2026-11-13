const $ = s => document.querySelector(s);
const fmt = bytes => {
  if (bytes == null) return 'Unbegrenzt';
  const units = ['B','KB','MB','GB','TB']; let n = bytes, i = 0;
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
  return `${n.toLocaleString('de-DE',{maximumFractionDigits:i ? 1 : 0})} ${units[i]}`;
};
const date = ts => ts ? new Date(ts * 1000).toLocaleString('de-DE') : '–';
async function api(method, url, body) {
  const opts = {method, credentials:'same-origin', headers:{'X-Requested-With':'notizen'}};
  if (body !== undefined) { opts.headers['Content-Type']='application/json'; opts.body=JSON.stringify(body); }
  const res = await fetch(url,opts);
  if (res.status===401) { location.href='/'; throw new Error('Bitte zuerst anmelden.'); }
  if (!res.ok) { let d={}; try { d=await res.json(); } catch {} throw new Error(d.detail || `Fehler ${res.status}`); }
  return res.status===204 ? null : res.json();
}
const INDEX_LABELS={ready:'Aktuell',pending:'Wartet',building:'Wird aufgebaut',error:'Fehler'};
const BY_LABELS={admin:'Admin',user:'Nutzer',system:'System'};
const indexCells=new Map();
let indexPoll=null;
function drawIndexCell(cell,st) {
  cell.replaceChildren();
  const head=document.createElement('div'); head.className=`index-state index-${st.state}`; head.textContent=INDEX_LABELS[st.state]||st.state;
  const sub=document.createElement('div'); sub.className='admin-sub';
  if (st.state==='building') sub.textContent=`${st.done} / ${st.total} Notizen`;
  else if (st.state==='ready') sub.textContent=`${st.entries} Einträge · ${fmt(st.bytes)} · ${date(st.built_at)}`;
  else if (st.state==='error') sub.textContent=st.error||'Unbekannter Fehler';
  else sub.textContent=st.requested_by?`angestoßen von ${BY_LABELS[st.requested_by]||st.requested_by}`:'Neuaufbau startet in Kürze';
  cell.append(head,sub);
  if (st.state==='building' && st.total) {
    const track=document.createElement('div'); track.className='quota-track'; const fill=document.createElement('span');
    fill.style.width=`${Math.min(100,st.done/st.total*100)}%`; track.append(fill); cell.append(track);
  }
}
function scheduleIndexPoll(rows) {
  clearTimeout(indexPoll);
  if (!rows.some(u=>['pending','building'].includes(u.search_index?.state))) return;
  indexPoll=setTimeout(async()=>{
    try {
      const fresh=await api('GET','/api/admin/users');
      for (const u of fresh) { const cell=indexCells.get(u.id); if (cell&&u.search_index) drawIndexCell(cell,u.search_index); }
      scheduleIndexPoll(fresh);
    } catch {}
  },2500);
}
function toast(text) { const el=$('#notice'); el.textContent=text; el.classList.add('show'); setTimeout(()=>el.classList.remove('show'),2600); }
function showError(text='') { const e=$('#error'); e.textContent=text; e.classList.toggle('hidden',!text); }
async function load() {
  showError();
  try {
    const rows=await api('GET','/api/admin/users');
    const used=rows.reduce((a,u)=>a+u.used_bytes,0), limited=rows.filter(u=>u.storage_limit!=null).length;
    $('#totals').textContent=`${rows.length} Nutzer · ${fmt(used)} belegt · ${limited} mit Limit`;
    $('#users').replaceChildren();
    indexCells.clear();
    for (const u of rows) {
      const pct=u.storage_limit ? Math.min(100,u.used_bytes/u.storage_limit*100) : 0;
      const limitInput=document.createElement('input'); limitInput.type='number'; limitInput.min='0'; limitInput.step='0.1'; limitInput.placeholder='unbegrenzt';
      limitInput.setAttribute('aria-label',`Kontingent für ${u.username} in MB`);
      if (u.storage_limit!=null) limitInput.value=(u.storage_limit/1048576).toFixed(1);
      const save=document.createElement('button'); save.className='text-btn primary'; save.textContent='Speichern';
      save.addEventListener('click',async()=>{
        try {
          const value=limitInput.value.trim();
          await api('PATCH',`/api/admin/users/${u.id}`,{storage_limit:value===''?null:Math.round(Number(value)*1048576)});
          toast(`Kontingent für ${u.username} gespeichert`); await load();
        } catch(e) { showError(e.message); }
      });
      const sessions=document.createElement('button'); sessions.className='text-btn'; sessions.textContent='Abmelden'; sessions.disabled=!u.sessions;
      sessions.addEventListener('click',async()=>{
        if (!confirm(`Alle ${u.sessions} Sitzungen von ${u.username} beenden?`)) return;
        try { await api('DELETE',`/api/admin/users/${u.id}/sessions`); toast(`Sitzungen von ${u.username} beendet`); await load(); }
        catch(e) { showError(e.message); }
      });
      const tr=document.createElement('tr');
      const td=(cls,child)=>{const x=document.createElement('td'); if(cls)x.className=cls; if(child instanceof Node)x.append(child); else x.textContent=child; return x;};
      const user=document.createElement('div'); user.className='admin-user'; user.textContent=u.username;
      const sub=document.createElement('div'); sub.className='admin-sub'; sub.textContent=`${u.is_admin?'Administrator · ':''}Konto seit ${date(u.created_at)}`; user.append(sub);
      const usage=document.createElement('div'); usage.className='quota-cell';
      const line=document.createElement('div'); line.className='quota-line';
      const amount=document.createElement('strong'); amount.textContent=fmt(u.used_bytes);
      const lim=document.createElement('span'); lim.textContent=u.storage_limit==null?'unbegrenzt':`von ${fmt(u.storage_limit)}`;
      line.append(amount,lim); const track=document.createElement('div'); track.className='quota-track'; const fill=document.createElement('span'); fill.style.width=`${pct}%`; track.append(fill); usage.append(line,track);
      const edit=document.createElement('div'); edit.className='limit-edit'; edit.append(limitInput,document.createTextNode('MB'),save);
      const reindex=document.createElement('button'); reindex.className='text-btn'; reindex.textContent='Index zurücksetzen';
      const indexCell=document.createElement('div'); indexCell.className='index-cell'; indexCells.set(u.id,indexCell);
      drawIndexCell(indexCell,u.search_index||{state:'pending'});
      reindex.addEventListener('click',async()=>{
        if (!confirm(`Suchindex von ${u.username} löschen und neu aufbauen?`)) return;
        reindex.disabled=true;
        try {
          const st=await api('POST',`/api/admin/users/${u.id}/search-index/reset`);
          drawIndexCell(indexCell,st); toast(`Suchindex von ${u.username} wird neu aufgebaut`);
          scheduleIndexPoll([{search_index:st}]);
        } catch(e) { showError(e.message); }
        finally { reindex.disabled=false; }
      });
      const actions=document.createElement('div'); actions.className='admin-actions'; actions.append(reindex,sessions);
      tr.append(td('',user),td('',String(u.notes)),td('',usage),td('',edit),td('',String(u.sessions)),td('',indexCell),td('',actions));
      $('#users').append(tr);
    }
    scheduleIndexPoll(rows);
  } catch(e) { showError(e.message); }
}
function metricCard(title,value,detail,percent=null) {
  const card=document.createElement('article'); card.className='metric-card';
  const heading=document.createElement('div'); heading.className='metric-label'; heading.textContent=title;
  const main=document.createElement('div'); main.className='metric-value'; main.textContent=value;
  const meta=document.createElement('div'); meta.className='metric-detail'; meta.textContent=detail;
  card.append(heading,main,meta);
  if (percent!==null && Number.isFinite(percent)) {
    const track=document.createElement('div'); track.className='metric-track';
    const fill=document.createElement('span'); fill.style.width=`${Math.max(0,Math.min(100,percent))}%`; track.append(fill); card.append(track);
  }
  return card;
}
function fmtPct(value) { return value==null?'–':`${Number(value).toLocaleString('de-DE',{maximumFractionDigits:1})}%`; }
async function loadMetrics() {
  const error=$('#metrics-error'); error.textContent=''; error.classList.add('hidden');
  try {
    const d=await api('GET','/api/admin/metrics');
    const grid=$('#metrics-grid'); grid.replaceChildren();
    const cpu=d.cpu||{}; const cores=cpu.cores||1;
    grid.append(metricCard('CPU jetzt',fmtPct(cpu.current_percent),`${cores} logische CPU-Kerne · Momentaufnahme über 120 ms`,cpu.current_percent));
    const loads=d.load_avg;
    for (const [key,label] of [['one','Load · 1 Min.'],['five','Load · 5 Min.'],['fifteen','Load · 15 Min.']]) {
      const value=loads&&Number.isFinite(loads[key])?loads[key]:null;
      const perCore=value==null?null:value/cores*100;
      grid.append(metricCard(label,value==null?'–':value.toLocaleString('de-DE',{minimumFractionDigits:2,maximumFractionDigits:2}),
        perCore==null?'Load Average nicht verfügbar':`${fmtPct(perCore)} der CPU-Kapazität je Kern`,perCore));
    }
    const host=d.host_memory;
    grid.append(metricCard('RAM · gesamter Server',host?`${fmt(host.used_bytes)} / ${fmt(host.total_bytes)}`:'Nicht verfügbar',
      host?`${fmtPct(host.used_percent)} belegt · ${fmt(host.available_bytes)} verfügbar`:'/proc/meminfo nicht lesbar',host?.used_percent??null));
    const svc=d.service||{};
    const status=[svc.active_state,svc.sub_state].filter(Boolean).join(' · ')||'Status unbekannt';
    const extras=[svc.pid?`PID ${svc.pid}`:null,svc.tasks!=null?`${svc.tasks} Tasks`:null,
      svc.cpu_time_seconds!=null?`CPU-Zeit ${svc.cpu_time_seconds.toLocaleString('de-DE')} s`:null].filter(Boolean).join(' · ');
    const detail=[status,extras,svc.source||null,svc.error||null].filter(Boolean).join(' · ');
    grid.append(metricCard('RAM · Dienst notizen',svc.memory_bytes==null?'Nicht verfügbar':fmt(svc.memory_bytes),
      detail||svc.error||'systemd-Messwert nicht verfügbar'));
    $('#metrics-updated').textContent=`Aktualisiert ${date(d.sampled_at)}`;
  } catch(e) {
    error.textContent=`Servermesswerte konnten nicht geladen werden: ${e.message}`;
    error.classList.remove('hidden');
    $('#metrics-updated').textContent='Messung fehlgeschlagen';
  }
}

$('#refresh').addEventListener('click',()=>{load();loadMetrics();});
load();
loadMetrics();
setInterval(()=>{if(!document.hidden)loadMetrics();},15000);
