/* NexShare web desktop — Control Panel admin tabs: security, users, services. Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- Control Panel: security

const TOTP_MODE_LABEL = { off: 'ปิดอยู่', tunnel: 'เมื่อเข้าผ่านอินเทอร์เน็ต', always: 'ทุกครั้งที่เข้าสู่ระบบ' };

function settingRow(label, desc, control) {
  return el('div', { class: 'set-row' }, el('div', { class: 'set-text' }, el('b', { text: label }), desc ? el('small', { text: desc }) : null), control);
}

function toggleSwitch(on, onchange) {
  const i = el('input', { type: 'checkbox', checked: on, onchange: e => onchange(e.target.checked, e.target) });
  return el('label', { class: 'switch' }, i, el('span'));
}

async function saveSettings(patch) {
  return api('settings', null, { method: 'POST', body: JSON.stringify(patch) });
}

function SecurityTab(pane) {
  let s = null;
  async function load() {
    try { s = await api('settings'); } catch (e) { fill(pane, el('div', { class: 'notice', text: e.message })); return; }
    try { Auth.tunnelOn = (await api('tunnel/status')).state === 'connected'; } catch { /* no tunnel */ }
    render();
  }
  const owner = () => State.user === 'admin';
  function render() {
    const httpsUrl = Stats.last && Stats.last.ip && s.httpsRunning ? `https://${Stats.last.ip}:${s.httpsPort}` : null;
    fill(pane,
      Auth.tunnelOn && !s.adminPassword && s.totpMode === 'off' ? el('div', { class: 'notice warn', text: 'เปิด Remote Access อยู่แต่ยังใช้แค่ PIN 6 หลัก แนะนำให้ตั้งรหัสผ่านผู้ดูแลหรือเปิด 2FA' }) : null,
      el('div', { class: 'card' },
        el('h3', { text: 'การเข้าสู่ระบบของผู้ดูแล' }),
        settingRow(s.adminPassword ? 'ใช้รหัสผ่าน' : 'ใช้ PIN 6 หลัก',
          s.adminPassword ? 'PIN บนมือถือใช้เข้าสู่ระบบไม่ได้แล้ว ล้างรหัสผ่านได้จากแอปบนมือถือหากลืม' : 'PIN เดาได้ง่ายกว่ารหัสผ่าน โดยเฉพาะเมื่อเปิดให้เข้าจากอินเทอร์เน็ต',
          owner() ? el('div', { class: 'row-inline' },
            el('button', { class: 'btn', onclick: setPassword }, icon('key'), s.adminPassword ? 'เปลี่ยนรหัสผ่าน' : 'ตั้งรหัสผ่าน'),
            s.adminPassword ? el('button', { class: 'btn', onclick: clearPassword }, 'กลับไปใช้ PIN') : null)
            : el('small', { class: 'muted', text: 'เฉพาะเจ้าของมือถือ' })),
        settingRow('ยืนยันตัวตน 2 ขั้น (2FA)', 'ใช้รหัส 6 หลักจาก Google Authenticator, Authy, 1Password ฯลฯ — ตอนนี้: ' + TOTP_MODE_LABEL[s.totpMode],
          owner() ? el('div', { class: 'row-inline' },
            s.totpMode === 'off' ? el('button', { class: 'btn primary', onclick: setupTotp }, icon('shield'), 'เปิด 2FA')
              : el('select', { onchange: e => changeTotpMode(e.target.value) }, ...['tunnel', 'always'].map(m => el('option', { value: m, text: TOTP_MODE_LABEL[m], selected: s.totpMode === m }))),
            s.totpMode !== 'off' ? el('button', { class: 'btn', onclick: disableTotp }, 'ปิด 2FA') : null)
            : el('small', { class: 'muted', text: 'เฉพาะเจ้าของมือถือ' }))),
      el('div', { class: 'card' },
        el('h3', { text: 'HTTPS (เข้ารหัสในวง Wi-Fi)' }),
        settingRow('เปิด HTTPS', `พอร์ต ${s.httpsPort} · ใบรับรองที่สร้างเอง เบราว์เซอร์จะเตือนครั้งแรก`, toggleSwitch(s.https, async on => { s = await saveSettings({ https: on }); setTimeout(load, 1500); })),
        settingRow('บังคับใช้ HTTPS', 'ผู้ที่เข้าแบบ http:// จะถูกพาไป https:// อัตโนมัติ', toggleSwitch(s.forceHttps, async (on, input) => {
          if (on && !s.httpsRunning) { input.checked = false; toast('ต้องเปิด HTTPS ก่อน'); return; }
          s = await saveSettings({ forceHttps: on });
          if (on && location.protocol === 'http:') location.href = httpsUrl || location.href;
        })),
        s.httpsRunning ? el('dl', { class: 'kv' },
          httpsUrl ? [el('dt', { text: 'ที่อยู่' }), el('dd', { class: 'mono', text: httpsUrl })] : null,
          el('dt', { text: 'ลายนิ้วมือ SHA-256' }), el('dd', { class: 'mono small', text: s.tlsFingerprint })) : null,
        s.httpsRunning ? el('div', { class: 'row-inline' },
          el('a', { class: 'btn', href: '/api/tls/cert', download: 'nexshare.crt' }, icon('download'), 'ดาวน์โหลดใบรับรอง'),
          el('button', { class: 'btn', onclick: regenerateCert }, icon('refresh'), 'สร้างใบรับรองใหม่')) : null,
        el('small', { class: 'muted', text: 'ติดตั้งใบรับรองในเครื่องที่ใช้บ่อยเพื่อไม่ให้เบราว์เซอร์เตือน และตรวจลายนิ้วมือให้ตรงกับที่แสดงที่นี่' })),
      el('div', { class: 'card' },
        el('h3', { text: 'เซสชัน' }),
        settingRow(`เข้าสู่ระบบค้างไว้ ${s.sessions} เครื่อง`, 'เซสชันอยู่ได้ 30 วัน และถูกยกเลิกเมื่อเปลี่ยน PIN หรือรหัสผ่าน',
          el('button', { class: 'btn danger', onclick: logoutAll }, icon('logout'), 'ออกจากระบบทุกเครื่อง'))));
  }

  async function setPassword() {
    const v = await formDialog({ title: s.adminPassword ? 'เปลี่ยนรหัสผ่านผู้ดูแล' : 'ตั้งรหัสผ่านผู้ดูแล',
      message: 'หลังบันทึก ทุกเครื่องต้องเข้าสู่ระบบใหม่ด้วยรหัสผ่านนี้ (ใช้แทน PIN)',
      fields: [{ name: 'p1', label: 'รหัสผ่านใหม่ (อย่างน้อย 8 ตัว)', type: 'password', autocomplete: 'new-password' },
        { name: 'p2', label: 'ยืนยันรหัสผ่าน', type: 'password', autocomplete: 'new-password' }], okText: 'บันทึก' });
    if (!v) return;
    if (v.p1.length < 8) return toast('รหัสผ่านต้องยาวอย่างน้อย 8 ตัวอักษร');
    if (v.p1 !== v.p2) return toast('รหัสผ่านทั้งสองช่องไม่ตรงกัน');
    try { await api('admin-password', null, { method: 'POST', body: JSON.stringify({ password: v.p1 }) }); }
    catch (e) { return toast(e.message); }
    toast('บันทึกแล้ว กรุณาเข้าสู่ระบบใหม่');
    setTimeout(() => location.reload(), 1200);
  }
  async function clearPassword() {
    if (!await dialog({ title: 'กลับไปใช้ PIN?', message: 'ทุกเครื่องจะถูกออกจากระบบ', okText: 'ยืนยัน', danger: true })) return;
    try { await api('admin-password', null, { method: 'POST', body: JSON.stringify({ password: '' }) }); } catch (e) { return toast(e.message); }
    location.reload();
  }
  async function setupTotp() {
    let setup;
    try { setup = await api('2fa/setup', null, { method: 'POST' }); } catch (e) { return toast(e.message); }
    const extra = el('div', { class: 'totp-setup' },
      qrImage(setup.uri, 5),
      el('div', null,
        el('p', { text: '1. สแกน QR ด้วยแอปยืนยันตัวตน หรือพิมพ์รหัสนี้:' }),
        el('div', { class: 'mono secret', text: setup.secret.replace(/(.{4})/g, '$1 ').trim() }),
        el('p', { text: '2. ใส่รหัส 6 หลักที่แอปแสดงเพื่อยืนยัน' })));
    const v = await formDialog({ title: 'เปิดยืนยันตัวตน 2 ขั้น', extra,
      fields: [{ name: 'code', label: 'รหัส 6 หลัก', inputmode: 'numeric', autocomplete: 'one-time-code' },
        { name: 'mode', label: 'ขอรหัสเมื่อ', type: 'select', value: 'tunnel', options: [['tunnel', TOTP_MODE_LABEL.tunnel], ['always', TOTP_MODE_LABEL.always]] }],
      okText: 'เปิดใช้งาน' });
    if (!v) return;
    try { await api('2fa/enable', null, { method: 'POST', body: JSON.stringify({ code: v.code, mode: v.mode }) }); }
    catch (e) { return toast(e.message); }
    toast('เปิด 2FA แล้ว — ถ้าทำแอปหาย ปิด 2FA ได้จากแอปบนมือถือ', 4500);
    await loadSession();
    load();
  }
  async function changeTotpMode(mode) {
    try { await api('2fa/enable', null, { method: 'POST', body: JSON.stringify({ mode }) }); toast('บันทึกแล้ว'); } catch (e) { toast(e.message); }
    load();
  }
  async function disableTotp() {
    if (!await dialog({ title: 'ปิด 2FA?', message: 'การเข้าสู่ระบบจะเหลือแค่ PIN/รหัสผ่าน', okText: 'ปิด 2FA', danger: true })) return;
    try { await api('2fa/disable', null, { method: 'POST' }); } catch (e) { return toast(e.message); }
    load();
  }
  async function regenerateCert() {
    if (!await dialog({ title: 'สร้างใบรับรองใหม่?', message: 'ใช้เมื่อเปลี่ยนเครือข่าย/IP เครื่องที่ติดตั้งใบรับรองเดิมไว้ต้องติดตั้งใหม่', okText: 'สร้างใหม่' })) return;
    try { await api('tls/regenerate', null, { method: 'POST' }); toast('สร้างใบรับรองใหม่แล้ว'); } catch (e) { toast(e.message); }
    setTimeout(load, 1500);
  }
  async function logoutAll() {
    if (!await dialog({ title: 'ออกจากระบบทุกเครื่อง?', message: 'รวมถึงเครื่องนี้ด้วย', okText: 'ออกจากระบบ', danger: true })) return;
    try { await saveSettings({ logoutAll: true }); } catch (e) { return toast(e.message); }
    location.reload();
  }
  load();
}

