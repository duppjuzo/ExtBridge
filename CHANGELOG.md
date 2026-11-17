# Changelog / 更新日志

本项目版本号与安装包后缀同步（见 `helper/AndroidManifest.xml` 及 GitHub Releases）。

---

## v3.4.2-lite (`versionCode: 21`, `lite` 高兼容分支) — 2026-11-18

### 🌿 跨品牌系统高兼容纯净分支构建
- **基于 `v3.4.2` 完整保留核心生产力特性**：保留 **DeX 独立扩展桌面**、**DeX 自由分屏（左右/上下可拖拽）**、**Dock 自动隐藏**、**主屏一键黑屏最低亮度省电**（并在主控制台与触摸板新增快捷黑屏按钮）、**`21:9` 带鱼屏与 EDID 最优分辨率自适应**、**防工作台悬浮小窗劫持保护** 及 **纯净原生镜像无缝切换**。
- **彻底剥离镜像副屏高耦合链路**：移除第二虚拟屏 `sSideVd`、镜像信息侧边栏、`AppWidget` 宿主绑定、`getCursorPositionInLogicalDisplay` 光标轮询、边缘鼠标捕获 (`SideMouseCaptureActivity` / `SideControlActivity`)、周期性 `dumpsys audio/media_session` 解析及小米 `com.miui.home` 强停逻辑，消除跨 ROM 隐藏接口兼容隐患与后台轮询开销。
- **增强多品牌 Android 11–16 适配**：`IActivityTaskManager.startActivity` 支持 10/11 参数双签名反射与 `am start --display` 命令兜底；输入注入拦截提示自动适配小米 HyperOS、OPPO/一加 ColorOS（「禁止权限监控」）与 vivo OriginOS。详见 [`docs/RELEASE_v3.4.2-lite.md`](docs/RELEASE_v3.4.2-lite.md)。

---

## v3.4.2 (`versionCode: 21`) — 2026-11-18

### 🐛 关键兼容修复：小米平板工作台小窗劫持防护 & 21:9 带鱼屏原生适配
- **彻底解决开启「小米工作台模式 / 强制桌面模式」时扩展屏桌面与应用被强转为居中悬浮小窗 (`•••`) 及鼠标无法拖拽窗口的问题**
  - 在小米平板 6S Pro（HyperOS 3）等开启系统自带「工作台模式 (`miui_dkt_mode`)」或开发者选项「强制使用桌面模式 (`force_desktop_mode_on_external_displays`)」的设备上，系统会将默认 `WINDOWING_MODE_UNDEFINED (0)` 的可缩放窗口强行拦截为 `WINDOWING_MODE_FREEFORM (5)` 悬浮小窗，且小米原生小窗顶部 `•••` 仅绑定主屏触摸、外接鼠标无法拖动。
  - 现统一在 `makeExtLaunchOptionsForMode` 中为所有非分屏扩展桌面启动（含 `LauncherActivity` 及 `mode = 0 / 1 / 2` 应用）显式指定 `WINDOWING_MODE_FULLSCREEN (1)` 与空 `launchBounds`；`applyDisplayFullscreenPolicy` 始终保持 `VirtualDisplay` 容器默认模式为 `FULLSCREEN (1)`。
  - 修复 `enforceRunningExtTasksFullscreen` 通过 `configuration.windowConfiguration.getWindowingMode()` 精确识别窗口模式（原 `readIntField` 在 Android 11–16 恒回退为 `1`），对任何被系统偷偷切入 `FREEFORM (5)` 的扩展屏非分屏任务实时自动剥离 `alwaysOnTop`、恢复 `FULLSCREEN (1)` 并重同步 `SurfaceFlinger` 边界；Dock `⊞ 桌面` (`relaunchLauncher`) 同步接入全屏恢复闭环，并清理 `switchToTaskOnVd` 历史残留的 `mode == 3` 分支。
