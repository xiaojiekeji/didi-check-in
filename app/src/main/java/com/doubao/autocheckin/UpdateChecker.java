package com.doubao.autocheckin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 检测升级（v1.5.7）：
 * 读 github.com 上的 Release 跳转拿最新 tag（不调 api.github.com，国内基本不可达），
 * 下载安装包按「直连 → 国内加速镜像」顺序重试，不需要挂 VPN。
 */
public class UpdateChecker {

    private static final String OWNER = "xiaojiekeji8";
    private static final String REPO = "didi-check-in";
    private static final String ASSET = "app-debug.apk";
    private static final String RELEASE_PAGE = "https://github.com/" + OWNER + "/" + REPO + "/releases/latest";
    private static final String DOWNLOAD_BASE = "https://github.com/" + OWNER + "/" + REPO + "/releases/download/";

    /** 依次尝试的线路，空串代表直连 GitHub。 */
    private static final String[] MIRRORS = {
            "",
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/"
    };

    private static final Handler UI = new Handler(Looper.getMainLooper());

    public static void check(final Activity act) {
        toast(act, "正在检查最新版本…");
        new Thread(() -> {
            try {
                String tag = fetchLatestTag();
                if (tag == null || tag.isEmpty()) {
                    post(() -> dialog(act, "检查失败",
                            "没能从 GitHub 读到最新版本号（可能网络受限）。"));
                    return;
                }
                String cur = currentVersion(act);
                if (!isNewer(tag, cur)) {
                    post(() -> dialog(act, "已是最新版本", "当前 " + cur + "，线上 " + tag + "。"));
                    return;
                }
                post(() -> new AlertDialog.Builder(act)
                        .setTitle("发现新版本 " + tag)
                        .setMessage("当前 " + cur + "。\n下载约 47MB，走国内加速线路，不需要 VPN。")
                        .setNegativeButton("稍后", null)
                        .setPositiveButton("立即升级", (d, w) -> startDownload(act, tag))
                        .show());
            } catch (Throwable t) {
                post(() -> dialog(act, "检查失败", String.valueOf(t)));
            }
        }, "update-check").start();
    }

    private static void startDownload(final Activity act, final String tag) {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(act)
                    .setTitle("先允许安装应用")
                    .setMessage("系统还没给本应用「安装未知应用」权限。去开启后回来再点一次「检测升级」。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("去开启", (d, w) -> {
                        try {
                            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + act.getPackageName())));
                        } catch (Throwable t) {
                            toast(act, "打不开设置页，请手动到「设置 → 应用 → 安装未知应用」里允许");
                        }
                    })
                    .show();
            return;
        }
        toast(act, "开始下载 " + tag + " …");
        new Thread(() -> {
            try {
                File apk = download(act, tag);
                post(() -> dialog(act, "下载完成",
                        "点下面「安装」进入系统安装器，装完记录和配置都保留。",
                        "安装", () -> install(act, apk)));
            } catch (Throwable t) {
                post(() -> dialog(act, "下载失败", "所有线路都没成功：\n" + t));
            }
        }, "update-download").start();
    }

    private static File download(Activity act, String tag) throws Exception {
        File dir = new File(act.getCacheDir(), "apk");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建缓存目录");
        }
        File out = new File(dir, "update-" + tag + ".apk");
        String rel = DOWNLOAD_BASE + tag + "/" + ASSET;
        Exception last = null;
        for (String prefix : MIRRORS) {
            String url = prefix + rel;
            try {
                fetchTo(url, out);
                if (out.length() > 20L * 1024 * 1024) {
                    Logger.log(act, "升级包下载成功：" + url + " 大小 " + out.length());
                    return out;
                }
                throw new Exception("文件太小，疑似被拦截");
            } catch (Exception e) {
                last = e;
                Logger.log(act, "升级线路失败 " + url + " ：" + e);
            }
        }
        throw last == null ? new Exception("没有可用线路") : last;
    }

    private static void fetchTo(String url, File out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DidiCheckinUpdater");
        int code = c.getResponseCode();
        if (code != 200) {
            throw new Exception("HTTP " + code);
        }
        try (InputStream in = c.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
        } finally {
            c.disconnect();
        }
    }

    private static void install(Activity act, File apk) {
        Uri uri = new Uri.Builder()
                .scheme("content")
                .authority(ApkProvider.AUTHORITY)
                .appendPath(apk.getName())
                .build();
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            act.startActivity(i);
        } catch (Throwable t) {
            toast(act, "拉起安装器失败：" + t);
        }
    }

    /** 读 github.com 的 302 跳转拿到最新 tag，例如 v1.5.7。 */
    private static String fetchLatestTag() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(RELEASE_PAGE).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        int code = c.getResponseCode();
        String loc = c.getHeaderField("Location");
        c.disconnect();
        if (loc != null) {
            int i = loc.lastIndexOf("/tag/");
            if (i > 0) {
                return loc.substring(i + 5).trim();
            }
        }
        throw new Exception("HTTP " + code + "，没拿到跳转地址");
    }

    private static String currentVersion(Activity act) {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0";
        }
    }

    /** v1.5.7 > v1.5.6 这种比较，逐段取数字比。 */
    private static boolean isNewer(String remote, String current) {
        int[] r = toNums(remote);
        int[] l = toNums(current);
        for (int i = 0; i < Math.max(r.length, l.length); i++) {
            int a = i < r.length ? r[i] : 0;
            int b = i < l.length ? l[i] : 0;
            if (a > b) {
                return true;
            }
            if (a < b) {
                return false;
            }
        }
        return false;
    }

    private static int[] toNums(String v) {
        String s = v == null ? "" : v.replaceAll("[^0-9.]", "");
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (Throwable ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static void dialog(Activity act, String title, String msg) {
        dialog(act, title, msg, null, null);
    }

    private static void dialog(Activity act, String title, String msg, String posText, Runnable posAction) {
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(posText == null ? "知道了" : posText,
                        (d, w) -> {
                            if (posAction != null) {
                                posAction.run();
                            }
                        })
                .show();
    }

    private static void toast(Activity act, String msg) {
        UI.post(() -> Toast.makeText(act, msg, Toast.LENGTH_SHORT).show());
    }

    private static void post(Runnable r) {
        UI.post(r);
    }
}
