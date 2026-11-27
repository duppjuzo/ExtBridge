package com.extbridge.helper.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun rememberAppBlurSupported(): Boolean = remember { isRuntimeShaderSupported() }

@Composable
fun rememberAppLiquidGlassSupported(): Boolean = rememberAppBlurSupported()

@Composable
fun rememberAppBackdrop(): LayerBackdrop {
    val background = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }
}

@Composable
fun rememberAppBackdropOrNull(): LayerBackdrop? {
    if (!rememberAppBlurSupported()) return null
    return rememberAppBackdrop()
}

@Composable
fun rememberAppBlurBackdrop(): LayerBackdrop? = rememberAppBackdropOrNull()

@Composable
fun appTopBarBlur(backdrop: LayerBackdrop?): Modifier = appBarBlur(backdrop)

@Composable
fun appBottomBarBlur(backdrop: LayerBackdrop?): Modifier = appBarBlur(backdrop)

@Composable
private fun appBarBlur(backdrop: LayerBackdrop?): Modifier {
    val surface = MiuixTheme.colorScheme.surface
    if (backdrop == null) return Modifier.background(surface)
    val colors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(surface.copy(alpha = 0.87f))),
    )
    return Modifier.textureBlur(
        backdrop = backdrop,
        shape = RectangleShape,
        blurRadius = 25f,
        colors = colors,
    )
}

@Composable
fun appNavigationRailBlur(backdrop: LayerBackdrop?): Modifier {
    val surface = MiuixTheme.colorScheme.surface
    if (backdrop == null) return Modifier.background(surface)
    val colors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(surface.copy(alpha = 0.87f))),
    )
    return Modifier.textureBlur(
        backdrop = backdrop,
        shape = RectangleShape,
        blurRadius = 25f,
        colors = colors,
    )
}
