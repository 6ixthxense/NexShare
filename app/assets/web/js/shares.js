/* NexShare web desktop — share links (create dialog + Shared Links app). Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- share links

const SHARE_EXPIRY = [[24, '1 วัน'], [168, '7 วัน'], [720, '30 วัน'], [0, 'ไม่หมดอายุ']];

/** Base URLs a share can be opened from: this address, plus the internet tunnel when it's up. */
async function shareBases() {
  const bases = [{ label: 'ที่อยู่นี้', url: location.origin }];
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

async function shareDialog(entry) {
  if (!entry) return;
  const v = await formDialog({
    title: 'แชร์ "' + entry.name + '"',
    message: 'ใครก็ตามที่มีลิงก์จะเปิด' + (entry.dir ? 'โฟลเดอร์นี้' : 'ไฟล์นี้') + 'ได้โดยไม่ต้องเข้าสู่ระบบ',
    fields: [
      { name: 'hours', label: 'หมดอายุใน', type: 'select', value: 168, options: SHARE_EXPIRY },
      { name: 'password', label: 'รหัสผ่าน (ไม่บังคับ)', type: 'password', placeholder: 'อย่างน้อย 4 ตัวอักษร', autocomplete: 'new-password' },
    ],
    okText: 'สร้างลิงก์',
  });
  if (!v) return;
  let share;
  try {
    share = await api('share/create', null, { method: 'POST', body: JSON.stringify({ path: entry.path, hours: +v.hours, password: v.password }) });
  } catch (e) { toast('สร้างลิงก์ไม่สำเร็จ: ' + e.message); return; }
  showShareLink(share);
}

async function showShareLink(share) {
  const bases = await shareBases();
  const box = el('div', { class: 'share-links' });
  for (const b of bases) {
    const url = b.url + '/s/' + share.id;
    const input = el('input', { value: url, readonly: true, onfocus: e => e.target.select() });
    box.append(el('div', { class: 'f-row' }, el('span', { text: b.label }),
      el('div', { class: 'row-inline' }, input, el('button', { class: 'btn', onclick: async () => toast(await copyText(url) ? 'คัดลอกลิงก์แล้ว' : 'คัดลอกไม่ได้ — เลือกข้อความแล้วคัดลอกเอง') }, icon('copy'), 'คัดลอก'))));
  }
  const best = bases[0].url + '/s/' + share.id;
  const qr = qrImage(best);
  const note = bases.length === 1 && State.admin
    ? 'ลิงก์นี้เปิดได้เฉพาะเครื่องที่เข้าถึงมือถือได้ เปิด Remote Access เพื่อส่งให้คนนอก Wi-Fi'
    : (share.expires ? 'หมดอายุ ' + fmtDate(share.expires) : 'ไม่หมดอายุ') + (share.password ? ' · มีรหัสผ่าน' : '');
  await dialog({ title: 'ลิงก์แชร์พร้อมแล้ว', message: note, okText: 'เสร็จ', cancel: false, extra: el('div', null, box, qr) });
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
    return el('span', { class: 'pill green', text: s.expires ? 'ถึง ' + fmtDate(s.expires) : 'ไม่หมดอายุ' });
  }
  function render() {
    fill(pane,
      el('h2', { text: 'ลิงก์ที่แชร์' }),
      el('p', { class: 'muted', text: 'สร้างลิงก์ได้จาก File Station: เลือกไฟล์ → การทำงาน → สร้างลิงก์แชร์' }),
      shares.length ? el('table', { class: 'tbl' },
        el('thead', null, el('tr', null, ...['ชื่อ', 'สถานะ', 'เปิดแล้ว', State.admin ? 'โดย' : null, ''].filter(x => x !== null).map(h => el('th', { text: h })))),
        el('tbody', null, ...shares.map(s => el('tr', null,
          el('td', { class: 'name-cell' }, fileIcon(s.dir ? 'folder' : kindOf(s)), el('span', { text: s.name, title: displayPath(s.path) }), s.password ? icon('key', 'tiny') : null),
          el('td', null, status(s)),
          el('td', { text: s.hits + ' ครั้ง' }),
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