- **原生适配 `3440×1440` / `2560×1080` (21:9 带鱼屏) 与 EDID 最优物理模式智能识别**
  - 控制台快捷预设新增 **`3440×1440 (21:9 准4K)`** 与 **`2560×1080 (21:9 带鱼)`**。
  - 新增 `pickBestNativeEdidMode`（智能过滤 `3840×2160@30Hz` 等低刷 HDMI 兼容档位，优先挑选 `≥50Hz` 最大像素原生面板档位，如 `#128 3440×1440@60Hz`）：首次插入外接显示器时若 `3:2` 平板系统默认协商在 `2048×1536` / `2560×1600`，会自动尝试切换至显示器原生满幅宽屏档位。
  - 修复控制台 **「↺ 恢复推荐默认」** 硬编码 `2560×1440` 的问题，改为动态匹配当前外接显示器 EDID 最优原生分辨率并同步下发物理模式请求。

---

## v3.4.1 (`versionCode: 20`) — 2026-11-17

### ✨ 核心功能新增（v3.3.x → v3.4.1 功能汇总）
- **原生镜像模式全面重塑为「信息侧边栏 + 系统小组件工作台」**
  - **4 种画面与黑边排版形态**：支持关闭（`0`）、镜像右移 + 左侧合并宽侧边栏（`1`）、镜像左移 + 右侧合并宽侧边栏（`2`）与居中双翼对称侧边栏（`3`），支持按不同显示器分辨率与屏幕比例自动适配排版，避免黑边空间浪费。
  - **14 款可自由替换与排序的预设信息组件**：内置 `大数字时钟`、`电池温压详情`、`Wi-Fi 与信号`、`蓝牙与外设`、`音频通道与音量`（支持直接点按 `➖/🔇/➕`）、`媒体播放与切歌`（支持点按 `⏮/⏯/⏭`）、`日历与近期日程`、`内存与系统负载`、`实时网速监控`、`存储与亮度信息`、`专注番茄钟`、`快捷桌面便签`、`镜像快捷控制台` 与 `主屏最低亮度` 卡片，字体全面放大清晰易读。
  - **6 款主题配色 + 自定义颜色**：支持 `极夜深蓝`、`OLED纯黑`、`晨雾浅白`、`赛博紫青`、`翡翠墨绿`、`暖暮琥珀` 及自定义背景纯色，同步适配前景色高对比度。
  - **系统桌面小组件 (`AppWidget`) 接入与防拉伸保护**：支持左/右槽位绑定任意系统/第三方小组件，并提供 `自动原比例 / 方形 1:1 / 竖屏 3:4 / 宽屏 16:9 / 铺满槽位` 5 档高度比例锁定，解决窄栏中 AppWidget 异常拉伸变形问题。
- **外置鼠标外屏显示、自由跨屏 & 无键鼠「副屏直控」**
  - 修复原生镜像下外置鼠标仅显示在主屏而不在外接屏显示的问题，实现实体鼠标**从镜像区左右边缘无缝跨入黑边侧边栏**点按、滚轮与右键操作小组件，并修复边缘碰触时误弹出 ExtBridge 主界面的 Bug（通过边缘捕获边界保护及 `SideMouseCaptureActivity` 协同）。
  - 提供 **`0.9x 精细 / 1.4x 标准 / 1.9x 流畅 / 2.6x 极速 / 3.4x 飞快`** 五档副屏光标灵敏度与 DPI 密度自适应，兼顾低分与 4K 高分显示器手感。
  - 新增主屏 **`副屏直控` (`SideControlActivity`)** 并升级虚拟触控板直接点按小组件能力，无外置键鼠时在平板/手机屏即可手指触控操作镜像副屏所有组件与 AppWidget；同时保护平板主屏原有桌面布局不受破坏。
- **主屏一键黑屏 & 最低亮度省电双向闭环**
  - DeX 底部 Dock 快捷控制浮窗新增 **「🌙 一键黑屏 (平板最低亮度)」**，镜像侧边栏同步配备 **「🌙 主屏最低亮度」** 卡片：一键将平板/手机主屏背光压至硬件最低 `1` 并叠加防误触遮罩（支持 `NavAssistService` 无障碍层级兜底）。
  - 支持双击黑屏遮罩安全退出并精确还原进入前亮度，且在服务销毁、断开外接屏或停止退出时强制执行亮度恢复。

