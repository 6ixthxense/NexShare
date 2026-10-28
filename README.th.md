# NexShare

[English](README.md) · **ภาษาไทย**

เปลี่ยนมือถือ Android ให้เป็น NAS เครื่องเล็ก ๆ NexShare จะรันเว็บเซิร์ฟเวอร์บนมือถือ
แล้วเสิร์ฟหน้าเดสก์ท็อปสไตล์ Synology DSM ที่เปิดจากเครื่องไหนก็ได้ในวง Wi-Fi เดียวกัน
ผ่าน **http://nexshare.local:8080** (หรือเลข IP ของมือถือ) เพื่อ เรียกดู อัปโหลด ดาวน์โหลด
สตรีม และจัดการไฟล์ในมือถือจากเบราว์เซอร์

เขียนด้วย Java ล้วนและเว็บเซิร์ฟเวอร์ที่เขียนเอง — ไม่ใช้ Gradle ไม่ใช้ AndroidX

## ภาพตัวอย่าง

หน้าเว็บเดสก์ท็อป เปิดจากเบราว์เซอร์บนเครื่องอื่น:

![NexShare web desktop](docs/screenshots/web-desktop.png)

แอปบน Android (รุ่น Root):

<img src="docs/screenshots/app.png" alt="NexShare Android app" width="320">

*(รันบนเครื่องจริง — เลข PIN ถูกเบลอไว้)*

## สองเวอร์ชัน

| | **NexShare** (ปกติ) | **NexShare Root** |
|---|---|---|
| ชื่อแพ็กเกจ | `com.nexshare` | `com.nexshare.root` |
| File Station, Media Player, Download Station, Wallpaper | ✓ | ✓ |
| ติดตั้ง APK จากหน้าเว็บ | เด้งให้ยืนยันบนมือถือ | **ติดตั้งเงียบ** (`pm install`) |
| Terminal (root shell + BusyBox ~300 คำสั่ง, ใช้แพ็กเกจ Termux ได้ด้วย) | — | ✓ |
| ดูหน้าจอมือถือสด + แตะ/ปัด/ปุ่มควบคุม | — | ✓ |
| เปิดหน้าจอค้าง | เฉพาะตอนอยู่หน้าแอป | ทั้งเครื่อง (`svc power stayon`) |
| จัดการแอป (ปิด/ถอน/บังคับหยุด/ล้างข้อมูล) | เปิดแอปได้อย่างเดียว | ครบ |

ทั้งสองเวอร์ชันเซ็นด้วยคีย์เดียวกันและใช้คนละชื่อแพ็กเกจ จึง **ลงคู่กันในเครื่องเดียวได้**
เวอร์ชัน Root ต้องมี Magisk/SuperSU และกดอนุญาต root

## ฟีเจอร์

- **File Station** — ตัวจัดการไฟล์แบบหน้าต่างสไตล์ DSM: มุมมองรายการ/ไอคอน, ลากไฟล์หรือทั้งโฟลเดอร์
  มาวางเพื่ออัปโหลด, เลือกหลายไฟล์, ดาวน์โหลด (หลายไฟล์รวมเป็น ZIP ให้อัตโนมัติ),
  คัดลอก/ย้าย/เปลี่ยนชื่อ/ลบ, ค้นหา, ภาพตัวอย่างรูปและวิดีโอ, แถบเส้นทางโฟลเดอร์
- **Media Player** — เพลย์ลิสต์, เล่นต่อจากจุดเดิม, โหลดซับ `.srt`/`.vtt` ที่ชื่อตรงกันให้อัตโนมัติ,
  ปรับความเร็ว, วนซ้ำ/สุ่ม, ภาพซ้อนภาพ (PiP), ปุ่มลัดแบบ YouTube และปุ่ม "เปิดใน VLC"
  สำหรับไฟล์ที่เบราว์เซอร์เล่น codec ไม่ได้
- **Download Station** — โหลดไฟล์จากลิงก์ http/https หรือ BitTorrent
  (ลิงก์ magnet และไฟล์ `.torrent` ผ่าน libtorrent4j) ลงมือถือโดยตรง
  พร้อมหยุด/เล่นต่อ, ความคืบหน้า, ความเร็ว และจำนวน peers
