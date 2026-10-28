"""Mock NexShare backend for the browser UI tests: serves app/assets/web and fakes the JSON API
(plus a WebSocket that streams an H.264 clip for the Screen Mirror test). Not a real server."""
import json, os, sys, time, urllib.parse
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

WEB = sys.argv[1]
PORT = int(sys.argv[2])
ROOT = '/storage/emulated/0'
# Same policy as WebApi.serveAsset sends for index.html, so violations surface in the tests.
CSP = """default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; worker-src 'self'; frame-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'self'"""
now = int(time.time() * 1000)

S = {
    'logged': False, 'user': 'admin', 'sudo': False, 'pin': '123456',
    'users': [{'name': 'mom', 'role': 'ro', 'folders': [ROOT + '/DCIM']}],
    'settings': {'pinEnabled': True, 'adminPassword': False, 'totpMode': 'off', 'https': True, 'httpsPort': 8443,
                 'httpsRunning': True, 'forceHttps': False, 'tlsFingerprint': 'AB:CD:' * 15 + 'EF', 'trash': True,
                 'trashDays': 30, 'webdav': True, 'webdavTunnel': False, 'sessions': 2},
    'shares': [{'id': 'abcdefghijklmnop', 'path': ROOT + '/DCIM/a.jpg', 'name': 'a.jpg', 'dir': False, 'owner': 'admin',
                'created': now - 3600e3, 'expires': now + 86400e3, 'hits': 3, 'password': True}],
    'trash': [{'id': '1700000000000-abcd', 'name': 'old.txt', 'orig': ROOT + '/Documents/old.txt', 'deleted': now - 86400e3,
               'size': 1234, 'dir': False, 'by': 'admin'}],
    'calls': [],
}

FILES = {
    ROOT: [('DCIM', True, 0), ('Documents', True, 0), ('Download', True, 0), ('notes.txt', False, 42)],
    ROOT + '/DCIM': [('a.jpg', False, 200000), ('b.mp4', False, 9000000)],
    ROOT + '/Documents': [('report.pdf', False, 50000)],
    ROOT + '/Download': [],
}


