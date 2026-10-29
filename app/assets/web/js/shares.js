/* NexShare web desktop — share links (create dialog + Shared Links app). Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- share links

const SHARE_EXPIRY = [[24, '1 วัน'], [168, '7 วัน'], [720, '30 วัน'], [0, 'ไม่หมดอายุ']];

/** Base URLs a share can be opened from: this address, Tailscale IP (if active), plus the internet tunnel when it's up. */
async function shareBases() {
  const bases = [{ label: 'ที่อยู่นี้', url: location.origin }];
  const sys = Stats.last || {};
  if (sys.tailscaleIp) {
    const tsHttp = `http://${sys.tailscaleIp}:${sys.port || 8080}`;
    if (tsHttp !== location.origin) bases.push({ label: 'Tailscale', url: tsHttp });
    if (sys.httpsPort) {
      const tsHttps = `https://${sys.tailscaleIp}:${sys.httpsPort}`;
      if (tsHttps !== location.origin) bases.push({ label: 'Tailscale (HTTPS)', url: tsHttps });
    }
  }
  if (State.admin) {
    try {
      const t = await api('tunnel/status');
      if (t.state === 'connected' && t.url) bases.unshift({ label: 'ผ่านอินเทอร์เน็ต', url: t.url.replace(/\/$/, '') });
    } catch { /* tunnel not available */ }
  }
  return bases;
}

/** A QR code image for a URL, using the bundled qrcode-generator (MIT). */
function qrImage(text, cell = 4) {
  try {
    const q = qrcode(0, 'M');
    q.addData(text, 'Byte');
    q.make();
    return el('div', { class: 'qr', html: q.createSvgTag({ cellSize: cell, margin: 2, scalable: true }) });
  } catch { return null; }
}

async function shareDialog(entry, defaultMode = 'share') {
  if (!entry) return;
  const isReq = entry.dir && defaultMode === 'request';
  const fields = [];
  if (entry.dir) {
    fields.push({
      name: 'mode',
      label: 'รูปแบบลิงก์',
      type: 'select',
      value: isReq ? 'request' : 'share',
      options: [
        ['share', 'แชร์โฟลเดอร์ (เปิดดูและดาวน์โหลดไฟล์)'],
        ['request', 'ลิงก์ขอไฟล์ (ให้คนอื่นส่งไฟล์เข้าโฟลเดอร์นี้)'],
      ],
    });
    fields.push({
      name: 'label',
      label: 'ข้อความหัวข้อ (สำหรับลิงก์ขอไฟล์)',
      placeholder: 'เช่น ส่งรูปงานแต่ง / ส่งการบ้าน',
      value: isReq ? 'ส่งไฟล์เข้า "' + entry.name + '"' : '',
    });
  }
  fields.push(
    { name: 'hours', label: 'หมดอายุใน', type: 'select', value: 168, options: SHARE_EXPIRY },
    { name: 'maxHits', label: 'จำกัดจำนวนครั้งที่เปิด/ส่งไฟล์ (0 = ไม่จำกัด)', type: 'number', value: '0', placeholder: '0' },
  );
  if (entry.dir) {
    fields.push({ name: 'maxSizeMb', label: 'จำกัดขนาดไฟล์สูงสุดสำหรับขอไฟล์ (MB, 0 = ไม่จำกัด)', type: 'number', value: '0', placeholder: '0' });
  }
  fields.push(
    { name: 'password', label: 'รหัสผ่าน (ไม่บังคับ)', type: 'password', placeholder: 'อย่างน้อย 4 ตัวอักษร', autocomplete: 'new-password' },
  );
  const v = await formDialog({
    title: (isReq ? 'สร้างลิงก์ขอไฟล์เข้า "' : 'แชร์ "') + entry.name + '"',
    message: isReq
      ? 'ผู้รับลิงก์จะอัปโหลดไฟล์เข้าโฟลเดอร์นี้ได้โดยไม่ต้องมีบัญชี และจะไม่เห็นไฟล์อื่นในโฟลเดอร์'
      : 'ใครก็ตามที่มีลิงก์จะเปิด' + (entry.dir ? 'โฟลเดอร์นี้' : 'ไฟล์นี้') + 'ได้โดยไม่ต้องเข้าสู่ระบบ',
    fields,
    okText: 'สร้างลิงก์',
  });
  if (!v) return;
  const mode = entry.dir && v.mode === 'request' ? 'request' : 'share';
  let share;
  try {
    share = await api('share/create', null, {
      method: 'POST',
      body: JSON.stringify({
        path: entry.path,
        hours: +v.hours,
        password: v.password,
        mode,
        label: v.label || '',
        maxHits: Math.max(0, parseInt(v.maxHits || '0', 10) || 0),
        maxSizeMb: Math.max(0, parseInt(v.maxSizeMb || '0', 10) || 0),
      }),
    });
  } catch (e) { toast('สร้างลิงก์ไม่สำเร็จ: ' + e.message); return; }
  showShareLink(share);
}