- **Remote Access (Cloudflare Tunnel)** — เปิดให้เข้าถึงเซิร์ฟเวอร์จากอินเทอร์เน็ตได้ในปุ่มเดียว
  ได้ลิงก์ชั่วคราว `https://<ชื่อ>.trycloudflare.com` หรือวาง token ของ named tunnel
  เพื่อผูกโดเมนตัวเอง ฝัง `cloudflared` มาแล้ว (arm64 + arm) ไม่ต้องลง Termux
  พอเปิด tunnel ระบบจะบังคับเปิด PIN ให้อัตโนมัติ
- **Wallpaper** — สร้างวอลเปเปอร์แบรนด์ NexOS แล้วตั้งบนมือถือ
  หรือคลิกขวารูปใน File Station เพื่อตั้งเป็นวอลเปเปอร์
- **วิดเจ็ตระบบ** — System Health, Resource Monitor (CPU/RAM/เครือข่าย), ที่เก็บข้อมูล, แบตเตอรี่
- เข้าถึงผ่าน `nexshare.local` ด้วยตัวตอบ mDNS ในตัว พร้อมลิงก์ IP สำรอง
  ใส่ PIN 6 หลักก่อนเข้าได้ (เปิด/ปิดได้)
- **ลิงก์แชร์** — ส่งไฟล์หรือโฟลเดอร์ให้ใครก็ได้ด้วยลิงก์ `/s/<id>` ตั้งวันหมดอายุ รหัสผ่าน
  มี QR code และนับจำนวนครั้งที่เปิด ใช้ได้ทั้งใน Wi-Fi และผ่าน tunnel
- **ถังขยะ (Recycle Bin)** — ไฟล์ที่ลบจะย้ายไปถังขยะของแต่ละไดรฟ์ กู้คืนได้
  ลบถาวรอัตโนมัติหลัง 30 วัน (ปรับได้) กด Shift+Delete เพื่อลบถาวรทันที
- **WebDAV** ที่ `/dav/` — ต่อมือถือเป็นไดรฟ์เครือข่ายใน Windows Explorer, macOS Finder,
  ตัวจัดการไฟล์บน Linux, VLC, Infuse ฯลฯ (อ่าน/เขียนได้ รองรับ lock ที่ Windows/macOS ต้องใช้)
- **หลายผู้ใช้** — บัญชีแยกตามสิทธิ์ (ดูอย่างเดียว / อ่าน-เขียน / ผู้ดูแล)
  จำกัดให้เห็นเฉพาะบางโฟลเดอร์ได้ มีผลทั้งหน้าเว็บ WebDAV และลิงก์แชร์
- **Backup Station** — สำรองโฟลเดอร์จากคอมไปเก็บในมือถือ ส่งเฉพาะไฟล์ใหม่หรือที่เปลี่ยน
  Chrome/Edge ผ่าน HTTPS จำโฟลเดอร์ไว้ กดสำรองครั้งเดียวได้
- **ติดตั้งเป็นแอป (PWA)** เมื่อเปิดผ่าน HTTPS
- **Screen Mirror (root)** สตรีมวิดีโอ H.264 จาก `screenrecord` ผ่าน WebSocket แล้วถอดรหัสด้วย
  WebCodecs ลื่นกว่าภาพนิ่งมาก (ถ้าไม่รองรับจะกลับไปใช้ภาพนิ่งอัตโนมัติ)

## ความปลอดภัย

- **รหัสผ่านผู้ดูแล** ใช้แทน PIN 6 หลักได้ และเปิด **2FA (TOTP)** ได้ ทั้งแบบขอทุกครั้ง
  หรือเฉพาะเมื่อเข้าผ่านอินเทอร์เน็ต
- **กันเดารหัส**: ผิด 5 ครั้งจะล็อกเครื่องนั้น และล็อกนานขึ้นเรื่อย ๆ (1 นาที → 64 นาที)
  ถ้าเข้าผ่าน tunnel จะใช้ IP จริงจาก `CF-Connecting-IP` (เชื่อเฉพาะที่มาจาก cloudflared ในเครื่อง)
  และความพยายามที่ผิดผ่าน tunnel ทั้งหมดมีโควตารวมอีกชั้น
