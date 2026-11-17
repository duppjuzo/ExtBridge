# ExtBridge Lite (`v3.4.2-lite`) 分支发布说明

> 分支：`lite`（基于 `v3.4.2` 裁剪构建的高兼容纯净版）  
> 版本号：`3.4.2-lite` (`versionCode: 21`)

---

## 一、分支定位

`lite` 分支面向 **多品牌 Android 系统（OPPO/一加 ColorOS、vivo OriginOS、魅族 Flyme、类原生 AOSP、小米 HyperOS 等）** 提供最稳健、低耦合的免 Root 类 DeX 独立扩展桌面体验。

### ✅ 完整保留的核心能力
1. **DeX 独立扩展桌面 + `21:9` 带鱼屏（`3440×1440` / `2560×1080`）原生适配 + EDID 智能识别**
2. **DeX 自由分屏模式**（横屏左右 / 竖屏上下、`25% ~ 75%` 拖拽分割条、比例预设与互换）
3. **Dock 自动隐藏与 Win11 风格快捷控制面板**
4. **🌙 主屏一键黑屏最低亮度省电**（Dock 浮窗、主控制台、虚拟触摸板三处均可触发；双击主屏或退出/拔线自动还原亮度）
5. **纯净原生镜像一键切换**（`mirrorDisplay(0)` 居中投射 + 异常回退系统默认镜像，切换不黑屏）
6. **虚拟触摸板 + 软键盘输入转发 + 分应用兼容策略 + 小米工作台防悬浮小窗劫持保护**
7. **全量 `v3.4.1/v3.4.2` 并发锁、`Transaction` 显式释放、`goAsync()` 异步广播与 UID 鉴权加固**

### ✂️ 移除的高耦合模块（降低跨系统兼容风险与后台开销）
- 移除原生镜像下的左右融合信息侧边栏与第二虚拟屏 (`sSideVd`)；
- 移除 `IAppWidgetService` 隐藏反射授权与系统桌面小组件宿主 (`AppWidgetHost`)；
- 移除 `IInputManager.getCursorPositionInLogicalDisplay` 轮询、边缘悬浮感应条与 `SideMouseCaptureActivity` / `SideControlActivity`；
- 移除周期性 `dumpsys audio` / `dumpsys media_session` 文本解析轮询及小米专属 `com.miui.home` 强停清理逻辑。
- **体积与代码精简**：移除逾 4,300 行高耦合代码，`extbridge.jar` 缩减约 **21%**（`47 KB`），APK 缩减逾 **100 KB**。

### 🔧 新增跨品牌适配增强
- `IActivityTaskManager.startActivity` 同时兼容 Android 11（10 参数）与 Android 12–16（11 参数）签名，并在厂商魔改签名下自动回退 `am start --display` CLI 启动；
- `INJECT_EVENTS` 受阻时按设备品牌自动提示对应开关（小米「USB 调试（安全设置）」/ ColorOS「禁止权限监控」/ OriginOS「USB 模拟点击」）。
