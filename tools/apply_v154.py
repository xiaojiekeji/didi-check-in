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


def main():
    svc = SVC.read_text(encoding="utf-8")
    if MARKER in svc:
        print("already patched, nothing to do")
        return
    if "private void tryUnlockKeyguard() {" not in svc:
        print("unexpected source, abort")
        sys.exit(1)

    # 1) 常量：解锁预算
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

    # 2) 字段
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

    # 3) startTask 里重置状态
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

    # 4) step() 里的锁屏分支：加预算 + 每次间隔 2.5 秒
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

    # 5) 解锁实现：整段替换（旧版没有上滑、旧节点复用、无坐标/root 备用）
    start = svc.index("    /**\n     * 锁屏解锁：靠无障碍点锁屏数字键盘")
    end_marker = "    private AccessibilityNodeInfo findUnlockConfirm(AccessibilityNodeInfo root) {"
    end = svc.index(end_marker)
    new_block = NEW_UNLOCK_BLOCK
    svc = svc[:start] + new_block + svc[end:]
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


NEW_UNLOCK_BLOCK = '''PLACEHOLDER_NEW_BLOCK'''
README_SECTION = '''PLACEHOLDER_README'''

if __name__ == "__main__":
    main()
