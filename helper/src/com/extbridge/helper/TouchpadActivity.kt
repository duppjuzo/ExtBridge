package com.extbridge.helper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.extbridge.helper.ui.*
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

class TouchpadActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val inputQueue = MouseInputQueue(io, { type, a, b -> BridgeClient.sendMouse(type, a, b) }) { type, xy ->
        handler.post {
            if (!destroyed && xy != null && xy.size >= 2) {
                curX = xy[0]; curY = xy[1]
                BridgeService.moveCursor(curX.toFloat(), curY.toFloat(), held || dragLocked || type == 3)
                if (xy.size >= 3) setInjectState(xy[2])
            }
        }
    }
    @Volatile private var destroyed = false
    private var held = false
    private var pad: NativeTrackpadView? = null
    private var themeMode by mutableIntStateOf(0)
    private var miuixBlur by mutableStateOf(true)
    private var speedIdx by mutableIntStateOf(1)
    private val speeds = floatArrayOf(1f, 1.6f, 2.3f, 3.2f)
    private var oneHand by mutableStateOf(false)
    private var dragLocked by mutableStateOf(false)
    private var useCtrlV by mutableStateOf(false)
    private var imeVisible by mutableStateOf(false)
    private var imeText by mutableStateOf("")
    private var clipboardGeneration = 0
    private var clipboardRestorePending = false
    private var clipboardBeforeInput: ClipData? = null
    private var clipboardInputText: String? = null
    private var connected by mutableStateOf(false)
    private var toExternal by mutableStateOf(true)
    private var injectDenied by mutableStateOf(false)
    private var virtW by mutableIntStateOf(2560)
    private var virtH by mutableIntStateOf(1440)
    private var curX by mutableIntStateOf(1280)
    private var curY by mutableIntStateOf(720)
    private var resumed = false
    private var refreshing = false
    private val binderCallback = Runnable { handler.post { refresh() } }
    private val refreshLoop = object : Runnable {
        override fun run() {
            if (!resumed) return
            refresh()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ensurePrimaryDisplay(this, TouchpadActivity::class.java)) return
        enableEdgeToEdge()
        BridgeService.ensureStarted(this)
        themeMode = ExtThemePrefs.get(this)
        miuixBlur = ExtThemePrefs.isMiuixBlurEnabled(this)
        setContent {
            ExtHyperTheme(themeMode) {
                val dark = isEffectiveDark(themeMode)
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                TouchpadScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        themeMode = ExtThemePrefs.get(this)
        miuixBlur = ExtThemePrefs.isMiuixBlurEnabled(this)
        resumed = true
        BridgeClient.setOnBinderReady(binderCallback)
        BridgeClient.requestBinder(this)
        handler.removeCallbacks(refreshLoop)
        handler.post(refreshLoop)
    }

    private fun releaseHeldMouse() {
        pad?.reset()
        val release = held || dragLocked
        dragLocked = false
        held = false
        inputQueue.clearMovement()
        if (release) inputQueue.offer(4)
        BridgeService.moveCursor(curX.toFloat(), curY.toFloat(), false)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(refreshLoop)
        releaseHeldMouse()
        BridgeClient.removeOnBinderReady(binderCallback)
        super.onPause()
    }

    override fun onStop() { releaseHeldMouse(); super.onStop() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        releaseHeldMouse()
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        releaseHeldMouse()
        restoreClipboardIfOwned()
        destroyed = true
        ++clipboardGeneration
        clipboardRestorePending = false
        clipboardBeforeInput = null
        clipboardInputText = null
        inputQueue.close()
        BridgeClient.removeOnBinderReady(binderCallback)
        handler.removeCallbacksAndMessages(null)
        io.shutdown() // NOT shutdownNow: queued LEFT_UP must reach the daemon.
        pad = null
        super.onDestroy()
    }

    private fun refresh() {
        if (destroyed || refreshing || io.isShutdown) return
        refreshing = true
        io.execute {
            val st = BridgeClient.getStatus()
            handler.post {
                refreshing = false
                if (destroyed) return@post
                connected = st.connected
                if (st.connected) {
                    virtW = st.virtW; virtH = st.virtH; curX = st.cursorX; curY = st.cursorY
                    toExternal = st.inputToExt == 1
                    setInjectState(st.injectStatus)
                }
            }
        }
    }

    private fun setInjectState(status: Int) { injectDenied = status == -1 }
    private fun mouse(type: Int, a: Float = 0f, b: Float = 0f) {
        if (destroyed || !connected) return
        if (type == 3) held = true
        if (type == 4) held = false
        inputQueue.offer(type, a, b)
    }
    private fun key(code: Int, meta: Int = 0) {
        if (!destroyed && connected && !io.isShutdown) io.execute { BridgeClient.sendKey(code, meta) }
    }

    private fun restoreClipboardIfOwned() {
        if (!clipboardRestorePending) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val expectedText = clipboardInputText
        val currentText = runCatching {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        }.getOrNull()
        val original = clipboardBeforeInput
        clipboardRestorePending = false
        clipboardBeforeInput = null
        clipboardInputText = null
        if (expectedText != null && currentText == expectedText) {
            if (original != null) clipboard.setPrimaryClip(original) else clipboard.clearPrimaryClip()
        }
    }

    private fun commitText() {
        if (!connected || io.isShutdown) return
        val text = imeText
        imeText = ""
        when {
            text.isEmpty() -> key(KeyEvent.KEYCODE_ENTER)
            text.all { it.code in 0x20..0x7E } -> io.execute { BridgeClient.sendAscii(text) }
            else -> {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                if (!clipboardRestorePending) {
                    clipboardBeforeInput = clipboard.primaryClip
                    clipboardRestorePending = true
                }
                val generation = ++clipboardGeneration
                clipboardInputText = text
                clipboard.setPrimaryClip(ClipData.newPlainText("extbridge", text))
                val ctrlV = useCtrlV
                handler.postDelayed({
                    if (!destroyed && !io.isShutdown && generation == clipboardGeneration) {
                        io.execute { BridgeClient.sendPaste(ctrlV) }
                    }
                }, 35)
                handler.postDelayed({
                    if (!destroyed && generation == clipboardGeneration) restoreClipboardIfOwned()
                }, 1500)
            }
        }
    }

    @Composable
    private fun TouchpadScreen() {
        val surface = MiuixTheme.colorScheme.surfaceContainer.toArgb()
        val blue = MiuixTheme.colorScheme.primary.toArgb()
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val density = LocalDensity.current
        val imeBottom = WindowInsets.ime.getBottom(density)
        var sawIme by remember { mutableStateOf(false) }
        LaunchedEffect(imeBottom) {
            if (imeBottom > 0) {
                sawIme = true
            } else if (sawIme) {
                sawIme = false
                imeVisible = false
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            val portrait = maxHeight > maxWidth
            val backdrop = if (miuixBlur) rememberAppBlurBackdrop() else null
            // A phone in landscape cannot fit the pad, controls AND IME. Keep input usable
            // in a scrollable compact panel instead of pushing it underneath the keyboard.
            val compactInput = imeVisible && maxHeight < 640.dp
            val scrollableContent = imeVisible || maxHeight < 560.dp || density.fontScale > 1.25f
            LaunchedEffect(imeVisible, compactInput) {
                if (imeVisible) { focus.requestFocus(); keyboard?.show() } else keyboard?.hide()
            }
            Scaffold(topBar = {
                if (!compactInput) SmallTopAppBar(
                    title = "大触摸板",
                    modifier = appTopBarBlur(backdrop),
                    color = Color.Transparent,
                    subtitle = if (connected) "光标 ($curX, $curY) · $virtW×$virtH" else "后台服务未连接",
                    navigationIcon = {
                        val layoutDirection = LocalLayoutDirection.current
                        IconButton(onClick = { finish() }) {
                            Icon(
                                MiuixIcons.Regular.Back,
                                "返回控制台",
                                modifier = Modifier.graphicsLayer {
                                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                },
                            )
                        }
                    },
                )
            }) { padding ->
                Box(
                    Modifier.fillMaxSize().then(
                        if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier,
                    ),
                ) {
                    AnimatedContent(
                        targetState = compactInput,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = { fadeIn(animationSpec = tween(180)).togetherWith(fadeOut(animationSpec = tween(140))) },
                        label = "touchpadInput",
                    ) { compact ->
                        if (compact) {
                            Column(
                                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("键盘输入", modifier = Modifier.weight(1f))
                                    TextButton("发送", enabled = connected, onClick = { commitText() }, colors = ButtonDefaults.textButtonColorsPrimary())
                                    TextButton("收起键盘", onClick = { imeVisible = false })
                                }
                                KeyboardInput(focus)
                            }
                        } else Column(
                            Modifier.fillMaxSize().padding(padding)
                                .then(if (scrollableContent) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                                .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("单指移动与左键 · 双指右键与滚动 · 右边缘滚动", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            AnimatedVisibility(visible = injectDenied) {
                                Card(insideMargin = PaddingValues(14.dp)) {
                                    Text("输入被系统拦截：${BridgeClient.getOemInjectHint()}", color = extErrorTextColor(), fontSize = 12.sp)
                                }
                            }
                            if (portrait && oneHand && !imeVisible && !scrollableContent) Spacer(Modifier.weight(0.72f))
                            AndroidView(factory = { ctx -> NativeTrackpadView(ctx).also { pad = it; it.setColors(surface, blue) } },
                                update = { it.setColors(surface, blue) },
                                modifier = Modifier.fillMaxWidth().then(
                                    if (scrollableContent) {
                                        Modifier.height(if (imeVisible) 220.dp else 180.dp)
                                    } else {
                                        Modifier.weight(1f).heightIn(min = 72.dp)
                                    },
                                ))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TextButton("左键", onClick = { mouse(1) }, enabled = connected, modifier = Modifier.weight(1f))
                                TextButton(if (dragLocked) "结束拖拽" else "开始拖拽", onClick = {
                                    dragLocked = !dragLocked; mouse(if (dragLocked) 3 else 4)
                                }, enabled = connected, modifier = Modifier.weight(1f), colors = if (dragLocked) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
                                TextButton("右键", onClick = { mouse(2) }, enabled = connected, modifier = Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton("返回", onClick = { if (connected) io.execute { BridgeClient.sendBack() } }, enabled = connected)
                                TextButton("桌面", onClick = { if (connected) io.execute { BridgeClient.relaunchLauncher() } }, enabled = connected)
                                TextButton(if (toExternal) "键鼠：扩展屏" else "键鼠：本机", onClick = {
                                    io.execute { BridgeClient.toggleInput(); handler.post { if (!destroyed) { BridgeService.refreshFromReceiver(this@TouchpadActivity); refresh() } } }
                                }, enabled = connected)
                                TextButton("速度 ${speeds[speedIdx]}×", onClick = { speedIdx = (speedIdx + 1) % speeds.size })
                                if (portrait) TextButton(if (oneHand) "切换全屏" else "单手模式", onClick = { oneHand = !oneHand })
                                TextButton("降低本机亮度", onClick = { BridgeService.toggleBlackoutFromApp(this@TouchpadActivity) }, enabled = connected)
                                }
                                TextButton(if (imeVisible) "收起键盘" else "键盘", onClick = { if (!imeVisible) releaseHeldMouse(); imeVisible = !imeVisible })
                            }
                            AnimatedVisibility(visible = imeVisible) {
                                KeyboardInput(focus)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun KeyboardInput(focus: FocusRequester) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(value = imeText, onValueChange = { imeText = it }, label = "输入要发送的文字", singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { commitText() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton("发送", enabled = connected, onClick = { commitText() }, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1f))
                TextButton(if (useCtrlV) "粘贴方式：Ctrl+V" else "粘贴方式：PASTE 键", onClick = { useCtrlV = !useCtrlV }, modifier = Modifier.weight(1f))
            }
            Text("非 ASCII 文字会短暂使用系统剪贴板，发送后尝试恢复原内容。",
                fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("回车" to KeyEvent.KEYCODE_ENTER, "退格" to KeyEvent.KEYCODE_DEL, "空格" to KeyEvent.KEYCODE_SPACE,
                    "Esc" to KeyEvent.KEYCODE_ESCAPE, "Tab" to KeyEvent.KEYCODE_TAB,
                    "上" to KeyEvent.KEYCODE_DPAD_UP, "下" to KeyEvent.KEYCODE_DPAD_DOWN,
                    "左" to KeyEvent.KEYCODE_DPAD_LEFT, "右" to KeyEvent.KEYCODE_DPAD_RIGHT, "Home" to KeyEvent.KEYCODE_HOME).forEach { (label, code) ->
                    TextButton(label, onClick = { key(code) }, enabled = connected)
                }
                listOf("全选" to KeyEvent.KEYCODE_A, "复制" to KeyEvent.KEYCODE_C, "粘贴" to KeyEvent.KEYCODE_V).forEach { (label, code) ->
                    TextButton(label, onClick = { key(code, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON) }, enabled = connected)
                }
            }
        }
    }

    private inner class NativeTrackpadView(ctx: Context) : View(ctx) {
        private val dots = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rail = Paint(Paint.ANTI_ALIAS_FLAG)
        private var mode = 0
        private var downTime = 0L
        private var lastTap = 0L
        private var lastX = 0f; private var lastY = 0f
        private var last2X = 0f; private var last2Y = 0f
        private var totalMove = 0f
        private var scrollV = 0f; private var scrollH = 0f
        private var touchX = -1f; private var touchY = -1f
        private var colors: Pair<Int, Int>? = null
        private fun px(dp: Float) = dp * resources.displayMetrics.density

        init {
            isFocusable = true
            isClickable = true
            contentDescription = "触摸板：单指移动与左键，双指右键与滚动，右边缘滚动；也可使用方向键移动、确认键点击"
        }

        fun setColors(surface: Int, primary: Int) {
            if (colors == surface to primary) return
            colors = surface to primary
            background = GradientDrawable().apply { setColor(surface); cornerRadius = px(20f); setStroke(px(1f).toInt().coerceAtLeast(1), (primary and 0x00FFFFFF) or 0x44000000) }
            dots.color = (primary and 0x00FFFFFF) or 0x22000000
            ring.color = primary; ring.strokeWidth = px(1.6f)
            rail.color = (primary and 0x00FFFFFF) or 0x14000000
            invalidate()
        }

        fun reset() { mode = 0; lastTap = 0; scrollH = 0f; scrollV = 0f; touchX = -1f; touchY = -1f; invalidate() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val railWidth = px(26f)
            val step = maxOf(24f, px(30f))
            var y = step
            while (y < height) {
                var x = step
                while (x < width - railWidth) { canvas.drawCircle(x, y, px(1.2f), dots); x += step }
                y += step
            }
            canvas.drawRect(width - railWidth, px(10f), width - px(2f), height - px(10f), rail)
            if (touchX >= 0 && touchY >= 0) canvas.drawCircle(touchX, touchY, px(20f), ring)
        }

        override fun performClick(): Boolean {
            super.performClick()
            mouse(1)
            return true
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
            if (!connected) return super.onKeyDown(keyCode, event)
            val step = px(20f) * speeds[speedIdx]
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> mouse(0, 0f, -step)
                KeyEvent.KEYCODE_DPAD_DOWN -> mouse(0, 0f, step)
                KeyEvent.KEYCODE_DPAD_LEFT -> mouse(0, -step, 0f)
                KeyEvent.KEYCODE_DPAD_RIGHT -> mouse(0, step, 0f)
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    performClick()
                    return true
                }
                else -> return super.onKeyDown(keyCode, event)
            }
            return true
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.isClickable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (!connected) return super.performAccessibilityAction(action, arguments)
            when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> performClick()
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> mouse(5, 0f, 8f)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> mouse(5, 0f, -8f)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id -> mouse(5, 8f, 0f)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id -> mouse(5, -8f, 0f)
                else -> return super.performAccessibilityAction(action, arguments)
            }
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!connected) return true
            val now = event.eventTime
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    downTime = now; lastX = event.x; lastY = event.y
                    totalMove = 0f; scrollH = 0f; scrollV = 0f
                    touchX = event.x; touchY = event.y
                    mode = when {
                        !dragLocked && now - lastTap < 240 -> { mouse(3); 3 }
                        event.x >= width - px(26f) && width > px(78f) -> 4
                        else -> 1
                    }
                    invalidate()
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                    if (mode == 3) mouse(4)
                    mode = 2
                    last2X = (event.getX(0) + event.getX(1)) * 0.5f
                    last2Y = (event.getY(0) + event.getY(1)) * 0.5f
                    scrollH = 0f; scrollV = 0f; totalMove = 0f
                }
                MotionEvent.ACTION_MOVE -> {
                    touchX = event.getX(0); touchY = event.getY(0); invalidate()
                    when (mode) {
                        1, 3 -> {
                            val dx = event.getX(0) - lastX; val dy = event.getY(0) - lastY
                            lastX = event.getX(0); lastY = event.getY(0)
                            val dist = hypot(dx, dy); totalMove += dist
                            if (dist > px(0.2f)) {
                                val padWidth = maxOf(320f, width - px(26f))
                                val portraitGain = ((virtW / padWidth) * 0.65f).coerceIn(1f, 2.1f)
                                val density = resources.displayMetrics.density.coerceAtLeast(1f)
                                val accel = speeds[speedIdx] * (2f / density) * portraitGain * (1f + minOf(dist, px(18f)) * (0.045f / density))
                                mouse(0, dx * accel, dy * accel)
                            }
                        }
                        4 -> {
                            val dx = event.getX(0) - lastX; val dy = event.getY(0) - lastY
                            lastX = event.getX(0); lastY = event.getY(0); totalMove += hypot(dx, dy)
                            scrollV += dy / px(16f)
                            if (abs(scrollV) >= 0.25f) { mouse(5, 0f, scrollV); scrollV = 0f }
                        }
                        2 -> if (event.pointerCount >= 2) {
                            val x = (event.getX(0) + event.getX(1)) * 0.5f
                            val y = (event.getY(0) + event.getY(1)) * 0.5f
                            val dx = x - last2X; val dy = y - last2Y
                            last2X = x; last2Y = y; totalMove += hypot(dx, dy)
                            scrollV += dy / px(16f); scrollH += dx / px(20f)
                            if (abs(scrollV) >= 0.25f || abs(scrollH) >= 0.25f) { mouse(5, -scrollH, scrollV); scrollV = 0f; scrollH = 0f }
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> if (mode == 2) {
                    if (now - downTime < 260 && totalMove < px(12f)) mouse(2)
                    mode = 0 // Do not turn the remaining finger into an accidental left click.
                }
                MotionEvent.ACTION_UP -> {
                    if (mode == 3) mouse(4)
                    else if ((mode == 1 || mode == 4) && now - downTime < 270 && totalMove < px(8f)) {
                        performClick(); lastTap = now
                    }
                    mode = 0; touchX = -1f; touchY = -1f; invalidate()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (mode == 3 || held || dragLocked) { dragLocked = false; mouse(4) }
                    reset(); parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }
    }
}
