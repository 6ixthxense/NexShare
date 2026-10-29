/* NexShare web desktop — file viewer (image, video, audio, PDF, Markdown, Code, CSV, Office) and resumable uploader. */
'use strict';

// ---------------------------------------------------------------- document preview helpers

const CODE_EXTS = new Set([
  'js', 'mjs', 'ts', 'tsx', 'jsx', 'py', 'java', 'c', 'cpp', 'h', 'cs', 'go', 'rs', 'rb', 'php',
  'sh', 'ps1', 'sql', 'html', 'htm', 'css', 'json', 'xml', 'yml', 'yaml', 'toml', 'ini', 'conf',
  'gradle', 'kt', 'swift', 'dart',
]);

function escHtml(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function highlightCodeLine(line) {
  let s = escHtml(line);
  s = s.replace(/(&quot;.*?&quot;|'[^']*'|`[^`]*`)/g, '<span class="hl-str">$1</span>');
  s = s.replace(/\b(function|return|const|let|var|if|else|for|while|class|import|from|export|async|await|try|catch|finally|public|private|protected|static|final|void|int|long|boolean|new|null|true|false|def|elif|None|True|False)\b/g, '<span class="hl-kw">$1</span>');
  s = s.replace(/(\/\/.*$|#.*$)/g, '<span class="hl-cm">$1</span>');
  return s;
}

function openTextEditor(entry, initialText, onSaved) {
  const ta = el('textarea', { class: 'doc-editor', spellcheck: 'false' });
  ta.value = initialText;
  const status = el('span', { class: 'muted', text: `${initialText.split('\n').length} บรรทัด · ${fmtSize(new Blob([initialText]).size)}` });
  ta.addEventListener('input', () => {
    status.textContent = `${ta.value.split('\n').length} บรรทัด · ${fmtSize(new Blob([ta.value]).size)} (แก้ไขแล้ว)`;
  });
  const save = async () => {
    saveBtn.disabled = true;
    saveBtn.textContent = 'กำลังบันทึก…';
    try {
      const updated = await api('write', { path: entry.path }, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain; charset=utf-8' },
        body: ta.value,
      });
      entry.size = updated.size ?? new Blob([ta.value]).size;
      entry.mtime = updated.mtime ?? Date.now();
      toast('บันทึกไฟล์แล้ว');
      window.dispatchEvent(new CustomEvent('nexshare:fs-change', { detail: { type: 'write', path: entry.path } }));
      if (onSaved) onSaved(ta.value);
    } catch (e) {
      toast('บันทึกไม่สำเร็จ: ' + e.message);
      saveBtn.disabled = false;
      saveBtn.textContent = 'บันทึก (Ctrl+S)';
    }
  };
  ta.addEventListener('keydown', e => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
      e.preventDefault();
      save();
    } else if (e.key === 'Tab') {
      e.preventDefault();
      const s = ta.selectionStart, end = ta.selectionEnd;
      ta.value = ta.value.slice(0, s) + '  ' + ta.value.slice(end);
      ta.selectionStart = ta.selectionEnd = s + 2;
    }
  });
  const saveBtn = el('button', { class: 'btn sm primary save-file-btn', text: 'บันทึก (Ctrl+S)', onclick: save });
  const cancelBtn = el('button', { class: 'btn sm', text: 'ยกเลิก', onclick: () => onSaved && onSaved(initialText) });
  setTimeout(() => ta.focus(), 30);
  return el('div', { class: 'doc-preview' },
    el('div', { class: 'doc-bar' },
      el('strong', { text: 'แก้ไข: ' + entry.name }),
      status,
      el('span', { class: 'grow' }),
      cancelBtn,
      saveBtn
    ),
    ta
  );
}

function renderCodeView(text, ext, entry, onReplace) {
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  const table = el('table', { class: 'code-table' });
  const maxLines = Math.min(lines.length, 5000);
  for (let i = 0; i < maxLines; i++) {
    const codeTd = el('td', { class: 'code-line', html: highlightCodeLine(lines[i]) || ' ' });
    table.append(el('tr', null, el('td', { class: 'code-ln', text: i + 1 }), codeTd));
  }
  const editBtn = State.canWrite && entry ? el('button', {
    class: 'btn sm edit-file-btn', text: 'แก้ไข',
    onclick: () => onReplace && onReplace(openTextEditor(entry, text, newText => onReplace(renderCodeView(newText, ext, entry, onReplace)))),
  }) : null;
  return el('div', { class: 'doc-preview code-preview' },
    el('div', { class: 'doc-bar' },
      el('span', { class: 'muted', text: `${ext.toUpperCase()} · ${lines.length} บรรทัด` }),
      el('span', { class: 'grow' }),
      editBtn,
      el('button', { class: 'btn sm', text: 'คัดลอกโค้ด', onclick: async () => { await copyText(text); toast('คัดลอกโค้ดแล้ว'); } })
    ),
    el('div', { class: 'code-scroll' }, table)
  );
}

function renderMarkdownHtml(md) {
  const lines = md.replace(/\r\n/g, '\n').split('\n');
  let out = '';
  let inCode = false, inList = false, inTable = false;
  const inline = s => escHtml(s)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\*([^*]+)\*/g, '<em>$1</em>')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.startsWith('```')) {
      if (inList) { out += '</ul>'; inList = false; }
      if (inTable) { out += '</tbody></table>'; inTable = false; }
      out += inCode ? '</code></pre>' : '<pre class="md-code"><code>';
      inCode = !inCode;
      continue;
    }
    if (inCode) {
      out += escHtml(line) + '\n';
      continue;
    }
    if (/^\|(.+)\|\s*$/.test(line)) {
      if (inList) { out += '</ul>'; inList = false; }
      const cells = line.trim().slice(1, -1).split('|').map(c => c.trim());
      if (/^[\s|:-]+$/.test(line.replace(/\|/g, ''))) continue;
      if (!inTable) {
        out += '<table class="md-table"><thead><tr>' + cells.map(c => `<th>${inline(c)}</th>`).join('') + '</tr></thead><tbody>';
        inTable = true;
      } else {
        out += '<tr>' + cells.map(c => `<td>${inline(c)}</td>`).join('') + '</tr>';
      }
      continue;
    } else if (inTable) {
      out += '</tbody></table>';
      inTable = false;
    }
    const hm = line.match(/^(#{1,6})\s+(.*)$/);
    if (hm) {
      if (inList) { out += '</ul>'; inList = false; }
      const lvl = hm[1].length;
      out += `<h${lvl}>${inline(hm[2])}</h${lvl}>`;
      continue;
    }
    if (/^[-*+]\s+/.test(line)) {
      if (!inList) { out += '<ul>'; inList = true; }
      out += `<li>${inline(line.replace(/^[-*+]\s+/, ''))}</li>`;
      continue;
    } else if (inList) {
      out += '</ul>';
      inList = false;
    }
    if (/^>\s?/.test(line)) {
      out += `<blockquote>${inline(line.replace(/^>\s?/, ''))}</blockquote>`;
      continue;
    }
    if (/^---+$/.test(line.trim())) {
      out += '<hr>';
      continue;
    }
    if (!line.trim()) {
      out += '<div class="md-gap"></div>';
    } else {
      out += `<p>${inline(line)}</p>`;
    }
  }
  if (inCode) out += '</code></pre>';
  if (inList) out += '</ul>';
  if (inTable) out += '</tbody></table>';
  return out;
}