// ---------------------------------------------------------------- Control Panel: users

function UsersTab(pane) {
  let users = [];
  async function load() {
    try { users = (await api('users')).users; } catch (e) { fill(pane, el('div', { class: 'notice', text: e.message })); return; }
    render();
  }
  function render() {
    fill(pane,
      el('div', { class: 'card' },
        el('div', { class: 'pane-head' }, el('h3', { text: 'บัญชีผู้ใช้', style: { margin: 0 } }), el('span', { class: 'grow' }),
          el('button', { class: 'btn primary', onclick: () => edit(null) }, icon('plus'), 'เพิ่มผู้ใช้')),
        el('p', { class: 'muted', text: 'ให้คนในบ้านหรือเพื่อนร่วมงานมีบัญชีของตัวเอง กำหนดได้ว่าแก้ไขไฟล์ได้หรือไม่ และเห็นโฟลเดอร์ไหนบ้าง' }),
        el('table', { class: 'tbl' },
          el('thead', null, el('tr', null, ...['ชื่อ', 'สิทธิ์', 'โฟลเดอร์', ''].map(h => el('th', { text: h })))),
          el('tbody', null,
            el('tr', null, el('td', { class: 'name-cell' }, icon('user'), el('b', { text: 'admin' })), el('td', { text: 'ผู้ดูแล (เจ้าของ)' }), el('td', { class: 'muted', text: 'ทั้งหมด' }), el('td')),
            ...users.map(u => el('tr', null,
              el('td', { class: 'name-cell' }, icon('user'), el('span', { text: u.name })),
              el('td', { text: ROLE_LABEL[u.role] }),
              el('td', { class: 'muted', text: u.folders.length ? u.folders.map(displayPath).join(', ') : 'ทั้งหมด' }),
              el('td', { class: 'actions' },
                el('button', { class: 'icon-btn', title: 'แก้ไข', onclick: () => edit(u) }, icon('rename')),
                el('button', { class: 'icon-btn danger', title: 'ลบผู้ใช้', onclick: () => remove(u) }, icon('trash')))))))),
      el('div', { class: 'card' }, el('h3', { text: 'สิทธิ์แต่ละแบบ' }), el('dl', { class: 'kv' },
        el('dt', { text: ROLE_LABEL.ro }), el('dd', { text: 'ดู ดาวน์โหลด และเล่นสื่อ' }),
        el('dt', { text: ROLE_LABEL.rw }), el('dd', { text: 'เพิ่ม อัปโหลด แก้ไข ลบ แชร์ลิงก์ ใช้ Download/Backup Station' }),
        el('dt', { text: ROLE_LABEL.admin }), el('dd', { text: 'ทุกอย่าง รวมถึงการตั้งค่า Remote Access และเครื่องมือ root' }))));
  }
  async function edit(u) {
    const folders = el('textarea', { rows: 3, placeholder: 'เว้นว่าง = เห็นทุกโฟลเดอร์', spellcheck: 'false' }, u ? u.folders.join('\n') : '');
    const pickBtn = el('button', { class: 'btn small', type: 'button', onclick: async () => {
      const p = await pickFolder(null, 'เลือกโฟลเดอร์ที่อนุญาต');
      if (p) folders.value = (folders.value.trim() ? folders.value.trim() + '\n' : '') + p;
    } }, icon('plus'), 'เลือกโฟลเดอร์');
    const extra = el('label', { class: 'f-row' }, el('span', { text: 'จำกัดเฉพาะโฟลเดอร์ (บรรทัดละโฟลเดอร์)' }), folders, pickBtn);
    const v = await formDialog({
      title: u ? 'แก้ไข ' + u.name : 'เพิ่มผู้ใช้',
      fields: [
        u ? null : { name: 'name', label: 'ชื่อผู้ใช้ (a-z, 0-9, . _ -)', autocomplete: 'off' },
        { name: 'role', label: 'สิทธิ์', type: 'select', value: u ? u.role : 'ro', options: [['ro', ROLE_LABEL.ro], ['rw', ROLE_LABEL.rw], ['admin', ROLE_LABEL.admin]] },
        { name: 'password', label: u ? 'รหัสผ่านใหม่ (เว้นว่าง = ไม่เปลี่ยน)' : 'รหัสผ่าน (อย่างน้อย 8 ตัว)', type: 'password', autocomplete: 'new-password' },
      ].filter(Boolean),
      extra, okText: 'บันทึก' });
    if (!v) return;
    const body = { name: u ? u.name : v.name, role: v.role, password: v.password, folders: folders.value.split('\n').map(x => x.trim()).filter(Boolean) };
    try { users = (await api('users/save', null, { method: 'POST', body: JSON.stringify(body) })).users; toast('บันทึกแล้ว'); }
    catch (e) { return toast(e.message, 4000); }
    Auth.multiUser = true;
    render();
  }
  async function remove(u) {
    if (!await dialog({ title: 'ลบผู้ใช้ ' + u.name + '?', message: 'ไฟล์ไม่ถูกลบ แต่ลิงก์แชร์ของผู้ใช้นี้จะใช้ไม่ได้', okText: 'ลบผู้ใช้', danger: true })) return;
    try { users = (await api('users/delete', { name: u.name }, { method: 'POST' })).users; } catch (e) { toast(e.message); }
    render();
  }
  load();
}

