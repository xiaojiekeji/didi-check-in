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
 * 核心执行器：截图 + OCR 文字识别 + 坐标点击（v1.3 极速版）。
 *
 * 最短路径：打开滴滴 → 进「滴滴充电」→ 底部「我的」→「会员中心」→ 下滑 → 点「签到」→ 校验并截图。
 *
 * 速度设计：
 * 1) 每一步只识别需要的那一块屏幕（ROI），比整屏识别快 2~3 倍；
 * 2) 点完只等 320ms 就复检，没找到目标 240ms 后再看一次，不再固定等 1.2 秒；
 * 3) 无障碍控件文字几乎零耗时，和 OCR 结果合并后一起匹配，命中就点，减少反复识别；
 * 4) OCR 回调统一回主线程再点，手势不会被排队拖慢。
 *
 * 防误点设计：
 * 1) 只有屏幕上出现弹窗特征文案时才去找关闭按钮，正常页面绝不乱点「× / 关闭」；
 * 2) 关闭按钮限制在屏幕中部区域，贴边的一律不点；
 * 3) 兜底点击沿用底部标签行的真实 y 坐标，绝不点屏幕最底部（那里是系统导航栏，会误触返回/主页）；
 * 4) 同一坐标 1.5 秒内不重复点，避免连点导致乱跳页面。
 *
 * Android 11 以下没有截图能力，自动降级为读取控件文字（无障碍节点）。
 */
public class CheckinAccessibilityService extends AccessibilityService {

    public static final String ACTION_RESULT = "com.doubao.autocheckin.ACTION_RESULT";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_MSG = "msg";

    private static final int ST_OPEN = 1;
    private static final int ST_OWNER = 2;
    private static final int ST_CHARGE = 3;
    private static final int ST_MINE = 4;
    private static final int ST_MEMBER = 5;
    private static final int ST_SIGN = 6;
    private static final int ST_VERIFY = 7;

    private static final int R_SUCCESS = 1;
    private static final int R_SKIP = 2;
    private static final int R_FAIL = 3;

    /** 点完等页面切过去的时间（随机 1~2 秒，避免机械节奏被风控）。 */
    private static final long SETTLE_MIN_MS = 1000L;
    private static final long SETTLE_MAX_MS = 2000L;
    /** 没找到目标时，隔多久再看一眼。
     *  注意：系统限制两次 takeScreenshot 间隔太短会直接失败（错误码 3），所以不能低于 480ms。 */
    private static final long RETRY_MS = 480L;
    /** 拉起 App 后第一帧的等待。 */
    private static final long LAUNCH_GAP_MS = 900L;
    /** 整轮上限（加了 1~2 秒人工节奏后，正常 15~25 秒，这里留足保险）。 */
    private static final long TASK_TIMEOUT_MS = 100_000L;
    /** 单个阶段找入口的上限。 */
    private static final long STAGE_TIMEOUT_MS = 12_000L;
    private static final int MAX_CLOSE = 3;
    private static final int MAX_SCROLL_CHARGE = 2;
    private static final int MAX_SCROLL_MEMBER = 2;
    private static final int MAX_SCROLL_SIGN = 6;
    private static final int MAX_SIGN_TAP = 2;

    private static CheckinAccessibilityService sInstance;

    private Handler handler;
    private volatile boolean busy;
    private volatile boolean ocrBusy;
    private boolean finishedOnce;

    private int stage;
    private long stageStart;
    private long taskStart;
    private long settleUntil;
    private long nextDelay = RETRY_MS;

    private int closeCount;
    private int scrollChargeCount;
    private int scrollMemberCount;
    private int scrollSignCount;
    private int signTapCount;

