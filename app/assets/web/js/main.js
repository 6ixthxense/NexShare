/* NexShare web desktop — auth & boot. Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- auth & boot

const Auth = {
  pinRequired: null,
  adminPassword: false,
  otp: false,
  multiUser: false,
  tunnelOn: false,
  show() {
    $('#login').classList.remove('hidden');
    Auth.renderForm();
    setTimeout(() => ($('#loginUser').value || !Auth.multiUser ? $('#pin') : $('#loginUser')).focus(), 50);
  },
  /** Owner logs in with the PIN (or admin password); other accounts with username + password. */
  renderForm() {
    const user = $('#loginUser').value.trim();
    const owner = !user || user.toLowerCase() === 'admin';
    const pinMode = owner && !Auth.adminPassword;
    const pin = $('#pin');
    pin.placeholder = pinMode ? '••••••' : 'รหัสผ่าน';
    pin.inputMode = pinMode ? 'numeric' : 'text';
    pin.maxLength = pinMode ? 6 : 128;
    pin.autocomplete = pinMode ? 'off' : 'current-password';
    $('#otpRow').classList.toggle('hidden', !(owner && Auth.otp));
    $('#loginServer').textContent = (Auth.server ? Auth.server + ' · ' : '')
      + (pinMode ? 'ใส่ PIN ที่แสดงในแอปบนมือถือ' : owner ? 'ใส่รหัสผ่านผู้ดูแล' : 'ใส่รหัสผ่านของบัญชีนี้');
  },
  async logout() {
    try { await api('logout', null, { method: 'POST' }); } catch { /* ignore */ }
    location.reload();
  },
};

$('#loginUser').addEventListener('input', () => Auth.renderForm());
$('#otherUserBtn').addEventListener('click', () => {
  $('#userRow').classList.remove('hidden');
  $('#otherUserBtn').classList.add('hidden');
  $('#loginUser').focus();
});

$('#loginForm').addEventListener('submit', async e => {
  e.preventDefault();
  const err = $('#loginErr');
  err.textContent = '';
  const body = { user: $('#loginUser').value.trim(), password: $('#pin').value, otp: $('#otp').value.trim() };
  try {
    await api('login', null, { method: 'POST', body: JSON.stringify(body) });
    $('#login').classList.add('hidden');
    $('#pin').value = '';
    $('#otp').value = '';
    start();
  } catch (x) {
    if (x.code === 'otp_required') {
      Auth.otp = true;
      Auth.renderForm();
      err.textContent = 'ใส่รหัส 6 หลักจากแอปยืนยันตัวตน';
      $('#otp').focus();
      return;
    }
    const owner = !body.user || body.user.toLowerCase() === 'admin';
    err.textContent = x.status === 429 ? 'ใส่ผิดหลายครั้ง กรุณารอสักครู่แล้วลองใหม่'
      : owner && !Auth.adminPassword ? (Auth.otp ? 'PIN หรือรหัส 2FA ไม่ถูกต้อง' : 'PIN ไม่ถูกต้อง')
      : 'ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง';
    $('#pin').select();
  }
});

const APPS = {
  filestation: { id: 'filestation', name: 'File Station', ico: 'filestation', w: 980, h: 600, mount: FileStation },
  photos: { id: 'photos', name: 'Photos', ico: 'photos', w: 900, h: 600, mount: MediaApp('image') },
  videos: { id: 'videos', name: 'Video Station', ico: 'videos', w: 900, h: 600, mount: MediaApp('video') },
  music: { id: 'music', name: 'Audio Station', ico: 'music', w: 640, h: 560, mount: MediaApp('audio') },
  monitor: { id: 'monitor', name: 'Resource Monitor', ico: 'monitor', w: 760, h: 520, mount: ResourceMonitor },
  control: { id: 'control', name: 'Control Panel', ico: 'control', w: 720, h: 640, mount: ControlPanel },
  help: { id: 'help', name: 'NexShare Help', ico: 'help', w: 640, h: 560, mount: Help },
  download: { id: 'download', name: 'Download Station', ico: 'download', w: 960, h: 560, mount: DownloadStation, write: true },
  player: { id: 'player', name: 'Media Player', ico: 'player', w: 1000, h: 600, mount: MediaPlayer },
  packagecenter: { id: 'packagecenter', name: 'Package Center', ico: 'packagecenter', w: 760, h: 580, mount: PackageCenter, admin: true },
  terminal: { id: 'terminal', name: 'Terminal', ico: 'terminal', w: 820, h: 540, mount: Terminal, root: true, admin: true },
  screen: { id: 'screen', name: 'Screen Mirror', ico: 'screen', w: 520, h: 640, mount: ScreenMirror, root: true, admin: true },
  wallpaper: { id: 'wallpaper', name: 'Wallpaper', ico: 'photos', w: 520, h: 640, mount: Wallpaper, admin: true },
  remote: { id: 'remote', name: 'Remote Access', ico: 'remote', w: 660, h: 620, mount: RemoteAccess, admin: true },
  shares: { id: 'shares', name: 'Shared Links', ico: 'shares', w: 760, h: 520, mount: SharedLinks },
  trash: { id: 'trash', name: 'Recycle Bin', ico: 'trash', w: 820, h: 540, mount: RecycleBin },
  backup: { id: 'backup', name: 'Backup Station', ico: 'backup', w: 720, h: 600, mount: BackupStation, write: true },
};

