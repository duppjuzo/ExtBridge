package com.extbridge.helper

import android.app.ActivityOptions
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.extbridge.helper.ui.*
import com.extbridge.helper.ui.components.FloatingBottomBar
import com.extbridge.helper.ui.components.FloatingBottomBarItem
import com.extbridge.helper.ui.components.FloatingBottomBarFallback
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.ScreenMirroring
import top.yukonga.miuix.kmp.icon.extended.Settings as MiuixSettingsIcon
import top.yukonga.miuix.kmp.nav.core.NavController
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.springAnimateToPage
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val appVersion by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }.getOrDefault("")
    }
    private val appVersionCode by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).longVersionCode }.getOrDefault(0L)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var resumed = false
    private var refreshing = false
    private var status by mutableStateOf(BridgeClient.Status())
    private var daemonStatusChecked by mutableStateOf(false)

    // Binder 交接宽限期：daemon 从不注册 ServiceManager，helper 只能等 NEED_BINDER 广播后由
    // daemon 异步回推 Binder。冷启动或后台被杀后重开时，首轮 getStatus() 往往先于回推到达，
    // 此时绝不能立即判定“未激活”，否则首页会从“工作中”闪回“未激活”再跳回。
    @Volatile private var binderHandoffDeadline = 0L
    // 单次探测/状态事务抖动（Binder 仍在但一次应答异常）先复查确认再翻转显示。
    private var transientBlipCount = 0
    private var config by mutableStateOf(DisplayConfig())
    private var configDirty = false
    private var configRevision = 0L
    private var themeMode by mutableIntStateOf(0)
    private var miuixBlur by mutableStateOf(true)
    private var appleBottomBar by mutableStateOf(false)
    private var liquidGlass by mutableStateOf(true)
    private var tab by mutableIntStateOf(0)
    private var aboutOpen by mutableStateOf(false)
    private var dimmed by mutableStateOf(false)
    private var autoHide by mutableStateOf(true)
    private var notice by mutableStateOf("")
    private var actionWorking by mutableStateOf(false)
    private var notif by mutableStateOf(false)
    private var overlay by mutableStateOf(false)
    private var accessibility by mutableStateOf(false)
    private var showPermissions by mutableStateOf(false)
    private var devGate = false
    private var showDevGuide by mutableStateOf(false)
    private var showActivation by mutableStateOf(false)
    private val activationConnection = ActivationConnectionTracker()
    private var showDiagnostics by mutableStateOf(false)
    private var showCustom by mutableStateOf(false)
    private var showEdid by mutableStateOf(false)
    private var showStop by mutableStateOf(false)
    private var skipActivation = false
    private var autoRootTried = false
    private var startupChecked = false
    private var activationTab by mutableIntStateOf(1)
    private var autoRoot by mutableStateOf(false)
    private var working by mutableStateOf(false)
    private var activationEpoch = 0L
    private var activationTicket: ActivationSession.Ticket? = null
    private var pendingActivationResult: ActivationFlow.Result? = null
    private var pendingActivationEpoch = 0L
    private var activationMessage by mutableStateOf("")
    private var mdns by mutableStateOf("")
    private var host by mutableStateOf("")
    private var pairPort by mutableStateOf("")
    private var pairCode by mutableStateOf("")
    private var connectPort by mutableStateOf("")
    private var hostEdited = false
    private var pairEdited = false
    private var connectEdited = false
    private var rules by mutableStateOf<List<AppRule>>(emptyList())
    private var rulesLoading by mutableStateOf(false)
    private var rulesLoaded = false
    private var rulesError by mutableStateOf("")
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissions()
        if (notif && devGate) sendPairNotification()
    }

    private data class AppRule(val pkg: String, val label: String, val mode: Int)
    private val onboarding get() = getSharedPreferences("extbridge_onboarding", MODE_PRIVATE)
    private val readyCallback = Runnable { handler.post { refresh() } }
    private val binderRePoke: Runnable = Runnable {
        if (resumed && BridgeClient.getService() == null &&
            android.os.SystemClock.elapsedRealtime() < binderHandoffDeadline
        ) {
            // 首轮回推可能因广播/Provider 冷启动甚至广播被系统丢弃而错过，
            // 宽限期内周期性再催 daemon 推送 Binder，直到送达或窗口结束。
            BridgeClient.requestBinder(this)
            handler.postDelayed(binderRePoke, BINDER_REPOKE_DELAY_MS)
        }
    }
    private val refreshLoop = object : Runnable {
        override fun run() {
            if (!resumed) return
            refresh()
            handler.postDelayed(
                this,
                when {
                    status.connected -> 2000L
                    !daemonStatusChecked -> 350L
                    else -> 1200L
                },
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ensurePrimaryDisplay(this, MainActivity::class.java)) return
        enableEdgeToEdge()
        AdbBootstrap.initialize(this)
        themeMode = ExtThemePrefs.get(this)
        miuixBlur = ExtThemePrefs.isMiuixBlurEnabled(this)
        appleBottomBar = ExtThemePrefs.isAppleBottomBarEnabled(this)
        liquidGlass = ExtThemePrefs.isLiquidGlassEnabled(this)
        tab = savedInstanceState?.getInt("tab")?.coerceIn(0, 3) ?: 0
        aboutOpen = savedInstanceState?.getBoolean("aboutOpen") ?: false
        showActivation = savedInstanceState?.getBoolean("showActivation") ?: false
        skipActivation = savedInstanceState?.getBoolean("skipActivation") ?: false
        activationTab = savedInstanceState?.getInt("activationTab")?.coerceIn(0, 2) ?: 1
        activationMessage = savedInstanceState?.getString("activationMessage").orEmpty()
        mdns = savedInstanceState?.getString("mdns").orEmpty()
        host = savedInstanceState?.getString("host").orEmpty()
        pairPort = savedInstanceState?.getString("pairPort").orEmpty()
        pairCode = savedInstanceState?.getString("pairCode").orEmpty()
        connectPort = savedInstanceState?.getString("connectPort").orEmpty()
        hostEdited = savedInstanceState?.getBoolean("hostEdited") ?: false
        pairEdited = savedInstanceState?.getBoolean("pairEdited") ?: false
        connectEdited = savedInstanceState?.getBoolean("connectEdited") ?: false
        if (savedInstanceState?.getBoolean("activationWorking") == true) {
            activationMessage = "界面已重建，上次激活已取消，请重新操作"
        }
        if (showActivation) activationConnection.open()
        BridgeService.sUserStopping = false
        BridgeService.ensureStarted(this)
        setContent {
            ExtHyperTheme(themeMode) {
                val dark = isEffectiveDark(themeMode)
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                AppShell()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab)
        outState.putBoolean("aboutOpen", aboutOpen)
        outState.putBoolean("showActivation", showActivation)
        outState.putBoolean("skipActivation", skipActivation)
        outState.putInt("activationTab", activationTab)
        outState.putString("activationMessage", activationMessage)
        outState.putString("mdns", mdns)
        outState.putString("host", host)
        outState.putString("pairPort", pairPort)
        outState.putString("pairCode", pairCode)
        outState.putString("connectPort", connectPort)
        outState.putBoolean("hostEdited", hostEdited)
        outState.putBoolean("pairEdited", pairEdited)
        outState.putBoolean("connectEdited", connectEdited)
        outState.putBoolean("activationWorking", working)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // 进程存活且 Binder 仍在缓存：无需重置为“未检查”，避免简单的前后台切换也闪一下。
        // 进程被回收后重开（Binder 缓存为空）：daemon 只能靠 NEED_BINDER 广播异步回推 Binder，
        // 首轮 getStatus() 必然早于回推到达。开启宽限期，期间保持“正在检查后台服务”，
        // 不得显示“未激活”、不得自动弹激活页——这正是“已激活却闪回未激活”的根因。
        val binderCached = BridgeClient.getService() != null
        daemonStatusChecked = binderCached && status.connected
        binderHandoffDeadline = if (daemonStatusChecked) 0L
            else android.os.SystemClock.elapsedRealtime() + BINDER_HANDOFF_GRACE_MS
        handler.removeCallbacks(binderRePoke)
        if (!binderCached) handler.postDelayed(binderRePoke, BINDER_REPOKE_DELAY_MS)
        themeMode = ExtThemePrefs.get(this)
        miuixBlur = ExtThemePrefs.isMiuixBlurEnabled(this)
        appleBottomBar = ExtThemePrefs.isAppleBottomBarEnabled(this)
        liquidGlass = ExtThemePrefs.isLiquidGlassEnabled(this)
        BridgeClient.setOnBinderReady(readyCallback)
        BridgeClient.requestBinder(this)
        BridgeService.sUserStopping = false
        BridgeService.ensureStarted(this)
        handler.removeCallbacks(refreshLoop)
        handler.post(refreshLoop)
    }

    override fun onPause() {
        resumed = false
        transientBlipCount = 0
        handler.removeCallbacks(refreshLoop)
        handler.removeCallbacks(binderRePoke)
        BridgeClient.removeOnBinderReady(readyCallback)
        super.onPause()
    }

    override fun onDestroy() {
        ++activationEpoch
        AdbBootstrap.cancelActivation(activationTicket)
        BridgeClient.removeOnBinderReady(readyCallback)
        handler.removeCallbacksAndMessages(null)
        // Preserve notification pairing if it is already running inside AdbBootstrap.
        if (!working) AdbBootstrap.stopDiscovery()
        io.shutdown()
        super.onDestroy()
    }

    private fun refreshPermissions() {
        notif = (Build.VERSION.SDK_INT < 33 || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) &&
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
        overlay = Settings.canDrawOverlays(this)
        accessibility = NavAssistService.isRunning() || runCatching {
            val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            enabled.split(':').any { it == "$packageName/.NavAssistService" || it == "$packageName/${NavAssistService::class.java.name}" }
        }.getOrDefault(false)
        autoHide = BridgeService.isDockAutoHideEnabled(this)
        autoRoot = AdbBootstrap.isAutoRootEnabled(this)
    }

    private fun refresh() {
        if (isDestroyed || isFinishing) return
        refreshPermissions()
        if (refreshing || io.isShutdown) return
        refreshing = true
        io.execute {
            val next = BridgeClient.getStatus()
            val sys = if (next.connected) BridgeClient.getSysStatus() else BridgeClient.SysStatus()
            handler.post {
                refreshing = false
                if (isDestroyed || isFinishing) return@post
                val reconnected = !status.connected && next.connected
                if (next.connected && (!configDirty || reconnected)) {
                    val refreshed = DisplayConfig(next.virtW, next.virtH, next.virtDpi, next.virtFps, next.virtRot)
                    if (refreshed != config) ++configRevision
                    config = refreshed
                    configDirty = false
                }
                // Binder 尚未送达（connectionIssue 为空即 getService()==null）且宽限期未结束：
                // 这是回推竞态，不是断线/未激活。保持“正在检查”，不显示“未激活”，也不弹激活页。
                val handoffPending = !next.connected && next.connectionIssue.isBlank() &&
                    android.os.SystemClock.elapsedRealtime() < binderHandoffDeadline
                // Binder 仍在、守护进程存活，但单次状态事务异常：保持上一次显示并立即复查，
                // 连续确认失败才翻转，避免运行中偶发 DeadObjectException 造成“工作中”闪断。
                val blipPending = !handoffPending && status.connected && !next.connected &&
                    next.connectionIssue.isNotBlank() && BridgeClient.getService() != null &&
                    transientBlipCount < 2
                if (!next.connected && status.connected && !blipPending) {
                    rulesLoaded = false
                    rulesError = ""
                }
                if (blipPending) {
                    ++transientBlipCount
                    handler.postDelayed({ refresh() }, 250L)
                } else {
                    transientBlipCount = 0
                    status = next
                }
                // 宽限期内保持“正在检查”；blip 保持期则维持上一次已验证的显示（daemonStatusChecked
                // 仍为 true，status 仍为旧的 connected 值），绿色“工作中”卡片不闪断。
                daemonStatusChecked = !handoffPending
                if (!blipPending) dimmed = sys.valid && sys.screenDimmed
                val pending = pendingActivationResult
                if (pending != null && !handoffPending && !blipPending) {
                    pendingActivationResult = null
                    if (pendingActivationEpoch == activationEpoch) {
                        working = false; activationTicket = null
                        val probe = ActivationFlow.Probe(next.connected, next.handshakeVerified, next.protocolCompatible,
                            next.daemonPid, next.daemonUid, next.activationToken, next.connectionIssue)
                        if (ActivationFlow.owns(probe, pending.token, pending.pid) && activationConnection.complete(pending.token, true)) {
                            showActivation = false; AdbBootstrap.cancelPairingNotification(this)
                            AdbBootstrap.stopDiscovery(); if (resumed) toast("本次激活成功")
                        } else if (!ActivationFlow.owns(probe, pending.token, pending.pid)) activationMessage = "服务状态已改变，请重新检查"
                    }
                }
                if (activationTicket?.isCancelled == true && working) {
                    ++activationEpoch; working = false; activationTicket = null; activationMessage = "激活已取消"
                }
                if (resumed && !startupChecked) {
                    startupChecked = true
                    if (!onboarding.getBoolean("startup_perms_done", false) && !(notif && overlay && accessibility)) {
                        showPermissions = true
                    }
                }
                if (resumed && !next.connected && !handoffPending && !blipPending && !showPermissions && !skipActivation && !showActivation) {
                    if (!autoRootTried && AdbBootstrap.isAutoRootEnabled(this)) {
                        autoRootTried = true
                        working = true
                        activationTicket = AdbBootstrap.autoRootBootstrapAsync(this, progress())
                    } else if (!working) openActivation()
                }
                if (tab == 2 && next.connected && !rulesLoaded && !rulesLoading) loadRules()
            }
        }
    }

    private fun edit(next: DisplayConfig) {
        config = next
        configDirty = true
        ++configRevision
    }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun action(block: () -> String) {
        if (actionWorking || io.isShutdown) return
        actionWorking = true
        io.execute {
            val result = runCatching(block).getOrElse { "操作失败：${it.message ?: it.javaClass.simpleName}" }
            handler.post {
                actionWorking = false
                if (!isDestroyed && !isFinishing) { toast(result); BridgeService.refreshFromReceiver(this); refresh() }
            }
        }
    }

    private fun applyConfig() {
        val chosen = config
        val revision = configRevision
        if (!chosen.valid()) { toast("参数无效：分辨率、DPI 或刷新率超出范围"); return }
        action {
            val result = BridgeClient.applyConfigDetailed(chosen.w, chosen.h, chosen.dpi, chosen.fps, chosen.physicalModeId, chosen.rotation)
            val text = when {
                !result.ok -> "应用失败，请先激活后台服务"
                result.physResult in -3..-1 -> "桌面设置已应用；外接显示保持 ${result.physW}×${result.physH} @ ${result.physFps.roundToInt()}Hz"
                else -> "已应用 ${chosen.w}×${chosen.h} · ${chosen.dpi}dpi · ${chosen.fps.roundToInt()}Hz"
            }
            handler.post {
                if (!isDestroyed && configRevision == revision) {
                    notice = text
                    if (result.ok) configDirty = false
                }
            }
            text
        }
    }

    private fun switchMode(mode: Int) = action {
        val result = BridgeClient.setMirrorMode(mode)
        if (result < 0) "切换失败，请先激活服务" else "已切换至 ${BridgeClient.mirrorModeLabel(result)}"
    }

    private fun loadRules() {
        if (rulesLoading || io.isShutdown) return
        rulesLoading = true
        rulesError = ""
        io.execute {
            val result = runCatching {
                val modes = BridgeClient.getAllAppModes()
                packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .filter { it.activityInfo != null && it.activityInfo.packageName != packageName }
                    .distinctBy { it.activityInfo.packageName }
                    .map { AppRule(it.activityInfo.packageName, it.loadLabel(packageManager).toString(), modes[it.activityInfo.packageName] ?: 0) }
                    .sortedWith(compareBy<AppRule> { it.mode == 0 }.thenBy { it.label.lowercase(Locale.ROOT) })
            }
            handler.post {
                if (isDestroyed) return@post
                rulesLoading = false
                result.onSuccess { rules = it; rulesLoaded = true }
                    .onFailure {
                        rulesError = it.message?.takeIf(String::isNotBlank) ?: "未知错误"
                        rulesLoaded = true // Stop the polling loop; offer an explicit retry instead.
                    }
            }
        }
    }

    @Composable
    private fun AppShell() {
        val backStack = remember {
            if (aboutOpen) navBackStackOf(AboutRoute) else navBackStackOf(MainRoute)
        }
        val navigator = remember(backStack) { NavController(backStack) }
        val showingAbout = backStack.lastOrNull() == AboutRoute
        val pagerState = rememberPagerState(initialPage = tab.coerceIn(0, 3)) { TAB_COUNT }
        val scope = rememberCoroutineScope()
        var navigating by remember { mutableStateOf(false) }
        var selectedTab by remember { mutableIntStateOf(tab.coerceIn(0, 3)) }

        // Keep the bar selection in sync with real swipes, but do not fight programmatic navigation.
        LaunchedEffect(pagerState.currentPage) {
            if (!navigating) selectedTab = pagerState.currentPage
        }
        SideEffect {
            tab = selectedTab
            aboutOpen = showingAbout
        }

        val selectTab: (Int) -> Unit = { index ->
            if (index != selectedTab) {
                selectedTab = index
                navigating = true
                scope.launch {
                    try {
                        pagerState.springAnimateToPage(index)
                    } finally {
                        navigating = false
                        if (pagerState.currentPage != index) selectedTab = pagerState.currentPage
                    }
                }
            }
        }
        val settingsScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
        val currentTab = TAB_ROUTES[selectedTab.coerceIn(0, TAB_COUNT - 1)]

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 760.dp
            Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { outerPadding ->
                Box(Modifier.fillMaxSize().padding(outerPadding)) {
                    NavDisplay(
                        navController = navigator,
                        modifier = Modifier.fillMaxSize(),
                        onBack = {
                            if (showingAbout) navigator.pop()
                            else if (selectedTab != 0) selectTab(0)
                            else finish()
                        },
                        transition = NavTransitions.MiuixDefault,
                        effects = NavDisplayEffects(
                            cornerClipRadius = if (wide) 0.dp else rememberNavSystemCornerRadius(),
                            backdropColor = MiuixTheme.colorScheme.surface,
                        ),
                    ) {
                        entry<MainRoute>(transition = NavTransitions.None) {
                            MainDestination(
                                tab = currentTab,
                                pagerState = pagerState,
                                selectedTab = selectedTab,
                                wide = wide,
                                backdrop = backdropFor(miuixBlur),
                                settingsScrollState = settingsScrollState,
                                onTabSelected = selectTab,
                                onOpenAbout = { if (!showingAbout) navigator.push(AboutRoute) },
                            )
                        }
                        entry<AboutRoute>(transition = NavTransitions.MiuixDefault) {
                            AboutPage(wide = wide, onBack = { navigator.pop() })
                        }
                    }
                    Overlays()
                }
            }
        }
        LaunchedEffect(currentTab, status.connected) {
            if (currentTab == AppRoute.Rules && status.connected && !rulesLoaded && !rulesLoading) loadRules()
        }
    }

    @Composable
    private fun backdropFor(enabled: Boolean): LayerBackdrop? =
        if (enabled) rememberAppBackdropOrNull() else null

    @Composable
    private fun MainDestination(
        tab: AppRoute,
        pagerState: androidx.compose.foundation.pager.PagerState,
        selectedTab: Int,
        wide: Boolean,
        backdrop: LayerBackdrop?,
        settingsScrollState: ScrollState,
        onTabSelected: (Int) -> Unit,
        onOpenAbout: () -> Unit,
    ) {
        val labels = listOf("控制台", "显示", "应用规则", "设置")
        val icons = listOf(
            MiuixIcons.Regular.Home,
            MiuixIcons.Regular.ScreenMirroring,
            MiuixIcons.Regular.ListView,
            MiuixIcons.Regular.MiuixSettingsIcon,
        )
        val scroll = MiuixScrollBehavior()
        val pageTitle = labels[tab.tabIndex]
        Scaffold(
            topBar = {
                if (!wide) TopAppBar(
                    title = pageTitle,
                    modifier = appTopBarBlur(backdrop),
                    color = Color.Transparent,
                    scrollBehavior = scroll,
                    actions = { IconButton(onClick = { showDiagnostics = true }) { Icon(MiuixIcons.Regular.Info, "连接状态") } },
                )
            },
            bottomBar = {
                if (!wide) {
                    if (appleBottomBar) {
                        val items: @Composable RowScope.() -> Unit = {
                            labels.forEachIndexed { index, label ->
                                FloatingBottomBarItem(
                                    onClick = { onTabSelected(index) },
                                    modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                                ) {
                                    Icon(
                                        imageVector = icons[index],
                                        contentDescription = label,
                                        tint = if (liquidGlass && selectedTab == index) {
                                            MiuixTheme.colorScheme.primary
                                        } else {
                                            MiuixTheme.colorScheme.onSurface
                                        },
                                    )
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        color = if (liquidGlass && selectedTab == index) {
                                            MiuixTheme.colorScheme.primary
                                        } else {
                                            MiuixTheme.colorScheme.onSurface
                                        },
                                    )
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    bottom = 12.dp + WindowInsets.navigationBars
                                        .asPaddingValues()
                                        .calculateBottomPadding(),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (backdrop != null) {
                                FloatingBottomBar(
                                    selectedIndex = { selectedTab },
                                    onSelected = onTabSelected,
                                    backdrop = backdrop,
                                    tabsCount = labels.size,
                                    isBlurEnabled = liquidGlass,
                                    content = items,
                                )
                            } else {
                                FloatingBottomBarFallback(
                                    selectedIndex = { selectedTab },
                                    onSelected = onTabSelected,
                                    tabsCount = labels.size,
                                    content = items,
                                )
                            }
                        }
                    } else NavigationBar(
                        modifier = appBottomBarBlur(backdrop),
                        color = Color.Transparent,
                    ) {
                        labels.forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = { onTabSelected(index) },
                                icon = icons[index],
                                label = label,
                            )
                        }
                    }
                }
            },
        ) { padding ->
            val contentPadding = if (wide) {
                // The rail owns the start inset and the manually placed app bar owns the top.
                // Keep the remaining system insets instead of dropping them on wide screens.
                PaddingValues(
                    start = 0.dp,
                    top = 0.dp,
                    end = padding.calculateEndPadding(LocalLayoutDirection.current),
                    bottom = padding.calculateBottomPadding(),
                )
            } else padding
            Row(Modifier.fillMaxSize()) {
                if (wide) NavigationRail(
                    modifier = appNavigationRailBlur(backdrop),
                    color = Color.Transparent,
                    header = { Icon(MiuixIcons.Regular.ScreenMirroring, "控制台", modifier = Modifier.padding(16.dp)) },
                ) {
                    labels.forEachIndexed { index, label ->
                        NavigationRailItem(
                            selected = selectedTab == index,
                            onClick = { onTabSelected(index) },
                            icon = icons[index],
                            label = label,
                        )
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (wide) SmallTopAppBar(
                        title = pageTitle,
                        modifier = appTopBarBlur(backdrop),
                        color = Color.Transparent,
                    )
                    Box(
                        Modifier.weight(1f).fillMaxWidth()
                            .then(Modifier.nestedScroll(scroll.nestedScrollConnection))
                            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.Top,
                        ) { page ->
                            when (page) {
                                0 -> Dashboard(wide, contentPadding)
                                1 -> DisplayPage(contentPadding)
                                2 -> RulesPage(contentPadding)
                                else -> SettingsPage(contentPadding, settingsScrollState, onOpenAbout, wide)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Page(
        contentPadding: PaddingValues,
        scrollState: ScrollState = rememberScrollState(),
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            Column(
                Modifier.padding(contentPadding)
                    .padding(horizontal = 20.dp)
                    .padding(top = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                content = content,
            )
        }
    }

    @Composable
    private fun Dashboard(wide: Boolean, contentPadding: PaddingValues) {
        if (wide) {
            Row(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Column(Modifier.weight(0.95f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ConnectionCard()
                    PermissionBanner()
                    ModeSelector()
                    DockControls()
                }
                Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    QuickTiles()
                }
            }
        } else Page(contentPadding) {
            ConnectionCard()
            PermissionBanner()
            ModeSelector()
            QuickTiles()
            DockControls()
        }
    }

    @Composable
    private fun ConnectionCard() {
        val working = daemonStatusChecked && status.connected && status.connectionIssue.isBlank()
        val dark = isEffectiveDark(themeMode)
        val detail = when {
            status.connectionIssue.isNotBlank() -> status.connectionIssue
            status.extId > 0 -> "物理输出 ${status.physW}×${status.physH} · ${status.physFps.roundToInt()}Hz"
            else -> null
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(0.dp),
            colors = CardDefaults.defaultColors(
                color = when {
                    working && dark -> Color(0xFF173B27)
                    working -> Color(0xFFE4F7E9)
                    else -> MiuixTheme.colorScheme.surfaceContainer
                },
                contentColor = if (working) {
                    if (dark) Color.White else Color(0xFF1B1B1B)
                } else MiuixTheme.colorScheme.onSurfaceContainer,
            ),
            onClick = { showDiagnostics = true },
        ) {
            if (working) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 188.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(horizontal = 28.dp)
                            .fillMaxWidth(0.68f),
                    ) {
                        Text(
                            "工作中",
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (dark) Color.White else Color(0xFF1B1B1B),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            when {
                                status.extId <= 0 -> "等待外接显示器"
                                status.mirrorMode == 1 -> "原生镜像"
                                else -> "DeX 扩展桌面"
                            },
                            fontSize = 16.sp,
                            color = if (dark) Color.White.copy(alpha = 0.72f) else Color(0xFF6D7B71),
                        )
                        if (detail != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                detail,
                                fontSize = 13.sp,
                                color = if (dark) Color.White.copy(alpha = 0.60f) else Color(0xFF7E8B82),
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .offset(x = 18.dp)
                            .size(104.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            progress = 1f,
                            size = 104.dp,
                            strokeWidth = 11.dp,
                            colors = ProgressIndicatorDefaults.progressIndicatorColors(
                                foregroundColor = HyperGreen,
                                backgroundColor = HyperGreen,
                            ),
                        )
                        Icon(
                            imageVector = MiuixIcons.Basic.Check,
                            contentDescription = null,
                            modifier = Modifier.size(52.dp),
                            tint = HyperGreen,
                        )
                    }
                }
            } else {
                BasicComponent(
                    title = when {
                        !daemonStatusChecked -> "正在检查后台服务"
                        status.connectionIssue.isNotBlank() -> "服务状态无法确认"
                        else -> "后台服务未激活"
                    },
                    titleColor = if (daemonStatusChecked) {
                        BasicComponentDefaults.titleColor(color = extWarningTextColor())
                    } else BasicComponentDefaults.titleColor(),
                    summary = detail,
                    insideMargin = PaddingValues(20.dp),
                    bottomAction = if (daemonStatusChecked && !status.connected) {
                        {
                            TextButton(
                                "激活后台服务",
                                onClick = { openActivation() },
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    } else null,
                )
            }
        }
    }

    @Composable
    private fun PermissionBanner() {
        val missing = buildList {
            if (!notif) add("通知")
            if (!overlay) add("悬浮窗")
            if (!accessibility) add("无障碍服务")
        }
        AnimatedVisibility(
            visible = missing.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Card {
                ArrowPreference(
                    title = "运行权限未就绪",
                    summary = "未授权：${missing.joinToString("、")}",
                    onClick = { devGate = false; showPermissions = true },
                )
            }
        }
    }

    @Composable
    private fun ModeSelector() {
        SmallTitle("输出模式")
        Card {
            RadioButtonPreference(
                title = "DeX 扩展桌面",
                summary = "独立桌面与多窗口",
                selected = status.mirrorMode == 0,
                onClick = { if (status.mirrorMode != 0) switchMode(0) },
                enabled = status.connected && !actionWorking,
                radioButtonLocation = RadioButtonLocation.End,
            )
            RadioButtonPreference(
                title = "原生镜像",
                summary = "同步显示本机画面",
                selected = status.mirrorMode == 1,
                onClick = { if (status.mirrorMode != 1) switchMode(1) },
                enabled = status.connected && !actionWorking,
                radioButtonLocation = RadioButtonLocation.End,
            )
        }
    }

    @Composable
    private fun QuickTiles() {
        SmallTitle("快捷控制")
        Card {
            BasicComponent(
                title = if (status.inputToExt == 1) "键鼠切换到本机" else "键鼠切换到扩展屏",
                summary = if (status.inputToExt == 1) "当前：扩展屏" else "当前：本机",
                enabled = status.connected && !actionWorking,
                onClick = {
                    action { if (BridgeClient.toggleInput() < 0) "键鼠切换失败" else "键鼠方向已切换" }
                },
            )
            ArrowPreference(
                title = "大触摸板",
                enabled = !actionWorking,
                onClick = {
                    startActivity(Intent(this@MainActivity, TouchpadActivity::class.java), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
                },
            )
            BasicComponent(
                title = if (dimmed) "恢复本机亮度" else "降低本机亮度",
                summary = "不影响外接画面",
                enabled = status.connected && !actionWorking,
                onClick = {
                    BridgeService.toggleBlackoutFromApp(this@MainActivity)
                    handler.postDelayed({ refresh() }, 350)
                },
            )
            ArrowPreference(
                title = "打开扩展桌面",
                enabled = status.connected && status.mirrorMode == 0 && !actionWorking,
                onClick = { action { BridgeClient.relaunchLauncher(); "正在打开扩展桌面" } },
            )
        }
    }

    @Composable
    private fun DockControls() {
        Card {
            SwitchPreference(title = "Dock 自动隐藏", summary = "应用运行时收起，鼠标移到底边显示", checked = autoHide,
                onCheckedChange = { autoHide = it; BridgeService.setDockAutoHideEnabled(this@MainActivity, it) })
            ArrowPreference(title = "连接状态", onClick = { showDiagnostics = true })
        }
    }

    @Composable
    private fun DisplayPage(contentPadding: PaddingValues) {
        val presets = listOf(3840 to 2160, 3440 to 1440, 2560 to 1600, 2560 to 1440, 2560 to 1080,
            1920 to 1200, 1920 to 1080, 1600 to 900, 1280 to 720)
        Page(contentPadding) {
            SmallTitle("DeX 显示设置")
            Card {
                OverlayDropdownPreference(title = "桌面分辨率", summary = "${config.w}×${config.h}",
                    items = presets.map { "${it.first} × ${it.second}" } + "自定义…",
                    selectedIndex = presets.indexOfFirst { maxOf(config.w, config.h) == it.first && minOf(config.w, config.h) == it.second }.let { if (it < 0) 9 else it },
                    onSelectedIndexChange = { if (it == 9) showCustom = true else edit(config.resolution(presets[it].first, presets[it].second)) })
                OverlayDropdownPreference(title = "输出旋转方向", items = listOf("0° 正常", "90° 顺时针", "180° 倒置", "270° 逆时针"),
                    selectedIndex = config.rotation.coerceIn(0, 3), onSelectedIndexChange = { edit(config.rotated(it)); if (status.connected) applyConfig() })
                SliderPreference(title = "界面缩放（DPI）",
                    value = config.dpi.toFloat(), valueRange = 80f..640f, valueText = "${config.dpi} dpi",
                    showKeyPoints = true, keyPoints = listOf(120f, 160f, 200f, 240f, 280f, 320f),
                    onValueChange = { edit(config.copy(dpi = ((it / 4).roundToInt() * 4).coerceIn(80, 640))) })
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(120, 160, 200, 240, 320).forEach { dpi -> TextButton("$dpi", onClick = { edit(config.copy(dpi = dpi)) }, modifier = Modifier.weight(1f),
                        insideMargin = PaddingValues(6.dp), colors = if (config.dpi == dpi) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors()) }
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("应用设置", onClick = { applyConfig() }, enabled = status.connected && !actionWorking,
                        colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1f))
                    TextButton("恢复推荐", onClick = { edit(recommendedConfig(status.modesRaw, status.physW, status.physH, status.physFps)); applyConfig() },
                        enabled = status.connected && !actionWorking, modifier = Modifier.weight(1f))
                }
            }
            AnimatedVisibility(visible = notice.isNotBlank()) {
                Card(insideMargin = PaddingValues(18.dp)) { Text(notice, color = extWarningTextColor()) }
            }
            Card {
                ArrowPreference(title = "自定义分辨率", summary = "设置宽度和高度", onClick = { showCustom = true })
                ArrowPreference(title = "手机推荐：1080p 横屏", summary = "1920×1080 @ 240dpi · 60Hz", onClick = {
                    edit(DisplayConfig(1920, 1080))
                    if (status.connected) applyConfig() else notice = "已载入推荐参数，连接后台服务后可应用"
                })
                ArrowPreference(title = "显示器模式与刷新率", summary = "目标 ${config.fps.roundToInt()}Hz", onClick = { showEdid = true })
                SwitchPreference(title = "DeX 默认横屏", summary = "应用规则可单独覆盖此设置", checked = status.fullscreenPolicy == 1,
                    enabled = status.connected && !actionWorking,
                    onCheckedChange = { action { BridgeClient.toggleFullscreenPolicy(); "全屏策略已切换" } })
            }
        }
    }

    @Composable
    private fun RulesPage(contentPadding: PaddingValues) {
        var query by rememberSaveable { mutableStateOf("") }
        var picked by remember { mutableStateOf<AppRule?>(null) }
        var reset by remember { mutableStateOf(false) }
        val filtered = remember(rules, query) { rules.filter { it.label.contains(query, true) || it.pkg.contains(query, true) } }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    top = contentPadding.calculateTopPadding() + 16.dp,
                    end = 20.dp,
                    bottom = contentPadding.calculateBottomPadding() + 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextField(value = query, onValueChange = { query = it }, label = "搜索应用或包名", singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("切换显示模式会重启该应用，请先保存内容。", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton("刷新列表", onClick = { loadRules() }, enabled = status.connected && !rulesLoading)
                            TextButton("全部重置", onClick = { reset = true }, enabled = status.connected && !actionWorking)
                        }
                        if (rulesError.isNotBlank()) {
                            Card(insideMargin = PaddingValues(16.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("读取应用规则失败", color = extWarningTextColor())
                                    Text(rulesError)
                                    if (rules.isNotEmpty()) Text("当前显示的可能不是最新规则。", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    TextButton("重试", onClick = { loadRules() }, enabled = status.connected && !rulesLoading)
                                }
                            }
                        }
                    }
                }
                when {
                    !status.connected -> item(key = "disconnected") {
                        Card(insideMargin = PaddingValues(20.dp)) { Text("激活后台服务后可管理应用规则。") }
                    }
                    rulesLoading && rules.isEmpty() -> item(key = "loading") {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(size = 28.dp, strokeWidth = 3.dp)
                            Text("正在读取应用与兼容规则…")
                        }
                    }
                    rulesError.isNotBlank() && rules.isEmpty() -> Unit
                    filtered.isEmpty() -> item(key = "empty") { Text("未找到匹配的应用") }
                    else -> items(filtered, key = { it.pkg }) { rule -> Card {
                        ArrowPreference(title = rule.label, summary = "${BridgeClient.appModeLabel(rule.mode)}\n${rule.pkg}", onClick = { picked = rule })
                    } }
                }
            }
            OverlayDialog(show = picked != null, title = picked?.label ?: "应用规则", onDismissRequest = { picked = null }) {
                val rule = picked
                Column {
                    listOf("跟随系统默认", "强制横屏全屏", "竖屏居中兼容").forEachIndexed { mode, label ->
                        RadioButtonPreference(
                            title = label,
                            selected = rule?.mode == mode,
                            onClick = {
                                picked = null
                                if (rule != null) action {
                                    val ok = BridgeClient.setAppMode(rule.pkg, mode, true)
                                    handler.post { rulesLoaded = false; loadRules() }
                                    if (ok) "已更新 ${rule.label} 的显示规则" else "规则更新失败"
                                }
                            },
                            radioButtonLocation = RadioButtonLocation.End,
                        )
                    }
                }
            }
            OverlayDialog(show = reset, title = "重置全部应用规则？", summary = "所有应用将恢复默认显示模式。", onDismissRequest = { reset = false }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("取消", onClick = { reset = false }, modifier = Modifier.weight(1f))
                    TextButton("确认重置", modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColors(
                        color = MiuixTheme.colorScheme.errorContainer,
                        textColor = MiuixTheme.colorScheme.onErrorContainer,
                    ), onClick = { reset = false; action {
                        val ok = BridgeClient.resetAllAppModes()
                        handler.post { rulesLoaded = false; loadRules() }
                        if (ok) "全部规则已重置" else "重置失败"
                    } })
                }
            }
        }
    }

    @Composable
    private fun SettingsPage(contentPadding: PaddingValues, scrollState: ScrollState, onOpenAbout: () -> Unit, wide: Boolean) {
        val liquidGlassSupported = rememberAppLiquidGlassSupported()
        val missingPermissions = buildList {
            if (!notif) add("通知")
            if (!overlay) add("悬浮窗")
            if (!accessibility) add("无障碍服务")
        }
        Page(contentPadding, scrollState) {
            SmallTitle("外观")
            Card {
                OverlayDropdownPreference(title = "界面外观", items = ExtThemePrefs.labels, selectedIndex = themeMode,
                    onSelectedIndexChange = { themeMode = it; ExtThemePrefs.set(this@MainActivity, it) })
                if (liquidGlassSupported) {
                    SwitchPreference(
                        title = "Miuix Blur 模糊",
                        checked = miuixBlur,
                        onCheckedChange = {
                            miuixBlur = it
                            ExtThemePrefs.setMiuixBlurEnabled(this@MainActivity, it)
                        },
                    )
                }
                SwitchPreference(
                    title = "悬浮导航栏",
                    summary = if (wide) "仅窄屏布局生效；当前宽屏使用侧边导航" else null,
                    checked = appleBottomBar,
                    enabled = !wide,
                    onCheckedChange = {
                        appleBottomBar = it
                        ExtThemePrefs.setAppleBottomBarEnabled(this@MainActivity, it)
                    },
                )
                SwitchPreference(
                    title = "液态玻璃底栏",
                    summary = when {
                        !liquidGlassSupported -> "需要 Android 13（API 33）或更高版本"
                        wide -> "仅窄屏悬浮导航栏可用"
                        !appleBottomBar -> "需先开启悬浮导航栏"
                        !miuixBlur -> "需先开启 Miuix Blur 模糊"
                        else -> null
                    },
                    checked = liquidGlass,
                    enabled = liquidGlassSupported && !wide && appleBottomBar && miuixBlur,
                    onCheckedChange = {
                        liquidGlass = it
                        ExtThemePrefs.setLiquidGlassEnabled(this@MainActivity, it)
                    },
                )
            }
            SmallTitle("权限与服务")
            Card {
                ArrowPreference(
                    title = "运行权限",
                    summary = if (missingPermissions.isEmpty()) "全部已授权" else "未授权：${missingPermissions.joinToString("、")}",
                    onClick = { devGate = false; showPermissions = true },
                )
                ArrowPreference(title = "无线调试与配对通知", summary = BridgeClient.getOemInjectHint(), onClick = { openDeveloperOptions() })
                ArrowPreference(title = "激活后台服务", summary = "Root、无线调试或电脑 ADB", onClick = { openActivation() })
                SwitchPreference(title = "自动 Root 激活", summary = "仅限已 Root 设备；服务未运行时自动激活", checked = autoRoot,
                    onCheckedChange = { changeAutoRoot(it) })
                ArrowPreference(title = "刷新显示", summary = "重新创建外接显示画面", enabled = status.connected && !actionWorking,
                    onClick = { action { BridgeClient.rebuild(); "正在刷新显示" } })
                ArrowPreference(title = "停止服务并退出", summary = "恢复本机亮度与外接显示状态", onClick = { showStop = true })
            }
            SmallTitle("其他")
            Card {
                ArrowPreference(title = "关于软件", onClick = onOpenAbout)
            }
        }
    }

    /** 关于软件：1:1 复刻 compose-miuix-ui 示例的 About 页（动态粉色渐变背景 + 玻璃质感卡片）。 */
    @Composable
    private fun AboutPage(wide: Boolean, onBack: () -> Unit) {
        val info = remember {
            val pi = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
            AboutMeta(
                version = appVersion.ifBlank { "—" },
                versionCode = appVersionCode,
                abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
                targetSdk = pi?.applicationInfo?.targetSdkVersion ?: 35,
            )
        }
        com.extbridge.helper.ui.AboutPage(
            appName = "ExtBridge Lite",
            versionLabel = "v${info.version} (${info.versionCode})",
            isDark = isEffectiveDark(themeMode),
            isWideScreen = wide,
            launcherIcon = painterResource(R.mipmap.ic_launcher),
            onBack = onBack,
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(title = "版本代码", endActions = {
                    Text("${info.versionCode}", fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                })
                BasicComponent(title = "系统版本", endActions = {
                    Text("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                })
                BasicComponent(title = "运行架构", endActions = {
                    Text(info.abi, fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                })
            }
            Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                BasicComponent(title = "界面主题", endActions = {
                    Text(ExtThemePrefs.labels[themeMode], fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                })
                BasicComponent(title = "界面框架", endActions = {
                    Text("Miuix 0.9.4", fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                })
                BasicComponent(title = "开源许可", endActions = {
                    Text("Apache-2.0", fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                })
            }
        }
    }

    private data class AboutMeta(
        val version: String,
        val versionCode: Long,
        val abi: String,
        val targetSdk: Int,
    )

    private fun openActivation(clearMessage: Boolean = true) {
        skipActivation = false
        activationConnection.open()
        if (clearMessage && !working) activationMessage = ""
        hostEdited = false; pairEdited = false; connectEdited = false
        if (host.isBlank()) host = AdbBootstrap.getLocalIpv4()
        activationTab = if (AdbBootstrap.hasSuBinaryHint() || autoRoot) 0 else 1
        showActivation = true
        daemonStatusChecked = false
        refresh()
        if (activationTab == 1) discover()
    }

    private fun dismissActivation() {
        ++activationEpoch
        pendingActivationResult = null
        AdbBootstrap.cancelActivation(activationTicket)
        activationTicket = null; working = false
        activationConnection.dismiss()
        showActivation = false
        skipActivation = true
        if (!working) AdbBootstrap.stopDiscovery()
    }

    private fun discover() {
        AdbBootstrap.startDiscovery(this) { found -> handler.post {
            if (isDestroyed || !showActivation) return@post
            mdns = "配对地址：${if (found.pairingPort > 0) "${found.pairingHost}:${found.pairingPort}" else "等待配对弹窗"}\n连接地址：${if (found.connectPort > 0) "${found.connectHost}:${found.connectPort}" else "等待开启无线调试"}"
            if (!hostEdited) host = found.pairingHost.ifBlank { found.connectHost }.ifBlank { host }
            if (!pairEdited && found.pairingPort > 0) pairPort = found.pairingPort.toString()
            if (!connectEdited && found.connectPort > 0) connectPort = found.connectPort.toString()
        } }
    }

    private fun changeAutoRoot(enabled: Boolean) {
        val saved = AdbBootstrap.setAutoRootEnabled(this, enabled)
        if (!saved) toast("自动激活设置未能保存")
        autoRoot = AdbBootstrap.isAutoRootEnabled(this)
        if (!autoRoot && activationTicket?.mode == ActivationSession.Mode.AUTO_ROOT) {
            ++activationEpoch; working = false; activationTicket = null; activationMessage = "自动激活已取消"
        }
        if (enabled && saved) {
            autoRootTried = false
            skipActivation = false
        }
    }

    private fun track(ticket: ActivationSession.Ticket?) {
        activationTicket = ticket
        if (ticket != null) activationConnection.requestActivation(ticket.token)
    }

    private fun progress(): AdbBootstrap.ProgressListener {
        val epoch = ++activationEpoch
        pendingActivationResult = null
        return object : AdbBootstrap.ProgressListener {
            override fun onStep(message: String?) {
                if (!isDestroyed && epoch == activationEpoch) activationMessage = message.orEmpty()
            }
            override fun onResult(success: Boolean, message: String?) {
                // Legacy callback does not provide ownership proof and cannot declare success.
                if (!isDestroyed && epoch == activationEpoch) { working = false; activationMessage = message.orEmpty(); refresh() }
            }
            override fun onActivationResult(result: ActivationFlow.Result) {
                if (isDestroyed || epoch != activationEpoch) return
                working = false; pairCode = ""
                activationMessage = result.message
                if (result.activated()) {
                    working = true; pendingActivationResult = result; pendingActivationEpoch = epoch
                } else activationTicket = null
                if ((result.outcome == ActivationFlow.Outcome.FAILED || result.outcome == ActivationFlow.Outcome.PAIRED_NEEDS_CONNECT)
                    && !showActivation && !skipActivation) openActivation(clearMessage = false)
                refresh()
            }
        }
    }

    private fun sendPairNotification() {
        refreshPermissions()
        if (!notif) { requestNotification(); return }
        AdbBootstrap.showPairingNotification(this, null)
        toast("已发送配对通知，请下拉通知栏输入配对码")
    }

    private fun requestNotification() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED &&
            (!onboarding.getBoolean("notif_asked", false) || shouldShowRequestPermissionRationale("android.permission.POST_NOTIFICATIONS"))) {
            onboarding.edit().putBoolean("notif_asked", true).apply()
            notificationPermission.launch("android.permission.POST_NOTIFICATIONS")
        } else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                .onFailure { toast("无法打开系统设置，请手动进入应用权限页") }
        }
    }

    private fun openDeveloperOptions() {
        refreshPermissions()
        if (!(notif && overlay && accessibility)) { devGate = true; showPermissions = true }
        else showDevGuide = true
    }

    private fun copyCommand() {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("激活命令", AdbBootstrap.buildPcAdbCommand()))
        toast("ADB 命令已复制，请在连接本机的电脑终端执行")
    }

    @Composable
    private fun Overlays() {
        OverlayDialog(show = showPermissions, title = "运行权限", onDismissRequest = { dismissPermissions() }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PermissionRow("通知与配对输入", notif) { requestNotification() }
                PermissionRow("悬浮窗与外接光标", overlay) { openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                PermissionRow("无障碍手势与浮层", accessibility) { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                if (!accessibility) TextButton("无障碍开关不可用？打开应用详情", onClick = { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) })
                Text(BridgeClient.getOemInjectHint(), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                TextButton(if (notif && overlay && accessibility) "完成" else if (devGate) "继续开发者选项" else "稍后设置",
                    onClick = { finishPermissions() }, modifier = Modifier.fillMaxWidth())
            }
        }
        OverlayDialog(show = showDevGuide, title = "无线调试配对", onDismissRequest = { showDevGuide = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. 开启无线调试。\n2. 打开「使用配对码配对设备」。\n3. 在配对通知中输入六位配对码。\n\n${BridgeClient.getOemInjectHint()}")
                TextButton("发送配对通知并打开开发者选项", onClick = {
                    showDevGuide = false; sendPairNotification(); openSettings(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }, colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
        ActivationOverlay()
        OverlayBottomSheet(show = showDiagnostics, title = "连接状态", onDismissRequest = { showDiagnostics = false }) {
            Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card {
                    DiagnosticRow("后台服务", when {
                        !daemonStatusChecked -> "检查中"
                        status.connected -> "已连接并验证"
                        else -> "未连接"
                    })
                    if (daemonStatusChecked && status.connected) {
                        DiagnosticRow("服务进程", "PID ${status.daemonPid} · UID ${status.daemonUid}")
                    }
                    DiagnosticRow("自动 Root 激活", if (autoRoot) "已开启" else "已关闭")
                }
                Card {
                    DiagnosticRow("输出模式", BridgeClient.mirrorModeLabel(status.mirrorMode))
                    DiagnosticRow("显示编号", "外接屏 #${status.extId} · 虚拟屏 #${status.vdId}")
                    DiagnosticRow("虚拟画面", "${status.virtW}×${status.virtH} · ${status.virtDpi}dpi")
                    DiagnosticRow("旋转方向", BridgeClient.rotationLabel(status.virtRot))
                    DiagnosticRow("目标刷新率", "${status.virtFps}Hz")
                    DiagnosticRow("物理信号", if (status.extId > 0) {
                        "${status.physW}×${status.physH} · ${status.physFps}Hz"
                    } else "未连接外接显示器")
                    DiagnosticRow("渲染管线", status.renderMode.ifBlank { "未知" })
                }
                Card {
                    DiagnosticRow("键鼠方向", if (status.inputToExt == 1) "扩展屏" else "本机")
                    DiagnosticRow("外接输入设备", if (status.extInputCnt >= 0) "${status.extInputCnt} 个" else "尚未枚举")
                    DiagnosticRow("分屏", if (status.splitActive == 1) "已开启（${status.splitRatioPct}%）" else "已关闭")
                }
                if (status.connectionIssue.isNotBlank()) Text(status.connectionIssue, color = extWarningTextColor())
                if (status.injectStatus == -1) Text("键鼠输入不可用：${BridgeClient.getOemInjectHint()}", color = extErrorTextColor())
                TextButton("刷新状态", onClick = { refresh() }, modifier = Modifier.fillMaxWidth())
            }
        }
        CustomResolutionOverlay()
        OverlayBottomSheet(show = showEdid, title = "显示器模式与刷新率", onDismissRequest = { showEdid = false }) {
            Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(listOf(60f, 75f, 90f), listOf(120f, 144f, 165f)).forEach { group -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.forEach { fps -> TextButton("${fps.toInt()}Hz", onClick = { edit(config.copy(fps = fps, physicalModeId = -1)) }, modifier = Modifier.weight(1f),
                        colors = if (config.fps == fps && config.physicalModeId == -1) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors()) }
                } }
                val modes = remember(status.modesRaw) { parsePhysicalModes(status.modesRaw) }
                if (modes.isEmpty()) Text("未检测到外接显示器模式。")
                if (modes.isNotEmpty()) Card {
                    modes.forEach { mode -> ArrowPreference(title = "${mode.w}×${mode.h} @ ${mode.fps}Hz", onClick = {
                        edit(config.resolution(mode.w, mode.h).copy(fps = mode.fps, physicalModeId = mode.id)); applyConfig(); showEdid = false
                    }) }
                }
                TextButton("应用刷新率", enabled = status.connected && !actionWorking, onClick = { applyConfig(); showEdid = false }, colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
        OverlayDialog(show = showStop, title = "停止服务并退出？", summary = "将停止后台服务，并恢复本机亮度与外接显示状态。", onDismissRequest = { showStop = false }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton("取消", onClick = { showStop = false }, modifier = Modifier.weight(1f))
                TextButton("停止并退出", modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColors(
                    color = HyperRed,
                    textColor = Color.White,
                ), onClick = { showStop = false; BridgeService.stopAllAndExit(this@MainActivity) })
            }
        }
    }

    private fun dismissPermissions() {
        showPermissions = false
        devGate = false
        onboarding.edit().putBoolean("startup_perms_done", true).apply()
        refresh()
    }

    private fun finishPermissions() {
        showPermissions = false
        onboarding.edit().putBoolean("startup_perms_done", true).apply()
        if (devGate) { devGate = false; showDevGuide = true }
        else refresh()
    }

    @Composable
    private fun PermissionRow(title: String, granted: Boolean, grant: () -> Unit) {
        Card(insideMargin = PaddingValues(0.dp)) {
            BasicComponent(
                title = title,
                summary = if (granted) "已授权" else "未授权",
                summaryColor = BasicComponentDefaults.summaryColor(color = if (granted) extSuccessTextColor() else extWarningTextColor()),
                endActions = {
                    if (!granted) TextButton("开启", onClick = grant)
                },
                onClick = if (granted) null else grant,
            )
        }
    }

    @Composable
    private fun DiagnosticRow(title: String, value: String) {
        BasicComponent(title = title, summary = value)
    }

    @Composable
    private fun CustomResolutionOverlay() {
        var w by remember(showCustom) { mutableStateOf(config.w.toString()) }
        var h by remember(showCustom) { mutableStateOf(config.h.toString()) }
        val next = config.copy(w = w.toIntOrNull() ?: 0, h = h.toIntOrNull() ?: 0, physicalModeId = -1)
        val validationError = when {
            w.toIntOrNull() == null -> "宽度请输入有效整数"
            h.toIntOrNull() == null -> "高度请输入有效整数"
            !next.valid() -> "当前宽高超出允许范围，请检查长边和短边"
            else -> null
        }
        OverlayDialog(show = showCustom, title = "自定义分辨率", onDismissRequest = { showCustom = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(value = w, onValueChange = { w = it }, label = "宽度", singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    TextField(value = h, onValueChange = { h = it }, label = "高度", singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                Text(validationError ?: "长边范围 640–7680，短边范围 480–4320。",
                    color = if (validationError != null) extErrorTextColor() else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                TextButton("对调宽高", onClick = { val swap = w; w = h; h = swap })
                TextButton(if (status.connected) "保存并应用" else "仅保存草稿", enabled = next.valid() && !actionWorking, onClick = {
                    edit(next)
                    showCustom = false
                    if (status.connected) applyConfig() else notice = "分辨率草稿已保存，连接后台服务后可应用"
                }, colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }

    @Composable
    private fun ActivationOverlay() {
        OverlayBottomSheet(show = showActivation && !showPermissions && !showDevGuide, title = "激活后台服务", onDismissRequest = {
            dismissActivation()
        }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("设备重启后需重新激活。", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                    BasicComponent(
                        title = when {
                            !daemonStatusChecked -> "正在检查后台服务…"
                            status.connected -> if (status.protocolCompatible) "后台服务已连接" else "后台服务需要更新"
                            status.connectionIssue.isNotBlank() -> "服务状态无法确认"
                            else -> "后台服务未激活"
                        },
                        titleColor = BasicComponentDefaults.titleColor(
                            color = if (daemonStatusChecked && status.connected) extSuccessTextColor() else MiuixTheme.colorScheme.onSurface,
                        ),
                        summary = when {
                            working -> "正在验证本次激活…"
                            daemonStatusChecked && status.connectionIssue.isNotBlank() -> status.connectionIssue
                            else -> null
                        },
                        insideMargin = PaddingValues(18.dp),
                    )
                }
                if (activationMessage.isNotBlank()) Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) { Text(activationMessage) }
                TabRowWithContour(tabs = listOf("Root", "无线调试", "电脑 ADB"), selectedTabIndex = activationTab,
                    onTabSelected = { activationTab = it; if (it == 1) discover() })
                AnimatedContent(
                    targetState = activationTab,
                    transitionSpec = { fadeIn(animationSpec = tween(180)).togetherWith(fadeOut(animationSpec = tween(140))) },
                    label = "activationTab",
                ) { mode ->
                    // AnimatedContent 会层叠直接子控件；每种激活方式必须自行提供纵向布局。
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when (mode) {
                        0 -> {
                            Text("设备需已获取 Root 权限。")
                            TextButton(if (working) "正在激活…" else "Root 激活", enabled = daemonStatusChecked && !working,
                                onClick = { working = true; track(AdbBootstrap.rootBootstrapAsync(this@MainActivity, false, progress())) },
                                colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.fillMaxWidth())
                            Card { SwitchPreference(title = "自动 Root 激活", summary = "服务未运行时自动激活", checked = autoRoot,
                                onCheckedChange = { changeAutoRoot(it) }) }
                        }
                        1 -> {
                            Text(mdns.ifBlank { "正在查找无线调试端口…" }, color = extInfoTextColor())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton("开启无线调试", onClick = { openDeveloperOptions() }, modifier = Modifier.weight(1f))
                                TextButton("发送配对通知", onClick = { sendPairNotification() }, modifier = Modifier.weight(1f))
                            }
                            TextField(value = host, onValueChange = { host = it; hostEdited = true }, label = "设备 IP 地址", singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                            if (hostEdited && host.isBlank()) Text("设备 IP 地址不能为空", color = extErrorTextColor())
                            TextField(value = pairPort, onValueChange = { pairPort = it; pairEdited = true }, label = "配对端口", singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                            if (pairEdited && (pairPort.toIntOrNull() ?: 0) !in 1..65535) Text("配对端口范围为 1–65535", color = extErrorTextColor())
                            TextField(value = pairCode, onValueChange = { pairCode = it }, label = "六位配对码", singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                            if (pairCode.isNotEmpty() && !pairCode.matches(Regex("\\d{6}"))) Text("配对码应为 6 位数字", color = extErrorTextColor())
                            TextField(value = connectPort, onValueChange = { connectPort = it; connectEdited = true }, label = "连接端口（已配对时使用）", singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                            if (connectEdited && connectPort.isNotBlank() && (connectPort.toIntOrNull() ?: 0) !in 1..65535) {
                                Text("连接端口范围为 1–65535", color = extErrorTextColor())
                            }
                            TextButton("配对并激活", enabled = daemonStatusChecked && !working && host.isNotBlank() && (pairPort.toIntOrNull() ?: 0) in 1..65535 && pairCode.matches(Regex("\\d{6}")),
                                onClick = { working = true; track(AdbBootstrap.pairAndBootstrapAsync(this@MainActivity, host.trim(), pairPort.toInt(), pairCode, host.trim(), connectPort.toIntOrNull() ?: 0, progress())) },
                                colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.fillMaxWidth())
                            TextButton("直接连接并激活", enabled = daemonStatusChecked && !working && host.isNotBlank() && (connectPort.toIntOrNull() ?: 0) in 1..65535,
                                onClick = { working = true; track(AdbBootstrap.connectAndBootstrapAsync(this@MainActivity, host.trim(), connectPort.toInt(), progress())) }, modifier = Modifier.fillMaxWidth())
                        }
                        else -> {
                            Text("电脑通过 USB 或无线 ADB 连接本机后，执行以下单行命令：")
                            Card(insideMargin = PaddingValues(16.dp)) { Text(AdbBootstrap.buildPcAdbCommand(), fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                            TextButton("复制 ADB 激活命令", onClick = { copyCommand() }, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.fillMaxWidth())
                        }
                        }
                    }
                }
                TextButton(when {
                    working -> "取消激活"
                    daemonStatusChecked && status.connected -> "完成"
                    else -> "暂不激活"
                }, onClick = { dismissActivation() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private data object MainRoute : NavKey
private data object AboutRoute : NavKey

private const val TAB_COUNT = 4

// Binder 交接宽限期与二次催推间隔（见 MainActivity.binderHandoffDeadline）。
private const val BINDER_HANDOFF_GRACE_MS = 4000L
private const val BINDER_REPOKE_DELAY_MS = 1200L

private enum class AppRoute(val tabIndex: Int) : NavKey {
    Dashboard(0),
    Display(1),
    Rules(2),
    Settings(3),
}

private val TAB_ROUTES = listOf(AppRoute.Dashboard, AppRoute.Display, AppRoute.Rules, AppRoute.Settings)

private fun routeForTab(index: Int): AppRoute = TAB_ROUTES[index.coerceIn(0, TAB_COUNT - 1)]

internal fun ensurePrimaryDisplay(activity: ComponentActivity, destination: Class<*>): Boolean {
    return runCatching {
        if (activity.display?.displayId != null && activity.display?.displayId != 0) {
            activity.startActivity(Intent(activity, destination).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
            activity.finishAndRemoveTask()
            true
        } else false
    }.getOrDefault(false)
}
