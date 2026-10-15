package com.mactouchpad.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 触控板数据连接：TCP 客户端。
 *  - USB 模式：连接 127.0.0.1:5907，通过 `adb reverse tcp:5907 tcp:5907` 转发到 Mac 本机。
 *  - WiFi 模式：直接连接 Mac 局域网 IP:5907（服务端已监听所有网卡 + Bonjour 广播）。
 * 断线后自动每 2 秒重连（用当前 targetHost / targetPort）。
 */
object ConnectionManager {

    /** 当前连接目标：USB 模式默认 127.0.0.1，WiFi 模式为 Mac 局域网 IP */
    @Volatile var targetHost: String = "127.0.0.1"
    @Volatile var targetPort: Int = 5907

    /** 切换连接目标（会立即断开当前连接，重连循环将使用新地址） */
    fun setTarget(host: String, port: Int = 5907) {
        if (targetHost == host && targetPort == port) return
        targetHost = host
        targetPort = port
        closeSocket()
    }

    /** 保活消息（服务端忽略），用于检测假死连接 */
    private val MSG_KEEPALIVE = byteArrayOf(0x00)

    private const val READ_TIMEOUT_MS = 5000
    private const val RECONNECT_WAIT_MS = 2000L

    interface Listener {
        fun onConnected()
        fun onDisconnected()
    }

    var listener: Listener? = null

    private val running = AtomicBoolean(false)
    private val lock = Any()
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var thread: Thread? = null

    /**
     * 触摸数据发送线程：触摸事件在主线程（UI 线程）到达，socket 写入必须在后台线程
     * 执行，否则 Android 会抛 NetworkOnMainThreadException 并静默丢弃数据。
     * 单线程保证消息按发送顺序 FIFO（DOWN 必须先于 UP）。
     */
    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MacTouchpad-Sender").apply { isDaemon = true }
    }

    // ---- 增量节流：MOVE / SCROLL 高频合并成包，WiFi 下显著降低包数量与卡顿 ----
    private val throttleHandler = Handler(Looper.getMainLooper())
    private val THROTTLE_MS = 14L
    @Volatile private var pendingMoveX = 0
    @Volatile private var pendingMoveY = 0
    @Volatile private var pendingScrollX = 0
    @Volatile private var pendingScrollY = 0
    @Volatile private var flushPosted = false

    /** 移动增量：先累积，按固定间隔合并成一个 MOVE 包发出 */
    fun sendMoveThrottled(dx: Int, dy: Int) {
        synchronized(lock) {
            pendingMoveX += dx
            pendingMoveY += dy
        }
        scheduleFlush()
    }

    /** 滚动增量：先累积，按固定间隔合并成一个 SCROLL 包发出 */
    fun sendScrollThrottled(dx: Int, dy: Int) {
        synchronized(lock) {
            pendingScrollX += dx
            pendingScrollY += dy
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushPosted) return
        flushPosted = true
        throttleHandler.postDelayed({
            flushPosted = false
            // 网络写入必须在后台发送线程执行
            sendExecutor.execute { flushThrottled() }
        }, THROTTLE_MS)
    }

    /** 把累积的移动 / 滚动增量合并发出。必须在后台发送线程调用。 */
    private fun flushThrottled() {
        synchronized(lock) {
            val mx = pendingMoveX; val my = pendingMoveY
            val sx = pendingScrollX; val sy = pendingScrollY
            pendingMoveX = 0; pendingMoveY = 0
            pendingScrollX = 0; pendingScrollY = 0
            if ((mx != 0 || my != 0) && out != null) {
                sendOnThread(Protocol.move(mx, my))
            }
            if ((sx != 0 || sy != 0) && out != null) {
                sendOnThread(Protocol.scroll(sx, sy))
            }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            while (running.get()) {
                var connected = false
                try {
                    val s = Socket()
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(targetHost, targetPort), 2000)
                    // 读超时：服务端是纯单向通道，read 正常会一直阻塞。
                    // 超时说明通道无数据（正常态），趁机发一次保活验证连接是否还活着。
                    s.soTimeout = READ_TIMEOUT_MS
                    synchronized(lock) {
                        socket = s
                        out = DataOutputStream(s.getOutputStream())
                    }
                    connected = true
                    notifyConnected()
                    Log.d("MacTouchpad", "连接建立")
                    // 保持连接；读到 EOF 说明对端关闭；读超时则发保活探测
                    val input = s.getInputStream()
                    while (running.get()) {
                        try {
                            if (input.read() == -1) break
                        } catch (_: SocketTimeoutException) {
                            // 空闲保活：连接线程是子线程，可直接同步写；失败说明连接已死
                            if (!sendOnThread(MSG_KEEPALIVE)) break
                        }
                    }
                } catch (_: Exception) {
                    // 连接失败或中断：交给重连循环
                } finally {
                    closeSocket()
                    if (connected) notifyDisconnected()
                }
                // 等待重连
                var waited = 0L
                while (running.get() && waited < RECONNECT_WAIT_MS) {
                    Thread.sleep(100)
                    waited += 100
                }
            }
        }, "MacTouchpad-Conn").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 触摸事件发送：异步提交到后台发送线程，避免主线程网络操作。
     * 发送关键消息（DOWN / UP / 手势）前先 flush 累积的移动增量，保证顺序与拖拽完整性。
     */
    fun send(bytes: ByteArray) {
        sendExecutor.execute {
            flushThrottled()
            sendOnThread(bytes)
        }
    }

    /** 必须在后台线程调用。写失败说明底层 socket 已死：立即清理并通知重连 */
    private fun sendOnThread(bytes: ByteArray): Boolean {
        synchronized(lock) {
            val o = out ?: run {
                Log.w("MacTouchpad", "send 失败：out 为 null（未连接）")
                return false
            }
            return try {
                o.write(bytes)
                o.flush()
                true
            } catch (e: Exception) {
                Log.e("MacTouchpad", "send 异常：${e.javaClass.simpleName} ${e.message}")
                // 连接已死：清理 socket 并通知断线，重连线程会立即恢复
                closeSocketLocked()
                listener?.onDisconnected()
                false
            }
        }
    }

    fun stop() {
        running.set(false)
        closeSocket()
        thread?.let { t ->
            t.interrupt()
            thread = null
        }
    }

    private fun closeSocket() {
        synchronized(lock) {
            closeSocketLocked()
        }
    }

    /** 必须在持有 lock 时调用 */
    private fun closeSocketLocked() {
        try { out?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null
    }

    private fun notifyConnected() {
        listener?.onConnected()
    }

    private fun notifyDisconnected() {
        listener?.onDisconnected()
    }
}
