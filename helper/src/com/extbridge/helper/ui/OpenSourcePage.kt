package com.extbridge.helper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

const val EXTBRIDGE_GITHUB_URL = "https://github.com/duppjuzo/ExtBridge"

private data class OpenSourceProject(
    val name: String,
    val usage: String,
    val githubUrl: String,
    val licenseUrl: String,
    val license: String = "Apache-2.0",
)

private val openSourceProjects = listOf(
    OpenSourceProject(
        "Miuix", "界面组件、图标、主题、模糊与导航 · 0.9.4",
        "https://github.com/compose-miuix-ui/miuix",
        "https://github.com/compose-miuix-ui/miuix/blob/HEAD/LICENSE",
    ),
    OpenSourceProject(
        "AndroidLiquidGlass", "液态玻璃效果与交互辅助源码 · Copyright 2025 Kyant",
        "https://github.com/Kyant0/AndroidLiquidGlass",
        "https://github.com/Kyant0/AndroidLiquidGlass/blob/HEAD/LICENSE",
    ),
    OpenSourceProject(
        "Compose Multiplatform", "Compose UI 与布局 · 1.12.0",
        "https://github.com/JetBrains/compose-multiplatform",
        "https://github.com/JetBrains/compose-multiplatform/blob/HEAD/LICENSE.txt",
    ),
    OpenSourceProject(
        "AndroidX", "Activity、Compose、生命周期与 Android 兼容组件",
        "https://github.com/androidx/androidx",
        "https://github.com/androidx/androidx/blob/HEAD/LICENSE.txt",
    ),
    OpenSourceProject(
        "Kotlin", "Kotlin 语言与标准库",
        "https://github.com/JetBrains/kotlin",
        "https://github.com/JetBrains/kotlin/blob/HEAD/license/LICENSE.txt",
    ),
    OpenSourceProject(
        "kotlinx.coroutines", "协程与异步任务",
        "https://github.com/Kotlin/kotlinx.coroutines",
        "https://github.com/Kotlin/kotlinx.coroutines/blob/HEAD/LICENSE.txt",
    ),
    OpenSourceProject(
        "kotlinx.serialization", "Miuix 导航依赖的序列化支持",
        "https://github.com/Kotlin/kotlinx.serialization",
        "https://github.com/Kotlin/kotlinx.serialization/blob/HEAD/LICENSE.txt",
    ),
    OpenSourceProject(
        "kotlinx.collections.immutable", "Miuix 导航依赖的不可变集合",
        "https://github.com/Kotlin/kotlinx.collections.immutable",
        "https://github.com/Kotlin/kotlinx.collections.immutable/blob/HEAD/LICENSE.txt",
    ),
    OpenSourceProject(
        "Android Debug Bridge (ADB)", "内置无线调试客户端；链接为 AOSP 源码的 LineageOS 镜像",
        "https://github.com/LineageOS/android_packages_modules_adb",
        "https://github.com/LineageOS/android_packages_modules_adb/blob/HEAD/NOTICE",
    ),
)

@Composable
fun OpenSourcePage(wide: Boolean, onBack: () -> Unit, onOpenLink: (String) -> Unit) {
    val layoutDirection = LocalLayoutDirection.current
    Scaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            SmallTopAppBar(
                title = "关于开源",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Back,
                            contentDescription = "返回",
                            modifier = Modifier.graphicsLayer {
                                scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = if (wide) 32.dp else 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(openSourceProjects, key = { it.githubUrl }) { project ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(title = project.name, summary = project.usage)
                    ArrowPreference(
                        title = "GitHub",
                        summary = project.githubUrl.removePrefix("https://"),
                        onClick = { onOpenLink(project.githubUrl) },
                    )
                    ArrowPreference(
                        title = "开源协议",
                        summary = project.license,
                        onClick = { onOpenLink(project.licenseUrl) },
                    )
                }
            }
        }
    }
}