### 🛡️ 全量代码审查修复、性能与内存优化
- **Daemon (`ExtBridge.java`)**：
  - 为输入队列、光标追踪、分屏状态与亮度调节补充细粒度互斥锁（`sInputLock` / `sCursorLock` / `sSplitLock` / `sScreenDimLock`），并缓存 `SurfaceControl` / `Transaction` 反射句柄（统一由 `closeSurfaceTx` 释放）。
  - 修复特定条件下 `VirtualDisplay` 每 `1.5s` 循环重建（区分 `NATIVE_MIRROR_STOCK`、引入 `sSideVdAttempted` 保护、`physSizeChanged` 判断）；`startMirrorCursorPoller` 改为自适应退避轮询（`16ms → 50ms → 150ms`）与基于时间戳的边缘防抖。
  - 强化 `BridgeBinder` 调用方 UID ACL 鉴权 (`isTrustedCaller`)，限制 `runCmdOut` 256KB 输出与 3 秒超时；修复 Captured Mirror 鼠标右键/中键（`actionType 6/7`）与 `code 42` 重入捕获保护，并确保 `cleanupDisplay` 幂等恢复亮度。
- **Helper (`BridgeService.java` / 客户端 / 接收器 / 清单)**：
  - 将 `ActivityManager.MemoryInfo`、`TrafficStats`、`StatFs` 与 `SCREEN_BRIGHTNESS` 读取移出主线程 `refreshMirrorSideViews()`；`mPomoTickTask` 仅在计时运行期调度；图标 `LruCache` 使用 `cloneDrawable()`，预分配高频绘制视图中的 `RectF` / `Path`，`QuickTile` 复用 `GradientDrawable`。
  - 增加 `mDestroyed`、`mPollSeq` 序号守卫与 `safeBgExecute` / `safePost`；`detachExtOverlays(boolean keepBlackout)` 防止切层误撤黑屏；`BridgeProvider` 限制调用方 UID 白名单；`BridgeClient` 改用 `CopyOnWriteArraySet` 与 `linkToDeath` / `pingBinder()`；`MainActivity`、`TouchpadActivity` 与 `LauncherActivity` 在生命周期结束移除监听；`TouchpadActivity` 鼠标移动合并异步节流；`ControlReceiver` 使用 `goAsync()` 异步响应并在 `AndroidManifest.xml` 补齐 11 项 Action 声明。

### 📚 图文文档与发布构建
- 新增完整实机截图集 [`docs/images/`](docs/images/)、完整图文使用说明书 [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md) 与更新 [README.md](README.md)（主线发布说明见 `main` 分支 `docs/RELEASE_v3.4.1.md`）。

---

## v3.3.0 (`versionCode: 9`) — 2026-11-17

### ✨ 全新特性：DeX 自由分屏模式 & Dock 栏自动隐藏
- **DeX 独立桌面分屏模式（横屏左右 / 竖屏上下 + 自由调比例）**
  - 自动适配输出方向：当扩展屏为横屏（`virtW >= virtH`）时自动启用**左右分屏**；旋转 `90°/270°` 或竖屏分辨率（`virtH > virtW`）时自动切换为**上下分屏**，旋转屏幕即时重排两个分屏窗口。
  - **可拖拽悬浮分屏分割条 + 实时比例调整（`25% ~ 75%`）**：分屏接缝处出现高对比度磨砂分割手柄，按住拖拽可实时调节左右/上下窗口占比，松手自动动态调整双任务尺寸（无需重启应用）；轻点分割条或 Dock「◫ 分屏」可呼出快捷浮窗，支持 `3:7 / 4:6 / 5:5 / 6:4 / 7:3` 一键预设、`⇄/⇅ 互换位置` 与 `✕ 退出分屏`。
  - **多种分屏入口**：
    - Dock 栏新增 **`◫ 分屏`** 快捷按钮（一键将最近两个应用拼屏，或呼出控制浮窗）；
    - 扩展桌面应用图标 **长按 / 鼠标右键菜单** 新增 **`◫ 分屏：放到左屏 / 右屏`**（竖屏下自动显示为 **`放到上屏 / 下屏`**），放入单侧后继续轻点桌面任意第二个应用即可自动补全另一侧。
  - 后台 `ExtBridge` 在放入分屏时自动对任务执行 `setTaskResizeable` + `WINDOWING_MODE_FREEFORM` 转换与边界重排，解决部分应用默认 `fullscreen` 无法直接调整尺寸的问题。
