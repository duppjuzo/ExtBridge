// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.extbridge.helper.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Solid, shader-free capsule for devices without RuntimeShader or with blur disabled. */
@Composable
fun FloatingBottomBarFallback(
    modifier: Modifier = Modifier,
    selectedIndex: () -> Int,
    onSelected: (index: Int) -> Unit,
    tabsCount: Int,
    content: @Composable RowScope.() -> Unit,
) {
    val selectedIndexProvider by rememberUpdatedState(selectedIndex)
    val onSelectedProvider by rememberUpdatedState(onSelected)
    val indicator = remember { Animatable(selectedIndexProvider().toFloat()) }
    val surfaceContainer = MiuixTheme.colorScheme.surfaceContainer
    val accentColor = MiuixTheme.colorScheme.primary
    val tabContentColor = MiuixTheme.colorScheme.onSurface
    val pillShape = remember { CircleShape }
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    var tabWidthPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(indicator) {
        snapshotFlow { selectedIndexProvider() }.collectLatest { index ->
            indicator.animateTo(index.toFloat())
            onSelectedProvider(index)
        }
    }

    Box(modifier = modifier.width(IntrinsicSize.Min), contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier
                .onGloballyPositioned { coordinates ->
                    val contentWidth = coordinates.size.width.toFloat() - with(density) { 8.dp.toPx() }
                    tabWidthPx = (contentWidth / tabsCount).coerceAtLeast(0f)
                }
                .clip(pillShape)
                .background(surfaceContainer)
                .height(64.dp)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides tabContentColor) {
                content()
            }
        }
        if (tabWidthPx > 0f) {
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .graphicsLayer {
                        val offset = indicator.value * tabWidthPx
                        translationX = if (isLtr) offset else -offset
                    }
                    .clip(pillShape)
                    .background(accentColor.copy(alpha = 0.15f))
                    .height(56.dp)
                    .width(with(density) { tabWidthPx.toDp() }),
            )
        }
    }
}
