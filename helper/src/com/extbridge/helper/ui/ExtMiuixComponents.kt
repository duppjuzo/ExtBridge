package com.extbridge.helper.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

object ExtThemePrefs {
    private const val PREF_NAME = "extbridge_ui_prefs"
    private const val KEY_THEME_MODE = "theme_mode" // 0=System, 1=Light, 2=Dark

    val THEME_LABELS = listOf("跟随系统外观", "澎湃浅色模式", "澎湃深色模式")

    fun getThemeModeIndex(ctx: Context): Int {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_THEME_MODE, 0)
            .coerceIn(0, 2)
    }

    fun setThemeModeIndex(ctx: Context, idx: Int) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME_MODE, idx.coerceIn(0, 2))
            .apply()
    }

    fun toColorSchemeMode(idx: Int): ColorSchemeMode = when (idx) {
        1 -> ColorSchemeMode.Light
        2 -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }
}

val HyperBlue = Color(0xFF0D84FF)
val HyperGreen = Color(0xFF28C76F)
val HyperAmber = Color(0xFFFF9F0A)
val HyperRed = Color(0xFFE94634)
val HyperPurple = Color(0xFF7C5CFA)

@Composable
fun ExtHyperTheme(
    themeModeIndex: Int,
    content: @Composable () -> Unit,
) {
    val lightScheme = remember {
        lightColorScheme(
            primary = HyperBlue,
            primaryVariant = Color(0xFF0070E0),
            surface = Color(0xFFF4F6FA),
            background = Color(0xFFF4F6FA),
            surfaceContainer = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFEEF1F6),
            secondaryVariant = Color(0xFFEAECEF),
            dividerLine = Color(0xFFE5E8EF),
            outline = Color(0xFFDDE2EC),
        )
    }
    val darkScheme = remember {
        darkColorScheme(
            primary = HyperBlue,
            primaryVariant = Color(0xFF3395FF),
            surface = Color(0xFF0B0D12),
            background = Color(0xFF0B0D12),
            surfaceContainer = Color(0xFF171A21),
            surfaceVariant = Color(0xFF171A21),
            secondaryContainer = Color(0xFF222630),
            secondaryVariant = Color(0xFF252934),
            dividerLine = Color(0xFF272B36),
            outline = Color(0xFF2F3442),
        )
    }
    val mode = ExtThemePrefs.toColorSchemeMode(themeModeIndex)
    val controller = remember(mode) {
        ThemeController(
            colorSchemeMode = mode,
            lightColors = lightScheme,
            darkColors = darkScheme,
        )
    }
    MiuixTheme(controller = controller, content = content)
}

@Composable
fun isEffectiveDark(themeModeIndex: Int): Boolean = when (themeModeIndex) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun StatusPillBadge(
    label: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(dotColor.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = dotColor,
            maxLines = 1,
        )
    }
}

/**
 * Xiaomi HyperOS Control Center style quick-action Bento tile.
 */
