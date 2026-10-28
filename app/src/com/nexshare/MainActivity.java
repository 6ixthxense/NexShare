package com.nexshare;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings.Secure;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;

public class MainActivity extends Activity {

    private static final int BLUE = 0xFF1E6FD9;
    private TextView status, urlHost, urlIp, urlHttps, pinView, pinNote, permNote, secState;
    private Button clearPwBtn, clearTotpBtn;
    private Button toggle, permBtn;
    private Switch pinSwitch;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BLUE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFEEF3F9);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(BLUE);
        header.setPadding(dp(24), dp(28), dp(24), dp(28));
        TextView title = text("NexShare", 28, Color.WHITE, true);
        TextView sub = text("แชร์ไฟล์ในมือถือผ่านหน้าเว็บในวง Wi-Fi เดียวกัน", 14, 0xDDFFFFFF, false);
        status = text("", 15, Color.WHITE, true);
        status.setPadding(0, dp(14), 0, 0);
        header.addView(title);
        header.addView(sub);
        header.addView(status);
        root.addView(header);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(24));
        root.addView(body);

        toggle = new Button(this);
        toggle.setAllCaps(false);
        toggle.setTextSize(17);
        toggle.setTextColor(Color.WHITE);
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (ServerService.running) stopService(new Intent(MainActivity.this, ServerService.class));
                else startServer();
                handler.postDelayed(new Runnable() {
                    public void run() {
                        refresh();
                    }
                }, 400);
            }
        });
        body.addView(toggle, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout card = card(body);
        card.addView(text("เปิดจากคอม/มือถือเครื่องอื่นที่ต่อ Wi-Fi เดียวกัน", 13, 0xFF6B7785, false));
        urlHost = link(card);
        urlIp = link(card);
        urlHttps = link(card);
        TextView hint = text("ถ้าเปิด nexshare.local ไม่ได้ ให้ใช้ลิงก์ที่เป็นเลข IP แทน", 12, 0xFF8A96A3, false);
        hint.setPadding(0, dp(8), 0, 0);
        card.addView(hint);

        LinearLayout pinCard = card(body);
        pinSwitch = new Switch(this);
        pinSwitch.setText("ต้องใส่ PIN ก่อนเข้า");
        pinSwitch.setTextSize(15);
        pinSwitch.setChecked(Settings.pinEnabled(this));
        pinSwitch.setOnCheckedChangeListener((v, on) -> {
            Settings.setPinEnabled(this, on);
            refresh();
        });
        pinCard.addView(pinSwitch);
        pinView = text("", 34, 0xFF1D2733, true);
        pinView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pinView.setLetterSpacing(0.15f);
        pinView.setPadding(0, dp(8), 0, dp(4));
        pinCard.addView(pinView);
        pinNote = text("", 12, 0xFF8A96A3, false);
        pinCard.addView(pinNote);
        Button newPin = new Button(this);
        newPin.setAllCaps(false);
        newPin.setText("สุ่ม PIN ใหม่");
        newPin.setOnClickListener(v -> {
            Settings.regeneratePin(this);
            refresh();
        });
        pinCard.addView(newPin);

        // Security & recovery: the phone is the way back in if a password or authenticator is lost.
        LinearLayout secCard = card(body);
        secCard.addView(text("ความปลอดภัย", 15, 0xFF1D2733, true));
        secState = text("", 13, 0xFF4A5663, false);
        secState.setPadding(0, dp(6), 0, dp(6));
        secCard.addView(secState);
        clearPwBtn = button(secCard, "ล้างรหัสผ่านผู้ดูแล (กลับไปใช้ PIN)", () -> confirm(
            "ล้างรหัสผ่านผู้ดูแล?", "ผู้ดูแลจะกลับไปเข้าสู่ระบบด้วย PIN และทุกเครื่องจะถูกออกจากระบบ",
            () -> Settings.setAdminPassword(this, null)));
        clearTotpBtn = button(secCard, "ปิดการยืนยันตัวตน 2 ขั้น (2FA)", () -> confirm(
            "ปิด 2FA?", "ใช้เมื่อทำแอปยืนยันตัวตนหาย สามารถตั้งใหม่ได้ในแผงควบคุมบนเว็บ",
            () -> Settings.setTotp(this, "", Settings.TOTP_OFF)));
        button(secCard, "ปลดล็อกการเข้าสู่ระบบที่ถูกระงับ", () -> {
            Auth.get(this).resetLockouts();
            Toast.makeText(this, "ปลดล็อกแล้ว ลองเข้าสู่ระบบใหม่ได้ทันที", Toast.LENGTH_SHORT).show();
        });
        button(secCard, "ออกจากระบบทุกเครื่อง", () -> confirm(
            "ออกจากระบบทุกเครื่อง?", "ทุกเบราว์เซอร์ที่เข้าสู่ระบบไว้จะต้องเข้าสู่ระบบใหม่",
            () -> SessionStore.get(this).revokeAll()));

        LinearLayout screenCard = card(body);
        Switch screenSwitch = new Switch(this);
        screenSwitch.setText("เปิดหน้าจอค้างไว้ขณะแชร์");
        screenSwitch.setTextSize(15);
        screenSwitch.setChecked(Settings.keepScreenOn(this));
        screenSwitch.setOnCheckedChangeListener((v, on) -> {
            Settings.setKeepScreenOn(this, on);
            applyKeepScreen();
            if (ServerService.running) startServer(); // re-apply svc power stayon for root
        });
        screenCard.addView(screenSwitch);
        screenCard.addView(text(Flavor.ROOT ? "รุ่น Root: จอจะติดตลอดแม้สลับไปแอปอื่น (ใช้ svc power stayon)" : "จอจะติดเฉพาะตอนเปิดหน้าแอปนี้", 12, 0xFF8A96A3, false));

        LinearLayout permCard = card(body);
        permNote = text("", 14, 0xFF1D2733, false);
        permCard.addView(permNote);
        permBtn = new Button(this);
        permBtn.setAllCaps(false);
        permBtn.setText("อนุญาตให้เข้าถึงไฟล์ทั้งหมด");
        permBtn.setOnClickListener(v -> requestFileAccess());
        permCard.addView(permBtn);

        if (Flavor.ROOT) {
            LinearLayout rootCard = card(body);
            TextView rootState = text("", 14, 0xFF1D2733, false);
            rootCard.addView(text("NexShare รุ่น Root", 15, 0xFF1D2733, true));
            rootCard.addView(rootState);
            Button rootBtn = new Button(this);
            rootBtn.setAllCaps(false);
            rootBtn.setText("ขอสิทธิ์ root");
            rootBtn.setOnClickListener(v -> new Thread(() -> {
                RootShell.resetCheck();
                boolean ok = RootShell.available();
                runOnUiThread(() -> rootState.setText(ok ? "✓ ได้สิทธิ์ root แล้ว — ใช้ Terminal, ดูหน้าจอ และติดตั้งแอปได้" : "ยังไม่ได้สิทธิ์ root (กด Grant ในแอป Magisk/SuperSU)"));
            }).start());
            rootCard.addView(rootBtn);
            new Thread(() -> {
                boolean ok = RootShell.available();
                runOnUiThread(() -> rootState.setText(ok ? "✓ ได้สิทธิ์ root แล้ว — ใช้ Terminal, ดูหน้าจอ และติดตั้งแอปได้" : "ยังไม่ได้สิทธิ์ root (กดปุ่มด้านล่างเพื่อขอ)"));
            }).start();
        }

        applyKeepScreen();
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        if (!hasFileAccess()) requestFileAccess();
        else if (!ServerService.running) startServer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasFileAccess() && !ServerService.running && !startedOnce) startServer();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private boolean startedOnce;

    private void applyKeepScreen() {
        if (Settings.keepScreenOn(this)) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void startServer() {
        startedOnce = true;
        Intent i = new Intent(this, ServerService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private boolean hasFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == 1 && hasFileAccess() && !ServerService.running) startServer();
        refresh();
    }

    private void refresh() {
        boolean on = ServerService.running;
        Inet4Address ip = MdnsResponder.lanAddress();
        if (on) status.setText("● กำลังทำงาน");
        else if (ServerService.lastError != null) status.setText("● เปิดเซิร์ฟเวอร์ไม่สำเร็จ: " + ServerService.lastError);
        else status.setText("○ หยุดอยู่");
        toggle.setText(on ? "หยุดแชร์" : "เริ่มแชร์");
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(on ? 0xFFE5484D : BLUE);
        toggle.setBackground(bg);

        String host = "http://" + MdnsResponder.HOST + ":" + ServerService.PORT;
        urlHost.setText(host);
        urlHost.setTag(host);
        if (ip != null) {
            String ipUrl = "http://" + ip.getHostAddress() + ":" + ServerService.PORT;
            urlIp.setText(ipUrl);
            urlIp.setTag(ipUrl);
        } else {
            urlIp.setText("ยังไม่ได้ต่อ Wi-Fi / Hotspot");
            urlIp.setTag(null);
        }
        urlHost.setAlpha(on ? 1f : 0.4f);
        urlIp.setAlpha(on ? 1f : 0.4f);

        if (ServerService.httpsRunning && ip != null) {
            String https = "https://" + ip.getHostAddress() + ":" + ServerService.HTTPS_PORT;
            urlHttps.setText(https + "  🔒");
            urlHttps.setTag(https);
            urlHttps.setVisibility(View.VISIBLE);
        } else {
            urlHttps.setVisibility(View.GONE);
        }
        urlHttps.setAlpha(on ? 1f : 0.4f);

        boolean pinOn = Settings.pinEnabled(this);
        boolean hasPw = Settings.hasAdminPassword(this);
        pinView.setText(hasPw ? "ใช้รหัสผ่าน" : pinOn ? Settings.pin(this) : "ไม่ใช้ PIN");
        pinView.setTextSize(hasPw || !pinOn ? 18 : 34);
        String note = hasPw ? "ผู้ดูแลเข้าสู่ระบบด้วยรหัสผ่านที่ตั้งไว้บนเว็บ (PIN ใช้ไม่ได้แล้ว)"
            : !pinOn && Settings.loginRequired(this) ? "มีบัญชีผู้ใช้อยู่ จึงยังต้องเข้าสู่ระบบเสมอ" : "";
        if (!pinOn) note = (note.isEmpty() ? "" : note + "\n") + "การเข้าผ่านอินเทอร์เน็ต (Remote Access) ต้องเข้าสู่ระบบเสมอ";
        pinNote.setText(note);
        pinNote.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);

        String totp = Settings.totpMode(this);
        StringBuilder sec = new StringBuilder();
        sec.append(hasPw ? "✓ ตั้งรหัสผ่านผู้ดูแลแล้ว" : "• ผู้ดูแลใช้ PIN 6 หลัก (ตั้งรหัสผ่านได้ในแผงควบคุมบนเว็บ)");
        sec.append('\n').append(Settings.TOTP_OFF.equals(totp) ? "• ยังไม่เปิด 2FA"
            : "✓ เปิด 2FA " + (Settings.TOTP_ALWAYS.equals(totp) ? "ทุกการเข้าสู่ระบบ" : "เมื่อเข้าผ่านอินเทอร์เน็ต"));
        sec.append('\n').append(ServerService.httpsRunning ? "✓ HTTPS พอร์ต " + ServerService.HTTPS_PORT
            : Settings.httpsEnabled(this) ? "• HTTPS กำลังเริ่ม" + (ServerService.httpsError != null ? " (ผิดพลาด: " + ServerService.httpsError + ")" : "") : "• ปิด HTTPS");
        sec.append('\n').append("เข้าสู่ระบบค้างไว้ ").append(SessionStore.get(this).count()).append(" เครื่อง");
        secState.setText(sec.toString());
        clearPwBtn.setVisibility(hasPw ? View.VISIBLE : View.GONE);
        clearTotpBtn.setVisibility(Settings.TOTP_OFF.equals(totp) ? View.GONE : View.VISIBLE);

        boolean access = hasFileAccess();
        permNote.setText(access ? "✓ ได้รับสิทธิ์เข้าถึงไฟล์แล้ว" : "ยังไม่ได้รับสิทธิ์เข้าถึงไฟล์ หน้าเว็บจะเปิดโฟลเดอร์ไม่ได้");
        permBtn.setVisibility(access ? View.GONE : View.VISIBLE);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.WHITE);
        c.setBackground(bg);
        c.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        parent.addView(c, lp);
        return c;
    }

    private TextView link(LinearLayout parent) {
        final TextView t = text("", 18, BLUE, true);
        t.setPadding(0, dp(10), 0, 0);
        t.setOnClickListener(v -> {
            Object url = v.getTag();
            if (url == null) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("NexShare", url.toString()));
            Toast.makeText(this, "คัดลอกลิงก์แล้ว", Toast.LENGTH_SHORT).show();
        });
        parent.addView(t);
        return t;
    }

    private Button button(LinearLayout parent, String label, final Runnable run) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setOnClickListener(v -> run.run());
        parent.addView(b);
        return b;
    }

    private void confirm(String title, String message, final Runnable action) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("ยืนยัน", (d, w) -> {
                action.run();
                refresh();
                Toast.makeText(this, "เรียบร้อย", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("ยกเลิก", null).show();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
