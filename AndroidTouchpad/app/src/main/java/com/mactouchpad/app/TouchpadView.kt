package com.mactouchpad.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 全屏触控板视图：把整个屏幕当作 Mac 触控板。
 *
 * 手势映射（仿 Apple 触控板）：
 *   单指滑动        → 移动指针（相对移动）
 *   单指轻点        → 左键单击
 *   单指长按 280ms  → 按住左键（随后移动即拖拽）
 *   双指滑动        → 滚动（自然滚动方向）
 *   双指轻点        → 右键单击
 *   三指慢拖        → 按住左键拖拽（拖窗口、选框等）—— Apple 的 "Three finger drag"
 *   三指快速滑动    → 系统手势（调度中心 / 显示桌面 / 切换桌面）
 *   四指 / 五指动作  → 系统手势（按方向：启动台 / 显示桌面 / 切换桌面）
 *
 * 模式判别：
 *   - 进入多指（≥3）手势时，记录当时的 centroid 作为手势起点，保持状态直到所有手指抬起。
 *   - 4 指及以上天然为 SWIPE。
 *   - 3 指在前 250ms 观察期内：若任一帧瞬时 centroid 速度 > [swipeVelocityDpPerSec]，判定为 SWIPE；
 *     否则判定为 DRAG（按住左键跟随 centroid 移动）。
 *   - 期间手指数上下变化（3→2→1 等）只是释放过程，不会打断手势状态。
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---- 灵敏度 / 阈值（可由手机上的设置面板微调） ----
    var moveSensitivity = 1.0f       // 单指移动 → 指针位移倍数（默认 1.0）
    var multiMoveScale = 1.6f        // 三指拖拽（centroid 移动）→ 指针位移倍数
    var scrollSensitivity = 0.7f     // 双指移动 → 滚动倍数
    private val clickSlop = dp(8f)           // 点击判定最大位移（超过视为移动）
    private val scrollStartSlop = dp(6f)     // 双指开始滚动所需累计位移
    private val gestureSlop = dp(50f)        // 多指手势判定最小滑动距离
    private val longPressMs = 280L           // 长按进入拖拽的时长
    private val tapTimeoutMs = 400L          // 轻点判定的最大按压时长
    private val swipeVelocityDpPerSec = 500f // 触发"滑动"模式的瞬时 centroid 速度阈值（dp/s）
    private val classifyWindowMs = 250L      // 三指模式判定观察窗口

    private data class Pointer(
        var downX: Float,
        var downY: Float,
        var lastX: Float,
        var lastY: Float,
        var moved: Float
    )

    private enum class Mode { IDLE, SINGLE, TWO, MULTI }
    private var mode = Mode.IDLE
    private val pointers = LinkedHashMap<Int, Pointer>()

    // ---- 单指 / 双指状态 ----
    private var pressStart = 0L
    private var buttonDown = false            // 单指长按后左键已按下
    private var rightClickPending = false     // 双指轻点候选（两指未移动、短按 → 右键）
    private var twoScrolled = false           // 本次双指手势是否已滚动（滚动收尾不触发单击）
    private var twoTapPending = false         // 双指阶段尚未移动
    private var twoMoveDist = 0f
    private var lastMidX: Float? = null
    private var lastMidY: Float? = null

    // ---- 旋转 ----
    /** 触摸坐标旋转角度（0/90/180/270）；用于在不同握持方向下保持手指方向与光标方向一致 */
    var rotationDegrees: Int = 0
        private set
    private val rotationMatrix = Matrix()

    /** 设置旋转角度（0/90/180/270 中的一个；其他值会规范化） */
    fun setRotationDegrees(deg: Int) {
        rotationDegrees = ((deg % 360) + 360) % 360
        invalidate()
    }

    // ---- 多指手势状态（贯穿手指数变化，直到 ACTION_UP） ----
    private enum class MultiClassify { NONE, DRAG, SWIPE }
    private var multiActive = false           // 是否曾进入多指（≥3）阶段
    private var multiMaxFingers = 0           // 本次手势的最大手指数（3 = 拖拽/滑动，≥4 = 滑动）
    private var multiStartX = 0f              // 手势起点（centroid），不再随 transition 重置
    private var multiStartY = 0f
    private var multiLastX = 0f               // 上一帧 centroid（用于瞬时速度）
    private var multiLastY = 0f
    private var multiLastTime = 0L
    private var multiFirstMoveTime = 0L       // 多指阶段第一次 MOVE 的时间
    private var multiLeftDown = false           // 三指拖拽中（左键已按下）
    private var multiClassified = MultiClassify.NONE
    private var pendingSwipeDir = 0            // SWIPE 模式下记录的方向（-1/0/1，x 维度），用于在手指数减少前补刀

    /** 长按定时器：单指按住不动到点后按下左键，进入拖拽 */
    private val longPressRunnable = Runnable {
        if (mode == Mode.SINGLE && pointers.size == 1 && !buttonDown && !multiActive) {
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

    private val statusPaint = Paint().apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = dp(14f)
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }

    /** 系统语言是否为中文（用于界面文字自动切换） */
    private val isChinese: Boolean =
        Locale.getDefault().language.startsWith("zh") ||
            resources.configuration.locales[0].language.startsWith("zh")

    private val hintText: String
        get() = if (isChinese)
            "单指·移动/单击    长按·拖拽    双指·滚动/右键    三指慢拖·拖窗口    三指快滑·调度中心/桌面    四指·启动台/桌面"
        else
            "1F·Move/Click    Hold·Drag    2F·Scroll/Right    3F Slow·Drag Win    3F Quick·Mission/Desk    4F·Launchpad/Desktop"

    /** 连接状态文字（顶栏），由 MainActivity 在连接 / 断开时更新 */
    var statusText: String = ""
        set(value) {
            field = value
            invalidate()
        }

    // ============================================================ 事件入口

    override fun onTouchEvent(event: MotionEvent): Boolean {
        Log.d("MacTouchpad", "onTouch action=${event.actionMasked} ptrs=${event.pointerCount} mode=$mode rot=$rotationDegrees")
        val e = if (rotationDegrees == 0) {
            event
        } else {
            // 按用户指定的旋转角度对触摸坐标做反向旋转：
            //   用户以"已旋转"的视角触摸 → 我们把坐标还原到 view 自身的坐标系里。
            //   这样长按 / 多指 / centroid 等都在 view 自己的坐标空间里计算。
            rotationMatrix.reset()
            rotationMatrix.postRotate(
                -rotationDegrees.toFloat(),
                width / 2f, height / 2f
            )
            val copy = MotionEvent.obtain(event)
            copy.transform(rotationMatrix)
            copy
        }
        return try {
            handleTouchEvent(e)
        } finally {
            if (e !== event) e.recycle()
        }
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                addPointer(event, event.actionIndex)
                enterSingle()
                postDelayed(longPressRunnable, longPressMs)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPressRunnable)
                addPointer(event, event.actionIndex)
                onFingerAdded()
            }
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event)
            MotionEvent.ACTION_UP -> handleLastUp(event)
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                resetAll()
            }
        }
        return true
    }

    // ============================================================ 状态切换

    /** ACTION_DOWN：从 IDLE 进入单指 */
    private fun enterSingle() {
        mode = Mode.SINGLE
        multiActive = false
        multiMaxFingers = 0
        multiClassified = MultiClassify.NONE
        multiFirstMoveTime = 0L
        rightClickPending = false
        twoScrolled = false
        twoTapPending = false
        lastMidX = null; lastMidY = null
        resetSingleBase()
    }

    /** ACTION_POINTER_DOWN：手指数变化 */
    private fun onFingerAdded() {
        val n = pointers.size
        when {
            n >= 3 -> {
                if (!multiActive) {
                    // 第一次跨入多指
                    multiActive = true
                    multiMaxFingers = n
                    multiClassified = MultiClassify.NONE
                    multiFirstMoveTime = 0L
                    val (mx, my) = centroidOfDown()
                    multiStartX = mx; multiStartY = my
                    multiLastX = mx; multiLastY = my
                    multiLastTime = SystemClock.uptimeMillis()
                    cancelPress()
                } else {
                    // 多指阶段又加了一根手指（3→4、3→5 等）：刷新 max 与 centroid 基准
                    multiMaxFingers = maxOf(multiMaxFingers, n)
                    val (mx, my) = centroidCurrent()
                    multiLastX = mx; multiLastY = my
                    multiLastTime = SystemClock.uptimeMillis()
                    multiFirstMoveTime = 0L
                }
                mode = Mode.MULTI
                twoTapPending = false
                rightClickPending = false
            }
            n == 2 -> {
                if (multiActive) {
                    // 多指 → 双指：继续等待所有手指抬起，期间不触发滚动 / 右键
                    mode = Mode.TWO
                    twoTapPending = false
                    rightClickPending = false
                    lastMidX = null; lastMidY = null
                } else {
                    mode = Mode.TWO
                    twoTapPending = true
                    twoMoveDist = 0f
                    lastMidX = null; lastMidY = null
                    rightClickPending = true      // 双指轻点候选：未移动且短按 → 右键
                    twoScrolled = false
                    resetAllPointerBases()
                }
            }
        }
    }

    /** ACTION_POINTER_UP：手指减少，可能仍在多指阶段 */
    private fun handlePointerUp(event: MotionEvent) {
        removeCallbacks(longPressRunnable)
        val idx = event.actionIndex
        val pid = event.getPointerId(idx)
        pointers.remove(pid)

        // 收尾阶段不调用判别（抬起手指时 centroid 会跳变，会被误判为快速滑动）；
        //   判别只在 handleMultiMove 看到真实位移时进行。
        // 但若 multiclass 已是 SWIPE/DRAG，则不再变动。
        if (multiActive) {
            multiFirstMoveTime = 0L  // 重置观察窗口，下一帧 MOVE 重新观察
        }

        when {
            pointers.size >= 3 -> mode = Mode.MULTI
            pointers.size == 2 -> {
                mode = Mode.TWO
                // 关键：来自多指时，禁用两指轻点候选，避免最后一指抬起误触发右键
                twoTapPending = false
                rightClickPending = false
                lastMidX = null; lastMidY = null
            }
            pointers.size == 1 -> {
                mode = Mode.SINGLE
                resetSingleBase()
                // 保留 rightClickPending：若双指未移动、短按抬起 → 在 ACTION_UP 时发右键
                // 若剩余手指随后移动，handleOneMove 会取消右键候选
                twoTapPending = false
            }
        }
    }

    /** ACTION_UP：最后一根手指抬起 */
    private fun handleLastUp(event: MotionEvent) {
        removeCallbacks(longPressRunnable)
        if (multiActive) {
            // 判别在 handleMultiMove 中已完成，此处只派发
            dispatchMulti()
        } else {
            dispatchSingleOrTwo()
        }
        resetAll()
    }

    /**
     * 多指手势的收尾：
     *   - 三指 DRAG → 发送 leftUp
     *   - 三指 SWIPE / 四指 SWIPE → 按 multiStart→当前 centroid 的方向派发系统手势
     */
    private fun dispatchMulti() {
        when (multiClassified) {
            MultiClassify.DRAG -> {
                if (multiLeftDown) {
                    multiLeftDown = false
                    ConnectionManager.send(Protocol.leftUp())
                }
            }
            MultiClassify.SWIPE -> {
                // 使用多指阶段最后 MOVE 的 centroid（而非抬手后剩余指针的位置），
                //   避免 3→2→1→0 时 centroid 被误拉到最后一指造成方向偏移
                val dx = multiLastX - multiStartX
                val dy = multiLastY - multiStartY
                if (abs(dx) < gestureSlop && abs(dy) < gestureSlop) return
                val gestureId = directionToGesture(dx, dy)
                ConnectionManager.send(Protocol.gesture(gestureId))
            }
            MultiClassify.NONE -> {
                // 收尾时仍未判定（极短的瞬触），按 DRAG 收尾（已按左键就松开，否则啥也不做）
                if (multiLeftDown) {
                    multiLeftDown = false
                    ConnectionManager.send(Protocol.leftUp())
                }
            }
        }
    }

    private fun directionToGesture(dx: Float, dy: Float): Int = when {
        abs(dy) > abs(dx) -> {
            if (dy < 0) {
                if (multiMaxFingers >= 4) Protocol.GESTURE_LAUNCHPAD
                else Protocol.GESTURE_MISSION_CONTROL   // 三指上滑 → 调度中心
            } else {
                Protocol.GESTURE_SHOW_DESKTOP            // 下滑 → 显示桌面
            }
        }
        dx < 0 -> Protocol.GESTURE_SWITCH_LEFT          // 上一桌面
        else    -> Protocol.GESTURE_SWITCH_RIGHT        // 下一桌面
    }

    private fun dispatchSingleOrTwo() {
        val now = SystemClock.uptimeMillis()
        if (rightClickPending) {
            // 双指轻点（两指未移动、短按）→ 右键
            rightClickPending = false
            ConnectionManager.send(Protocol.rightDown())
            ConnectionManager.send(Protocol.rightUp())
            return
        }
        // 双指滚动收尾：不触发单击，避免滚完页面误点
        if (twoScrolled) {
            twoScrolled = false
            return
        }
        if (mode == Mode.SINGLE) {
            val p = pointers.values.firstOrNull()
            if (!buttonDown && p != null && p.moved < clickSlop && now - pressStart < tapTimeoutMs) {
                ConnectionManager.send(Protocol.leftDown())
                ConnectionManager.send(Protocol.leftUp())
            } else if (buttonDown) {
                buttonDown = false
                ConnectionManager.send(Protocol.leftUp())
            }
        }
    }

    /**
     * 多指手势模式判别（基于瞬时 centroid 速度 + 观察窗口）：
     *   - 4+ 指 → SWIPE
     *   - 3 指：任一帧 centroid 速度 > 阈值 → SWIPE；窗口结束仍未触发 → DRAG
     */
    private fun classifyMultiIfPossible() {
        if (multiClassified != MultiClassify.NONE) return
        if (!multiActive) return
        if (multiMaxFingers >= 4) {
            multiClassified = MultiClassify.SWIPE
            return
        }
        val now = SystemClock.uptimeMillis()
        val dt = (now - multiLastTime).coerceAtLeast(1L)
        val (cx, cy) = centroidCurrent()
        val dist = hypot(cx - multiLastX, cy - multiLastY)
        val velocityDpPerSec = dist / dt * 1000f
        when {
            velocityDpPerSec >= swipeVelocityDpPerSec ->
                multiClassified = MultiClassify.SWIPE
            multiFirstMoveTime > 0 && now - multiFirstMoveTime >= classifyWindowMs ->
                multiClassified = MultiClassify.DRAG
        }
    }

    // ============================================================ MOVE 处理

    private fun handleMove(event: MotionEvent) {
        when (mode) {
            Mode.SINGLE -> handleOneMove(event)
            Mode.TWO -> handleTwoMove(event)
            Mode.MULTI -> handleMultiMove(event)
            else -> {}
        }
    }

    private fun handleOneMove(event: MotionEvent) {
        // 多指手势收尾阶段（已 lift 到 1 指）的最后移动：不发指针事件，避免漂移
        if (multiActive) return
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
        if (p.moved < clickSlop) return
        if (rightClickPending) rightClickPending = false
        // 增量节流发送：高频移动合并成包，WiFi 下更顺滑
        ConnectionManager.sendMoveThrottled(
            (dx * moveSensitivity).toInt(), (dy * moveSensitivity).toInt()
        )
    }

    private fun handleTwoMove(event: MotionEvent) {
        if (multiActive) return
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
        if (twoMoveDist < scrollStartSlop) return
        twoTapPending = false
        rightClickPending = false   // 已滚动，不再是右键轻点
        twoScrolled = true          // 本次手势滚动过：收尾不触发单击
        ConnectionManager.sendScrollThrottled(
            (dx * scrollSensitivity).toInt(), (-dy * scrollSensitivity).toInt()
        )
    }

    /**
     * 多指 MOVE：维护 centroid 速度、按模式应用效果。
     *   - DRAG：三指拖移，发 leftDown（一次）+ 按 centroid 增量发 MOVE
     *   - SWIPE：只跟踪，不发事件（最后一指抬起时按 multiStart→当前 centroid 派发）
     */
    private fun handleMultiMove(event: MotionEvent) {
        if (pointers.size < 3) return
        val (cx, cy) = centroidOfEvent(event)
        val now = SystemClock.uptimeMillis()

        // 首帧：仅初始化基准，不做判定（避免 POINTER_DOWN 后第一帧因 dt=0 速度爆炸）
        if (multiFirstMoveTime == 0L) {
            multiFirstMoveTime = now
            multiLastTime = now
            multiLastX = cx
            multiLastY = cy
            return
        }

        val dt = (now - multiLastTime).coerceAtLeast(1L)
        val dist = hypot(cx - multiLastX, cy - multiLastY)
        val velocityDpPerSec = dist / dt * 1000f

        // 模式判别
        if (multiClassified == MultiClassify.NONE) {
            if (multiMaxFingers >= 4) {
                multiClassified = MultiClassify.SWIPE
            } else if (velocityDpPerSec >= swipeVelocityDpPerSec) {
                multiClassified = MultiClassify.SWIPE
            } else if (now - multiFirstMoveTime >= classifyWindowMs) {
                multiClassified = MultiClassify.DRAG
            }
        }

        when (multiClassified) {
            MultiClassify.DRAG -> {
                if (!multiLeftDown) {
                    multiLeftDown = true
                    ConnectionManager.send(Protocol.leftDown())
                }
                val dx = cx - multiLastX
                val dy = cy - multiLastY
                if (abs(dx) > 0.5f || abs(dy) > 0.5f) {
                    ConnectionManager.sendMoveThrottled(
                        (dx * multiMoveScale).toInt(), (dy * multiMoveScale).toInt()
                    )
                }
            }
            MultiClassify.SWIPE -> { /* track only，释放时按方向派发 */ }
            MultiClassify.NONE -> { /* 还在观察窗口内 */ }
        }

        multiLastX = cx
        multiLastY = cy
        multiLastTime = now
    }

    // ============================================================ 辅助

    private fun addPointer(event: MotionEvent, index: Int) {
        val pid = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)
        pointers[pid] = Pointer(downX = x, downY = y, lastX = x, lastY = y, moved = 0f)
        pressStart = SystemClock.uptimeMillis()
    }

    /** 指针 down 位置的几何中心 */
    private fun centroidOfDown(): Pair<Float, Float> = avgPoint { it.downX to it.downY }

    /** 指针 last 位置的几何中心 */
    private fun centroidCurrent(): Pair<Float, Float> = avgPoint { it.lastX to it.lastY }

    /** 当前 event 中（仍在 pointers 里）的指针几何中心 */
    private fun centroidOfEvent(event: MotionEvent): Pair<Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0
        for (pid in pointers.keys) {
            val idx = event.findPointerIndex(pid)
            if (idx >= 0) {
                sx += event.getX(idx); sy += event.getY(idx); n++
            }
        }
        return if (n == 0) 0f to 0f else sx / n to sy / n
    }

    private inline fun avgPoint(sel: (Pointer) -> Pair<Float, Float>): Pair<Float, Float> {
        var sx = 0f; var sy = 0f
        for (p in pointers.values) {
            val (x, y) = sel(p); sx += x; sy += y
        }
        val n = pointers.size
        return if (n == 0) 0f to 0f else sx / n to sy / n
    }

    /** 结束当前按住状态（单指拖拽 / 三指拖移） */
    private fun cancelPress() {
        if (buttonDown || multiLeftDown) {
            ConnectionManager.send(Protocol.leftUp())
        }
        buttonDown = false
        multiLeftDown = false
    }

    private fun updatePointer(pid: Int, x: Float, y: Float) {
        val p = pointers[pid] ?: return
        p.moved += abs(x - p.lastX) + abs(y - p.lastY)
        p.lastX = x; p.lastY = y
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

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun resetAll() {
        pointers.clear()
        mode = Mode.IDLE
        rightClickPending = false
        twoTapPending = false
        twoScrolled = false
        multiActive = false
        multiMaxFingers = 0
        multiClassified = MultiClassify.NONE
        multiFirstMoveTime = 0L
        lastMidX = null; lastMidY = null
        if (buttonDown || multiLeftDown) {
            ConnectionManager.send(Protocol.leftUp())
        }
        buttonDown = false
        multiLeftDown = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        if (rotationDegrees == 0) {
            canvas.drawText(hintText, cx, height - dp(22f), hintPaint)
            canvas.drawText(statusText, cx, dp(28f), statusPaint)
        } else {
            // 跟着旋转角度画两行字，但旋转后依然保持"视觉居中"：
            //   文字目标位置 = 旋转后屏幕的底部中央（hint）/ 顶部中央（status）。
            //   先把目标锚点逆旋转到当前绘制坐标系，再交给 drawTextRotated 旋转回目标位置。
            //   drawTextRotated 内置自适应缩放：90°/270° 时文字宽度变成竖直方向，超出 view 高度按比例缩小。
            val theta = Math.toRadians(rotationDegrees.toDouble())
            val s = sin(theta).toFloat()
            val c = cos(theta).toFloat()
            val cy = height / 2f

            val hintTargetY = height - dp(22f)
            drawTextRotated(canvas, hintText, hintPaint,
                cx + (hintTargetY - cy) * s, cy + (hintTargetY - cy) * c)

            val statusTargetY = dp(28f)
            drawTextRotated(canvas, statusText, statusPaint,
                cx + (statusTargetY - cy) * s, cy + (statusTargetY - cy) * c)
        }
    }

    /**
     * 画一行"跟着旋转"的文字。
     *   1) 如需要（旋转 90°/270° 时宽度超界），先在 view 坐标系里绕 (x, y) 缩小；
     *   2) 然后绕 view 中心旋转；
     *   3) 还原。
     * 视觉效果——在 90° CW 时文字顶到底在屏幕左侧、宽度变成文字高度；缩放保证
     *   旋转后的文字高度（=原宽度）落在 view 高度内，不会被切掉。
     */
    private fun drawTextRotated(
        canvas: Canvas,
        text: String,
        paint: Paint,
        x: Float, y: Float
    ) {
        if (text.isEmpty()) return
        canvas.save()
        // 自适应：90°/270° 下 view 的"窄边"是 height；0°/180° 下是 width
        val narrowSide = if (rotationDegrees == 90 || rotationDegrees == 270)
            height.toFloat() else width.toFloat()
        val textWidth = paint.measureText(text)
        if (textWidth > narrowSide) {
            val s = narrowSide / textWidth
            canvas.scale(s, s, x, y)
        }
        canvas.rotate(rotationDegrees.toFloat(), width / 2f, height / 2f)
        canvas.drawText(text, x, y, paint)
        canvas.restore()
    }
}
