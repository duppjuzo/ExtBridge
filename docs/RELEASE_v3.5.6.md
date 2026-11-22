# ExtBridge `v3.5.6` (`versionCode: 29`) 正式版更新说明

## 1. 🐛 关键交互 Bug 修复：DeX 模式下外置物理键盘中文打字外屏不显示备选词

- **修复 DeX 独立扩展桌面模式下，连接外置物理键盘输入中文时外接显示器不显示候选词栏（备选词）的问题**（`ExtBridge.java` / `BridgeService.java`）
  - **问题根因**：为保持手机/平板内屏（`Display 0`）全面屏返回与上滑手势不被系统副屏导航栏抢占，DeX 虚拟屏设置了 `setShouldShowSystemDecors(vdId, false)`；而在 Android 12～17 的 `WindowManagerService`（`DisplayWindowSettings`）中，关闭系统装饰栏且未显式指定输入法策略的虚拟屏默认采用 `DISPLAY_IME_POLICY_FALLBACK_DISPLAY (1)`，导致输入法窗口容器 `ImeContainer`（包含物理键盘的中文候选词条与悬浮备选窗）被强行挂载在主屏 `Display 0`，无法显示在外接显示器上。
  - **修复方案**：
    1. **开启扩展屏本地输入法策略 (`DISPLAY_IME_POLICY_LOCAL = 0`)**：在保持 `setShouldShowSystemDecors(vdId, false)` 不影响内屏手势的前提下，创建虚拟屏、切换分辨率/DPI 及同步外设路由时显式调用 `IWindowManager.setDisplayImePolicy(vdId, 0)`（含 `VirtualDisplayConfig.Builder` 预置及 Android 11 `setShouldShowIme(vdId, true)` 兜底），使物理键盘中文候选词条与软键盘直接原生显示在外接扩展屏。
    2. **跨屏顶层输入法焦点同步**：针对手机/平板单输入法会话（`config_perDisplayFocusEnabled=false`）机制，在外接键鼠接入/切至外屏、启动扩展屏应用、虚拟触摸板点击外屏输入框及开启主屏「一键黑屏」后自动同步 `focusDisplayForKey(sVdId)`，避免手指触碰内屏后输入法焦点滞留主屏。
    3. **DeX 底部 Dock 智能避让候选栏**：新增扩展屏 `WindowInsets.Type.ime()` 实时监听，当外屏底部弹出中文候选栏或软键盘时，高层级 `TYPE_APPLICATION_OVERLAY` 底部 Dock 自动平滑收起让位（含「Dock 固定显示」状态），防止遮挡底部备选词，结束输入后自动恢复。

---

## 2. ⚠️ 老版本升级提示

由于本次修复涉及后台守护进程 `extbridge.jar`，覆盖安装 `ExtBridge_v3.5.6.apk` 后，若此前旧版守护进程仍在后台运行，请在「设置」页点击一次 **「停止后台服务并退出 ExtBridge」** 后重新打开激活（或重新执行一次无线调试直连 / Shizuku / Root / 电脑 ADB 激活），使新版守护进程生效。