function renderMarkdownView(text, entry, onReplace) {
  const content = el('div', { class: 'md-body', html: renderMarkdownHtml(text) });
  const raw = el('pre', { class: 'md-raw hidden', text });
  let showRaw = false;
  const toggleBtn = el('button', {
    class: 'btn sm', text: 'ดูโค้ดต้นฉบับ',
    onclick: () => {
      showRaw = !showRaw;
      content.classList.toggle('hidden', showRaw);
      raw.classList.toggle('hidden', !showRaw);
      toggleBtn.textContent = showRaw ? 'ดูหน้าพรีวิว' : 'ดูโค้ดต้นฉบับ';
    },
  });
  const editBtn = State.canWrite && entry ? el('button', {
    class: 'btn sm primary edit-file-btn', text: 'แก้ไข',
    onclick: () => onReplace && onReplace(openTextEditor(entry, text, newText => onReplace(renderMarkdownView(newText, entry, onReplace)))),
  }) : null;
  return el('div', { class: 'doc-preview' },
    el('div', { class: 'doc-bar' }, el('span', { class: 'muted', text: 'Markdown' }), el('span', { class: 'grow' }), editBtn, toggleBtn),
    content, raw
  );
}

function renderCsvView(text, sep = ',', entry = null, onReplace = null) {
  const rows = text.replace(/\r\n/g, '\n').split('\n').filter(r => r.length > 0).slice(0, 500);
  const tbl = el('table', { class: 'md-table' });
  rows.forEach((r, idx) => {
    const cols = r.split(sep);
    const tr = el('tr', null, ...cols.map(c => el(idx === 0 ? 'th' : 'td', { text: c.replace(/^"|"$/g, '') })));
    tbl.append(tr);
  });
  const editBtn = State.canWrite && entry ? el('button', {
    class: 'btn sm edit-file-btn', text: 'แก้ไขข้อความ',
    onclick: () => onReplace && onReplace(openTextEditor(entry, text, newText => onReplace(renderCsvView(newText, sep, entry, onReplace)))),
  }) : null;
  return el('div', { class: 'doc-preview' },
    el('div', { class: 'doc-bar' }, el('span', { class: 'muted', text: `ตารางข้อมูล (${rows.length} แถว)` }), el('span', { class: 'grow' }), editBtn),
    el('div', { class: 'doc-scroll' }, tbl)
  );
}

