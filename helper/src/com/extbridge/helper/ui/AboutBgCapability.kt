package com.extbridge.helper.ui

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * Shader capability gate for the about-page background.
 *
 * This app ships one APK with `minSdk 30`, while `miuix-blur` declares `minSdk 33`.
 * The documented single-APK approach is: keep the app's minSdk, declare
 * `tools:overrideLibrary` for the blur module, and never touch shader/backdrop APIs
 * unless the capability check passes — then draw an equivalent themed fallback.
 */
internal object AboutBgCapability {

    @ChecksSdkIntAtLeast(Build.VERSION_CODES.TIRAMISU)
    fun runtimeShaderSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
