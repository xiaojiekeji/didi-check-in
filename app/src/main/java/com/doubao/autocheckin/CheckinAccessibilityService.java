package com.doubao.autocheckin;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 核心执行器：截图 + OCR 文字识别 + 坐标点击。
 *
 * 流程（与滴滴当前页面一致）：
 * 打开 App → 关掉所有弹窗 → 点顶部「车主」→ 关弹窗 → 点「滴滴充电」→ 关弹窗
 * → 点右下角「我的」→ 点「会员中心」→ 页面下滑 → 找到「签到」并点击 → 校验成功并截图
 *
 * Android 11 以下没有截图能力，自动降级为读取控件文字（无障碍节点）。
 */
public class CheckinAccessibilityService extends AccessibilityService {

    public static final String ACTION_RESULT = "com.doubao.autocheckin.ACTION_RESULT";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_MSG = "msg";

    private static final int ST_LAUNCH = 1;
    private static final int ST_HOME_CLOSE = 2;
    private static final int ST_TAP_OWNER = 3;
    private static final int ST_OWNER_CLOSE = 4;
    private static final int ST_TAP_CHARGE = 5;
    private static final int ST_CHARGE_CLOSE = 6;
    private static final int ST_TAP_MINE = 7;
    private static final int ST_TAP_MEMBER = 8;
    private static final int ST_SCROLL_SIGN = 9;
    private static final int ST_TAP_SIGN = 10;
    private static final int ST_VERIFY = 11;

    private static final int R_SUCCESS = 1;
    private static final int R_SKIP = 2;
    private static final int R_FAIL = 3;

    private static final long TICK_MS = 1200L;
    private static final long TASK_TIMEOUT_MS = 150_000L;
    private static final long LAUNCH_WAIT_MS = 4_500L;
    private static final long STAGE_TIMEOUT_MS = 22_000L;
    private static final long CLOSE_PHASE_MS = 7_000L;
    private static final int MAX_CLOSE = 6;
    private static final int MAX_SCROLL = 10;
    private static final int MAX_SIGN_TAP = 3;

    private static CheckinAccessibilityService sInstance;

    private Handler handler;
    private volatile boolean busy;
    private volatile boolean ocrBusy;
    private boolean finishedOnce;

    private int stage;
    private long stageStart;
    private int closeCount;
    private int scrollCount;
    private int signTapCount;
    private int memberScrollCount;
    private boolean mineFallbackDone;

    private String pkg;
    private String sourceLabel = "";
    private String lastShotPath = "";
    private String ocrError = "";

    private String[] kwOwner;
    private String[] kwCharge;
    private String[] kwMine;
    private String[] kwMember;
    private String[] kwCheckin;
    private String[] kwSigned;
    private String[] kwSuccess;
    private String[] kwClose;
    private String[] kwConfirm;

    private PowerManager.WakeLock wakeLock;