function renderPlainTextView(text, entry, onReplace) {
  const pre = el('pre', { text });
  if (!State.canWrite || !entry) return pre;
  const editBtn = el('button', {
    class: 'btn sm primary edit-file-btn', text: 'แก้ไข',
    onclick: () => onReplace && onReplace(openTextEditor(entry, text, newText => onReplace(renderPlainTextView(newText, entry, onReplace)))),
  });
  return el('div', { class: 'doc-preview' },
    el('div', { class: 'doc-bar' },
      el('span', { class: 'muted', text: `ข้อความ (${text.split('\n').length} บรรทัด)` }),
      el('span', { class: 'grow' }),
      editBtn,
      el('button', { class: 'btn sm', text: 'คัดลอก', onclick: async () => { await copyText(text); toast('คัดลอกแล้ว'); } })
    ),
    pre
  );
}

/** Reads entries from a ZIP buffer (used for .docx, .xlsx, .pptx). */
async function unzipEntries(buf) {
  const bytes = new Uint8Array(buf);
  const view = new DataView(buf);
  let eocd = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
    if (view.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('ไฟล์ไม่ได้อยู่ในรูปแบบ ZIP/Office ที่รองรับ');
  const count = view.getUint16(eocd + 10, true);
  let dirOff = view.getUint32(eocd + 16, true);
  const dec = new TextDecoder();
  const entries = new Map();
  for (let i = 0; i < count && dirOff + 46 <= bytes.length; i++) {
    if (view.getUint32(dirOff, true) !== 0x02014b50) break;
    const method = view.getUint16(dirOff + 10, true);
    const compSize = view.getUint32(dirOff + 20, true);
    const nameLen = view.getUint16(dirOff + 28, true);
    const extraLen = view.getUint16(dirOff + 30, true);
    const commentLen = view.getUint16(dirOff + 32, true);
    const localOff = view.getUint32(dirOff + 42, true);
    const name = dec.decode(bytes.subarray(dirOff + 46, dirOff + 46 + nameLen));
    dirOff += 46 + nameLen + extraLen + commentLen;
    entries.set(name, { method, compSize, localOff });
  }
  async function readText(name) {
    const info = entries.get(name);
    if (!info) return null;
    const lo = info.localOff;
    const nLen = view.getUint16(lo + 26, true);
    const eLen = view.getUint16(lo + 28, true);
    const start = lo + 30 + nLen + eLen;
    const slice = bytes.subarray(start, start + info.compSize);
    if (info.method === 0) return dec.decode(slice);
    if (info.method === 8 && typeof DecompressionStream !== 'undefined') {
      const ds = new DecompressionStream('deflate-raw');
      const writer = ds.writable.getWriter();
      writer.write(slice);
      writer.close();
      const ab = await new Response(ds.readable).arrayBuffer();
      return dec.decode(ab);
    }
    return null;
  }
  return { entries, readText };
}

async function renderOfficePreview(url, entry) {
  const ext = extOf(entry.name);
  const r = await fetch(url);
  if (!r.ok) throw new Error('HTTP ' + r.status);
  const buf = await r.arrayBuffer();
  const zip = await unzipEntries(buf);
  const parser = new DOMParser();
  const body = el('div', { class: 'md-body' });

  if (ext === 'docx') {
    const xmlStr = await zip.readText('word/document.xml');
    if (!xmlStr) throw new Error('ไม่พบเนื้อหาในไฟล์ Word (.docx)');
    const doc = parser.parseFromString(xmlStr, 'application/xml');
    const children = doc.getElementsByTagName('w:body')[0]?.childNodes || [];
    for (const node of children) {
      if (node.nodeName === 'w:p') {
        const texts = Array.from(node.getElementsByTagName('w:t')).map(t => t.textContent).join('');
        if (texts.trim()) body.append(el('p', { text: texts }));
      } else if (node.nodeName === 'w:tbl') {
        const tbl = el('table', { class: 'md-table' });
        for (const trNode of Array.from(node.getElementsByTagName('w:tr'))) {
          const tr = el('tr');
          for (const tcNode of Array.from(trNode.getElementsByTagName('w:tc'))) {
            const cellText = Array.from(tcNode.getElementsByTagName('w:t')).map(t => t.textContent).join(' ');
            tr.append(el('td', { text: cellText }));
          }
          tbl.append(tr);
        }
        body.append(tbl);
      }
    }
  } else if (ext === 'xlsx') {
    const sharedXml = await zip.readText('xl/sharedStrings.xml');
    const shared = [];
    if (sharedXml) {
      const sDoc = parser.parseFromString(sharedXml, 'application/xml');
      for (const si of Array.from(sDoc.getElementsByTagName('si'))) {
        shared.push(Array.from(si.getElementsByTagName('t')).map(t => t.textContent).join(''));
      }
    }
    const sheetNames = Array.from(zip.entries.keys()).filter(k => /^xl\/worksheets\/sheet\d+\.xml$/.test(k)).sort();
    for (const sName of sheetNames) {
      const sheetXml = await zip.readText(sName);
      if (!sheetXml) continue;
      const sDoc = parser.parseFromString(sheetXml, 'application/xml');
      const tbl = el('table', { class: 'md-table' });
      const rows = Array.from(sDoc.getElementsByTagName('row')).slice(0, 300);
      rows.forEach((rNode, rIdx) => {
        const tr = el('tr');
        for (const cNode of Array.from(rNode.getElementsByTagName('c'))) {
          const tAttr = cNode.getAttribute('t');
          const vText = cNode.getElementsByTagName('v')[0]?.textContent || '';
          const inlineText = cNode.getElementsByTagName('t')[0]?.textContent || '';
          const val = tAttr === 's' ? (shared[parseInt(vText, 10)] ?? vText) : (inlineText || vText);
          tr.append(el(rIdx === 0 ? 'th' : 'td', { text: val }));
        }
        tbl.append(tr);
      });
      body.append(el('h3', { text: sName.split('/').pop().replace('.xml', '').toUpperCase() }), el('div', { class: 'doc-scroll' }, tbl));
    }
  } else if (ext === 'pptx') {
    const slideNames = Array.from(zip.entries.keys()).filter(k => /^ppt\/slides\/slide\d+\.xml$/.test(k)).sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
    let idx = 1;
    for (const sName of slideNames) {
      const sXml = await zip.readText(sName);
      if (!sXml) continue;
      const sDoc = parser.parseFromString(sXml, 'application/xml');
      const paras = Array.from(sDoc.getElementsByTagName('a:p'))
        .map(p => Array.from(p.getElementsByTagName('a:t')).map(t => t.textContent).join(''))
        .filter(s => s.trim().length > 0);
      body.append(el('div', { class: 'card', style: { marginBottom: '12px' } },
        el('strong', { text: `สไลด์ ${idx++}` }),
        ...paras.map(p => el('p', { text: p, style: { margin: '6px 0' } }))
      ));
    }
  } else {
    throw new Error('ไฟล์รุ่นเก่า (.doc/.xls/.ppt) แนะนำให้ดาวน์โหลดเพื่อเปิดในแอป Office');
  }

  if (!body.childNodes.length) body.append(el('p', { class: 'muted', text: '(เอกสารไม่มีข้อความ)' }));
  return el('div', { class: 'doc-preview' },
    el('div', { class: 'doc-bar' },
      el('span', { class: 'muted', text: `พรีวิวเอกสาร Office (${entry.name})` }),
      el('a', { class: 'btn sm', href: fileUrl(entry.path, true), style: { textDecoration: 'none' }, text: 'ดาวน์โหลดไฟล์จริง' })
    ),
    body
  );
}

// ---------------------------------------------------------------- viewer

const Viewer = {
  deg: 0,
  slideTimer: null,
  open(entry, list, opts = {}) {
    this.list = list && list.length ? list : [entry];
    this.i = Math.max(0, this.list.findIndex(e => e.path === entry.path));
    if (!this.el) this.build();
    this.stopSlideshow();
    this.el.classList.remove('hidden');
    this.show();
    if (opts.slideshow) this.toggleSlideshow(true);
  },
  openEditor(entry, initialText = '') {
    this.list = [entry];
    this.i = 0;
    this.cur = entry;
    if (!this.el) this.build();
    this.stopSlideshow();
    this.el.classList.remove('hidden');
    this.title.textContent = `${entry.name}  ·  แก้ไขไฟล์`;
    this.prev.classList.add('hidden');
    this.next.classList.add('hidden');
    for (const b of [this.rotateBtn, this.slideBtn, this.exifBtn]) b.classList.add('hidden');
    this.exifPane.classList.add('hidden');
    const replace = node => this.body.replaceChildren(node);
    replace(openTextEditor(entry, initialText, () => this.show()));
  },
  build() {
    this.title = el('div', { class: 'v-title' });
    this.body = el('div', { class: 'v-body' });
    this.prev = el('button', { class: 'v-nav prev', onclick: () => this.step(-1) }, icon('back'));
    this.next = el('button', { class: 'v-nav next', onclick: () => this.step(1) }, icon('fwd'));
    this.rotateBtn = el('button', { title: 'หมุนรูป 90° (R)', class: 'hidden', text: '↻', style: { fontSize: '18px' }, onclick: () => this.rotate() });
    this.slideBtn = el('button', { title: 'สไลด์โชว์', class: 'hidden', onclick: () => this.toggleSlideshow() }, icon('play'));
    this.exifBtn = el('button', { title: 'ข้อมูลภาพ (EXIF)', class: 'hidden', onclick: () => this.toggleExif() }, icon('info'));
    this.exifPane = el('div', { class: 'exif-pane hidden' });
    this.el = el('div', { id: 'viewer', class: 'hidden' },
      el('div', { class: 'v-top' }, this.title,
        this.slideBtn,
        this.rotateBtn,
        this.exifBtn,
        el('button', { title: 'เปิดในแท็บใหม่', onclick: () => window.open(fileUrl(this.cur.path), '_blank') }, icon('open')),
        el('button', { title: 'ดาวน์โหลด', onclick: () => { location.href = fileUrl(this.cur.path, true); } }, icon('download')),
        el('button', { title: 'ปิด', onclick: () => this.close() }, icon('x'))),
      el('div', { style: { flex: 1, minHeight: 0, position: 'relative', display: 'flex' } }, this.body, this.exifPane, this.prev, this.next));
    this.body.style.flex = '1';
    document.body.append(this.el);
    addEventListener('keydown', e => {
      if (this.el.classList.contains('hidden') || $('#modal')) return;
      if (e.target && (e.target.tagName === 'TEXTAREA' || e.target.tagName === 'INPUT')) {
        if (e.key === 'Escape') this.close();
        return;
      }
      if (e.key === 'Escape') this.close();
      if (e.key === 'ArrowLeft') this.step(-1);
      if (e.key === 'ArrowRight') this.step(1);
      if (e.key.toLowerCase() === 'r' && kindOf(this.cur) === 'image') this.rotate();
    });
    let sx = null;
    this.body.addEventListener('touchstart', e => { sx = e.touches[0].clientX; }, { passive: true });
    this.body.addEventListener('touchend', e => {
      if (sx == null) return;
      const dx = e.changedTouches[0].clientX - sx;
      if (Math.abs(dx) > 60 && kindOf(this.cur) === 'image') this.step(dx < 0 ? 1 : -1);
      sx = null;
    });
  },
  rotate() {
    this.deg = (this.deg + 90) % 360;
    const img = this.body.querySelector('img');
    if (img) img.style.transform = this.deg ? `rotate(${this.deg}deg)` : '';
  },
  toggleSlideshow(force) {
    const on = force !== undefined ? force : !this.slideTimer;
    this.stopSlideshow();
    if (on) {
      this.slideBtn.replaceChildren(icon('pause'));
      this.slideTimer = setInterval(() => {
        if (this.i + 1 < this.list.length) this.step(1);
        else { this.i = 0; this.show(); }
      }, 3500);
      toast('เริ่มเล่นสไลด์โชว์ (ทุก 3.5 วินาที)');
    }
  },
  stopSlideshow() {
    if (this.slideTimer) {
      clearInterval(this.slideTimer);
      this.slideTimer = null;
    }
    if (this.slideBtn) this.slideBtn.replaceChildren(icon('play'));
  },
  async toggleExif() {
    if (!this.exifPane.classList.contains('hidden')) {
      this.exifPane.classList.add('hidden');
      return;
    }
    this.exifPane.classList.remove('hidden');
    this.exifPane.replaceChildren(el('div', { class: 'muted', text: 'กำลังอ่านข้อมูล EXIF…' }));
    try {
      const info = await api('exif', { path: this.cur.path });
      const rows = [
        ['ชื่อไฟล์', info.name],
        ['ขนาดไฟล์', fmtSize(info.size)],
        info.width && info.height ? ['ความละเอียด', `${info.width} × ${info.height} px`] : null,
        info.dateTaken || info.dateTime ? ['วันที่ถ่าย', info.dateTaken || info.dateTime] : ['แก้ไขล่าสุด', fmtDate(info.mtime)],
        info.make || info.model ? ['กล้อง / อุปกรณ์', [info.make, info.model].filter(Boolean).join(' ')] : null,
        info.aperture ? ['รูรับแสง', 'f/' + info.aperture] : null,
        info.exposure ? ['ความเร็วชัตเตอร์', info.exposure + ' s'] : null,
        info.iso ? ['ISO', info.iso] : null,
        info.focalLength ? ['ระยะเลนส์', info.focalLength] : null,
      ].filter(Boolean);
      const dl = el('dl', { class: 'kv' });
      for (const [k, v] of rows) dl.append(el('dt', { text: k }), el('dd', { text: v }));
      if (typeof info.lat === 'number' && typeof info.lon === 'number') {
        const coord = `${info.lat.toFixed(5)}, ${info.lon.toFixed(5)}`;
        dl.append(el('dt', { text: 'พิกัด GPS' }),
          el('dd', null, el('a', { href: `https://maps.google.com/?q=${info.lat},${info.lon}`, target: '_blank', rel: 'noopener', text: coord + ' (ดูแผนที่)' })));
      }
      this.exifPane.replaceChildren(el('h4', { text: 'ข้อมูลรูปภาพ (EXIF)' }), dl);
    } catch (e) {
      this.exifPane.replaceChildren(el('div', { class: 'muted', text: 'ไม่สามารถอ่านข้อมูล EXIF ได้: ' + e.message }));
    }
  },
  step(d) {
    const n = this.i + d;
    if (n < 0 || n >= this.list.length) return;
    this.i = n;
    this.show();
  },
  async show() {
    this.deg = 0;
    const e = this.cur = this.list[this.i];
    this.title.textContent = `${e.name}  ·  ${fmtSize(e.size)}` + (this.list.length > 1 ? `  ·  ${this.i + 1}/${this.list.length}` : '');
    this.prev.classList.toggle('hidden', this.i === 0);
    this.next.classList.toggle('hidden', this.i >= this.list.length - 1);
    const url = fileUrl(e.path);
    const k = kindOf(e);
    const ext = extOf(e.name);
    const isImg = k === 'image';
    this.rotateBtn.classList.toggle('hidden', !isImg);
    this.slideBtn.classList.toggle('hidden', !isImg || this.list.length <= 1);
    this.exifBtn.classList.toggle('hidden', !isImg);
    if (!isImg) this.exifPane.classList.add('hidden');
    else if (!this.exifPane.classList.contains('hidden')) {
      this.exifPane.classList.add('hidden');
      this.toggleExif();
    }
    this.body.replaceChildren();
    if (k === 'image') {
      this.body.append(el('img', { src: url, alt: e.name }));
    } else if (k === 'video') {
      this.body.append(el('video', { src: url, controls: true, autoplay: true, playsinline: true }));
    } else if (k === 'audio') {
      this.body.append(el('div', { class: 'v-info' }, fileIcon('audio'), el('audio', { src: url, controls: true, autoplay: true })));
    } else if (k === 'pdf') {
      this.body.append(el('iframe', { class: 'pdf-frame', src: url + '#navpanes=0', title: e.name }));
    } else if ((k === 'doc' || k === 'sheet' || k === 'slide') && ['docx', 'xlsx', 'pptx'].includes(ext) && e.size < 30 * 1024 * 1024) {
      const loading = el('div', { class: 'v-info', text: 'กำลังอ่านเอกสาร Office…' });
      this.body.append(loading);
      try {
        const view = await renderOfficePreview(url, e);
        this.body.replaceChildren(view);
      } catch (err) {
        this.body.replaceChildren(el('div', { class: 'v-info' }, fileIcon(k), el('div', { text: err.message || 'ไม่สามารถแสดงตัวอย่างได้' }),
          el('a', { class: 'btn primary', href: fileUrl(e.path, true), style: { textDecoration: 'none', marginTop: '12px' } }, icon('download'), 'ดาวน์โหลด')));
      }
    } else if (k === 'text' && e.size < 4 * 1024 * 1024) {
      const pre = el('pre', { text: 'กำลังโหลด…' });
      this.body.append(pre);
      const replace = node => this.body.replaceChildren(node);
      try {
        const r = await fetch(url, { headers: { Range: 'bytes=0-1048575' } });
        const text = await r.text();
        if (ext === 'md' || ext === 'markdown') {
          replace(renderMarkdownView(text, e, replace));
        } else if (ext === 'csv' || ext === 'tsv') {
          replace(renderCsvView(text, ext === 'tsv' ? '\t' : ',', e, replace));
        } else if (CODE_EXTS.has(ext)) {
          replace(renderCodeView(text, ext, e, replace));
        } else {
          replace(renderPlainTextView(text, e, replace));
        }
      } catch {
        pre.textContent = 'โหลดไม่สำเร็จ';
      }
    } else {
      this.body.append(el('div', { class: 'v-info' }, fileIcon(k), el('div', { text: 'ไม่มีตัวอย่างสำหรับไฟล์ชนิดนี้' }),
        el('div', { style: { margin: '6px 0 16px' }, text: `${fmtSize(e.size)} · แก้ไข ${fmtDate(e.mtime)}` }),
        el('a', { class: 'btn primary', href: fileUrl(e.path, true), style: { textDecoration: 'none' } }, icon('download'), 'ดาวน์โหลด')));
    }
  },
  close() {
    this.stopSlideshow();
    this.el.classList.add('hidden');
    this.exifPane.classList.add('hidden');
    this.body.replaceChildren();
  },
};

// ---------------------------------------------------------------- resumable uploads

const CHUNK_SIZE = 4 * 1024 * 1024; // 4 MB chunks for resumable 5G/Wi-Fi uploads

const Uploader = {
  queue: [], active: 0, listeners: new Set(),
  add(items, dest) {
    if (!items.length || !dest) return;
    $('#uploads').classList.remove('hidden');
    for (const it of items) {
      const bar = el('i');
      const s = el('span', { class: 's', text: 'รอคิว' });
      const node = el('div', { class: 'u-item' }, el('div', { class: 'r1' }, el('span', { class: 'n', text: (it.rel ? it.rel + '/' : '') + it.file.name, title: it.file.name }), s), el('div', { class: 'bar' }, bar));
      $('#uList').prepend(node);
      this.queue.push({ ...it, dest, node, bar, s });
    }
    this.pump();
    this.badge();
  },
  pump() {
    while (this.active < 2 && this.queue.length) this.send(this.queue.shift());
  },
  async queryOffset(job) {
    try {
      const st = await api('upload/status', {
        path: job.dest,
        name: job.file.name,
        rel: job.rel || null,
        size: job.file.size,
      });
      return Math.max(0, st.offset || 0);
    } catch {
      return 0;
    }
  },
  sendChunk(job, offset, total, t0) {
    return new Promise((resolve, reject) => {
      const end = Math.min(total, offset + CHUNK_SIZE);
      const blob = total === 0 ? job.file : job.file.slice(offset, end);
      const xhr = new XMLHttpRequest();
      xhr.open('POST', '/api/upload' + qs({
        path: job.dest,
        name: job.file.name,
        rel: job.rel || null,
        mtime: job.file.lastModified || null,
        overwrite: job.overwrite ? 1 : null,
        resumable: 1,
        offset,
        total,
      }));
      xhr.upload.onprogress = e => {
        if (!e.lengthComputable) return;
        const doneBytes = offset + e.loaded;
        const pct = total > 0 ? Math.min(100, (doneBytes / total) * 100) : 100;
        job.bar.style.width = pct + '%';
        const secs = (performance.now() - t0) / 1000;
        job.s.textContent = `${pct.toFixed(0)}% · ${fmtRate(doneBytes / Math.max(secs, 0.1))}`;
      };
      xhr.onload = () => {
        let j = {};
        try { j = JSON.parse(xhr.responseText || '{}'); } catch { /* ignore */ }
        if (xhr.status === 200) {
          resolve({ ok: true, nextOffset: j.offset != null ? j.offset : end, done: !j.partial });
        } else if (xhr.status === 409 && j.offset != null) {
          resolve({ ok: true, nextOffset: j.offset, done: false });
        } else {
          reject({ status: xhr.status, error: j.error || ('HTTP ' + xhr.status), retryable: xhr.status >= 500 || xhr.status === 400 });
        }
      };
      xhr.onerror = () => reject({ status: 0, error: 'network_drop', retryable: true });
      xhr.send(blob);
    });
  },
  async send(job) {
    this.active++;
    this.badge();
    const total = job.file.size;
    const t0 = performance.now();
    let offset = total > 0 ? await this.queryOffset(job) : 0;
    if (offset > 0 && offset < total) {
      job.s.textContent = `ส่งต่อจาก ${fmtSize(offset)}…`;
    }
    let retries = 0;
    let ok = false;
    while (true) {
      try {
        const res = await this.sendChunk(job, offset, total, t0);
        retries = 0;
        offset = res.nextOffset;
        if (res.done || offset >= total) {
          ok = true;
          break;
        }
      } catch (err) {
        if (err.status === 401) { Auth.show(); break; }
        if (err.status === 403 || err.status === 507) {
          job.s.textContent = err.status === 507 ? 'เกินโควตาพื้นที่' : 'ไม่มีสิทธิ์เขียน';
          break;
        }
        if (err.retryable && retries < 4) {
          retries++;
          job.s.textContent = `เน็ตสะดุด กำลังส่งต่อ (${retries}/4)…`;
          await new Promise(r => setTimeout(r, 1200 * retries));
          offset = await this.queryOffset(job);
          continue;
        }
        job.s.textContent = err.status === 0 ? 'การเชื่อมต่อหลุด (กดส่งซ้ำเพื่อต่อจากจุดเดิมได้)' : 'ล้มเหลว';
        break;
      }
    }
    job.node.classList.add(ok ? 'done' : 'err');
    if (ok) {
      job.bar.style.width = '100%';
      job.s.textContent = fmtSize(total) + ' ✓';
    }
    this.finish(job, ok);
  },
  finish(job, ok) {
    this.active--;
    if (job.cb) try { job.cb(ok); } catch { /* listener error */ }
    const dest = job.rel ? job.dest + '/' + job.rel : job.dest;
    clearTimeout(this.refreshT);
    this.refreshT = setTimeout(() => this.listeners.forEach(f => f(job.dest)), 300);
    if (!this.active && !this.queue.length) {
      const done = $('#uList').querySelectorAll('.u-item.done').length;
      notify(`อัปโหลดเสร็จ ${done} ไฟล์ไปที่ ${displayPath(dest)}`);
      toast(ok ? 'อัปโหลดเสร็จแล้ว' : 'อัปโหลดเสร็จ (มีบางไฟล์ล้มเหลว)');
    }
    this.pump();
    this.badge();
  },
  badge() {
    const n = this.active + this.queue.length;
    const b = $('#uploadsBadge');
    b.textContent = n;
    b.classList.toggle('hidden', !n);
    $('#uTitle').textContent = n ? `กำลังอัปโหลด (${n})` : 'อัปโหลด';
  },
};
$('#uClose').onclick = () => $('#uploads').classList.add('hidden');
$('#uClear').onclick = () => $('#uList').querySelectorAll('.u-item.done, .u-item.err').forEach(n => n.remove());
$('#uploadsBtn').onclick = () => $('#uploads').classList.toggle('hidden');