- **Dock 栏自动隐藏与底边唤醒**
  - 默认开启「应用内 Dock 自动隐藏」（可在 Dock 快捷面板 **`📌 Dock自动隐藏`** 或手机控制台切换为「始终固定显示」）：
    - 在扩展桌面主页或快捷面板展开时 Dock 保持常驻可见；
    - 进入全屏/分屏应用且无操作闲置约 `2.6s` 后，Dock 自动平滑向下收起，仅在屏幕底端居中保留纤细的交互式触发指示条。
    - 将实体鼠标或触摸板光标移至外接屏底边中央、悬停或轻点底边触发条，Dock 立即平滑弹起唤醒；鼠标悬停在 Dock 栏区域内时自动延迟隐藏不误收。

---

## v3.2.3 (`versionCode: 8`) — 2026-11-17

### 🐛 关键问题修复（外接屏无法点击启动 / 输入注入静默失效）
- **绕过系统「后台弹出界面 (`MIUIOP 10021`)」对扩展屏桌面启动应用的静默拦截**
  - 在小米/HyperOS 等深度定制 ROM 上，应用处于 `VirtualDisplay` 时，从 Helper 进程自身调用 `startActivity()` 可能因缺少 `MIUIOP(10021)` 后台弹窗权限而被系统直接吞掉（无异常抛出，表现为点击图标有按下高亮动画却无任何软件打开）。
  - 现改为当 `LauncherActivity` 运行于扩展屏时，**优先通过 Shell 守护进程 (`BridgeClient.launchComponent` -> `ExtBridge.launchComponentOnVd`) 代理启动应用**，仅在守护未连接/返回失败时才回退本地 `startActivity()`；同时在 `ExtBridge` 启动、无线调试引导、`start_extbridge.sh` 与电脑 ADB 命令中自动授予 `appops set com.extbridge.helper 10021 allow`。
- **可感知化 `INJECT_EVENTS` 安全拦截状态 & 鼠标输入注入与光标层级修复**
  - 为 `ExtBridge` 统一封装 `invokeInjectInput()`，精确捕获 `SecurityException` 与输入注入返回值，并在状态接口 (`code=15`) 与鼠标注入返回 (`code=17`) 中透传 `sLastInjectStatus`（`1=正常` / `-1=INJECT_EVENTS 被拒绝`）。
  - 修正触摸板左/右键单击注入使用的未来时间戳序列为单调递增至当前时间（`now - 25` ~ `now`），防止部分设备 `InputDispatcher` 丢弃未来时间戳的 `ACTION_UP` 事件。
  - 修复 `BridgeService` 宿主模式下 `mDockRoot`（`10dp`）和快捷面板（`16dp`）海拔高于 `mCursorView`（`0dp`）导致 **鼠标准星被 Dock 栏挡在下层** 的层级 bug，统一提升鼠标指针至最高渲染层级（`elevation/translationZ = 48dp` + `bringToFront()`）。
  - 当系统未开启 MIUI/HyperOS「开发者选项 → USB 调试（安全设置）/ 允许模拟点击」导致触控板/鼠标准星只动画不生效时，控制台详情页与触控板顶部会立即弹出醒目红色提示与 Toast 指引开启。

- **首次启动全量权限初始化 & 进入开发者选项二次校验（含无障碍必需项）**
  - 将 **无障碍服务（`ExtBridge 平板辅助手势`）** 提升为与通知、悬浮窗并列的必需项；应用首次启动时自动顺序申请全部核心权限（Android 13+ 运行时 `POST_NOTIFICATIONS`、`SYSTEM_ALERT_WINDOW` 悬浮窗与无障碍服务），并提供 Android 13+「受限设置（灰色开关）」一键进应用详情解锁引导。
  - 若用户在首次启动时拒绝或跳过了任意权限（含无障碍/悬浮窗/通知），当其首次点击**「去开启无线调试 / 开发者选项」**时，会先拦截并要求补齐缺失权限；同时在 ADB/守护激活命令中自动授予 `ACCESS_RESTRICTED_SETTINGS` 解锁受限设置。

