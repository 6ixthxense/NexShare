# Changelog

## 2.0.0

### Security
- Admin password (replaces the PIN) and optional TOTP two-factor login, always or only via the tunnel.
- Brute-force protection with exponential backoff; real client IP via `CF-Connecting-IP` behind the
  tunnel (fixes every internet visitor sharing one lockout) plus a global tunnel failure budget.
- Sessions persist across restarts, stored as SHA-256 hashes; revoked on PIN/password change.
- Re-authentication ("sudo", 10 min) for root terminal, screen control, app installs and security settings.
- HTTPS on port 8443 with an on-device self-signed certificate; optional HTTP → HTTPS redirect.
- Same-origin (`Origin`/`Referer`) check on API calls; `Secure` cookies over HTTPS/tunnel.
- Phone-hosted HTML/SVG served with `Content-Security-Policy: sandbox`; CSP and security headers on the UI.
- Phone app: clear admin password, disable 2FA, unlock lockouts, log out everywhere.

### Features
- Share links with expiry, password, QR code and download counter (`/s/<id>`).
- Recycle Bin with restore and automatic purge.
- WebDAV server at `/dav/` (Windows, macOS, Linux, media apps).
- Multiple users with roles and per-folder access (web UI, WebDAV, share links).
- Backup Station: incremental backup of a computer folder to the phone.
- Installable PWA (manifest, icons, service worker).
- Screen Mirror streams H.264 over WebSocket, decoded with WebCodecs.

### Performance
- Bounded worker pool (no unbounded thread growth); 503 when saturated.
- Thumbnail disk cache (256 MB LRU) in front of the memory cache.
- Chunked request bodies and `Expect: 100-continue` support.

### Code & tooling
- `WebApi` split into `Auth`, `FileApi`, `SystemApi`, `WebDav`, `Shares`, `Trash`, `Storage`…
- Web UI split from one 2,300-line `app.js` into `js/*.js` modules.
- `build.sh` / `setup.sh` for Linux/macOS, GitHub Actions CI with release uploads.
- JVM unit + integration tests (58) and headless-browser UI tests (26).
