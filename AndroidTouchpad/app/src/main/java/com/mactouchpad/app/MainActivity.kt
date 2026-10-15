package com.mactouchpad.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var rotationBtn: TextView
    private lateinit var settingsBtn: TextView
    private lateinit var settingsPanel: FrameLayout
    private lateinit var touchpad: TouchpadView
    private lateinit var prefs: SharedPreferences

    // 连接方式 UI（USB 走 adb reverse → 127.0.0.1；WiFi 直连 Mac 局域网 IP）
    private lateinit var usbBtn: TextView
    private lateinit var wifiBtn: TextView
    private lateinit var wifiRow: LinearLayout
    private lateinit var discoverBtn: TextView
    private lateinit var wifiInput: EditText
    private var connModeWifi = false
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var failCount = 0            // 连续连接失败次数（用于自动切换 USB/WiFi）

    // 灵敏度当前值（内存态，变化即写入 touchpad 并持久化）
    private var moveSens = 1.0f
    private var scrollSens = 0.7f
    private var multiScale = 1.6f

    /** 系统语言是否为中文（用于界面文字自动切换） */
    private val isChinese: Boolean
        get() = Locale.getDefault().language.startsWith("zh") ||
            resources.configuration.locales[0].language.startsWith("zh")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏沉浸 + 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        touchpad = TouchpadView(this)
        // 顶栏连接状态文字由 TouchpadView 自己画，跟着旋转一起转
        touchpad.statusText = if (isChinese)
            "正在连接 Mac…（USB 连接后执行 adb reverse tcp:5907 tcp:5907）"
        else
            "Connecting to Mac… (run: adb reverse tcp:5907 tcp:5907)"

        // 读取保存的灵敏度设置
        prefs = getSharedPreferences("mactouchpad_prefs", MODE_PRIVATE)
        moveSens = prefs.getFloat("move_sens", 1.0f)
        scrollSens = prefs.getFloat("scroll_sens", 0.7f)
        multiScale = prefs.getFloat("multi_scale", 1.6f)
        touchpad.moveSensitivity = moveSens
        touchpad.scrollSensitivity = scrollSens
        touchpad.multiMoveScale = multiScale

        // 左上角小按钮：循环切换 0° / 90° / 180° / 270°，让不同握持方向下手感都自然
        rotationBtn = TextView(this).apply {
            text = "0°"
            setTextColor(Color.argb(220, 255, 255, 255))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(140, 30, 30, 30))
                setStroke(dp(1), Color.argb(120, 255, 255, 255))
            }
            setOnClickListener {
                val next = (touchpad.rotationDegrees + 90) % 360
                touchpad.setRotationDegrees(next)
                updateRotationBtnText()
            }
        }
        updateRotationBtnText()

        // 右上角设置按钮：打开 / 关闭设置面板（连接方式 + 灵敏度）
        settingsBtn = TextView(this).apply {
            text = "⚙"
            setTextColor(Color.argb(220, 255, 255, 255))
            textSize = 17f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(140, 30, 30, 30))
                setStroke(dp(1), Color.argb(120, 255, 255, 255))
            }
            setOnClickListener { toggleSettings() }
        }

        settingsPanel = buildSettingsPanel()

        // 恢复上次连接方式（WiFi 模式 + 保存的 Mac 地址）
        connModeWifi = prefs.getBoolean("wifi_mode", false)
        if (connModeWifi) {
            val savedIp = prefs.getString("wifi_host", "") ?: ""
            if (savedIp.isNotEmpty()) {
                wifiInput.setText(savedIp)
                ConnectionManager.setTarget(savedIp)
            }
        }
        updateConnModeUi()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(touchpad, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(rotationBtn, FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.TOP or Gravity.START).apply {
                setMargins(dp(16), dp(16), 0, 0)
            })
            addView(settingsBtn, FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(16), dp(16), 0)
            })
            addView(settingsPanel, FrameLayout.LayoutParams(
                dp(300), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(72), dp(16), 0)
            })
        }
        setContentView(root)

        // 沉浸式全屏（必须在 setContentView 之后，insetsController 才可用）
        hideSystemUi()

        // 连接状态监听
        ConnectionManager.listener = object : ConnectionManager.Listener {
            override fun onConnected() {
                failCount = 0
                runOnUiThread {
                    touchpad.statusText = if (isChinese) "● 已连接 Mac 触控板" else "● Connected to Mac trackpad"
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    touchpad.statusText = if (isChinese)
                        "○ 连接断开，正在重连…（检查 adb reverse 是否生效）"
                    else
                        "○ Disconnected, reconnecting… (check adb reverse)"
                    // 连续失败到阈值：自动尝试另一种连接方式（USB ↔ WiFi）
                    failCount++
                    if (failCount >= 4) {
                        autoSwitchMode()
                    }
                }
            }
        }
        ConnectionManager.start()
    }

    override fun onDestroy() {
        stopAutoDiscover()
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

    private fun updateRotationBtnText() {
        rotationBtn.text = "${touchpad.rotationDegrees}°"
    }

    // ============================================================ 设置面板

    private fun toggleSettings() {
        settingsPanel.visibility =
            if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /** 构建设置面板：连接方式（USB / WiFi）+ 灵敏度调节 */
    private fun buildSettingsPanel(): FrameLayout {
        val panel = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(215, 22, 22, 22))
                setStroke(dp(1), Color.argb(120, 255, 255, 255))
            }
            isClickable = true       // 面板区域拦截触摸，避免穿透到触控板
            isFocusable = true
            visibility = View.GONE
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
        }

        // 标题行 + 关闭按钮
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = if (isChinese) "设置" else "Settings"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "✕"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(220, 255, 255, 255))
            setPadding(dp(10), dp(2), dp(4), dp(2))
            setOnClickListener { settingsPanel.visibility = View.GONE }
        })
        box.addView(header)

        // ===== 连接方式 =====
        box.addView(sectionLabel(if (isChinese) "连接方式（USB / WiFi）" else "Connection (USB / WiFi)"))

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        usbBtn = modeBtn("USB") { setConnMode(false) }
        wifiBtn = modeBtn("WiFi") { setConnMode(true) }
        modeRow.addView(usbBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        modeRow.addView(wifiBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })
        box.addView(modeRow)

        // WiFi 地址输入行
        wifiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        wifiInput = EditText(this).apply {
            hint = "192.168.x.x"
            setHintTextColor(Color.argb(150, 255, 255, 255))
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(Color.argb(120, 50, 50, 50))
            }
        }
        wifiRow.addView(wifiInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        wifiRow.addView(TextView(this).apply {
            text = if (isChinese) "连接" else "Connect"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(Color.argb(200, 30, 90, 160))
            }
            setOnClickListener { connectWifi(wifiInput.text.toString().trim()) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
        box.addView(wifiRow)

        // WiFi 自动发现按钮
        discoverBtn = TextView(this).apply {
            text = if (isChinese) "🔍 自动发现 Mac" else "🔍 Auto-discover Mac"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(6))
            setOnClickListener { startAutoDiscover() }
        }
        box.addView(discoverBtn)

        // ===== 灵敏度 =====
        box.addView(sectionLabel(if (isChinese) "灵敏度（步进 0.1）" else "Sensitivity (step 0.1)"))
        box.addView(adjusterRow(
            if (isChinese) "指针灵敏度" else "Pointer",
            min = 0.3f, max = 5f, step = 0.1f,
            current = { moveSens },
            onChanged = { moveSens = it; touchpad.moveSensitivity = it; save("move_sens", it) }
        ))
        box.addView(adjusterRow(
            if (isChinese) "滚动灵敏度" else "Scroll",
            min = 0.2f, max = 2f, step = 0.1f,
            current = { scrollSens },
            onChanged = { scrollSens = it; touchpad.scrollSensitivity = it; save("scroll_sens", it) }
        ))
        box.addView(adjusterRow(
            if (isChinese) "三指拖移" else "3F Drag",
            min = 0.3f, max = 5f, step = 0.1f,
            current = { multiScale },
            onChanged = { multiScale = it; touchpad.multiMoveScale = it; save("multi_scale", it) }
        ))

        panel.addView(box)
        return panel
    }

    /** 小节标题 */
    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.argb(150, 255, 255, 255))
        setPadding(0, dp(8), 0, dp(2))
    }

    /** USB / WiFi 模式选择按钮 */
    private fun modeBtn(label: String, onClick: () -> Unit): TextView {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(Color.argb(150, 60, 60, 60))
        }
        return TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = bg
            setOnClickListener { onClick() }
        }
    }

    /** 切换连接方式（USB = 127.0.0.1 / WiFi = Mac 局域网 IP） */
    private fun setConnMode(wifi: Boolean) {
        if (connModeWifi == wifi) return
        connModeWifi = wifi
        prefs.edit().putBoolean("wifi_mode", wifi).apply()
        if (wifi) {
            fillWifiHint()                              // 自动填充同网段 IP 前缀
            val ip = wifiInput.text.toString().trim()
            if (ip.isNotEmpty() && !ip.endsWith(".")) {
                connectWifi(ip)
            } else {
                ConnectionManager.setTarget("127.0.0.1")   // 先断开 USB
                startAutoDiscover()                         // 并自动搜索
            }
        } else {
            ConnectionManager.setTarget("127.0.0.1")
        }
        updateConnModeUi()
    }

    /**
     * WiFi 模式下自动填充 Mac 地址：取手机当前 WiFi IP（如 192.168.68.80）
     * 去掉最后一段 → "192.168.68."，Mac 通常同网段，用户只需补最后一位。
     */
    private fun fillWifiHint() {
        if (wifiInput.text.isNotEmpty()) return   // 已有输入则不覆盖
        val ip = getLocalWifiIp() ?: return
        val prefix = ip.substringBeforeLast('.') + "."
        wifiInput.setText(prefix)
        wifiInput.setSelection(prefix.length)
    }

    /** 获取手机当前 WiFi 的局域网 IPv4（优先 192.168/10/172 网段） */
    private fun getLocalWifiIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                if (ni.isUp && !ni.isLoopback) {
                    ni.inetAddresses?.toList()?.forEach { a ->
                        if (a is Inet4Address) {
                            val ip = a.hostAddress ?: return@forEach
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") ||
                                ip.startsWith("172.")) {
                                return ip
                            }
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** 连续失败自动切换连接方式（USB 失败试 WiFi，WiFi 失败回 USB） */
    private fun autoSwitchMode() {
        failCount = 0
        if (connModeWifi) {
            // WiFi 连不上 → 切回 USB
            setConnMode(false)
            touchpad.statusText = if (isChinese)
                "↺ WiFi 未连上，已自动切回 USB 模式…"
            else
                "↺ WiFi failed, switched back to USB…"
        } else {
            // USB 连不上 → 尝试 WiFi（自动填充 + 自动发现）
            connModeWifi = true
            prefs.edit().putBoolean("wifi_mode", true).apply()
            fillWifiHint()
            val ip = wifiInput.text.toString().trim()
            if (ip.isNotEmpty() && !ip.endsWith(".")) {
                connectWifi(ip)
            } else {
                ConnectionManager.setTarget("127.0.0.1")
                startAutoDiscover()
            }
            updateConnModeUi()
            touchpad.statusText = if (isChinese)
                "↺ USB 未连上，正在尝试 WiFi 连接…"
            else
                "↺ USB failed, trying WiFi…"
        }
    }

    /** 以指定 IP 连接 Mac（WiFi） */
    private fun connectWifi(ip: String) {
        if (ip.isEmpty()) return
        prefs.edit().putString("wifi_host", ip).apply()
        ConnectionManager.setTarget(ip)
        settingsPanel.visibility = View.GONE
    }

    /** 刷新 USB / WiFi 按钮与输入行的显示状态 */
    private fun updateConnModeUi() {
        (usbBtn.background as GradientDrawable).setColor(
            if (!connModeWifi) Color.argb(220, 30, 90, 160) else Color.argb(150, 60, 60, 60))
        (wifiBtn.background as GradientDrawable).setColor(
            if (connModeWifi) Color.argb(220, 30, 90, 160) else Color.argb(150, 60, 60, 60))
        usbBtn.invalidate()
        wifiBtn.invalidate()
        wifiRow.visibility = if (connModeWifi) View.VISIBLE else View.GONE
        discoverBtn.visibility = if (connModeWifi) View.VISIBLE else View.GONE
    }

    // ============================================================ mDNS 自动发现

    /** 通过 Bonjour（_mactouchpad._tcp）自动发现 Mac 并连接 */
    private fun startAutoDiscover() {
        stopAutoDiscover()
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = nsd
        val dl = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                runOnUiThread {
                    try {
                        nsd.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val host = info.host?.hostAddress
                                if (host != null) {
                                    runOnUiThread {
                                        wifiInput.setText(host)
                                        connectWifi(host)
                                    }
                                }
                            }

                            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                        })
                    } catch (_: Exception) {}
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = dl
        try {
            nsd.discoverServices("_mactouchpad._tcp", NsdManager.PROTOCOL_DNS_SD, dl)
        } catch (_: Exception) {}
    }

    private fun stopAutoDiscover() {
        discoveryListener?.let { dl ->
            try { nsdManager?.stopServiceDiscovery(dl) } catch (_: Exception) {}
        }
        discoveryListener = null
        nsdManager = null
    }

    // ============================================================ 灵敏度调节器

    /** 一行「名称 + 减号 + 当前值 + 加号」的调节器（步进 0.1） */
    private fun adjusterRow(
        title: String,
        min: Float, max: Float, step: Float,
        current: () -> Float,
        onChanged: (Float) -> Unit
    ): LinearLayout {
        val valueLabel = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            minWidth = dp(46)
        }
        fun refresh() {
            valueLabel.text = String.format(Locale.US, "%.1f", current())
        }
        fun stepBtn(symbol: String, delta: Float) = TextView(this).apply {
            text = symbol
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(3), dp(14), dp(3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(Color.argb(170, 70, 70, 70))
            }
            setOnClickListener {
                val v = current() + delta
                if (v >= min && v <= max) {
                    onChanged(v)
                    refresh()
                }
            }
        }
        refresh()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 13f
                setTextColor(Color.argb(230, 255, 255, 255))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(stepBtn("−", -step))
            addView(valueLabel)
            addView(stepBtn("+", step))
        }
    }

    private fun save(key: String, v: Float) {
        prefs.edit().putFloat(key, v).apply()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
