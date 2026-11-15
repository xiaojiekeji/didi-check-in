# didi-check-in

安卓端「滴滴出行 · 小桔充电」每日自动签到工具。纯本机运行，不联网上传任何数据，不需要抓包、不需要 token。

> 说明：本工程基于无障碍服务（AccessibilityService）模拟点击实现，属于对平台规则的灰区操作。
> 建议先用小号试运行 1~2 周，一天只跑一次，遇到验证码/滑块立即停手，不要做破解。

## 功能

- 每日固定时刻（默认 08:30）+ 0~30 分钟随机偏移自动触发，避开机械的整点特征
- 开机、应用更新后自动恢复排程
- 进入 App → 找到充电入口 → 判断「今日已签到」→ 点击签到 → 校验结果
- 校验失败自动重试（间隔 5 分钟，最多 3 次）
- 每次执行自动截图 + 本地日志（保留 30 天）
- 通知栏汇报结果；无障碍未开启时直接提醒
- 文案关键词（入口/按钮/已签到/成功/弹窗）全部可在资源文件里改，页面改版不需要重写代码

## 目录结构

```
app/src/main/java/com/doubao/autocheckin/
├── MainActivity.java                  # 配置与状态面板
├── CheckinAccessibilityService.java   # 核心状态机（轮询式）
├── CheckinScheduler.java              # 每日排程 / 重试排程
├── AlarmReceiver.java                 # 闹钟触发
├── BootReceiver.java                  # 开机恢复排程
├── Config.java                        # 配置读写
├── Logger.java                        # 本地日志
└── Notifier.java                      # 通知
app/src/main/res/values/arrays.xml      # 所有关键词（改这里适配页面）
app/src/main/res/xml/accessibility_service_config.xml
```

## 编译

需要 JDK 17 + Android SDK（platform 34、build-tools 34.0.0）。Android Studio 直接打开本目录即可。

命令行：

```bash
gradle :app:assembleDebug      # 产物 app/build/outputs/apk/debug/app-debug.apk
```

## 首次使用

1. 安装 APK，打开应用
2. 依次点：
   - 去开启无障碍服务（设置 → 无障碍 → 滴滴充电自动签到 → 开启）
   - 加入电池优化白名单（否则国产 ROM 会杀后台）
   - 允许精确闹钟（Android 12+ 必须，否则只能走近似闹钟）
   - 允许通知
3. 设置执行时间与目标包名，点「保存配置并重新排程」
4. 点「立即测试执行一次」，看日志与「最近截图」确认能走到签到按钮

## 适配页面改版

改 `app/src/main/res/values/arrays.xml`：

| 数组 | 作用 |
| --- | --- |
| `nav_keywords` | 首页进入充电页的入口文案 |
| `entry_keywords` | 签到按钮文案（自上而下按顺序尝试） |
| `signed_keywords` | 「今天已经签过」的判定文案，优先于按钮判断 |
| `success_keywords` | 点击后的成功判定文案 |
| `popup_keywords` | 需要顺手关掉的弹窗按钮 |

## 已知限制

- 屏幕若是密码锁屏，无障碍服务无法越过锁屏，需要关闭锁屏密码或用智能解锁；任务会记录在日志里
- 部分 ROM（MIUI / ColorOS / EMUI）需要在系统设置里额外允许「自启动」「后台弹出界面」
- 目标 App 若走 H5/Flutter 自绘控件，可能取不到文字节点，此时依赖坐标兜底点击（服务内已内置 Rect 中心点手势点击）
- 截图能力需要 Android 11+

## 合规提示

自动化操作可能违反平台用户协议，存在被风控/封号风险；本项目仅用于个人单账号、低频使用，请勿批量多账号运行。
