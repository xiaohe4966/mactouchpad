package com.mactouchpad.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 全屏触控板视图：把整个屏幕当作 Mac 触控板。
 *
 * 手势映射：
 *  单指滑动        → 移动指针（相对移动）
 *  单指轻点        → 左键单击
 *  单指长按 280ms  → 按住左键（随后移动即拖拽）
 *  双指滑动        → 滚动（自然滚动方向）
 *  双指轻点        → 右键单击
 *  三指上滑        → 调度中心
 *  三指下滑        → 显示桌面
 *  三指左/右滑     → 切换桌面
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---- 灵敏度 / 阈值（可按手感调整） ----
    private val moveSensitivity = 1.6f     // 单指移动 → 指针位移倍数
    private val scrollSensitivity = 0.7f   // 双指移动 → 滚动倍数
    private val clickSlop = dp(8f)         // 点击判定最大位移（超过视为移动）
    private val scrollStartSlop = dp(6f)   // 双指开始滚动所需累计位移
    private val gestureSlop = dp(50f)      // 三指手势判定最小滑动距离
    private val longPressMs = 280L         // 长按进入拖拽的时长
    private val tapTimeoutMs = 400L        // 轻点判定的最大按压时长

    private data class Pointer(
        var downX: Float,
        var downY: Float,
        var lastX: Float,
        var lastY: Float,
        var moved: Float
    )

    private enum class Mode { IDLE, SINGLE, SCROLL, THREE }

    private val pointers = LinkedHashMap<Int, Pointer>()
    private var mode = Mode.IDLE
    private var pressStart = 0L
    private var buttonDown = false          // 左键已按下（拖拽中）
    private var rightClickPending = false   // 双指轻点候选 → 最后一指抬起触发右键
    private var twoTapPending = false       // 双指阶段尚未移动（轻点候选）
    private var twoMoveDist = 0f            // 双指阶段累计位移
    private var lastMidX: Float? = null     // 双指滚动上一帧中点
    private var lastMidY: Float? = null
    private var threeGesturePending = false // 曾进入三指模式 → 最后一指抬起触发手势
    private var threeFingers = 0            // 进入三指模式时的实际手指数（区分三指/四指）
    private var threeStartX = 0f
    private var threeStartY = 0f
    private var threeEndX = 0f
    private var threeEndY = 0f

    /** 单指长按定时器：按住不动到点后按下左键，进入拖拽 */
    private val longPressRunnable = Runnable {
        if (mode == Mode.SINGLE && pointers.size == 1 && !buttonDown) {
            val p = pointers.values.firstOrNull()
            if (p != null && p.moved < clickSlop) {
                buttonDown = true
                ConnectionManager.send(Protocol.leftDown())
            }
        }
    }

    private val hintPaint = Paint().apply {
        color = Color.argb(90, 255, 255, 255)
        textSize = dp(13f)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    // ---------------------------------------------------------------- 事件入口

    override fun onTouchEvent(event: MotionEvent): Boolean {
        Log.d("MacTouchpad", "onTouchEvent action=${event.actionMasked} pointers=${event.pointerCount} x=${event.x} y=${event.y}")
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                addPointer(event, event.actionIndex)
                transition(pointers.size)
                postDelayed(longPressRunnable, longPressMs)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPressRunnable)
                addPointer(event, event.actionIndex)
                transition(pointers.size)
            }
            MotionEvent.ACTION_MOVE -> {
                when (pointers.size) {
                    1 -> handleOneMove(event)
                    2 -> handleTwoMove(event)
                    else -> handleThreeMove(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                removeCallbacks(longPressRunnable)
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val wasCount = pointers.size
                pointers.remove(pid)
                if (wasCount == 2 && twoTapPending) {
                    // 双指都未移动且已抬起一指 → 等最后一指抬起发右键
                    rightClickPending = true
                }
                if (wasCount >= 3) {
                    // 三指抬起一指 → 手势判定交给最后一指抬起
                    threeGesturePending = true
                }
                transition(pointers.size)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                onLastFingerUp(event)
                resetAll()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                resetAll()
            }
        }
        return true
    }

    // ---------------------------------------------------------------- 手势处理

    private fun addPointer(event: MotionEvent, index: Int) {
        val pid = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)
        pointers[pid] = Pointer(downX = x, downY = y, lastX = x, lastY = y, moved = 0f)
        pressStart = SystemClock.uptimeMillis()
    }

    /** 指针数量变化时切换模式并重置相关基准 */
    private fun transition(to: Int) {
        when {
            to >= 3 -> {
                if (buttonDown) { ConnectionManager.send(Protocol.leftUp()); buttonDown = false }
                mode = Mode.THREE
                rightClickPending = false
                twoTapPending = false
                threeGesturePending = true
                threeFingers = to
                val (mx, my) = currentMid()
                threeStartX = mx; threeStartY = my
                threeEndX = mx; threeEndY = my
            }
            to == 2 -> {
                if (buttonDown) { ConnectionManager.send(Protocol.leftUp()); buttonDown = false }
                mode = Mode.SCROLL
                twoTapPending = true
                twoMoveDist = 0f
                lastMidX = null; lastMidY = null
                rightClickPending = false
                resetAllPointerBases()
            }
            to == 1 -> {
                mode = Mode.SINGLE
                twoTapPending = false
                resetSingleBase()
            }
            else -> mode = Mode.IDLE
        }
    }

    private fun handleOneMove(event: MotionEvent) {
        // 三指手势收尾阶段：剩余单指移动只更新手势终点，不发送指针事件
        if (threeGesturePending) {
            updateThreeEnd(event)
            return
        }
        val pid = pointers.keys.firstOrNull() ?: return
        val idx = event.findPointerIndex(pid)
        if (idx < 0) return
        val p = pointers[pid] ?: return
        val x = event.getX(idx)
        val y = event.getY(idx)
        val dx = x - p.lastX
        val dy = y - p.lastY
        p.lastX = x
        p.lastY = y
        p.moved += abs(dx) + abs(dy)

        if (p.moved < clickSlop) return          // 点击候选阶段，不发移动
        if (rightClickPending) rightClickPending = false

        ConnectionManager.send(
            Protocol.move((dx * moveSensitivity).toInt(), (dy * moveSensitivity).toInt())
        )
    }

    private fun handleTwoMove(event: MotionEvent) {
        // 三指手势收尾阶段：剩余两指移动只更新手势终点，不发送滚动
        if (threeGesturePending) {
            updateThreeEnd(event)
            return
        }
        if (pointers.size != 2) return
        val p0 = pointers.keys.elementAt(0)
        val p1 = pointers.keys.elementAt(1)
        val i0 = event.findPointerIndex(p0)
        val i1 = event.findPointerIndex(p1)
        if (i0 < 0 || i1 < 0) return

        val x0 = event.getX(i0); val y0 = event.getY(i0)
        val x1 = event.getX(i1); val y1 = event.getY(i1)
        updatePointer(p0, x0, y0)
        updatePointer(p1, x1, y1)

        val mx = (x0 + x1) / 2f
        val my = (y0 + y1) / 2f
        val lx = lastMidX
        val ly = lastMidY
        if (lx == null || ly == null) {
            lastMidX = mx; lastMidY = my
            return
        }
        val dx = mx - lx
        val dy = my - ly
        lastMidX = mx; lastMidY = my

        twoMoveDist += abs(dx) + abs(dy)
        if (twoMoveDist < scrollStartSlop) return // 未到滚动门槛，保持轻点候选
        twoTapPending = false
        ConnectionManager.send(
            Protocol.scroll((dx * scrollSensitivity).toInt(), (-dy * scrollSensitivity).toInt())
        )
    }

    private fun handleThreeMove(event: MotionEvent) {
        updateThreeEnd(event)
        // 三指滑动期间不发送任何指针事件，只在抬起时触发系统手势
    }

    private fun onLastFingerUp(event: MotionEvent) {
        val now = SystemClock.uptimeMillis()
        when {
            threeGesturePending -> {
                updateThreeEnd(event)
                threeGesturePending = false
                val dx = threeEndX - threeStartX
                val dy = threeEndY - threeStartY
                if (abs(dx) >= gestureSlop || abs(dy) >= gestureSlop) {
                    val id = if (abs(dy) > abs(dx)) {
                        if (dy < 0) {
                            // 上滑：三指=调度中心，四指=启动台
                            if (threeFingers >= 4) Protocol.GESTURE_LAUNCHPAD
                            else Protocol.GESTURE_MISSION_CONTROL
                        } else Protocol.GESTURE_SHOW_DESKTOP
                    } else {
                        if (dx < 0) Protocol.GESTURE_SWITCH_LEFT
                        else Protocol.GESTURE_SWITCH_RIGHT
                    }
                    ConnectionManager.send(Protocol.gesture(id))
                }
            }
            rightClickPending -> {
                rightClickPending = false
                ConnectionManager.send(Protocol.rightDown())
                ConnectionManager.send(Protocol.rightUp())
            }
            mode == Mode.SINGLE -> {
                val p = pointers.values.firstOrNull()
                if (!buttonDown && p != null && p.moved < clickSlop && now - pressStart < tapTimeoutMs) {
                    ConnectionManager.send(Protocol.leftDown())   // 轻点 → 左键单击
                    ConnectionManager.send(Protocol.leftUp())
                } else if (buttonDown) {
                    buttonDown = false
                    ConnectionManager.send(Protocol.leftUp())     // 拖拽结束
                }
            }
        }
    }

    private fun updateThreeEnd(event: MotionEvent) {
        val n = event.pointerCount
        if (n == 0) return
        var sx = 0f; var sy = 0f
        for (i in 0 until n) {
            sx += event.getX(i); sy += event.getY(i)
        }
        threeEndX = sx / n
        threeEndY = sy / n
    }

    private fun resetAll() {
        pointers.clear()
        mode = Mode.IDLE
        rightClickPending = false
        twoTapPending = false
        threeGesturePending = false
        threeFingers = 0
        lastMidX = null; lastMidY = null
        if (buttonDown) {
            buttonDown = false
            ConnectionManager.send(Protocol.leftUp())
        }
    }

    // ---------------------------------------------------------------- 辅助

    private fun updatePointer(pid: Int, x: Float, y: Float) {
        val p = pointers[pid] ?: return
        p.moved += abs(x - p.lastX) + abs(y - p.lastY)
        p.lastX = x
        p.lastY = y
    }

    /** 重置所有指针基准：进入双指模式时以当前位置为新起点，避免跳变 */
    private fun resetAllPointerBases() {
        for (p in pointers.values) {
            p.downX = p.lastX; p.downY = p.lastY
            p.moved = 0f
        }
        pressStart = SystemClock.uptimeMillis()
    }

    /** 回到单指时重置基准，重新计时点击 / 长按 */
    private fun resetSingleBase() {
        val p = pointers.values.firstOrNull() ?: return
        p.downX = p.lastX; p.downY = p.lastY
        p.moved = 0f
        pressStart = SystemClock.uptimeMillis()
    }

    private fun currentMid(): Pair<Float, Float> {
        var sx = 0f; var sy = 0f
        for (p in pointers.values) {
            sx += p.downX; sy += p.downY
        }
        val n = pointers.size
        return if (n == 0) 0f to 0f else sx / n to sy / n
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawText(
            "单指滑动·移动    轻点·单击    长按拖动·拖拽    双指滑动·滚动    双指轻点·右键    三指滑动·系统手势",
            width / 2f, height - dp(22f), hintPaint
        )
    }
}
