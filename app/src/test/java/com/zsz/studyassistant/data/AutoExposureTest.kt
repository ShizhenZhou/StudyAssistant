package com.zsz.studyassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拍照页「自动曝光」纯逻辑的回归测试（2026-10-11）。
 *
 * 为什么测这里：曝光补偿调错方向会**越调越糟**（过曝还往上抬），而这些阈值/夹紧逻辑
 * 在真机上很难逐条复现（要真的对着亮屏/暗处拍）。这里用合成 Y 平面把边界钉死。
 */
class AutoExposureTest {

    /** 造一个纯色 Y 平面（rowStride = width） */
    private fun plane(value: Int, w: Int, h: Int): ByteArray =
        ByteArray(w * h) { value.toByte() }

    @Test
    fun `全白画面：平均亮度 255、近白占比 100%`() {
        val st = lumaStats(plane(255, 64, 48), width = 64, height = 48, rowStride = 64)
        assertEquals(255.0, st.mean, 0.001)
        assertEquals(1.0, st.clipRatio, 0.001)
    }

    @Test
    fun `全黑画面：平均亮度 0、没有近白像素`() {
        val st = lumaStats(plane(0, 64, 48), width = 64, height = 48, rowStride = 64)
        assertEquals(0.0, st.mean, 0.001)
        assertEquals(0.0, st.clipRatio, 0.001)
    }

    @Test
    fun `一半白一半黑：平均约 127、近白占比约 50%`() {
        val w = 64; val h = 64
        val y = ByteArray(w * h) { if (it % w < w / 2) 255.toByte() else 0 }
        val st = lumaStats(y, width = w, height = h, rowStride = w)
        assertEquals(127.5, st.mean, 1.0)
        assertEquals(0.5, st.clipRatio, 0.02)
    }

    @Test
    fun `行填充字节必须跳过（rowStride 大于 width 时不能把填充当像素）`() {
        // 有效区域全黑，填充区故意写成 255：若把填充读进来，mean 会明显大于 0
        val w = 32; val h = 32; val stride = 48
        val y = ByteArray(stride * h) { 255.toByte() }
        for (row in 0 until h) for (col in 0 until w) y[row * stride + col] = 0
        val st = lumaStats(y, width = w, height = h, rowStride = stride)
        assertEquals(0.0, st.mean, 0.001)
        assertEquals(0.0, st.clipRatio, 0.001)
    }

    @Test
    fun `非法尺寸不会崩，返回空统计`() {
        val st = lumaStats(ByteArray(0), width = 0, height = 0, rowStride = 0)
        assertEquals(0.0, st.mean, 0.001)
        assertEquals(0.0, st.clipRatio, 0.001)
    }

    @Test
    fun `严重过曝：一次压半档 EV`() {
        assertEquals(-EV_STEP_SEVERE, autoExposureDeltaEv(mean = 250.0, clipRatio = 0.3), 0.001)
    }

    @Test
    fun `轻度过曝与整体偏亮：压四分之一档 EV`() {
        assertEquals(-EV_STEP_MILD, autoExposureDeltaEv(mean = 150.0, clipRatio = 0.06), 0.001)
        assertEquals(-EV_STEP_MILD, autoExposureDeltaEv(mean = 210.0, clipRatio = 0.0), 0.001)
    }

    @Test
    fun `画面太暗：抬四分之一档 EV`() {
        assertEquals(EV_STEP_MILD, autoExposureDeltaEv(mean = 30.0, clipRatio = 0.0), 0.001)
    }

    @Test
    fun `正常画面：不动（避免来回抖）`() {
        assertEquals(0.0, autoExposureDeltaEv(mean = 128.0, clipRatio = 0.01), 0.001)
        // 边界：刚好等于阈值也算"不动"
        assertEquals(0.0, autoExposureDeltaEv(mean = 195.0, clipRatio = 0.04), 0.001)
        assertEquals(0.0, autoExposureDeltaEv(mean = 60.0, clipRatio = 0.0), 0.001)
    }

    @Test
    fun `EV 夹在 ±2 与设备范围内`() {
        // 设备范围更窄 → 用设备的
        assertEquals(-1.0, clampAutoEv(currentEv = -1.0, deltaEv = -0.25, deviceMinEv = -1.0, deviceMaxEv = 1.0), 0.001)
        // 设备范围更宽 → 用自动范围 ±2
        assertEquals(-2.0, clampAutoEv(currentEv = -1.9, deltaEv = -0.5, deviceMinEv = -3.0, deviceMaxEv = 3.0), 0.001)
        assertEquals(2.0, clampAutoEv(currentEv = 1.9, deltaEv = 0.25, deviceMinEv = -3.0, deviceMaxEv = 3.0), 0.001)
        // 正常调整
        assertEquals(-0.5, clampAutoEv(currentEv = -0.25, deltaEv = -0.25, deviceMinEv = -2.0, deviceMaxEv = 2.0), 0.001)
    }

    @Test
    fun `设备范围异常（下限高于上限）时不越界`() {
        val v = clampAutoEv(currentEv = 0.5, deltaEv = 0.25, deviceMinEv = 1.0, deviceMaxEv = -1.0)
        assertTrue("结果应落在设备范围内或等于当前值：$v", v <= 1.0 && v >= -1.0)
    }
}
