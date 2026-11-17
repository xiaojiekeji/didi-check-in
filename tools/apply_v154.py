#!/usr/bin/env python3
"""一次性迁移：把 v1.5.4 的锁屏解锁改动写入源码（幂等，重复运行直接退出）。"""
import pathlib
import sys

SVC = pathlib.Path("app/src/main/java/com/doubao/autocheckin/CheckinAccessibilityService.java")
GRADLE = pathlib.Path("app/build.gradle")
README = pathlib.Path("README.md")

MARKER = "UNLOCK_BUDGET_MS"


def apply(text, old, new, tag):
    if old not in text:
        print("ANCHOR MISSING: " + tag)
        sys.exit(1)
    print("apply: " + tag)
    return text.replace(old, new, 1)


NEW_UNLOCK_BLOCK = r'''    /**
     * 锁屏解锁（v1.5.4）：
     * 1) 先把锁屏上滑，露出 PIN 数字键盘 —— 亮屏后手机停在时钟页，不上滑根本没有数字键，
     *    v1.5.3 就是死在这一步（日志首句就是「锁屏键盘上找不到数字 6」）；
     * 2) 用无障碍节点点数字键；
     * 3) 节点在但点不动（安全锁屏拒绝 ACTION_CLICK）→ 用节点 bounds 坐标注入手势点；
     * 4) 还不行 → 有 root 的设备直接 `su -c input text` 注入；
     * 5) 最后点「确定 / 解锁」，再复查锁屏是否解开。
     */
    private void tryUnlockKeyguard() {
        String pin = Config.pin(this);
        if (pin == null || pin.isEmpty()) {
            Logger.log(this, "未设置锁屏密码，跳过自动解锁");
            return;
        }
        Logger.log(this, "锁屏窗口包名：" + foregroundPkg());
        // 1) 上滑露出键盘；已经能看到数字键就不用滑
        for (int i = 0; i < 2; i++) {
            if (hasDigitKeys()) {
                break;
            }
            swipeOnKeyguard();
        }
        boolean ok = typePinByNodes(pin);
        if (!ok) {
            Logger.log(this, "节点点到一半失败，改用节点坐标手势输入");
            ok = typePinByNodeBounds(pin);
        }
        if (!ok) {
            Logger.log(this, "坐标手势也没输入进去，试试 root 注入");
            ok = typePinByRoot(pin);
        }
        if (!ok) {
            Logger.log(this, "锁屏键盘取不到数字键（安全锁屏/无节点），等待人工解锁");
            return;
        }
        clickUnlockConfirm();
        sleepQuietly(500L);
        Logger.log(this, "已输入锁屏密码，当前锁屏状态：" + (isKeyguardLocked() ? "仍锁着" : "已解开"));
    }

    /** 锁屏上滑：点亮后停在时钟页，必须上滑才出现密码键盘。 */
    private void swipeOnKeyguard() {
        try {
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            Path p = new Path();
            p.moveTo(w * 0.5f, h * 0.85f);
            p.lineTo(w * 0.5f, h * 0.25f);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 320))
                    .build();
            boolean sent = dispatchGesture(gesture, null, null);
            Logger.log(this, "锁屏上滑（露出密码键盘）" + (sent ? "已发送" : "被系统拒绝"));
            sleepQuietly(600L);
        } catch (Throwable t) {
            Logger.log(this, "锁屏上滑失败：" + t);
        }
    }

    private boolean hasDigitKeys() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && findKeyByDigit(root, '1') != null;
    }

    private boolean typePinByNodes(String pin) {
        for (char ch : pin.toCharArray()) {
            // 每敲一位都重新取根节点：旧节点输入一位后就会失效
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo key = root == null ? null : findKeyByDigit(root, ch);
            if (key == null) {
                Logger.log(this, "锁屏键盘节点里没有数字「" + ch + "」");
                return false;
            }
            if (!performClick(key)) {
                Logger.log(this, "数字「" + ch + "」节点点不动（安全锁屏），改用坐标");
                return false;
            }
            sleepQuietly(130L);
        }
        return true;
    }

    private boolean typePinByNodeBounds(String pin) {
        for (char ch : pin.toCharArray()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo key = root == null ? null : findKeyByDigit(root, ch);
            if (key == null) {
                return false;
            }
            Rect r = new Rect();
            key.getBoundsInScreen(r);
            if (r.isEmpty()) {
                return false;
            }
            Logger.log(this, "坐标点数字「" + ch + "」@" + r.centerX() + "," + r.centerY());
            tap(r.centerX(), r.centerY());
            sleepQuietly(150L);
        }
        return true;
    }

    /** 有 root 的设备直接注入按键，比无障碍点更可靠（仅在本机已授权 su 时生效）。 */
    private boolean typePinByRoot(String pin) {
        if (!hasRoot()) {
            return false;
        }
        String r1 = execShell("su -c 'input text " + pin + "'");
        if (r1 == null) {
            return false;
        }
        execShell("su -c 'input keyevent 66'");
        sleepQuietly(300L);
        return true;
    }

    private boolean hasRoot() {
        if (rootProbe >= 0) {
            return rootProbe == 1;
        }
        try {
            Process p = new ProcessBuilder("sh", "-c", "su -c id").redirectErrorStream(true).start();
            boolean done = p.waitFor(1500L, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroy();
                rootProbe = 0;
            } else {
                rootProbe = p.exitValue() == 0 ? 1 : 0;
            }
        } catch (Throwable t) {
            rootProbe = 0;
        }
        Logger.log(this, "root 探测：" + (rootProbe == 1 ? "可用" : "不可用"));
        return rootProbe == 1;
    }

    private String execShell(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            int code = p.waitFor();
            Logger.log(this, "执行「" + cmd + "」退出码 " + code + " 输出 " + sb.toString().trim());
            return code == 0 ? sb.toString() : null;
        } catch (Throwable t) {
            Logger.log(this, "执行「" + cmd + "」失败：" + t);
            return null;
        }
    }

    private void clickUnlockConfirm() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo ok = root == null ? null : findUnlockConfirm(root);
        if (ok == null) {
            return;
        }
        Logger.log(this, "点解锁确认键");
        performClick(ok);
        sleepQuietly(250L);
    }

    private AccessibilityNodeInfo findKeyByDigit(AccessibilityNodeInfo root, char digit) {
        String d = String.valueOf(digit);
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        for (AccessibilityNodeInfo n : nodes) {
            String t = normalizeKeyText(n.getText());
            String desc = normalizeKeyText(n.getContentDescription());
            if (d.equals(t) || d.equals(desc)) {
                return n;
            }
        }
        return null;
    }

    /** 数字键的文字往往是「１」「1 」「1\n」之类，统一成半角无空白再比较。 */
    private String normalizeKeyText(CharSequence cs) {
        if (cs == null) {
            return "";
        }
        String s = cs.toString().replace("\u00A0", "");
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c >= '０' && c <= '９') {
                sb.append((char) ('0' + (c - '０')));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

'''

