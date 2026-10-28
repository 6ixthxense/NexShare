/* NexShare web desktop — Backup Station and the folder picker. Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- folder picker

/** Lets the user browse the phone and pick a folder. Resolves to a path or null. */
function pickFolder(start, title = 'เลือกโฟลเดอร์บนมือถือ') {
  return new Promise(resolve => {
    let cur = start || (State.folders[0] || (State.roots[0] && State.roots[0].path));
    const crumbs = el('div', { class: 'pick-path mono' });
    const listBox = el('div', { class: 'pick-list' });
    const close = v => { wrap.remove(); resolve(v); };
    async function show(path) {
      cur = path;
      crumbs.textContent = displayPath(path);
      listBox.replaceChildren(el('div', { class: 'muted', text: 'กำลังโหลด…' }));
      try {
        const r = await api('list', { path });
        const dirs = r.entries.filter(e => e.dir && !e.hidden).sort((a, b) => a.name.localeCompare(b.name));
        listBox.replaceChildren(
          r.parent ? el('button', { class: 'pick-item', onclick: () => show(r.parent) }, icon('up'), '..') : null,
          ...dirs.map(d => el('button', { class: 'pick-item', onclick: () => show(d.path) }, fileIcon('folder'), d.name)));
        if (!dirs.length && !r.parent) listBox.append(el('div', { class: 'muted', text: 'ไม่มีโฟลเดอร์ย่อย' }));
      } catch (e) { listBox.replaceChildren(el('div', { class: 'f-err', text: e.message })); }
    }
    const roots = el('div', { class: 'pick-roots' }, ...(State.folders.length ? State.folders.map(f => [f.split('/').pop(), f]) : State.roots.map(r => [r.name, r.path]))
      .map(([n, p]) => el('button', { class: 'btn small', onclick: () => show(p) }, n)));
    const newBtn = State.canWrite ? el('button', { class: 'btn', onclick: async () => {
      const name = await dialog({ title: 'โฟลเดอร์ใหม่', input: 'โฟลเดอร์ใหม่', okText: 'สร้าง' });
      if (!name) return;
      try { const r = await api('mkdir', { path: cur, name }, { method: 'POST' }); show(r.path); } catch (e) { toast(e.message); }
    } }, icon('newfolder'), 'สร้างโฟลเดอร์') : null;
    const wrap = el('div', { id: 'modal', onmousedown: e => { if (e.target === wrap) close(null); } },
      el('div', { class: 'dialog form wide' },
        el('div', { class: 'd-head', text: title }),
        el('div', { class: 'd-body' }, roots, crumbs, listBox),
        el('div', { class: 'd-foot' }, newBtn, el('span', { class: 'grow' }), el('button', { class: 'btn', text: 'ยกเลิก', onclick: () => close(null) }),
          el('button', { class: 'btn primary', text: 'เลือกโฟลเดอร์นี้', onclick: () => close(cur) }))));
    wrap.addEventListener('keydown', e => { if (e.key === 'Escape') close(null); });
    document.body.append(wrap);
    show(cur);
  });
}

// ---------------------------------------------------------------- Backup Station

/**
 * Backs up a folder from this computer to the phone, uploading only new or changed files
 * (compared by path + size). With the File System Access API (Chrome/Edge over HTTPS) the
 * chosen folder is remembered so "Back up now" re-runs in one click; elsewhere the folder
 * is picked each time.
 */
