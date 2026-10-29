/* NexShare web desktop — Photos / Video / Audio station with album filter & slideshow. */
'use strict';

// ---------------------------------------------------------------- media apps

function MediaApp(type) {
  return (body, win) => {
    const pane = el('div', { class: 'photos' },
      el('div', { class: 'skeleton-grid' }, ...Array.from({ length: 16 }, () => el('div', { class: 'skeleton skeleton-card' }))));
    body.append(pane);
    let allItems = [];
    let curAlbum = 'all';

    function albumOf(e) {
      const parts = (e.path || '').split('/');
      return parts.length >= 2 ? parts[parts.length - 2] : 'ทั่วไป';
    }

    function renderGallery() {
      pane.replaceChildren();
      if (!allItems.length) { pane.append(el('div', { class: 'empty', text: 'ไม่พบไฟล์' })); return; }
      if (type === 'audio') return renderAudio(allItems);

      const albums = [...new Set(allItems.map(albumOf))].sort();
      const filtered = curAlbum === 'all' ? allItems : allItems.filter(e => albumOf(e) === curAlbum);
      win.setTitle(win.app.name + ' — ' + filtered.length + ' รายการ');

      const bar = el('div', { class: 'ph-bar' },
        el('button', {
          class: 'term-chip' + (curAlbum === 'all' ? ' on' : ''),
          text: `ทั้งหมด (${allItems.length})`,
          onclick: () => { curAlbum = 'all'; renderGallery(); },
        }),
        ...albums.map(a => el('button', {
          class: 'term-chip' + (curAlbum === a ? ' on' : ''),
          text: `${a} (${allItems.filter(e => albumOf(e) === a).length})`,
          onclick: () => { curAlbum = a; renderGallery(); },
        })),
        el('span', { class: 'grow' }),
        type === 'image' && filtered.length > 1 ? el('button', {
          class: 'btn small primary',
          text: '▶ สไลด์โชว์',
          onclick: () => Viewer.open(filtered[0], filtered, { slideshow: true }),
        }) : null
      );
      pane.append(bar);

      let month = null, grid = null;
      for (const e of filtered) {
        const d = new Date(e.mtime);
        const m = d.toLocaleDateString('th-TH', { year: 'numeric', month: 'long' });
        if (m !== month) { month = m; grid = el('div', { class: 'ph-grid' }); pane.append(el('h4', { text: m }), grid); }
        const img = el('img', { loading: 'lazy', decoding: 'async', src: thumbUrl(e.path, 260), alt: '' });
        img.onerror = () => img.replaceWith(fileIcon(kindOf(e)));
        grid.append(el('div', { class: 'ph', title: `${e.name} (${albumOf(e)})`, onclick: () => openEntry(e, filtered) }, img,
          kindOf(e) === 'video' ? el('div', { class: 'play' }, icon('play')) : null));
      }
    }

    (async () => {
      try {
        const r = await api('media', { type });
        allItems = r.entries || [];
        renderGallery();
      } catch (e) { pane.replaceChildren(el('div', { class: 'notice', text: 'โหลดไม่สำเร็จ: ' + e.message })); }
    })();

    function renderAudio(items) {
      win.setTitle(win.app.name + ' — ' + items.length + ' รายการ');
      const tbody = el('tbody');
      items.forEach(e => tbody.append(el('tr', { onclick: () => openEntry(e, items) },
        el('td', { class: 'name' }, el('div', null, fileIcon('audio'), el('span', { text: e.name }))),
        el('td', { class: 'dim', text: fmtSize(e.size), style: { width: '90px' } }))));
      pane.append(el('div', { style: { margin: '12px 2px', color: 'var(--text-2)' }, text: 'กดที่เพลงเพื่อเปิดใน Media Player (เล่นต่อเนื่องทั้งรายการ)' }), el('table', { class: 'files' }, tbody));
    }
    return {};
  };
}
