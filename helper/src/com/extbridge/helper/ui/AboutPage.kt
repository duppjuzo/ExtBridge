package com.extbridge.helper.ui

// Ported 1:1 from compose-miuix-ui example (v0.9.4)
// example/shared/src/commonMain/kotlin/AboutPage.kt
// SPDX-License-Identifier: Apache-2.0
//
// Deviations from the reference, and why:
//  * No navigation framework: the page is a child of the Settings tab, so the caller
//    supplies `onBack` instead of a NavController.
//  * No blur backdrop pass: `rememberLayerBackdrop`/`textureBlur` render the shader a
//    second time; the reference itself falls back to opaque cards when a backdrop is
//    unavailable, which is the branch used here for every device.

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalScrollBarApi::class)
@Composable
fun AboutPage(
    appName: String,
    versionLabel: String,
    isDark: Boolean,
    isWideScreen: Boolean,
    launcherIcon: androidx.compose.ui.graphics.painter.Painter,
    onBack: () -> Unit,
    rows: @Composable () -> Unit,
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()

    val scrollProgress by remember {
        derivedStateOf {
            when {
                lazyListState.firstVisibleItemIndex > 0 -> 1f
                else -> {
                    val spacer = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "hero" }
                    if (spacer != null && spacer.size > 0) {
                        (lazyListState.firstVisibleItemScrollOffset.toFloat() / spacer.size).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            val titleColor = MiuixTheme.colorScheme.onSurface.copy(
                alpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f),
            )
            val layoutDirection = LocalLayoutDirection.current
            val surfaceAlpha = ((scrollProgress - 0.20f) / 0.35f).coerceIn(0f, 1f)
            SmallTopAppBar(
                title = "关于软件",
                scrollBehavior = topAppBarScrollBehavior,
                color = MiuixTheme.colorScheme.surface.copy(alpha = surfaceAlpha),
                titleColor = titleColor,
                navigationIcon = {
                    top.yukonga.miuix.kmp.basic.IconButton(onClick = onBack) {
                        top.yukonga.miuix.kmp.basic.Icon(
                            MiuixIcons.Regular.Back,
                            "返回",
                            modifier = Modifier.graphicsLayer {
                                scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                            },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        AboutContent(
            appName = appName,
            versionLabel = versionLabel,
            isDark = isDark,
            isWideScreen = isWideScreen,
            launcherIcon = launcherIcon,
            padding = innerPadding,
            topAppBarScrollBehavior = topAppBarScrollBehavior,
            lazyListState = lazyListState,
            scrollProgressProvider = { scrollProgress },
            rows = rows,
        )
    }
}

@OptIn(ExperimentalScrollBarApi::class)
@Composable
private fun AboutContent(
    appName: String,
    versionLabel: String,
    isDark: Boolean,
    isWideScreen: Boolean,
    launcherIcon: androidx.compose.ui.graphics.painter.Painter,
    padding: PaddingValues,
    topAppBarScrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
    lazyListState: LazyListState,
    scrollProgressProvider: () -> Float,
    rows: @Composable () -> Unit,
) {
    val horizontal = if (isWideScreen) 32.dp else 16.dp

    AboutBgBackground(
        dynamicBackground = true,
        isOs3Effect = true,
        isFullSize = true,
        isPad = isWideScreen,
        isDark = isDark,
        modifier = Modifier.fillMaxSize(),
        alpha = { 1f - scrollProgressProvider() },
    ) {
        // Hero 和信息卡在同一个列表中排版，不再用固定 Hero + 估算占位高度，避免内容重叠。
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = horizontal,
                end = horizontal,
                top = padding.calculateTopPadding() + 52.dp,
                bottom = padding.calculateBottomPadding(),
            ),
        ) {
            item(key = "hero") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(88.dp)
                            .graphicsLayer {
                                val iconProgress = ((scrollProgressProvider() - 0.35f) / 0.15f).coerceIn(0f, 1f)
                                clip = true
                                shape = RoundedCornerShape(24.dp)
                                alpha = 1 - iconProgress
                                scaleX = 1 - (iconProgress * 0.05f)
                                scaleY = 1 - (iconProgress * 0.05f)
                            }
                            .background(Color.White),
                    ) {
                        Image(
                            modifier = Modifier.size(74.dp).clip(RoundedCornerShape(18.dp)),
                            painter = launcherIcon,
                            contentDescription = null,
                        )
                    }
                    Text(
                        modifier = Modifier
                            .padding(top = 24.dp, bottom = 10.dp)
                            .graphicsLayer {
                                val projectNameProgress = ((scrollProgressProvider() - 0.20f) / 0.15f).coerceIn(0f, 1f)
                                alpha = 1 - projectNameProgress
                                scaleX = 1 - (projectNameProgress * 0.05f)
                                scaleY = 1 - (projectNameProgress * 0.05f)
                            },
                        text = appName,
                        color = MiuixTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 35.sp,
                    )
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                val versionCodeProgress = ((scrollProgressProvider() - 0.05f) / 0.15f).coerceIn(0f, 1f)
                                alpha = 1 - versionCodeProgress
                                scaleX = 1 - (versionCodeProgress * 0.05f)
                                scaleY = 1 - (versionCodeProgress * 0.05f)
                            },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        text = versionLabel,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item(key = "heroSpacing") {
                Spacer(modifier = Modifier.height(96.dp))
            }
            item(key = "about") {
                Column(modifier = Modifier.padding(bottom = 24.dp)) {
                    rows()
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(lazyListState),
            modifier = Modifier.fillMaxSize().padding(end = 2.dp),
            trackPadding = PaddingValues(vertical = 16.dp),
        )
    }
}
