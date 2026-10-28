/* NexShare web desktop — root edition: Terminal, Screen Mirror, Package Center. Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- Terminal (root)

function Terminal(body, win) {
  const out = el('div', { class: 'term-out' });
  const ps = el('span', { class: 'ps', text: '/data/local/tmp' });
  const input = el('input', { spellcheck: 'false', autocapitalize: 'off', autocomplete: 'off', placeholder: 'พิมพ์คำสั่งแล้ว Enter' });
  const st = { cwd: '/data/local/tmp', history: store.get('term.hist', []), hi: -1, busy: false };
  const quick = ['ls -la', 'busybox | head -40', 'id', 'getprop ro.product.model', 'df -h', 'free -m', 'ps -A | head', 'pm list packages -3'];
  const bar = el('div', { class: 'term-bar' }, ...quick.map(c => el('button', { class: 'term-chip', text: c, onclick: () => run(c) })));
  const inBar = el('div', { class: 'term-in' }, ps, input);
  body.append(el('div', { class: 'term' }, bar, out, inBar));

  function line(text, cls) {
    out.append(el('div', { class: cls || null, text }));
    out.scrollTop = out.scrollHeight;
  }
  function write(text) {
    if (!text) return;
    const pre = el('div');
    pre.textContent = text.replace(/\n$/, '');
    out.append(pre);
    out.scrollTop = out.scrollHeight;
  }

  async function run(cmd) {
    cmd = cmd.trim();
    if (!cmd || st.busy) return;
    if (cmd === 'clear') { out.replaceChildren(); input.value = ''; return; }
    st.history = st.history.filter(h => h !== cmd); st.history.push(cmd);
    if (st.history.length > 100) st.history.shift();
    store.set('term.hist', st.history);
    st.hi = -1;
    line(cmd, 'cmd');
    input.value = '';
    st.busy = true;
    input.disabled = true;
    try {
      const r = await api('root/exec', null, { method: 'POST', body: JSON.stringify({ cmd, cwd: st.cwd }) });
      write(r.output);
      if (r.cwd) { st.cwd = r.cwd; ps.textContent = r.cwd; }
    } catch (e) {
      line(e.message, 'err');
      if (e.status === 401) Auth.show();
    }
    st.busy = false;
    input.disabled = false;
    input.focus();
  }

  input.addEventListener('keydown', e => {
    if (e.key === 'Enter') run(input.value);
    else if (e.key === 'ArrowUp') { if (st.hi < st.history.length - 1) { st.hi++; input.value = st.history[st.history.length - 1 - st.hi]; e.preventDefault(); } }
    else if (e.key === 'ArrowDown') { if (st.hi > 0) { st.hi--; input.value = st.history[st.history.length - 1 - st.hi]; } else { st.hi = -1; input.value = ''; } }
    else if (e.key === 'c' && e.ctrlKey) { input.value = ''; line('^C', 'err'); }
  });
  body.addEventListener('click', e => { if (!e.target.closest('button') && !window.getSelection().toString()) input.focus(); });

  (async () => {
    try {
      const s = await api('root/status');
      if (!s.root) {
        out.append(el('div', { class: 'notice', text: s.flavor !== 'root'
          ? 'Terminal ใช้ได้เฉพาะ NexShare รุ่น Root'
          : 'ยังไม่ได้สิทธิ์ root — เปิดแอป NexShare บนมือถือแล้วกด "ขอสิทธิ์ root" (ต้องมี Magisk/SuperSU)' }));
        input.disabled = true;
        return;
      }
      line('NexShare root shell'
        + (s.busybox ? ' · มี busybox (~300 คำสั่ง: ls, grep, sed, awk, tar, vi, wget, top…)' : '')
        + (s.termux ? ' · พบ Termux (ใช้แพ็กเกจของ Termux ได้ด้วย)' : ''));
      run('id && getprop ro.product.model');
      input.focus();
    } catch (e) { out.append(el('div', { class: 'notice', text: e.message })); }
  })();

  return { handle() { input.focus(); } };
}

// ---------------------------------------------------------------- Screen mirror (root)

/** Splits an H.264 Annex B byte stream into NAL units (keeps a partial tail between chunks). */
class AnnexBParser {
  constructor() { this.buf = new Uint8Array(0); }
  push(chunk) {
    const b = new Uint8Array(this.buf.length + chunk.length);
    b.set(this.buf);
    b.set(chunk, this.buf.length);
    const starts = [];
    for (let i = 0; i + 3 < b.length; i++) {
      if (b[i] === 0 && b[i + 1] === 0 && (b[i + 2] === 1 || (b[i + 2] === 0 && b[i + 3] === 1))) {
        starts.push(i);
        i += b[i + 2] === 1 ? 2 : 3;
      }
    }
    const nals = [];
    for (let k = 0; k + 1 < starts.length; k++) nals.push(b.subarray(starts[k], starts[k + 1]));
    this.buf = starts.length ? b.slice(starts[starts.length - 1]) : b;
    return nals;
  }
}
const nalType = nal => nal[nal[2] === 1 ? 3 : 4] & 0x1f;
const nalBody = nal => nal.subarray(nal[2] === 1 ? 3 : 4);

