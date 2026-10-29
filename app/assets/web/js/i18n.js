/* NexShare web desktop — Theme switcher (Light / Dark) and Thai / English UI localization. */
'use strict';

const I18n = (() => {
  // Dictionary mapping Thai UI strings to English
  const EN = {
    'ใส่ PIN ที่แสดงในแอปบนมือถือ': 'Enter the PIN shown in the mobile app',
    'ใส่รหัสผ่านผู้ดูแล': 'Enter administrator password',
    'ใส่รหัสผ่านของบัญชีนี้': 'Enter password for this account',
    'ชื่อผู้ใช้ (เว้นว่าง = ผู้ดูแล)': 'Username (blank = admin)',
    'รหัสผ่าน': 'Password',
    'รหัส 2FA 6 หลัก': '6-digit 2FA code',
    'เข้าสู่ระบบ': 'Sign in',
    'เข้าสู่ระบบด้วยบัญชีอื่น': 'Sign in with another account',
    'เมนูหลัก': 'Main Menu',
    'ค้นหาแอป': 'Search apps',
    'งานอัปโหลด': 'Upload Queue',
    'การแจ้งเตือน': 'Notifications',
    'ยังไม่มีการแจ้งเตือน': 'No notifications yet',
    'บัญชี': 'Account',
    'ค้นหาไฟล์': 'Search files',
    'ข้อมูลระบบ': 'System Info',
    'วิดเจ็ต': 'Widgets',
    'เพิ่มวิดเจ็ต': 'Add Widget',
    'ซ่อนวิดเจ็ต': 'Hide Widgets',
    'ปักหมุด': 'Pin Widgets',
    'มือถือของคุณทำงานปกติ': 'Your phone is running normally',
    'พื้นที่เก็บข้อมูลใกล้เต็ม': 'Storage is almost full',
    'แบตเตอรี่ใกล้หมด': 'Battery is low',
    'ใช้ไป': 'Used',
    'ว่าง': 'Free',
    'แบตเตอรี่': 'Battery',
    'กำลังอัปโหลด': 'Uploading',
    'ล้างรายการที่เสร็จแล้ว': 'Clear completed',
    'ซ่อน': 'Hide',
    'ออกจากระบบ': 'Sign out',
    'ผู้ดูแล': 'Admin',
    'ผู้ดูแล (เจ้าของ)': 'Admin (Owner)',
    'ผู้ดูแล (เจ้าของมือถือ)': 'Admin (Phone Owner)',
    'อ่าน/เขียน': 'Read / Write',
    'ดูอย่างเดียว': 'Read Only',
    'ตกลง': 'OK',
    'ยกเลิก': 'Cancel',
    'ยืนยัน': 'Confirm',
    'บันทึก': 'Save',
    'ลบ': 'Delete',
    'ปิด': 'Close',
    'เสร็จ': 'Done',
    'คัดลอก': 'Copy',
    'คัดลอกลิงก์แล้ว': 'Link copied',
    'รีเฟรช': 'Refresh',
    'สร้างโฟลเดอร์': 'New Folder',
    'อัปโหลด': 'Upload',
    'อัปโหลดไฟล์': 'Upload Files',
    'อัปโหลดโฟลเดอร์': 'Upload Folder',
    'ดาวน์โหลด': 'Download',
    'การทำงาน': 'Actions',
    'เปิด': 'Open',
    'เล่นใน Media Player': 'Play in Media Player',
    'ตั้งเป็นภาพพื้นหลัง': 'Set as Wallpaper',
    'สร้างลิงก์แชร์…': 'Create Share Link…',
    'สร้างลิงก์ขอไฟล์…': 'Create File Request…',
    'สร้างลิงก์ขอไฟล์': 'Create File Request',
    'สร้างลิงก์': 'Create Link',
    'เปลี่ยนชื่อ': 'Rename',
    'คัดลอกไปที่…': 'Copy to…',
    'ย้ายไปที่…': 'Move to…',
    'ตัด': 'Cut',
    'วาง': 'Paste',
    'ย้ายไปถังขยะ': 'Move to Trash',
    'ลบถาวร': 'Delete Permanently',
    'ข้อมูล': 'Properties',
    'ชื่อ': 'Name',
    'ขนาด': 'Size',
    'ชนิด': 'Type',
    'แก้ไขล่าสุด': 'Last Modified',
    'ที่เก็บข้อมูล': 'Storage',
    'ทางลัด': 'Shortcuts',
    'โฟลเดอร์ว่างเปล่า': 'This folder is empty',
    'รายการ': 'items',
    'เลือกอยู่': 'selected',
    'วางไฟล์ที่นี่เพื่ออัปโหลด': 'Drop files here to upload',
    'แผงควบคุม': 'Control Panel',
    'ทั่วไป': 'General',
    'ความปลอดภัย': 'Security',
    'ผู้ใช้': 'Users',
    'บริการ': 'Services',
    'บันทึกกิจกรรม': 'Audit Log',
    'อุปกรณ์': 'Device',
    'ชื่อเซิร์ฟเวอร์': 'Server Name',
    'รุ่น': 'Model',
    'เปิดเครื่องมาแล้ว': 'Uptime',
    'การเข้าถึงจากเครื่องอื่น': 'Remote / LAN Access',
    'บัญชีของคุณ': 'Your Account',
    'ลิงก์ที่แชร์': 'Shared Links',
    'ยังไม่มีลิงก์แชร์': 'No shared links yet',
    'สถานะ': 'Status',
    'เปิดแล้ว': 'Hits',
    'โดย': 'Owner',
    'ประเภท': 'Mode',
    'แชร์ไฟล์': 'Share',
    'ขอไฟล์': 'File Request',
    'ไม่หมดอายุ': 'Never expires',
    'หมดอายุ': 'Expired',
    'ไม่จำกัด': 'Unlimited',
    'ทั้งหมด': 'All',
    'เพิ่มผู้ใช้': 'Add User',
    'บัญชีผู้ใช้': 'User Accounts',
    'สิทธิ์': 'Role',
    'โควตาพื้นที่': 'Quota',
    'โฟลเดอร์': 'Folders',
    'ล้างประวัติ': 'Clear Log',
    'ยังไม่มีประวัติกิจกรรม': 'No activity logged yet',
    'เวลา': 'Time',
    'กิจกรรม': 'Event',
    'รายละเอียด': 'Details',
    'โหมดมืด': 'Dark Mode',
    'โหมดสว่าง': 'Light Mode',
    'คัดลอกข้อผิดพลาด': 'Copy Error',
    'เกิดข้อผิดพลาดในหน้าเว็บ': 'Web UI Error',
  };

  const TH_FROM_EN = {};
  for (const [k, v] of Object.entries(EN)) TH_FROM_EN[v] = k;

  let currentLang = store.get('lang', 'th');
  let currentTheme = store.get('theme', 'light');

  function t(str, enFallback) {
    if (currentLang !== 'en') return str;
    if (enFallback) return enFallback;
    if (EN[str]) return EN[str];
    return str;
  }

  function translateNode(root) {
    if (!root) return;
    const dict = currentLang === 'en' ? EN : TH_FROM_EN;
    const walk = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    let node;
    while ((node = walk.nextNode())) {
      const raw = node.nodeValue;
      if (!raw) continue;
      const trimmed = raw.trim();
      if (trimmed && dict[trimmed]) {
        node.nodeValue = raw.replace(trimmed, dict[trimmed]);
      }
    }
    if (root.querySelectorAll) {
      root.querySelectorAll('[placeholder],[title]').forEach(elNode => {
        const ph = elNode.getAttribute('placeholder');
        if (ph && dict[ph]) elNode.setAttribute('placeholder', dict[ph]);
        const ti = elNode.getAttribute('title');
        if (ti && dict[ti]) elNode.setAttribute('title', dict[ti]);
      });
    }
  }

  function applyTheme(theme) {
    currentTheme = theme === 'dark' ? 'dark' : 'light';
    document.documentElement.setAttribute('data-theme', currentTheme);
    store.set('theme', currentTheme);
    document.querySelectorAll('.theme-toggle-btn').forEach(btn => {
      btn.textContent = currentTheme === 'dark' ? '☀️' : '🌙';
      btn.title = currentTheme === 'dark' ? t('โหมดสว่าง', 'Light Mode') : t('โหมดมืด', 'Dark Mode');
    });
  }

  function toggleTheme() {
    applyTheme(currentTheme === 'dark' ? 'light' : 'dark');
  }

  function applyLang(lang) {
    currentLang = lang === 'en' ? 'en' : 'th';
    document.documentElement.lang = currentLang;
    store.set('lang', currentLang);
    document.querySelectorAll('.lang-toggle-btn').forEach(btn => {
      btn.textContent = currentLang === 'en' ? 'EN' : 'TH';
      btn.title = currentLang === 'en' ? 'Switch to Thai (ภาษาไทย)' : 'Switch to English';
    });
    translateNode(document.body);
  }

  function toggleLang() {
    applyLang(currentLang === 'en' ? 'th' : 'en');
    toast(currentLang === 'en' ? 'Language: English' : 'ภาษา: ไทย');
  }

  function init() {
    applyTheme(currentTheme);
    applyLang(currentLang);
    if (typeof MutationObserver !== 'undefined' && document.body) {
      const obs = new MutationObserver(mutations => {
        if (currentLang !== 'en') return;
        for (const m of mutations) {
          for (const n of m.addedNodes) {
            if (n.nodeType === 1) translateNode(n);
            else if (n.nodeType === 3 && n.parentElement) translateNode(n.parentElement);
          }
        }
      });
      obs.observe(document.body, { childList: true, subtree: true });
    }
  }

  return { init, t, applyTheme, toggleTheme, applyLang, toggleLang, get lang() { return currentLang; }, get theme() { return currentTheme; } };
})();

const t = (s, en) => I18n.t(s, en);
