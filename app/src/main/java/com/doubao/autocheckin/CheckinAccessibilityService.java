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
 * 核心执行器：无障碍服务内的轮询式状态机。
 *
 * 阶段：ST_LAUNCH（打开 App） → ST_NAV（找到充电入口） → ST_ENTRY（点签到） → ST_VERIFY（校验结果）
 */
public class CheckinAccessibilityService extends AccessibilityService {

    private static final int ST_LAUNCH = 1;
    private static final int ST_NAV = 2;
    private static final int ST_ENTRY = 3;
    private static final int ST_VERIFY = 4;

    private static final int R_SUCCESS = 1;
    private static final int R_SKIP = 2;
    private static final int R_FAIL = 3;

    private static final long TICK_MS = 900L;
    private static final long TASK_TIMEOUT_MS = 90_000L;
    private static final long LAUNCH_TIMEOUT_MS = 15_000L;
    private static final long NAV_TIMEOUT_MS = 25_000L;
    private static final long ENTRY_TIMEOUT_MS = 20_000L;
    private static final long VERIFY_TIMEOUT_MS = 12_000L;
    private static final int MAX_CLICK = 3;

    private static CheckinAccessibilityService sInstance;

    private Handler handler;
    private volatile boolean busy;
    private int stage;
    private long stageStart;
    private int clickCount;
    private String pkg;

    private String[] navKws;
    private String[] entryKws;
    private String[] signedKws;
    private String[] successKws;
    private String[] popupKws;

    private PowerManager.WakeLock wakeLock;

    public static CheckinAccessibilityService getInstance() {
        return sInstance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        handler = new Handler(Looper.getMainLooper());
        navKws = getResources().getStringArray(R.array.nav_keywords);
        entryKws = getResources().getStringArray(R.array.entry_keywords);
        signedKws = getResources().getStringArray(R.array.signed_keywords);
        successKws = getResources().getStringArray(R.array.success_keywords);
        popupKws = getResources().getStringArray(R.array.popup_keywords);
        Notifier.init(this);
        Logger.log(this, "无障碍服务已连接");
        CheckinScheduler.scheduleDaily(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 采用主动轮询，不依赖事件内容，这里仅做保活
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
        clickCount = 0;
        pkg = Config.targetPackage(this);
        Logger.log(this, "==== 开始执行（" + source + "），目标包名 " + pkg + " ====");
        acquireWake();
        launchTarget();
        stage = ST_LAUNCH;
        stageStart = System.currentTimeMillis();
        handler.postDelayed(tick, 1500);
        handler.postDelayed(timeoutTask, TASK_TIMEOUT_MS);
    }

    private final Runnable timeoutTask = new Runnable() {
        @Override
        public void run() {
            if (busy) {
                finish(R_FAIL, "执行超时（90 秒），请查看截图与日志");
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!busy) {
                return;
            }
            try {
                step();
            } catch (Throwable t) {
                Logger.log(CheckinAccessibilityService.this, "执行异常：" + t);
                finish(R_FAIL, "执行异常：" + t.getClass().getSimpleName());
                return;
            }
            if (busy) {
                handler.postDelayed(this, TICK_MS);
            }
        }
    };

    private void step() {
        long now = System.currentTimeMillis();
        switch (stage) {
            case ST_LAUNCH: {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null && pkg.equals(String.valueOf(root.getPackageName()))) {
                    Logger.log(this, "已进入目标 App");
                    stage = ST_NAV;
                    stageStart = now;
                    return;
                }
                if (now - stageStart > LAUNCH_TIMEOUT_MS) {
                    finish(R_FAIL, "打不开目标 App，请检查包名是否正确、应用是否已安装");
                }
                return;
            }
            case ST_NAV: {
                dismissPopups();
                if (containsAny(signedKws)) {
                    finish(R_SKIP, "今天已签到（检测到「已签到」文案）");
                    return;
                }
                if (containsAny(entryKws)) {
                    stage = ST_ENTRY;
                    stageStart = now;
                    return;
                }
                if (clickText(navKws)) {
                    Logger.log(this, "已点击充电入口，等待页面加载");
                    stageStart = now;
                    return;
                }
                if (now - stageStart > NAV_TIMEOUT_MS) {
                    finish(R_FAIL, "找不到「小桔充电 / 签到」入口，可能是页面改版");
                }
                return;
            }
            case ST_ENTRY: {
                dismissPopups();
                if (containsAny(signedKws)) {
                    finish(R_SKIP, "今天已签到（检测到「已签到」文案）");
                    return;
                }
                if (clickText(entryKws)) {
                    clickCount++;
                    Logger.log(this, "已点击签到按钮（第 " + clickCount + " 次）");
                    stage = ST_VERIFY;
                    stageStart = now;
                    return;
                }
                if (containsAny(successKws)) {
                    stage = ST_VERIFY;
                    stageStart = now;
                    return;
                }
                if (now - stageStart > ENTRY_TIMEOUT_MS) {
                    finish(R_FAIL, "找不到可点击的签到按钮，可能是页面改版或需要人工登录");
                }
                return;
            }
            case ST_VERIFY: {
                if (containsAny(successKws) || containsAny(signedKws)) {
                    finish(R_SUCCESS, "签到成功");
                    return;
                }
                if (now - stageStart > VERIFY_TIMEOUT_MS) {
                    if (clickCount < MAX_CLICK) {
                        Logger.log(this, "结果未确认，重试点击");
                        stage = ST_ENTRY;
                        stageStart = now;
                    } else {
                        finish(R_FAIL, "点击后未检测到签到成功提示，请查看截图");
                    }
                }
                return;
            }
            default:
                finish(R_FAIL, "未知阶段");
        }
    }