@Composable
fun HyperBentoTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = HyperBlue,
) {
    val containerColor = if (active) activeColor else MiuixTheme.colorScheme.surfaceContainer
    val titleColor = if (active) Color.White else MiuixTheme.colorScheme.onSurfaceContainer
    val subColor = if (active) Color.White.copy(alpha = 0.82f) else MiuixTheme.colorScheme.onSurfaceVariantSummary
    val iconBg = if (active) Color.White.copy(alpha = 0.20f) else MiuixTheme.colorScheme.secondaryContainer
    val iconTint = if (active) Color.White else MiuixTheme.colorScheme.primary

    Card(
        modifier = modifier,
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
        colors = CardDefaults.defaultColors(
            color = containerColor,
            contentColor = titleColor,
        ),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onClick,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    color = subColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 2x2 Visual Mirror Layout Selector Cards with custom Canvas mini-monitor illustrations.
 */
@Composable
fun MirrorLayoutSelectorGrid(
    selectedMode: Int,
    onSelectMode: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        Triple(0, "纯黑居中", "无边栏 · 100% 纯画面"),
        Triple(1, "主屏靠左", "右侧融合宽边栏 · 推荐"),
        Triple(2, "主屏靠右", "左侧融合宽边栏"),
        Triple(3, "主屏居中", "左右对称双信息条"),
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (rowIdx in 0..1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (colIdx in 0..1) {
                    val (modeVal, title, sub) = items[rowIdx * 2 + colIdx]
                    MirrorLayoutVisualCard(
                        mode = modeVal,
                        title = title,
                        subtitle = sub,
                        selected = (selectedMode == modeVal),
                        onClick = { onSelectMode(modeVal) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MirrorLayoutVisualCard(
    mode: Int,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MiuixTheme.colorScheme.primary
    val borderMod = if (selected) {
        Modifier.border(1.8.dp, accent, RoundedCornerShape(16.dp))
    } else {
        Modifier.border(1.dp, MiuixTheme.colorScheme.dividerLine, RoundedCornerShape(16.dp))
    }
    val cardBg = if (selected) {
        accent.copy(alpha = 0.10f)
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }

    Card(
        modifier = modifier.then(borderMod),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(12.dp),
        colors = CardDefaults.defaultColors(color = cardBg),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onClick,
    ) {
        val screenFrameColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f)
        val mainScreenFill = if (selected) accent.copy(alpha = 0.85f) else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f)
        val sideBarFill = if (selected) HyperPurple.copy(alpha = 0.55f) else MiuixTheme.colorScheme.secondaryContainer

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
        ) {
            val mw = size.width * 0.78f
            val mh = size.height * 0.88f
            val mx = (size.width - mw) * 0.5f
            val my = (size.height - mh) * 0.5f
            val pad = 4.dp.toPx()

            drawRoundRect(
                color = screenFrameColor,
                topLeft = Offset(mx, my),
                size = Size(mw, mh),
                cornerRadius = CornerRadius(6.dp.toPx()),
                style = Stroke(width = 1.6.dp.toPx()),
            )

            val iw = mw - pad * 2
            val ih = mh - pad * 2
            val ix = mx + pad
            val iy = my + pad
            val r = CornerRadius(3.5.dp.toPx())
            val gap = 3.dp.toPx()

            when (mode) {
                0 -> {
                    // Pure centered main screen, black sidebars
                    val cw = iw * 0.62f
                    drawRoundRect(
                        color = mainScreenFill,
                        topLeft = Offset(ix + (iw - cw) * 0.5f, iy),
                        size = Size(cw, ih),
                        cornerRadius = r,
                    )
                }
                1 -> {
                    // Main screen left, wide sidebar right
                    val lw = iw * 0.63f
                    drawRoundRect(mainScreenFill, Offset(ix, iy), Size(lw, ih), r)
                    drawRoundRect(sideBarFill, Offset(ix + lw + gap, iy), Size(iw - lw - gap, ih), r)
                }
                2 -> {
                    // Wide sidebar left, main screen right
                    val sw = iw * 0.34f
                    drawRoundRect(sideBarFill, Offset(ix, iy), Size(sw, ih), r)
                    drawRoundRect(mainScreenFill, Offset(ix + sw + gap, iy), Size(iw - sw - gap, ih), r)
                }
                else -> {
                    // Symmetric dual wings + centered main screen
                    val wing = iw * 0.18f
                    val cw = iw - wing * 2 - gap * 2
                    drawRoundRect(sideBarFill, Offset(ix, iy), Size(wing, ih), r)
                    drawRoundRect(mainScreenFill, Offset(ix + wing + gap, iy), Size(cw, ih), r)
                    drawRoundRect(sideBarFill, Offset(ix + wing + cw + gap * 2, iy), Size(wing, ih), r)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) accent else MiuixTheme.colorScheme.onSurfaceContainer,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Cursor-safe wrapper around Miuix [TextField] using the [TextFieldValue] overload.
 *
 * Miuix's `OverlayBottomSheet` / `OverlayDialog` (`MiuixPopupUtils.DialogLayout`) forwards its
 * content lambda to `MiuixPopupHost` inside a `SideEffect`, introducing a 1-frame delay for plain
 * `String` state hoisted outside the overlay. Calling `TextField(value: String, ...)` inside an
 * overlay causes `BasicTextField(String)` to clamp `TextFieldValue.selection` against the previous
 * frame's shorter string (e.g. `""` -> `TextRange(0, 0)` when typing the 1st digit, jumping the
 * cursor before the character). Keeping `TextFieldValue` state locally inside the popup composition
 * preserves cursor selection and IME composition spans across async/deferred parent recompositions.
 */
@Composable
fun ExtTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    resetKey: Any? = Unit,
) {
    var tfv by remember(resetKey) {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    var lastExternalValue by remember(resetKey) { mutableStateOf(value) }
    val pendingEchoes = remember(resetKey) { ArrayDeque<String>() }

    if (value != lastExternalValue) {
        lastExternalValue = value
        val echoIdx = pendingEchoes.indexOf(value)
        if (echoIdx >= 0) {
            repeat(echoIdx + 1) { pendingEchoes.removeFirst() }
        } else {
            pendingEchoes.clear()
            if (value != tfv.text) {
                tfv = TextFieldValue(text = value, selection = TextRange(value.length))
            }
        }
    }

    TextField(
        value = tfv,
        onValueChange = { next ->
            val prevText = tfv.text
            tfv = next
            if (next.text != prevText) {
                if (pendingEchoes.size >= 32) {
                    pendingEchoes.removeFirst()
                }
                pendingEchoes.addLast(next.text)
                onValueChange(next.text)
            }
        },
        modifier = modifier,
        label = label,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
    )
}
