package com.doubao.autocheckin;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private TextView statusAccessibility;
    private TextView statusBattery;
    private TextView statusAlarm;
    private TextView tvNextRun;
    private TextView tvLog;
    private TimePicker timePicker;
    private EditText etPackage;
    private CheckBox cbEnabled;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            loadLog();
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Notifier.init(this);

        statusAccessibility = findViewById(R.id.statusAccessibility);
        statusBattery = findViewById(R.id.statusBattery);
        statusAlarm = findViewById(R.id.statusAlarm);
        tvNextRun = findViewById(R.id.tvNextRun);
        tvLog = findViewById(R.id.tvLog);
        timePicker = findViewById(R.id.timePicker);
        etPackage = findViewById(R.id.etPackage);
        cbEnabled = findViewById(R.id.cbEnabled);

        timePicker.setIs24HourView(true);
        if (Build.VERSION.SDK_INT >= 23) {
            timePicker.setHour(Config.hour(this));
            timePicker.setMinute(Config.minute(this));
        } else {
            timePicker.setCurrentHour(Config.hour(this));
            timePicker.setCurrentMinute(Config.minute(this));
        }
        etPackage.setText(Config.targetPackage(this));
        cbEnabled.setChecked(Config.isEnabled(this));

        findViewById(R.id.btnAccessibility).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btnBattery).setOnClickListener(v -> requestIgnoreBattery());
        findViewById(R.id.btnAlarm).setOnClickListener(v -> requestExactAlarm());
        findViewById(R.id.btnNotify).setOnClickListener(v -> requestNotifications());
        findViewById(R.id.btnSave).setOnClickListener(v -> saveConfig());
        findViewById(R.id.btnRunNow).setOnClickListener(v -> runNow());
        findViewById(R.id.btnRefreshLog).setOnClickListener(v -> loadLog());
        findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            Logger.clear(this);
            loadLog();
            toast("日志已清空");
        });
        findViewById(R.id.btnShot).setOnClickListener(v -> showLastShot());

        refreshStatus();
        loadLog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        loadLog();
        handler.removeCallbacks(autoRefresh);
        handler.postDelayed(autoRefresh, 3000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(autoRefresh);
    }

    // ---------------------------------------------------------------- 状态

    private boolean isAccessibilityOn() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        ComponentName cn = new ComponentName(this, CheckinAccessibilityService.class);
        String flat = cn.flattenToString();
        String shortFlat = cn.flattenToShortString();
        return enabled.contains(flat) || enabled.contains(shortFlat);
    }

    private void refreshStatus() {
        boolean on = isAccessibilityOn();
        statusAccessibility.setText(on ? "无障碍服务：已开启（可自动执行）" : "无障碍服务：未开启");

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        statusBattery.setText(ignoring ? "电池优化：已加入白名单" : "电池优化：未加入白名单（可能被杀后台）");

        if (Build.VERSION.SDK_INT >= 31) {
            android.app.AlarmManager am = getSystemService(android.app.AlarmManager.class);
            boolean can = am != null && am.canScheduleExactAlarms();
            statusAlarm.setText(can ? "精确闹钟：已允许" : "精确闹钟：未允许（触发可能延迟）");
        } else {
            statusAlarm.setText("精确闹钟：系统默认允许");
        }

        long next = Config.nextTrigger(this);
        if (next <= 0) {
            tvNextRun.setText("下次执行：未排程（保存配置后会排程）");
        } else {
            tvNextRun.setText("下次执行："
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(next))
                    + "     上次结果：" + Config.lastResult(this));
        }
    }

    // ---------------------------------------------------------------- 权限

    private void requestIgnoreBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void requestExactAlarm() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                toast("请手动在系统设置中允许「闹钟与提醒」");
            }
        } else {
            toast("当前系统无需此权限");
        }
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
        } else {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.areNotificationsEnabled()) {
                toast("通知已开启");
            } else {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(i);
            }
        }
    }

    // ---------------------------------------------------------------- 操作

    private void saveConfig() {
        int hour;
        int minute;
        if (Build.VERSION.SDK_INT >= 23) {
            hour = timePicker.getHour();
            minute = timePicker.getMinute();
        } else {
            hour = timePicker.getCurrentHour();
            minute = timePicker.getCurrentMinute();
        }
        String pkgText = etPackage.getText().toString().trim();
        if (TextUtils.isEmpty(pkgText)) {
            pkgText = Config.DEF_PACKAGE;
            etPackage.setText(pkgText);
        }
        Config.setTime(this, hour, minute);
        Config.setTargetPackage(this, pkgText);
        Config.setEnabled(this, cbEnabled.isChecked());
        if (cbEnabled.isChecked()) {
            CheckinScheduler.scheduleDaily(this);
        } else {
            CheckinScheduler.cancel(this);
            Config.setNextTrigger(this, 0L);
        }
        Logger.log(this, String.format(Locale.CHINA, "配置已保存：%02d:%02d 起（随机+0~30 分钟），包名 %s，开关 %s",
                hour, minute, pkgText, cbEnabled.isChecked() ? "开" : "关"));
        toast("已保存并重新排程");
        refreshStatus();
    }

    private void runNow() {
        CheckinAccessibilityService svc = CheckinAccessibilityService.getInstance();
        if (svc == null) {
            toast("无障碍服务未运行，请先开启无障碍服务");
            return;
        }
        toast("已触发，稍后看日志与通知");
        svc.startTask("手动测试");
        handler.postDelayed(this::loadLog, 2500);
    }

    private void loadLog() {
        tvLog.setText(Logger.tail(this, 200));
    }

    private void showLastShot() {
        String path = Config.lastShot(this);
        if (TextUtils.isEmpty(path)) {
            toast("还没有截图");
            return;
        }
        File f = new File(path);
        if (!f.exists()) {
            toast("截图文件不存在：" + path);
            return;
        }
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(BitmapFactory.decodeFile(path));
        new AlertDialog.Builder(this)
                .setTitle("最近一次执行截图")
                .setView(iv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001 && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            toast("未授予通知权限，结果通知将不可见");
        }
    }
}
