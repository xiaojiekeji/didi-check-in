package com.doubao.autocheckin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 远程测试入口（v1.5.6）：
 * <pre>
 * adb shell am broadcast -a com.doubao.autocheckin.ACTION_RUN
 * </pre>
 * 用来在不点屏幕的情况下立刻跑一轮完整打卡，方便在电脑上做真机验证。
 * 只做「启动一轮任务」这一件事，不做任何数据修改。
 */
public class TestReceiver extends BroadcastReceiver {

    public static final String ACTION_RUN = "com.doubao.autocheckin.ACTION_RUN";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_RUN.equals(intent.getAction())) {
            return;
        }
        CheckinAccessibilityService svc = CheckinAccessibilityService.getInstance();
        if (svc == null) {
            Logger.log(context, "远程测试：无障碍服务未运行，请先在系统设置里打开无障碍开关");
            Notifier.notify(context, Notifier.ID_FAIL, "远程测试未启动", "无障碍服务未运行");
            return;
        }
        Logger.log(context, "收到远程测试指令，立即开始一轮打卡");
        svc.startTask("远程测试");
    }
}