// ---------------------------------------------------------------- Control Panel: services

function ServicesTab(pane) {
  let s = null;
  async function load() {
    try { s = await api('settings'); } catch (e) { fill(pane, el('div', { class: 'notice', text: e.message })); return; }
    render();
  }
  function render() {
    const sys = Stats.last || {};
    const urls = [`http://${sys.host || 'nexshare.local'}:${sys.port || 8080}/dav/`];
    if (sys.ip) urls.push(`http://${sys.ip}:${sys.port || 8080}/dav/`);
    if (s.httpsRunning && sys.ip) urls.push(`https://${sys.ip}:${s.httpsPort}/dav/`);
    fill(pane,
      el('div', { class: 'card' },
        el('h3', { text: 'WebDAV — ต่อมือถือเป็นไดรฟ์เครือข่าย' }),
        settingRow('เปิด WebDAV', 'ใช้กับ Windows Explorer, macOS Finder, ตัวจัดการไฟล์บน Linux, VLC, Infuse ฯลฯ', toggleSwitch(s.webdav, async on => { s = await saveSettings({ webdav: on }); render(); })),
        settingRow('อนุญาตผ่านอินเทอร์เน็ต', 'WebDAV ใช้ชื่อผู้ใช้/รหัสผ่านแบบ Basic และใส่รหัส 2FA ไม่ได้ ผู้ดูแลที่เปิด 2FA จะเข้าผ่าน WebDAV ทางอินเทอร์เน็ตไม่ได้',
          toggleSwitch(s.webdavTunnel, async on => { s = await saveSettings({ webdavTunnel: on }); render(); })),
        s.webdav ? el('dl', { class: 'kv' }, ...urls.flatMap((u, i) => [el('dt', { text: i === 0 ? 'ที่อยู่' : '' }), el('dd', { class: 'mono', text: u })]),
          el('dt', { text: 'ชื่อผู้ใช้' }), el('dd', { text: 'admin + PIN/รหัสผ่าน หรือบัญชีผู้ใช้อื่น' })) : null,
        s.webdav ? el('details', { class: 'howto' }, el('summary', { text: 'วิธีเชื่อมต่อ' }),
          el('p', null, el('b', { text: 'Windows: ' }), 'File Explorer → This PC → Map network drive → ใส่ที่อยู่ด้านบน (Windows ยอมใช้รหัสผ่านกับ http:// เฉพาะเมื่อตั้ง BasicAuthLevel=2 ใน Registry — แนะนำใช้ https:// แทน)'),
          el('p', null, el('b', { text: 'macOS: ' }), 'Finder → Go → Connect to Server (⌘K) → ใส่ที่อยู่ด้านบน'),
          el('p', null, el('b', { text: 'Linux: ' }), 'ตัวจัดการไฟล์ → ใส่ dav://ที่อยู่ หรือ davs:// สำหรับ HTTPS'),
          el('p', null, el('b', { text: 'แอปมือถือ/ทีวี: ' }), 'เพิ่มแหล่งไฟล์แบบ WebDAV ใน VLC, Infuse, Documents, Solid Explorer ฯลฯ')) : null),
      el('div', { class: 'card' },
        el('h3', { text: 'ถังขยะ' }),
        settingRow('ย้ายไฟล์ที่ลบไปถังขยะ', 'กู้คืนไฟล์ที่ลบผิดได้จาก Recycle Bin', toggleSwitch(s.trash, async on => { s = await saveSettings({ trash: on }); State.trash = on; render(); })),
        settingRow('เก็บไว้กี่วัน', 'เกินกำหนดจะถูกลบถาวรอัตโนมัติ',
          el('select', { disabled: !s.trash, onchange: async e => { s = await saveSettings({ trashDays: +e.target.value }); State.trashDays = s.trashDays; } },
            ...[7, 14, 30, 60, 90].map(d => el('option', { value: d, text: d + ' วัน', selected: s.trashDays === d }))))));
  }
  load();
}
