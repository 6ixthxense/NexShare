/* NexShare web desktop — File Station. Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- File Station

function FileStation(body, win) {
  const st = {
    path: null, back: [], fwd: [], entries: [], shown: [], sel: new Set(), anchor: null,
    sort: store.get('sort', { key: 'name', desc: false }), view: store.get('view', 'list'),
    hidden: store.get('hidden', false), searching: null,
  };

  const backBtn = el('button', { class: 'icon-btn', title: 'ย้อนกลับ', onclick: () => history(-1) }, icon('back'));
  const fwdBtn = el('button', { class: 'icon-btn', title: 'ไปข้างหน้า', onclick: () => history(1) }, icon('fwd'));
  const upBtn = el('button', { class: 'icon-btn hide-sm', title: 'โฟลเดอร์แม่', onclick: () => st.parent && go(st.parent) }, icon('up'));
  const crumbs = el('div', { class: 'crumbs' });
  const searchIn = el('input', { placeholder: 'ค้นหาในโฟลเดอร์นี้', onkeydown: e => {
    if (e.key === 'Enter') doSearch(searchIn.value.trim());
    if (e.key === 'Escape') { searchIn.value = ''; if (st.searching) load(); }
  } });
  const nav = el('div', { class: 'fs-nav' }, backBtn, fwdBtn, upBtn,
    el('button', { class: 'icon-btn', title: 'รีเฟรช', onclick: () => load() }, icon('refresh')),
    crumbs, el('div', { class: 'fs-search' }, icon('search'), searchIn));

  const uploadBtn = el('button', { class: 'btn primary', onclick: e => menuAt(e.currentTarget, [
    { label: 'อัปโหลดไฟล์', ico: 'upload', run: () => pick('#filePick') },
    { label: 'อัปโหลดทั้งโฟลเดอร์', ico: 'folderUp', run: () => pick('#folderPick') },
  ]) }, icon('upload'), 'อัปโหลด', icon('chev', 'chev'));
  const createBtn = el('button', { class: 'btn', onclick: () => mkdir() }, icon('newfolder'), el('span', { class: 'hide-sm', text: 'สร้างโฟลเดอร์' }));
  const actionBtn = el('button', { class: 'btn', onclick: e => menuAt(e.currentTarget, actionItems()) }, 'การทำงาน', icon('chev', 'chev'));
  const dlBtn = el('button', { class: 'btn', onclick: () => download() }, icon('download'), el('span', { class: 'hide-sm', text: 'ดาวน์โหลด' }));
  const delBtn = el('button', { class: 'btn hide-sm', onclick: () => del() }, icon('trash'));
  const viewList = el('button', { class: 'icon-btn', title: 'มุมมองรายการ', onclick: () => setView('list') }, icon('list'));
  const viewGrid = el('button', { class: 'icon-btn', title: 'มุมมองไอคอน', onclick: () => setView('grid') }, icon('grid'));
  const hidBtn = el('button', { class: 'icon-btn hide-sm', title: 'แสดงไฟล์ซ่อน', onclick: () => { st.hidden = !st.hidden; store.set('hidden', st.hidden); render(); } }, icon('eye'));
  const shareBtn = el('button', { class: 'btn hide-sm', title: 'สร้างลิงก์แชร์', onclick: () => { const s = selected(); if (s.length === 1) shareDialog(s[0]); else toast('เลือกไฟล์หรือโฟลเดอร์ 1 รายการเพื่อแชร์'); } }, icon('share'));
  const tools = el('div', { class: 'fs-tools' }, uploadBtn, createBtn, actionBtn, dlBtn, shareBtn, delBtn, el('div', { class: 'grow' }), hidBtn, viewList, viewGrid);
  /** Hide write tools for read-only accounts / folders. */
  function applyWritable() {
    const w = State.canWrite && st.writable !== false && !st.searching;
    for (const b of [uploadBtn, createBtn, delBtn, shareBtn]) b.classList.toggle('hidden', !w);
  }

  const list = el('div', { class: 'fs-list', tabindex: '0' });
  const status = el('div', { class: 'fs-status' });
  const side = el('div', { class: 'fs-side' });
  const main = el('div', { class: 'fs-main' }, nav, tools, list, status);
  body.append(el('div', { class: 'fs' }, side, main));

  // ---- sidebar tree
  function treeNode(name, path, depth) {
    const kids = el('div', { class: 'tree-kids hidden' });
    const caret = el('span', { class: 'caret' }, icon('caret'));
    const row = el('div', { class: 'tn', 'data-path': path, title: path }, caret, fileIcon('folder'), el('span', { class: 'nm', text: name }));
    let loaded = false;
    const toggle = async open => {
      const willOpen = open ?? kids.classList.contains('hidden');
      kids.classList.toggle('hidden', !willOpen);
      caret.classList.toggle('open', willOpen);
      if (willOpen && !loaded) {
        loaded = true;
        try {
          const r = await api('list', { path });
          const dirs = r.entries.filter(e => e.dir && !e.hidden).sort((a, b) => a.name.localeCompare(b.name));
          if (!dirs.length) caret.classList.add('none');
          for (const d of dirs) kids.append(treeNode(d.name, d.path, depth + 1));
        } catch { caret.classList.add('none'); }
      }
    };
    caret.addEventListener('click', e => { e.stopPropagation(); toggle(); });
    row.addEventListener('click', () => { go(path); toggle(true); });
    return el('div', { class: 'tree-node' }, row, kids);
  }
  function buildSide() {
    side.replaceChildren(el('div', { class: 'sec', text: State.folders.length ? 'โฟลเดอร์ของคุณ' : 'ที่เก็บข้อมูล' }));
    if (State.folders.length) for (const f of State.folders) side.append(treeNode(f.split('/').pop(), f, 0));
    else for (const r of State.roots) side.append(treeNode(r.name, r.path, 0));
    const main = State.folders.length ? null : State.roots[0];
    const extra = el('div', { class: 'sec', text: 'อื่น ๆ' });
    const link = (label, ico, run) => el('div', { class: 'tree-node' }, el('div', { class: 'tn', onclick: run },
      el('span', { class: 'caret none' }), icon(ico, 'side-ico'), el('span', { class: 'nm', text: label })));
    if (main) {
      side.append(el('div', { class: 'sec', text: 'เข้าถึงด่วน' }));
      for (const [n, label] of [['DCIM', 'กล้อง (DCIM)'], ['Pictures', 'รูปภาพ'], ['Download', 'ดาวน์โหลด'], ['Documents', 'เอกสาร'], ['Movies', 'วิดีโอ'], ['Music', 'เพลง']]) {
        const p = main.path + '/' + n;
        side.append(el('div', { class: 'tree-node' }, el('div', { class: 'tn', 'data-path': p, onclick: () => go(p) },
          el('span', { class: 'caret none' }), fileIcon('folder'), el('span', { class: 'nm', text: label }))));
      }
    }
    side.append(extra, link('ลิงก์ที่แชร์', 'link', () => openApp('shares')), link('ถังขยะ', 'trash', () => openApp('trash')));
  }
  function markSide() {
    for (const n of side.querySelectorAll('.tn')) n.classList.toggle('cur', n.dataset.path === st.path && !st.searching);
  }

  // ---- navigation
  async function go(path, push = true) {
    if (push && st.path && st.path !== path) { st.back.push(st.path); st.fwd = []; }
    st.path = path;
    st.searching = null;
    searchIn.value = '';
    await load();
  }
  function history(dir) {
    const from = dir < 0 ? st.back : st.fwd, to = dir < 0 ? st.fwd : st.back;
    if (!from.length) return;
    to.push(st.path);
    go(from.pop(), false);
  }
  async function load(keepSel) {
    if (!st.path) return;
    if (!keepSel) st.sel.clear();
    st.searching = null;
    list.replaceChildren(el('div', { class: 'empty', text: 'กำลังโหลด…' }));
    try {
      const r = await api('list', { path: st.path });
      st.entries = r.entries;
      st.parent = r.parent;
      st.writable = r.writable;
      st.error = null;
    } catch (e) {
      st.entries = [];
      st.parent = null;
      st.writable = false;
      st.error = e.message === 'no_permission'
        ? 'แอป NexShare บนมือถือยังไม่ได้รับสิทธิ์เข้าถึงไฟล์ — เปิดแอปบนมือถือแล้วกด "อนุญาตให้เข้าถึงไฟล์ทั้งหมด"'
        : 'เปิดโฟลเดอร์นี้ไม่ได้ (' + e.message + ')';
    }
    render();
    applyWritable();
  }
  async function doSearch(q) {
    if (!q) return load();
    st.searching = q;
    st.sel.clear();
    list.replaceChildren(el('div', { class: 'empty', text: 'กำลังค้นหา…' }));
    try {
      const r = await api('search', { path: st.path, q });
      st.entries = r.entries;
      st.error = null;
    } catch (e) { st.entries = []; st.error = e.message; }
    render();
  }

  // ---- rendering
  function sorted() {
    const { key, desc } = st.sort;
    const arr = st.entries.filter(e => st.hidden || !e.hidden);
    const cmp = {
      name: (a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }),
      size: (a, b) => a.size - b.size,
      mtime: (a, b) => a.mtime - b.mtime,
      type: (a, b) => kindOf(a).localeCompare(kindOf(b)) || extOf(a.name).localeCompare(extOf(b.name)),
    }[key];
    arr.sort((a, b) => (b.dir - a.dir) || (desc ? -cmp(a, b) : cmp(a, b)));
    return arr;
  }

  function renderCrumbs() {
    crumbs.replaceChildren();
    const r = rootOf(st.path || '');
    if (!r) return;
    const parts = st.path.slice(r.path.length).split('/').filter(Boolean);
    let acc = r.path;
    crumbs.append(el('button', { text: r.name, onclick: () => go(r.path) }));
    for (const p of parts) {
      acc += '/' + p;
      const target = acc;
      crumbs.append(el('span', { class: 'sep', text: '›' }), el('button', { text: p, onclick: () => go(target) }));
    }
    if (st.searching) crumbs.append(el('span', { class: 'sep', text: '›' }), el('button', { text: `ผลการค้นหา "${st.searching}"` }));
    crumbs.scrollLeft = crumbs.scrollWidth;
  }

  function render() {
    st.shown = sorted();
    renderCrumbs();
    markSide();
    backBtn.disabled = !st.back.length;
    fwdBtn.disabled = !st.fwd.length;
    upBtn.disabled = !st.parent;
    viewList.classList.toggle('on', st.view === 'list');
    viewGrid.classList.toggle('on', st.view === 'grid');
    hidBtn.classList.toggle('on', st.hidden);
    win.setTitle('File Station' + (st.path ? ' — ' + displayPath(st.path).split('/').pop() : ''));
    list.replaceChildren();
    if (st.error) { list.append(el('div', { class: 'notice', text: st.error })); updateSel(); return; }
    if (!st.shown.length) {
      list.append(el('div', { class: 'empty' }, fileIcon('folder'), st.searching ? 'ไม่พบไฟล์ที่ตรงกัน' : 'โฟลเดอร์นี้ว่างเปล่า — ลากไฟล์มาวางเพื่ออัปโหลด'));
      updateSel();
      return;
    }
    if (st.view === 'grid') renderGrid(); else renderTable();
    updateSel();
  }

  function renderTable() {
    const th = (key, label, cls) => el('th', {
      class: [cls, st.sort.key === key ? 'sorted' : '', st.sort.key === key && st.sort.desc ? 'desc' : ''].filter(Boolean).join(' '),
      onclick: () => { st.sort = { key, desc: st.sort.key === key ? !st.sort.desc : false }; store.set('sort', st.sort); render(); },
    }, label);
    const allChk = el('input', { type: 'checkbox', onclick: e => { e.stopPropagation(); if (e.target.checked) st.shown.forEach(x => st.sel.add(x.path)); else st.sel.clear(); updateSel(); } });
    const colgroup = el('colgroup', null, el('col', { style: { width: '36px' } }), el('col'), el('col', { style: { width: '96px' } }), el('col', { class: 'col-type', style: { width: '110px' } }), el('col', { class: 'col-date', style: { width: '150px' } }));
    const tbody = el('tbody');
    for (const e of st.shown) {
      const kind = kindOf(e);
      const chk = el('input', { type: 'checkbox', onclick: ev => { ev.stopPropagation(); toggleSel(e.path, ev.target.checked); } });
      const tr = el('tr', { 'data-path': e.path, class: e.hidden ? 'hidden-file' : null },
        el('td', { class: 'chk' }, chk),
        el('td', { class: 'name', title: e.name }, el('div', null, fileIcon(kind), el('span', { text: e.name }))),
        el('td', { class: 'dim', text: e.dir ? '' : fmtSize(e.size) }),
        el('td', { class: 'dim col-type', text: e.dir ? KIND_LABEL.folder : (extOf(e.name).toUpperCase() || KIND_LABEL.file) }),
        el('td', { class: 'dim col-date', text: fmtDate(e.mtime) }));
      bindItem(tr, e);
      tbody.append(tr);
    }
    list.append(el('table', { class: 'files' }, colgroup,
      el('thead', null, el('tr', null, el('th', { class: 'chk' }, allChk), th('name', 'ชื่อ'), th('size', 'ขนาด'), th('type', 'ชนิด', 'col-type'), th('mtime', 'แก้ไขล่าสุด', 'col-date'))),
      tbody));
  }

  function renderGrid() {
    const g = el('div', { class: 'grid-files' });
    for (const e of st.shown) {
      const kind = kindOf(e);
      const pic = el('div', { class: 'pic' });
      if (kind === 'image' || kind === 'video') {
        const img = el('img', { loading: 'lazy', src: thumbUrl(e.path, 200), alt: '' });
        img.onerror = () => img.replaceWith(fileIcon(kind));
        pic.append(img);
      } else pic.append(fileIcon(kind));
      const item = el('div', { class: 'gf', 'data-path': e.path, title: e.name }, pic, el('div', { class: 'nm', text: e.name }));
      bindItem(item, e);
      g.append(item);
    }
    list.append(g);
  }

  // ---- selection
  const coarse = matchMedia('(pointer: coarse)').matches;
  function bindItem(node, e) {
    node.addEventListener('click', ev => {
      if (coarse && !ev.ctrlKey && !ev.shiftKey && !st.sel.size) { open(e); return; }
      if (ev.shiftKey && st.anchor) {
        const a = st.shown.findIndex(x => x.path === st.anchor), b = st.shown.findIndex(x => x.path === e.path);
        if (!ev.ctrlKey) st.sel.clear();
        for (let i = Math.min(a, b); i <= Math.max(a, b); i++) st.sel.add(st.shown[i].path);
      } else if (ev.ctrlKey || ev.metaKey || (coarse && st.sel.size)) {
        toggleSel(e.path, !st.sel.has(e.path));
        st.anchor = e.path;
        return;
      } else {
        st.sel.clear();
        st.sel.add(e.path);
        st.anchor = e.path;
      }
      updateSel();
    });
    node.addEventListener('dblclick', () => open(e));
    node.addEventListener('contextmenu', ev => {
      ev.preventDefault();
      if (!st.sel.has(e.path)) { st.sel.clear(); st.sel.add(e.path); st.anchor = e.path; updateSel(); }
      menu(actionItems(), ev.clientX, ev.clientY);
    });
  }
  function toggleSel(p, on) {
    if (on) st.sel.add(p); else st.sel.delete(p);
    updateSel();
  }
  function updateSel() {
    for (const n of list.querySelectorAll('[data-path]')) {
      const on = st.sel.has(n.dataset.path);
      n.classList.toggle('sel', on);
      const c = n.querySelector('input[type=checkbox]');
      if (c) c.checked = on;
    }
    const n = st.sel.size;
    dlBtn.disabled = !n;
    delBtn.disabled = !n;
    const files = st.shown.filter(e => !e.dir).length, dirs = st.shown.length - files;
    const size = selected().reduce((s, e) => s + (e.dir ? 0 : e.size), 0);
    status.replaceChildren(...[
      el('span', { text: `${st.shown.length} รายการ (${dirs} โฟลเดอร์, ${files} ไฟล์)` }),
      n ? el('span', { text: `เลือก ${n} รายการ${size ? ' · ' + fmtSize(size) : ''}` }) : null,
      State.clipboard ? el('span', { text: `คลิปบอร์ด: ${State.clipboard.paths.length} รายการ (${State.clipboard.move ? 'ตัด' : 'คัดลอก'})` }) : null].filter(Boolean));
  }
  const selected = () => st.shown.filter(e => st.sel.has(e.path));

  list.addEventListener('click', e => { if (e.target === list || e.target.classList.contains('grid-files')) { st.sel.clear(); updateSel(); } });
  list.addEventListener('contextmenu', e => {
    if (e.target.closest('[data-path]')) return;
    e.preventDefault();
    st.sel.clear();
    updateSel();
    const w = State.canWrite && st.writable !== false;
    menu([
      w && { label: 'สร้างโฟลเดอร์', ico: 'newfolder', run: mkdir },
      w && { label: 'อัปโหลดไฟล์', ico: 'upload', run: () => pick('#filePick') },
      w && { label: 'อัปโหลดทั้งโฟลเดอร์', ico: 'folderUp', run: () => pick('#folderPick') },
      w && State.clipboard && { label: 'วาง', ico: 'paste', run: paste },
      w && st.path && { label: 'แชร์โฟลเดอร์นี้', ico: 'share', run: () => shareDialog({ path: st.path, name: st.path.split('/').pop(), dir: true }) },
      w ? '-' : null,
      { label: 'รีเฟรช', ico: 'refresh', run: () => load() },
    ], e.clientX, e.clientY);
  });
  list.addEventListener('keydown', e => {
    if (e.target.tagName === 'INPUT') return;
    if (e.key === 'Delete') del(e.shiftKey);
    else if (e.key === 'F2') rename();
    else if (e.key === 'Backspace' && st.parent) go(st.parent);
    else if (e.key === 'Enter' && st.sel.size === 1) open(selected()[0]);
    else if ((e.ctrlKey || e.metaKey) && e.key === 'a') { e.preventDefault(); st.shown.forEach(x => st.sel.add(x.path)); updateSel(); }
    else if ((e.ctrlKey || e.metaKey) && e.key === 'c' && st.sel.size) clip(false);
    else if ((e.ctrlKey || e.metaKey) && e.key === 'x' && st.sel.size) clip(true);
    else if ((e.ctrlKey || e.metaKey) && e.key === 'v' && State.clipboard) paste();
  });

  // ---- actions
  function actionItems() {
    const sel = selected(), one = sel.length === 1 ? sel[0] : null;
    const w = State.canWrite && (st.writable !== false || st.searching);
    return [
      { label: 'เปิด', ico: 'open', disabled: !one, run: () => open(one) },
      { label: 'ดาวน์โหลด', ico: 'download', disabled: !sel.length, run: download },
      { label: 'ดาวน์โหลดเป็น ZIP', ico: 'zip', disabled: !sel.length, run: () => download(true) },
      w && { label: 'สร้างลิงก์แชร์…', ico: 'share', disabled: !one, run: () => shareDialog(one) },
      { label: 'คัดลอกลิงก์ดาวน์โหลด (ใน Wi-Fi)', ico: 'link', disabled: !one || one.dir, run: () => copyLink(one) },
      one && w && /\.torrent$/i.test(one.name) ? { label: 'เพิ่มใน Download Station', ico: 'download', run: () => addTorrentFromPhone(one) } : null,
      one && State.admin && !one.dir && kindOf(one) === 'image' ? { label: 'ตั้งเป็นวอลเปเปอร์', ico: 'open', run: () => setWallpaperFromFile(one) } : null,
      w ? '-' : null,
      w && { label: 'เปลี่ยนชื่อ', ico: 'rename', disabled: !one, run: rename },
      w && { label: 'คัดลอก', ico: 'copy', disabled: !sel.length, run: () => clip(false) },
      w && { label: 'ตัด', ico: 'cut', disabled: !sel.length, run: () => clip(true) },
      w && { label: 'วาง', ico: 'paste', disabled: !State.clipboard, run: paste },
      w ? '-' : null,
      w && { label: State.trash ? 'ย้ายไปถังขยะ' : 'ลบ', ico: 'trash', danger: true, disabled: !sel.length, run: () => del(false) },
      w && State.trash && { label: 'ลบถาวร', ico: 'x', danger: true, disabled: !sel.length, run: () => del(true) },
    ];
  }
  async function setWallpaperFromFile(e) {
    try { await api('wallpaper', { path: e.path }, { method: 'POST' }); toast('ตั้งวอลเปเปอร์แล้ว'); }
    catch (x) { toast('ไม่สำเร็จ: ' + x.message); }
  }
  async function addTorrentFromPhone(e) {
    try {
      const blob = await (await fetch(fileUrl(e.path))).blob();
      if (await submitDownloads({ urls: '', dest: '', torrentFile: blob })) openApp('download');
    } catch (x) { toast('ไม่สำเร็จ: ' + x.message); }
  }
  function open(e) {
    if (e.dir) go(e.path);
    else openEntry(e, st.shown.filter(x => !x.dir), st.entries);
  }
  function download(asZip) {
    const sel = selected();
    if (!sel.length) return;
    const url = !asZip && sel.length === 1 && !sel[0].dir ? fileUrl(sel[0].path, true) : '/api/zip' + qs({ p: sel.map(e => e.path) });
    const a = el('a', { href: url, download: '' });
    document.body.append(a);
    a.click();
    a.remove();
  }
  async function copyLink(e) {
    const url = location.origin + fileUrl(e.path, true);
    if (await copyText(url)) toast('คัดลอกลิงก์แล้ว (ผู้รับต้องเข้าสู่ระบบ NexShare — ใช้ "สร้างลิงก์แชร์" ถ้าจะส่งให้คนอื่น)', 4200);
    else dialog({ title: 'ลิงก์ดาวน์โหลด', input: url, cancel: false });
  }
  async function mkdir() {
    const name = await dialog({ title: 'สร้างโฟลเดอร์', input: 'โฟลเดอร์ใหม่', okText: 'สร้าง' });
    if (!name) return;
    try { await api('mkdir', { path: st.path, name }, { method: 'POST' }); await load(); toast('สร้างโฟลเดอร์แล้ว'); }
    catch (e) { toast(e.status === 409 ? 'มีชื่อนี้อยู่แล้ว' : 'สร้างไม่สำเร็จ: ' + e.message); }
  }
  async function rename() {
    const e = selected()[0];
    if (!e) return;
    const name = await dialog({ title: 'เปลี่ยนชื่อ', input: e.name, okText: 'บันทึก' });
    if (!name || name === e.name) return;
    try { await api('rename', { path: e.path, name }, { method: 'POST' }); await load(); }
    catch (err) { toast(err.status === 409 ? 'มีชื่อนี้อยู่แล้ว' : 'เปลี่ยนชื่อไม่สำเร็จ'); }
  }
  async function del(permanent) {
    const sel = selected();
    if (!sel.length || !State.canWrite) return;
    const toTrash = State.trash && !permanent;
    const what = sel.length === 1 ? `"${sel[0].name}"` : sel.length + ' รายการ';
    const ok = await dialog(toTrash
      ? { title: 'ย้าย ' + what + ' ไปถังขยะ?', message: `กู้คืนได้ใน Recycle Bin ภายใน ${State.trashDays || 30} วัน (กด Shift+Delete เพื่อลบถาวร)`, okText: 'ย้ายไปถังขยะ', danger: true }
      : { title: 'ลบ ' + what + ' ถาวร?', message: 'ไฟล์จะถูกลบออกจากมือถือถาวร กู้คืนไม่ได้', okText: 'ลบถาวร', danger: true });
    if (!ok) return;
    try {
      const r = await api('delete', { p: sel.map(e => e.path), permanent: toTrash ? null : 1 }, { method: 'POST' });
      toast(r.trash ? `ย้ายไปถังขยะแล้ว ${r.deleted} รายการ` : `ลบแล้ว ${r.deleted} รายการ`);
    } catch (e) { toast('ลบไม่สำเร็จ: ' + e.message); }
    load();
  }
  function clip(move) {
    State.clipboard = { move, paths: selected().map(e => e.path) };
    toast((move ? 'ตัด ' : 'คัดลอก ') + State.clipboard.paths.length + ' รายการ — ไปที่โฟลเดอร์ปลายทางแล้วกด วาง');
    updateSel();
  }
  async function paste() {
    const c = State.clipboard;
    if (!c) return;
    toast('กำลัง' + (c.move ? 'ย้าย' : 'คัดลอก') + '…');
    try {
      const r = await api(c.move ? 'move' : 'copy', { dst: st.path, p: c.paths }, { method: 'POST' });
      toast(`${c.move ? 'ย้าย' : 'คัดลอก'}แล้ว ${r.done} รายการ`);
      if (c.move) State.clipboard = null;
    } catch (e) { toast('ไม่สำเร็จ: ' + e.message); }
    load();
  }
  function pick(sel) {
    const input = $(sel);
    input.value = '';
    input.onchange = () => {
      Uploader.add([...input.files].map(f => ({ file: f, rel: (f.webkitRelativePath || '').split('/').slice(0, -1).join('/') })), st.path);
    };
    input.click();
  }
  function setView(v) { st.view = v; store.set('view', v); render(); }

  // ---- drag & drop upload
  let dragDepth = 0;
  list.addEventListener('dragenter', e => { if ([...e.dataTransfer.types].includes('Files')) { dragDepth++; list.classList.add('drop'); } });
  list.addEventListener('dragleave', () => { if (--dragDepth <= 0) { dragDepth = 0; list.classList.remove('drop'); } });
  list.addEventListener('dragover', e => { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; });
  list.addEventListener('drop', async e => {
    e.preventDefault();
    dragDepth = 0;
    list.classList.remove('drop');
    if (!st.path || st.error) return;
    const dest = st.path;
    const entries = [...e.dataTransfer.items].map(i => i.webkitGetAsEntry && i.webkitGetAsEntry()).filter(Boolean);
    if (!entries.length) { Uploader.add([...e.dataTransfer.files].map(f => ({ file: f, rel: '' })), dest); return; }
    const out = [];
    const walk = (entry, rel) => new Promise(res => {
      if (entry.isFile) entry.file(f => { out.push({ file: f, rel }); res(); }, () => res());
      else {
        const reader = entry.createReader();
        const sub = rel ? rel + '/' + entry.name : entry.name;
        const batch = () => reader.readEntries(async items => {
          if (!items.length) return res();
          for (const it of items) await walk(it, sub);
          batch();
        }, () => res());
        batch();
      }
    });
    for (const en of entries) await walk(en, '');
    Uploader.add(out, dest);
  });

  const onUploaded = dest => { if (st.path && !st.searching && (dest === st.path || dest.startsWith(st.path + '/'))) load(true); };
  Uploader.listeners.add(onUploaded);

  buildSide();
  go(State.folders.length ? State.folders[0] : State.roots[0] ? State.roots[0].path : null);
  setTimeout(() => list.focus(), 50);

  return {
    handle(arg) { if (arg.path) go(arg.path); if (arg.search) { searchIn.focus(); } },
    destroy() { Uploader.listeners.delete(onUploaded); },
  };
}
