/* NexShare web desktop — File Station operations, context actions, and drag-and-drop upload. */
'use strict';

function createFileOps({ st, selected, open, load, updateSel, list, buildSide }) {
  const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent)
    || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

  function isFav(path) {
    return (store.get('favorites', []) || []).some(f => f.path === path);
  }

  function toggleFav(entry) {
    if (!entry) return;
    const favs = store.get('favorites', []) || [];
    const idx = favs.findIndex(f => f.path === entry.path);
    if (idx >= 0) {
      favs.splice(idx, 1);
      toast('นำออกจากรายการโปรดแล้ว');
    } else {
      favs.push({ path: entry.path, name: entry.name, dir: !!entry.dir });
      toast('ปักหมุด "' + entry.name + '" ในรายการโปรดแล้ว');
    }
    store.set('favorites', favs);
    if (buildSide) buildSide();
  }

  function actionItems() {
    const sel = selected(), one = sel.length === 1 ? sel[0] : null;
    const w = State.canWrite && (st.writable !== false || st.searching);
    const isZip = one && !one.dir && /\.zip$/i.test(one.name);
    return [
      { label: 'เปิด', ico: 'open', disabled: !one, run: () => open(one) },
      one && one.dir ? { label: isFav(one.path) ? 'เลิกปักหมุดจากรายการโปรด' : 'ปักหมุดในรายการโปรด', ico: 'star', run: () => toggleFav(one) } : null,
      { label: 'ดาวน์โหลด', ico: 'download', disabled: !sel.length, run: () => download(false) },
      { label: 'ดาวน์โหลดเป็น ZIP', ico: 'zip', disabled: !sel.length, run: () => download(true) },
      w && isZip ? { label: 'แตกไฟล์ที่นี่ (Extract ZIP)', ico: 'zip', run: () => unzipFile(one) } : null,
      w && sel.length ? { label: 'บีบอัดเป็น .zip เก็บในโฟลเดอร์นี้…', ico: 'zip', run: () => compressZip() } : null,
      w && !st.searching ? { label: 'สร้างไฟล์ข้อความใหม่…', ico: 'edit', run: () => createFile() } : null,
      w && { label: 'สร้างลิงก์แชร์…', ico: 'share', disabled: !one, run: () => shareDialog(one, 'share') },
      w && one && one.dir ? { label: 'สร้างลิงก์ขอไฟล์…', ico: 'upload', run: () => shareDialog(one, 'request') } : null,
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

  async function createFile() {
    const name = await dialog({ title: 'สร้างไฟล์ข้อความใหม่', input: 'note.md', okText: 'สร้างและแก้ไข' });
    if (!name) return;
    try {
      const created = await api('write', { path: st.path, name, create: 1 }, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain; charset=utf-8' },
        body: '',
      });
      await load();
      toast('สร้างไฟล์ "' + created.name + '" แล้ว');
      if (typeof Viewer !== 'undefined' && Viewer.openEditor) {
        Viewer.openEditor(created, '');
      }
    } catch (e) {
      toast(e.status === 409 ? 'มีไฟล์ชื่อนี้อยู่แล้ว' : 'สร้างไฟล์ไม่สำเร็จ: ' + e.message);
    }
  }

  async function unzipFile(e) {
    if (!e) return;
    toast('กำลังแตกไฟล์ "' + e.name + '"…');
    try {
      const r = await api('unzip', { path: e.path }, { method: 'POST' });
      await load();
      toast(`แตกไฟล์แล้ว ${r.extracted} รายการ ไปยัง "${r.name}"`);
    } catch (err) {
      toast('แตกไฟล์ไม่สำเร็จ: ' + err.message);
    }
  }

  async function compressZip() {
    const sel = selected();
    if (!sel.length) return;
    const defName = (sel.length === 1 ? sel[0].name.replace(/\.[^.]+$/, '') : 'Archive') + '.zip';
    const name = await dialog({ title: 'บีบอัดเป็นไฟล์ .zip เก็บในโฟลเดอร์นี้', input: defName, okText: 'บีบอัด' });
    if (!name) return;
    toast('กำลังบีบอัดไฟล์…');
    try {
      const r = await api('zip/save', null, {
        method: 'POST',
        body: JSON.stringify({ dst: st.path, name, p: sel.map(e => e.path) }),
      });
      await load();
      toast(`สร้างไฟล์ ZIP "${r.name}" (${fmtSize(r.size)}) แล้ว`);
    } catch (err) {
      toast('บีบอัดไม่สำเร็จ: ' + err.message);
    }
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

  function download(asZip) {
    const sel = selected();
    if (!sel.length) { toast('เลือกรายการที่ต้องการดาวน์โหลดก่อน'); return; }
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
      if (r.trash && r.deleted > 0) {
        toastAction(`ย้ายไปถังขยะแล้ว ${r.deleted} รายการ`, 'เลิกทำ', async () => {
          try {
            const tl = await api('trash/list');
            const items = (tl.items || []).slice(0, r.deleted);
            if (items.length) {
              await api('trash/restore', { id: items.map(x => x.id) }, { method: 'POST' });
              toast('กู้คืนไฟล์เรียบร้อย');
              load();
            }
          } catch (err) { toast('กู้คืนไม่สำเร็จ: ' + err.message); }
        });
      } else {
        toast(`ลบแล้ว ${r.deleted} รายการ`);
      }
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
    let targetSel = sel;
    if (sel === '#folderPick' && isIOS) {
      toast('Safari บน iPhone/iPad ไม่รองรับการเลือกทั้งโฟลเดอร์ — เปิดเลือกหลายไฟล์แทน');
      targetSel = '#filePick';
    }
    const input = $(targetSel);
    if (!input) return;
    input.value = '';
    input.onchange = () => {
      if (!input.files || !input.files.length) return;
      Uploader.add([...input.files].map(f => ({ file: f, rel: (f.webkitRelativePath || '').split('/').slice(0, -1).join('/') })), st.path);
    };
    input.click();
  }

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

  return { actionItems, download, copyLink, mkdir, createFile, unzipFile, compressZip, toggleFav, rename, del, clip, paste, pick };
}