function ScreenMirror(body, win) {
  const canH264 = 'VideoDecoder' in window && window.isSecureContext;
  const img = el('img', { alt: 'หน้าจอมือถือ', draggable: false });
  const canvas = el('canvas', { class: 'scr-canvas' });
  const stage = el('div', { class: 'scr-stage' }, el('div', { class: 'scr-msg', text: 'กำลังเชื่อมต่อ…' }));
  const fps = el('span', { class: 'fps', text: '' });
  const liveBtn = el('button', { class: 'btn', onclick: () => toggle() }, icon('play'), el('span', { text: 'เริ่มสด' }));
  const shotBtn = el('button', { class: 'btn', onclick: () => grab() }, icon('refresh'), el('span', { class: 'hide-sm', text: 'ถ่ายใหม่' }));
  const saveBtn = el('button', { class: 'btn', onclick: () => save() }, icon('download'), el('span', { class: 'hide-sm', text: 'บันทึก' }));
  const selStyle = { height: '30px', borderRadius: '7px', background: '#1b2330', color: '#d6dee8', border: '1px solid #2a3440' };
  const modeSel = el('select', { title: 'โหมด', style: selStyle, onchange: () => { st.mode = modeSel.value; store.set('scr.mode', st.mode); if (st.live) { stop(); start(); } } },
    canH264 ? el('option', { value: 'video', text: 'วิดีโอ H.264 (ลื่น)' }) : null,
    el('option', { value: 'shots', text: 'ภาพนิ่งต่อเนื่อง' }));
  const rateSel = el('select', { style: selStyle, onchange: () => { st.interval = +rateSel.value; if (st.live && st.mode === 'shots') { stop(); start(); } } },
    ...[[700, 'ปกติ ~1.4 fps'], [400, 'เร็ว ~2.5 fps'], [1500, 'ประหยัด'], [250, 'ลื่นสุด (กินแบต)']].map(([v, l]) => el('option', { value: v, text: l })));
  const tapChk = el('label', { style: { color: '#c4cfda', fontSize: '12px', display: 'flex', gap: '4px', alignItems: 'center' } },
    el('input', { type: 'checkbox', checked: true, onchange: e => st.control = e.target.checked }), 'สั่งงาน (แตะ/ปัด)');
  const bar = el('div', { class: 'scr-bar' }, liveBtn, shotBtn, modeSel, rateSel, tapChk, el('span', { class: 'grow' }), fps, saveBtn);
  const key = (label, code) => el('button', { title: label, onclick: () => sendKey(code) }, label === 'Back' ? icon('back') : label === 'Home' ? el('span', { text: '⬤', style: { fontSize: '12px' } }) : label === 'Recents' ? icon('max') : el('span', { text: label }));
  const nav = el('div', { class: 'scr-nav' }, key('Back', 4), key('Home', 3), key('Recents', 187),
    el('button', { title: 'Power', onclick: () => sendKey(26) }, el('span', { text: '⏻' })),
    el('button', { title: 'Volume +', onclick: () => sendKey(24) }, el('span', { text: '🔊' })),
    el('button', { title: 'Volume −', onclick: () => sendKey(25) }, el('span', { text: '🔈' })));
  body.append(el('div', { class: 'scr' }, bar, stage, nav));
  if (!canH264) shotBtn.title = 'โหมดวิดีโอต้องใช้ HTTPS และเบราว์เซอร์ที่รองรับ WebCodecs';

  const st = { live: false, mode: canH264 ? store.get('scr.mode', 'video') : 'shots', interval: 700, control: true, busy: false, lastShot: null,
    natW: 0, natH: 0, scrW: 0, scrH: 0, lastT: 0, timer: null, ws: null, decoder: null, frames: 0, fpsT: 0 };
  if (!canH264) st.mode = 'shots';
  modeSel.value = st.mode;
  rateSel.classList.toggle('hidden', st.mode !== 'shots');

  // ---- still screenshots (works everywhere)
  async function grab() {
    if (st.busy) return;
    st.busy = true;
    try {
      const r = await rawFetch('/api/root/screen?t=' + Date.now());
      if (!r.ok) {
        const j = await r.json().catch(() => ({}));
        throw new Error(j.error || r.status);
      }
      const blob = await r.blob();
      const url = URL.createObjectURL(blob);
      if (st.lastShot) URL.revokeObjectURL(st.lastShot);
      st.lastShot = url;
      if (st.mode === 'shots' && !img.parentElement) stage.replaceChildren(img);
      if (st.mode === 'shots') img.src = url;
      const now = performance.now();
      if (st.mode === 'shots' && st.lastT) fps.textContent = (1000 / (now - st.lastT)).toFixed(1) + ' fps';
      st.lastT = now;
    } catch (e) {
      if (e.cancelled) stop();
      if (!st.live) stage.replaceChildren(el('div', { class: 'scr-msg', text: 'จับภาพไม่ได้: ' + e.message }));
    }
    st.busy = false;
  }

  // ---- H.264 over WebSocket, decoded with WebCodecs
  async function startVideo() {
    try {
      const r = await rawFetch('/api/root/stream'); // runs the sudo prompt if needed
      if (!r.ok) throw new Error((await r.json().catch(() => ({}))).error || r.status);
    } catch (e) { stop(); stage.replaceChildren(el('div', { class: 'scr-msg', text: e.message })); return; }
    const parser = new AnnexBParser();
    let sps = null, pps = null, configured = false, codec = null;
    const ctx2d = canvas.getContext('2d');
    const decoder = new VideoDecoder({
      output: frame => {
        if (canvas.width !== frame.displayWidth) { canvas.width = frame.displayWidth; canvas.height = frame.displayHeight; }
        ctx2d.drawImage(frame, 0, 0);
        frame.close();
        st.frames++;
        const now = performance.now();
        if (now - st.fpsT > 1000) { fps.textContent = (st.frames * 1000 / (now - st.fpsT)).toFixed(0) + ' fps'; st.frames = 0; st.fpsT = now; }
      },
      error: e => { stage.replaceChildren(el('div', { class: 'scr-msg', text: 'ถอดรหัสวิดีโอไม่ได้: ' + e.message })); },
    });
    st.decoder = decoder;
    const ws = new WebSocket((location.protocol === 'https:' ? 'wss:' : 'ws:') + '//' + location.host + '/api/root/stream?max=1280&kbps=4000');
    ws.binaryType = 'arraybuffer';
    st.ws = ws;
    stage.replaceChildren(canvas);
    const concat = parts => { const n = parts.reduce((a, p) => a + p.length, 0), out = new Uint8Array(n); let o = 0; for (const p of parts) { out.set(p, o); o += p.length; } return out; };
    ws.onmessage = ev => {
      if (typeof ev.data === 'string') {
        const m = JSON.parse(ev.data);
        if (m.type === 'config') { st.natW = m.width; st.natH = m.height; st.scrW = m.screenWidth; st.scrH = m.screenHeight; }
        else if (m.type === 'restart') { configured = false; }
        else if (m.type === 'error') {
          stop();
          if (m.message === 'unsupported') {
            toast('มือถือเครื่องนี้สตรีมวิดีโอไม่ได้ เปลี่ยนเป็นภาพนิ่งต่อเนื่อง');
            st.mode = 'shots'; modeSel.value = 'shots'; rateSel.classList.remove('hidden');
            start();
          } else stage.replaceChildren(el('div', { class: 'scr-msg', text: m.message }));
        }
        return;
      }
      for (const nal of parser.push(new Uint8Array(ev.data))) {
        const t = nalType(nal);
        if (t === 7) { sps = nal; const b = nalBody(nal); codec = 'avc1.' + [b[1], b[2], b[3]].map(x => x.toString(16).padStart(2, '0')).join(''); continue; }
        if (t === 8) { pps = nal; continue; }
        if (t !== 1 && t !== 5) continue;
        if (t === 5 && sps && pps) {
          if (!configured) {
            try { decoder.configure({ codec, optimizeForLatency: true }); configured = true; } catch (e) { stage.replaceChildren(el('div', { class: 'scr-msg', text: 'เบราว์เซอร์ถอดรหัส ' + codec + ' ไม่ได้' })); ws.close(); return; }
          }
          decoder.decode(new EncodedVideoChunk({ type: 'key', timestamp: performance.now() * 1000, data: concat([sps, pps, nal]) }));
        } else if (configured && t === 1) {
          if (decoder.decodeQueueSize > 30) continue; // falling behind: drop until the next key frame
          decoder.decode(new EncodedVideoChunk({ type: 'delta', timestamp: performance.now() * 1000, data: nal }));
        }
      }
    };
    ws.onclose = () => { if (st.ws === ws && st.live) { stop(); stage.append(el('div', { class: 'scr-msg overlay', text: 'การสตรีมหยุดแล้ว — กดเริ่มสดอีกครั้ง' })); } };
  }

  function start() {
    st.live = true;
    liveBtn.replaceChildren(icon('pause'), el('span', { text: 'หยุด' }));
    rateSel.classList.toggle('hidden', st.mode !== 'shots');
    if (st.mode === 'video') { st.fpsT = performance.now(); st.frames = 0; startVideo(); return; }
    const loop = async () => { if (!st.live) return; await grab(); if (st.live) st.timer = setTimeout(loop, st.interval); };
    loop();
  }
  function stop() {
    st.live = false;
    clearTimeout(st.timer);
    if (st.ws) { const ws = st.ws; st.ws = null; try { ws.close(); } catch { /* closed */ } }
    if (st.decoder) { try { st.decoder.close(); } catch { /* closed */ } st.decoder = null; }
    liveBtn.replaceChildren(icon('play'), el('span', { text: 'เริ่มสด' }));
    fps.textContent = '';
  }
  function toggle() { st.live ? stop() : start(); }

  // ---- input: taps and swipes map from the shown image to real screen pixels
  img.addEventListener('load', () => { st.natW = img.naturalWidth; st.natH = img.naturalHeight; st.scrW = st.natW; st.scrH = st.natH; });
  function mapCoord(ev, target) {
    const r = target.getBoundingClientRect();
    const sx = (st.scrW || st.natW) / r.width, sy = (st.scrH || st.natH) / r.height;
    return { x: Math.round((ev.clientX - r.left) * sx), y: Math.round((ev.clientY - r.top) * sy) };
  }
  let down = null;
  for (const target of [img, canvas]) {
    target.addEventListener('pointerdown', e => { if (st.control) { down = { ...mapCoord(e, target), t: Date.now() }; e.preventDefault(); } });
    target.addEventListener('pointerup', async e => {
      if (!st.control || !down) return;
      const up = mapCoord(e, target);
      const dist = Math.hypot(up.x - down.x, up.y - down.y);
      try {
        if (dist < 12 && Date.now() - down.t < 400) await sendInput({ type: 'tap', x: up.x, y: up.y });
        else await sendInput({ type: 'swipe', x1: down.x, y1: down.y, x2: up.x, y2: up.y, ms: Math.min(800, Date.now() - down.t) });
        if (st.mode === 'shots') setTimeout(grab, 120);
      } catch (err) { toast(err.message); }
      down = null;
    });
  }
  async function sendInput(b) {
    if (st.ws && st.ws.readyState === 1) { st.ws.send(JSON.stringify(b)); return; } // same socket, lower latency
    await api('root/input', null, { method: 'POST', body: JSON.stringify(b) });
  }
  async function sendKey(code) { try { await sendInput({ type: 'key', key: String(code) }); if (st.mode === 'shots') setTimeout(grab, 150); } catch (e) { toast(e.message); } }
  async function save() {
    if (st.mode === 'video' && canvas.width) {
      canvas.toBlob(b => { const u = URL.createObjectURL(b); const a = el('a', { href: u, download: 'screen-' + Date.now() + '.png' }); document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(u), 5000); });
      return;
    }
    if (!st.lastShot) return;
    const a = el('a', { href: st.lastShot, download: 'screen-' + Date.now() + '.png' });
    document.body.append(a); a.click(); a.remove();
  }

  (async () => {
    try {
      const s = await api('root/status');
      if (!s.root) {
        stage.replaceChildren(el('div', { class: 'scr-msg', text: s.flavor !== 'root'
          ? 'ดูหน้าจอได้เฉพาะ NexShare รุ่น Root' : 'ยังไม่ได้สิทธิ์ root — ขอสิทธิ์ในแอปบนมือถือก่อน' }));
        [liveBtn, shotBtn, saveBtn].forEach(b => b.disabled = true);
        return;
      }
      if (st.mode === 'shots') await grab();
      start();
    } catch (e) { stage.replaceChildren(el('div', { class: 'scr-msg', text: e.message })); }
  })();

  return { destroy() { stop(); if (st.lastShot) URL.revokeObjectURL(st.lastShot); } };
}

