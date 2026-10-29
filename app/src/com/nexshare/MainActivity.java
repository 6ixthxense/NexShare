package com.nexshare;

import android.Manifest;
import android.app.Activity;
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
import android.os.PowerManager;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;

import static com.nexshare.MainUi.BLUE;
import static com.nexshare.MainUi.button;
import static com.nexshare.MainUi.card;
import static com.nexshare.MainUi.confirm;
import static com.nexshare.MainUi.dp;
import static com.nexshare.MainUi.link;
import static com.nexshare.MainUi.text;

public class MainActivity extends Activity {

    private TextView status, urlHost, urlIp, urlHttps, urlTailscale, urlTailscaleHttps;
    private TextView pinView, pinNote, permNote, secState, batteryNote;
    private Button clearPwBtn, clearTotpBtn, batteryBtn;
    private Button toggle, permBtn;
    private Switch pinSwitch, bootSwitch;
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
        header.setPadding(dp(this, 24), dp(this, 28), dp(this, 24), dp(this, 28));
        TextView title = text(this, "NexShare", 28, Color.WHITE, true);
        TextView sub = text(this, "แชร์ไฟล์ในมือถือผ่านหน้าเว็บในวง Wi-Fi หรือ Tailscale", 14, 0xDDFFFFFF, false);
        status = text(this, "", 15, Color.WHITE, true);
        status.setPadding(0, dp(this, 14), 0, 0);
        header.addView(title);
        header.addView(sub);
        header.addView(status);
        root.addView(header);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 24));
        root.addView(body);

        toggle = new Button(this);
        toggle.setAllCaps(false);
        toggle.setTextSize(17);
        toggle.setTextColor(Color.WHITE);
        toggle.setOnClickListener(v -> {
            if (ServerService.running) stopService(new Intent(MainActivity.this, ServerService.class));
            else startServer();
            handler.postDelayed(this::refresh, 400);
        });
        body.addView(toggle, new LinearLayout.LayoutParams(-1, dp(this, 56)));

        LinearLayout urlCard = card(this, body);
        urlCard.addView(text(this, "เปิดจากคอม/มือถือเครื่องอื่น (แตะที่ลิงก์เพื่อคัดลอก)", 13, 0xFF6B7785, false));
        urlHost = link(this, urlCard);
        urlIp = link(this, urlCard);
        urlHttps = link(this, urlCard);
        urlTailscale = link(this, urlCard);
        urlTailscaleHttps = link(this, urlCard);
        TextView hint = text(this, "ถ้าเปิด nexshare.local ไม่ได้ ให้ใช้ลิงก์ที่เป็นเลข IP แทน", 12, 0xFF8A96A3, false);
        hint.setPadding(0, dp(this, 8), 0, 0);
        urlCard.addView(hint);

        LinearLayout pinCard = card(this, body);
        pinSwitch = new Switch(this);
        pinSwitch.setText("ต้องใส่ PIN ก่อนเข้า");
        pinSwitch.setTextSize(15);
        pinSwitch.setChecked(Settings.pinEnabled(this));
        pinSwitch.setOnCheckedChangeListener((v, on) -> {
            Settings.setPinEnabled(this, on);
            refresh();
        });
        pinCard.addView(pinSwitch);
        pinView = text(this, "", 34, 0xFF1D2733, true);
        pinView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pinView.setLetterSpacing(0.15f);
        pinView.setPadding(0, dp(this, 8), 0, dp(4));
        pinCard.addView(pinView);
        pinNote = text(this, "", 12, 0xFF8A96A3, false);
        pinCard.addView(pinNote);
        button(this, pinCard, "สุ่ม PIN ใหม่", () -> {
            Settings.regeneratePin(this);
            refresh();
        });

        LinearLayout secCard = card(this, body);
        secCard.addView(text(this, "ความปลอดภัย", 15, 0xFF1D2733, true));
        secState = text(this, "", 13, 0xFF4A5663, false);
        secState.setPadding(0, dp(this, 6), 0, dp(6));
        secCard.addView(secState);
        clearPwBtn = button(this, secCard, "ล้างรหัสผ่านผู้ดูแล (กลับไปใช้ PIN)", () -> confirm(this,
            "ล้างรหัสผ่านผู้ดูแล?", "ผู้ดูแลจะกลับไปเข้าสู่ระบบด้วย PIN และทุกเครื่องจะถูกออกจากระบบ",
            () -> Settings.setAdminPassword(this, null), this::refresh));
        clearTotpBtn = button(this, secCard, "ปิดการยืนยันตัวตน 2 ขั้น (2FA)", () -> confirm(this,
            "ปิด 2FA?", "ใช้เมื่อทำแอปยืนยันตัวตนหาย สามารถตั้งใหม่ได้ในแผงควบคุมบนเว็บ",
            () -> Settings.setTotp(this, "", Settings.TOTP_OFF), this::refresh));
        button(this, secCard, "ปลดล็อกการเข้าสู่ระบบที่ถูกระงับ", () -> {
            Auth.get(this).resetLockouts();
            Toast.makeText(this, "ปลดล็อกแล้ว ลองเข้าสู่ระบบใหม่ได้ทันที", Toast.LENGTH_SHORT).show();
        });
        button(this, secCard, "ออกจากระบบทุกเครื่อง", () -> confirm(this,
            "ออกจากระบบทุกเครื่อง?", "ทุกเบราว์เซอร์ที่เข้าสู่ระบบไว้จะต้องเข้าสู่ระบบใหม่",
            () -> SessionStore.get(this).revokeAll(), this::refresh));

        LinearLayout powerCard = card(this, body);
        powerCard.addView(text(this, "การทำงานเบื้องหลัง & พลังงาน", 15, 0xFF1D2733, true));
        bootSwitch = new Switch(this);
        bootSwitch.setText("เปิดแชร์อัตโนมัติเมื่อเปิดเครื่อง");
        bootSwitch.setTextSize(15);
        bootSwitch.setPadding(0, dp(this, 8), 0, dp(4));
        bootSwitch.setChecked(Settings.startOnBoot(this));
        bootSwitch.setOnCheckedChangeListener((v, on) -> Settings.setStartOnBoot(this, on));
        powerCard.addView(bootSwitch);

        Switch screenSwitch = new Switch(this);
        screenSwitch.setText("เปิดหน้าจอค้างไว้ขณะแชร์");
        screenSwitch.setTextSize(15);
        screenSwitch.setChecked(Settings.keepScreenOn(this));
        screenSwitch.setOnCheckedChangeListener((v, on) -> {
            Settings.setKeepScreenOn(this, on);
            applyKeepScreen();
            if (ServerService.running) startServer();
        });
        powerCard.addView(screenSwitch);
        powerCard.addView(text(this, Flavor.ROOT
            ? "รุ่น Root: จอจะติดตลอดแม้สลับไปแอปอื่น (ใช้ svc power stayon)"
            : "จอจะติดเฉพาะตอนเปิดหน้าแอปนี้", 12, 0xFF8A96A3, false));

        batteryNote = text(this, "", 13, 0xFF4A5663, false);
        batteryNote.setPadding(0, dp(this, 10), 0, dp(4));
        powerCard.addView(batteryNote);
        batteryBtn = button(this, powerCard, "ยกเว้นการประหยัดแบตเตอรี่ (แนะนำ)", this::requestIgnoreBatteryOptimizations);

        LinearLayout permCard = card(this, body);
        permNote = text(this, "", 14, 0xFF1D2733, false);
        permCard.addView(permNote);
        permBtn = button(this, permCard, "อนุญาตให้เข้าถึงไฟล์ทั้งหมด", this::requestFileAccess);

        if (Flavor.ROOT) {
            LinearLayout rootCard = card(this, body);
            TextView rootState = text(this, "", 14, 0xFF1D2733, false);
            rootCard.addView(text(this, "NexShare รุ่น Root", 15, 0xFF1D2733, true));
            rootCard.addView(rootState);
            button(this, rootCard, "ขอสิทธิ์ root", () -> new Thread(() -> {
                RootShell.resetCheck();
                boolean ok = RootShell.available();
                runOnUiThread(() -> rootState.setText(ok ? "✓ ได้สิทธิ์ root แล้ว — ใช้ Terminal, ดูหน้าจอ และติดตั้งแอปได้" : "ยังไม่ได้สิทธิ์ root (กด Grant ในแอป Magisk/SuperSU)"));
            }).start());
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

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) { }
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
        Inet4Address ts = MdnsResponder.tailscaleAddress();
        if (on) status.setText("● กำลังทำงาน");
        else if (ServerService.lastError != null) status.setText("● เปิดเซิร์ฟเวอร์ไม่สำเร็จ: " + ServerService.lastError);
        else status.setText("○ หยุดอยู่");
        toggle.setText(on ? "หยุดแชร์" : "เริ่มแชร์");
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(this, 12));
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

        if (ts != null) {
            String tsUrl = "http://" + ts.getHostAddress() + ":" + ServerService.PORT;
            urlTailscale.setText(tsUrl + "  (Tailscale)");
            urlTailscale.setTag(tsUrl);
            urlTailscale.setVisibility(View.VISIBLE);
            urlTailscale.setAlpha(on ? 1f : 0.4f);
            if (ServerService.httpsRunning) {
                String tsHttps = "https://" + ts.getHostAddress() + ":" + ServerService.HTTPS_PORT;
                urlTailscaleHttps.setText(tsHttps + "  (Tailscale 🔒)");
                urlTailscaleHttps.setTag(tsHttps);
                urlTailscaleHttps.setVisibility(View.VISIBLE);
                urlTailscaleHttps.setAlpha(on ? 1f : 0.4f);
            } else {
                urlTailscaleHttps.setVisibility(View.GONE);
            }
        } else {
            urlTailscale.setVisibility(View.GONE);
            urlTailscaleHttps.setVisibility(View.GONE);
        }

        if (bootSwitch != null && bootSwitch.isChecked() != Settings.startOnBoot(this)) {
            bootSwitch.setChecked(Settings.startOnBoot(this));
        }

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
        secState.setText(MainUi.buildSecuritySummary(this));
        clearPwBtn.setVisibility(hasPw ? View.VISIBLE : View.GONE);
        clearTotpBtn.setVisibility(Settings.TOTP_OFF.equals(totp) ? View.GONE : View.VISIBLE);

        boolean ignoringBat = isIgnoringBatteryOptimizations();
        batteryNote.setText(ignoringBat
            ? "✓ ยกเว้นการประหยัดแบตเตอรี่แล้ว — เปิดแชร์ทิ้งไว้นาน ๆ ได้โดยไม่ถูกระบบสั่งปิด"
            : "• แนะนำให้กดยกเว้นการประหยัดแบตเตอรี่ เพื่อไม่ให้ระบบปิดเซิร์ฟเวอร์เมื่อปิดหน้าจอ");
        batteryBtn.setVisibility(ignoringBat ? View.GONE : View.VISIBLE);

        boolean access = hasFileAccess();
        permNote.setText(access ? "✓ ได้รับสิทธิ์เข้าถึงไฟล์แล้ว" : "ยังไม่ได้รับสิทธิ์เข้าถึงไฟล์ หน้าเว็บจะเปิดโฟลเดอร์ไม่ได้");
        permBtn.setVisibility(access ? View.GONE : View.VISIBLE);
    }
}
