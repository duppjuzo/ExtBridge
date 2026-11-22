# ExtBridge `v3.5.5` (`versionCode: 28`) 正式版更新说明

## 1. 🐛 关键交互 Bug 修复

- **修复无线调试输入配对码（及弹窗内输入框）输入第 1 个数字后光标跳回数字前方的问题**（`ExtMiuixComponents.kt` / `MainSheets.kt` / `MainActivity.kt` / `TouchpadActivity.kt`）
  - **问题原因**：`Miuix 0.9.4` 的 `OverlayBottomSheet` / `OverlayDialog`（`MiuixPopupUtils.DialogLayout`）通过 `SideEffect` 将弹窗内容闭包同步至根节点 `MiuixPopupHost`，使弹窗外部传入的纯 `String` 状态存在 **1 帧同步滞后**；调用 `TextField(value: String)` 时，底层 `BasicTextField(String)` 在第 1 帧重组中拿到上一帧的空串 `""`，执行 `textFieldValueState.copy(text = "")` 将刚输入第 1 位数字后的光标位置 `TextRange(1, 1)` 强行截断归零为 `TextRange(0, 0)`，导致第 2 帧起光标跳至首字符前方（`|1`）。
  - **修复方案**：新增基于 `TextFieldValue` 重载封装的 `ExtTextField` 统一输入组件，在弹窗合成作用域内直接维护完整的 `TextFieldValue`（包含光标 `selection` 与中文输入法拼音 `composition` 预编辑区间）及待回显队列，彻底过滤跨层 `SideEffect` 的 1 帧旧值回传，同时完整保留 mDNS 端口自动填入、宽高对调、色板预设等外部赋值的光标末尾同步。
  - **覆盖范围**：无线调试激活页（**`6 位配对码`、`配对端口`、`连接端口`、`本机 IP 地址`**）、自定义渲染分辨率（宽高）、边栏快捷便签、自定义副屏背景色、系统小组件搜索、分应用规则搜索及虚拟触摸板远程输入框共 **11 处输入控件**全部同步升级。

---

## 2. ✨ `v3.5.5` 正式版功能汇总（转正自 `v3.5.5beta`）

- **新增「Shizuku / Sui 一键秒激活」第四并列通道（完整保留内置无线调试 + Root + 电脑 ADB）**（`ShizukuBootstrap.java` / `AdbBootstrap.java` / `MainSheets.kt`）
  - 激活抽屉现并列提供 **「无线调试 / Shizuku / Root / 电脑 ADB」** 四种激活方式：未安装 Shizuku 的用户仍可零第三方依赖使用内置 `libadb.so` 本机无线调试；已运行 Shizuku / Sui 的用户免输配对码一键拉起守护进程，并支持「启动软件时自动尝试 Shizuku 激活」静默恢复。
  - 采用内存资产标准输入流直写（`O_EXCL` 防符号链接 + 256 位 `SHA-256` 校验），零 `unzip` 依赖；自动兼容 Shizuku 的 ADB Shell（`uid=2000`）与 Root / Sui（`uid=0` 自动降权至 `u:r:shell:s0`）双身份。
- **官方仓库识别保护与签名验真声明**（`.github/workflows/build.yml` / `helper/build.gradle.kts` / `README.md`）
  - CI 自动发版严格锁定官方主仓 `duppjuzo/ExtBridge`，非官方 Fork 构建产物自动附加 `-unofficial-fork-<owner>` 标识后缀；文档与构建脚本同步公示官方 APK 签名证书 SHA-256 指纹（`e4f67b3012a16633006c64bfc9d5a95c7bdda10826bd729777c2a3066f49117c`），支持老版本无缝覆盖升级。
