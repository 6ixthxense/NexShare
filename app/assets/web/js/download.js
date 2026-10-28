/* NexShare web desktop — Download Station. Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- Download Station

const DL_STATUS = { queued: 'รอคิว', downloading: 'กำลังโหลด', paused: 'หยุดชั่วคราว', done: 'เสร็จแล้ว', seeding: 'กำลังแชร์ (seed)', error: 'ผิดพลาด', checking: 'กำลังตรวจสอบ', metadata: 'กำลังหาข้อมูล torrent' };

function fmtEta(secs) {
  if (!isFinite(secs) || secs <= 0) return '';
  if (secs < 60) return Math.ceil(secs) + ' วิ';
  if (secs < 3600) return Math.ceil(secs / 60) + ' นาที';
  return (secs / 3600).toFixed(1) + ' ชม.';
}

function addDownloadDialog(info, preset) {
  return new Promise(resolve => {
    const urls = el('textarea', { placeholder: 'https://example.com/file.zip\nmagnet:?xt=urn:btih:...', spellcheck: 'false' });
    if (preset) urls.value = preset;
    const dest = el('input', { value: info.defaultDir || '', spellcheck: 'false' });
    let torrentFile = null;
    const tName = el('span', { class: 'hint' });
    const tPick = el('input', { type: 'file', accept: '.torrent,application/x-bittorrent', class: 'hidden', onchange: () => {
      torrentFile = tPick.files[0] || null;
      tName.textContent = torrentFile ? ' ' + torrentFile.name : '';
    } });
    const close = v => { wrap.remove(); resolve(v); };
    const wrap = el('div', { id: 'modal', onmousedown: e => { if (e.target === wrap) close(null); } },
      el('div', { class: 'dialog wide' },
        el('div', { class: 'd-head', text: 'สร้างงานดาวน์โหลด' }),
        el('div', { class: 'd-body' },
          el('label', { class: 'f', text: 'ลิงก์ (ใส่ได้หลายบรรทัด) — http, https หรือ magnet' }), urls,
          info.torrent ? el('div', { style: { marginTop: '10px' } },
            el('button', { class: 'btn', onclick: () => tPick.click() }, icon('upload'), 'เลือกไฟล์ .torrent'), tName, tPick)
            : el('div', { class: 'hint', text: 'แอปเวอร์ชันนี้ยังไม่รองรับ BitTorrent' }),
          el('label', { class: 'f', text: 'บันทึกไปที่โฟลเดอร์ (บนมือถือ)' }), dest,
          el('div', { class: 'hint', text: 'มือถือจะโหลดเองแม้ปิดหน้าเว็บนี้ไปแล้ว' })),
        el('div', { class: 'd-foot' },
          el('button', { class: 'btn', text: 'ยกเลิก', onclick: () => close(null) }),
          el('button', { class: 'btn primary', text: 'เริ่มดาวน์โหลด', onclick: () => close({ urls: urls.value, dest: dest.value.trim(), torrentFile }) }))));
    document.body.append(wrap);
    urls.focus();
  });
}

async function submitDownloads(v) {
  if (!v) return false;
  let ok = false;
  try {
    if (v.urls.trim()) { const r = await api('dl/add', null, { method: 'POST', body: JSON.stringify({ urls: v.urls, dest: v.dest }) }); toast(`เพิ่มงานแล้ว ${r.added} รายการ`); ok = true; }
    if (v.torrentFile) {
      const r = await fetch('/api/dl/torrent' + qs({ dest: v.dest || null }), { method: 'POST', body: v.torrentFile });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || r.status);
      toast('เพิ่มไฟล์ torrent แล้ว');
      ok = true;
    }
  } catch (e) { toast('เพิ่มงานไม่สำเร็จ: ' + e.message, 4000); }
  return ok;
}

function DownloadStation(body, win) {
  const st = { filter: 'all', tasks: [], info: {}, sel: new Set() };
  const side = el('div', { class: 'ds-side' });
  const tbody = el('tbody');
  const empty = el('div', { class: 'empty hidden' }, fileIcon('folder'), 'ยังไม่มีงานดาวน์โหลด — กด "สร้าง" แล้ววางลิงก์หรือ magnet');
  const btn = (ico, label, run, cls) => el('button', { class: 'btn ' + (cls || ''), onclick: run }, icon(ico), el('span', { class: cls === 'primary' ? null : 'hide-sm', text: label }));
  const tools = el('div', { class: 'ds-tools' },
    btn('plus', 'สร้าง', () => create(), 'primary'),
    btn('play', 'ดำเนินการต่อ', () => act('resume')),
    btn('min', 'หยุดชั่วคราว', () => act('pause')),
    btn('trash', 'ลบงาน', () => removeSel()),
    el('div', { style: { flex: 1 } }),
    btn('folderUp', 'เปิดโฟลเดอร์', () => openFolder()),
    btn('x', 'ล้างงานที่เสร็จ', async () => { await api('dl/clear', null, { method: 'POST' }); refresh(); }));
  const table = el('table', { class: 'files dl' },
    el('colgroup', null, el('col', { style: { width: '36px' } }), el('col'), el('col', { style: { width: '90px' } }), el('col', { style: { width: '170px' } }), el('col', { class: 'col-type', style: { width: '120px' } }), el('col', { class: 'col-date', style: { width: '140px' } })),
    el('thead', null, el('tr', null, el('th', { class: 'chk' }), el('th', { text: 'ชื่อไฟล์' }), el('th', { text: 'ขนาด' }), el('th', { text: 'ความคืบหน้า' }), el('th', { class: 'col-type', text: 'ความเร็ว' }), el('th', { class: 'col-date', text: 'สถานะ' }))),
    tbody);
  body.append(el('div', { class: 'ds' }, side, el('div', { class: 'ds-main' }, tools, el('div', { class: 'ds-list' }, table, empty))));

  const FILTERS = [['all', 'ทั้งหมด'], ['active', 'กำลังโหลด'], ['done', 'เสร็จแล้ว'], ['paused', 'หยุดชั่วคราว'], ['error', 'ผิดพลาด']];
  const match = (t, f) => f === 'all' || (f === 'active' ? ['downloading', 'queued', 'checking', 'metadata', 'seeding'].includes(t.status) : t.status === f);

  function render() {
    side.replaceChildren(...FILTERS.map(([k, label]) => el('div', { class: 'tn' + (st.filter === k ? ' cur' : ''), onclick: () => { st.filter = k; render(); } },
      el('span', { text: label }), el('span', { class: 'cnt', text: st.tasks.filter(t => match(t, k)).length || '' }))));
    const shown = st.tasks.filter(t => match(t, st.filter));
    empty.classList.toggle('hidden', shown.length > 0);
    table.classList.toggle('hidden', !shown.length);
    tbody.replaceChildren(...shown.map(t => {
      const pct = t.total > 0 ? Math.min(100, t.done / t.total * 100) : (t.status === 'done' ? 100 : 0);
      const eta = t.status === 'downloading' && t.speed > 0 && t.total > 0 ? fmtEta((t.total - t.done) / t.speed) : '';
      const speed = t.status === 'downloading' || t.status === 'seeding'
        ? '▼ ' + fmtRate(t.speed) + (t.type === 'torrent' ? '\n▲ ' + fmtRate(t.upSpeed) : '') : '';
      const chk = el('input', { type: 'checkbox', checked: st.sel.has(t.id), onclick: e => { e.stopPropagation(); e.target.checked ? st.sel.add(t.id) : st.sel.delete(t.id); } });
      const tr = el('tr', { class: 'st-' + t.status + (st.sel.has(t.id) ? ' sel' : ''), title: t.error || t.url,
        onclick: () => { st.sel.clear(); st.sel.add(t.id); render(); },
        ondblclick: () => openTask(t),
        oncontextmenu: e => { e.preventDefault(); if (!st.sel.has(t.id)) { st.sel.clear(); st.sel.add(t.id); render(); } menu(taskMenu(t), e.clientX, e.clientY); } },
        el('td', { class: 'chk' }, chk),
        el('td', { class: 'name' }, el('div', null, fileIcon(t.type === 'torrent' ? 'archive' : kindOf({ name: t.name })), el('span', { text: t.name }))),
        el('td', { class: 'dim', text: t.total > 0 ? fmtSize(t.total) : '-' }),
        el('td', { class: 'prog' }, el('div', { class: 'pct', text: `${pct.toFixed(1)}%${eta ? ' · เหลือ ' + eta : ''}${t.type === 'torrent' && t.peers ? ' · ' + t.peers + ' peers' : ''}` }), el('div', { class: 'bar' }, el('i', { style: { width: pct + '%' } }))),
        el('td', { class: 'dim col-type', style: { whiteSpace: 'pre', lineHeight: '1.3', fontSize: '12px' }, text: speed }),
        el('td', { class: 'col-date' }, el('span', { class: 'st-pill ' + t.status, text: t.status === 'error' ? 'ผิดพลาด: ' + (t.error || '') : (DL_STATUS[t.status] || t.status) })));
      return tr;
    }));
    const active = st.tasks.filter(t => t.status === 'downloading').length;
    win.setTitle('Download Station' + (active ? ` — กำลังโหลด ${active}` : ''));
  }

  function taskMenu(t) {
    return [
      { label: 'เปิดไฟล์', ico: 'open', disabled: t.status !== 'done' && t.status !== 'seeding', run: () => openTask(t) },
      { label: 'เปิดโฟลเดอร์ปลายทาง', ico: 'folderUp', run: () => openApp('filestation', { path: t.dest }) },
      { label: 'คัดลอกลิงก์ต้นทาง', ico: 'link', run: () => dialog({ title: 'ลิงก์ต้นทาง', input: t.url, cancel: false }) },
      '-',
      { label: 'ดำเนินการต่อ', ico: 'play', disabled: !['paused', 'error'].includes(t.status), run: () => act('resume') },
      { label: 'หยุดชั่วคราว', ico: 'min', disabled: !['downloading', 'queued', 'seeding', 'metadata'].includes(t.status), run: () => act('pause') },
      { label: 'ลบงาน', ico: 'trash', danger: true, run: removeSel },
    ];
  }
  async function openTask(t) {
    if (t.status !== 'done' && t.status !== 'seeding') return;
    const path = t.dest + '/' + t.name;
    try {
      const r = await api('list', { path: t.dest });
      const e = r.entries.find(x => x.path === path);
      if (!e) return toast('ไม่พบไฟล์');
      if (e.dir) return openApp('filestation', { path });
      openEntry(e, r.entries.filter(x => !x.dir));
    } catch (e) { toast('เปิดไม่ได้: ' + e.message); }
  }
  async function act(kind) {
    for (const id of st.sel) await api('dl/' + kind, { id }, { method: 'POST' }).catch(() => {});
    refresh();
  }
  async function removeSel() {
    if (!st.sel.size) return;
    const files = el('input', { type: 'checkbox' });
    const ok = await new Promise(resolve => {
      const close = v => { wrap.remove(); resolve(v); };
      const wrap = el('div', { id: 'modal' }, el('div', { class: 'dialog' },
        el('div', { class: 'd-head', text: `ลบ ${st.sel.size} งาน?` }),
        el('div', { class: 'd-body' }, el('label', { style: { display: 'flex', gap: '8px', alignItems: 'center', cursor: 'pointer' } }, files, 'ลบไฟล์ที่โหลดมาแล้วด้วย')),
        el('div', { class: 'd-foot' }, el('button', { class: 'btn', text: 'ยกเลิก', onclick: () => close(false) }), el('button', { class: 'btn danger', text: 'ลบ', onclick: () => close(true) }))));
      document.body.append(wrap);
    });
    if (!ok) return;
    for (const id of st.sel) await api('dl/remove', { id, files: files.checked ? 1 : 0 }, { method: 'POST' }).catch(() => {});
    st.sel.clear();
    refresh();
  }
  function openFolder() {
    const t = st.tasks.find(x => st.sel.has(x.id));
    openApp('filestation', { path: t ? t.dest : st.info.defaultDir });
  }
  async function create(preset) {
    const v = await addDownloadDialog(st.info, preset);
    if (await submitDownloads(v)) refresh();
  }
  async function refresh() {
    try {
      const r = await api('dl/list');
      st.tasks = r.tasks;
      st.info = { defaultDir: r.defaultDir, torrent: r.torrent };
      for (const id of [...st.sel]) if (!st.tasks.some(t => t.id === id)) st.sel.delete(id);
      render();
    } catch { /* offline */ }
  }
  refresh();
  const timer = setInterval(refresh, 1500);
  return { destroy() { clearInterval(timer); }, handle(arg) { if (arg.add) create(arg.add); } };
}
