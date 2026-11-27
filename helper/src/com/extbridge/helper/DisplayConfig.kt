package com.extbridge.helper

/** Pure editing model: no Binder, Android or UI dependencies; shared with regression tests. */
data class DisplayConfig(
    val w: Int = 2560, val h: Int = 1440, val dpi: Int = 240,
    val fps: Float = 60f, val rotation: Int = 0, val physicalModeId: Int = -1,
) {
    fun valid() = maxOf(w, h) in 640..7680 && minOf(w, h) in 480..4320 &&
        dpi in 80..640 && fps.isFinite() && fps in 1f..240f && rotation in 0..3
    fun rotated(next: Int): DisplayConfig {
        val portrait = next and 1 == 1
        return if ((portrait && w > h) || (!portrait && h > w))
            copy(w = h, h = w, rotation = next)
        else copy(rotation = next)
    }
    fun resolution(width: Int, height: Int): DisplayConfig {
        val portrait = rotation and 1 == 1
        return copy(w = if (portrait) minOf(width, height) else maxOf(width, height),
            h = if (portrait) maxOf(width, height) else minOf(width, height), physicalModeId = -1)
    }
    val smallestWidthDp: Int get() = (minOf(w, h) * 160f / dpi.coerceAtLeast(80)).toInt()
}

data class PhysicalMode(val id: Int, val w: Int, val h: Int, val fps: Float)

fun parsePhysicalModes(raw: String): List<PhysicalMode> = raw.split(';').mapNotNull { item ->
    val match = Regex("(\\d+):(\\d+)x(\\d+)@([0-9.]+)").matchEntire(item.trim()) ?: return@mapNotNull null
    val (idText, wText, hText, fpsText) = match.destructured
    val id = idText.toIntOrNull() ?: return@mapNotNull null
    val w = wText.toIntOrNull() ?: return@mapNotNull null
    val h = hText.toIntOrNull() ?: return@mapNotNull null
    val fps = fpsText.toFloatOrNull() ?: return@mapNotNull null
    if (maxOf(w, h) < 640 || minOf(w, h) < 480 || !fps.isFinite() || fps <= 0) null
    else PhysicalMode(id, w, h, fps)
}

fun recommendedConfig(raw: String, fallbackW: Int, fallbackH: Int, fallbackFps: Float): DisplayConfig {
    val modes = parsePhysicalModes(raw)
    val eligible = if (modes.any { it.fps >= 49.5f }) modes.filter { it.fps >= 49.5f } else modes
    val best = eligible.maxWithOrNull(compareBy<PhysicalMode> { it.w.toLong() * it.h }
        .thenBy { minOf(it.fps, 120f) })
    val w = best?.w ?: fallbackW.takeIf { it >= 640 } ?: 2560
    val h = best?.h ?: fallbackH.takeIf { it >= 480 } ?: 1440
    val dpi = when { minOf(w, h) >= 2000 -> 240; minOf(w, h) >= 1520 -> 200; else -> 160 }
    return DisplayConfig(w, h, dpi, best?.fps ?: fallbackFps.takeIf { it.isFinite() && it > 10 } ?: 60f,
        physicalModeId = best?.id ?: -1)
}
