/* NexShare web desktop — Media Player. Loaded as a classic script; see index.html for order. */
'use strict';

// ---------------------------------------------------------------- Media Player

function srtToVtt(text) {
  return 'WEBVTT\n\n' + text.replace(/\r/g, '').replace(/^\d+\s*$/gm, '').replace(/(\d\d:\d\d:\d\d),(\d\d\d)/g, '$1.$2').trim() + '\n';
}
const fmtClock = s => {
  if (!isFinite(s)) return '0:00';
  s = Math.floor(s);
  const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, sec = String(s % 60).padStart(2, '0');
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
};

function MediaPlayer(body, win) {
  const st = { list: [], i: -1, folder: [], shuffle: false, loop: store.get('mp.loop', 'list'), subUrl: null, subs: [] };
  const video = el('video', { playsinline: true, preload: 'metadata' });
  video.volume = store.get('mp.vol', 1);
  const art = el('div', { class: 'mp-art hidden' }, el('div', null, el('div', { class: 'disc' }), el('div', { class: 't' }), el('div', { class: 's' })));
  const osd = el('div', { class: 'mp-osd', style: { opacity: 0 } });
  const big = el('div', { class: 'mp-big', style: { opacity: 0 } });
  const msg = el('div', { class: 'mp-msg hidden' });
  const screen = el('div', { class: 'mp-screen' }, video, art, big, osd, msg);

  const played = el('div', { class: 'played' }), buf = el('div', { class: 'buf' }), knob = el('div', { class: 'knob' }), tip = el('div', { class: 'tip' });
  const seek = el('div', { class: 'mp-seek' }, el('div', { class: 'track' }), buf, played, knob, tip);
  const time = el('span', { class: 'time', text: '0:00 / 0:00' });
  const b = (ico, title, run) => el('button', { title, onclick: run }, typeof ico === 'string' ? icon(ico) : ico);
  const playBtn = b('play', 'เล่น/หยุด (Space)', () => toggle());
  const vol = el('input', { type: 'range', min: 0, max: 1, step: 0.05, value: video.volume, class: 'vol', oninput: e => { video.volume = +e.target.value; video.muted = false; store.set('mp.vol', video.volume); } });
  const muteBtn = b(volIcon(), 'ปิดเสียง (M)', () => { video.muted = !video.muted; });
  const speedBtn = el('button', { title: 'ความเร็ว', text: '1x', onclick: e => menuAt(e.currentTarget, [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2].map(r => ({ label: (video.playbackRate === r ? '● ' : '') + r + 'x', run: () => setRate(r) }))) });
  const ccBtn = el('button', { title: 'คำบรรยาย', text: 'CC', onclick: e => subMenu(e.currentTarget) });
  const loopBtn = b('refresh', 'เล่นซ้ำ', () => cycleLoop());
  const shufBtn = el('button', { title: 'สุ่ม', text: '⤮', style: { fontSize: '17px' }, onclick: () => { st.shuffle = !st.shuffle; shufBtn.classList.toggle('on', st.shuffle); flash(st.shuffle ? 'สุ่มเพลง: เปิด' : 'สุ่มเพลง: ปิด'); } });
  const listBtn = b('list', 'เพลย์ลิสต์', () => { listPane.classList.toggle('hidden'); listBtn.classList.toggle('on', !listPane.classList.contains('hidden')); });
  listBtn.classList.add('on');
  const more = b('link', 'ลิงก์สตรีม / เปิดใน VLC', e => menuAt(e.currentTarget, [
    { label: 'คัดลอกลิงก์สตรีม (ใช้กับ VLC/โปรแกรมอื่น)', ico: 'link', run: () => copyStream() },
    { label: 'เปิดในแอป VLC (มือถือ)', ico: 'open', run: () => { location.href = 'vlc://' + location.origin + fileUrl(cur().path); } },
    { label: 'ดาวน์โหลดไฟล์นี้', ico: 'download', run: () => { location.href = fileUrl(cur().path, true); } },
    document.pictureInPictureEnabled ? { label: 'ภาพซ้อนภาพ (PiP)', ico: 'restore', run: () => video.requestPictureInPicture().catch(() => {}) } : null,
  ]));
  const pipBtn = document.pictureInPictureEnabled ? b('restore', 'ภาพซ้อนภาพ (PiP)', () => {
    if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
    else video.requestPictureInPicture().catch(() => {});
  }) : null;
  const fsBtn = b('max', 'เต็มจอ (F)', () => fullscreen());
  const ctrl = el('div', { class: 'mp-ctrl' }, seek, el('div', { class: 'mp-row' },
    b('back', 'ก่อนหน้า (P)', () => step(-1)), playBtn, b('fwd', 'ถัดไป (N)', () => step(1)),
    time, el('div', { class: 'grow' }), muteBtn, vol, speedBtn, ccBtn, loopBtn, shufBtn, pipBtn, more, listBtn, fsBtn));
  const stage = el('div', { class: 'mp-stage' }, screen, ctrl);
  const listItems = el('div', { class: 'items' });
  const listHead = el('h4');
  const listPane = el('div', { class: 'mp-list' }, listHead, listItems);
  const root = el('div', { class: 'mp', tabindex: '0' }, stage, listPane);
  body.append(root);
  updateLoopBtn();

  function volIcon() {
    const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('fill', 'none'); s.setAttribute('stroke', 'currentColor'); s.setAttribute('stroke-width', '2'); s.setAttribute('stroke-linecap', 'round');
    s.innerHTML = '<path d="M4 9h4l5-4v14l-5-4H4z" fill="currentColor"/><path class="w" d="M16 9a4 4 0 0 1 0 6M18.5 6.5a8 8 0 0 1 0 11"/>';
    return s;
  }
  const cur = () => st.list[st.i];
  const isAudio = e => kindOf(e) === 'audio';

  function flash(text) {
    osd.textContent = text;
    osd.style.opacity = 1;
    clearTimeout(flash.t);
    flash.t = setTimeout(() => { osd.style.opacity = 0; }, 900);
  }
  function bigIcon(name) {
    big.replaceChildren(icon(name));
    big.style.opacity = 1;
    clearTimeout(bigIcon.t);
    bigIcon.t = setTimeout(() => { big.style.opacity = 0; }, 450);
  }
  function setRate(r) { video.playbackRate = r; speedBtn.textContent = r + 'x'; flash('ความเร็ว ' + r + 'x'); }
  function cycleLoop() {
    st.loop = { list: 'one', one: 'off', off: 'list' }[st.loop];
    store.set('mp.loop', st.loop);
    updateLoopBtn();
    flash({ list: 'เล่นซ้ำทั้งเพลย์ลิสต์', one: 'เล่นซ้ำไฟล์นี้', off: 'ไม่เล่นซ้ำ' }[st.loop]);
  }
  function updateLoopBtn() {
    loopBtn.classList.toggle('on', st.loop !== 'off');
    loopBtn.title = { list: 'เล่นซ้ำ: ทั้งหมด', one: 'เล่นซ้ำ: ไฟล์นี้', off: 'เล่นซ้ำ: ปิด' }[st.loop];
    loopBtn.style.position = 'relative';
    loopBtn.dataset.one = st.loop === 'one' ? '1' : '';
    loopBtn.replaceChildren(icon('refresh'), st.loop === 'one' ? el('span', { text: '1', style: { position: 'absolute', fontSize: '9px', right: '6px', bottom: '5px' } }) : '');
  }
  function toggle() { if (video.paused) video.play().catch(() => {}); else video.pause(); }
  function fullscreen() {
    if (document.fullscreenElement) document.exitFullscreen();
    else (stage.requestFullscreen ? stage.requestFullscreen() : video.webkitEnterFullscreen && video.webkitEnterFullscreen());
  }
  function copyStream() {
    const url = location.origin + fileUrl(cur().path);
    if (navigator.clipboard && isSecureContext) navigator.clipboard.writeText(url).then(() => toast('คัดลอกลิงก์แล้ว'));
    else dialog({ title: 'ลิงก์สตรีม — วางใน VLC (Media › Open Network Stream)', input: url, cancel: false });
  }

  function savePos() {
    const e = cur();
    if (!e || !isFinite(video.duration) || video.duration < 120) return;
    const pos = video.currentTime > 10 && video.currentTime < video.duration - 15 ? Math.floor(video.currentTime) : 0;
    const all = store.get('mp.pos', {});
    if (pos) all[e.path] = pos; else delete all[e.path];
    const keys = Object.keys(all);
    if (keys.length > 200) delete all[keys[0]];
    store.set('mp.pos', all);
  }

  async function play(i, resume = true) {
    savePos();
    st.i = i;
    const e = cur();
    if (!e) return;
    msg.classList.add('hidden');
    [...video.querySelectorAll('track')].forEach(t => t.remove());
    if (st.subUrl) { URL.revokeObjectURL(st.subUrl); st.subUrl = null; }
    video.src = fileUrl(e.path);
    const audio = isAudio(e);
    art.classList.toggle('hidden', !audio);
    if (audio) { art.querySelector('.t').textContent = e.name.replace(/\.[^.]+$/, ''); art.querySelector('.s').textContent = `${st.i + 1} / ${st.list.length} · ${fmtSize(e.size)}`; }
    win.setTitle('Media Player — ' + e.name);
    const pos = resume && store.get('mp.pos', {})[e.path];
    if (pos) video.addEventListener('loadedmetadata', () => { video.currentTime = pos; flash('เล่นต่อจาก ' + fmtClock(pos)); }, { once: true });
    video.play().catch(() => {});
    renderList();
    findSubs(e);
  }
  function step(d) {
    if (!st.list.length) return;
    let n;
    if (st.shuffle && st.list.length > 1) { do n = Math.floor(Math.random() * st.list.length); while (n === st.i); }
    else n = st.i + d;
    if (n >= st.list.length) { if (st.loop === 'list') n = 0; else return; }
    if (n < 0) n = st.list.length - 1;
    play(n);
  }

  async function findSubs(e) {
    st.subs = [];
    ccBtn.classList.remove('on');
    if (isAudio(e)) return;
    let folder = st.folder;
    const dir = e.path.slice(0, e.path.lastIndexOf('/'));
    if (!folder.length || !folder.some(x => x.path.startsWith(dir + '/'))) {
      try { folder = (await api('list', { path: dir })).entries; st.folder = folder; } catch { folder = []; }
    }
    const base = e.name.replace(/\.[^.]+$/, '').toLowerCase();
    st.subs = folder.filter(x => /\.(srt|vtt)$/i.test(x.name) && x.path.startsWith(dir + '/'))
      .sort((a, b) => (b.name.toLowerCase().startsWith(base)) - (a.name.toLowerCase().startsWith(base)));
    const auto = st.subs.find(x => x.name.toLowerCase().startsWith(base));
    if (auto) loadSub(auto, true);
  }
  async function loadSub(s, quiet) {
    try {
      let text = await (await fetch(fileUrl(s.path))).text();
      if (/\.srt$/i.test(s.name)) text = srtToVtt(text);
      [...video.querySelectorAll('track')].forEach(t => t.remove());
      if (st.subUrl) URL.revokeObjectURL(st.subUrl);
      st.subUrl = URL.createObjectURL(new Blob([text], { type: 'text/vtt' }));
      const tr = el('track', { kind: 'subtitles', src: st.subUrl, srclang: 'th', label: s.name, default: true });
      video.append(tr);
      tr.track.mode = 'showing';
      ccBtn.classList.add('on');
      if (!quiet) flash('คำบรรยาย: ' + s.name);
    } catch { toast('โหลดคำบรรยายไม่ได้'); }
  }
  function subMenu(anchor) {
    const pickLocal = el('input', { type: 'file', accept: '.srt,.vtt', class: 'hidden', onchange: async () => {
      const f = pickLocal.files[0];
      if (!f) return;
      let text = await f.text();
      if (/\.srt$/i.test(f.name)) text = srtToVtt(text);
      [...video.querySelectorAll('track')].forEach(t => t.remove());
      st.subUrl = URL.createObjectURL(new Blob([text], { type: 'text/vtt' }));
      const tr = el('track', { kind: 'subtitles', src: st.subUrl, label: f.name, default: true });
      video.append(tr);
      tr.track.mode = 'showing';
      ccBtn.classList.add('on');
    } });
    document.body.append(pickLocal);
    const track = video.textTracks[0];
    menuAt(anchor, [
      { label: (track && track.mode === 'showing' ? '' : '● ') + 'ปิดคำบรรยาย', run: () => { if (track) track.mode = 'hidden'; ccBtn.classList.remove('on'); } },
      ...st.subs.map(s => ({ label: s.name, run: () => loadSub(s) })),
      '-',
      { label: 'โหลดไฟล์คำบรรยายจากเครื่องนี้…', ico: 'upload', run: () => pickLocal.click() },
    ]);
  }

  function renderList() {
    const savedPos = store.get('mp.pos', {}) || {};
    listHead.replaceChildren(el('span', { text: 'เพลย์ลิสต์', style: { color: '#e6edf3', fontWeight: 600 } }), el('span', { text: st.list.length + ' ไฟล์' }));
    listItems.replaceChildren(...st.list.map((e, i) => {
      const k = kindOf(e);
      let thumb;
      if (k === 'video') { thumb = el('img', { loading: 'lazy', src: thumbUrl(e.path, 160), alt: '' }); thumb.onerror = () => thumb.replaceWith(el('div', { class: 'ph' }, fileIcon(k))); }
      else thumb = el('div', { class: 'ph' }, fileIcon(k));
      const resumeAt = savedPos[e.path];
      const subText = fmtSize(e.size) + (resumeAt ? ` · ▶ ต่อจาก ${fmtClock(resumeAt)}` : '');
      return el('div', { class: 'mp-item' + (i === st.i ? ' cur' : ''), onclick: () => play(i) }, thumb,
        el('div', { style: { minWidth: 0 } }, el('div', { class: 'n', text: e.name }), el('div', { class: 'z', text: subText })));
    }));
    const c = listItems.children[st.i];
    if (c) c.scrollIntoView({ block: 'nearest' });
  }

  // ---- events
  const updateTime = () => {
    const d = video.duration || 0, t = video.currentTime || 0;
    const p = d ? t / d * 100 : 0;
    played.style.width = p + '%';
    knob.style.left = p + '%';
    time.textContent = fmtClock(t) + ' / ' + fmtClock(d);
    if (video.buffered.length && d) buf.style.width = video.buffered.end(video.buffered.length - 1) / d * 100 + '%';
  };
  video.addEventListener('timeupdate', updateTime);
  video.addEventListener('progress', updateTime);
  video.addEventListener('loadedmetadata', updateTime);
  video.addEventListener('play', () => { playBtn.replaceChildren(icon('pause')); art.classList.add('spin'); });
  video.addEventListener('pause', () => { playBtn.replaceChildren(icon('play')); art.classList.remove('spin'); savePos(); });
  video.addEventListener('volumechange', () => { vol.value = video.muted ? 0 : video.volume; muteBtn.querySelector('.w').style.display = video.muted || !video.volume ? 'none' : ''; });
  video.addEventListener('ended', () => { if (st.loop === 'one') { video.currentTime = 0; video.play(); } else step(1); });
  video.addEventListener('error', () => {
    if (!cur()) return;
    msg.replaceChildren(el('div', null,
      el('div', { style: { fontSize: '15px', marginBottom: '6px' }, text: 'เบราว์เซอร์เล่นไฟล์นี้ไม่ได้' }),
      el('div', { style: { color: '#8b98a5', marginBottom: '14px', fontSize: '12.5px' }, text: 'มักเกิดจาก codec ที่เบราว์เซอร์ไม่รองรับ (เช่น HEVC/H.265, AC3, DTS ในไฟล์ .mkv/.avi) ลองเปิดด้วย VLC แทน' }),
      el('button', { class: 'btn primary', onclick: copyStream }, icon('link'), 'คัดลอกลิงก์ไปเปิดใน VLC'),
      el('a', { class: 'btn', href: 'vlc://' + location.origin + fileUrl(cur().path), style: { textDecoration: 'none' } }, icon('open'), 'เปิดแอป VLC'),
      el('button', { class: 'btn', onclick: () => step(1) }, icon('fwd'), 'ไฟล์ถัดไป')));
    msg.classList.remove('hidden');
  });
  screen.addEventListener('click', e => { if (e.target === video || e.target === screen) { toggle(); bigIcon(video.paused ? 'pause' : 'play'); } });
  screen.addEventListener('dblclick', fullscreen);

  const seekTo = x => { const r = seek.getBoundingClientRect(); const f = Math.min(1, Math.max(0, (x - r.left) / r.width)); if (video.duration) video.currentTime = f * video.duration; };
  seek.addEventListener('pointerdown', e => {
    seekTo(e.clientX);
    const move = ev => seekTo(ev.clientX);
    const up = () => { removeEventListener('pointermove', move); removeEventListener('pointerup', up); };
    addEventListener('pointermove', move);
    addEventListener('pointerup', up);
  });
  seek.addEventListener('mousemove', e => {
    const r = seek.getBoundingClientRect();
    const f = Math.min(1, Math.max(0, (e.clientX - r.left) / r.width));
    tip.style.left = f * 100 + '%';
    tip.textContent = fmtClock(f * (video.duration || 0));
  });

  root.addEventListener('keydown', e => {
    if (e.target.tagName === 'INPUT' || $('#modal')) return;
    const k = e.key.toLowerCase();
    const seekBy = s => { video.currentTime = Math.max(0, Math.min(video.duration || 0, video.currentTime + s)); flash((s > 0 ? '+' : '') + s + ' วิ'); };
    if (k === ' ' || k === 'k') { e.preventDefault(); toggle(); bigIcon(video.paused ? 'pause' : 'play'); }
    else if (k === 'arrowright') seekBy(5);
    else if (k === 'arrowleft') seekBy(-5);
    else if (k === 'l') seekBy(10);
    else if (k === 'j') seekBy(-10);
    else if (k === 'arrowup') { e.preventDefault(); video.volume = Math.min(1, video.volume + .05); flash('เสียง ' + Math.round(video.volume * 100) + '%'); }
    else if (k === 'arrowdown') { e.preventDefault(); video.volume = Math.max(0, video.volume - .05); flash('เสียง ' + Math.round(video.volume * 100) + '%'); }
    else if (k === 'm') video.muted = !video.muted;
    else if (k === 'f') fullscreen();
    else if (k === 'n') step(1);
    else if (k === 'p') step(-1);
    else if (k === '>' || k === '.') setRate(Math.min(2, video.playbackRate + .25));
    else if (k === '<' || k === ',') setRate(Math.max(.25, video.playbackRate - .25));
    else if (k === 'c') { const t = video.textTracks[0]; if (t) { t.mode = t.mode === 'showing' ? 'hidden' : 'showing'; ccBtn.classList.toggle('on', t.mode === 'showing'); } }
  });
  if ('mediaSession' in navigator) {
    navigator.mediaSession.setActionHandler('nexttrack', () => step(1));
    navigator.mediaSession.setActionHandler('previoustrack', () => step(-1));
  }

  return {
    handle(arg) {
      const kind = kindOf(arg.entry);
      st.list = (arg.list && arg.list.length ? arg.list : [arg.entry]).filter(x => ['video', 'audio'].includes(kindOf(x)) && (kind === 'audio') === isAudio(x));
      if (!st.list.some(x => x.path === arg.entry.path)) st.list.unshift(arg.entry);
      st.folder = arg.folder || [];
      play(st.list.findIndex(x => x.path === arg.entry.path));
      setTimeout(() => root.focus(), 50);
    },
    destroy() { savePos(); video.pause(); video.removeAttribute('src'); video.load(); if (st.subUrl) URL.revokeObjectURL(st.subUrl); },
  };
}

/** Opens a file in the best app: media → Media Player, everything else → viewer. */
function openEntry(e, siblings, folder) {
  const k = kindOf(e);
  if (k === 'video' || k === 'audio') openApp('player', { entry: e, list: siblings, folder: folder || siblings });
  else Viewer.open(e, siblings);
}
