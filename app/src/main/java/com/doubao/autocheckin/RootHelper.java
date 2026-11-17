package com.doubao.autocheckin;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * root 授权（v1.5.7）：
 * 手动点一下，App 主动执行 `su -c id`，让「超级用户」弹窗在你人就在旁边的时候出来。
 * 等够 10 秒，足够点「允许」并勾上记住。
 */
public class RootHelper {

    private static final Handler UI = new Handler(Looper.getMainLooper());

    public static void request(final Activity act) {
        Toast.makeText(act, "正在申请 root 授权，请留意手机上的「超级用户」弹窗…", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            String out;
            boolean ok = false;
            try {
                Process p = new ProcessBuilder("sh", "-c", "su -c id")
                        .redirectErrorStream(true).start();
                if (p.waitFor(10, TimeUnit.SECONDS)) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    out = sb.toString().trim();
                    ok = p.exitValue() == 0 && out.contains("uid=0");
                } else {
                    p.destroy();
                    out = "10 秒内 su 没有返回（多半是弹窗在等授权，或者设备根本没有 su）";
                }
            } catch (Throwable t) {
                out = String.valueOf(t);
            }
            final String result = out;
            final boolean granted = ok;
            Logger.log(act, "申请 root 授权：" + (granted ? "成功 " : "失败 ") + result);
            UI.post(() -> new AlertDialog.Builder(act)
                    .setTitle(granted ? "root 已授权" : "root 没拿到")
                    .setMessage(granted
                            ? "已拿到 uid=0，早晨锁屏时可以直接用 root 注入按键解锁。\n\n" + result
                            : "su 的输出：\n" + result + "\n\n"
                            + "排查顺序：\n"
                            + "1) 打开 Magisk / KernelSU / APatch，找到「滴滴充电自动签到」，设为允许（永久）\n"
                            + "2) 如果列表里根本没有这个 App，说明 root 管理器开了白名单或隐藏，需要先把它加进去\n"
                            + "3) 如果这些管理器一个都没装，那这台机器其实没有 root，只能把锁屏改成无密码/滑动")
                    .setPositiveButton("知道了", null)
                    .show());
        }, "root-request").start();
    }
}