// ---------------------------------------------------------------- Package Center

function PackageCenter(body, win) {
  const st = { apps: [], root: false, flavor: 'normal', q: '', showSystem: false };
  const search = el('input', { placeholder: 'ค้นหาแอป', oninput: e => { st.q = e.target.value.toLowerCase(); render(); } });
  const sysChk = el('label', { style: { display: 'flex', gap: '5px', alignItems: 'center', fontSize: '12.5px', color: 'var(--text-2)', whiteSpace: 'nowrap' } },
    el('input', { type: 'checkbox', onchange: e => { st.showSystem = e.target.checked; load(); } }), 'รวมแอประบบ');
  const installBtn = el('button', { class: 'btn primary', onclick: () => pick() }, icon('upload'), el('span', { text: 'ติดตั้ง APK' }));
  const bar = el('div', { class: 'pc-bar' }, el('div', { class: 'pc-search' }, icon('search'), search), sysChk, installBtn);
  const drop = el('div', { class: 'pc-drop', text: 'ลากไฟล์ .apk มาวางที่นี่เพื่อติดตั้ง' });
  const list = el('div', { class: 'pc-list' }, drop);
  const apkPick = el('input', { type: 'file', accept: '.apk', class: 'hidden', onchange: () => { if (apkPick.files[0]) installApk(apkPick.files[0]); apkPick.value = ''; } });
  body.append(el('div', { class: 'pc' }, bar, list), apkPick);

  function render() {
    const rows = st.apps.filter(a => !st.q || a.label.toLowerCase().includes(st.q) || a.pkg.includes(st.q));
    win.setTitle('Package Center — ' + st.apps.length + ' แอป');
    list.replaceChildren(drop, ...rows.map(a => {
      const ico = el('img', { loading: 'lazy', src: '/api/root/appicon' + qs({ pkg: a.pkg }), alt: '' });
      ico.onerror = () => ico.replaceWith(el('div', { class: 'ph' }, fileIcon('apk')));
      return el('div', { class: 'pc-item' }, ico,
        el('div', { class: 'meta' },
          el('div', { class: 'nm' }, a.label, a.system ? el('span', { class: 'tag sys', text: ' ระบบ', style: { marginLeft: '6px' } }) : '', !a.enabled ? el('span', { class: 'tag off', text: ' ปิดอยู่', style: { marginLeft: '6px' } }) : ''),
          el('div', { class: 'sub', text: a.pkg + (a.version ? ' · v' + a.version : '') + (a.size ? ' · ' + fmtSize(a.size) : '') })),
        el('button', { class: 'btn', onclick: e => menuAt(e.currentTarget, appMenu(a)) }, 'จัดการ', icon('chev', 'chev')));
    }));
  }
  function appMenu(a) {
    const act = async (action, confirm) => {
      if (confirm && !await dialog({ title: confirm, okText: 'ตกลง', danger: true })) return;
      try { const r = await api('root/app', { pkg: a.pkg, action }, { method: 'POST' }); toast('สำเร็จ'); if (['uninstall', 'disable', 'enable'].includes(action)) load(); }
      catch (e) { toast('ไม่สำเร็จ: ' + e.message); }
    };
    return [
      { label: 'เปิดแอป', ico: 'open', run: () => act('open') },
      st.root ? { label: 'บังคับหยุด', ico: 'min', run: () => act('force-stop') } : null,
      st.root ? { label: 'ล้างข้อมูล', ico: 'trash', run: () => act('clear', 'ล้างข้อมูลของ ' + a.label + '?') } : null,
      st.root && a.enabled && a.system ? { label: 'ปิดการใช้งาน', run: () => act('disable', 'ปิด ' + a.label + '?') } : null,
      st.root && !a.enabled ? { label: 'เปิดการใช้งาน', run: () => act('enable') } : null,
      st.root && !a.system ? { label: 'ถอนการติดตั้ง', ico: 'trash', danger: true, run: () => act('uninstall', 'ถอน ' + a.label + '?') } : null,
    ].filter(Boolean);
  }
  function pick() { apkPick.click(); }
  async function installApk(file) {
    toast('กำลังอัปโหลดและติดตั้ง ' + file.name + '…', 4000);
    try {
      const r = await rawFetch('/api/root/install', { method: 'POST', body: file });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || r.status);
      if (j.mode === 'root') { toast('ติดตั้งสำเร็จ'); load(); }
      else toast('อัปโหลดแล้ว — ยืนยันการติดตั้งบนหน้าจอมือถือ', 5000);
    } catch (e) { toast('ติดตั้งไม่สำเร็จ: ' + e.message, 5000); }
  }
  let dragN = 0;
  list.addEventListener('dragenter', e => { if ([...e.dataTransfer.types].includes('Files')) { dragN++; drop.classList.add('drag'); } });
  list.addEventListener('dragleave', () => { if (--dragN <= 0) { dragN = 0; drop.classList.remove('drag'); } });
  list.addEventListener('dragover', e => e.preventDefault());
  list.addEventListener('drop', e => { e.preventDefault(); dragN = 0; drop.classList.remove('drag'); const f = [...e.dataTransfer.files].find(f => /\.apk$/i.test(f.name)); if (f) installApk(f); else toast('ลากไฟล์ .apk เท่านั้น'); });

  async function load() {
    try {
      const r = await api('root/apps', { system: st.showSystem ? 1 : 0 });
      st.apps = r.apps;
      st.root = r.root;
      drop.textContent = st.root ? 'ลากไฟล์ .apk มาวางที่นี่เพื่อติดตั้งแบบเงียบ (root)' : 'ลากไฟล์ .apk มาวางเพื่อติดตั้ง (ยืนยันบนมือถือ)';
      render();
    } catch (e) { list.replaceChildren(el('div', { class: 'notice', text: 'โหลดรายการแอปไม่ได้: ' + e.message })); }
  }
  (async () => { try { st.flavor = (await api('root/status')).flavor; } catch {} load(); })();
  return { handle(arg) { if (arg && arg.install) pick(); } };
}
