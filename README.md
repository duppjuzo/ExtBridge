# ExtBridge Lite (`v3.4.2-lite`) — 跨系统高兼容纯净版 Type-C 类 DeX 扩展桌面系统

> **无需 Root · 单个 APK 自激活 · 面向多品牌 Android 系统（ColorOS / OriginOS / Flyme / 类原生 AOSP / HyperOS 等）的高兼容纯净分支 · [📖 使用说明书](docs/USER_GUIDE.md) · [📝 v3.4.2-lite 分支说明](docs/RELEASE_v3.4.2-lite.md)**

`lite` 分支基于主线 `v3.4.2` 裁剪构建，**彻底移除了对底层非公开隐藏接口耦合最重的「镜像信息侧边栏 / 双虚拟屏 / 边缘鼠标捕获 / AppWidget 宿主」模块**，同时**完整保留了 DeX 独立扩展桌面、自由分屏、Dock 自动隐藏、主屏一键黑屏省电与纯净原生镜像切换**，并增强了跨品牌 Android 11–16 系统的接口签名自适应与权限引导。

> 如需完整的「原生镜像黑边信息副屏 + 14 种预设卡片 + 系统桌面小组件 + 物理鼠标跨屏操控」功能，请切换至仓库 `main` 主分支。

---

## ✨ 核心特性 (`v3.4.2-lite`)

### 1. 🖥️ 真·双屏独立 DeX 扩展桌面 + 21:9 带鱼屏自适应
- 自动探测外接物理显示器 (`Display.TYPE_EXTERNAL`) 并通过 `SurfaceControl` 投射独立的 `VirtualDisplay`（首次接入自动从 EDID 优先识别 `≥50Hz` 最高原生宽屏物理分辨率与适配 DPI，避免 3:2 平板默认回落到 4:3/16:9 留黑边）。
- **独立「桌面渲染分辨率」调节与四向旋转（`0° / 90° / 180° / 270°`）**：内置 `3840×2160 (4K)`、`3440×1440 (21:9 准4K带鱼)`、`2560×1600`、`2560×1440`、`2560×1080 (21:9 带鱼)`、`1920×1200`、`1920×1080`、`1600×900`、`1280×720` 预设及自定义宽高比，「恢复推荐默认」自动匹配当前显示器最佳原生 EDID 分辨率。
- **纯净原生镜像一键切换**：在 DeX 独立桌面与纯净居中原生镜像间一键无缝切换（不创建第二虚拟屏、无后台轮询，若定制 ROM 无 `mirrorDisplay` 接口则自动回退系统原生镜像）。

### 2. 🌙 一键黑屏（平板/手机主屏最低亮度省电护眼）
- 在外接屏 Dock 快捷控制浮窗、主控制台首页或虚拟触摸板底部点击 **「🌙 一键黑屏」**：
  - **DeX 模式下**：主屏立即叠加纯黑防误触遮罩（支持 `NavAssistService` 无障碍层级兜底）并将物理背光压至硬件最低值 `1`；**双击主屏任意位置**、关闭服务或拔掉外接屏时自动精确还原进入前的亮度。
  - **纯净镜像模式下**：仅将本机内屏硬件背光压至最低（不盖黑窗，确保外接显示器/电视画面正常高亮），再点立即恢复。

### 3. ◫ DeX 自由分屏模式 & Dock 自动隐藏
- **横屏自动左右分屏 / 竖屏自动上下分屏**：Dock `◫ 分屏` 一键配对最近两个应用，或在桌面长按/右键图标选择放入左/右/上/下屏；中央拖拽分割条支持 **`25% ~ 75%`** 实时调节比例与 `3:7 / 4:6 / 5:5 / 6:4 / 7:3` 快捷预设、一键互换与退出。
- **自适应自动隐藏 Dock 栏 & Win11 风格快捷浮窗**：进入应用闲置约 `2.6s` 自动平滑收起为底边触发条，鼠标移至底边即刻唤醒。
- **三档分应用显示兼容 + 防悬浮小窗劫持**：支持 *跟随系统默认 / 强制横屏全屏 / 竖屏居中兼容*，自动锁定扩展屏 `WINDOWING_MODE_FULLSCREEN` 容器，防止工作台/强制桌面模式将应用误转为不可鼠标拖拽的小窗。

### 4. 🛡️ 专为多品牌 Android 系统优化的兼容性设计
- **零副屏 / 零边缘抢焦点开销**：完全去除第二虚拟屏 `sSideVd`、`IInputManager.getCursorPositionInLogicalDisplay` 轮询、透明 `PointerCapture` 捕获页、`IAppWidgetService` 反射授权及周期性 `dumpsys audio/media_session` 调用。
- **跨 Android 11–16 方法签名自适应 + CLI 兜底**：`IActivityTaskManager.startActivity` 自动兼容 10 参数（Android 11）与 11 参数（Android 12–16）签名，并在深度魔改 ROM 上自动回退 `am start --display` 命令。
- **分品牌权限智能提示**：当输入注入被系统拦截 (`INJECT_EVENTS`) 时，自动识别品牌提示对应开关（如小米/REDMI「USB 调试（安全设置）」、OPPO/一加/真我 ColorOS「禁止权限监控」、vivo/iQOO「USB 模拟点击」）。