async function showShareLink(share) {
  const bases = await shareBases();
  const box = el('div', { class: 'share-links' });
  const prefix = '/s/';
  for (const b of bases) {
    const url = b.url + prefix + share.id;
    const input = el('input', { value: url, readonly: true, onfocus: e => e.target.select() });
    box.append(el('div', { class: 'f-row' }, el('span', { text: b.label }),
      el('div', { class: 'row-inline' }, input, el('button', { class: 'btn', onclick: async () => toast(await copyText(url) ? 'คัดลอกลิงก์แล้ว' : 'คัดลอกไม่ได้ — เลือกข้อความแล้วคัดลอกเอง') }, icon('copy'), 'คัดลอก'))));
  }
  const best = bases[0].url + prefix + share.id;
  const qr = qrImage(best);
  const note = bases.length === 1 && State.admin
    ? 'ลิงก์นี้เปิดได้เฉพาะเครื่องที่เข้าถึงมือถือได้ เปิด Remote Access หรือใช้ Tailscale เพื่อส่งให้คนนอก Wi-Fi'
    : (share.expires ? 'หมดอายุ ' + fmtDate(share.expires) : 'ไม่หมดอายุ') + (share.password ? ' · มีรหัสผ่าน' : '');
  const title = share.mode === 'request' ? 'ลิงก์ขอไฟล์พร้อมแล้ว' : 'ลิงก์แชร์พร้อมแล้ว';
  await dialog({ title, message: note, okText: 'เสร็จ', cancel: false, extra: el('div', null, box, qr) });
}

function SharedLinks(body) {
  const pane = el('div', { class: 'pane' });
  body.append(pane);
  let shares = [];
  async function load() {
    try { shares = (await api('share/list')).shares.sort((a, b) => b.created - a.created); }
    catch (e) { fill(pane, el('div', { class: 'notice', text: e.message })); return; }
    render();
  }
  function status(s) {
    if (s.expires && s.expires < Date.now()) return el('span', { class: 'pill gray', text: 'หมดอายุ' });
    if (s.maxHits > 0 && s.hits >= s.maxHits) return el('span', { class: 'pill orange', text: 'ครบโควตา' });
    return el('span', { class: 'pill green', text: s.expires ? 'ถึง ' + fmtDate(s.expires) : 'ไม่หมดอายุ' });
  }
  function modeBadge(s) {
    if (s.mode === 'request') return el('span', { class: 'pill blue', text: 'ขอไฟล์' });
    return el('span', { class: 'pill gray', text: 'แชร์ไฟล์' });
  }
  async function createFileRequest() {
    const p = await pickFolder(null, 'เลือกโฟลเดอร์ปลายทางสำหรับรับไฟล์');
    if (!p) return;
    const name = p.split('/').filter(Boolean).pop() || 'โฟลเดอร์';
    await shareDialog({ path: p, name, dir: true }, 'request');
    load();
  }
  function render() {
    fill(pane,
      el('div', { class: 'pane-head' },
        el('h2', { text: 'ลิงก์ที่แชร์' }),
        el('span', { class: 'grow' }),
        State.canWrite ? el('button', { class: 'btn primary', onclick: createFileRequest }, icon('upload'), 'สร้างลิงก์ขอไฟล์') : null,
        el('button', { class: 'btn', onclick: load }, icon('refresh'), 'รีเฟรช')),
      el('p', { class: 'muted', text: 'สร้างลิงก์แชร์หรือลิงก์ขอไฟล์ได้จาก File Station: เลือกไฟล์/โฟลเดอร์ → การทำงาน → สร้างลิงก์แชร์ หรือ สร้างลิงก์ขอไฟล์' }),
      shares.length ? el('table', { class: 'tbl' },
        el('thead', null, el('tr', null, ...['ชื่อ', 'ประเภท', 'สถานะ', 'เปิดแล้ว', State.admin ? 'โดย' : null, ''].filter(x => x !== null).map(h => el('th', { text: h })))),
        el('tbody', null, ...shares.map(s => el('tr', null,
          el('td', { class: 'name-cell' }, fileIcon(s.dir ? 'folder' : kindOf(s)), el('span', { text: s.label ? `${s.name} (${s.label})` : s.name, title: displayPath(s.path) }), s.password ? icon('key', 'tiny') : null),
          el('td', null, modeBadge(s)),
          el('td', null, status(s)),
          el('td', { text: (s.maxHits > 0 ? `${s.hits} / ${s.maxHits} ครั้ง` : `${s.hits} ครั้ง`) + (s.maxBytes > 0 ? ` · ≤${fmtSize(s.maxBytes)}` : '') }),
          State.admin ? el('td', { text: s.owner === 'admin' ? 'ผู้ดูแล' : s.owner }) : null,
          el('td', { class: 'actions' },
            el('button', { class: 'icon-btn', title: 'ดูลิงก์ / QR', onclick: () => showShareLink(s) }, icon('link')),
            el('button', { class: 'icon-btn danger', title: 'ยกเลิกลิงก์', onclick: () => revoke(s) }, icon('trash')))))))
        : el('div', { class: 'empty', text: 'ยังไม่มีลิงก์แชร์' }));
  }
  async function revoke(s) {
    if (!await dialog({ title: 'ยกเลิกลิงก์ "' + s.name + '"?', message: 'คนที่มีลิงก์จะเปิดไม่ได้อีก (ไฟล์ไม่ถูกลบ)', okText: 'ยกเลิกลิงก์', danger: true })) return;
    try { await api('share/delete', { id: s.id }, { method: 'POST' }); toast('ยกเลิกลิงก์แล้ว'); } catch (e) { toast(e.message); }
    load();
  }
  load();
  return { handle() { load(); } };
}