README_SECTION = '''## 锁屏自动解锁（v1.5.4 修复）

v1.5.3 的早晨定时任务连续失败三次，全是「整体执行超时（180 秒）」，日志第一句就是
`锁屏键盘上找不到数字「6」`。原因不是密码错，而是**亮屏后手机停在锁屏时钟页，根本没打开 PIN 键盘**，
无障碍在时钟页上当然找不到数字键，然后一直干等到整轮 180 秒超时。

v1.5.4 的解锁顺序改成：

1. **先上滑锁屏**（时钟页 → 密码键盘），最多滑两次；已经能看到数字键就不滑
2. 用无障碍节点点数字键（每敲一位都重新取根节点，旧节点输入一位后会失效）
3. 节点在但点不动（安全锁屏拒绝 `ACTION_CLICK`）→ 用节点 bounds 的坐标 `dispatchGesture` 点击
4. 还不行 → 设备有 root 就用 `su -c input text <pin>` + `input keyevent 66` 注入
5. 最后尝试点「确定 / 解锁」，并复查 `isKeyguardLocked()`

另外加了**解锁预算 45 秒**：锁屏超过 45 秒没解开就立刻结束本轮（记录「锁屏未能自动解锁」并交给 5 分钟后的重试），
不再傻等 180 秒、也不再连丢三次才留下一条看不出原因的日志。

数字键文字做了归一化（全角 `６`、`6 `、`6\\n` 都能认）。

仍需注意：部分 ROM 的安全锁屏完全禁止第三方点击与手势注入，这种情况下自动解锁一定失败。
想做到早晨全无人值守，最省事的做法是**把锁屏改成「无密码 / 滑动解锁」**；保持 PIN 时，能否自动输入取决于机型策略。

'''