---

## 📸 实机效果展示

| DeX 独立扩展桌面 & Dock | Win11 快捷浮窗 & 一键黑屏入口 | 主屏一键黑屏最低亮度保护 |
| :---: | :---: | :---: |
| ![ExtBridge 扩展桌面](docs/images/ext-desktop.png) | ![Dock 快捷浮窗](docs/images/ext-flyout.png) | ![主屏一键黑屏](docs/images/dex-blackout.png) |

| 短剧等应用强制横屏全屏 | DeX 50/50 自由分屏模式 | 主控制台与增强虚拟触摸板 |
| :---: | :---: | :---: |
| ![强制横屏全屏](docs/images/ext-fullscreen.png) | ![自由分屏](docs/images/ext-split.png) | <img src="docs/images/console-main.png" width="46%" /> <img src="docs/images/touchpad-portrait.png" width="46%" /> |

---

## 🏗️ 架构与目录结构（Lite 纯净分支）

```text
├── bridge/                                  # Shell 权限后台守护进程（app_process 启动）
│   └── src/com/extbridge/daemon/
│       └── ExtBridge.java                   # 物理屏接管、单 VirtualDisplay 投射、纯净镜像合成、
│                                            # 分屏/键鼠注入、一键黑屏调光、跨系统反射兼容
├── helper/                                  # Android 客户端 App (com.extbridge.helper)
│   ├── AndroidManifest.xml                  # 清单声明 (v3.4.2-lite / code 21)
│   ├── jniLibs/arm64-v8a/libadb.so          # 内置 arm64 adb 二进制，供免电脑无线调试自激活使用
│   ├── res/
│   └── src/com/extbridge/helper/
│       ├── MainActivity.java                # 控制台主界面（分辨率/旋转/一键黑屏/分应用兼容/激活引导）
│       ├── LauncherActivity.java            # 外接屏独立桌面启动器（壁纸同步、拼音检索、分屏/兼容菜单）
│       ├── BridgeService.java               # 前台服务（Dock、快捷浮窗、分屏分割条、黑屏遮罩、光标）
│       ├── TouchpadActivity.java            # 手机/平板端大触摸板、一键黑屏与软键盘输入转发
│       ├── BridgeClient.java                # Binder IPC 客户端与多品牌权限提示工具
│       ├── BridgeProvider.java              # 安全分发 Binder 句柄（UID 白名单鉴权）
│       ├── ControlReceiver.java             # `goAsync()` 异步后台广播接收器
│       ├── NavAssistService.java            # 无障碍辅助服务（高优先级黑屏遮罩、浮层与手势穿透）
│       ├── AdbBootstrap.java                # mDNS 自动发现、无线调试配对连接、守护进程拉起
│       └── AdbPairingReceiver.java          # 通知栏 6 位配对码快捷响应
├── docs/
│   ├── USER_GUIDE.md                        # Lite 纯净版图文使用说明书
│   ├── RELEASE_v3.4.2-lite.md               # Lite 分支更新与裁剪说明
│   └── images/                              # 实机功能截图
├── .github/workflows/build.yml              # GitHub Actions 自动构建与 Release 工作流
└── build_v3.ps1                             # Windows 免 Gradle 一键编译脚本
```

---

## 🚀 快速上手

1. 安装 `ExtBridge_v3.4.2-lite.apk`，首次打开按引导授予 **通知**、**悬浮窗** 与 **无障碍服务 (`ExtBridge 平板辅助手势`)** 权限。
   - **小米 / REDMI**：建议在开发者选项开启「USB 调试（安全设置）」；
   - **OPPO / 一加 / 真我 (ColorOS)**：建议在开发者选项开启「禁止权限监控」；
   - **vivo / iQOO (OriginOS)**：建议在开发者选项开启「USB 模拟点击 / 允许模拟输入」。
2. 选择 **本机无线调试通知栏填 6 位配对码**、**电脑 ADB 一键命令** 或 **Root 一键降权激活** 拉起守护进程：
   ```bash
   adb shell "(pm path com.extbridge.helper | head -n1 | cut -d: -f2; echo assets/extbridge.jar) | xargs unzip -p > /data/local/tmp/extbridge.jar && chmod 644 /data/local/tmp/extbridge.jar && appops set com.extbridge.helper SYSTEM_ALERT_WINDOW allow && appops set com.extbridge.helper RUN_ANY_IN_BACKGROUND allow && appops set com.extbridge.helper 10021 allow 2>/dev/null; appops set com.extbridge.helper ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null; pm grant com.extbridge.helper android.permission.POST_NOTIFICATIONS 2>/dev/null; dumpsys deviceidle whitelist +com.extbridge.helper >/dev/null 2>&1; pkill -f '^app_process.*ExtBridge' 2>/dev/null; sleep 0.2; CLASSPATH=/data/local/tmp/extbridge.jar nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge >/data/local/tmp/extbridge.log 2>&1 </dev/null &"
   ```
3. 插入 Type-C 显示器即可体验 **DeX 独立扩展桌面、自由分屏、一键黑屏省电与纯净原生镜像切换**。

---

## ⚠️ 开源协议
基于 [MIT License](LICENSE) 开源。