def entry(parent, name, d, size):
    return {'name': name, 'path': parent + '/' + name, 'dir': d, 'size': size, 'mtime': now, 'hidden': name.startswith('.')}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, obj=None, ctype='application/json', body=None):
        data = body if body is not None else json.dumps(obj).encode()
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(data)))
        if getattr(self, 'csp', None):
            self.send_header('Content-Security-Policy', self.csp)
            self.csp = None
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.route('GET')

    def do_POST(self):
        self.route('POST')

    def ws_frame(self, opcode, data):
        n = len(data)
        head = bytes([0x80 | opcode])
        if n < 126: head += bytes([n])
        elif n < 65536: head += bytes([126]) + n.to_bytes(2, 'big')
        else: head += bytes([127]) + n.to_bytes(8, 'big')
        self.wfile.write(head + data)
        self.wfile.flush()

    def stream(self):
        import base64, hashlib, random
        key = self.headers['Sec-WebSocket-Key']
        acc = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        self.wfile.write(('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' + acc + '\r\n\r\n').encode())
        self.ws_frame(1, json.dumps({'type': 'config', 'width': 640, 'height': 360, 'screenWidth': 1280, 'screenHeight': 720}).encode())
        data = open(os.environ['NEXSHARE_TEST_H264'], 'rb').read()
        i = 0
        while i < len(data):  # arbitrary chunk boundaries, like a pipe
            n = random.randint(7, 4000)
            self.ws_frame(2, data[i:i + n])
            i += n
            time.sleep(0.005)
        time.sleep(2)

    def route(self, method):
        u = urllib.parse.urlparse(self.path)
        if u.path == '/api/root/stream' and self.headers.get('Upgrade'):
            return self.stream()
        q = urllib.parse.parse_qs(u.query)
        p = u.path
        n = int(self.headers.get('Content-Length') or 0)
        raw = self.rfile.read(n) if n else b''
        try:
            body = json.loads(raw) if raw and raw[:1] in (b'{', b'[') else {}
        except Exception:
            body = {}
        S['calls'].append((method, p, q, body))
        if not p.startswith('/api/'):
            f = os.path.join(WEB, 'index.html' if p == '/' else p.lstrip('/'))
            if not os.path.isfile(f):
                return self.send(404, {'error': 'not found'})
            ext = f.rsplit('.', 1)[-1]
            ct = {'html': 'text/html', 'js': 'application/javascript', 'css': 'text/css', 'png': 'image/png',
                  'webmanifest': 'application/manifest+json'}.get(ext, 'application/octet-stream')
            self.csp = CSP if ext == 'html' else None
            return self.send(200, ctype=ct, body=open(f, 'rb').read())
        a = p[5:]
        if a == 'session':
            o = {'auth': S['logged'], 'pinRequired': True, 'adminPassword': S['settings']['adminPassword'], 'otp': False,
                 'multiUser': True, 'secure': False, 'server': 'POCO X7 Pro', 'flavor': 'root', 'version': '2.0.0', 'root': True,
                 'webdav': True, 'trash': True, 'trashDays': 30, 'httpsPort': 8443}
            if S['logged']:
                role = 'admin' if S['user'] == 'admin' else next(x['role'] for x in S['users'] if x['name'] == S['user'])
                o.update({'user': S['user'], 'role': role, 'admin': role == 'admin', 'canWrite': role != 'ro', 'sudo': S['sudo'], 'loggedIn': True})
            return self.send(200, o)
        if a == 'login':
            user = body.get('user') or 'admin'
            ok = (user == 'admin' and body.get('password') == S['pin']) or (user == 'mom' and body.get('password') == 'momsecret1')
            if not ok:
                return self.send(401, {'error': 'wrong_credentials'})
            S['logged'], S['user'], S['sudo'] = True, user, user == 'admin'
            return self.send(200, {'ok': True})
        if not S['logged']:
            return self.send(401, {'error': 'unauthorized'})
        role = 'admin' if S['user'] == 'admin' else 'ro'
        if a == 'logout':
            S['logged'] = False
            return self.send(200, {})
        if a == 'sudo':
            if body.get('password') != S['pin']:
                return self.send(401, {'error': 'wrong_credentials'})
            S['sudo'] = True
            return self.send(200, {'ok': True})
        if a == 'roots':
            return self.send(200, {'roots': [{'name': 'Internal storage', 'id': 'internal', 'path': ROOT, 'total': 128e9, 'free': 40e9, 'readable': True}],
                                   'folders': [] if role == 'admin' else [ROOT + '/DCIM']})
        if a == 'list':
            path = q.get('path', [ROOT])[0]
            if role != 'admin' and not path.startswith(ROOT + '/DCIM') and path != ROOT:
                return self.send(403, {'error': 'not allowed for this user'})
            ents = [entry(path, *e) for e in FILES.get(path, [])]
            return self.send(200, {'path': path, 'parent': None if path == ROOT else path.rsplit('/', 1)[0], 'writable': role != 'ro', 'entries': ents})
        if a == 'system':
            return self.send(200, {'name': 'NexShare-POCO', 'model': 'Xiaomi POCO X7 Pro', 'android': '15', 'sdk': 35, 'uptime': 3600e3, 'cores': 8,
                                   'ip': '192.168.1.50', 'host': 'nexshare.local', 'port': 8080, 'httpsPort': 8443, 'connections': 3,
                                   'memTotal': 12e9, 'memAvail': 5e9, 'cpu': 12.5, 'diskTotal': 256e9, 'diskFree': 100e9,
                                   'battery': 80, 'charging': True, 'batteryTemp': 31.0, 'rx': 1e6, 'tx': 2e6, 'time': now})
        if a in ('settings',):
            if method == 'POST':
                if not S['sudo']:
                    return self.send(403, {'error': 'sudo_required'})
                S['settings'].update({k: v for k, v in body.items() if k in S['settings']})
            return self.send(200, S['settings'])
        if a == 'users':
            return self.send(200, {'users': S['users']})
        if a == 'users/save':
            if not S['sudo']:
                return self.send(403, {'error': 'sudo_required'})
            S['users'] = [x for x in S['users'] if x['name'] != body['name']] + [{'name': body['name'], 'role': body['role'], 'folders': body.get('folders', [])}]
            return self.send(200, {'users': S['users']})
        if a == 'tunnel/status':
            return self.send(200, {'supported': True, 'state': 'connected', 'url': 'https://demo-tunnel.trycloudflare.com', 'error': '', 'mode': 'quick', 'hasToken': False, 'log': '', 'totpMode': 'off'})
        if a == 'share/list':
            return self.send(200, {'shares': S['shares']})
        if a == 'share/create':
            sh = {'id': 'qrstuvwxyz234567', 'path': body['path'], 'name': body['path'].rsplit('/', 1)[-1], 'dir': False, 'owner': S['user'],
                  'created': now, 'expires': now + body.get('hours', 0) * 3600e3 if body.get('hours') else 0, 'hits': 0, 'password': bool(body.get('password'))}
            S['shares'].append(sh)
            return self.send(200, sh)
        if a == 'trash/list':
            return self.send(200, {'items': S['trash'], 'enabled': True, 'days': 30})
        if a == 'trash/restore':
            S['trash'] = []
            return self.send(200, {'restored': [ROOT + '/Documents/old.txt']})
        if a == 'delete':
            return self.send(200, {'deleted': len(q.get('p', [])), 'trash': 'permanent' not in q})
        if a == '2fa/setup':
            if not S['sudo']:
                return self.send(403, {'error': 'sudo_required'})
            return self.send(200, {'secret': 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP', 'uri': 'otpauth://totp/NexShare:demo?secret=JBSWY3DPEHPK3PXP&issuer=NexShare'})
        if a == 'root/stream':
            return self.send(200, {'ok': True})
        if a == 'root/status':
            return self.send(200, {'flavor': 'root', 'root': True, 'termux': False, 'busybox': True})
        if a == 'dl/list':
            return self.send(200, {'tasks': [], 'defaultDir': ROOT + '/Download', 'torrent': True})
        if a == 'media':
            return self.send(200, {'entries': []})
        if a == 'exists':
            return self.send(200, {'missing': [i['rel'] for i in body.get('items', [])], 'missingBytes': 0, 'checked': len(body.get('items', []))})
        if a == '__nosudo':
            S['sudo'] = False
            return self.send(200, {})
        if a == '__calls':
            return self.send(200, {'calls': S['calls'][-50:]})
        return self.send(200, {})


ThreadingHTTPServer(('127.0.0.1', PORT), H).serve_forever()