function BackupStation(body, win) {
  const pane = el('div', { class: 'pane' });
  body.append(pane);
  const fsAccess = 'showDirectoryPicker' in window && window.isSecureContext;
  const st = {
    jobs: store.get('backup.jobs', []), // [{ id, name, dest, last, files, bytes }]
    running: null,
  };
  const save = () => store.set('backup.jobs', st.jobs.map(({ handle, ...j }) => j));

  // Folder handles are kept in IndexedDB (they can't go in localStorage).
  const idb = {
    open() {
      return new Promise((res, rej) => {
        const r = indexedDB.open('nexshare-backup', 1);
        r.onupgradeneeded = () => r.result.createObjectStore('handles');
        r.onsuccess = () => res(r.result);
        r.onerror = () => rej(r.error);
      });
    },
    async get(id) {
      try {
        const db = await this.open();
        return await new Promise(res => { const t = db.transaction('handles').objectStore('handles').get(id); t.onsuccess = () => res(t.result); t.onerror = () => res(null); });
      } catch { return null; }
    },
    async put(id, h) {
      try {
        const db = await this.open();
        await new Promise(res => { const t = db.transaction('handles', 'readwrite'); t.objectStore('handles').put(h, id); t.oncomplete = res; t.onerror = res; });
      } catch { /* private mode */ }
    },
    async del(id) {
      try {
        const db = await this.open();
        await new Promise(res => { const t = db.transaction('handles', 'readwrite'); t.objectStore('handles').delete(id); t.oncomplete = res; t.onerror = res; });
      } catch { /* ignore */ }
    },
  };

  const folderInput = el('input', { type: 'file', webkitdirectory: true, multiple: true, class: 'hidden', onchange: e => {
    const files = [...e.target.files];
    e.target.value = '';
    if (pendingDest && files.length) run(null, files.map(f => ({ file: f, rel: f.webkitRelativePath.split('/').slice(1).join('/') })), pendingDest, files[0].webkitRelativePath.split('/')[0]);
  } });
  let pendingDest = null;
  body.append(folderInput);

  function render() {
    const r = st.running;
    fill(pane,
      el('h2', { text: 'Backup Station' }),
      el('p', { class: 'muted', text: 'สำรองโฟลเดอร์จากคอมพิวเตอร์ไปเก็บในมือถือ ส่งเฉพาะไฟล์ใหม่หรือที่เปลี่ยนแปลง' }),
      !fsAccess ? el('div', { class: 'notice', text: 'เบราว์เซอร์นี้จำโฟลเดอร์ไม่ได้ ต้องเลือกโฟลเดอร์ทุกครั้งที่สำรอง (ใช้ Chrome/Edge ผ่าน HTTPS เพื่อกดสำรองครั้งเดียวได้)' }) : null,
      r ? el('div', { class: 'card' },
        el('h3', { text: 'กำลังสำรอง: ' + r.name, style: { marginTop: 0 } }),
        el('div', { class: 'meter' }, el('div', { class: 'row' }, el('span', { text: r.phase }), el('b', { text: r.total ? `${r.done}/${r.total} ไฟล์ · ${fmtSize(r.doneBytes)} / ${fmtSize(r.totalBytes)}` : '' })),
          el('div', { class: 'bar' }, el('i', { style: { width: (r.totalBytes ? r.doneBytes / r.totalBytes * 100 : r.total ? r.done / r.total * 100 : 0) + '%' } }))),
        r.failed ? el('div', { class: 'f-err', text: `ล้มเหลว ${r.failed} ไฟล์` }) : null) : null,
      el('div', { class: 'card' },
        el('div', { class: 'pane-head' }, el('h3', { text: 'งานสำรองข้อมูล', style: { margin: 0 } }), el('span', { class: 'grow' }),
          el('button', { class: 'btn primary', disabled: !!r, onclick: addJob }, icon('plus'), 'เพิ่มงานสำรอง')),
        st.jobs.length ? el('table', { class: 'tbl' },
          el('thead', null, el('tr', null, ...['โฟลเดอร์บนคอม', 'เก็บไว้ที่', 'ล่าสุด', ''].map(h => el('th', { text: h })))),
          el('tbody', null, ...st.jobs.map(j => el('tr', null,
            el('td', { class: 'name-cell' }, fileIcon('folder'), el('span', { text: j.name })),
            el('td', { class: 'muted', text: displayPath(j.dest) }),
            el('td', { text: j.last ? fmtDate(j.last) + ` · ${j.files} ไฟล์` : 'ยังไม่เคย' }),
            el('td', { class: 'actions' },
              el('button', { class: 'btn small', disabled: !!r, onclick: () => runJob(j) }, icon('sync'), 'สำรองตอนนี้'),
              el('button', { class: 'icon-btn danger', title: 'ลบงาน (ไม่ลบไฟล์)', disabled: !!r, onclick: () => removeJob(j) }, icon('trash')))))))
          : el('div', { class: 'empty', text: 'ยังไม่มีงานสำรอง' })));
  }

  async function addJob() {
    let handle = null, name;
    if (fsAccess) {
      try { handle = await window.showDirectoryPicker({ mode: 'read' }); } catch { return; }
      name = handle.name;
    }
    const base = State.folders[0] || (State.roots[0] && State.roots[0].path);
    const dest = await pickFolder(base, 'เลือกที่เก็บสำรองบนมือถือ');
    if (!dest) return;
    if (!fsAccess) {
      pendingDest = dest;
      folderInput.click();
      return;
    }
    const job = { id: 'j' + Date.now(), name, dest, last: 0, files: 0 };
    await idb.put(job.id, handle);
    st.jobs.push(job);
    save();
    render();
    runJob(job);
  }

  async function removeJob(j) {
    if (!await dialog({ title: 'ลบงานสำรอง "' + j.name + '"?', message: 'ไฟล์ที่สำรองไว้ในมือถือจะยังอยู่', okText: 'ลบงาน', danger: true })) return;
    st.jobs = st.jobs.filter(x => x !== j);
    await idb.del(j.id);
    save();
    render();
  }

  async function runJob(j) {
    if (!fsAccess) { pendingDest = j.dest; folderInput.click(); return; }
    const handle = await idb.get(j.id);
    if (!handle) { toast('ไม่พบสิทธิ์โฟลเดอร์เดิม — ลบงานแล้วเพิ่มใหม่'); return; }
    if ((await handle.queryPermission({ mode: 'read' })) !== 'granted' && (await handle.requestPermission({ mode: 'read' })) !== 'granted') {
      toast('ไม่ได้รับสิทธิ์อ่านโฟลเดอร์');
      return;
    }
    st.running = { name: j.name, phase: 'กำลังอ่านรายชื่อไฟล์…', done: 0, total: 0, doneBytes: 0, totalBytes: 0, failed: 0 };
    render();
    const items = [];
    async function walk(dir, rel) {
      for await (const [name, h] of dir.entries()) {
        if (name.startsWith('.') || name === 'node_modules' || name === 'Thumbs.db' || name === 'desktop.ini') continue;
        const r = rel ? rel + '/' + name : name;
        if (h.kind === 'directory') await walk(h, r);
        else items.push({ handle: h, rel: r });
        if (items.length % 500 === 0) { st.running.phase = `พบ ${items.length} ไฟล์…`; render(); }
      }
    }
    try { await walk(handle, ''); } catch (e) { st.running = null; render(); toast('อ่านโฟลเดอร์ไม่ได้: ' + e.message); return; }
    const files = [];
    for (const it of items) {
      try { const f = await it.handle.getFile(); files.push({ file: f, rel: it.rel }); } catch { /* unreadable */ }
    }
    await run(j, files, j.dest, j.name);
  }

  /** Compares with the phone, uploads what's missing, then records the result. */
  async function run(job, files, dest, name) {
    st.running = st.running || { name, failed: 0 };
    Object.assign(st.running, { name, phase: 'กำลังเทียบกับไฟล์ในมือถือ…', done: 0, total: 0, doneBytes: 0, totalBytes: 0, failed: 0 });
    render();
    const target = dest + '/' + name;
    try { await api('mkdir', { path: dest, name }, { method: 'POST' }); } catch (e) { if (e.status !== 409) { st.running = null; render(); toast(e.message); return; } }
    const missing = new Set();
    for (let i = 0; i < files.length; i += 2000) {
      const batch = files.slice(i, i + 2000);
      try {
        const r = await api('exists', null, { method: 'POST', body: JSON.stringify({ path: target, items: batch.map(f => ({ rel: f.rel, size: f.file.size })) }) });
        r.missing.forEach(m => missing.add(m));
      } catch (e) { st.running = null; render(); toast('เทียบไฟล์ไม่สำเร็จ: ' + e.message); return; }
    }
    const todo = files.filter(f => missing.has(f.rel));
    const r = st.running;
    r.total = todo.length;
    r.totalBytes = todo.reduce((n, f) => n + f.file.size, 0);
    r.phase = todo.length ? 'กำลังอัปโหลด' : 'ไม่มีไฟล์ใหม่';
    render();
    if (todo.length) {
      await new Promise(resolve => {
        let left = todo.length;
        Uploader.add(todo.map(f => {
          const slash = f.rel.lastIndexOf('/');
          return { file: f.file, rel: slash > 0 ? f.rel.slice(0, slash) : '', overwrite: true, cb: ok => {
            r.done++;
            r.doneBytes += f.file.size;
            if (!ok) r.failed++;
            if (--left === 0) resolve();
            else if (r.done % 5 === 0) render();
          } };
        }), target);
      });
    }
    const summary = todo.length ? `สำรอง ${name}: ส่ง ${todo.length - r.failed} ไฟล์ (${fmtSize(r.totalBytes)})` + (r.failed ? `, ล้มเหลว ${r.failed}` : '') : `สำรอง ${name}: ไม่มีอะไรเปลี่ยน`;
    notify(summary);
    toast(summary, 4000);
    if (job) {
      job.last = Date.now();
      job.files = files.length;
      save();
    }
    st.running = null;
    render();
  }

  render();
  return {};
}
