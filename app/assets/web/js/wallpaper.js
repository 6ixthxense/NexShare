/* NexShare web desktop — NexOS Wallpaper. Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- NexOS Wallpaper

const NEX_LOGO = `<g transform="translate(-54,-54)"><rect width="108" height="108" rx="26" fill="#1e6fd9"/><path d="M26,40h22l8,8h32a5,5 0 0 1 5,5v30a5,5 0 0 1 -5,5h-62a5,5 0 0 1 -5,-5v-38a5,5 0 0 1 5,-5z" fill="#ffc94a"/><path d="M54,52l16,16h-11v16h-10v-16h-11z" fill="#fff"/></g>`;

const WALLPAPERS = {
  nexos_dark: {
    name: 'NexOS Dark',
    svg: (w, h, sub) => `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">
      <defs><radialGradient id="g" cx="50%" cy="34%" r="80%"><stop offset="0" stop-color="#1b2a44"/><stop offset="55%" stop-color="#0e1626"/><stop offset="1" stop-color="#070b14"/></radialGradient>
      <linearGradient id="t" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#5aa0f0"/><stop offset="1" stop-color="#9f7bff"/></linearGradient></defs>
      <rect width="${w}" height="${h}" fill="url(#g)"/>
      ${rays(w, h, 0.05)}
      <g transform="translate(${w/2},${h*0.4}) scale(${Math.min(w,h)/420})">${NEX_LOGO}</g>
      <text x="${w/2}" y="${h*0.4 + Math.min(w,h)*0.2}" text-anchor="middle" font-family="Segoe UI, Arial" font-weight="700" font-size="${Math.min(w,h)*0.085}" fill="url(#t)">NexOS</text>
      <text x="${w/2}" y="${h*0.4 + Math.min(w,h)*0.26}" text-anchor="middle" font-family="Segoe UI, Arial" font-size="${Math.min(w,h)*0.028}" fill="#8ba0bd" letter-spacing="6">N E X S H A R E</text>
      ${sub ? `<text x="${w/2}" y="${h*0.93}" text-anchor="middle" font-family="Segoe UI, Arial" font-size="${Math.min(w,h)*0.024}" fill="#5b6c84">${esc(sub)}</text>` : ''}
    </svg>`,
  },
  nexos_blue: {
    name: 'NexOS Aqua',
    svg: (w, h, sub) => `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">
      <defs><linearGradient id="g" x1="0" y1="0" x2="0.6" y2="1"><stop offset="0" stop-color="#d6eefc"/><stop offset="0.4" stop-color="#8fc6ee"/><stop offset="1" stop-color="#3d80c6"/></linearGradient></defs>
      <rect width="${w}" height="${h}" fill="url(#g)"/>
      ${rays(w, h, 0.12)}
      <g transform="translate(${w/2},${h*0.42}) scale(${Math.min(w,h)/380})">${NEX_LOGO}</g>
      <text x="${w/2}" y="${h*0.42 + Math.min(w,h)*0.21}" text-anchor="middle" font-family="Segoe UI, Arial" font-weight="700" font-size="${Math.min(w,h)*0.088}" fill="#13477f">NexOS</text>
      <text x="${w/2}" y="${h*0.42 + Math.min(w,h)*0.275}" text-anchor="middle" font-family="Segoe UI, Arial" font-size="${Math.min(w,h)*0.028}" letter-spacing="6" fill="#1e6fd9">N E X S H A R E</text>
      ${sub ? `<text x="${w/2}" y="${h*0.93}" text-anchor="middle" font-family="Segoe UI, Arial" font-size="${Math.min(w,h)*0.024}" fill="#14467c">${esc(sub)}</text>` : ''}
    </svg>`,
  },
  nexos_grid: {
    name: 'NexOS Grid',
    svg: (w, h, sub) => `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">
      <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0b1220"/><stop offset="1" stop-color="#101d33"/></linearGradient>
      <pattern id="grid" width="${w/18}" height="${w/18}" patternUnits="userSpaceOnUse"><path d="M${w/18} 0H0V${w/18}" fill="none" stroke="#1d3a5f" stroke-width="1"/></pattern></defs>
      <rect width="${w}" height="${h}" fill="url(#g)"/><rect width="${w}" height="${h}" fill="url(#grid)" opacity="0.5"/>
      <circle cx="${w/2}" cy="${h*0.4}" r="${Math.min(w,h)*0.28}" fill="none" stroke="#1e6fd9" stroke-width="2" opacity="0.4"/>
      <g transform="translate(${w/2},${h*0.4}) scale(${Math.min(w,h)/400})">${NEX_LOGO}</g>
      <text x="${w/2}" y="${h*0.4 + Math.min(w,h)*0.2}" text-anchor="middle" font-family="Segoe UI, Arial" font-weight="700" font-size="${Math.min(w,h)*0.082}" fill="#e6edf3">NexOS</text>
      <text x="${w/2}" y="${h*0.4 + Math.min(w,h)*0.255}" text-anchor="middle" font-family="Segoe UI, Arial" font-size="${Math.min(w,h)*0.026}" fill="#5aa0f0" letter-spacing="5">NEXSHARE EDITION</text>
      ${sub ? `<text x="${w/2}" y="${h*0.93}" text-anchor="middle" font-family="monospace" font-size="${Math.min(w,h)*0.022}" fill="#3f6187">${esc(sub)}</text>` : ''}
    </svg>`,
  },
};
function rays(w, h, a) {
  let s = `<g opacity="${a}" fill="#fff">`;
  for (let i = 0; i < 7; i++) { const x = w * (0.6 + i * 0.12); s += `<polygon points="${w},0 ${x},0 ${w},${h * (0.3 + i * 0.1)}"/>`; }
  return s + '</g>';
}
const esc = s => String(s).replace(/[<>&]/g, c => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[c]));

function Wallpaper(body, win) {
  const st = { key: 'nexos_dark', showInfo: true, w: screen.width * (devicePixelRatio || 1), h: screen.height * (devicePixelRatio || 1) };
  if (!st.w || st.w < 720) { st.w = 1080; st.h = 2340; }
  const preview = el('div', { style: { flex: '1', display: 'grid', placeItems: 'center', background: '#11161d', minHeight: 0, padding: '14px' } });
  const frame = el('div', { style: { height: '100%', aspectRatio: st.w + '/' + st.h, maxWidth: '100%', borderRadius: '18px', overflow: 'hidden', boxShadow: '0 10px 40px rgba(0,0,0,.5)', border: '6px solid #222' } });
  preview.append(frame);
  const thumbs = el('div', { style: { display: 'flex', gap: '10px', padding: '12px', overflowX: 'auto', borderTop: '1px solid var(--line)' } });
  const infoChk = el('label', { style: { display: 'flex', gap: '6px', alignItems: 'center', fontSize: '13px' } },
    el('input', { type: 'checkbox', checked: true, onchange: e => { st.showInfo = e.target.checked; render(); } }), 'ใส่ชื่อเครื่อง/IP ด้านล่าง');
  const status = el('span', { style: { color: 'var(--text-3)', fontSize: '12px' } });
  const setBtn = el('button', { class: 'btn primary', onclick: e => menuAt(e.currentTarget, [
    { label: 'ตั้งเป็นวอลเปเปอร์ (หน้าหลัก + ล็อก)', run: () => apply('both') },
    { label: 'เฉพาะหน้าหลัก', run: () => apply('home') },
    { label: 'เฉพาะหน้าจอล็อก', run: () => apply('lock') },
    { label: 'ดาวน์โหลดรูป', ico: 'download', run: () => download() },
  ]) }, icon('download'), 'ตั้งเป็นวอลเปเปอร์', icon('chev', 'chev'));
  const bar = el('div', { class: 'ds-tools' }, infoChk, el('span', { style: { flex: 1 } }), status, setBtn);
  body.append(el('div', { style: { flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 } }, bar, preview, thumbs));

  const sub = () => st.showInfo && Stats.last ? `${Stats.last.name}  ·  ${Stats.last.ip || ''}  ·  nexshare.local` : '';
  const svgOf = key => WALLPAPERS[key].svg(st.w, st.h, sub());

  function render() {
    frame.innerHTML = svgOf(st.key);
    const s = frame.querySelector('svg');
    if (s) { s.setAttribute('width', '100%'); s.setAttribute('height', '100%'); s.style.display = 'block'; }
    thumbs.replaceChildren(...Object.keys(WALLPAPERS).map(k => {
      const t = el('div', { style: { flex: 'none', width: '72px', aspectRatio: st.w + '/' + st.h, borderRadius: '10px', overflow: 'hidden', cursor: 'pointer', border: k === st.key ? '2px solid var(--accent)' : '2px solid transparent' }, onclick: () => { st.key = k; render(); } });
      t.innerHTML = WALLPAPERS[k].svg(st.w, st.h, '');
      const sv = t.querySelector('svg'); if (sv) { sv.setAttribute('width', '100%'); sv.setAttribute('height', '100%'); }
      return t;
    }));
    win.setTitle('Wallpaper — ' + WALLPAPERS[st.key].name);
  }
  async function toBlob() {
    const svg = svgOf(st.key);
    const img = new Image();
    const url = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }));
    await new Promise((res, rej) => { img.onload = res; img.onerror = rej; img.src = url; });
    const canvas = el('canvas'); canvas.width = st.w; canvas.height = st.h;
    canvas.getContext('2d').drawImage(img, 0, 0, st.w, st.h);
    URL.revokeObjectURL(url);
    return await new Promise(r => canvas.toBlob(r, 'image/jpeg', 0.92));
  }
  async function apply(which) {
    status.textContent = 'กำลังตั้งวอลเปเปอร์…';
    try {
      const blob = await toBlob();
      const r = await fetch('/api/wallpaper' + qs({ which }), { method: 'POST', body: blob, credentials: 'same-origin' });
      if (!r.ok) { const j = await r.json().catch(() => ({})); throw new Error(j.error || r.status); }
      status.textContent = '';
      toast('ตั้งวอลเปเปอร์ NexOS แล้ว');
    } catch (e) { status.textContent = ''; toast('ไม่สำเร็จ: ' + e.message, 4000); }
  }
  async function download() {
    const blob = await toBlob();
    const a = el('a', { href: URL.createObjectURL(blob), download: 'NexOS-' + st.key + '.jpg' });
    document.body.append(a); a.click(); setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
  }
  render();
  return {};
}
