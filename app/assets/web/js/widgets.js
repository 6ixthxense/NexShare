/* NexShare web desktop — desktop widgets and polling. Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- widgets & polling

function setBar(id, pct) {
  const b = $('#' + id);
  b.style.width = Math.max(0, Math.min(100, pct)) + '%';
  b.parentElement.classList.toggle('warn', pct >= 75 && pct < 90);
  b.parentElement.classList.toggle('crit', pct >= 90);
}

async function poll() {
  try {
    const s = await api('system');
    const H = Stats.hist;
    if (Stats.last) {
      const dt = Math.max(.5, (s.time - Stats.last.time) / 1000);
      Stats.up = Math.max(0, (s.tx - Stats.last.tx) / dt);
      Stats.down = Math.max(0, (s.rx - Stats.last.rx) / dt);
    } else { Stats.up = Stats.down = 0; }
    const ram = (s.memTotal - s.memAvail) / s.memTotal * 100;
    const push = (a, v) => { a.push(v); if (a.length > 60) a.shift(); };
    push(H.cpu, Math.max(0, s.cpu));
    push(H.ram, ram);
    push(H.up, Stats.up);
    push(H.down, Stats.down);
    Stats.last = s;

    $('#serverName').textContent = s.name;
    $('#hName').textContent = s.name;
    $('#hIp').textContent = s.ip || '-';
    $('#hHost').textContent = s.host + ':' + s.port;
    $('#hUp').textContent = fmtUptime(s.uptime);
    const warn = (s.battery >= 0 && s.battery <= 15 && !s.charging) || s.diskFree / s.diskTotal < .05;
    $('#hState').textContent = warn ? 'Warning' : 'Good';
    $('#hState').style.color = warn ? '#e8742a' : '';
    $('#hSub').textContent = warn ? (s.diskFree / s.diskTotal < .05 ? 'พื้นที่เก็บข้อมูลใกล้เต็ม' : 'แบตเตอรี่ใกล้หมด') : 'มือถือของคุณทำงานปกติ';
    $('#rCpu').textContent = s.cpu >= 0 ? s.cpu.toFixed(0) + '%' : 'N/A';
    setBar('rCpuBar', Math.max(0, s.cpu));
    $('#rRam').textContent = ram.toFixed(0) + '%';
    setBar('rRamBar', ram);
    $('#rUp').textContent = fmtRate(Stats.up);
    $('#rDown').textContent = fmtRate(Stats.down);
    drawChart($('#rChart'), [{ data: H.cpu, color: '#1e9ad6' }, { data: H.ram, color: '#2fb36b' }], 100, true);
    const used = s.diskTotal - s.diskFree;
    $('#sPct').textContent = (used / s.diskTotal * 100).toFixed(0) + '%';
    setBar('sBar', used / s.diskTotal * 100);
    $('#sUsed').textContent = fmtSize(used) + ' / ' + fmtSize(s.diskTotal);
    $('#sFree').textContent = fmtSize(s.diskFree);
    $('#sBat').textContent = s.battery >= 0 ? s.battery + '%' + (s.charging ? ' ⚡ กำลังชาร์จ' : '') : '-';
    Stats.listeners.forEach(f => f(s));
  } catch { /* server unreachable or logged out */ }
}

function initWidgets() {
  const panel = $('#widgets'), btn = $('#widgetsBtn'), pin = $('#wPin');
  const hiddenW = new Set(store.get('hiddenWidgets', []));
  const apply = () => {
    const show = store.get('widgets', true);
    panel.classList.toggle('hidden', !show);
    btn.classList.toggle('active', show);
    const pinned = store.get('pinned', false);
    pin.classList.toggle('on', pinned);
    panel.style.zIndex = pinned ? 4500 : 50;
    for (const id of ['wHealth', 'wRes', 'wStorage']) $('#' + id).classList.toggle('hidden', hiddenW.has(id));
  };
  btn.onclick = () => { store.set('widgets', !store.get('widgets', true)); apply(); };
  $('#wMin').onclick = () => { store.set('widgets', false); apply(); };
  pin.onclick = () => { store.set('pinned', !store.get('pinned', false)); apply(); };
  $('#wAdd').onclick = e => menuAt(e.currentTarget, [['wHealth', 'System Health'], ['wRes', 'Resource Monitor'], ['wStorage', 'Storage']].map(([id, label]) => ({
    label: (hiddenW.has(id) ? '☐ ' : '☑ ') + label,
    run: () => { hiddenW.has(id) ? hiddenW.delete(id) : hiddenW.add(id); store.set('hiddenWidgets', [...hiddenW]); apply(); },
  })));
  apply();
}