- **เซสชัน** อยู่รอดแม้แอปรีสตาร์ท เก็บแค่ค่า SHA-256 ของ token เปลี่ยน PIN/รหัสผ่าน
  หรือลบผู้ใช้จะออกจากระบบทันที
- **ยืนยันตัวตนซ้ำ (sudo)**: Terminal, การควบคุมหน้าจอ, การติดตั้งแอป และการตั้งค่าความปลอดภัย
  ต้องใส่ PIN/รหัสผ่านอีกครั้ง (มีผล 10 นาที)
- **HTTPS** พอร์ต 8443 ด้วยใบรับรองที่สร้างบนมือถือ (ดาวน์โหลดไปติดตั้งได้จากแผงควบคุม)
  บังคับเปลี่ยน HTTP → HTTPS ได้ ตรวจ `Origin` กัน CSRF, คุกกี้เป็น `HttpOnly; SameSite=Strict`
  และไฟล์ HTML/SVG ในมือถือถูกเปิดแบบ sandbox จึงสวมสิทธิ์ผู้ที่ล็อกอินอยู่ไม่ได้
- **กู้คืนจากมือถือ**: แอปบนมือถือล้างรหัสผ่านผู้ดูแล ปิด 2FA ปลดล็อก และออกจากระบบทุกเครื่องได้

## ดาวน์โหลด

โหลด APK ตามเวอร์ชันที่ต้องการได้ที่หน้า [Releases](../../releases):

- `NexShare-vX.Y.Z.apk` — เวอร์ชันปกติ
- `NexShare-Root-vX.Y.Z.apk` — เวอร์ชัน Root

เปิด "ติดตั้งจากแหล่งที่ไม่รู้จัก" → ติดตั้ง → เปิดแอป → อนุญาต **เข้าถึงไฟล์ทั้งหมด**
แล้วเซิร์ฟเวอร์จะเริ่มเอง จากนั้นเปิดลิงก์ที่แสดงจากอีกเครื่องในวง Wi-Fi เดียวกัน

## บิลด์จากซอร์ส

### Linux / macOS / CI

```bash
./setup.sh           # ครั้งเดียว: Android SDK + libtorrent4j + cloudflared + busybox (ต้องมี JDK 17+)
./build.sh           # บิลด์ทั้งสองเวอร์ชันลงใน dist/   (./build.sh root|normal เลือกเวอร์ชันเดียว)
./test/run-tests.sh  # เทสบน JVM: เว็บเซิร์ฟเวอร์, crypto/TLS, ล็อกอิน, ผู้ใช้, ถังขยะ, ลิงก์แชร์, WebDAV
python test/ui/ui_test.py   # เทสหน้าเว็บในเบราว์เซอร์ (ต้อง `pip install playwright`)
```

GitHub Actions (`.github/workflows/build.yml`) รันเทสและบิลด์ APK ทุกครั้งที่ push
ถ้า push tag `v*` จะแนบ APK ลง Release ให้ ตั้ง secret `NEXSHARE_KEYSTORE_B64` และ
`NEXSHARE_KEYSTORE_PASS` เพื่อเซ็นด้วยคีย์เดิม (ไม่งั้นมือถือจะอัปเดตทับเวอร์ชันเก่าไม่ได้)

### Windows

ไม่ต้องใช้ Android Studio — สคริปต์จะโหลด JDK + Android SDK ลงใน `.tools/`
และโหลดไลบรารีลงใน `libs/` ให้เอง

```powershell
./setup.ps1          # ครั้งเดียว: โหลด JDK 17 + Android SDK + libtorrent4j + cloudflared + busybox (~620 MB)
./build.ps1          # บิลด์ทั้งสองเวอร์ชันลงใน dist/
./build.ps1 -Flavor root     # หรือเลือกเวอร์ชันเดียว: normal | root
```

ผลลัพธ์: `dist/NexShare-v<version>.apk` และ `dist/NexShare-Root-v<version>.apk`

