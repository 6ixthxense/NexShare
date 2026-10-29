"""Browser smoke tests for the web desktop, run in headless Chromium AND WebKit against test/ui/mock_server.py.

    pip install playwright && python -m playwright install chromium webkit
    python test/ui/ui_test.py            # screenshots land in build/ui/

Set CHROMIUM_PATH to use an existing Chromium/Chrome binary instead of Playwright's.
"""
import json, os, shutil, subprocess, sys, tempfile, time
from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
WEB = os.path.join(REPO, 'app', 'assets', 'web')
OUT = os.path.join(REPO, 'build', 'ui')
os.makedirs(OUT, exist_ok=True)
PORT = 8765

# A short H.264 clip for the Screen Mirror (WebCodecs) test, if ffmpeg is available.
H264 = os.path.join(OUT, 'test.h264')
if shutil.which('ffmpeg') and not os.path.exists(H264):
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc=size=640x360:rate=30', '-t', '3', '-c:v', 'libx264',
                    '-profile:v', 'baseline', '-pix_fmt', 'yuv420p', '-bsf:v', 'h264_mp4toannexb', '-f', 'h264', H264, '-y'], check=True)
os.environ['NEXSHARE_TEST_H264'] = H264
srv = subprocess.Popen([sys.executable, os.path.join(HERE, 'mock_server.py'), WEB, str(PORT)])
time.sleep(0.8)
LAUNCH = {'args': ['--no-proxy-server']}
if os.environ.get('CHROMIUM_PATH'):
    LAUNCH['executable_path'] = os.environ['CHROMIUM_PATH']
errors, results = [], []

def ok(name, cond, detail=''):
    results.append((name, bool(cond), detail))
    print(('  ok   ' if cond else '  FAIL ') + name + ('' if cond else ' — ' + str(detail)))

