# Mac 触控板（Mac Touchpad）

把安卓手机整块屏幕变成 Mac 的触控板：单指移动指针、轻点单击、长按拖拽、双指滚动、双指轻点右键、三指滑动触发系统手势（调度中心 / 显示桌面 / 切换桌面）。

连接方式：**USB 数据线 + ADB 端口转发**（延迟低、稳定，无需同一 WiFi）。

---

## 项目结构

```
MacTouchpad/
  server.swift         Mac 端服务程序（Swift 单文件，无第三方依赖）
  touchpad-server      编译好的可执行文件（arm64）
AndroidTouchpad/       安卓端工程（Kotlin，Android Studio 可直接打开）
  app/src/main/java/com/mactouchpad/app/
    MainActivity.kt    主界面：全屏触控板 + 连接状态栏
    TouchpadView.kt    手势识别状态机（核心）
    ConnectionManager.kt  TCP 客户端 + 断线自动重连
    Protocol.kt         二进制协议编码
```

通信协议（TCP 5907 端口，大端序，每条消息 = 1 字节类型 + 载荷）：

| 类型 | 含义 | 载荷 |
|------|------|------|
| 0x01 | 移动指针（相对） | int16 dx, int16 dy |
| 0x02 / 0x03 | 左键按下 / 抬起 | 无 |
| 0x04 / 0x05 | 右键按下 / 抬起 | 无 |
| 0x06 | 滚动 | int16 dx, int16 dy |
| 0x07 | 系统手势 | int8 id（1=调度中心 2=显示桌面 3=切换上一桌面 4=切换下一桌面） |

---

## 一、Mac 端：编译并运行服务

已编译好的可执行文件在 `MacTouchpad/touchpad-server`，也可以自己编译：

```bash
cd MacTouchpad
swiftc -O -o touchpad-server server.swift
./touchpad-server
```

首次运行请授权**辅助功能**（否则模拟输入会被系统忽略）：

> 系统设置 → 隐私与安全性 → 辅助功能 → 勾选「终端」（如果从终端运行本程序）
> 或勾选编译出的 `touchpad-server` 可执行文件。授权后**重新运行**程序。

注意：macOS 的「屏幕共享」默认占用 5900 端口，本程序使用 **5907** 端口避开冲突。

## 二、手机端：安装 App

1. 用 Android Studio 打开 `AndroidTouchpad` 目录（首次 Sync 会自动下载 Gradle）。
2. 手机开启「开发者选项 → USB 调试」，用数据线连接 Mac。
3. 点击 Run 安装到手机（或 Build → Build APK 手动安装）。

## 三、建立连接

```bash
# 手机 USB 连上 Mac 后执行：
adb reverse tcp:5907 tcp:5907
```

然后打开手机上的 App，顶部状态显示「● 已连接 Mac 触控板」即可使用。
（`adb reverse` 在拔线后失效，重新插线后需再次执行；Mac 端服务保持运行即可。）

---

## 手势对照表

| 手机手势 | Mac 效果 |
|----------|----------|
| 单指滑动 | 移动指针（方向与手指一致） |
| 单指轻点 | 左键单击 |
| 单指长按 0.28 秒后滑动 | 按住左键拖拽（拖文件、选框、拖动窗口） |
| 双指滑动 | 滚动（自然滚动方向：手指上滑内容向上） |
| 双指轻点 | 右键单击 |
| 三指上滑 | 调度中心（Mission Control） |
| 三指下滑 | 显示桌面 |
| 三指左滑 / 右滑 | 切换上一个 / 下一个桌面（全屏空间） |

> 三指手势通过系统快捷键实现（Ctrl+↑ / ↓ / ← / →）。若你的快捷键自定义过，可在「系统设置 → 键盘 → 键盘快捷键」中确认，或在 `server.swift` 的 `handleGesture` 中调整。

## 参数调节（按手感）

打开 `TouchpadView.kt` 顶部可调整：

| 参数 | 默认 | 说明 |
|------|------|------|
| `moveSensitivity` | 1.6 | 单指移动 → 指针位移倍数（越大指针动得越快） |
| `scrollSensitivity` | 0.7 | 双指滚动倍数 |
| `longPressMs` | 280 | 长按进入拖拽的时长（毫秒） |
| `clickSlop` | 8dp | 点击判定最大位移 |

---

## 常见问题

- **连不上 / 一直显示重连**：确认 Mac 端服务在运行（终端有「监听端口 5907」输出）；确认 `adb reverse tcp:5907 tcp:5907` 执行成功（可用 `adb reverse --list` 检查）。
- **指针不动，但连接正常**：辅助功能权限未授予，或授权后没有重启服务程序。
- **三指手势无反应**：先在 Mac 的「系统设置 → 键盘 → 键盘快捷键」确认 Ctrl+方向键 映射未被修改。
- **滚动方向相反**：把 `TouchpadView.kt` 中 `Protocol.scroll` 的 `-dy` 改回 `dy`。
