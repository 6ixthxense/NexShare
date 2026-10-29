/* NexShare web desktop — Resource Monitor, Control Panel, Help. Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- Resource Monitor / Control Panel / Help

const Stats = { hist: { cpu: [], ram: [], up: [], down: [] }, last: null, listeners: new Set() };

function drawChart(canvas, series, max, noLabels) {
  const dpr = devicePixelRatio || 1;
  const w = canvas.clientWidth, h = canvas.clientHeight;
  if (!w || !h) return;
  if (canvas.width !== w * dpr) { canvas.width = w * dpr; canvas.height = h * dpr; }
  const c = canvas.getContext('2d');
  c.setTransform(dpr, 0, 0, dpr, 0, 0);
  c.clearRect(0, 0, w, h);
  const top = max || Math.max(1, ...series.flatMap(s => s.data)) * 1.15;
  const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
  c.strokeStyle = isDark ? '#2a3442' : '#e7ecf2';
  c.lineWidth = 1;
  c.fillStyle = isDark ? '#7d8b99' : '#9aa6b2';
  c.font = '10px sans-serif';
  const steps = noLabels ? 5 : 2;
  for (let i = 0; i <= steps; i++) {
    const y = Math.round(4 + (h - 8) * i / steps) + .5;
    c.beginPath(); c.moveTo(0, y); c.lineTo(w, y); c.stroke();
    if (max && !noLabels) c.fillText(String(Math.round(max * (1 - i / steps))), 2, i === 0 ? y + 10 : y - 2);
  }
  const N = 60;
  for (const s of series) {
    if (!s.data.length) continue;
    c.beginPath();
    s.data.forEach((v, i) => {
      const x = w - (s.data.length - 1 - i) * (w / (N - 1));
      const y = 4 + (h - 8) * (1 - Math.min(v, top) / top);
      i ? c.lineTo(x, y) : c.moveTo(x, y);
    });
    c.strokeStyle = s.color;
    c.lineWidth = 1.8;
    c.stroke();
    c.lineTo(w, h); c.lineTo(w - (s.data.length - 1) * (w / (N - 1)), h); c.closePath();
    c.globalAlpha = .1; c.fillStyle = s.color; c.fill(); c.globalAlpha = 1;
  }
}

function ResourceMonitor(body) {
  const mk = (title) => {
    const val = el('div', { class: 'val', text: '-' });
    const sub = el('div', { class: 'dim', style: { fontSize: '12px', color: 'var(--text-2)', marginBottom: '8px' } });
    const cv = el('canvas', { class: 'chart' });
    return { card: el('div', { class: 'card' }, el('h4', { text: title }), val, sub, cv), val, sub, cv };
  };
  const cpu = mk('CPU'), ram = mk('หน่วยความจำ (RAM)'), net = mk('เครือข่าย'), conn = mk('การเชื่อมต่อ');
  conn.cv.remove();
  body.append(el('div', { class: 'pane' }, el('div', { class: 'rm-grid' }, cpu.card, ram.card, net.card, conn.card)));
  const update = s => {
    const H = Stats.hist;
    cpu.val.textContent = s.cpu >= 0 ? s.cpu.toFixed(0) + '%' : 'N/A';
    cpu.sub.textContent = s.cores + ' คอร์';
    drawChart(cpu.cv, [{ data: H.cpu, color: '#1e6fd9' }], 100);
    const used = s.memTotal - s.memAvail;
    ram.val.textContent = Math.round(used / s.memTotal * 100) + '%';
    ram.sub.textContent = fmtSize(used) + ' / ' + fmtSize(s.memTotal);
    drawChart(ram.cv, [{ data: H.ram, color: '#2fb36b' }], 100);
    net.val.replaceChildren(el('span', { style: { color: 'var(--up)' }, text: '▲ ' + fmtRate(Stats.up) }), ' ', el('span', { style: { color: 'var(--down)', fontSize: '20px' }, text: '▼ ' + fmtRate(Stats.down) }));
    net.sub.textContent = 'รับรวม ' + fmtSize(s.rx) + ' · ส่งรวม ' + fmtSize(s.tx);
    drawChart(net.cv, [{ data: H.up, color: '#2a8cf0' }, { data: H.down, color: '#32b67a' }]);
    conn.val.textContent = s.connections;
    conn.sub.textContent = 'การเชื่อมต่อ HTTP ที่เปิดอยู่ตอนนี้ · แบตเตอรี่ ' + (s.battery >= 0 ? s.battery + '%' : '-') + (s.charging ? ' (กำลังชาร์จ)' : '') + (s.batteryTemp ? ' · ' + s.batteryTemp + '°C' : '');
  };
  Stats.listeners.add(update);
  if (Stats.last) update(Stats.last);
  return { destroy() { Stats.listeners.delete(update); } };
}

function ControlPanel(body) {
  const tabs = [
    ['general', 'ทั่วไป', 'info'],
    ['security', 'ความปลอดภัย', 'shield'],
    ['users', 'ผู้ใช้', 'user'],
    ['services', 'บริการ', 'sync'],
    ['audit', 'บันทึกกิจกรรม', 'list'],
  ].filter(([id]) => id === 'general' || State.admin);
  const bar = el('div', { class: 'tabs' });
  const pane = el('div', { class: 'pane' });
  body.append(el('div', { class: 'cp' }, bar, pane));
  let timer = null, cur = null;
  function select(id) {
    cur = id;
    clearInterval(timer);
    fill(bar, ...tabs.map(([t, label, ico]) => el('button', { class: 'tab' + (t === id ? ' on' : ''), onclick: () => select(t) }, icon(ico), label)));
    pane.replaceChildren();
    if (id === 'general') { renderGeneral(); timer = setInterval(renderGeneral, 5000); }
    else if (id === 'security') SecurityTab(pane);
    else if (id === 'users') UsersTab(pane);
    else if (id === 'services') ServicesTab(pane);
    else if (id === 'audit') AuditTab(pane);
  }
  const renderGeneral = () => {
    const s = Stats.last || {};
    const urls = [
      [`http://${s.host || 'nexshare.local'}:${s.port || 8080}`, 'Local domain'],
      s.ip ? [`http://${s.ip}:${s.port}`, 'ผ่าน IP'] : null,
      s.ip && s.httpsPort ? [`https://${s.ip}:${s.httpsPort}`, 'HTTPS'] : null,
      s.tailscaleIp ? [`http://${s.tailscaleIp}:${s.port || 8080}`, 'Tailscale'] : null,
      s.tailscaleIp && s.httpsPort ? [`https://${s.tailscaleIp}:${s.httpsPort}`, 'Tailscale (HTTPS)'] : null,
    ].filter(Boolean);
    fill(pane,
      el('h2', { text: 'แผงควบคุม' }),
      State.roots.length && !State.roots[0].readable ? el('div', { class: 'notice', style: { margin: '0 0 12px' }, text: 'ยังไม่ได้ให้สิทธิ์เข้าถึงไฟล์ — เปิดแอป NexShare บนมือถือแล้วกด "อนุญาตให้เข้าถึงไฟล์ทั้งหมด"' }) : null,
      Auth.pinRequired === false ? el('div', { class: 'notice', style: { margin: '0 0 12px' }, text: 'ปิดการใช้ PIN อยู่ ใครก็ตามใน Wi-Fi เดียวกันเข้าถึงไฟล์ได้ เปิด PIN ได้ในแอปบนมือถือ' }) : null,
      el('div', { class: 'card' }, el('h3', { text: 'อุปกรณ์', style: { marginTop: 0 } }), el('dl', { class: 'kv' },
        el('dt', { text: 'ชื่อเซิร์ฟเวอร์' }), el('dd', { text: s.name || '-' }),
        el('dt', { text: 'รุ่น' }), el('dd', { text: s.model || '-' }),
        el('dt', { text: 'Android' }), el('dd', { text: s.android ? `${s.android} (API ${s.sdk})` : '-' }),
        el('dt', { text: 'เปิดเครื่องมาแล้ว' }), el('dd', { text: s.uptime ? fmtUptime(s.uptime) : '-' }))),
      el('div', { class: 'card' }, el('h3', { text: 'การเข้าถึงจากเครื่องอื่น', style: { marginTop: 0 } }), el('dl', { class: 'kv' },
        ...urls.flatMap(([u, label]) => [el('dt', { text: label }), el('dd', { class: 'mono', text: u })]),
        State.webdav ? [el('dt', { text: 'WebDAV' }), el('dd', { class: 'mono', text: urls[urls.length - 1][0] + '/dav/' })] : null,
        el('dt', { text: 'ความปลอดภัย' }), el('dd', { text: !Auth.pinRequired ? 'ไม่ต้องเข้าสู่ระบบ' : Auth.adminPassword ? 'รหัสผ่าน' + (Auth.otp ? ' + 2FA' : '') : 'PIN' + (Auth.otp ? ' + 2FA' : '') }),
        el('dt', { text: 'บัญชีของคุณ' }), el('dd', { text: (State.user === 'admin' ? 'ผู้ดูแล (เจ้าของมือถือ)' : State.user) + ' · ' + ROLE_LABEL[State.role] }))),
      el('div', { class: 'card' }, el('h3', { text: 'ที่เก็บข้อมูล', style: { marginTop: 0 } }),
        ...State.roots.map(r => el('div', { class: 'meter' },
          el('div', { class: 'row' }, el('span', { text: r.name }), el('b', { text: r.total ? `${fmtSize(r.total - r.free)} / ${fmtSize(r.total)}` : '-' })),
          el('div', { class: 'bar' }, el('i', { style: { width: r.total ? ((r.total - r.free) / r.total * 100) + '%' : 0 } }))))),
      Auth.pinRequired ? el('button', { class: 'btn', onclick: () => Auth.logout() }, icon('logout'), 'ออกจากระบบ') : null);
  };
  select('general');
  return {
    handle(arg) { if (arg && arg.tab) select(arg.tab); },
    destroy() { clearInterval(timer); },
  };
}

function Help(body) {
  body.append(el('div', { class: 'pane help', html: `
    <h2>วิธีใช้ NexShare</h2>
    <ol>
      <li>เปิดแอป <b>NexShare</b> บนมือถือ แล้วกด <b>เริ่มแชร์</b> (ครั้งแรกต้องอนุญาตให้เข้าถึงไฟล์ทั้งหมด)</li>
      <li>ให้คอมหรือมือถืออีกเครื่องต่อ Wi-Fi เดียวกัน (หรือต่อ Hotspot / Tailscale ของมือถือเครื่องนี้)</li>
      <li>เปิดเบราว์เซอร์ไปที่ <b class="mono">http://nexshare.local:8080</b> ถ้าเข้าไม่ได้ให้ใช้ลิงก์เลข IP ที่แสดงในแอป</li>
      <li>ใส่ PIN 6 หลักที่แสดงในแอป</li>
    </ol>
    <h3>File Station</h3>
    <ul>
      <li>ดับเบิลคลิกเพื่อเปิดโฟลเดอร์หรือดูไฟล์ (บนจอสัมผัสแตะครั้งเดียว)</li>
      <li>ลากไฟล์หรือโฟลเดอร์จากคอมมาวางในหน้าต่างเพื่ออัปโหลด (รองรับอัปโหลดต่อจากจุดเดิมอัตโนมัติเมื่อเน็ตหลุด)</li>
      <li>เลือกหลายไฟล์ด้วย Ctrl/Shift หรือติ๊กช่องหน้าไฟล์ แล้วกด ดาวน์โหลด (หลายไฟล์จะรวมเป็น ZIP)</li>
      <li>คลิกขวาหรือกดปุ่ม ⋯ เพื่อ เปลี่ยนชื่อ / คัดลอก / ตัด / วาง / ลบ · ปุ่มลัด: Delete, F2, Ctrl+A, Ctrl+C, Ctrl+X, Ctrl+V</li>
      <li>ไฟล์ที่ลบจะไปอยู่ใน <b>Recycle Bin</b> กู้คืนได้ · กด Shift+Delete เพื่อลบถาวร</li>
      <li>เลือกไฟล์แล้วกด <b>สร้างลิงก์แชร์</b> หรือเลือกโฟลเดอร์แล้วกด <b>สร้างลิงก์ขอไฟล์</b> เพื่อรับไฟล์จากคนอื่นโดยไม่ต้องให้รหัสบัญชี</li>
    </ul>
    <h3>ใช้มือถือเป็นไดรฟ์ (WebDAV)</h3>
    <ul>
      <li>Windows: This PC → Map network drive · macOS: Finder → Connect to Server (⌘K)</li>
      <li>ที่อยู่ <b class="mono">http://nexshare.local:8080/dav/</b> ผู้ใช้ <b>admin</b> กับ PIN/รหัสผ่าน หรือบัญชีผู้ใช้อื่น</li>
    </ul>
    <h3>ความปลอดภัยและบันทึกกิจกรรม</h3>
    <ul>
      <li>แผงควบคุม → ความปลอดภัย: ตั้งรหัสผ่านแทน PIN, เปิด 2FA, เปิด HTTPS (รองรับทั้ง LAN IP และ Tailscale IP), ออกจากระบบทุกเครื่อง</li>
      <li>แผงควบคุม → ผู้ใช้: สร้างบัญชีให้คนอื่น กำหนดสิทธิ์ โฟลเดอร์ที่อนุญาต และตั้งโควตาพื้นที่เก็บข้อมูลสูงสุด</li>
      <li>แผงควบคุม → บันทึกกิจกรรม: ตรวจสอบประวัติการเข้าสู่ระบบ อัปโหลด ดาวน์โหลด ลบไฟล์ และการแชร์</li>
    </ul>` }));
}