---

## v3.2.2 (`versionCode: 7`) — 2026-11-16

### 🎨 交互表达优化 & 项目定位明确
- **区分「桌面渲染分辨率」与「外接物理输出」，消除切换误解**
  - 控制台卡片明确标注上方预设与自定义宽高控制的是 **`VirtualDisplay` 真实渲染画布**（全机型 100% 生效：支持 `720p/900p` 降 GPU 负载稳帧、`4K` 超采样锐化、比例适配与竖屏旋转），状态栏同步区分显示 `虚拟渲染画布` 与 `外接物理输出 (系统/EDID协商)`。
  - 将 **目标刷新率预设与显示器硬件 EDID 模式列表** 统一收纳进默认折叠的 **「▸ 高级：外接物理信号与帧率尝试 (受设备固件限制)」** 面板，主界面只突出 **桌面渲染分辨率、四向旋转与缩放 DPI** 三项 100% 生效的核心设置；主应用按钮更名为 **「✓ 应用渲染分辨率与设置」**。
- **物理模式锁死诚实检测 (`sLastPhysResult = -3`)**
  - `ExtBridge` 在调用 `setUserPreferredDisplayMode` 后若检测到系统固件未实际切换物理档位（例如 Xiaomi HyperOS 4 下 `DisplayModeDirectorImpl` 限制 `displayId` 仅 0/1 且 `SurfaceFlinger` 拦截 `Attempt to set desired display modes for external display`），会返回明确状态 `-3`，控制台通过横幅与 Toast 告知用户“虚拟桌面已切换，当前系统固件锁定外接物理输出为 xx×yy@zzHz，已自动硬件缩放铺满”。
- **明确项目免 Root 定位与文档说明**
  - `README.md` 与激活弹窗补充说明：ExtBridge 专注 **免 Root / Shell 权限** 路线，不开发也不会维护用于改写系统框架或 `SurfaceFlinger` 的 Zygisk/LSPosed Root Hook 模块；已有的一键 Root 激活仅作省事注册与立即降权运行使用。

---

## v3.2.1 (`versionCode: 6`) — 2026-11-16

### 🐛 问题修复
- **修复部分 Android 13–16 设备物理外接分辨率与刷新率切换失效的问题**
  - 原 `DisplayManager.setUserPreferredDisplayMode(int, Display.Mode, boolean)` 三参数反射仅存在于 Android 17+，在 Android 15 / 16（如小米平板 8 Pro 等 ROM）上会静默 `NoSuchMethodException` 导致显示器始终停在默认 `1080P 60Hz`。
  - 现改为多签名级联探测并带 Shell 兜底：`DisplayManager`（3参/2参）→ `DisplayManagerGlobal`（2参/3参）→ `IDisplayManager`（2参/3参）→ `cmd display set-user-preferred-display-mode`。
  - 修复 `applyConfig()` 预设匹配逻辑（适配竖屏旋转宽高、刷新率无匹配挡位时不静默伪装成功，由控制台诚实提示 EDID 不支持该帧率或系统拒绝切换）；点击分辨率预设、自定义输入框或「对调宽高」时自动重置旧 EDID 物理模式 ID 锁定。
  - 修复 `tick()` 与异步切换时 `reassert()` 未重算缩放矩阵的问题：当 `sPhysW × sPhysH` 发生变化时自动重新执行 `applySurfaceTransform()` 与状态同步。
- **修复 App 划掉 / 强行停止后自动重启，并新增「停止服务并退出」功能**
  - 移除 `ExtBridge` 中 `sHelperProcToken.linkToDeath` 的 1.5 秒强行拉起死循环，`tick()` 仅在首次接入外接物理屏时同步唤醒 Helper；`notifyHelperSync()` 在 Helper 未存活时不再发 `STATE_SYNC` 广播触发唤醒；`BridgeService` 改为 `START_NOT_STICKY`。
  - 在控制台顶部操作区与待机通知栏新增 **「⏻ 停止服务并退出」**（调用 `BridgeClient.shutdownDaemon()` 触发 `code == 99` + `SHUTDOWN` 广播，先恢复外接屏并退出守护进程，再销毁前台通知与 App 任务），不再使用外接屏时可一键彻底关闭。

