/* NexShare web desktop — helpers, icons, state, window manager, taskbar & desktop. Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- helpers

const $ = (sel, root = document) => root.querySelector(sel);

function el(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  if (attrs) for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'html') e.innerHTML = v;
    else if (k === 'text') e.textContent = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k === 'style' && typeof v === 'object') Object.assign(e.style, v);
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const k of kids.flat()) if (k != null && k !== false) e.append(k instanceof Node ? k : document.createTextNode(String(k)));
  return e;
}

// Like node.replaceChildren but drops null/false/undefined args (which would otherwise
// be inserted as the literal text "null"/"false").
function fill(node, ...kids) {
  node.replaceChildren(...kids.flat().filter(k => k != null && k !== false));
}

const store = {
  get(k, d) { try { const v = localStorage.getItem('ns.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('ns.' + k, JSON.stringify(v)); } catch { /* private mode */ } },
};

function qs(params) {
  const parts = [];
  for (const [k, v] of Object.entries(params || {})) {
    if (v == null) continue;
    for (const one of Array.isArray(v) ? v : [v]) parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(one));
  }
  return parts.length ? '?' + parts.join('&') : '';
}

const API_ERRORS = {
  read_only: 'บัญชีนี้ดูได้อย่างเดียว แก้ไขไฟล์ไม่ได้',
  admin_only: 'เฉพาะผู้ดูแลระบบเท่านั้น',
  forbidden: 'ไม่มีสิทธิ์เข้าถึง',
  'not allowed for this user': 'บัญชีนี้ไม่มีสิทธิ์เข้าถึงโฟลเดอร์นี้',
  'cross-origin request blocked': 'คำขอถูกบล็อก (ต่างโดเมน)',
  locked: 'ใส่ผิดหลายครั้ง กรุณารอสักครู่แล้วลองใหม่',
  quota_exceeded: 'พื้นที่เกินโควตาที่กำหนดไว้สำหรับบัญชีนี้',
};

const errorHistory = [];

/**
 * Displays an on-screen error banner so any runtime or action failure (especially on mobile/iPhone)
 * is visible and can be screenshotted or copied immediately instead of failing silently.
 */
function reportError(context, err) {
  if (err && (err.cancelled || err.status === 401)) return;
  const msg = err instanceof Error ? (err.message || String(err)) : String(err || 'Unknown error');
  const stack = err && err.stack ? String(err.stack).split('\n').slice(0, 4).join('\n') : '';
  const detail = (context ? `[${context}] ` : '') + msg + (stack && !stack.includes(msg) ? '\n' + stack : '');
  errorHistory.push({ ts: Date.now(), context, msg, detail });
  if (!document.body) return;
  let banner = $('#errorBanner');
  if (!banner) {
    banner = el('div', { id: 'errorBanner', class: 'error-banner' });
    document.body.append(banner);
  }
  const textEl = el('pre', { class: 'error-banner-text', text: detail });
  fill(banner,
    el('div', { class: 'error-banner-head' },
      el('strong', { text: '⚠ เกิดข้อผิดพลาดบนหน้าเว็บ' }),
      el('div', { class: 'error-banner-actions' },
        el('button', {
          class: 'btn sm', text: 'คัดลอกข้อผิดพลาด',
          onclick: async () => { await copyText(detail); toast('คัดลอกข้อความผิดพลาดแล้ว'); },
        }),
        el('button', { class: 'btn sm', text: 'ปิด', onclick: () => banner.remove() })
      )
    ),
    textEl
  );
}

window.addEventListener('error', ev => {
  const loc = ev.filename ? `${ev.filename.split('/').pop()}:${ev.lineno || 0}` : 'window.onerror';
  reportError(loc, ev.error || ev.message || 'Script error');
});

window.addEventListener('unhandledrejection', ev => {
  const reason = ev.reason;
  if (reason && (reason.cancelled || reason.status === 401)) return;
  reportError('Promise', reason || 'Unhandled rejection');
});

/**
 * Calls the JSON API. A 403 "sudo_required" (root tools, security settings) asks the user to
 * confirm their PIN/password and retries once; 401 shows the login screen.
 */
async function api(path, params, opt = {}) {
  for (let attempt = 0; ; attempt++) {
    const r = await fetch('/api/' + path + qs(params), {
      method: opt.method || 'GET',
      body: opt.body,
      headers: opt.body && typeof opt.body === 'string' ? { 'Content-Type': 'application/json' } : undefined,
      credentials: 'same-origin',
    });
    let j = {};
    try { j = await r.json(); } catch { /* empty body */ }
    if (r.status === 401 && path !== 'login' && path !== 'sudo') { Auth.show(); throw Object.assign(new Error('unauthorized'), { status: 401 }); }
    if (r.status === 403 && j.error === 'sudo_required' && attempt === 0) { await Sudo.prompt(); continue; }
    if (!r.ok) throw Object.assign(new Error(API_ERRORS[j.error] || j.error || ('HTTP ' + r.status)), { status: r.status, code: j.error });
    return j;
  }
}

