/* NexShare web desktop — File Station navigation & views. Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- File Station

function FileStation(body, win) {
  const st = {
    path: null, back: [], fwd: [], entries: [], shown: [], sel: new Set(), anchor: null,
    sort: store.get('sort', { key: 'name', desc: false }), view: store.get('view', 'list'),
    hidden: store.get('hidden', false), searching: null,
  };

  const selected = () => st.shown.filter(e => st.sel.has(e.path));
  function open(e) {
    if (e.dir) go(e.path);
    else openEntry(e, st.shown.filter(x => !x.dir), st.entries);
  }

  const list = el('div', { class: 'fs-list', tabindex: '0' });
  const status = el('div', { class: 'fs-status' });
  const side = el('div', { class: 'fs-side' });

  let ops;

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
    { label: 'อัปโหลดไฟล์', ico: 'upload', run: () => ops.pick('#filePick') },
    { label: 'อัปโหลดทั้งโฟลเดอร์', ico: 'folderUp', run: () => ops.pick('#folderPick') },
  ]) }, icon('upload'), 'อัปโหลด', icon('chev', 'chev'));
  const createBtn = el('button', { class: 'btn', onclick: () => ops.mkdir() }, icon('newfolder'), el('span', { class: 'hide-sm', text: 'สร้างโฟลเดอร์' }));
  const actionBtn = el('button', { class: 'btn', onclick: e => menuAt(e.currentTarget, ops.actionItems()) }, 'การทำงาน', icon('chev', 'chev'));
  const dlBtn = el('button', { class: 'btn', onclick: () => ops.download(false) }, icon('download'), el('span', { class: 'hide-sm', text: 'ดาวน์โหลด' }));
  const delBtn = el('button', { class: 'btn hide-sm', onclick: () => ops.del(false) }, icon('trash'));
  const viewList = el('button', { class: 'icon-btn', title: 'มุมมองรายการ', onclick: () => setView('list') }, icon('list'));
  const viewGrid = el('button', { class: 'icon-btn', title: 'มุมมองไอคอน', onclick: () => setView('grid') }, icon('grid'));
  const hidBtn = el('button', { class: 'icon-btn hide-sm', title: 'แสดงไฟล์ซ่อน', onclick: () => { st.hidden = !st.hidden; store.set('hidden', st.hidden); render(); } }, icon('eye'));
  const shareBtn = el('button', {
    class: 'btn hide-sm', title: 'สร้างลิงก์แชร์ / ขอไฟล์',
    onclick: e => {
      const s = selected();
      if (s.length === 1) {
        if (s[0].dir) {
          menuAt(e.currentTarget, [
            { label: 'สร้างลิงก์แชร์โฟลเดอร์นี้…', ico: 'share', run: () => shareDialog(s[0], 'share') },
            { label: 'สร้างลิงก์ขอไฟล์เข้าโฟลเดอร์นี้…', ico: 'upload', run: () => shareDialog(s[0], 'request') },
          ]);
        } else {
          shareDialog(s[0], 'share');
        }
      } else if (st.path) {
        const curDir = { path: st.path, name: st.path.split(/[/\\]/).pop() || 'Folder', dir: true };
        menuAt(e.currentTarget, [
          { label: 'แชร์โฟลเดอร์ปัจจุบัน…', ico: 'share', run: () => shareDialog(curDir, 'share') },
          { label: 'สร้างลิงก์ขอไฟล์เข้าโฟลเดอร์ปัจจุบัน…', ico: 'upload', run: () => shareDialog(curDir, 'request') },
        ]);
      } else {
        toast('เลือกไฟล์หรือโฟลเดอร์ 1 รายการเพื่อแชร์');
      }
    },
  }, icon('share'));
  const tools = el('div', { class: 'fs-tools' }, uploadBtn, createBtn, actionBtn, dlBtn, shareBtn, delBtn, el('div', { class: 'grow' }), hidBtn, viewList, viewGrid);

  ops = createFileOps({ st, selected, open, load, updateSel, list });

  /** Hide write tools for read-only accounts / folders. */
  function applyWritable() {
    const w = State.canWrite && st.writable !== false && !st.searching;
    for (const b of [uploadBtn, createBtn, delBtn, shareBtn]) b.classList.toggle('hidden', !w);
  }

  const main = el('div', { class: 'fs-main' }, nav, tools, list, status);
  body.append(el('div', { class: 'fs' }, side, main));

  // ---- sidebar tree
  function treeNode(name, path, depth) {
    const kids = el('div', { class: 'tree-kids hidden' });
    const caret = el('span', { class: 'caret' }, icon('caret'));
    const row = el('div', { class: 'tn', 'data-path': path, title: path }, caret, fileIcon('folder'), el('span', { class: 'nm', text: name }));
    let loaded = false;
    const toggle = async openTree => {
      const willOpen = openTree ?? kids.classList.contains('hidden');
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
    if (State.folders.length) for (const f of State.folders) side.append(treeNode(f.split(/[/\\]/).pop(), f, 0));
    else for (const r of State.roots) side.append(treeNode(r.name, r.path, 0));
    const mainRoot = State.folders.length ? null : State.roots[0];
    const extra = el('div', { class: 'sec', text: 'อื่น ๆ' });
    const link = (label, ico, run) => el('div', { class: 'tree-node' }, el('div', { class: 'tn', onclick: run },
      el('span', { class: 'caret none' }), icon(ico, 'side-ico'), el('span', { class: 'nm', text: label })));
    if (mainRoot) {
      side.append(el('div', { class: 'sec', text: 'เข้าถึงด่วน' }));
      for (const [n, label] of [['DCIM', 'กล้อง (DCIM)'], ['Pictures', 'รูปภาพ'], ['Download', 'ดาวน์โหลด'], ['Documents', 'เอกสาร'], ['Movies', 'วิดีโอ'], ['Music', 'เพลง']]) {
        const p = mainRoot.path + '/' + n;
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
    const parts = st.path.slice(r.path.length).split(/[/\\]/).filter(Boolean);
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
    win.setTitle('File Station' + (st.path ? ' — ' + displayPath(st.path).split(/[/\\]/).pop() : ''));
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
    const colgroup = el('colgroup', null, el('col', { style: { width: '36px' } }), el('col'), el('col', { style: { width: '96px' } }), el('col', { class: 'col-type', style: { width: '110px' } }), el('col', { class: 'col-date', style: { width: '150px' } }), el('col', { style: { width: '38px' } }));
    const tbody = el('tbody');
    for (const e of st.shown) {
      const kind = kindOf(e);
      const chk = el('input', { type: 'checkbox', onclick: ev => { ev.stopPropagation(); toggleSel(e.path, ev.target.checked); } });
      const moreBtn = el('button', {
        class: 'row-more', title: 'เมนู', text: '⋯',
        onclick: ev => {
          ev.stopPropagation();
          if (!st.sel.has(e.path)) { st.sel.clear(); st.sel.add(e.path); st.anchor = e.path; updateSel(); }
          menuAt(ev.currentTarget, ops.actionItems());
        },
      });
      const tr = el('tr', { 'data-path': e.path, class: e.hidden ? 'hidden-file' : null },
        el('td', { class: 'chk' }, chk),
        el('td', { class: 'name', title: e.name }, el('div', null, fileIcon(kind), el('span', { text: e.name }))),
        el('td', { class: 'dim', text: e.dir ? '' : fmtSize(e.size) }),
        el('td', { class: 'dim col-type', text: e.dir ? KIND_LABEL.folder : (extOf(e.name).toUpperCase() || KIND_LABEL.file) }),
        el('td', { class: 'dim col-date', text: fmtDate(e.mtime) }),
        el('td', { class: 'chk' }, moreBtn));
      bindItem(tr, e);
      tbody.append(tr);
    }
    list.append(el('table', { class: 'files' }, colgroup,
      el('thead', null, el('tr', null, el('th', { class: 'chk' }, allChk), th('name', 'ชื่อ'), th('size', 'ขนาด'), th('type', 'ชนิด', 'col-type'), th('mtime', 'แก้ไขล่าสุด', 'col-date'), el('th'))),
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
      const chk = el('input', {
        type: 'checkbox', class: 'gf-chk',
        onclick: ev => { ev.stopPropagation(); toggleSel(e.path, ev.target.checked); },
      });
      const item = el('div', { class: 'gf', 'data-path': e.path, title: e.name }, chk, pic, el('div', { class: 'nm', text: e.name }));
      bindItem(item, e);
      g.append(item);
    }
    list.append(g);
  }

  // ---- selection
  const coarse = matchMedia('(pointer: coarse)').matches;
  function bindItem(node, e) {
    node.addEventListener('click', ev => {
      if (ev.target.closest('input,button')) return;
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
      menu(ops.actionItems(), ev.clientX, ev.clientY);
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

  list.addEventListener('click', e => { if (e.target === list || e.target.classList.contains('grid-files')) { st.sel.clear(); updateSel(); } });
  list.addEventListener('contextmenu', e => {
    if (e.target.closest('[data-path]')) return;
    e.preventDefault();
    st.sel.clear();
    updateSel();
    const w = State.canWrite && st.writable !== false;
    menu([
      w && { label: 'สร้างโฟลเดอร์', ico: 'newfolder', run: ops.mkdir },
      w && { label: 'อัปโหลดไฟล์', ico: 'upload', run: () => ops.pick('#filePick') },
      w && { label: 'อัปโหลดทั้งโฟลเดอร์', ico: 'folderUp', run: () => ops.pick('#folderPick') },
      w && State.clipboard && { label: 'วาง', ico: 'paste', run: ops.paste },
      w && st.path && { label: 'แชร์โฟลเดอร์นี้', ico: 'share', run: () => shareDialog({ path: st.path, name: st.path.split(/[/\\]/).pop(), dir: true }, 'share') },
      w && st.path && { label: 'สร้างลิงก์ขอไฟล์เข้าโฟลเดอร์นี้', ico: 'upload', run: () => shareDialog({ path: st.path, name: st.path.split(/[/\\]/).pop(), dir: true }, 'request') },
      w ? '-' : null,
      { label: 'รีเฟรช', ico: 'refresh', run: () => load() },
    ], e.clientX, e.clientY);
  });
  list.addEventListener('keydown', e => {
    if (e.target.tagName === 'INPUT') return;
    if (e.key === 'Delete') ops.del(e.shiftKey);
    else if (e.key === 'F2') ops.rename();
    else if (e.key === 'Backspace' && st.parent) go(st.parent);
    else if (e.key === 'Enter' && st.sel.size === 1) open(selected()[0]);
    else if ((e.ctrlKey || e.metaKey) && e.key === 'a') { e.preventDefault(); st.shown.forEach(x => st.sel.add(x.path)); updateSel(); }
    else if ((e.ctrlKey || e.metaKey) && e.key === 'c' && st.sel.size) ops.clip(false);
    else if ((e.ctrlKey || e.metaKey) && e.key === 'x' && st.sel.size) ops.clip(true);
    else if ((e.ctrlKey || e.metaKey) && e.key === 'v' && State.clipboard) ops.paste();
  });

  function setView(v) { st.view = v; store.set('view', v); render(); }

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
