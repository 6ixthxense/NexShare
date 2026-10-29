/* NexShare web desktop — desktop widgets, shared clipboard, and real-time event polling. */
'use strict';

// ---------------------------------------------------------------- widgets & polling

let lastEventSeq = null;

function setBar(id, pct) {
  const b = $('#' + id);
  b.style.width = Math.max(0, Math.min(100, pct)) + '%';
  b.parentElement.classList.toggle('warn', pct >= 75 && pct < 90);
  b.parentElement.classList.toggle('crit', pct >= 90);
}

async function checkLiveEvents(seq) {
  if (typeof seq !== 'number') return;
  if (lastEventSeq === null) {
    lastEventSeq = seq;
    return;
  }
  if (seq <= lastEventSeq) return;
  const prev = lastEventSeq;
  lastEventSeq = seq;
  try {
    const r = await api('events', { since: prev });
    const events = r.events || [];
    for (const ev of events) {
      if (ev.type === 'request_upload') {
        const msg = `มีไฟล์ส่งเข้ามาใหม่ผ่านลิงก์ขอไฟล์: ${ev.detail}`;
        notify(msg);
        toast('📥 ' + msg, 4000);
      } else if (ev.type === 'clipboard') {
        loadClipboardWidget();
      }
      window.dispatchEvent(new CustomEvent('nexshare:fs-change', { detail: ev }));
    }
  } catch { /* ignore */ }
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
    const tsLbl = $('#hTsLabel'), tsVal = $('#hTs');
    if (tsLbl && tsVal) {
      const hasTs = !!s.tailscaleIp;
      tsLbl.classList.toggle('hidden', !hasTs);
      tsVal.classList.toggle('hidden', !hasTs);
      if (hasTs) tsVal.textContent = s.tailscaleIp;
    }
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
    if (typeof s.eventSeq === 'number') checkLiveEvents(s.eventSeq);
    Stats.listeners.forEach(f => f(s));
  } catch { /* server unreachable or logged out */ }
}

let clipLatest = '';

async function loadClipboardWidget() {
  const listEl = $('#clipList');
  if (!listEl) return;
  try {
    const r = await api('clipboard');
    clipLatest = r.current || '';
    const items = r.items || [];
    listEl.replaceChildren();
    if (!items.length) {
      listEl.append(el('div', { class: 'muted small', text: 'ยังไม่มีข้อความที่ส่งไว้' }));
      return;
    }
    for (const it of items.slice(0, 4)) {
      const row = el('div', {
        class: 'clip-item',
        title: 'คลิกเพื่อคัดลอก',
        onclick: async () => {
          if (await copyText(it.text)) toast('คัดลอกข้อความแล้ว');
        },
      }, el('span', { text: it.text }));
      listEl.append(row);
    }
  } catch { /* ignore */ }
}

function initClipboardWidget() {
  const inEl = $('#clipIn');
  const sendBtn = $('#clipSendBtn');
  const copyBtn = $('#clipCopyBtn');
  if (!inEl || !sendBtn || !copyBtn) return;
  if (!State.canWrite) {
    inEl.classList.add('hidden');
    sendBtn.classList.add('hidden');
  }
  sendBtn.onclick = async () => {
    const text = inEl.value.trim();
    if (!text) { toast('พิมพ์หรือวางข้อความก่อนส่ง'); return; }
    try {
      await api('clipboard', null, { method: 'POST', body: JSON.stringify({ text }) });
      inEl.value = '';
      toast('ส่งเข้าคลิปบอร์ดมือถือแล้ว');
      loadClipboardWidget();
    } catch (e) { toast('ส่งไม่สำเร็จ: ' + e.message); }
  };
  inEl.addEventListener('keydown', e => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault();
      sendBtn.click();
    }
  });
  copyBtn.onclick = async () => {
    await loadClipboardWidget();
    if (!clipLatest) { toast('ยังไม่มีข้อความในคลิปบอร์ด'); return; }
    if (await copyText(clipLatest)) toast('คัดลอกข้อความล่าสุดแล้ว');
  };
  loadClipboardWidget();
}

function initWidgets() {
  const panel = $('#widgets'), btn = $('#widgetsBtn'), pin = $('#wPin');
  const hiddenW = new Set(store.get('hiddenWidgets', []));
  const allWidgets = [['wHealth', 'System Health'], ['wRes', 'Resource Monitor'], ['wStorage', 'Storage'], ['wClip', 'Quick Clipboard']];
  const apply = () => {
    const show = store.get('widgets', true);
    panel.classList.toggle('hidden', !show);
    btn.classList.toggle('active', show);
    const pinned = store.get('pinned', false);
    pin.classList.toggle('on', pinned);
    panel.style.zIndex = pinned ? 4500 : 50;
    for (const [id] of allWidgets) {
      const node = $('#' + id);
      if (node) node.classList.toggle('hidden', hiddenW.has(id));
    }
  };
  btn.onclick = () => { store.set('widgets', !store.get('widgets', true)); apply(); };
  $('#wMin').onclick = () => { store.set('widgets', false); apply(); };
  pin.onclick = () => { store.set('pinned', !store.get('pinned', false)); apply(); };
  $('#wAdd').onclick = e => menuAt(e.currentTarget, allWidgets.map(([id, label]) => ({
    label: (hiddenW.has(id) ? '☐ ' : '☑ ') + label,
    run: () => { hiddenW.has(id) ? hiddenW.delete(id) : hiddenW.add(id); store.set('hiddenWidgets', [...hiddenW]); apply(); },
  })));
  initClipboardWidget();
  apply();
}
