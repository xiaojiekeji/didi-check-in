#!/usr/bin/env python3
"""一次性迁移：v1.5.5 改成 root 优先注入解锁（幂等）。"""
import pathlib
import sys

SVC = pathlib.Path("app/src/main/java/com/doubao/autocheckin/CheckinAccessibilityService.java")
GRADLE = pathlib.Path("app/build.gradle")
README = pathlib.Path("README.md")

UNLOCK_METHOD = r'''    /**
     * 锁屏解锁（v1.5.5）：
     * 0) 有 root 优先走 root 注入（安全锁屏会拒绝无障碍点击和手势，root 的 input 事件不受这个限制）；
     * 1) 没有 root 才上滑锁屏露出 PIN 数字键盘（时钟页根本没有数字键，v1.5.3 就死在这里）；
     * 2) 用无障碍节点点数字键；
     * 3) 节点在但点不动 → 用节点 bounds 坐标注入手势点；
     * 4) 最后点「确定 / 解锁」，再复查锁屏是否解开。
     */
    private void tryUnlockKeyguard() {
        String pin = Config.pin(this);
        if (pin == null || pin.isEmpty()) {
            Logger.log(this, "未设置锁屏密码，跳过自动解锁");
            return;
        }
        Logger.log(this, "锁屏窗口包名：" + foregroundPkg());
        // 0) root 主路径：input keyevent 注入数字键，不受安全锁屏限制
        if (hasRoot() && unlockByRoot(pin)) {
            Logger.log(this, "root 注入解锁成功，继续签到");
            return;
        }
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
            Logger.log(this, "无障碍节点与坐标手势都失败，且 root 不可用，等待人工解锁");
            return;
        }
        clickUnlockConfirm();
        sleepQuietly(500L);
        Logger.log(this, "已输入锁屏密码，当前锁屏状态：" + (isKeyguardLocked() ? "仍锁着" : "已解开"));
    }

'''

ROOT_BLOCK = r'''    /**
     * root 注入解锁（v1.5.5 主路径）：
     * 用 {@code input keyevent} 发数字键（KEYCODE_0=7 … KEYCODE_9=16），比 {@code input text} 可靠 ——
     * 锁屏 PIN 框不是输入框，input text 经常一个字符都灌不进去，这是 v1.5.4 里潜着的坑。
     * 顺序：唤醒 → wm dismiss-keyguard（无密码机型直接消掉）→ 上滑 → 数字键 + 确认。
     */
    private boolean unlockByRoot(String pin) {
        String codes = pinToKeyCodes(pin);
        execShell("su -c 'input keyevent 224'");
        sleepQuietly(350L);
        execShell("su -c 'wm dismiss-keyguard'");
        sleepQuietly(450L);
        if (!isKeyguardLocked()) {
            Logger.log(this, "wm dismiss-keyguard 已消掉锁屏");
            return true;
        }
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        execShell("su -c 'input swipe " + (w / 2) + " " + (int) (h * 0.85f) + " "
                + (w / 2) + " " + (int) (h * 0.25f) + " 300'");
        sleepQuietly(700L);
        // 一次 su 就把整串数字和确认键发完，减少 su 往返的几百毫秒
        execShell("su -c 'input keyevent " + codes + " 66'");
        sleepQuietly(800L);
        boolean unlocked = !isKeyguardLocked();
        Logger.log(this, "root 注入结果：" + (unlocked ? "已解开" : "仍锁着") + "（keycodes " + codes + "）");
        return unlocked;
    }

    /** 688888 → "13 15 15 15 15 15"（KEYCODE_0=7 … KEYCODE_9=16）。 */
    private String pinToKeyCodes(String pin) {
        StringBuilder sb = new StringBuilder();
        for (char c : pin.toCharArray()) {
            if (c < '0' || c > '9') {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(7 + (c - '0'));
        }
        return sb.toString();
    }

    private boolean hasRoot() {
        if (rootProbe >= 0) {
            return rootProbe == 1;
        }
        Logger.log(this, "正在请求 root 授权，如果手机上弹出「超级用户」请求请点【允许】并勾选记住");
        try {
            Process p = new ProcessBuilder("sh", "-c", "su -c id").redirectErrorStream(true).start();
            boolean done = p.waitFor(2500L, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroy();
                rootProbe = 0;
                Logger.log(this, "su 2.5 秒没返回（可能在等授权弹窗），本次记为不可用");
            } else {
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    out.append(line).append(' ');
                }
                String text = out.toString();
                rootProbe = (p.exitValue() == 0 && text.contains("uid=0")) ? 1 : 0;
                Logger.log(this, "root 探测输出：" + text.trim());
            }
        } catch (Throwable t) {
            rootProbe = 0;
            Logger.log(this, "root 探测失败：" + t);
        }
        Logger.log(this, "root 探测：" + (rootProbe == 1 ? "可用" : "不可用"));
        return rootProbe == 1;
    }

'''

