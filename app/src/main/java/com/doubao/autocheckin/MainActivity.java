package com.doubao.autocheckin;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    public static final String EXTRA_FROM_REMIND = "from_remind";

    private static final int COLOR_OK = Color.parseColor("#0F766E");
    private static final int COLOR_SKIP = Color.parseColor("#1D4ED8");
    private static final int COLOR_WARN = Color.parseColor("#B45309");
    private static final int COLOR_FAIL = Color.parseColor("#B91C1C");
    private static final int COLOR_MUTED = Color.parseColor("#6B7280");

    private TextView statusAccessibility;
    private TextView statusBattery;
    private TextView statusAlarm;
    private TextView tvNextRun;
    private TextView tvLog;
    private TextView tvTodayStatus;
    private TextView tvTodayDetail;
    private TextView tvStreak;
    private LinearLayout llRecords;
    private TimePicker timePicker;
    private TimePicker remindPicker;
    private EditText etPackage;
    private CheckBox cbEnabled;
    private CheckBox cbRemind;

    private boolean receiverRegistered;
    private boolean dialogShowing;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            refreshToday();
            loadRecords();
            loadLog();
            handler.postDelayed(this, 5000);
        }
    };

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !CheckinAccessibilityService.ACTION_RESULT.equals(intent.getAction())) {
                return;
            }
            int status = intent.getIntExtra(CheckinAccessibilityService.EXTRA_STATUS, 0);
            String msg = intent.getStringExtra(CheckinAccessibilityService.EXTRA_MSG);
            refreshToday();
            loadRecords();
            loadLog();
            if (status == 1 || status == 2) {
                toast(status == 1 ? "打卡成功：" + msg : "今天已签到：" + msg);
            } else {
                showFailDialog(msg);
            }
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
        tvTodayStatus = findViewById(R.id.tvTodayStatus);
        tvTodayDetail = findViewById(R.id.tvTodayDetail);
        tvStreak = findViewById(R.id.tvStreak);
        llRecords = findViewById(R.id.llRecords);
        timePicker = findViewById(R.id.timePicker);
        remindPicker = findViewById(R.id.remindPicker);
        etPackage = findViewById(R.id.etPackage);
        cbEnabled = findViewById(R.id.cbEnabled);
        cbRemind = findViewById(R.id.cbRemind);

        timePicker.setIs24HourView(true);
        remindPicker.setIs24HourView(true);
        setPicker(timePicker, Config.hour(this), Config.minute(this));
        setPicker(remindPicker, Config.remindHour(this), Config.remindMinute(this));

        etPackage.setText(Config.targetPackage(this));
        cbEnabled.setChecked(Config.isEnabled(this));
        cbRemind.setChecked(Config.isRemindEnabled(this));

        findViewById(R.id.btnAccessibility).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btnBattery).setOnClickListener(v -> requestIgnoreBattery());
        findViewById(R.id.btnAlarm).setOnClickListener(v -> requestExactAlarm());
        findViewById(R.id.btnNotify).setOnClickListener(v -> requestNotifications());
        findViewById(R.id.btnSave).setOnClickListener(v -> saveConfig());
        findViewById(R.id.btnCheckinNow).setOnClickListener(v -> runNow("手动打卡"));
        findViewById(R.id.btnRefreshRecords).setOnClickListener(v -> loadRecords());
        findViewById(R.id.btnClearRecords).setOnClickListener(v -> confirmClearRecords());
        findViewById(R.id.btnRefreshLog).setOnClickListener(v -> loadLog());
        findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            Logger.clear(this);
            loadLog();
            toast("日志已清空");
        });
        findViewById(R.id.btnShot).setOnClickListener(v -> showLastShot());

        refreshStatus();
        refreshToday();
        loadRecords();
        loadLog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerResultReceiver();
        refreshStatus();
        refreshToday();
        loadRecords();
        loadLog();
        handler.removeCallbacks(autoRefresh);
        handler.postDelayed(autoRefresh, 3000);
        handler.postDelayed(this::maybePromptCheckin, 800);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(autoRefresh);
        if (receiverRegistered) {
            try {
                unregisterReceiver(resultReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
    }

    private void registerResultReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter f = new IntentFilter(CheckinAccessibilityService.ACTION_RESULT);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(resultReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(resultReceiver, f);
            }
            receiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- 时间控件

    private void setPicker(TimePicker picker, int hour, int minute) {
        if (Build.VERSION.SDK_INT >= 23) {
            picker.setHour(hour);
            picker.setMinute(minute);
        } else {
            picker.setCurrentHour(hour);
            picker.setCurrentMinute(minute);
        }
    }

    private int pickerHour(TimePicker picker) {
        return Build.VERSION.SDK_INT >= 23 ? picker.getHour() : picker.getCurrentHour();
    }

    private int pickerMinute(TimePicker picker) {
        return Build.VERSION.SDK_INT >= 23 ? picker.getMinute() : picker.getCurrentMinute();
    }

    // ---------------------------------------------------------------- 今日状态

    private void refreshToday() {
        Records.Item today = Records.today(this);
        int streak = Records.streak(this);
        tvStreak.setText("连续打卡 " + streak + " 天");

        if (today == null) {
            tvTodayStatus.setText("今天还没打卡");
            tvTodayStatus.setTextColor(COLOR_WARN);
            tvTodayDetail.setText("等待自动执行，也可以现在手动打卡");
        } else if (today.status == Records.SUCCESS) {
            tvTodayStatus.setText("今天已打卡成功");
            tvTodayStatus.setTextColor(COLOR_OK);
            tvTodayDetail.setText(today.timeText() + " · " + today.msg
                    + (TextUtils.isEmpty(today.source) ? "" : "（" + today.source + "）"));
        } else if (today.status == Records.SKIP) {
            tvTodayStatus.setText("今天已完成（已签到）");
            tvTodayStatus.setTextColor(COLOR_SKIP);
            tvTodayDetail.setText(today.timeText() + " · " + today.msg);
        } else {
            tvTodayStatus.setText("今天打卡失败");
            tvTodayStatus.setTextColor(COLOR_FAIL);
            tvTodayDetail.setText(today.timeText() + " · " + today.msg + "，可点下方按钮重试");
        }

        boolean busy = CheckinAccessibilityService.getInstance() != null
                && CheckinAccessibilityService.getInstance().isBusy();
        TextView btn = findViewById(R.id.btnCheckinNow);
        btn.setEnabled(!busy);
        btn.setText(busy ? "打卡执行中…" : (Records.todayDone(this) ? "再执行一次" : "开始打卡"));
    }

    private void loadRecords() {
        llRecords.removeAllViews();
        List<Records.Item> items = Records.recent(this, 30);
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有打卡记录。完成一次打卡后会出现在这里。");
            empty.setTextSize(12);
            empty.setTextColor(COLOR_MUTED);
            llRecords.addView(empty);
            return;
        }
        SimpleDateFormat dayFmt = new SimpleDateFormat("MM-dd", Locale.CHINA);
        for (Records.Item it : items) {
            llRecords.addView(buildRecordRow(dayFmt.format(new Date(it.at)), it));
        }
    }

    private View buildRecordRow(String dayText, Records.Item it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 8, 0, 8);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);

        TextView left = new TextView(this);
        left.setText(dayText + "  " + it.timeText());
        left.setTextSize(13);
        left.setTextColor(Color.parseColor("#374151"));
        LinearLayout.LayoutParams lpLeft =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(left, lpLeft);

        TextView right = new TextView(this);
        right.setText(it.statusText());
        right.setTextSize(13);
        right.setGravity(Gravity.END);
        right.setTextColor(it.status == Records.SUCCESS ? COLOR_OK
                : (it.status == Records.SKIP ? COLOR_SKIP : COLOR_FAIL));
        head.addView(right, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        row.addView(head);

        TextView sub = new TextView(this);
        int streakAt = Records.streakAt(this, it.day);
        sub.setText("连续第 " + streakAt + " 天"
                + (TextUtils.isEmpty(it.source) ? "" : "  ·  " + it.source));
        sub.setTextSize(11);
        sub.setTextColor(streakAt > 0 ? COLOR_OK : COLOR_MUTED);
        row.addView(sub);

        TextView msg = new TextView(this);
        msg.setText(it.msg);
        msg.setTextSize(11);
        msg.setTextColor(COLOR_MUTED);
        row.addView(msg);

        addThumbIfAny(row, it);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#E5E7EB"));
        LinearLayout.LayoutParams lpDiv = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lpDiv.topMargin = 8;
        row.addView(divider, lpDiv);
        return row;
    }

    /** 记录里的签到截图：缩略图直出，点一下看大图。 */
    private void addThumbIfAny(LinearLayout row, Records.Item it) {
        if (TextUtils.isEmpty(it.shotPath)) {
            return;
        }
        File f = new File(it.shotPath);
        if (!f.exists()) {
            return;
        }
        android.graphics.Bitmap thumb = decodeSampled(it.shotPath, 220);
        if (thumb == null) {
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lpBox = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpBox.topMargin = 6;

        ImageView iv = new ImageView(this);
        iv.setImageBitmap(thumb);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(Color.parseColor("#E5E7EB"));
        iv.setPadding(2, 2, 2, 2);
        iv.setOnClickListener(v -> showShotDialog(it.shotPath, "签到截图 · " + it.timeText()));
        box.addView(iv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 220));
        row.addView(box, lpBox);
    }

    private android.graphics.Bitmap decodeSampled(String path, int reqHeight) {
        try {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, opts);
            int h = opts.outHeight;
            int sample = 1;
            while (h / sample > reqHeight * 2) {
                sample *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private void showShotDialog(String path, String title) {
        File f = new File(path);
        if (!f.exists()) {
            toast("截图文件不存在：" + path);
            return;
        }
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageBitmap(decodeSampled(path, 900));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(iv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void confirmClearRecords() {
        new AlertDialog.Builder(this)
                .setTitle("清空打卡记录")
                .setMessage("只会删掉本机的记录文件，不影响已经完成的历史打卡本身。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    Records.clear(this);
                    loadRecords();
                    refreshToday();
                    toast("打卡记录已清空");
                })
                .show();
    }

    // ---------------------------------------------------------------- 未打卡提醒

    private void maybePromptCheckin() {
        if (isFinishing() || dialogShowing) {
            return;
        }
        if (Records.todayDone(this)) {
            return;
        }
        Intent it = getIntent();
        boolean fromRemind = it != null
                && it.getBooleanExtra(EXTRA_FROM_REMIND, false);
        if (fromRemind && it != null) {
            it.removeExtra(EXTRA_FROM_REMIND);
        }

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
        boolean alreadyPrompted = today.equals(Config.lastPromptDay(this));
        if (!fromRemind && alreadyPrompted) {
            return;
        }
        if (!fromRemind && !pastScheduledTime()) {
            return;
        }
        Config.setLastPromptDay(this, today);
        showCheckinPrompt();
    }

    /** 当前时间是否已经过了配置的每日执行时刻。 */
    private boolean pastScheduledTime() {
        Date now = new Date();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTime(now);
        int nowMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
        return nowMinutes >= Config.hour(this) * 60 + Config.minute(this);
    }

    private void showCheckinPrompt() {
        Records.Item today = Records.today(this);
        String message;
        if (today == null) {
            message = "今天还没有打卡记录。现在手动执行一次？";
        } else {
            message = "今天最后一次执行失败：" + today.msg + "\n现在重试一次？";
        }
        dialogShowing = true;
        new AlertDialog.Builder(this)
                .setTitle("今天还没打卡")
                .setMessage(message)
                .setPositiveButton("立即打卡", (d, w) -> {
                    dialogShowing = false;
                    runNow("提醒后手动");
                })
                .setNeutralButton("30 分钟后提醒", (d, w) -> {
                    dialogShowing = false;
                    CheckinScheduler.scheduleReminderAt(this,
                            System.currentTimeMillis() + 30L * 60_000L);
                    toast("已安排 30 分钟后提醒");
                })
                .setNegativeButton("今天不再提醒", (d, w) -> {
                    dialogShowing = false;
                    toast("已跳过今天的弹窗提醒（通知仍会按设置发送）");
                })
                .setOnDismissListener(d -> dialogShowing = false)
                .show();
    }

    private void showFailDialog(String msg) {
        if (isFinishing()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("打卡失败")
                .setMessage(TextUtils.isEmpty(msg) ? "本次执行失败，请查看日志" : msg)
                .setPositiveButton("再试一次", (d, w) -> runNow("失败后重试"))
                .setNegativeButton("查看日志", (d, w) -> loadLog())
                .show();
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
        long nextRemind = Config.nextRemind(this);
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        StringBuilder sb = new StringBuilder();
        sb.append(next <= 0 ? "下次执行：未排程（保存配置后会排程）"
                : "下次执行：" + fmt.format(new Date(next)));
        if (Config.isRemindEnabled(this) && nextRemind > 0) {
            sb.append("     未打卡提醒：").append(fmt.format(new Date(nextRemind)));
        }
        tvNextRun.setText(sb.toString());
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
        int hour = pickerHour(timePicker);
        int minute = pickerMinute(timePicker);
        int rHour = pickerHour(remindPicker);
        int rMinute = pickerMinute(remindPicker);
        String pkgText = etPackage.getText().toString().trim();
        if (TextUtils.isEmpty(pkgText)) {
            pkgText = Config.DEF_PACKAGE;
            etPackage.setText(pkgText);
        }
        Config.setTime(this, hour, minute);
        Config.setTargetPackage(this, pkgText);
        Config.setEnabled(this, cbEnabled.isChecked());
        Config.setRemindTime(this, rHour, rMinute);
        Config.setRemindEnabled(this, cbRemind.isChecked());

        if (cbEnabled.isChecked()) {
            CheckinScheduler.scheduleDaily(this);
        } else {
            CheckinScheduler.cancel(this);
            Config.setNextTrigger(this, 0L);
        }
        CheckinScheduler.scheduleReminderDaily(this);

        Logger.log(this, String.format(Locale.CHINA,
                "配置已保存：%02d:%02d 起（随机+0~60 分钟），包名 %s，自动签到 %s，未打卡提醒 %02d:%02d（%s）",
                hour, minute, pkgText, cbEnabled.isChecked() ? "开" : "关",
                rHour, rMinute, cbRemind.isChecked() ? "开" : "关"));
        toast("已保存并重新排程");
        refreshStatus();
    }

    private void runNow(String source) {
        CheckinAccessibilityService svc = CheckinAccessibilityService.getInstance();
        if (svc == null) {
            new AlertDialog.Builder(this)
                    .setTitle("无障碍服务未开启")
                    .setMessage("自动点击依赖无障碍服务。请到「设置 → 无障碍 → 已下载的服务」里启用「滴滴充电自动签到」，然后回来重试。")
                    .setPositiveButton("去开启", (d, w) ->
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        if (svc.isBusy()) {
            toast("上一次还在执行，稍等一下");
            return;
        }
        if (!isAccessibilityOn()) {
            toast("无障碍服务显示未开启，可能刚被系统回收，去设置里重新打开");
            return;
        }
        toast("已开始执行，结果会自动记录并通知");
        svc.startTask(source);
        refreshToday();
        handler.postDelayed(() -> {
            refreshToday();
            loadRecords();
            loadLog();
        }, 2500);
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
            toast("未授予通知权限，结果通知与未打卡提醒将不可见");
        }
    }
}
