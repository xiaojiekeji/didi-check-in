package com.doubao.autocheckin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 应用更新后恢复每日排程。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Logger.log(context, "开机/更新广播：" + (intent == null ? "-" : intent.getAction()) + "，恢复排程");
        CheckinScheduler.scheduleDaily(context);
        CheckinScheduler.scheduleReminderDaily(context);
        KeepAliveService.start(context);
        KeepAliveJobService.schedule(context);
    }
}