/** fetch() for non-JSON endpoints (screenshots, uploads) with the same sudo/login handling. */
async function rawFetch(url, opt = {}) {
  for (let attempt = 0; ; attempt++) {
    const r = await fetch(url, { credentials: 'same-origin', ...opt });
    if (r.status === 401) { Auth.show(); throw Object.assign(new Error('unauthorized'), { status: 401 }); }
    if (r.status === 403 && attempt === 0) {
      const j = await r.clone().json().catch(() => ({}));
      if (j.error === 'sudo_required') { await Sudo.prompt(); continue; }
    }
    return r;
  }
}

/**
 * Small form dialog. fields: [{ name, label, type: text|password|number|select|checkbox|textarea,
 * value, placeholder, options: [[value, label]], hint }]. Resolves to {name: value} or null.
 */
function formDialog({ title, message, fields, okText = 'ตกลง', danger, extra }) {
  return new Promise(resolve => {
    const inputs = {};
    const rows = fields.map(f => {
      let input;
      if (f.type === 'select') input = el('select', null, ...f.options.map(([v, l]) => el('option', { value: v, text: l, selected: String(v) === String(f.value) })));
      else if (f.type === 'checkbox') input = el('input', { type: 'checkbox', checked: !!f.value });
      else if (f.type === 'textarea') input = el('textarea', { rows: f.rows || 3, placeholder: f.placeholder || '', spellcheck: 'false' }, f.value || '');
      else input = el('input', { type: f.type || 'text', value: f.value ?? '', placeholder: f.placeholder || '', autocomplete: f.autocomplete || 'off', inputmode: f.inputmode, spellcheck: 'false' });
      inputs[f.name] = input;
      if (f.type === 'checkbox') return el('label', { class: 'f-row f-check' }, input, el('span', { text: f.label }), f.hint ? el('small', { text: f.hint }) : null);
      return el('label', { class: 'f-row' }, el('span', { text: f.label }), input, f.hint ? el('small', { text: f.hint }) : null);
    });
    const err = el('div', { class: 'f-err' });
    const values = () => Object.fromEntries(Object.entries(inputs).map(([k, i]) => [k, i.type === 'checkbox' ? i.checked : i.value]));
    const close = v => { wrap.remove(); resolve(v); };
    const ok = el('button', { class: 'btn ' + (danger ? 'danger' : 'primary'), text: okText, onclick: () => close(values()) });
    const wrap = el('div', { id: 'modal', onmousedown: e => { if (e.target === wrap) close(null); } },
      el('div', { class: 'dialog form' },
        el('div', { class: 'd-head', text: title }),
        el('div', { class: 'd-body' }, message ? el('div', { class: 'f-msg', text: message }) : null, extra || null, ...rows, err),
        el('div', { class: 'd-foot' }, el('button', { class: 'btn', text: 'ยกเลิก', onclick: () => close(null) }), ok)));
    wrap.addEventListener('keydown', e => {
      if (e.key === 'Enter' && e.target.tagName !== 'TEXTAREA') { e.preventDefault(); ok.click(); }
      if (e.key === 'Escape') close(null);
    });
    document.body.append(wrap);
    const first = Object.values(inputs).find(i => i.type !== 'checkbox');
    (first || ok).focus();
  });
}

/** Re-authentication for dangerous actions (root tools, security settings). */
const Sudo = {
  pending: null,
  prompt() {
    if (this.pending) return this.pending;
    this.pending = (async () => {
      try {
        for (let msg = 'ยืนยันตัวตนอีกครั้งก่อนใช้คำสั่งนี้ (มีผล 10 นาที)'; ;) {
          const fields = [{ name: 'secret', label: Auth.adminPassword || State.user !== 'admin' ? 'รหัสผ่าน' : 'PIN', type: 'password', autocomplete: 'current-password' }];
          if (Auth.otp && State.user === 'admin') fields.push({ name: 'otp', label: 'รหัส 6 หลักจากแอปยืนยันตัวตน', inputmode: 'numeric', autocomplete: 'one-time-code' });
          const v = await formDialog({ title: 'ยืนยันตัวตน', message: msg, fields, okText: 'ยืนยัน' });
          if (!v) throw Object.assign(new Error('ยกเลิกแล้ว'), { cancelled: true });
          try {
            await api('sudo', null, { method: 'POST', body: JSON.stringify({ password: v.secret, otp: v.otp || '' }) });
            State.sudo = true;
            return;
          } catch (e) {
            msg = e.status === 429 ? 'ใส่ผิดหลายครั้ง กรุณารอสักครู่' : e.message === 'otp_required' ? 'ต้องใส่รหัสจากแอปยืนยันตัวตนด้วย' : 'รหัสไม่ถูกต้อง ลองอีกครั้ง';
            if (e.message === 'otp_required') Auth.otp = true;
          }
        }
      } finally { this.pending = null; }
    })();
    return this.pending;
  },
};

async function copyText(text) {
  try { await navigator.clipboard.writeText(text); return true; } catch { /* insecure context */ }
  const t = el('textarea', { style: { position: 'fixed', top: '0', left: '0', width: '1px', height: '1px', opacity: '0' } }, text);
  document.body.append(t);
  t.focus();
  t.select();
  try { t.setSelectionRange(0, text.length); } catch { /* ignore */ }
  let ok = false;
  try { ok = document.execCommand('copy'); } catch { /* ignore */ }
  t.remove();
  return ok;
}

