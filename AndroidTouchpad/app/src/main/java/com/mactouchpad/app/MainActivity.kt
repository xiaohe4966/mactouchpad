package com.mactouchpad.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏沉浸 + 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val touchpad = TouchpadView(this)

        statusView = TextView(this).apply {
            text = "正在连接 Mac…（USB 连接后执行 adb reverse tcp:5907 tcp:5907）"
            setTextColor(Color.argb(200, 255, 255, 255))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(touchpad, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(statusView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        setContentView(root)

        // 沉浸式全屏（必须在 setContentView 之后，insetsController 才可用）
        hideSystemUi()

        // 连接状态监听
        ConnectionManager.listener = object : ConnectionManager.Listener {
            override fun onConnected() {
                runOnUiThread { statusView.text = "● 已连接 Mac 触控板" }
            }

            override fun onDisconnected() {
                runOnUiThread { statusView.text = "○ 连接断开，正在重连…（检查 adb reverse 是否生效）" }
            }
        }
        ConnectionManager.start()
    }

    override fun onDestroy() {
        ConnectionManager.stop()
        ConnectionManager.listener = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    /**
     * 沉浸式全屏：隐藏状态栏与导航栏。
     * 说明：安卓系统不允许普通 App 完全禁止「下拉通知栏」手势（系统级安全机制），
     * 沉浸模式下从顶部下拉只会短暂显示系统栏、数秒后自动隐藏，不影响触控板使用。
     */
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：现代 WindowInsetsController
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { c ->
                c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                c.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
