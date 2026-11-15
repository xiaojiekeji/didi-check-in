package com.doubao.autocheckin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 闹钟到点后触发执行，并排下一次。 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Logger.log(context, "闹钟触发（" + (intent == null ? "-" : intent.getAction()) + "）");
        // 先排好下一天的日常任务，避免中途异常导致排程丢失
        CheckinScheduler.scheduleDaily(context);

        CheckinAccessibilityService service = CheckinAccessibilityService.getInstance();
        if (service == null) {
            Logger.log(context, "无障碍服务未运行，本次无法自动签到");
            Notifier.notify(context, Notifier.ID_FAIL, "签到失败：无障碍服务未开启",
                    "请在「设置 → 无障碍」中开启本应用的服务，然后点「立即测试执行一次」验证。");
            return;
        }
        service.startTask("定时任务");
    }
}
