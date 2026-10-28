/* NexShare web desktop — file viewer and uploader. Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- viewer

const Viewer = {
  open(entry, list) {
    this.list = list && list.length ? list : [entry];
    this.i = Math.max(0, this.list.findIndex(e => e.path === entry.path));
    if (!this.el) this.build();
    this.el.classList.remove('hidden');
    this.show();
  },
  build() {
    this.title = el('div', { class: 'v-title' });
    this.body = el('div', { class: 'v-body' });
    this.prev = el('button', { class: 'v-nav prev', onclick: () => this.step(-1) }, icon('back'));
    this.next = el('button', { class: 'v-nav next', onclick: () => this.step(1) }, icon('fwd'));
    this.el = el('div', { id: 'viewer', class: 'hidden' },
      el('div', { class: 'v-top' }, this.title,
        el('button', { title: 'เปิดในแท็บใหม่', onclick: () => window.open(fileUrl(this.cur.path), '_blank') }, icon('open')),
        el('button', { title: 'ดาวน์โหลด', onclick: () => { location.href = fileUrl(this.cur.path, true); } }, icon('download')),
        el('button', { title: 'ปิด', onclick: () => this.close() }, icon('x'))),
      el('div', { style: { flex: 1, minHeight: 0, position: 'relative', display: 'flex' } }, this.body, this.prev, this.next));
    this.body.style.flex = '1';
    document.body.append(this.el);
    addEventListener('keydown', e => {
      if (this.el.classList.contains('hidden') || $('#modal')) return;
      if (e.key === 'Escape') this.close();
      if (e.key === 'ArrowLeft') this.step(-1);
      if (e.key === 'ArrowRight') this.step(1);
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
  step(d) {
    const n = this.i + d;
    if (n < 0 || n >= this.list.length) return;
    this.i = n;
    this.show();
  },
  async show() {
    const e = this.cur = this.list[this.i];
    this.title.textContent = `${e.name}  ·  ${fmtSize(e.size)}` + (this.list.length > 1 ? `  ·  ${this.i + 1}/${this.list.length}` : '');
    this.prev.classList.toggle('hidden', this.i === 0);
    this.next.classList.toggle('hidden', this.i >= this.list.length - 1);
    const url = fileUrl(e.path);
    const k = kindOf(e);
    this.body.replaceChildren();
    if (k === 'image') this.body.append(el('img', { src: url, alt: e.name }));
    else if (k === 'video') this.body.append(el('video', { src: url, controls: true, autoplay: true, playsinline: true }));
    else if (k === 'audio') this.body.append(el('div', { class: 'v-info' }, fileIcon('audio'), el('audio', { src: url, controls: true, autoplay: true })));
    else if (k === 'pdf') this.body.append(el('iframe', { src: url }));
    else if (k === 'text' && e.size < 4 * 1024 * 1024) {
      const pre = el('pre', { text: 'กำลังโหลด…' });
      this.body.append(pre);
      try {
        const r = await fetch(url, { headers: { Range: 'bytes=0-1048575' } });
        pre.textContent = await r.text();
      } catch { pre.textContent = 'โหลดไม่สำเร็จ'; }
    } else {
      this.body.append(el('div', { class: 'v-info' }, fileIcon(k), el('div', { text: 'ไม่มีตัวอย่างสำหรับไฟล์ชนิดนี้' }),
        el('div', { style: { margin: '6px 0 16px' }, text: `${fmtSize(e.size)} · แก้ไข ${fmtDate(e.mtime)}` }),
        el('a', { class: 'btn primary', href: fileUrl(e.path, true), style: { textDecoration: 'none' } }, icon('download'), 'ดาวน์โหลด')));
    }
  },
  close() {
    this.el.classList.add('hidden');
    this.body.replaceChildren();
  },
};

// ---------------------------------------------------------------- uploads

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
  send(job) {
    this.active++;
    this.badge();
    const xhr = new XMLHttpRequest();
    const t0 = performance.now();
    xhr.open('POST', '/api/upload' + qs({ path: job.dest, name: job.file.name, rel: job.rel || null, mtime: job.file.lastModified || null, overwrite: job.overwrite ? 1 : null }));
    xhr.upload.onprogress = e => {
      if (!e.lengthComputable) return;
      const pct = e.loaded / e.total * 100;
      job.bar.style.width = pct + '%';
      const secs = (performance.now() - t0) / 1000;
      job.s.textContent = `${pct.toFixed(0)}% · ${fmtRate(e.loaded / Math.max(secs, .1))}`;
    };
    xhr.onload = () => {
      const ok = xhr.status === 200;
      job.node.classList.add(ok ? 'done' : 'err');
      job.bar.style.width = '100%';
      job.s.textContent = ok ? fmtSize(job.file.size) + ' ✓' : xhr.status === 403 ? 'ไม่มีสิทธิ์เขียน' : 'ล้มเหลว';
      if (xhr.status === 401) Auth.show();
      this.finish(job, ok);
    };
    xhr.onerror = () => {
      job.node.classList.add('err');
      job.s.textContent = 'การเชื่อมต่อหลุด';
      this.finish(job, false);
    };
    xhr.send(job.file);
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