    public static CheckinAccessibilityService getInstance() {
        return sInstance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        handler = new Handler(Looper.getMainLooper());
        kwOwner = getResources().getStringArray(R.array.kw_owner);
        kwCharge = getResources().getStringArray(R.array.kw_charge);
        kwMine = getResources().getStringArray(R.array.kw_mine);
        kwMember = getResources().getStringArray(R.array.kw_member);
        kwCheckin = getResources().getStringArray(R.array.kw_checkin);
        kwSigned = getResources().getStringArray(R.array.kw_signed);
        kwSuccess = getResources().getStringArray(R.array.kw_success);
        kwClose = getResources().getStringArray(R.array.kw_close);
        kwConfirm = getResources().getStringArray(R.array.kw_confirm);
        Notifier.init(this);
        Logger.log(this, "无障碍服务已连接（识别方式："
                + (Build.VERSION.SDK_INT >= 30 ? "截图 + OCR 文字识别" : "控件文字（Android 11 以下无截图）") + "）");
        CheckinScheduler.scheduleDaily(this);
        CheckinScheduler.scheduleReminderDaily(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 主动轮询，不依赖事件
    }

    @Override
    public void onInterrupt() {
        Logger.log(this, "无障碍服务被中断");
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        releaseWake();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 任务入口

    public void startTask(String source) {
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
        }
        if (busy) {
            Logger.log(this, "已有任务在执行，忽略本次触发：" + source);
            return;
        }
        if (!Config.isEnabled(this)) {
            Logger.log(this, "自动签到开关已关闭，跳过：" + source);
            return;
        }
        busy = true;
        ocrBusy = false;
        finishedOnce = false;
        closeCount = 0;
        scrollCount = 0;
        memberScrollCount = 0;
        signTapCount = 0;
        mineFallbackDone = false;
        lastShotPath = "";
        ocrError = "";
        sourceLabel = source == null ? "" : source;
        pkg = Config.targetPackage(this);
        Logger.log(this, "==== 开始执行（" + source + "），目标包名 " + pkg + " ====");
        acquireWake();
        launchTarget();
        stage = ST_LAUNCH;
        stageStart = System.currentTimeMillis();
        handler.postDelayed(tick, 1800);
        handler.postDelayed(timeoutTask, TASK_TIMEOUT_MS);
    }

    private final Runnable timeoutTask = new Runnable() {
        @Override
        public void run() {
            if (busy) {
                finish(R_FAIL, "整体执行超时（150 秒），请查看截图与日志");
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!busy) {
                return;
            }
            if (ocrBusy) {
                handler.postDelayed(this, 600);
                return;
            }
            ocrBusy = true;
            captureScreen((bitmap, err) -> {
                if (!busy) {
                    ocrBusy = false;
                    return;
                }
                List<ScreenOcr.Word> fallback = null;
                if (bitmap == null) {
                    ocrError = err;
                    fallback = nodeWords();
                }
                if (fallback != null) {
                    ocrBusy = false;
                    afterWords(fallback);
                    return;
                }
                ScreenOcr.recognize(bitmap, (words, error) -> {
                    ocrBusy = false;
                    if (!busy) {
                        return;
                    }
                    List<ScreenOcr.Word> use = (words == null || words.isEmpty())
                            ? nodeWords() : words;
                    if (words == null || words.isEmpty()) {
                        if (error != null) {
                            ocrError = error;
                        }
                        Logger.log(CheckinAccessibilityService.this,
                                "OCR 未取到文字，改用控件文字（" + ocrError + "）");
                    }
                    afterWords(use);
                });
            });
        }
    };

    private void afterWords(List<ScreenOcr.Word> words) {
        if (!busy) {
            return;
        }
        try {
            step(words);
        } catch (Throwable t) {
            Logger.log(this, "执行异常：" + t);
            finish(R_FAIL, "执行异常：" + t.getClass().getSimpleName());
            return;
        }
        if (busy) {
            handler.postDelayed(tick, TICK_MS);
        }
    }

    // ---------------------------------------------------------------- 状态机

    private void step(List<ScreenOcr.Word> words) {
        long now = System.currentTimeMillis();
        switch (stage) {
            case ST_LAUNCH: {
                if (now - stageStart < LAUNCH_WAIT_MS) {
                    return;
                }
                Logger.log(this, "打开 App 完成，开始清理弹窗");
                enter(ST_HOME_CLOSE, now);
                return;
            }
            case ST_HOME_CLOSE: {
                if (closeOnePopup(words)) {
                    return;
                }
                if (closeCount >= MAX_CLOSE || now - stageStart > CLOSE_PHASE_MS) {
                    Logger.log(this, "首页弹窗清理完成，准备点「车主」（共关闭 " + closeCount + " 个）");
                    enter(ST_TAP_OWNER, now);
                }
                return;
            }
            case ST_TAP_OWNER: {
                ScreenOcr.Word w = findAny(words, kwOwner);
                if (w != null) {
                    tapWord(w, "顶部「车主」");
                    enter(ST_OWNER_CLOSE, now);
                    return;
                }
                if (now - stageStart > STAGE_TIMEOUT_MS) {
                    finish(R_FAIL, "首页找不到「车主」入口，可能页面结构有变化（可改 res/values/arrays.xml 的 kw_owner）");
                }
                return;
            }
            case ST_OWNER_CLOSE: {
                if (closeOnePopup(words)) {
                    return;
                }
                if (closeCount >= MAX_CLOSE || now - stageStart > CLOSE_PHASE_MS) {
                    Logger.log(this, "车主页弹窗清理完成");
                    enter(ST_TAP_CHARGE, now);
                }
                return;
            }
            case ST_TAP_CHARGE: {
                ScreenOcr.Word w = findAny(words, kwCharge);
                if (w != null) {
                    tapWord(w, "「滴滴充电」入口");
                    enter(ST_CHARGE_CLOSE, now);
                    return;
                }
                if (now - stageStart > STAGE_TIMEOUT_MS) {
                    finish(R_FAIL, "车主页找不到「滴滴充电」入口（可改 kw_charge）");
                }
                return;
            }
            case ST_CHARGE_CLOSE: {
                if (closeOnePopup(words)) {
                    return;
                }
                if (closeCount >= MAX_CLOSE || now - stageStart > CLOSE_PHASE_MS) {
                    Logger.log(this, "充电页弹窗清理完成");
                    enter(ST_TAP_MINE, now);
                }
                return;
            }
            case ST_TAP_MINE: {
                ScreenOcr.Word w = findAny(words, kwMine, 0.55f);
                if (w == null) {
                    w = findAny(words, kwMine);
                }
                if (w != null) {
                    tapWord(w, "底部「我的」");
                    enter(ST_TAP_MEMBER, now);
                    return;
                }
                if (!mineFallbackDone && now - stageStart > 8_000L) {
                    mineFallbackDone = true;
                    tapBottomRight("兜底点击右下角「我的」");
                    enter(ST_TAP_MEMBER, now);
                    return;
                }
                if (now - stageStart > STAGE_TIMEOUT_MS) {
                    finish(R_FAIL, "找不到「我的」入口（可改 kw_mine）");
                }
                return;
            }
            case ST_TAP_MEMBER: {
                ScreenOcr.Word w = findAny(words, kwMember);
                if (w != null) {
                    tapWord(w, "「会员中心」");
                    enter(ST_SCROLL_SIGN, now);
                    return;
                }
                if (now - stageStart > 6_000L) {
                    if (memberScrollCount >= 4) {
                        finish(R_FAIL, "找不到「会员中心」（可改 kw_member）");
                        return;
                    }
                    memberScrollCount++;
                    swipeUp("没看到会员中心，先往下滑（第 " + memberScrollCount + " 次）");
                    stageStart = now;
                }
                return;
            }
            case ST_SCROLL_SIGN: {
                if (anyOf(words, kwSigned)) {
                    Logger.log(this, "检测到今日已签到的文案");
                    enter(ST_VERIFY, now);
                    return;
                }
                ScreenOcr.Word w = findAny(words, kwCheckin);
                if (w != null) {
                    Logger.log(this, "找到签到按钮：" + w.text);
                    enter(ST_TAP_SIGN, now);
                    return;
                }
                if (scrollCount >= MAX_SCROLL) {
                    finish(R_FAIL, "会员中心下滑 " + MAX_SCROLL + " 次仍未找到签到按钮（可改 kw_checkin）");
                    return;
                }
                scrollCount++;
                swipeUp("下滑找签到按钮（第 " + scrollCount + " 次）");
                stageStart = now;
                return;
            }
            case ST_TAP_SIGN: {
                ScreenOcr.Word w = findAny(words, kwCheckin);
                if (w == null) {
                    if (now - stageStart > 3_000L) {
                        enter(ST_SCROLL_SIGN, now);
                    }
                    return;
                }
                tapWord(w, "签到按钮");
                signTapCount++;
                enter(ST_VERIFY, now);
                return;
            }
            case ST_VERIFY: {
                if (anyOf(words, kwSuccess) || anyOf(words, kwSigned)) {
                    Logger.log(this, "确认签到成功");
                    succeedAndShot(R_SUCCESS, "签到成功");
                    return;
                }
                if (now - stageStart > 6_000L) {
                    if (signTapCount < MAX_SIGN_TAP) {
                        Logger.log(this, "还没看到成功提示，重新找签到按钮再点一次");
                        enter(ST_SCROLL_SIGN, now);
                    } else {
                        finish(R_FAIL, "点了 " + signTapCount + " 次签到，仍未检测到成功提示，请查看截图");
                    }
                }
                return;
            }
            default:
                finish(R_FAIL, "未知阶段 " + stage);
        }
    }

    private void enter(int newStage, long now) {
        stage = newStage;
        stageStart = now;
        closeCount = 0;
        mineFallbackDone = false;
    }

    // ---------------------------------------------------------------- 弹窗

    private boolean closeOnePopup(List<ScreenOcr.Word> words) {
        ScreenOcr.Word w = findCloseWord(words);
        if (w != null) {
            tapWord(w, "关闭弹窗");
            closeCount++;
            stageStart = System.currentTimeMillis();
            return true;
        }
        // 实在没有「关闭 / × / 知道了」这类文案，再看有没有必须同意的隐私弹窗
        ScreenOcr.Word agree = findAny(words, kwConfirm);
        if (agree != null && closeCount == 0) {
            tapWord(agree, "隐私弹窗（同意后继续）");
            closeCount++;
            stageStart = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    private ScreenOcr.Word findCloseWord(List<ScreenOcr.Word> words) {
        ScreenOcr.Word best = null;
        int bestScore = -1;
        for (ScreenOcr.Word w : words) {
            String t = w.text;
            if (t == null || t.isEmpty()) {
                continue;
            }
            if (t.contains("签到") || t.contains("会员") || t.contains("车主")) {
                continue;
            }
            for (String k : kwClose) {
                if (k == null || k.isEmpty()) {
                    continue;
                }
                int s = -1;
                if (k.length() == 1) {
                    if (t.equalsIgnoreCase(k)) {
                        s = 3;
                    }
                } else if (t.equals(k)) {
                    s = 3;
                } else if (t.contains(k)) {
                    s = 2;
                }
                if (s > bestScore) {
                    bestScore = s;
                    best = w;
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- 判定

    private boolean anyOf(List<ScreenOcr.Word> words, String[] kws) {
        return findAny(words, kws) != null;
    }

    private ScreenOcr.Word findAny(List<ScreenOcr.Word> words, String[] kws) {
        return findAny(words, kws, 0f);
    }

    /**
     * 在识别结果里找关键词：完全相等 > 以关键词开头 > 包含；
     * minYRatio > 0 时只考虑屏幕下半部分（用来定位「我的」这种底部导航）。
     */
    private ScreenOcr.Word findAny(List<ScreenOcr.Word> words, String[] kws, float minYRatio) {
        if (words == null || kws == null) {
            return null;
        }
        int screenH = getResources().getDisplayMetrics().heightPixels;
        ScreenOcr.Word best = null;
        int bestScore = -1;
        for (ScreenOcr.Word w : words) {
            if (w == null || w.text == null || w.text.isEmpty()) {
                continue;
            }
            if (minYRatio > 0 && screenH > 0 && w.centerY() < screenH * minYRatio) {
                continue;
            }
            for (String k : kws) {
                if (k == null || k.isEmpty()) {
                    continue;
                }
                int s = -1;
                if (k.length() == 1) {
                    if (w.text.equalsIgnoreCase(k)) {
                        s = 3;
                    }
                } else if (w.text.equals(k)) {
                    s = 3;
                } else if (w.text.startsWith(k)) {
                    s = 2;
                } else if (w.text.contains(k)) {
                    s = 1;
                }
                if (s > bestScore) {
                    bestScore = s;
                    best = w;
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- 收尾

    private void succeedAndShot(int code, String msg) {
        captureScreen((bitmap, err) -> {
            if (bitmap != null) {
                lastShotPath = saveShot(bitmap, code == R_SUCCESS ? "ok" : "skip");
            } else {
                Logger.log(CheckinAccessibilityService.this, "成功截图失败：" + err);
            }
            finish(code, msg);
        });
        // 截图回调万一不来，5 秒后照样收尾
        handler.postDelayed(() -> {
            if (busy) {
                finish(code, msg);
            }
        }, 5_000L);
    }

    private void finish(int code, String msg) {
        if (finishedOnce) {
            return;
        }
        finishedOnce = true;
        busy = false;
        if (handler != null) {
            handler.removeCallbacks(tick);
            handler.removeCallbacks(timeoutTask);
        }
        releaseWake();
        Logger.log(this, "结束：" + msg);
        Config.setLastResult(this, msg);

        int recStatus = code == R_SUCCESS ? Records.SUCCESS
                : (code == R_SKIP ? Records.SKIP : Records.FAIL);
        Records.add(this, recStatus, msg, sourceLabel, lastShotPath);
        broadcastResult(code, msg);

        if (code == R_FAIL) {
            int fails = Config.incFail(this);
            Notifier.notify(this, Notifier.ID_FAIL, "签到未成功", msg);
            if (fails < 3) {
                CheckinScheduler.scheduleRetry(this, fails * 5L);
                Logger.log(this, "已安排第 " + fails + " 次重试（" + (fails * 5) + " 分钟）");
            } else {
                Logger.log(this, "连续失败 3 次，今日停止自动重试，请人工检查");
                Notifier.notify(this, Notifier.ID_FAIL, "连续失败，已暂停今日自动重试", msg);
                Config.resetFail(this);
            }
        } else {
            Config.resetFail(this);
            Notifier.notify(this, code == R_SUCCESS ? Notifier.ID_SUCCESS : Notifier.ID_SKIP,
                    code == R_SUCCESS ? "签到成功" : "今天已签到", msg);
        }
    }

    private void broadcastResult(int code, String msg) {
        try {
            Intent i = new Intent(ACTION_RESULT);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_STATUS, code);
            i.putExtra(EXTRA_MSG, msg);
            sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }

    public boolean isBusy() {
        return busy;
    }

    // ---------------------------------------------------------------- 动作实现

    private void launchTarget() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                Logger.log(this, "未找到目标 App：" + pkg);
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception e) {
            Logger.log(this, "启动 App 失败：" + e);
        }
    }

    private void acquireWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) {
                return;
            }
            wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "didicheckin:task");
            wakeLock.acquire(TASK_TIMEOUT_MS);
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                Logger.log(this, "屏幕处于锁屏状态，若设置了密码锁可能无法自动操作");
            }
        } catch (Exception e) {
            Logger.log(this, "唤醒屏幕失败：" + e);
        }
    }

    private void releaseWake() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }

    private void tapWord(ScreenOcr.Word w, String what) {
        if (w == null) {
            return;
        }
        Logger.log(this, "点击" + what + "：\"" + w.text + "\" @"
                + w.centerX() + "," + w.centerY());
        tap(w.centerX(), w.centerY());
    }

    private void tapBottomRight(String what) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        Logger.log(this, what + " @" + (int) (w * 0.85f) + "," + (int) (h * 0.94f));
        tap((int) (w * 0.85f), (int) (h * 0.94f));
    }

    private boolean tap(int x, int y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, 120);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture, null, null);
        } catch (Throwable t) {
            Logger.log(this, "坐标点击失败：" + t);
            return false;
        }
    }

    private void swipeUp(String what) {
        try {
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            Path p = new Path();
            p.moveTo(w * 0.5f, h * 0.78f);
            p.lineTo(w * 0.5f, h * 0.32f);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, 420);
            dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
            Logger.log(this, what);
        } catch (Throwable t) {
            Logger.log(this, "滑动失败：" + t);
        }
    }

    // ---------------------------------------------------------------- 截图 / OCR

    private interface ShotCallback {
        void onShot(Bitmap bitmap, String error);
    }

    private void captureScreen(final ShotCallback cb) {
        if (Build.VERSION.SDK_INT < 30) {
            cb.onShot(null, "系统低于 Android 11，无截图权限，改用控件文字");
            return;
        }
        try {
            takeShot(cb);
        } catch (Throwable t) {
            cb.onShot(null, "截图异常：" + t);
        }
    }

    @TargetApi(30)
    private void takeShot(final ShotCallback cb) {
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        try {
                            Bitmap bmp = Bitmap.wrapHardwareBuffer(
                                    result.getHardwareBuffer(), result.getColorSpace());
                            if (bmp == null) {
                                cb.onShot(null, "截图解码为空");
                                return;
                            }
                            cb.onShot(bmp.copy(Bitmap.Config.ARGB_8888, false), null);
                        } catch (Throwable t) {
                            cb.onShot(null, "截图转换失败：" + t);
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        cb.onShot(null, "截图失败，错误码 " + errorCode);
                    }
                });
    }

    private String saveShot(Bitmap bitmap, String tag) {
        if (bitmap == null) {
            return "";
        }
        try {
            File dir = new File(getFilesDir(), "shots");
            if (!dir.exists() && !dir.mkdirs()) {
                return "";
            }
            String name = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(new Date())
                    + "-" + tag + ".png";
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            try {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            } finally {
                fos.close();
            }
            Config.setLastShot(this, f.getAbsolutePath());
            Logger.log(this, "截图已保存：" + name);
            return f.getAbsolutePath();
        } catch (Exception e) {
            Logger.log(this, "写入截图失败：" + e);
            return "";
        }
    }

    // ---------------------------------------------------------------- 控件文字兜底

    private List<ScreenOcr.Word> nodeWords() {
        List<ScreenOcr.Word> out = new ArrayList<>();
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                collect(root, out, 0);
            }
        } catch (Throwable t) {
            Logger.log(this, "读取控件文字失败：" + t);
        }
        return out;
    }

    private void collect(AccessibilityNodeInfo n, List<ScreenOcr.Word> out, int depth) {
        if (n == null || depth > 40 || out.size() >= 120) {
            return;
        }
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) {
            t = n.getContentDescription();
        }
        if (t != null && t.length() > 0) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() > 4 && r.height() > 4) {
                out.add(new ScreenOcr.Word(t.toString(), r));
            }
        }
        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            collect(n.getChild(i), out, depth + 1);
        }
    }
}