    private int lastTapX = -1;
    private int lastTapY = -1;
    private long lastTapAt;

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
    private String[] kwPopup;

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
        kwPopup = getResources().getStringArray(R.array.kw_popup);
        Notifier.init(this);
        Logger.log(this, "无障碍服务已连接（识别方式："
                + (Build.VERSION.SDK_INT >= 30 ? "截图 + OCR 文字识别（极速版）" : "控件文字（Android 11 以下无截图）")
                + "）");
        // 后台预热 OCR 模型，第一次识别不再等模型加载
        new Thread(() -> ScreenOcr.warmup(), "ocr-warmup").start();
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
        scrollChargeCount = 0;
        scrollMemberCount = 0;
        scrollSignCount = 0;
        signTapCount = 0;
        lastTapX = -1;
        lastTapY = -1;
        lastTapAt = 0;
        lastShotPath = "";
        ocrError = "";
        sourceLabel = source == null ? "" : source;
        pkg = Config.targetPackage(this);
        taskStart = System.currentTimeMillis();
        stage = ST_OPEN;
        stageStart = taskStart;
        nextDelay = RETRY_MS;
        Logger.log(this, "==== 开始执行（" + source + "），目标包名 " + pkg + " ====");
        acquireWake();
        launchTarget();
        settleUntil = System.currentTimeMillis() + LAUNCH_GAP_MS;
        handler.postDelayed(tick, LAUNCH_GAP_MS);
        handler.postDelayed(timeoutTask, TASK_TIMEOUT_MS);
    }

    private final Runnable timeoutTask = new Runnable() {
        @Override
        public void run() {
            if (busy) {
                finish(R_FAIL, "整体执行超时（70 秒），请查看截图与日志");
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!busy) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now < settleUntil) {
                handler.postDelayed(this, settleUntil - now);
                return;
            }
            if (ocrBusy) {
                handler.postDelayed(this, 100);
                return;
            }
            ocrBusy = true;
            observe();
        }
    };

    /** 截一帧，按当前阶段只识别需要的那块区域，再叠加控件文字一起交给状态机。 */
    private void observe() {
        final int stageSnap = stage;
        captureScreen((bitmap, err) -> {
            if (!busy) {
                ocrBusy = false;
                return;
            }
            if (bitmap == null) {
                ocrError = err;
                final List<ScreenOcr.Word> nodes = nodeWords();
                ocrBusy = false;
                onWords(nodes);
                return;
            }
            ScreenOcr.recognize(bitmap, roiFor(stageSnap), (words, error) -> {
                ocrBusy = false;
                if (error != null) {
                    ocrError = error;
                }
                List<ScreenOcr.Word> merged = new ArrayList<>();
                if (words != null) {
                    merged.addAll(words);
                }
                // 控件文字零成本，合并进来提高命中率
                merged.addAll(nodeWords());
                onWords(merged);
            });
        });
    }

    private void onWords(final List<ScreenOcr.Word> words) {
        handler.post(() -> {
            if (!busy) {
                return;
            }
            // 自己 App 还在前台（刚点「开始打卡」那一两秒）就什么都别点，
            // 否则 OCR 会把自己 App 的标题「滴滴充电自动签到」当成充电入口点掉
            if (isOwnAppForeground()) {
                Logger.log(this, "等待滴滴切到前台…（当前仍是我方界面）");
                nextDelay = 500L;
                handler.postDelayed(tick, nextDelay);
                return;
            }
            try {
                logWords(words);
                step(words);
            } catch (Throwable t) {
                Logger.log(this, "执行异常：" + t);
                finish(R_FAIL, "执行异常：" + t.getClass().getSimpleName());
                return;
            }
            if (!busy) {
                return;
            }
            long now = System.currentTimeMillis();
            long delay = now < settleUntil ? (settleUntil - now) : nextDelay;
            handler.postDelayed(tick, delay);
        });
    }

    private boolean isOwnAppForeground() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return false;
            }
            CharSequence p = root.getPackageName();
            return p != null && getPackageName().equals(p.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把每帧识别到的文字打进日志，方便真机上调关键词和坐标。 */
    private void logWords(List<ScreenOcr.Word> words) {
        if (words == null || words.isEmpty()) {
            Logger.log(this, "阶段 " + stageName() + " 未识别到任何文字");
            return;
        }
        StringBuilder sb = new StringBuilder("阶段 ").append(stageName())
                .append(" 识别 ").append(words.size()).append(" 条：");
        int n = 0;
        for (ScreenOcr.Word w : words) {
            if (n++ >= 14 || w == null || w.text.isEmpty()) {
                continue;
            }
            sb.append("\"").append(w.text).append("\"(").append(w.centerX())
                    .append(",").append(w.centerY()).append(") ");
        }
        Logger.log(this, sb.toString());
    }

    private String stageName() {
        switch (stage) {
            case ST_OPEN:
                return "启动";
            case ST_OWNER:
                return "找车主分类";
            case ST_CHARGE:
                return "找滴滴充电";
            case ST_MINE:
                return "找我的";
            case ST_MEMBER:
                return "找会员中心";
            case ST_SIGN:
                return "找签到";
            case ST_VERIFY:
                return "校验结果";
            default:
                return String.valueOf(stage);
        }
    }

    // ---------------------------------------------------------------- 状态机

    private void step(List<ScreenOcr.Word> words) {
        long now = System.currentTimeMillis();
        nextDelay = RETRY_MS;

        // 弹窗优先：只有看起来真是弹窗才点关闭，正常页面绝不乱点
        if (stage != ST_VERIFY && closeCount < MAX_CLOSE && tryClosePopup(words)) {
            return;
        }

        switch (stage) {
            case ST_OPEN:
                stage = ST_OWNER;
                stageStart = now;
                doOwner(words, now);
                return;
            case ST_OWNER:
                doOwner(words, now);
                return;
            case ST_CHARGE:
                doCharge(words, now);
                return;
            case ST_MINE:
                doMine(words, now);
                return;
            case ST_MEMBER:
                doMember(words, now);
                return;
            case ST_SIGN:
                doSign(words, now);
                return;
            case ST_VERIFY:
                doVerify(words, now);
                return;
            default:
                finish(R_FAIL, "未知阶段 " + stage);
        }
    }

    /** 首页顶部「出行 / 送货 / 旅行 / 车主」分类栏，点「车主」。 */
    private void doOwner(List<ScreenOcr.Word> words, long now) {
        ScreenOcr.Word tab = findBest(words, kwOwner, 0.02f, 0.22f);
        if (tab != null) {
            tapWord(tab, "顶部「车主」分类");
            enter(ST_CHARGE, now);
            return;
        }
        // 有些版本首页就能直接看到充电入口，那就省一步
        ScreenOcr.Word charge = findBest(words, kwCharge, 0.05f, 0.95f);
        if (charge != null) {
            tapWord(charge, "「滴滴充电」入口（首页直接可见）");
            enter(ST_MINE, now);
            return;
        }
        if (now - stageStart > 2200) {
            if (scrollChargeCount < MAX_SCROLL_CHARGE) {
                scrollChargeCount++;
                swipeUp("首页没看到入口，下滑看看（第 " + scrollChargeCount + " 次）");
                stageStart = now;
                return;
            }
            finish(R_FAIL, "首页顶部没找到「车主」分类（可改 kw_owner）");
        }
    }

    /** 车主页里找「滴滴充电」入口。 */
    private void doCharge(List<ScreenOcr.Word> words, long now) {
        ScreenOcr.Word w = findBest(words, kwCharge, 0.05f, 0.95f);
        if (w != null) {
            tapWord(w, "「滴滴充电」入口");
            enter(ST_MINE, now);
            return;
        }
        if (now - stageStart > 700) {
            if (scrollChargeCount < MAX_SCROLL_CHARGE) {
                scrollChargeCount++;
                swipeUp("车主页下滑找「滴滴充电」（第 " + scrollChargeCount + " 次）");
                stageStart = now;
                return;
            }
            finish(R_FAIL, "车主页里没找到「滴滴充电」入口（可改 kw_charge）");
        }
    }

    /** 底部导航「我的」：只在屏幕下半部分找。 */
    private void doMine(List<ScreenOcr.Word> words, long now) {
        ScreenOcr.Word w = findBest(words, kwMine, 0.62f, 1.0f);
        if (w != null) {
            tapWord(w, "底部「我的」");
            enter(ST_MEMBER, now);
            return;
        }
        if (now - stageStart > 2200) {
            if (tapBottomTab(words, "兜底点底部「我的」（沿用底部标签行的 y）")) {
                enter(ST_MEMBER, now);
                return;
            }
            finish(R_FAIL, "找不到底部「我的」入口（可改 kw_mine）");
        }
    }

    /** 「会员中心」入口。 */
    private void doMember(List<ScreenOcr.Word> words, long now) {
        ScreenOcr.Word w = findBest(words, kwMember, 0.03f, 0.90f);
        if (w != null) {
            tapWord(w, "「会员中心」");
            enter(ST_SIGN, now);
            return;
        }
        if (now - stageStart > 1600) {
            if (scrollMemberCount < MAX_SCROLL_MEMBER) {
                scrollMemberCount++;
                swipeUp("我的页下滑找「会员中心」（第 " + scrollMemberCount + " 次）");
                stageStart = now;
                return;
            }
            finish(R_FAIL, "找不到「会员中心」（可改 kw_member）");
        }
    }

    /** 下滑找「签到」按钮。 */
    private void doSign(List<ScreenOcr.Word> words, long now) {
        // 先找按钮：签到日历里历史日期也可能写着「已签到」，有按钮就一律以按钮为准
        ScreenOcr.Word w = findCheckin(words);
        if (w != null) {
            tapWord(w, "签到按钮");
            signTapCount++;
            enter(ST_VERIFY, now);
            return;
        }
        if (anyOf(words, kwSigned)) {
            Logger.log(this, "没有签到按钮、页面显示已签到");
            succeedAndShot(R_SKIP, "今天已经签过了");
            return;
        }
        if (now - stageStart > 800) {
            if (scrollSignCount < MAX_SCROLL_SIGN) {
                scrollSignCount++;
                swipeUp("下滑找签到按钮（第 " + scrollSignCount + " 次）");
                stageStart = now;
                return;
            }
            finish(R_FAIL, "下滑 " + scrollSignCount + " 次仍未找到签到按钮（可改 kw_checkin）");
        }
    }

    /** 校验签到结果。 */
    private void doVerify(List<ScreenOcr.Word> words, long now) {
        if (anyOf(words, kwSuccess) || anyOf(words, kwSigned)) {
            Logger.log(this, "确认签到成功");
            succeedAndShot(R_SUCCESS, "签到成功");
            return;
        }
        if (now - stageStart > 2200) {
            if (signTapCount < MAX_SIGN_TAP) {
                Logger.log(this, "还没看到成功提示，回去再点一次签到");
                enter(ST_SIGN, now);
            } else {
                finish(R_FAIL, "点了 " + signTapCount + " 次签到仍未检测到成功提示，请查看截图");
            }
        }
    }

    private void enter(int newStage, long now) {
        stage = newStage;
        stageStart = now;
        closeCount = 0;
        nextDelay = RETRY_MS;
        if (newStage == ST_CHARGE || newStage == ST_OWNER) {
            scrollChargeCount = 0;
        }
    }

    // ---------------------------------------------------------------- 区域与弹窗

    /** 每个阶段只看需要的那块屏幕：越小识别越快，也越不容易误认别处文字。 */
    private Rect roiFor(int s) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        switch (s) {
            case ST_OWNER:
                // 只认顶部「出行 / 送货 / 旅行 / 车主」这一行
                return new Rect(0, (int) (h * 0.02f), w, (int) (h * 0.24f));
            case ST_MINE:
                return new Rect(0, (int) (h * 0.55f), w, h);
            case ST_MEMBER:
                return new Rect(0, (int) (h * 0.03f), w, (int) (h * 0.75f));
            case ST_SIGN:
                return new Rect(0, (int) (h * 0.10f), w, (int) (h * 0.90f));
            case ST_VERIFY:
                return new Rect(0, (int) (h * 0.12f), w, (int) (h * 0.82f));
            default:
                return new Rect(0, (int) (h * 0.03f), w, (int) (h * 0.94f));
        }
    }

    /** 屏幕上出现这些字，才认为有弹窗需要处理。 */
    private boolean looksLikePopup(List<ScreenOcr.Word> words) {
        for (ScreenOcr.Word w : words) {
            if (w == null || w.text.isEmpty()) {
                continue;
            }
            for (String k : kwPopup) {
                if (k != null && !k.isEmpty() && w.text.contains(k)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean tryClosePopup(List<ScreenOcr.Word> words) {
        if (!looksLikePopup(words)) {
            return false;
        }
        long now = System.currentTimeMillis();
        ScreenOcr.Word close = findCloseButton(words);
        if (close != null) {
            tapWord(close, "关闭弹窗");
            closeCount++;
            stageStart = now;
            return true;
        }
        ScreenOcr.Word agree = findBest(words, kwConfirm, 0.25f, 0.95f);
        if (agree != null) {
            tapWord(agree, "弹窗「同意」");
            closeCount++;
            stageStart = now;
            return true;
        }
        return false;
    }

    /**
     * 找弹窗上的关闭按钮：只认屏幕中部、尺寸正常的文字，
     * 贴边的「×」多半是页面装饰或广告角标，点了会跳走，所以直接排除。
     */
    private ScreenOcr.Word findCloseButton(List<ScreenOcr.Word> words) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        ScreenOcr.Word best = null;
        int bestScore = -1;
        for (ScreenOcr.Word word : words) {
            if (word == null || word.text.isEmpty()) {
                continue;
            }
            String t = word.text;
            if (t.contains("签到") || t.contains("会员") || t.contains("车主") || t.contains("充电")) {
                continue;
            }
            int cy = word.centerY();
            int cx = word.centerX();
            if (cy < h * 0.18f || cy > h * 0.86f) {
                continue;
            }
            if (cx < w * 0.04f || cx > w * 0.96f) {
                continue;
            }
            if (word.height() > h * 0.12f) {
                continue;
            }
            int score = -1;
            for (String k : kwClose) {
                if (k == null || k.isEmpty()) {
                    continue;
                }
                if (k.length() == 1) {
                    if (t.equalsIgnoreCase(k)) {
                        score = Math.max(score, 3);
                    }
                } else if (t.equals(k)) {
                    // 只认完全相等：「取消」「关闭」这类短词必须整词命中，
                    // 否则「取消订单」「关闭自动续费」会被当成弹窗关闭按钮点掉
                    score = Math.max(score, 3);
                } else if (k.length() >= 3 && t.startsWith(k)) {
                    score = Math.max(score, 2);
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = word;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- 匹配

    private boolean anyOf(List<ScreenOcr.Word> words, String[] kws) {
        return findBest(words, kws, 0f, 1f) != null;
    }

    /** 找签到按钮，排除「签到规则 / 签到记录 / 签到日历」这类说明入口。 */
    private ScreenOcr.Word findCheckin(List<ScreenOcr.Word> words) {
        if (words == null || words.isEmpty()) {
            return null;
        }
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        ScreenOcr.Word best = null;
        int bestScore = -1;
        for (ScreenOcr.Word word : words) {
            if (word == null || word.text.isEmpty()) {
                continue;
            }
            String t = word.text;
            if (t.contains("规则") || t.contains("记录") || t.contains("日历")
                    || t.contains("说明") || t.contains("详情") || t.contains("查看") || t.contains("帮助")) {
                continue;
            }
            int cy = word.centerY();
            int cx = word.centerX();
            if (h > 0 && (cy < h * 0.05f || cy > h * 0.95f)) {
                continue;
            }
            if (w > 0 && (cx < w * 0.03f || cx > w * 0.97f)) {
                continue;
            }
            int score = -1;
            for (String k : kwCheckin) {
                if (k == null || k.isEmpty()) {
                    continue;
                }
                if (t.equals(k)) {
                    score = Math.max(score, 3);
                } else if (t.startsWith(k)) {
                    score = Math.max(score, 2);
                } else if (t.contains(k)) {
                    score = Math.max(score, 1);
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = word;
            }
        }
        return best;
    }

    /**
     * 在识别结果里按「完全相等 > 以关键词开头 > 包含」打分，
     * 并且排除贴边文字（贴边多半是状态栏、导航栏、广告角标）。
     */
    private ScreenOcr.Word findBest(List<ScreenOcr.Word> words, String[] kws, float minYRatio, float maxYRatio) {
        if (words == null || kws == null) {
            return null;
        }
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        ScreenOcr.Word best = null;
        int bestScore = -1;
        for (ScreenOcr.Word word : words) {
            if (word == null || word.text.isEmpty()) {
                continue;
            }
            int cy = word.centerY();
            int cx = word.centerX();
            if (h > 0 && (cy < h * minYRatio || cy > h * maxYRatio)) {
                continue;
            }
            if (w > 0 && (cx < w * 0.03f || cx > w * 0.97f)) {
                continue;
            }
            for (String k : kws) {
                if (k == null || k.isEmpty()) {
                    continue;
                }
                int s = -1;
                if (k.length() == 1) {
                    if (word.text.equalsIgnoreCase(k)) {
                        s = 3;
                    }
                } else if (word.text.equals(k)) {
                    s = 3;
                } else if (word.text.startsWith(k)) {
                    s = 2;
                } else if (word.text.contains(k)) {
                    s = 1;
                }
                if (s > bestScore) {
                    bestScore = s;
                    best = word;
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
        handler.postDelayed(() -> {
            if (busy) {
                finish(code, msg);
            }
        }, 3_000L);
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
        long cost = System.currentTimeMillis() - taskStart;
        Logger.log(this, "结束（耗时 " + (cost / 1000.0) + " 秒）：" + msg
                + (ocrError.isEmpty() ? "" : "，OCR 备注：" + ocrError));
        Config.setLastResult(this, msg);

        int recStatus = code == R_SUCCESS ? Records.SUCCESS
                : (code == R_SKIP ? Records.SKIP : Records.FAIL);
        Records.add(this, recStatus, msg, sourceLabel, lastShotPath);
        broadcastResult(code, msg);

        if (code == R_FAIL) {
            int fails = Config.incFail(this);
            Notifier.notify(this, Notifier.ID_FAIL, "签到未成功", msg);
            if (fails < 3) {
                CheckinScheduler.scheduleRetry(this, fails * 3L);
                Logger.log(this, "已安排第 " + fails + " 次重试（" + (fails * 3) + " 分钟）");
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

    private void tapWord(ScreenOcr.Word word, String what) {
        if (word == null) {
            return;
        }
        int x = word.centerX();
        int y = word.centerY();
        long now = System.currentTimeMillis();
        // 同一处 1.5 秒内不重复点：避免页面切换途中连点，跳到别的页面
        if (x == lastTapX && y == lastTapY && now - lastTapAt < 1500L) {
            nextDelay = 400L;
            return;
        }
        lastTapX = x;
        lastTapY = y;
        lastTapAt = now;
        Logger.log(this, "点击" + what + "：\"" + word.text + "\" @" + x + "," + y);
        tap(x, y);
        long gap = humanDelay();
        nextDelay = gap;
        settleUntil = now + gap;
        Logger.log(this, "等页面切换 " + gap + "ms");
    }

    /** 每次动作后的停顿：1~2 秒随机，别让节奏像脚本。 */
    private long humanDelay() {
        return SETTLE_MIN_MS + (long) (Math.random() * (SETTLE_MAX_MS - SETTLE_MIN_MS + 1));
    }

    /**
     * 兜底点底部标签：用识别到的底部标签行的真实 y 坐标，
     * 不点屏幕最底部（那里是系统导航栏，会误触返回/主页把 App 顶掉）。
     */
    private boolean tapBottomTab(List<ScreenOcr.Word> words, String what) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int y = -1;
        for (ScreenOcr.Word word : words) {
            if (word == null || word.text.isEmpty()) {
                continue;
            }
            int cy = word.centerY();
            // 底部标签行一般落在 66%~88% 高度，再往下的最底部是系统导航栏，不能点
            if (cy > h * 0.66f && cy < h * 0.90f) {
                y = Math.max(y, cy);
            }
        }
        if (y < 0) {
            return false;
        }
        int x = (int) (w * 0.87f);
        long now = System.currentTimeMillis();
        Logger.log(this, what + " @" + x + "," + y);
        tap(x, y);
        lastTapX = x;
        lastTapY = y;
        lastTapAt = now;
        long gap = humanDelay();
        nextDelay = gap;
        settleUntil = now + gap;
        return true;
    }

    private boolean tap(int x, int y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, 100);
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
            p.moveTo(w * 0.5f, h * 0.72f);
            p.lineTo(w * 0.5f, h * 0.36f);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, 360);
            dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
            long gap = humanDelay();
            Logger.log(this, what + "，等 " + gap + "ms");
            settleUntil = System.currentTimeMillis() + gap;
            nextDelay = gap;
        } catch (Throwable t) {
            Logger.log(this, "滑动失败：" + t);
        }
    }

    // ---------------------------------------------------------------- 截图 / OCR

    private interface ShotCallback {
        void onShot(Bitmap bitmap, String error);
    }

    /** 系统对 takeScreenshot 有最小间隔限制，太密会直接返回错误码 3，这里主动限流。 */
    private static final long MIN_SHOT_INTERVAL_MS = 480L;
    private long lastShotAt;

    private void captureScreen(final ShotCallback cb) {
        if (Build.VERSION.SDK_INT < 30) {
            cb.onShot(null, "系统低于 Android 11，无截图权限，改用控件文字");
            return;
        }
        long now = System.currentTimeMillis();
        long wait = MIN_SHOT_INTERVAL_MS - (now - lastShotAt);
        if (wait > 0) {
            handler.postDelayed(() -> {
                if (!busy) {
                    ocrBusy = false;
                    return;
                }
                startShot(cb);
            }, wait);
            return;
        }
        startShot(cb);
    }

    private void startShot(ShotCallback cb) {
        lastShotAt = System.currentTimeMillis();
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
