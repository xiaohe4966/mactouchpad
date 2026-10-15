package com.mactouchpad.app

import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log

/**
 * 触控板数据连接：TCP 客户端，固定连接 127.0.0.1:5907。
 * 通过 USB 数据线 + `adb reverse tcp:5907 tcp:5907` 转发到 Mac 本机端口。
 * 断线后自动每 2 秒重连。
 */
object ConnectionManager {

    const val HOST = "127.0.0.1"
    const val PORT = 5907

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

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            while (running.get()) {
                var connected = false
                try {
                    val s = Socket()
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(HOST, PORT), 2000)
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
     */
    fun send(bytes: ByteArray) {
        sendExecutor.execute { sendOnThread(bytes) }
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
