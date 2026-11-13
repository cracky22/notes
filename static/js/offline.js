/* offline.js - gezieltes Löschen app-eigener Browserdaten */
const PREFIX = 'notizen.';
const generationKey = userId => `${PREFIX}cache-generation.${userId}`;
const ownedName = name => /^notizen(?:[-_.:]|$)/i.test(name || '');

export async function clearLocalAppCache(userId, generation = null) {
  const keep = userId == null ? null : generationKey(userId);
  for (const storageName of ['localStorage', 'sessionStorage']) {
    let storage;
    try { storage = window[storageName]; } catch { continue; }
    if (!storage) continue;
    try {
      const keys = [];
      for (let i = 0; i < storage.length; i++) {
        const key = storage.key(i);
        if (key && key.startsWith(PREFIX) && key !== keep) keys.push(key);
      }
      for (const key of keys) storage.removeItem(key);
    } catch { /* Browser-Speicher kann blockiert sein. */ }
  }

  if ('caches' in window) {
    try {
      const names = await caches.keys();
      await Promise.all(names.filter(ownedName).map(name => caches.delete(name)));
    } catch { /* Kein CacheStorage verfügbar. */ }
  }

  if (window.indexedDB?.databases) {
    try {
      const databases = await indexedDB.databases();
      await Promise.all((databases || []).filter(db => ownedName(db.name)).map(db => new Promise(resolve => {
        const request = indexedDB.deleteDatabase(db.name);
        request.onsuccess = request.onerror = request.onblocked = () => resolve();
        setTimeout(resolve, 2000);
      })));
    } catch { /* Ältere Browser stellen keine Datenbankliste bereit. */ }
  }

  if (userId != null && generation != null) {
    try {
      const previous = Number(localStorage.getItem(keep));
      localStorage.setItem(keep, String(Number.isSafeInteger(previous) ? Math.max(previous, generation) : generation));
    } catch { /* optionaler Marker */ }
  }
}

export async function reconcileCacheGeneration(user) {
  const generation = Number(user?.cache_generation || 0);
  if (!Number.isSafeInteger(generation) || generation < 0 || user?.id == null) return;
  const key = generationKey(user.id);
  let known = null;
  try { known = localStorage.getItem(key); } catch { /* optionaler Marker */ }
  if (known !== null && Number(known) !== generation) {
    await clearLocalAppCache(user.id, generation);
  } else if (known === null) {
    try { localStorage.setItem(key, String(generation)); } catch { /* optionaler Marker */ }
  }
}