$('#mainMenuBtn').onclick = e => openPop && openPop.btn === e.currentTarget ? closePop() : appGridPopover(e.currentTarget, Object.keys(APPS).filter(appAllowed), 'ค้นหาแอป');
$('#searchBtn').onclick = () => openApp('filestation', { search: true });
$('#infoBtn').onclick = () => openApp('control');
$('#userBtn').onclick = e => menuAt(e.currentTarget, [
  { label: (State.user === 'admin' ? 'ผู้ดูแล' : State.user) + ' · ' + ROLE_LABEL[State.role], disabled: true, run: () => {} },
  { label: Stats.last ? Stats.last.model : 'NexShare', ico: 'info', run: () => openApp('control') },
  Auth.pinRequired ? { label: 'ออกจากระบบ', ico: 'logout', run: () => Auth.logout() } : null,
]);
$('#notifBtn').onclick = e => {
  const items = State.notes.length ? State.notes.slice(0, 8).map(n => ({ label: n.text, run: () => {} })) : [{ label: 'ยังไม่มีการแจ้งเตือน', disabled: true, run: () => {} }];
  menuAt(e.currentTarget, items);
};
document.addEventListener('dragover', e => e.preventDefault());
document.addEventListener('drop', e => { if (!e.target.closest('.fs-list')) { e.preventDefault(); toast('เปิด File Station แล้ววางไฟล์ในหน้าต่างเพื่ออัปโหลด'); } });
addEventListener('resize', () => { renderDesktop(); });

const ROLE_LABEL = { admin: 'ผู้ดูแล', rw: 'อ่าน/เขียน', ro: 'ดูอย่างเดียว' };

/** Reads /api/session into Auth/State. Returns the session object. */
async function loadSession() {
  const s = await api('session');
  Auth.pinRequired = s.pinRequired;
  Auth.adminPassword = !!s.adminPassword;
  Auth.otp = !!s.otp;
  Auth.multiUser = !!s.multiUser;
  Auth.server = s.server;
  State.flavor = s.flavor || 'normal';
  State.isRoot = !!s.root;
  State.secure = !!s.secure;
  State.httpsPort = s.httpsPort || 0;
  State.webdav = !!s.webdav;
  State.trash = s.trash !== false;
  State.trashDays = s.trashDays || 30;
  if (s.auth) {
    State.user = s.user;
    State.role = s.role;
    State.admin = !!s.admin;
    State.canWrite = !!s.canWrite;
    State.sudo = !!s.sudo;
  }
  $('#serverName').textContent = s.auth && s.user !== 'admin' ? s.user : 'NexShare';
  if (Auth.multiUser) { $('#userRow').classList.remove('hidden'); $('#otherUserBtn').classList.add('hidden'); }
  return s;
}

let started = false;
async function start() {
  try {
    await loadSession();
    const r = await api('roots');
    State.roots = r.roots;
    State.folders = r.folders || [];
  } catch (e) { if (e.status === 401) return; }
  renderDesktop();
  Taskbar.render();
  if (started) return;
  started = true;
  initWidgets();
  poll();
  setInterval(poll, 2000);
  if (State.roots.length && !State.roots[0].readable) notify('แอปบนมือถือยังไม่ได้รับสิทธิ์เข้าถึงไฟล์');
  if (innerWidth > 760) openApp('filestation');
}

/** Installable web app (needs HTTPS or localhost; skipped silently elsewhere). */
function registerPwa() {
  if (!('serviceWorker' in navigator) || !window.isSecureContext) return;
  navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch(() => { /* optional */ });
}

(async function boot() {
  for (const n of document.querySelectorAll('[data-ico]')) n.innerHTML = APP_ICO[n.dataset.ico];
  renderDesktop();
  Taskbar.render();
  registerPwa();
  try {
    const s = await loadSession();
    if (!s.auth) { Auth.show(); return; }
    start();
  } catch { toast('เชื่อมต่อมือถือไม่ได้'); }
})();
