package com.extbridge.helper.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

// Same palette and preference contract as main; no ROM-specific dependencies.
object ExtThemePrefs {
    val labels = listOf("跟随系统", "浅色", "深色")
    fun get(ctx: Context) = ctx.getSharedPreferences("extbridge_ui_prefs", 0)
        .getInt("theme_mode", 0).coerceIn(0, 2)
    fun set(ctx: Context, value: Int) = ctx.getSharedPreferences("extbridge_ui_prefs", 0)
        .edit().putInt("theme_mode", value.coerceIn(0, 2)).apply()

    fun isMiuixBlurEnabled(ctx: Context) = ctx.getSharedPreferences("extbridge_ui_prefs", 0)
        .getBoolean("miuix_blur_enabled", true)
    fun setMiuixBlurEnabled(ctx: Context, enabled: Boolean) = ctx.getSharedPreferences("extbridge_ui_prefs", 0)
        .edit().putBoolean("miuix_blur_enabled", enabled).apply()

    fun isAppleBottomBarEnabled(ctx: Context) = ctx.getSharedPreferences("extbridge_ui_prefs", 0)
        .getBoolean("apple_bottom_bar_enabled", false)
    fun setAppleBottomBarEnabled(ctx: Context, enabled: Boolean) = ctx.getSharedPreferences("extbridge_ui_prefs", 0)
        .edit().putBoolean("apple_bottom_bar_enabled", enabled).apply()

    fun isLiquidGlassEnabled(ctx: Context) = ctx.getSharedPreferences("extbridge_ui_prefs", 0)
        .getBoolean("liquid_glass_enabled", true)
    fun setLiquidGlassEnabled(ctx: Context, enabled: Boolean) = ctx.getSharedPreferences("extbridge_ui_prefs", 0)
        .edit().putBoolean("liquid_glass_enabled", enabled).apply()
}

// Restore the original light HyperOS blue used before the contrast pass.
val HyperBlue = Color(0xFF0D84FF)

// Restore the original bright status colors from before the contrast pass.
val HyperGreen = Color(0xFF28C76F)
val HyperAmber = Color(0xFFFF9F0A)
val HyperRed = Color(0xFFE94634)

// Multi-tone pink accent used by the "About" page brand header.
val HyperPink = Color(0xFFFF4D8D)
val HyperViolet = Color(0xFF9B5BFF)

// Status text keeps the original bright hues the user expects.
@Composable
fun extInfoTextColor(): Color = HyperBlue

@Composable
fun extSuccessTextColor(): Color = HyperGreen

@Composable
fun extWarningTextColor(): Color = HyperAmber

@Composable
fun extErrorTextColor(): Color = HyperRed

@Composable
fun isEffectiveDark(mode: Int): Boolean = when (mode) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun ExtHyperTheme(mode: Int, content: @Composable () -> Unit) {
    val light = remember {
        lightColorScheme(
            primary = HyperBlue, primaryVariant = Color(0xFF0070E0),
            surface = Color(0xFFF4F6FA), background = Color(0xFFF4F6FA),
            surfaceContainer = Color.White, surfaceVariant = Color.White,
            secondaryContainer = Color(0xFFEEF1F6), secondaryVariant = Color(0xFFEAECEF),
            dividerLine = Color(0xFFE5E8EF), outline = Color(0xFFDDE2EC),
        )
    }
    val dark = remember {
        darkColorScheme(
            primary = HyperBlue, primaryVariant = Color(0xFF3395FF),
            surface = Color(0xFF0B0D12), background = Color(0xFF0B0D12),
            surfaceContainer = Color(0xFF171A21), surfaceVariant = Color(0xFF171A21),
            secondaryContainer = Color(0xFF222630), secondaryVariant = Color(0xFF252934),
            dividerLine = Color(0xFF272B36), outline = Color(0xFF2F3442),
        )
    }
    val controller = remember(mode) {
        ThemeController(
            colorSchemeMode = when (mode) {
                1 -> ColorSchemeMode.Light
                2 -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            },
            lightColors = light, darkColors = dark,
        )
    }
    MiuixTheme(controller = controller, content = content)
}