---

## v3.2.0 (`versionCode: 5`) — 2026-11-16

### ✨ 新增功能
- **四向输出画面旋转与竖屏桌面 (`0° / 90° / 180° / 270°`)**
  - 在 `ExtBridge` 图层合成管线（`applySurfaceTransform`）引入 `Matrix` + `RectF` 硬件旋转与居中矩阵变换，支持将物理横屏输出的 Type-C 显示器/便携屏竖放后点对点满屏竖屏显示，或倒置 `180°` 显示。
  - 切换 `90° / 270°` 时通过 `liveResizeOrRebuild()` 对 `VirtualDisplay` 与 `BLASTBufferQueue` 执行无感热重建与宽高对调，**不中断已打开的后台应用**；旋转角度持久化保存于 `/data/local/tmp/extbridge.conf`。
- **控制台与扩展屏快捷面板同步接入**
  - `MainActivity` 新增 **「输出桌面旋转方向」**（`0° 正常横屏` / `90° 顺时针竖屏` / `180° 倒置横屏` / `270° 逆时针竖屏`）与 **`⇅ 对调宽高`**（支持在横屏显示器上保持 `0°` 居中投射竖屏桌面）。
  - 扩展屏右下角快捷面板（`BridgeService`）新增 **`↻ 旋转`** 按钮，可直接在扩展屏侧循环切换输出方向。
- **竖屏排版与边界适配**
  - 放宽分辨率校验 `isValidDims()`，完整支持 `1440×2560`、`1080×1920`、`2160×3840` 等竖屏尺寸；`computeOverlayDpi()` 改为按短边限幅以优化竖屏 Dock 缩放；`calcFreeformRect()` 适配竖屏自由小窗宽高比例。

---

## v3.1.0 (`versionCode: 4`) — 2026-11-16

### ✨ 新增与改进
- **新增 Root 一键激活模式（免 ADB / 免开发者选项）**
  - 在激活弹窗新增 **「👑 Root 激活 (免 ADB 权限)」**，兼容 Magisk / KernelSU / APatch；支持勾选 **「启动软件时自动 Root 激活」**，重启设备打开 App 即可静默恢复后台守护进程。
  - `ExtBridge` 守护进程以 Root 拉起后自动通过 `Os.setgid(2000)` / `Os.setuid(2000)` 降权至 `shell` 身份运行，降低高权限驻留风险。
- **首次插屏跨分辨率自动适配**
  - 在用户尚未保存自定义配置（`/data/local/tmp/extbridge.conf` 不存在）时，自动按接入外接显示器的原生 `physW × physH @ physFps` 创建虚拟桌面，并按短边比例自动计算桌面 DPI（限幅 `160 ~ 320`）。
- **主屏手势隔离加固 & 工程化整理**
  - 在 `applyDisplayFullscreenPolicy()` 中通过反射补充 `IWindowManager.setShouldShowSystemDecors(vdId, false)`，加强对不同系统 ROM 主屏全面屏手势的兼容隔离。
  - `MainActivity` 标题改为动态读取安装包 `versionName`；补充 GitHub Actions 自动编译工作流 `.github/workflows/build.yml`。

---

## v3.0.0 (`versionCode: 3`)

### 🎉 核心基线功能
- 基于 `VirtualDisplay` + `SurfaceControl` 的真·双屏独立扩展桌面，支持一键切换原生镜像模式。
- 手机端内建 `arm64` `adb` 与 `extbridge.jar`，支持本机无线调试 mDNS 自动配对自激活（自动屏蔽 VPN 虚拟网卡干扰）。
- 独立桌面启动器（同步系统壁纸 + 高斯毛玻璃、中文/拼音首字母搜索）、底部 Dock 任务栏与 Win11 风格快捷控制中心。
- 手机端全高大触摸板与软键盘文字转发、硬件鼠标映射热切。
- 按应用粒度管理的四种显示兼容模式（跟随系统默认 / 强制横屏全屏 / 竖屏居中兼容 / 自由小窗模式），以及可选无障碍手势兜底服务。
