package com.zsz.studyassistant.data

/**
 * 拍照页「单击对焦 + 自动调曝光」的**纯计算部分**（2026-10-11 用户要求）。
 *
 * 背景：用户反馈**拍电脑屏幕时容易过曝、字看不清**。屏幕本身很亮，相机默认按整幅画面测光时
 * 容易把白色区域拍到纯白（高光溢出），黑字反而糊掉。做法是：
 *   ① 单击时把 **AF + AE（+AWB）测光区域**都放到点击点（见 `ui/CameraScreen.kt`）；
 *   ② 之后 ~1.2 秒内看**预览帧的实际亮度**，过曝就把曝光补偿（EV）往下压、太暗就往上抬。
 *
 * 这里只放"看一帧算一次"的逻辑，方便单测（不依赖 CameraX / Android）：
 *   · [lumaStats]：从 YUV 的 Y 平面（亮度）统计**平均亮度**与**近白像素占比**（过曝指标）；
 *   · [autoExposureDeltaEv]：根据这两个指标决定这一步要调整多少 EV。
 */

/** 一帧亮度统计：mean = 平均亮度(0~255)，clipRatio = 近白像素占比(0~1) */
data class FrameLuma(val mean: Double, val clipRatio: Double)

/** 判定"近白/溢出"的亮度阈值：>= 这个值就算快要拍成纯白了 */
const val LUMA_CLIP_THRESHOLD: Int = 250

/** 严重过曝：近白像素超过这个比例 → 一次多压一点 EV */
const val CLIP_RATIO_SEVERE: Double = 0.15

/** 轻度过曝：近白像素超过这个比例就压 EV */
const val CLIP_RATIO_MILD: Double = 0.04

/** 平均亮度上限：超过就认为整体偏亮（画面以亮部为主） */
const val LUMA_TOO_BRIGHT: Double = 195.0

/** 平均亮度下限：低于就认为太暗（拍出来一片黑）→ 抬 EV */
const val LUMA_TOO_DARK: Double = 60.0

/** 每一步的 EV 调整量（设备支持的话一次调 1/4 EV） */
const val EV_STEP_MILD: Double = 0.25

/** 严重过曝时一次压 1/2 EV，收敛更快 */
const val EV_STEP_SEVERE: Double = 0.5

/** EV 自动调整的上下限（避免越调越偏；真正的范围还会被设备能力夹一次） */
const val EV_AUTO_MIN: Double = -2.0
const val EV_AUTO_MAX: Double = 2.0

/**
 * 统计 Y 平面（YUV_420_888 的 Y）亮度。
 *
 * @param y          Y 平面字节（每个字节 = 一个像素亮度）
 * @param width      图像宽（像素）
 * @param height     图像高（像素）
 * @param rowStride  Y 平面每行**实际**字节数（可能 > width，右侧有填充，必须跳过，否则会读到垃圾数据）
 * @param step       采样步长（每隔 step 个像素取一个，统计足够准又足够快）
 * @param clipThreshold 近白判定阈值
 */
fun lumaStats(
    y: ByteArray,
    width: Int,
    height: Int,
    rowStride: Int,
    step: Int = 8,
    clipThreshold: Int = LUMA_CLIP_THRESHOLD
): FrameLuma {
    if (width <= 0 || height <= 0 || step <= 0) return FrameLuma(0.0, 0.0)
    val stride = if (rowStride > 0) rowStride else width
    var sum = 0L
    var count = 0L
    var clipped = 0L
    var row = 0
    while (row < height) {
        val base = row * stride
        var col = 0
        while (col < width) {
            val idx = base + col
            if (idx < y.size) {
                val v = y[idx].toInt() and 0xFF
                sum += v
                if (v >= clipThreshold) clipped++
                count++
            }
            col += step
        }
        row += step
    }
    if (count == 0L) return FrameLuma(0.0, 0.0)
    return FrameLuma(mean = sum.toDouble() / count.toDouble(), clipRatio = clipped.toDouble() / count.toDouble())
}

/**
 * 根据当前一帧的亮度，算出这一步应该调整多少 EV。
 *
 * 规则（都在上面的常量里）：
 *   · 近白像素很多（> [CLIP_RATIO_SEVERE]）→ 压 [EV_STEP_SEVERE]（过曝严重，快点压下来）
 *   · 近白像素偏多（> [CLIP_RATIO_MILD]）或整体偏亮 → 压 [EV_STEP_MILD]
 *   · 整体太暗（< [LUMA_TOO_DARK]）→ 抬 [EV_STEP_MILD]
 *   · 其余情况 → 0（不动，避免来回抖）
 */
fun autoExposureDeltaEv(mean: Double, clipRatio: Double): Double = when {
    clipRatio > CLIP_RATIO_SEVERE -> -EV_STEP_SEVERE
    clipRatio > CLIP_RATIO_MILD || mean > LUMA_TOO_BRIGHT -> -EV_STEP_MILD
    mean < LUMA_TOO_DARK -> EV_STEP_MILD
    else -> 0.0
}

/**
 * 把"当前 EV + 这一步的调整量"夹到自动调整范围与设备支持范围里。
 * @param currentEv 当前曝光补偿（单位 EV）
 * @param deltaEv   [autoExposureDeltaEv] 给出的调整量
 * @param deviceMinEv / deviceMaxEv 设备支持的 EV 范围
 */
fun clampAutoEv(currentEv: Double, deltaEv: Double, deviceMinEv: Double, deviceMaxEv: Double): Double {
    // 设备上报的范围异常（下限高于上限）→ 不敢乱调，保持当前值（coerceIn 会因空区间抛异常）
    if (deviceMinEv > deviceMaxEv) return currentEv
    val lo = maxOf(deviceMinEv, EV_AUTO_MIN)
    val hi = minOf(deviceMaxEv, EV_AUTO_MAX)
    if (lo > hi) return currentEv.coerceIn(deviceMinEv, deviceMaxEv)   // 自动范围与设备范围不重叠 → 用设备的
    return (currentEv + deltaEv).coerceIn(lo, hi)
}