const fileUrl = (p, dl) => '/api/file' + qs({ path: p, dl: dl ? 1 : null });
const thumbUrl = (p, s = 240) => '/api/thumb' + qs({ path: p, s });

function fmtSize(n) {
  if (n == null || n < 0) return '-';
  if (n < 1024) return n + ' B';
  const u = ['KB', 'MB', 'GB', 'TB'];
  let i = -1;
  do { n /= 1024; i++; } while (n >= 1024 && i < u.length - 1);
  return (n >= 100 ? n.toFixed(0) : n.toFixed(1)) + ' ' + u[i];
}
const fmtRate = n => fmtSize(Math.max(0, n)).replace(' B', ' B') + '/s';
function fmtDate(ms) {
  const d = new Date(ms);
  const p = n => String(n).padStart(2, '0');
  return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
function fmtUptime(ms) {
  let s = Math.floor(ms / 1000);
  const d = Math.floor(s / 86400); s %= 86400;
  const h = Math.floor(s / 3600); s %= 3600;
  const m = Math.floor(s / 60);
  return (d ? d + ' วัน ' : '') + h + ' ชม. ' + m + ' นาที';
}
const extOf = n => { const i = n.lastIndexOf('.'); return i > 0 ? n.slice(i + 1).toLowerCase() : ''; };

const KIND = {};
for (const [k, list] of Object.entries({
  image: 'jpg jpeg png gif webp bmp heic heif svg',
  video: 'mp4 m4v webm mkv 3gp mov avi',
  audio: 'mp3 m4a aac ogg opus wav flac amr',
  pdf: 'pdf',
  text: 'txt log md markdown csv tsv json xml html htm css js mjs ts tsx jsx py java c cpp h cs go rs rb php sh ps1 sql ini conf yml yaml toml gradle kt swift dart srt vtt',
  archive: 'zip rar 7z tar gz bz2 xz',
  apk: 'apk xapk apks',
  doc: 'doc docx odt rtf',
  sheet: 'xls xlsx ods',
  slide: 'ppt pptx odp',
})) for (const e of list.split(' ')) KIND[e] = k;
const kindOf = entry => entry.dir ? 'folder' : (KIND[extOf(entry.name)] || 'file');
const KIND_LABEL = { folder: 'โฟลเดอร์', image: 'รูปภาพ', video: 'วิดีโอ', audio: 'เสียง', pdf: 'PDF', text: 'ข้อความ', archive: 'ไฟล์บีบอัด', apk: 'แอป Android', doc: 'เอกสาร', sheet: 'สเปรดชีต', slide: 'งานนำเสนอ', file: 'ไฟล์' };

function toast(msg, ms = 2600) {
  const t = el('div', { class: 'toast', text: msg });
  $('#toasts').append(t);
  setTimeout(() => t.remove(), ms);
}

function dialog({ title, message, input, okText = 'ตกลง', danger, cancel = true, extra }) {
  return new Promise(resolve => {
    const field = input != null ? el('input', { value: input, spellcheck: 'false' }) : null;
    const close = v => { wrap.remove(); resolve(v); };
    const ok = el('button', { class: 'btn ' + (danger ? 'danger' : 'primary'), text: okText, onclick: () => close(field ? field.value : true) });
    const wrap = el('div', { id: 'modal', onmousedown: e => { if (e.target === wrap && cancel) close(null); } },
      el('div', { class: 'dialog' },
        el('div', { class: 'd-head', text: title }),
        el('div', { class: 'd-body' }, message ? el('div', { text: message, style: { marginBottom: field || extra ? '10px' : 0 } }) : null, field, extra || null),
        el('div', { class: 'd-foot' }, cancel ? el('button', { class: 'btn', text: 'ยกเลิก', onclick: () => close(null) }) : null, ok)));
    wrap.addEventListener('keydown', e => {
      if (e.key === 'Enter') { e.preventDefault(); ok.click(); }
      if (e.key === 'Escape' && cancel) close(null);
    });
    document.body.append(wrap);
    if (field) {
      field.focus();
      const dot = field.value.lastIndexOf('.');
      field.setSelectionRange(0, dot > 0 ? dot : field.value.length);
    } else ok.focus();
  });
}

let openMenuEl = null;
function closeMenu() { if (openMenuEl) { openMenuEl.remove(); openMenuEl = null; } }
function menu(items, x, y) {
  closeMenu();
  const m = el('div', { class: 'menu' });
  for (const it of items) {
    if (!it) continue;
    if (it === '-') { m.append(el('hr')); continue; }
    m.append(el('button', {
      class: it.danger ? 'danger' : null, disabled: it.disabled,
      onclick: e => {
        e.stopPropagation();
        try {
          // Run handler BEFORE removing the button from DOM so WebKit/iOS Safari preserves
          // transient user activation when triggering <input type="file">.click().
          const res = it.run();
          if (res && typeof res.catch === 'function') res.catch(err => reportError(it.label, err));
        } catch (err) {
          reportError(it.label, err);
        } finally {
          closeMenu();
        }
      },
    }, it.ico ? icon(it.ico) : null, it.label));
  }
  document.body.append(m);
  const r = m.getBoundingClientRect();
  m.style.left = Math.max(4, Math.min(x, innerWidth - r.width - 6)) + 'px';
  m.style.top = Math.max(4, Math.min(y, innerHeight - r.height - 6)) + 'px';
  openMenuEl = m;
}
function menuAt(btn, items) { const r = btn.getBoundingClientRect(); menu(items, r.left, r.bottom + 4); }

let openPop = null;
function closePop() { if (openPop) { openPop.el.remove(); openPop.btn && openPop.btn.classList.remove('active'); openPop = null; } }
document.addEventListener('pointerdown', e => {
  if (openMenuEl && !openMenuEl.contains(e.target)) closeMenu();
  if (openPop && !openPop.el.contains(e.target) && !(openPop.btn && openPop.btn.contains(e.target))) closePop();
});
document.addEventListener('keydown', e => { if (e.key === 'Escape') { closeMenu(); closePop(); } });

// ---------------------------------------------------------------- icons

const G = {
  back: '<path d="M15 18l-6-6 6-6"/>',
  fwd: '<path d="M9 18l6-6-6-6"/>',
  up: '<path d="M12 19V5M5 12l7-7 7 7"/>',
  refresh: '<path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/>',
  upload: '<path d="M12 16V4M7 9l5-5 5 5"/><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3"/>',
  download: '<path d="M12 4v12M7 11l5 5 5-5"/><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  newfolder: '<path d="M3 6h6l2 2h10v11H3z"/><path d="M12 11v6M9 14h6"/>',
  rename: '<path d="M4 20h4L19 9l-4-4L4 16z"/>',
  trash: '<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/>',
  copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"/>',
  cut: '<circle cx="6" cy="18" r="3"/><circle cx="18" cy="18" r="3"/><path d="M8 16L19 4M16 16L5 4"/>',
  paste: '<rect x="5" y="4" width="14" height="17" rx="2"/><path d="M9 4V2h6v2"/>',
  zip: '<path d="M6 3h9l4 4v14H6z"/><path d="M11 6h2M11 9h2M11 12h2M10 15h4v3h-4z"/>',
  open: '<path d="M14 4h6v6M20 4l-9 9"/><path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/>',
  link: '<path d="M10 14a4 4 0 0 0 6 0l3-3a4 4 0 0 0-6-6l-1 1"/><path d="M14 10a4 4 0 0 0-6 0l-3 3a4 4 0 0 0 6 6l1-1"/>',
  list: '<path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
  eye: '<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
  chev: '<path d="M6 9l6 6 6-6"/>',
  caret: '<path d="M9 6l6 6-6 6"/>',
  x: '<path d="M6 6l12 12M18 6L6 18"/>',
  min: '<path d="M5 12h14"/>',
  max: '<rect x="5" y="5" width="14" height="14" rx="2"/>',
  restore: '<rect x="4" y="8" width="12" height="12" rx="2"/><path d="M8 8V5a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1h-3"/>',
  logout: '<path d="M15 4h4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-4M10 17l5-5-5-5M15 12H3"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 8v.01M12 11v5"/>',
  play: '<path d="M8 5v14l11-7z" fill="currentColor"/>',
  pause: '<rect x="6" y="5" width="4" height="14" rx="1" fill="currentColor"/><rect x="14" y="5" width="4" height="14" rx="1" fill="currentColor"/>',
  folderUp: '<path d="M3 6h6l2 2h10v11H3z"/><path d="M12 17v-6M9 14l3-3 3 3"/>',
  undo: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6"/>',
  shield: '<path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"/><path d="M9 12l2 2 4-4"/>',
  key: '<circle cx="8" cy="15" r="4"/><path d="M11 12l9-9M17 6l3 3M15 8l2 2"/>',
  share: '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="M8.6 13.5l6.8 4M15.4 6.5l-6.8 4"/>',
  sync: '<path d="M20 11a8 8 0 0 0-14.9-3M4 13a8 8 0 0 0 14.9 3"/><path d="M4 4v4h4M20 20v-4h-4"/>',
};
function icon(name, cls) {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', '0 0 24 24');
  s.setAttribute('fill', 'none');
  s.setAttribute('stroke', 'currentColor');
  s.setAttribute('stroke-width', '2');
  s.setAttribute('stroke-linecap', 'round');
  s.setAttribute('stroke-linejoin', 'round');
  if (cls) s.setAttribute('class', cls);
  s.innerHTML = G[name] || '';
  return s;
}

// App icons, 64x64.
const APP_ICO = {
  filestation: `<svg viewBox="0 0 64 64"><defs><linearGradient id="fsA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffd76a"/><stop offset="1" stop-color="#f5a623"/></linearGradient></defs>
    <path d="M6 16a4 4 0 0 1 4-4h14l5 5h25a4 4 0 0 1 4 4v29a4 4 0 0 1-4 4H10a4 4 0 0 1-4-4z" fill="#e8951a"/>
    <path d="M6 23a4 4 0 0 1 4-4h44a4 4 0 0 1 4 4v27a4 4 0 0 1-4 4H10a4 4 0 0 1-4-4z" fill="url(#fsA)"/>
    <circle cx="38" cy="38" r="9" fill="#e9f3ff" stroke="#1e6fd9" stroke-width="4"/><path d="M45 45l8 8" stroke="#1e6fd9" stroke-width="5" stroke-linecap="round"/></svg>`,
  photos: `<svg viewBox="0 0 64 64"><rect x="4" y="4" width="56" height="56" rx="14" fill="#fff"/>
    <g transform="translate(32 32)"><ellipse rx="7" ry="14" cy="-11" fill="#f9b233" opacity=".9"/><ellipse rx="7" ry="14" cy="-11" fill="#ef5a5a" opacity=".85" transform="rotate(72)"/>
    <ellipse rx="7" ry="14" cy="-11" fill="#a35ee8" opacity=".85" transform="rotate(144)"/><ellipse rx="7" ry="14" cy="-11" fill="#2f8ae6" opacity=".85" transform="rotate(216)"/>
    <ellipse rx="7" ry="14" cy="-11" fill="#2fb36b" opacity=".85" transform="rotate(288)"/></g></svg>`,
  videos: `<svg viewBox="0 0 64 64"><defs><linearGradient id="vdA" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#8a5cf6"/><stop offset="1" stop-color="#5b3cc4"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#vdA)"/><rect x="13" y="18" width="38" height="28" rx="5" fill="none" stroke="#fff" stroke-width="3.5"/><path d="M28 25v14l11-7z" fill="#fff"/></svg>`,
  music: `<svg viewBox="0 0 64 64"><defs><linearGradient id="muA" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#ff6b8b"/><stop offset="1" stop-color="#e3345b"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#muA)"/><path d="M26 42V20l18-4v22" fill="none" stroke="#fff" stroke-width="3.5" stroke-linejoin="round"/><circle cx="21" cy="42" r="6" fill="#fff"/><circle cx="39" cy="38" r="6" fill="#fff"/></svg>`,
  monitor: `<svg viewBox="0 0 64 64"><defs><linearGradient id="rmA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#34475d"/><stop offset="1" stop-color="#1c2836"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#rmA)"/><path d="M12 40h8l5-14 7 22 6-16 4 8h10" fill="none" stroke="#3ddc97" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round"/></svg>`,
  control: `<svg viewBox="0 0 64 64"><defs><linearGradient id="cpA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#5ba7f7"/><stop offset="1" stop-color="#1e6fd9"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#cpA)"/>
    <path d="M18 20h28M18 32h28M18 44h28" stroke="#cfe3fb" stroke-width="3.5" stroke-linecap="round"/>
    <circle cx="38" cy="20" r="5" fill="#fff"/><circle cx="25" cy="32" r="5" fill="#fff"/><circle cx="41" cy="44" r="5" fill="#fff"/></svg>`,
  help: `<svg viewBox="0 0 64 64"><defs><linearGradient id="hpA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#4fd18b"/><stop offset="1" stop-color="#22a35c"/></linearGradient></defs>
    <path d="M10 12a4 4 0 0 1 4-4h38v48H14a4 4 0 0 1-4-4z" fill="url(#hpA)"/><path d="M10 50a4 4 0 0 1 4-4h38" fill="none" stroke="#d8f6e5" stroke-width="3"/>
    <path d="M26 24a6 6 0 1 1 8 5.6c-1.6.7-2 1.6-2 3.4" fill="none" stroke="#fff" stroke-width="3.5" stroke-linecap="round"/><circle cx="32" cy="39" r="2.4" fill="#fff"/></svg>`,
  upload: `<svg viewBox="0 0 64 64"><defs><linearGradient id="upA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#36c3f0"/><stop offset="1" stop-color="#1688d6"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#upA)"/><path d="M20 40a9 9 0 0 1 1-18 12 12 0 0 1 23 2 8 8 0 0 1 0 16" fill="none" stroke="#fff" stroke-width="3.5" stroke-linecap="round"/><path d="M32 48V30M25 36l7-7 7 7" fill="none" stroke="#fff" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round"/></svg>`,
  download: `<svg viewBox="0 0 64 64"><defs><linearGradient id="dsA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#4fc3f7"/><stop offset="1" stop-color="#0b7bd6"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#dsA)"/><circle cx="32" cy="30" r="15" fill="none" stroke="#fff" stroke-width="3.5" opacity=".35"/>
    <path d="M32 14v26M22 31l10 10 10-10" fill="none" stroke="#fff" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M18 48h28" stroke="#fff" stroke-width="4.5" stroke-linecap="round"/></svg>`,
  player: `<svg viewBox="0 0 64 64"><defs><linearGradient id="mpA" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#ffa53a"/><stop offset="1" stop-color="#f05a28"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#mpA)"/><circle cx="32" cy="32" r="17" fill="rgba(255,255,255,.22)"/><path d="M27 22v20l16-10z" fill="#fff"/></svg>`,
  terminal: `<svg viewBox="0 0 64 64"><defs><linearGradient id="tmA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2d3b4e"/><stop offset="1" stop-color="#161e29"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#tmA)"/><path d="M16 22l9 8-9 8" fill="none" stroke="#5bd68a" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/><path d="M32 42h16" stroke="#9fb0c3" stroke-width="4" stroke-linecap="round"/></svg>`,
  screen: `<svg viewBox="0 0 64 64"><defs><linearGradient id="smA" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#36c3f0"/><stop offset="1" stop-color="#5566e0"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#smA)"/><rect x="20" y="12" width="24" height="40" rx="5" fill="none" stroke="#fff" stroke-width="3.2"/><circle cx="32" cy="46" r="2.4" fill="#fff"/><path d="M27 26l6 4-6 4z" fill="#fff"/></svg>`,
  remote: `<svg viewBox="0 0 64 64"><defs><linearGradient id="raA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ff9d4d"/><stop offset="1" stop-color="#f06a1e"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#raA)"/><circle cx="32" cy="32" r="16" fill="none" stroke="#fff" stroke-width="3"/><path d="M16 32h32M32 16c6 5 6 27 0 32M32 16c-6 5-6 27 0 32" fill="none" stroke="#fff" stroke-width="2.6"/></svg>`,
  packagecenter: `<svg viewBox="0 0 64 64"><defs><linearGradient id="pkA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffd15c"/><stop offset="1" stop-color="#f3932b"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#pkA)"/><path d="M32 14l16 8v20l-16 8-16-8V22z" fill="none" stroke="#fff" stroke-width="3.2" stroke-linejoin="round"/><path d="M16 22l16 8 16-8M32 30v20" fill="none" stroke="#fff" stroke-width="3.2" stroke-linejoin="round"/></svg>`,
  group: `<svg viewBox="0 0 64 64"><rect x="4" y="4" width="56" height="56" rx="14" fill="rgba(30,40,55,.55)"/>
    <rect x="13" y="13" width="17" height="17" rx="5" fill="#fff"/><rect x="34" y="13" width="17" height="17" rx="5" fill="#8a5cf6"/><rect x="13" y="34" width="17" height="17" rx="5" fill="#ef5a7a"/><rect x="34" y="34" width="17" height="17" rx="5" fill="#2f8ae6" opacity=".55"/>
    <g transform="translate(21.5 21.5) scale(.18)"><ellipse rx="7" ry="14" cy="-11" fill="#f9b233" transform="rotate(0)"/><ellipse rx="7" ry="14" cy="-11" fill="#2f8ae6" transform="rotate(120)"/><ellipse rx="7" ry="14" cy="-11" fill="#2fb36b" transform="rotate(240)"/></g>
    <path d="M40 18v8l6-4z" fill="#fff"/></svg>`,
  shares: `<svg viewBox="0 0 64 64"><defs><linearGradient id="shA" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#2ccf9b"/><stop offset="1" stop-color="#0f9c74"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#shA)"/><path d="M28 36a8 8 0 0 0 11.3 0l6.4-6.4a8 8 0 0 0-11.3-11.3l-2.2 2.2" fill="none" stroke="#fff" stroke-width="4" stroke-linecap="round"/>
    <path d="M36 28a8 8 0 0 0-11.3 0l-6.4 6.4a8 8 0 0 0 11.3 11.3l2.2-2.2" fill="none" stroke="#fff" stroke-width="4" stroke-linecap="round"/></svg>`,
  trash: `<svg viewBox="0 0 64 64"><defs><linearGradient id="tbA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#93a3b8"/><stop offset="1" stop-color="#5e6f86"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#tbA)"/><path d="M18 22h28M27 22v-4h10v4" fill="none" stroke="#fff" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round"/>
    <path d="M21 22l2 24a3 3 0 0 0 3 3h12a3 3 0 0 0 3-3l2-24" fill="none" stroke="#fff" stroke-width="3.5" stroke-linejoin="round"/><path d="M29 29v13M35 29v13" stroke="#fff" stroke-width="3" stroke-linecap="round"/></svg>`,
  backup: `<svg viewBox="0 0 64 64"><defs><linearGradient id="bkA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#7c8cff"/><stop offset="1" stop-color="#4b55d6"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#bkA)"/><path d="M45 26a14 14 0 0 0-26-3M19 38a14 14 0 0 0 26 3" fill="none" stroke="#fff" stroke-width="4" stroke-linecap="round"/>
    <path d="M18 16v8h8M46 48v-8h-8" fill="none" stroke="#fff" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/></svg>`,
};
function appIco(name) { return el('div', { class: 'app-ico', html: APP_ICO[name] || '' }); }

// File-type icons (24x24 artwork drawn at any size).
const FILE_COLORS = { image: '#2fb36b', video: '#8a5cf6', audio: '#ef5a7a', pdf: '#e5484d', text: '#7a8794', archive: '#c08a2e', apk: '#3ddc84', doc: '#2b6fd6', sheet: '#1f9d55', slide: '#e8742a', file: '#9aa6b2' };
const FILE_TAG = { image: 'IMG', video: '▶', audio: '♪', pdf: 'PDF', text: 'TXT', archive: 'ZIP', apk: 'APK', doc: 'DOC', sheet: 'XLS', slide: 'PPT', file: '' };
function fileIcon(kind) {
  if (kind === 'folder') return el('span', { class: 'fi', html: '<svg viewBox="0 0 24 24" width="100%" height="100%"><path d="M2 6.5A1.5 1.5 0 0 1 3.5 5H9l2 2h9.5A1.5 1.5 0 0 1 22 8.5V18a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 18z" fill="#f5b432"/><path d="M2 9.5A1.5 1.5 0 0 1 3.5 8h17A1.5 1.5 0 0 1 22 9.5V18a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 18z" fill="#ffd25e"/></svg>' });
  const c = FILE_COLORS[kind] || FILE_COLORS.file;
  const tag = FILE_TAG[kind] || '';
  return el('span', { class: 'fi', html: `<svg viewBox="0 0 24 24" width="100%" height="100%"><path d="M5 2h9.5L20 7.5V21a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z" fill="#fff" stroke="#c9d2dc" stroke-width=".8"/><path d="M14.5 2v5.5H20" fill="#e9eef3" stroke="#c9d2dc" stroke-width=".8"/>${tag ? `<rect x="2.5" y="12" width="14" height="7" rx="1.5" fill="${c}"/><text x="9.5" y="17.4" font-size="5" font-family="Arial,sans-serif" font-weight="700" fill="#fff" text-anchor="middle">${tag}</text>` : ''}</svg>` });
}

// ---------------------------------------------------------------- state

const State = { roots: [], sys: null, notes: [], clipboard: null, isRoot: false, flavor: 'normal',
  user: 'admin', role: 'admin', admin: true, canWrite: true, sudo: false, folders: [] };

function rootOf(p) {
  let best = null;
  for (const r of State.roots) if ((p === r.path || p.startsWith(r.path + '/')) && (!best || r.path.length > best.path.length)) best = r;
  return best;
}
function displayPath(p) {
  const r = rootOf(p);
  return r ? r.name + p.slice(r.path.length) : p;
}
function notify(text) {
  State.notes.unshift({ text, time: Date.now() });
  State.notes.length = Math.min(State.notes.length, 30);
}

// ---------------------------------------------------------------- window manager

const WM = {
  wins: new Map(),
  z: 100,
  active: null,
  open(app) {
    let w = this.wins.get(app.id);
    if (w) { w.restore(); return w; }
    w = new Win(app);
    this.wins.set(app.id, w);
    Taskbar.render();
    return w;
  },
};

class Win {
  constructor(app) {
    this.app = app;
    const n = WM.wins.size;
    const vw = innerWidth, vh = innerHeight;
    const w = Math.min(app.w || 900, vw - 40), h = Math.min(app.h || 560, vh - 80);
    const x = Math.max(10, Math.min(130 + n * 32, vw - w - 10));
    const y = Math.max(54, Math.min(64 + n * 28, vh - h - 10));
    this.body = el('div', { class: 'win-body' });
    this.maxBtn = el('button', { class: 'win-btn max-btn', title: 'ขยาย', onclick: () => this.toggleMax() }, icon('max'));
    this.titleEl = el('div', { class: 'title', text: app.name });
    this.bar = el('div', { class: 'titlebar', ondblclick: e => { if (!e.target.closest('button')) this.toggleMax(); } },
      appIco(app.ico), this.titleEl,
      el('button', { class: 'win-btn', title: 'ย่อ', onclick: () => this.minimize() }, icon('min')),
      this.maxBtn,
      el('button', { class: 'win-btn close', title: 'ปิด', onclick: () => this.close() }, icon('x')));
    this.grip = el('div', { class: 'resize' });
    this.el = el('section', { class: 'window', style: { left: x + 'px', top: y + 'px', width: w + 'px', height: h + 'px' } }, this.bar, this.body, this.grip);
    this.el.addEventListener('pointerdown', () => this.focus(), true);
    document.body.append(this.el);
    this.drag();
    this.focus();
    this.ctl = app.mount(this.body, this) || {};
  }
  setTitle(t) { this.titleEl.textContent = t; }
  focus() {
    if (WM.active === this && !this.el.classList.contains('inactive') && !this.minimized) return;
    if (WM.active && WM.active !== this) WM.active.el.classList.add('inactive');
    this.el.classList.remove('inactive');
    this.el.style.zIndex = ++WM.z;
    WM.active = this;
    Taskbar.render();
  }
  minimize() {
    this.el.classList.add('hidden');
    this.minimized = true;
    if (WM.active === this) WM.active = null;
    Taskbar.render();
  }
  restore() {
    this.el.classList.remove('hidden');
    this.minimized = false;
    this.focus();
  }
  toggleMax() {
    if (!this.max) {
      this.prev = this.el.getAttribute('style');
      Object.assign(this.el.style, { left: '0px', top: 'var(--bar-h)', width: '100%', height: 'calc(100% - var(--bar-h))' });
      this.el.classList.add('max');
      this.maxBtn.replaceChildren(icon('restore'));
    } else {
      this.el.setAttribute('style', this.prev);
      this.el.style.zIndex = ++WM.z;
      this.el.classList.remove('max');
      this.maxBtn.replaceChildren(icon('max'));
    }
    this.max = !this.max;
  }
  close() {
    if (this.ctl.destroy) this.ctl.destroy();
    this.el.remove();
    WM.wins.delete(this.app.id);
    if (WM.active === this) WM.active = null;
    Taskbar.render();
  }
  drag() {
    const track = (handle, onMove) => handle.addEventListener('pointerdown', e => {
      if (e.button !== 0 || e.target.closest('button') || this.max || innerWidth <= 760) return;
      e.preventDefault();
      const r = this.el.getBoundingClientRect();
      const sx = e.clientX, sy = e.clientY;
      const move = ev => onMove(r, ev.clientX - sx, ev.clientY - sy);
      const up = () => { removeEventListener('pointermove', move); removeEventListener('pointerup', up); };
      addEventListener('pointermove', move);
      addEventListener('pointerup', up);
    });
    track(this.bar, (r, dx, dy) => {
      this.el.style.left = Math.min(innerWidth - 80, Math.max(-r.width + 80, r.left + dx)) + 'px';
      this.el.style.top = Math.min(innerHeight - 40, Math.max(44, r.top + dy)) + 'px';
    });
    track(this.grip, (r, dx, dy) => {
      this.el.style.width = Math.max(320, r.width + dx) + 'px';
      this.el.style.height = Math.max(220, r.height + dy) + 'px';
    });
  }
}

// ---------------------------------------------------------------- taskbar & desktop

const Taskbar = {
  pinned: ['filestation', 'download'],
  render() {
    const box = $('#taskApps');
    box.replaceChildren();
    const ids = [...this.pinned];
    for (const id of WM.wins.keys()) if (!ids.includes(id)) ids.push(id);
    for (const id of ids) {
      const app = APPS[id];
      const w = WM.wins.get(id);
      if (!w && !appAllowed(id)) continue;
      const b = el('button', {
        class: 'tb-btn' + (w ? ' open' : '') + (w && w.minimized ? ' minimized' : '') + (w && WM.active === w ? ' active' : ''),
        title: app.name,
        onclick: () => {
          if (!w) openApp(id);
          else if (WM.active === w && !w.minimized) w.minimize();
          else w.restore();
        },
      }, appIco(app.ico));
      box.append(b);
    }
  },
};

function openApp(id, arg) {
  closePop();
  if (!appAllowed(id)) { toast(APPS[id] && APPS[id].admin ? API_ERRORS.admin_only : 'แอปนี้ใช้กับบัญชีนี้ไม่ได้'); return null; }
  const w = WM.open(APPS[id]);
  if (arg && w.ctl.handle) w.ctl.handle(arg);
  return w;
}

const DESKTOP = [
  { app: 'filestation' },
  { app: 'download' },
  { group: 'Multimedia', ico: 'group', apps: ['player', 'photos', 'videos', 'music', 'wallpaper'] },
  { app: 'backup' },
  { app: 'shares' },
  { app: 'trash' },
  { app: 'monitor' },
  { app: 'remote' },
  { app: 'packagecenter' },
  { app: 'terminal' },
  { app: 'screen' },
  { app: 'control', badge: () => controlBadge() },
  { app: 'help' },
];

/** Whether the current user may open an app (root edition, admin role, write access). */
function appAllowed(id) {
  const a = APPS[id];
  if (!a) return false;
  if (a.root && !State.isRoot) return false;
  if (a.admin && !State.admin) return false;
  if (a.write && !State.canWrite) return false;
  return true;
}

function controlBadge() {
  let n = 0;
  if (State.roots.length && !State.roots[0].readable) n++;
  if (Auth.pinRequired === false) n++;
  if (State.admin && !Auth.adminPassword && Auth.tunnelOn) n++;
  return n;
}

function renderDesktop() {
  const d = $('#desktop');
  d.replaceChildren();
  for (const it of DESKTOP) {
    if (it.app && !appAllowed(it.app)) continue;
    const app = it.app ? APPS[it.app] : null;
    const groupApps = it.apps ? it.apps.filter(appAllowed) : null;
    if (groupApps && !groupApps.length) continue;
    const badge = it.badge ? it.badge() : 0;
    const btn = el('button', { class: 'desk-icon' }, appIco(app ? app.ico : it.ico), el('div', { class: 'lbl', text: app ? app.name : it.group }), badge ? el('span', { class: 'badge', text: badge }) : null);
    btn.addEventListener('click', () => app ? openApp(it.app) : groupPopover(btn, it));
    d.append(btn);
  }
}

function appGridPopover(anchorBtn, ids, title, three) {
  closePop();
  const grid = el('div', { class: 'pop-grid' + (three ? ' three' : '') });
  const fill = q => {
    grid.replaceChildren();
    for (const id of ids) {
      const a = APPS[id];
      if (q && !a.name.toLowerCase().includes(q.toLowerCase())) continue;
      grid.append(el('button', { class: 'pop-tile', onclick: () => openApp(id) }, appIco(a.ico), a.name));
    }
  };
  const search = el('input', { class: 'search', placeholder: title || 'ค้นหา', oninput: e => fill(e.target.value) });
  const pop = el('div', { class: 'popover' }, search, grid);
  fill('');
  document.body.append(pop);
  const r = anchorBtn.getBoundingClientRect();
  const pr = pop.getBoundingClientRect();
  pop.style.left = Math.max(8, Math.min(r.left, innerWidth - pr.width - 8)) + 'px';
  pop.style.top = (anchorBtn.id === 'mainMenuBtn' ? r.bottom + 6 : Math.min(r.top, innerHeight - pr.height - 8)) + 'px';
  if (anchorBtn.classList.contains('desk-icon') && innerWidth > 760) pop.style.left = (r.right + 8) + 'px';
  anchorBtn.classList.add('active');
  openPop = { el: pop, btn: anchorBtn };
  if (innerWidth > 760) search.focus();
}
function groupPopover(btn, it) { appGridPopover(btn, it.apps.filter(appAllowed), 'ค้นหาใน ' + it.group, true); }
