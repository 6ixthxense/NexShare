/* NexShare service worker: makes the web desktop installable and keeps the UI shell cached
 * so it opens instantly. API calls, file transfers, share links and WebDAV always go to
 * the network (never cached). Bump VERSION when the asset list changes. */
'use strict';

const VERSION = 'nexshare-v2.1.0';
const SHELL = [
  '/', '/index.html', '/style.css', '/manifest.webmanifest',
  '/vendor/qrcode.js',
  '/js/core.js', '/js/i18n.js', '/js/file-ops.js', '/js/files.js', '/js/media.js',
  '/js/system.js', '/js/security.js', '/js/viewer.js', '/js/widgets.js',
  '/js/download.js', '/js/player.js', '/js/root.js', '/js/wallpaper.js',
  '/js/remote.js', '/js/shares.js', '/js/trash.js', '/js/backup.js', '/js/main.js',
  '/icons/icon-192.png', '/icons/icon-512.png',
];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== VERSION).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  if (/^\/(api|s|r|dav)(\/|$)/.test(url.pathname)) return; // live data: straight to the network
  // Shell: network first (the phone is usually right there), cache as fallback for speed/offline.
  e.respondWith(fetch(e.request).then(r => {
    if (r.ok) {
      const copy = r.clone();
      caches.open(VERSION).then(c => c.put(e.request, copy));
    }
    return r;
  }).catch(() => caches.match(e.request).then(r => r || caches.match('/index.html'))));
});