    // ---------------------------------------------------------------- 收尾

    private void finish(int code, String msg) {
        busy = false;
        if (handler != null) {
            handler.removeCallbacks(tick);
            handler.removeCallbacks(timeoutTask);
        }
        captureShot();
        releaseWake();
        Logger.log(this, "结束：" + msg);
        Config.setLastResult(this, msg);

        if (code == R_FAIL) {
            int fails = Config.incFail(this);
            Notifier.notify(this, Notifier.ID_FAIL, "签到未成功", msg);
            if (fails < 3) {
                CheckinScheduler.scheduleRetry(this, fails * 5L);
                Logger.log(this, "已安排第 " + fails + " 次重试（" + (fails * 5) + " 分钟后）");
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

    private void dismissPopups() {
        List<AccessibilityNodeInfo> nodes = findNodes(popupKws);
        for (AccessibilityNodeInfo n : nodes) {
            if (clickNode(n)) {
                Logger.log(this, "已关闭一个弹窗");
                return;
            }
        }
    }

    private boolean containsAny(String[] kws) {
        return !findNodes(kws).isEmpty();
    }

    private boolean clickText(String[] kws) {
        List<AccessibilityNodeInfo> nodes = findNodes(kws);
        for (AccessibilityNodeInfo n : nodes) {
            if (clickNode(n)) {
                return true;
            }
        }
        return false;
    }

    private boolean clickNode(AccessibilityNodeInfo n) {
        if (n == null) {
            return false;
        }
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.isClickable() && cur.isEnabled()) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            }
            cur = cur.getParent();
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.width() > 8 && r.height() > 8) {
            return tap(r.centerX(), r.centerY());
        }
        return false;
    }

    private boolean tap(int x, int y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, 80);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture, null, null);
        } catch (Throwable t) {
            Logger.log(this, "坐标点击失败：" + t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 节点查找

    private List<AccessibilityNodeInfo> findNodes(String[] kws) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && kws != null) {
            collect(root, kws, out, 0);
        }
        return out;
    }

    private void collect(AccessibilityNodeInfo n, String[] kws, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 40 || out.size() >= 40) {
            return;
        }
        String t = nodeText(n);
        if (t != null) {
            for (String k : kws) {
                if (k != null && !k.isEmpty() && t.contains(k)) {
                    out.add(n);
                    break;
                }
            }
        }
        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            collect(n.getChild(i), kws, out, depth + 1);
        }
    }

    private String nodeText(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) {
            return t.toString();
        }
        CharSequence desc = n.getContentDescription();
        if (desc != null && desc.length() > 0) {
            return desc.toString();
        }
        return null;
    }

    // ---------------------------------------------------------------- 截图

    private void captureShot() {
        if (Build.VERSION.SDK_INT < 30) {
            return;
        }
        try {
            takeShot();
        } catch (Throwable t) {
            Logger.log(this, "截图失败：" + t);
        }
    }

    @TargetApi(30)
    private void takeShot() {
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        try {
                            Bitmap bmp = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                            if (bmp == null) {
                                return;
                            }
                            Bitmap soft = bmp.copy(Bitmap.Config.ARGB_8888, false);
                            saveShot(soft);
                        } catch (Throwable t) {
                            Logger.log(CheckinAccessibilityService.this, "截图保存失败：" + t);
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        Logger.log(CheckinAccessibilityService.this, "截图失败，错误码 " + errorCode);
                    }
                });
    }

    private void saveShot(Bitmap bitmap) {
        try {
            File dir = new File(getFilesDir(), "shots");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            String name = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(new Date()) + ".png";
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            try {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            } finally {
                fos.close();
            }
            Config.setLastShot(this, f.getAbsolutePath());
            Logger.log(this, "截图已保存：" + name);
        } catch (Exception e) {
            Logger.log(this, "写入截图失败：" + e);
        }
    }
}
