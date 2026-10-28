/* NexShare web desktop — Recycle Bin. Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- Recycle Bin

function RecycleBin(body) {
  const pane = el('div', { class: 'pane' });
  body.append(pane);
  const st = { items: [], sel: new Set(), enabled: true, days: 30 };

  async function load() {
    try {
      const r = await api('trash/list');
      st.items = r.items.sort((a, b) => b.deleted - a.deleted);
      st.enabled = r.enabled;
      st.days = r.days;
      st.sel.clear();
    } catch (e) { fill(pane, el('div', { class: 'notice', text: e.message })); return; }
    render();
  }

  function render() {
    const total = st.items.reduce((n, i) => n + (i.size || 0), 0);
    const canWrite = State.canWrite;
    const selIds = [...st.sel];
    fill(pane,
      el('div', { class: 'pane-head' },
        el('h2', { text: 'ถังขยะ' }),
        el('span', { class: 'grow' }),
        canWrite ? el('button', { class: 'btn', disabled: !selIds.length, onclick: () => restore(selIds) }, icon('undo'), 'กู้คืน') : null,
        canWrite ? el('button', { class: 'btn danger', disabled: !selIds.length, onclick: () => purge(selIds) }, icon('x'), 'ลบถาวร') : null,
        canWrite ? el('button', { class: 'btn', disabled: !st.items.length, onclick: emptyAll }, icon('trash'), 'ล้างถังขยะ') : null),
      el('p', { class: 'muted', text: st.enabled
        ? `ไฟล์ที่ลบจะอยู่ที่นี่ ${st.days} วันก่อนถูกลบถาวรอัตโนมัติ · ${st.items.length} รายการ · ${fmtSize(total)}`
        : 'ถังขยะปิดอยู่ ไฟล์ที่ลบจะหายถาวรทันที (เปิดได้ในแผงควบคุม)' }),
      st.items.length ? el('table', { class: 'tbl' },
        el('thead', null, el('tr', null,
          el('th', null, el('input', { type: 'checkbox', checked: selIds.length === st.items.length, onchange: e => { st.sel = new Set(e.target.checked ? st.items.map(i => i.id) : []); render(); } })),
          ...['ชื่อ', 'ตำแหน่งเดิม', 'ลบเมื่อ', 'ขนาด'].map(h => el('th', { text: h })))),
        el('tbody', null, ...st.items.map(i => el('tr', { class: st.sel.has(i.id) ? 'sel' : null },
          el('td', null, el('input', { type: 'checkbox', checked: st.sel.has(i.id), onchange: e => { e.target.checked ? st.sel.add(i.id) : st.sel.delete(i.id); render(); } })),
          el('td', { class: 'name-cell' }, fileIcon(i.dir ? 'folder' : kindOf(i)), el('span', { text: i.name })),
          el('td', { class: 'muted', text: displayPath(i.orig.slice(0, i.orig.lastIndexOf('/')) || '/') }),
          el('td', { text: fmtDate(i.deleted) + (i.by && i.by !== State.user ? ' · ' + i.by : '') }),
          el('td', { text: fmtSize(i.size || 0) })))))
        : el('div', { class: 'empty', text: 'ถังขยะว่าง' }));
  }

  async function restore(ids) {
    try {
      const r = await api('trash/restore', { id: ids }, { method: 'POST' });
      toast(`กู้คืนแล้ว ${r.restored.length} รายการ`);
      if (r.restored.length === 1) notify('กู้คืน ' + displayPath(r.restored[0]));
    } catch (e) { toast('กู้คืนไม่สำเร็จ: ' + e.message); }
    load();
  }
  async function purge(ids) {
    if (!await dialog({ title: `ลบถาวร ${ids.length} รายการ?`, message: 'กู้คืนไม่ได้อีก', okText: 'ลบถาวร', danger: true })) return;
    try { await api('trash/purge', { id: ids }, { method: 'POST' }); } catch (e) { toast(e.message); }
    load();
  }
  async function emptyAll() {
    if (!await dialog({ title: 'ล้างถังขยะ?', message: `ลบถาวรทั้งหมด ${st.items.length} รายการ`, okText: 'ล้างถังขยะ', danger: true })) return;
    try { const r = await api('trash/empty', null, { method: 'POST' }); toast(`ลบถาวรแล้ว ${r.purged} รายการ`); } catch (e) { toast(e.message); }
    load();
  }

  load();
  return { handle() { load(); } };
}