def main():
    svc = SVC.read_text(encoding="utf-8")
    if MARKER in svc:
        print("already patched, nothing to do")
        return

    svc = apply(
        svc,
        '    /** 整轮上限（加了 1~2 秒人工节奏后，正常 15~25 秒，这里留足保险）。 */\n'
        '    private static final long TASK_TIMEOUT_MS = 180_000L;\n',
        '    /** 整轮上限（加了 1~2 秒人工节奏后，正常 15~25 秒，这里留足保险）。 */\n'
        '    private static final long TASK_TIMEOUT_MS = 180_000L;\n'
        '    /**\n'
        '     * 锁屏状态下最多死磕这么久，超时就结束本轮交给 5 分钟后的重试。\n'
        '     * v1.5.3 就是在这里翻车的：锁屏解不开却一直等到整轮 180 秒超时，连丢三次、日志还看不出原因。\n'
        '     */\n'
        '    private static final long UNLOCK_BUDGET_MS = 45_000L;\n',
        "constants",
    )

    svc = apply(
        svc,
        '    /** 上一次尝试解锁的时间：锁屏时每 2 秒试一次，避免狂点。 */\n'
        '    private long lastUnlockTryAt;\n',
        '    /** 上一次尝试解锁的时间：锁屏时每 2 秒试一次，避免狂点。 */\n'
        '    private long lastUnlockTryAt;\n'
        '    /** 本轮第一次发现锁屏的时间，用来限制解锁预算，避免把整轮时间耗在锁屏上。 */\n'
        '    private long lockFirstSeenAt;\n'
        '    private int unlockTryCount;\n'
        '    /** root 探测结果：-1 未知 / 0 不可用 / 1 可用。 */\n'
        '    private int rootProbe = -1;\n',
        "fields",
    )

    svc = apply(
        svc,
        '        lastTapX = -1;\n'
        '        lastTapY = -1;\n'
        '        lastTapAt = 0;\n'
        '        lastShotPath = "";\n',
        '        lastTapX = -1;\n'
        '        lastTapY = -1;\n'
        '        lastTapAt = 0;\n'
        '        lastUnlockTryAt = 0;\n'
        '        lockFirstSeenAt = 0;\n'
        '        unlockTryCount = 0;\n'
        '        lastShotPath = "";\n',
        "startTask reset",
    )

    old_lock = (
        '        // -1) 手机还锁着屏：先尝试用无障碍点数字键输入密码解锁，解不开就提醒用户手动解锁\n'
        '        if (isKeyguardLocked()) {\n'
        '            if (System.currentTimeMillis() - lastUnlockTryAt > 2000L) {\n'
        '                lastUnlockTryAt = System.currentTimeMillis();\n'
        '                Logger.log(this, "屏幕锁屏中，尝试自动解锁…");\n'
        '                tryUnlockKeyguard();\n'
        '                if (isKeyguardLocked()) {\n'
        '                    Notifier.notify(this, Notifier.ID_FAIL, "需要解锁手机",\n'
        '                            "自动签到现在被锁屏挡住了，请解锁手机，解锁后会自动继续。");\n'
        '                    Logger.log(this, "自动解锁未成功，等待手动解锁（解锁后自动继续）");\n'
        '                }\n'
        '            }\n'
        '            nextDelay = 1200L;\n'
        '            return;\n'
        '        }\n'
    )
    new_lock = (
        '        // -1) 手机还锁着屏：先尝试用无障碍点数字键输入密码解锁，解不开就提醒用户手动解锁\n'
        '        if (isKeyguardLocked()) {\n'
        '            if (lockFirstSeenAt == 0L) {\n'
        '                lockFirstSeenAt = now;\n'
        '                Logger.log(this, "检测到锁屏，开始自动解锁（预算 " + (UNLOCK_BUDGET_MS / 1000) + " 秒）");\n'
        '            }\n'
        '            if (now - lockFirstSeenAt > UNLOCK_BUDGET_MS) {\n'
        '                Logger.log(this, "锁屏 " + ((now - lockFirstSeenAt) / 1000) + " 秒仍未解开，放弃本轮，交给 5 分钟后的重试");\n'
        '                finish(R_FAIL, "锁屏未能自动解锁（已尝试 " + unlockTryCount + " 次）。"\n'
        '                        + "请确认锁屏密码是 " + maskPin(Config.pin(this)) + "，或把锁屏改成滑动解锁");\n'
        '                return;\n'
        '            }\n'
        '            if (now - lastUnlockTryAt > 2500L) {\n'
        '                lastUnlockTryAt = now;\n'
        '                unlockTryCount++;\n'
        '                Logger.log(this, "屏幕锁屏中，第 " + unlockTryCount + " 次尝试自动解锁…");\n'
        '                tryUnlockKeyguard();\n'
        '                if (isKeyguardLocked()) {\n'
        '                    Notifier.notify(this, Notifier.ID_FAIL, "需要解锁手机",\n'
        '                            "自动签到现在被锁屏挡住了，请解锁手机，解锁后会自动继续。");\n'
        '                    Logger.log(this, "自动解锁第 " + unlockTryCount + " 次未成功，继续重试");\n'
        '                } else {\n'
        '                    Logger.log(this, "锁屏已解开，继续签到");\n'
        '                    lockFirstSeenAt = 0;\n'
        '                    unlockTryCount = 0;\n'
        '                    settleUntil = now + 800L;\n'
        '                }\n'
        '            }\n'
        '            nextDelay = 1200L;\n'
        '            return;\n'
        '        }\n'
    )
    svc = apply(svc, old_lock, new_lock, "step lock branch")

    start = svc.index("    /**\n     * 锁屏解锁：靠无障碍点锁屏数字键盘")
    end = svc.index("    private AccessibilityNodeInfo findUnlockConfirm(AccessibilityNodeInfo root) {")
    svc = svc[:start] + NEW_UNLOCK_BLOCK + svc[end:]
    print("apply: unlock block")

    SVC.write_text(svc, encoding="utf-8")

    g = GRADLE.read_text(encoding="utf-8")
    g = g.replace("versionCode 13", "versionCode 14").replace('versionName "1.5.3"', 'versionName "1.5.4"')
    GRADLE.write_text(g, encoding="utf-8")
    print("apply: version bump")

    r = README.read_text(encoding="utf-8")
    if "## 锁屏自动解锁（v1.5.4 修复）" not in r:
        r = r.replace("## 功能", README_SECTION + "## 功能", 1)
        README.write_text(r, encoding="utf-8")
        print("apply: README")


if __name__ == "__main__":
    main()