การบิลด์รัน `aapt2 → javac → d8 → zipalign → apksigner` โดยตรง
คีย์สำหรับเซ็นจะถูกสร้างครั้งแรกและเก็บไว้ใน `.tools/` (ไม่ขึ้น git)

## โครงสร้างโปรเจกต์

```
app/AndroidManifest.xml      ไฟล์ manifest (เปลี่ยนชื่อแพ็กเกจ/ชื่อแอปตามเวอร์ชันตอนบิลด์)
app/src/com/nexshare/
  HttpServer.java            เว็บเซิร์ฟเวอร์ HTTP/1.1: keep-alive, chunked, TLS, WebSocket
  WebApi.java                ตัวจัดเส้นทาง: บังคับ HTTPS, กัน CSRF, ตรวจล็อกอิน/สิทธิ์/sudo
  Auth, Users, SessionStore  ล็อกอิน, กันเดารหัส, 2FA, บัญชีผู้ใช้, เซสชัน
  FileApi, Trash, Shares     API ของ File Station, ถังขยะ, ลิงก์แชร์
  WebDav.java                เซิร์ฟเวอร์ WebDAV ที่ /dav/
  SystemApi.java             สถานะระบบ, การตั้งค่า, ผู้ใช้, tunnel, Download Station
  RootApi.java               เวอร์ชัน root: Terminal, สตรีมหน้าจอ, จัดการแอป
  CertGen, TlsManager        สร้างใบรับรองและเปิด HTTPS
  Storage, Crypto, Zip       ตรวจ path, ส่งไฟล์, hash/TOTP/encoding
app/assets/web/              หน้าเว็บ: index.html, style.css, js/*.js, sw.js, manifest
app/res/                     ไอคอนแอป
test/                        เทสบน JVM (มี fake ของ Android) และเทสหน้าเว็บในเบราว์เซอร์
build.sh / setup.sh          บิลด์แบบไม่ใช้ Gradle บน Linux/macOS/CI
build.ps1 / setup.ps1        แบบเดียวกันสำหรับ Windows
docs/screenshots/            ภาพตัวอย่าง
libs/                        libtorrent4j jars + cloudflared + busybox (โหลดโดย setup)
```

## เริ่มใช้ WebDAV

ที่อยู่: `http://nexshare.local:8080/dav/` (หรือ `https://<IP มือถือ>:8443/dav/`)
เข้าสู่ระบบเป็น `admin` ด้วย PIN/รหัสผ่านผู้ดูแล หรือใช้บัญชีผู้ใช้อื่น

- **Windows**: File Explorer → This PC → *Map network drive* (Windows ส่งรหัสผ่านผ่าน `http://`
  เฉพาะเมื่อตั้ง `BasicAuthLevel` = 2 ใน Registry แนะนำใช้ `https://` หลังติดตั้งใบรับรอง)
- **macOS**: Finder → Go → *Connect to Server…* (⌘K)
- **Linux**: ใส่ `dav://…` หรือ `davs://…` ในตัวจัดการไฟล์

WebDAV ผ่านอินเทอร์เน็ตปิดไว้เป็นค่าเริ่มต้น (แผงควบคุม → บริการ) เพราะใช้ Basic auth
และถามรหัส 2FA ไม่ได้

## ข้อควรรู้และความปลอดภัย

- `usesCleartextTraffic` เปิดไว้เพราะเซิร์ฟเวอร์ยังรับ HTTP ธรรมดาในวง LAN ด้วย
  ถ้าอยู่บนเครือข่ายที่ไม่ไว้ใจให้เปิดการเข้าสู่ระบบและใช้ HTTPS (แผงควบคุม → ความปลอดภัย → บังคับใช้ HTTPS) เราเตอร์ที่เปิด AP/client isolation
  จะกันไม่ให้เครื่องคุยกันได้
- เวอร์ชัน Root จะรันคำสั่งเชลล์ด้วยสิทธิ์ uid 0 ตามที่คุณสั่ง
  Terminal, ตัวจัดการแอป และตัวติดตั้งทำงานด้วยสิทธิ์เต็มที่ ใช้ด้วยความระมัดระวัง
  เหมือนเครื่องมือ root ตัวอื่น