try:
    with sync_playwright() as p:
        b = p.chromium.launch(**LAUNCH)
        pg = b.new_page(viewport={'width': 1366, 'height': 860})
        pg.on('pageerror', lambda e: errors.append('pageerror: ' + str(e)))
        pg.on('console', lambda m: errors.append('console: ' + m.text) if m.type == 'error' and '401' not in m.text and '403' not in m.text else None)
        base = f'http://127.0.0.1:{PORT}/'
        pg.goto(base)
        pg.wait_for_selector('#login:not(.hidden)', timeout=5000)
        ok('login screen shows', pg.is_visible('#loginForm'))
        ok('username field visible when users exist', pg.is_visible('#loginUser'))
        pg.fill('#pin', '000000'); pg.click('#loginForm button[type=submit]')
        pg.wait_for_timeout(400)
        ok('wrong PIN shows error', 'PIN' in pg.inner_text('#loginErr'), pg.inner_text('#loginErr'))
        pg.fill('#pin', '123456'); pg.click('#loginForm button[type=submit]')
        pg.wait_for_selector('#login.hidden', state='attached', timeout=5000)
        pg.wait_for_selector('.window', timeout=5000)
        ok('File Station opens after login', pg.locator('.window').count() >= 1)
        icons = pg.locator('#desktop .desk-icon .lbl').all_inner_texts()
        ok('desktop has new apps', all(x in icons for x in ['Backup Station', 'Shared Links', 'Recycle Bin']), icons)
        ok('root apps visible to admin', 'Terminal' in icons and 'Screen Mirror' in icons, icons)
        pg.wait_for_timeout(400)
        ok('Tailscale IP shown in health widget', pg.inner_text('#hTs') == '100.88.1.2', pg.inner_text('#hTs'))
        pg.screenshot(path=os.path.join(OUT, '01-desktop.png'))

        # Markdown preview test
        pg.locator('.fs-list [data-path$="/readme.md"]').first.dblclick()
        pg.wait_for_selector('#viewer .md-body', timeout=3000)
        ok('Markdown preview renders headings and formatting', 'NexShare Docs' in pg.inner_text('#viewer .md-body'))
        pg.keyboard.press('Escape')
        pg.wait_for_timeout(200)

        # File Station: select a file and share it
        pg.locator('.fs-list [data-path$="/notes.txt"]').first.click()
        pg.locator('.fs-tools button[title="สร้างลิงก์แชร์"]').click()
        pg.wait_for_selector('.dialog.form', timeout=3000)
        ok('share dialog opens', 'แชร์' in pg.inner_text('.dialog .d-head'))
        pg.locator('.dialog.form .btn.primary').click()
        pg.wait_for_selector('.share-links', timeout=4000)
        links = pg.locator('.share-links input').evaluate_all('els => els.map(e => e.value)')
        ok('share link includes tunnel + Tailscale + local URLs',
           any('trycloudflare' in l for l in links) and any('100.88.1.2' in l for l in links) and any('127.0.0.1' in l for l in links), links)
        ok('QR code rendered', pg.locator('.qr svg').count() == 1)
        pg.screenshot(path=os.path.join(OUT, '02-share.png'))
        pg.locator('#modal .btn.primary').click()

        # File Request link on a folder
        pg.locator('.fs-list [data-path$="/DCIM"]').first.click()
        pg.locator('.fs-tools button', has_text='การทำงาน').click()
        pg.wait_for_selector('.menu', timeout=2000)
        pg.locator('.menu button', has_text='สร้างลิงก์ขอไฟล์').click()
        pg.wait_for_selector('.dialog.form', timeout=3000)
        ok('file request dialog opens for folder', 'ขอไฟล์' in pg.inner_text('.dialog .d-head'))
        pg.locator('.dialog.form .btn.primary').click()
        pg.wait_for_selector('.share-links', timeout=4000)
        ok('file request link created', 'ขอไฟล์' in pg.inner_text('#modal .d-head'))
        pg.locator('#modal .btn.primary').click()

        # Delete -> trash wording
        pg.locator('.fs-list [data-path$="/notes.txt"]').first.click()
        pg.keyboard.press('Delete')
        pg.wait_for_selector('#modal', timeout=3000)
        ok('delete goes to trash', 'ถังขยะ' in pg.inner_text('#modal'), pg.inner_text('#modal'))
        pg.locator('#modal .btn.danger').click()
        pg.wait_for_timeout(500)
        calls = json.loads(pg.evaluate("fetch('/api/__calls').then(r => r.text())"))['calls']
        dels = [c for c in calls if c[1] == '/api/delete']
        ok('delete request without permanent flag', dels and 'permanent' not in dels[-1][2], dels[-1:])

        # Control Panel tabs (now 5 tabs including Audit Log)
        pg.evaluate("openApp('control')")
        pg.wait_for_selector('.tabs .tab', timeout=3000)
        tabs = pg.locator('.tabs .tab').all_inner_texts()
        ok('control panel has 5 tabs for admin (with Audit Log)', len(tabs) == 5, tabs)
        pg.locator('.tabs .tab', has_text='ความปลอดภัย').click()
        pg.wait_for_selector('.set-row', timeout=3000)
        ok('security tab warns about tunnel + PIN', pg.locator('.notice.warn').count() == 1)
        pg.screenshot(path=os.path.join(OUT, '03-security.png'))

        # Sudo flow: drop sudo, then try enabling 2FA -> should prompt
        pg.evaluate("fetch('/api/__nosudo')")
        pg.locator('button', has_text='เปิด 2FA').click()
        pg.wait_for_selector('.dialog .d-head:has-text("ยืนยันตัวตน")', timeout=3000)
        ok('sudo prompt appears for 2FA setup', True)
        pg.fill('.dialog.form input[type=password]', '123456')
        pg.locator('.dialog.form .btn.primary').click()
        pg.wait_for_selector('.totp-setup', timeout=4000)
        ok('2FA setup shows QR and secret after sudo', pg.locator('.totp-setup .qr svg').count() == 1 and 'JBSW' in pg.inner_text('.totp-setup'))
        pg.screenshot(path=os.path.join(OUT, '04-2fa.png'))
        pg.keyboard.press('Escape')

        # Users tab + quota
        pg.locator('.tabs .tab', has_text='ผู้ใช้').click()
        pg.wait_for_selector('.tbl', timeout=3000)
        ok('users table lists mom', 'mom' in pg.inner_text('.tbl'))
        pg.locator('button', has_text='เพิ่มผู้ใช้').click()
        pg.wait_for_selector('.dialog.form', timeout=3000)
        pg.locator('.dialog.form input').nth(0).fill('kid')
        pg.locator('.dialog.form input[type=password]').fill('kidsecret1')
        pg.locator('.dialog.form input[type=number]').fill('500')
        pg.locator('.dialog.form .btn.primary').click()
        pg.wait_for_timeout(500)
        tbl_text = pg.inner_text('.tbl')
        ok('new user saved with quota', 'kid' in tbl_text and '500 MB' in tbl_text, tbl_text)
        pg.screenshot(path=os.path.join(OUT, '05-users.png'))

        # Services tab + start on boot
        pg.locator('.tabs .tab', has_text='บริการ').click()
        pg.wait_for_selector('.howto', timeout=3000)
        ok('WebDAV URLs and startOnBoot shown', '/dav/' in pg.inner_text('.cp') and 'เปิดแชร์อัตโนมัติเมื่อเปิดเครื่อง' in pg.inner_text('.cp'))

        # Audit Log tab
        pg.locator('.tabs .tab', has_text='บันทึกกิจกรรม').click()
        pg.wait_for_selector('.audit-tbl', timeout=3000)
        ok('audit log lists recent activity', 'เข้าสู่ระบบ' in pg.inner_text('.audit-tbl') and 'บันทึกผู้ใช้' in pg.inner_text('.audit-tbl'))

        # Theme & Language toggles
        pg.click('#themeBtn')
        ok('dark mode toggle sets data-theme=dark', pg.evaluate("document.documentElement.getAttribute('data-theme')") == 'dark')
        pg.click('#langBtn')
        ok('language toggle switches to English', pg.evaluate("document.documentElement.lang") == 'en')
        pg.click('#langBtn')
        pg.click('#themeBtn')

        # Shared links + Recycle Bin + Backup apps
        pg.evaluate("openApp('shares')")
        pg.wait_for_timeout(500)
        ok('shared links list shows request badge', pg.locator('.window .tbl').count() >= 1 and 'ขอไฟล์' in pg.inner_text('body'))
        pg.evaluate("openApp('trash')")
        pg.wait_for_timeout(500)
        ok('recycle bin lists item', 'old.txt' in pg.inner_text('body'))
        pg.screenshot(path=os.path.join(OUT, '06-trash.png'))
        pg.evaluate("openApp('backup')")
        pg.wait_for_timeout(500)
        ok('backup station renders', 'Backup Station' in pg.inner_text('body'))
        pg.screenshot(path=os.path.join(OUT, '07-backup.png'))

        # Read-only user: logout and log in as mom
        pg.evaluate("Auth.logout()")
        pg.wait_for_selector('#login:not(.hidden)', timeout=5000)
        pg.fill('#loginUser', 'mom')
        ok('password mode for named user', pg.get_attribute('#pin', 'placeholder') == 'รหัสผ่าน')
        pg.fill('#pin', 'momsecret1'); pg.click('#loginForm button[type=submit]')
        pg.wait_for_selector('.window', timeout=5000)
        icons = pg.locator('#desktop .desk-icon .lbl').all_inner_texts()
        ok('read-only user: no admin/root/write apps', not any(x in icons for x in ['Terminal', 'Remote Access', 'Backup Station', 'Download Station']), icons)
        ok('read-only user: upload button hidden', pg.locator('.fs-tools .btn.primary.hidden').count() == 1)
        ok('read-only user: sidebar shows own folder', 'DCIM' in pg.inner_text('.fs-side'))
        pg.screenshot(path=os.path.join(OUT, '08-readonly.png'))

        # Screen Mirror: H.264 over WebSocket decoded with WebCodecs (localhost counts as a secure context)
        if os.path.exists(H264):
            s = b.new_page(viewport={'width': 1200, 'height': 860})
            s.on('pageerror', lambda e: errors.append('screen pageerror: ' + str(e)))
            pg.evaluate("fetch('/api/logout', {method: 'POST'})")
            s.goto(base)
            s.wait_for_selector('#login:not(.hidden)', timeout=5000)
            s.fill('#loginUser', ''); s.fill('#pin', '123456'); s.click('#loginForm button[type=submit]')
            s.wait_for_selector('.window', timeout=5000)
            s.evaluate("localStorage.removeItem('ns.scr.mode'); openApp('screen')")
            s.wait_for_timeout(3500)
            info = s.evaluate("""() => { const c = document.querySelector('canvas.scr-canvas'); if (!c || !c.width) return null;
              const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data; let lit = 0;
              for (let i = 0; i < d.length; i += 4000) if (d[i] || d[i + 1] || d[i + 2]) lit++;
              return { w: c.width, h: c.height, lit }; }""")
            ok('screen mirror decodes H.264 stream', info and info['w'] == 640 and info['lit'] > 50, info)
            s.locator('.window').last.screenshot(path=os.path.join(OUT, '10-screen-h264.png'))
        else:
            print('  skip screen mirror H.264 (no ffmpeg)')

        # Mobile layout in Chromium
        m = b.new_page(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True)
        m.on('pageerror', lambda e: errors.append('mobile pageerror: ' + str(e)))
        m.goto(base)
        m.wait_for_timeout(800)
        m.screenshot(path=os.path.join(OUT, '09-mobile.png'))
        ok('no on-screen error banner in Chromium', m.locator('#errorBanner').count() == 0)
        b.close()

        # WebKit (iOS / iPhone Safari engine) test suite
        wb = None
        try:
            wb = p.webkit.launch()
        except Exception as ex:
            if os.environ.get('CI'):
                ok('WebKit browser launch in CI', False, str(ex))
            else:
                print('  skip WebKit locally (not installed): ' + str(ex))
        if wb:
            w = wb.new_page(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True)
            w.on('pageerror', lambda e: errors.append('webkit pageerror: ' + str(e)))
            w.on('console', lambda m: errors.append('webkit console: ' + m.text) if m.type == 'error' and '401' not in m.text and '403' not in m.text else None)
            w.goto(base)
            w.wait_for_timeout(600)
            if w.is_visible('#loginForm'):
                w.fill('#loginUser', '')
                w.fill('#pin', '123456')
                w.click('#loginForm button[type=submit]')
            w.wait_for_selector('#desktop .desk-icon', timeout=5000)
            # Tap File Station icon on mobile viewport (regression test for iPhone desktop icon tap)
            w.locator('#desktop .desk-icon').first.tap()
            w.wait_for_selector('.window .fs', timeout=4000)
            ok('WebKit iPhone: tapping desktop icon opens File Station', w.locator('.window .fs').count() == 1)
            # Verify #filePick is not display:none so iOS Safari allows .click() from menu
            fp_display = w.evaluate("getComputedStyle(document.querySelector('#filePick')).display")
            ok('WebKit iPhone: file input is offscreen block (not display:none)', fp_display == 'block', fp_display)
            # Tap Upload button -> menu opens
            w.locator('.fs-tools .btn.primary').tap()
            w.wait_for_selector('.menu', timeout=2000)
            ok('WebKit iPhone: upload menu opens on tap', w.locator('.menu button').count() >= 2)
            # Tap row ⋯ button -> context menu opens on touch without right-click
            w.locator('.fs-list .row-more').first.tap()
            w.wait_for_selector('.menu', timeout=2000)
            ok('WebKit iPhone: row ⋯ button opens file action menu', 'ดาวน์โหลด' in w.inner_text('.menu') or 'เปิด' in w.inner_text('.menu'))
            ok('WebKit iPhone: no error banner shown', w.locator('#errorBanner').count() == 0)
            w.screenshot(path=os.path.join(OUT, '11-webkit-iphone.png'))
            wb.close()
finally:
    srv.terminate()

print()
for e in errors: print('  JS ERROR', e)
failed = [r for r in results if not r[1]]
print(f"{len(results) - len(failed)} passed, {len(failed)} failed, {len(errors)} JS errors")
sys.exit(1 if failed or errors else 0)
