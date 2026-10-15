package com.mactouchpad.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 与 Mac 端 server.swift 完全一致的二进制协议（大端序）。
 *
 *  0x01 MOVE        int16 dx, int16 dy     相对移动（已乘灵敏度）
 *  0x02 LEFT_DOWN   （无载荷）
 *  0x03 LEFT_UP     （无载荷）
 *  0x04 RIGHT_DOWN  （无载荷）
 *  0x05 RIGHT_UP    （无载荷）
 *  0x06 SCROLL      int16 dx, int16 dy     dy>0 = 手指下滑（内容向下）
 *  0x07 GESTURE     int8 id                1=调度中心 2=上一桌面 3=下一桌面 4=显示桌面
 */
object Protocol {

    const val MSG_MOVE = 1
    const val MSG_LEFT_DOWN = 2
    const val MSG_LEFT_UP = 3
    const val MSG_RIGHT_DOWN = 4
    const val MSG_RIGHT_UP = 5
    const val MSG_SCROLL = 6
    const val MSG_GESTURE = 7

    const val GESTURE_MISSION_CONTROL = 1 // 三指上滑 → 调度中心
    const val GESTURE_SWITCH_LEFT = 2     // 三指左滑 → 上一桌面
    const val GESTURE_SWITCH_RIGHT = 3    // 三指右滑 → 下一桌面
    const val GESTURE_SHOW_DESKTOP = 4    // 三指下滑 → 显示桌面
    const val GESTURE_LAUNCHPAD = 5       // 四指上滑 → 启动台

    fun move(dx: Int, dy: Int): ByteArray =
        encode(MSG_MOVE) {
            putShort(clampShort(dx))
            putShort(clampShort(dy))
        }

    fun leftDown(): ByteArray = ByteArray(1) { MSG_LEFT_DOWN.toByte() }
    fun leftUp(): ByteArray = ByteArray(1) { MSG_LEFT_UP.toByte() }
    fun rightDown(): ByteArray = ByteArray(1) { MSG_RIGHT_DOWN.toByte() }
    fun rightUp(): ByteArray = ByteArray(1) { MSG_RIGHT_UP.toByte() }

    fun scroll(dx: Int, dy: Int): ByteArray =
        encode(MSG_SCROLL) {
            putShort(clampShort(dx))
            putShort(clampShort(dy))
        }

    fun gesture(id: Int): ByteArray =
        encode(MSG_GESTURE) {
            put(id.toByte())
        }

    private fun clampShort(v: Int): Short =
        v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private inline fun encode(type: Int, body: ByteBuffer.() -> Unit): ByteArray {
        val size = when (type) {
            MSG_MOVE, MSG_SCROLL -> 5
            MSG_GESTURE -> 2
            else -> 1
        }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.put(type.toByte())
        buf.body()
        return buf.array()
    }
}
