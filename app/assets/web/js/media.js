/* NexShare web desktop — Photos / Video / Audio station. Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- media apps

function MediaApp(type) {
  return (body, win) => {
    const pane = el('div', { class: 'photos' }, el('div', { class: 'empty', text: 'กำลังโหลด…' }));
    body.append(pane);
    (async () => {
      try {
        const r = await api('media', { type });
        const items = r.entries;
        pane.replaceChildren();
        if (!items.length) { pane.append(el('div', { class: 'empty', text: 'ไม่พบไฟล์' })); return; }
        win.setTitle(win.app.name + ' — ' + items.length + ' รายการ');
        if (type === 'audio') return renderAudio(items);
        let month = null, grid = null;
        for (const e of items) {
          const d = new Date(e.mtime);
          const m = d.toLocaleDateString('th-TH', { year: 'numeric', month: 'long' });
          if (m !== month) { month = m; grid = el('div', { class: 'ph-grid' }); pane.append(el('h4', { text: m }), grid); }
          const img = el('img', { loading: 'lazy', src: thumbUrl(e.path, 260), alt: '' });
          img.onerror = () => img.replaceWith(fileIcon(kindOf(e)));
          grid.append(el('div', { class: 'ph', title: e.name, onclick: () => openEntry(e, items) }, img,
            kindOf(e) === 'video' ? el('div', { class: 'play' }, icon('play')) : null));
        }
      } catch (e) { pane.replaceChildren(el('div', { class: 'notice', text: 'โหลดไม่สำเร็จ: ' + e.message })); }
    })();
    function renderAudio(items) {
      const tbody = el('tbody');
      items.forEach(e => tbody.append(el('tr', { onclick: () => openEntry(e, items) },
        el('td', { class: 'name' }, el('div', null, fileIcon('audio'), el('span', { text: e.name }))),
        el('td', { class: 'dim', text: fmtSize(e.size), style: { width: '90px' } }))));
      pane.append(el('div', { style: { margin: '12px 2px', color: 'var(--text-2)' }, text: 'กดที่เพลงเพื่อเปิดใน Media Player (เล่นต่อเนื่องทั้งรายการ)' }), el('table', { class: 'files' }, tbody));
    }
    return {};
  };
}
