/* NexShare web desktop — Remote Access (Cloudflare Tunnel). Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- Remote Access (Cloudflare Tunnel)

const TUN_STATE = { off: 'ปิดอยู่', starting: 'กำลังเชื่อมต่อ…', connected: 'เชื่อมต่อแล้ว', error: 'ผิดพลาด' };

function RemoteAccess(body, win) {
  const pane = el('div', { class: 'pane' });
  body.append(pane);
  let timer, lastState = null, logOpen = false;

  async function act(path, payload) {
    try {
      const r = await api('tunnel/' + path, null, payload ? { method: 'POST', body: JSON.stringify(payload) } : { method: 'POST' });
      render(r);
    } catch (e) { toast('ไม่สำเร็จ: ' + e.message, 4000); refresh(); }
  }

  function render(s) {
    lastState = s;
    const connected = s.state === 'connected';
    const dotColor = { off: '#9aa6b2', starting: '#f0a020', connected: '#2fb36b', error: '#e5484d' }[s.state];
    fill(pane,
      el('h2', null, 'เข้าถึงจากอินเทอร์เน็ต (Cloudflare Tunnel)'),
      !s.supported ? el('div', { class: 'notice', text: 'มือถือเครื่องนี้ไม่รองรับ (ไม่มีไฟล์ cloudflared สำหรับ CPU นี้) — ใช้ได้เฉพาะในวง Wi-Fi เดียวกัน' }) : null,
      el('div', { class: 'card' },
        el('div', { style: { display: 'flex', alignItems: 'center', gap: '10px', marginBottom: connected || s.state === 'error' ? '12px' : 0 } },
          el('span', { style: { width: '12px', height: '12px', borderRadius: '50%', background: dotColor, flex: 'none' } }),
          el('b', { text: TUN_STATE[s.state] || s.state }),
          s.mode && s.mode !== 'off' ? el('span', { class: 'st-pill', text: s.mode === 'named' ? 'โดเมนตัวเอง' : 'ลิงก์ชั่วคราว' }) : null),
        connected && s.url ? el('div', null,
          el('div', { style: { fontSize: '12px', color: 'var(--text-3)', marginBottom: '4px' }, text: 'ลิงก์สาธารณะ (เปิดได้จากทุกที่):' }),
          el('div', { style: { display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' } },
            el('a', { class: 'mono', href: s.url, target: '_blank', style: { fontSize: '16px', color: 'var(--accent)', wordBreak: 'break-all' }, text: s.url }),
            el('button', { class: 'btn', onclick: () => copy(s.url) }, icon('link'), 'คัดลอก'))) : null,
        connected && s.mode === 'named' && !s.url ? el('div', { class: 'mono', style: { color: 'var(--text-2)' }, text: 'เชื่อมต่อแล้ว — เปิดด้วยโดเมนที่คุณตั้งไว้ใน Cloudflare' }) : null,
        s.state === 'error' ? el('div', { class: 'notice', style: { margin: '0' }, text: s.error || 'เชื่อมต่อไม่สำเร็จ' }) : null),

      s.state === 'off' || s.state === 'error'
        ? el('div', { class: 'card' },
            el('h3', { style: { marginTop: 0 }, text: 'เปิด Tunnel' }),
            el('div', { class: 'notice', style: { margin: '0 0 12px' }, text: 'เมื่อเปิด เครื่องจะเข้าถึงได้จากอินเทอร์เน็ต ระบบจะบังคับเปิด PIN ให้อัตโนมัติ อย่าปิด PIN ขณะเปิด tunnel' }),
            el('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap', marginBottom: '14px' } },
              el('button', { class: 'btn primary', disabled: !s.supported, onclick: () => act('start', { mode: 'quick' }) }, icon('link'), 'ลิงก์ชั่วคราว (trycloudflare.com)')),
            el('h3', { text: 'หรือใช้โดเมนตัวเอง (Named Tunnel)' }),
            el('div', { class: 'hint', style: { marginBottom: '8px' }, html: 'ไปที่ Cloudflare Zero Trust → Networks → Tunnels → สร้าง tunnel → คัดลอก token (ขึ้นต้นด้วย <span class="mono">eyJ…</span>) มาวางด้านล่าง แล้วตั้ง Public hostname ชี้มาที่ <span class="mono">http://localhost:' + (Stats.last ? Stats.last.port : 8080) + '</span>' }),
            (() => {
              const t = el('input', { placeholder: 'วาง tunnel token ที่นี่', value: s.hasToken ? '••••••••••••••••' : '', style: { fontFamily: 'monospace' }, onfocus: e => { if (e.target.value.startsWith('•')) e.target.value = ''; } });
              return el('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap' } }, t,
                el('button', { class: 'btn', disabled: !s.supported, onclick: () => act('start', { mode: 'named', token: t.value.startsWith('•') ? undefined : t.value }) }, icon('link'), 'เชื่อมต่อด้วย token'));
            })())
        : el('div', { class: 'card' },
            el('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap' } },
              el('button', { class: 'btn danger', onclick: () => act('stop') }, icon('x'), 'ปิด Tunnel'),
              el('button', { class: 'btn', onclick: () => act('start', { mode: s.mode === 'named' ? 'named' : 'quick' }) }, icon('refresh'), 'เชื่อมต่อใหม่'))),

      el('div', { class: 'card' },
        el('div', { style: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', cursor: 'pointer' }, onclick: () => { logOpen = !logOpen; render(lastState); } },
          el('h3', { style: { margin: 0 }, text: 'บันทึกการทำงาน (log)' }), icon(logOpen ? 'min' : 'chev')),
        logOpen ? el('pre', { class: 'mono', style: { marginTop: '10px', maxHeight: '220px', overflow: 'auto', background: '#0d1117', color: '#c9d1d9', padding: '12px', borderRadius: '8px', fontSize: '12px', whiteSpace: 'pre-wrap', userSelect: 'text' }, text: s.log || '(ยังไม่มี)' }) : null));
  }
  function copy(url) {
    if (navigator.clipboard && isSecureContext) navigator.clipboard.writeText(url).then(() => toast('คัดลอกลิงก์แล้ว'));
    else dialog({ title: 'ลิงก์สาธารณะ', input: url, cancel: false });
  }
  async function refresh() {
    try { render(await api('tunnel/status')); } catch { /* offline */ }
  }
  refresh();
  timer = setInterval(() => { if (!lastState || lastState.state === 'starting' || logOpen) refresh(); }, 2000);
  return { destroy() { clearInterval(timer); } };
}