README_SECTION = '''## root 注入解锁（v1.5.5）

v1.5.4 把解锁做成「无障碍点数字键 → 点不动再用坐标手势」，但 NX809J 这类安全锁屏会
**同时拒绝 ACTION_CLICK 和注入手势**，所以点不动也滑不动。v1.5.5 改成 root 优先：

1. **先查 root**，有 root 就走 root 主路径（`input` 是系统级注入，不受安全锁屏限制）
2. `su -c input keyevent 224` 唤醒屏幕
3. `su -c wm dismiss-keyguard` —— 无密码 / 滑动解锁的机型到这一步就开了
4. 还锁着就 `su -c input swipe` 上滑露出 PIN 键盘
5. `su -c input keyevent 13 15 15 15 15 15 66` 一次性发完 688888 + 确认键
   （用 keyevent 而不是 `input text`：锁屏 PIN 框不是输入框，`input text` 经常一个字都灌不进去）
6. 只有 root 不可用时，才退回无障碍点数字键 / 坐标手势

**授权要在有人在旁边的时候做**：开启无障碍服务后 App 会主动 `su -c id` 探测一次 root，
这时「超级用户」弹窗就会出来，点「允许」并勾选记住；如果是 7 点锁屏状态下才弹窗，
没人点得到，那一轮就必然失败。日志里会写明 `root 探测：可用 / 不可用`。

'''


def slice_replace(svc, start_marker, end_marker, new, tag):
    if start_marker not in svc:
        print("START MISSING: " + tag)
        sys.exit(1)
    if end_marker not in svc:
        print("END MISSING: " + tag)
        sys.exit(1)
    i = svc.index(start_marker)
    j = svc.rfind("    /**", 0, i)
    k = svc.index(end_marker, i)
    print("apply: " + tag)
    return svc[:j] + new + svc[k:]


def main():
    svc = SVC.read_text(encoding="utf-8")
    if "unlockByRoot" in svc:
        print("already patched, nothing to do")
        return

    old_warm = ('        // 后台预热 OCR 模型，第一次识别不再等模型加载\n'
                '        new Thread(() -> ScreenOcr.warmup(), "ocr-warmup").start();\n')
    new_warm = ('        // 后台预热 OCR 模型，第一次识别不再等模型加载\n'
                '        new Thread(() -> ScreenOcr.warmup(), "ocr-warmup").start();\n'
                '        // 提前试一次 root：让「超级用户」弹窗在你人就在旁边的时候出现，\n'
                '        // 别等到早上 7 点锁屏了才弹、没人点「允许」\n'
                '        handler.postDelayed(() -> {\n'
                '            if (hasRoot()) {\n'
                '                Logger.log(this, "root 已授权：早晨锁屏时用 root 注入按键解锁");\n'
                '            } else {\n'
                '                Logger.log(this, "root 未授权：请到 Magisk/超级用户 里给「滴滴充电自动签到」永久授权，"\n'
                '                        + "否则早晨只能靠无障碍点键盘（安全锁屏通常会拒绝）");\n'
                '            }\n'
                '        }, 2500L);\n')
    if old_warm not in svc:
        print("ANCHOR MISSING: ocr warmup")
        sys.exit(1)
    svc = svc.replace(old_warm, new_warm, 1)
    print("apply: root warmup")

    svc = slice_replace(svc, "锁屏解锁（v1.5.4）", "    /** 锁屏上滑：点亮后停在时钟页",
                        UNLOCK_METHOD, "tryUnlockKeyguard")
    svc = slice_replace(svc, "有 root 的设备直接注入按键", "    private String execShell(String cmd) {",
                        ROOT_BLOCK, "root block")

    SVC.write_text(svc, encoding="utf-8")

    g = GRADLE.read_text(encoding="utf-8")
    g = g.replace("versionCode 14", "versionCode 15").replace('versionName "1.5.4"', 'versionName "1.5.5"')
    GRADLE.write_text(g, encoding="utf-8")
    print("apply: version bump")

    r = README.read_text(encoding="utf-8")
    if "## root 注入解锁（v1.5.5）" not in r:
        r = r.replace("## 锁屏自动解锁（v1.5.4 修复）", README_SECTION + "## 锁屏自动解锁（v1.5.4 修复）", 1)
        README.write_text(r, encoding="utf-8")
        print("apply: README")


if __name__ == "__main__":
    main()
