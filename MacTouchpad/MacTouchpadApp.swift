#!/usr/bin/env swift
//
//  MacTouchpad Server (GUI) —— 双击即可运行的 Mac 端服务
//
//  功能：监听 TCP 5907 端口，接收安卓手机发来的二进制指令，
//        通过 CoreGraphics 模拟鼠标移动 / 点击 / 滚动 / 手势快捷键。
//  连接：USB（adb reverse）或 WiFi（自动发现 / 手动输 IP）。
//
//  用法：
//    1. 编译：  swiftc -O MacTouchpadApp.swift -framework AppKit -o MacTouchpad
//    2. 打包：  放入 MacTouchpad.app/Contents/MacOS/，配好 Info.plist
//    3. 首次运行请在「系统设置 → 隐私与安全性 → 辅助功能」中勾选本程序，
//       否则鼠标事件会被系统忽略（窗口内也会提示）。
//
//  通信协议（大端序，每条消息 = 1 字节类型 + 载荷）：
//    0x01 MOVE        int16 dx, int16 dy     相对移动（已乘灵敏度）
//    0x02 LEFT_DOWN   （无载荷）
//    0x03 LEFT_UP     （无载荷）
//    0x04 RIGHT_DOWN  （无载荷）
//    0x05 RIGHT_UP    （无载荷）
//    0x06 SCROLL      int16 dx, int16 dy
//    0x07 GESTURE     int8 id                1=调度中心 2=上一桌面 3=下一桌面 4=显示桌面 5=启动台
//

import AppKit
import CoreGraphics
import ApplicationServices
import Network

// MARK: - 协议常量

let MSG_MOVE: UInt8 = 1
let MSG_LEFT_DOWN: UInt8 = 2
let MSG_LEFT_UP: UInt8 = 3
let MSG_RIGHT_DOWN: UInt8 = 4
let MSG_RIGHT_UP: UInt8 = 5
let MSG_SCROLL: UInt8 = 6
let MSG_GESTURE: UInt8 = 7

let GESTURE_MISSION_CONTROL: Int8 = 1  // 调度中心
let GESTURE_SWITCH_LEFT: Int8 = 2      // 上一桌面
let GESTURE_SWITCH_RIGHT: Int8 = 3     // 下一桌面
let GESTURE_SHOW_DESKTOP: Int8 = 4     // 显示桌面
let GESTURE_LAUNCHPAD: Int8 = 5        // 启动台

let SERVER_PORT: NWEndpoint.Port = 5907

// MARK: - 日志（输出到 stdout + 窗口）

var logHandler: ((String) -> Void)?

func log(_ s: String) {
    print(s)
    DispatchQueue.main.async { logHandler?(s) }
}

// MARK: - 鼠标模拟（CGEvent）

enum Mouse {

    static func currentLocation() -> CGPoint {
        if let e = CGEvent(source: nil) { return e.location }
        return .zero
    }

    private static func post(_ type: CGEventType, button: CGMouseButton = .left) {
        let loc = currentLocation()
        guard let e = CGEvent(mouseEventSource: nil,
                              mouseType: type,
                              mouseCursorPosition: loc,
                              mouseButton: button) else { return }
        e.post(tap: .cghidEventTap)
    }

    /// 相对移动：光标沿手指方向移动（与真实触控板一致）
    static func move(dx: Int, dy: Int) {
        let loc = currentLocation()
        let target = CGPoint(x: loc.x + CGFloat(dx), y: loc.y + CGFloat(dy))
        guard let e = CGEvent(mouseEventSource: nil,
                              mouseType: .mouseMoved,
                              mouseCursorPosition: target,
                              mouseButton: .left) else { return }
        e.post(tap: .cghidEventTap)
    }

    /// 绝对移动：按给定坐标注入（供平滑注入器使用，避免读取异步更新的光标位置导致丢步）
    static func moveAbsolute(x: Double, y: Double) {
        guard let e = CGEvent(mouseEventSource: nil,
                              mouseType: .mouseMoved,
                              mouseCursorPosition: CGPoint(x: x, y: y),
                              mouseButton: .left) else { return }
        e.post(tap: .cghidEventTap)
    }

    /// 滚动：pixel 单位，wheel1 正值 = 内容向上（自然滚动方向由安卓端换算）
    static func scroll(dx: Int, dy: Int) {
        guard let e = CGEvent(scrollWheelEvent2Source: nil,
                              units: .pixel,
                              wheelCount: 2,
                              wheel1: Int32(dy),
                              wheel2: Int32(dx),
                              wheel3: 0) else { return }
        e.post(tap: .cghidEventTap)
    }

    static func leftDown() { post(.leftMouseDown, button: .left) }
    static func leftUp()   { post(.leftMouseUp,   button: .left) }
    static func leftClick() { leftDown(); leftUp() }

    static func rightDown() { post(.rightMouseDown, button: .right) }
    static func rightUp()   { post(.rightMouseUp,   button: .right) }
    static func rightClick() { rightDown(); rightUp() }
}

// MARK: - 手势快捷键（三指/四指滑动无法直接模拟，映射为系统级快捷键）

let kVK_Control: CGKeyCode = 0x3B  // left Control
let kVK_Up: CGKeyCode    = 0x7E
let kVK_Down: CGKeyCode  = 0x7D
let kVK_Left: CGKeyCode  = 0x7B
let kVK_Right: CGKeyCode = 0x7C
let kVK_F3: CGKeyCode    = 0x63   // 调度中心（Mission Control）默认快捷键
let kVK_F11: CGKeyCode   = 0x67   // 显示桌面（Show Desktop）默认快捷键

func postKey(_ key: CGKeyCode) {
    guard let src = CGEventSource(stateID: .hidSystemState) else { return }
    CGEvent(keyboardEventSource: src, virtualKey: key, keyDown: true)?.post(tap: .cghidEventTap)
    CGEvent(keyboardEventSource: src, virtualKey: key, keyDown: false)?.post(tap: .cghidEventTap)
}

func postKeyboardShortcut(_ key: CGKeyCode) {
    guard let src = CGEventSource(stateID: .hidSystemState) else { return }
    let ctrlDown = CGEvent(keyboardEventSource: src, virtualKey: kVK_Control, keyDown: true)
    let ctrlUp   = CGEvent(keyboardEventSource: src, virtualKey: kVK_Control, keyDown: false)
    let keyDown  = CGEvent(keyboardEventSource: src, virtualKey: key, keyDown: true)
    let keyUp    = CGEvent(keyboardEventSource: src, virtualKey: key, keyDown: false)
    ctrlDown?.flags = .maskControl
    keyDown?.flags  = .maskControl
    ctrlDown?.post(tap: .cghidEventTap)
    keyDown?.post(tap: .cghidEventTap)
    keyUp?.post(tap: .cghidEventTap)
    ctrlUp?.post(tap: .cghidEventTap)
}

func handleGesture(_ id: Int8) {
    switch id {
    case GESTURE_MISSION_CONTROL: postKey(kVK_F3)                // 调度中心
    case GESTURE_SHOW_DESKTOP:    postKey(kVK_F11)               // 显示桌面
    case GESTURE_SWITCH_LEFT:     postKeyboardShortcut(kVK_Left) // Ctrl+← 上一桌面/全屏
    case GESTURE_SWITCH_RIGHT:    postKeyboardShortcut(kVK_Right) // Ctrl+→ 下一桌面/全屏
    case GESTURE_LAUNCHPAD:
        // 启动台没有标准快捷键，直接打开 Launchpad 应用
        if let url = URL(string: "/System/Applications/Launchpad.app") {
            NSWorkspace.shared.open(url)
        }
    default: break
    }
}

// MARK: - 指针平滑注入（把合并包拆成小步连续注入，消除一顿一顿）

final class SmoothMover {

    private let queue = DispatchQueue(label: "mactouchpad.smooth")
    private var steps: [(sx: Double, sy: Double)] = []
    private var timer: DispatchSourceTimer?
    private var running = false
    /// 自维护的光标位置（CGEvent 注入后读取 currentLocation 有异步延迟，直接维护避免丢步）
    private var posX: Double?
    private var posY: Double?

    /// 每个合并包拆成的小步数（配合 2ms tick，指针近似连续移动）
    private let stepsPerMove = 3

    func start() {
        queue.async { [self] in
            guard !self.running else { return }
            self.running = true
            let t = DispatchSource.makeTimerSource(queue: self.queue)
            t.schedule(deadline: .now(), repeating: .milliseconds(2))
            t.setEventHandler { [weak self] in self?.tick() }
            t.resume()
            self.timer = t
        }
    }

    /// 收到一个 MOVE 包：拆成小步排队
    func enqueue(dx: Double, dy: Double) {
        queue.async { [self] in
            let n = Double(self.stepsPerMove)
            for _ in 0..<self.stepsPerMove {
                self.steps.append((dx / n, dy / n))
            }
        }
    }

    /// 关键动作（点击 / 手势）前调用：把队列剩余位移一次性注入，保证不丢移动、顺序正确
    func flush() {
        queue.sync { [self] in
            var tx = 0.0
            var ty = 0.0
            for s in self.steps { tx += s.sx; ty += s.sy }
            self.steps.removeAll()
            if tx != 0 || ty != 0 {
                ensurePos()
                posX! += tx
                posY! += ty
                Mouse.moveAbsolute(x: posX!, y: posY!)
            }
        }
    }

    private func ensurePos() {
        if posX == nil {
            let p = Mouse.currentLocation()
            posX = p.x
            posY = p.y
        }
    }

    private func tick() {
        guard !steps.isEmpty else { return }
        let s = steps.removeFirst()
        ensurePos()
        posX! += s.sx
        posY! += s.sy
        Mouse.moveAbsolute(x: posX!, y: posY!)
    }
}

// MARK: - 本机局域网 IPv4 地址（en 开头接口，排除回环）

func localIPAddress() -> String? {
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
    defer { freeifaddrs(ifaddr) }
    for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
        let flags = Int32(ptr.pointee.ifa_flags)
        var addr = ptr.pointee.ifa_addr.pointee
        if (flags & (IFF_UP | IFF_RUNNING)) == (IFF_UP | IFF_RUNNING),
           addr.sa_family == UInt8(AF_INET) {
            let name = String(cString: ptr.pointee.ifa_name)
            if name.hasPrefix("en") {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(&addr, socklen_t(addr.sa_len), &host, socklen_t(host.count),
                              nil, 0, NI_NUMERICHOST) == 0 {
                    let ip = String(cString: host)
                    if !ip.hasPrefix("127.") {
                        return ip
                    }
                }
            }
        }
    }
    return nil
}

// MARK: - TCP 服务器（Network framework）

final class Server {

    private let queue = DispatchQueue(label: "mactouchpad.server")
    private var listener: NWListener?
    private var current: NWConnection?
    private var buffer = [UInt8]()   // 粘包缓冲
    private let mover = SmoothMover()

    /// 客户端连接状态变化回调（true = 已连接）
    var onClientChanged: ((Bool) -> Void)?

    func start() {
        do {
            listener = try NWListener(using: .tcp, on: SERVER_PORT)
            // 注册 Bonjour 服务：手机 App 可通过 mDNS 自动发现本机（_mactouchpad._tcp）
            listener?.service = NWListener.Service(name: nil, type: "_mactouchpad._tcp")
        } catch {
            log("❌ 无法监听端口 \(SERVER_PORT)：\(error)")
            log("   可能端口被占用，请关闭占用 5907 的程序后重试。")
            return
        }
        listener?.newConnectionHandler = { [weak self] conn in
            self?.accept(conn)
        }
        listener?.start(queue: queue)
        mover.start()
        log("✅ MacTouchpad 服务已启动，监听端口 \(SERVER_PORT)（USB / WiFi 均可连接）")
        if let ip = localIPAddress() {
            log("   WiFi 连接：手机 App 设置里选「WiFi」，输入 Mac 地址 \(ip):\(SERVER_PORT)（或点自动发现）")
        }
        log("   USB 连接：adb reverse tcp:\(SERVER_PORT) tcp:\(SERVER_PORT)")
    }

    private func accept(_ conn: NWConnection) {
        // 只保留最新一个客户端，旧连接释放
        if let old = current {
            log("🔄 新客户端接入，替换旧连接")
            old.cancel()
        }
        current = conn
        buffer.removeAll()

        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                if case .hostPort(let host, let port) = conn.endpoint {
                    log("📱 已连接客户端（\(host):\(port)）")
                } else {
                    log("📱 已连接客户端（触控板就绪）")
                }
                self?.onClientChanged?(true)
            case .failed(let err):
                log("连接结束：\(err.localizedDescription)")
                if self?.current === conn {
                    self?.current = nil
                    self?.onClientChanged?(false)
                }
            case .cancelled:
                if self?.current === conn {
                    self?.current = nil
                    self?.onClientChanged?(false)
                }
            default:
                break
            }
        }
        conn.start(queue: queue)
        receive(from: conn)
    }

    private func receive(from conn: NWConnection) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            if let data = data, !data.isEmpty {
                self.buffer.append(contentsOf: data)
                self.processBuffer()
            }
            if isComplete || error != nil {
                if self.current === conn { self.current = nil }
                return
            }
            self.receive(from: conn)
        }
    }

    /// 按协议解析缓冲区内所有完整消息
    private func processBuffer() {
        while true {
            guard buffer.count >= 1 else { return }
            let type = buffer[0]
            let totalLen: Int
            switch type {
            case MSG_MOVE, MSG_SCROLL: totalLen = 5   // 1 + 2 + 2
            case MSG_GESTURE:          totalLen = 2   // 1 + 1
            default:                   totalLen = 1
            }
            guard buffer.count >= totalLen else { return }
            let payload = Array(buffer[0..<totalLen])
            buffer.removeFirst(totalLen)
            handle(type: type, payload: payload)
        }
    }

    private func i16(_ b: [UInt8], _ off: Int) -> Int {
        Int(Int16(bitPattern: UInt16(b[off]) << 8 | UInt16(b[off + 1])))
    }

    private func handle(type: UInt8, payload: [UInt8]) {
        switch type {
        case MSG_MOVE:
            let dx = i16(payload, 1); let dy = i16(payload, 3)
            log("收到 MOVE dx=\(dx) dy=\(dy)")
            mover.enqueue(dx: Double(dx), dy: Double(dy))
        case MSG_LEFT_DOWN:
            log("收到 LEFT_DOWN")
            mover.flush()
            Mouse.leftDown()
        case MSG_LEFT_UP:
            log("收到 LEFT_UP")
            mover.flush()
            Mouse.leftUp()
        case MSG_RIGHT_DOWN:
            log("收到 RIGHT_DOWN")
            mover.flush()
            Mouse.rightDown()
        case MSG_RIGHT_UP:
            log("收到 RIGHT_UP")
            mover.flush()
            Mouse.rightUp()
        case MSG_SCROLL:
            let dx = i16(payload, 1); let dy = i16(payload, 3)
            log("收到 SCROLL dx=\(dx) dy=\(dy)")
            Mouse.scroll(dx: dx, dy: dy)
        case MSG_GESTURE:
            log("收到 GESTURE id=\(payload[1])")
            mover.flush()
            handleGesture(Int8(bitPattern: payload[1]))
        default:
            log("收到未知消息 type=\(type)")
        }
    }
}

// MARK: - 窗口 UI

final class AppDelegate: NSObject, NSApplicationDelegate {

    private var window: NSWindow!
    private var logView: NSTextView!
    private var statusLabel: NSTextField!
    private var clientLabel: NSTextField!
    private var permLabel: NSTextField!
    private var permBtn: NSButton!
    private let server = Server()
    private var permTimer: Timer?

    func applicationDidFinishLaunching(_ notification: Notification) {
        logHandler = { [weak self] s in self?.appendLog(s) }
        buildUI()
        server.onClientChanged = { [weak self] connected in
            DispatchQueue.main.async {
                self?.updateClient(connected)
            }
        }
        server.start()
        startPermissionMonitor()
        // 初始权限状态
        updatePermission()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return true
    }

    private func buildUI() {
        let rect = NSRect(x: 0, y: 0, width: 460, height: 400)
        window = NSWindow(contentRect: rect,
                          styleMask: [.titled, .closable, .miniaturizable],
                          backing: .buffered,
                          defer: false)
        window.title = "Mac 触控板服务"
        window.isReleasedWhenClosed = false
        let content = window.contentView!

        func label(_ y: CGFloat, _ size: CGFloat, _ bold: Bool) -> NSTextField {
            let f = NSTextField(frame: NSRect(x: 18, y: y, width: 424, height: 20))
            f.isBezeled = false
            f.isEditable = false
            f.drawsBackground = false
            f.font = .systemFont(ofSize: size, weight: bold ? .semibold : .regular)
            f.textColor = .white
            return f
        }

        let title = label(368, 16, true)
        title.stringValue = "🖱️ Mac 触控板服务"
        content.addSubview(title)

        statusLabel = label(340, 13, false)
        content.addSubview(statusLabel)

        clientLabel = label(316, 13, false)
        content.addSubview(clientLabel)

        permLabel = label(288, 13, false)
        content.addSubview(permLabel)

        permBtn = NSButton(frame: NSRect(x: 18, y: 258, width: 220, height: 26))
        permBtn.title = "打开「辅助功能」设置"
        permBtn.bezelStyle = .rounded
        permBtn.target = self
        permBtn.action = #selector(openPermissionSettings)
        content.addSubview(permBtn)

        // 日志区
        let scroll = NSScrollView(frame: NSRect(x: 18, y: 14, width: 424, height: 228))
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.borderType = .bezelBorder
        logView = NSTextView(frame: NSRect(x: 0, y: 0, width: 424, height: 228))
        logView.isEditable = false
        logView.isRichText = false
        logView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        logView.backgroundColor = NSColor(white: 0.1, alpha: 1)
        logView.textColor = .white
        logView.isVerticallyResizable = true
        logView.autoresizingMask = [.width]
        scroll.documentView = logView
        content.addSubview(scroll)

        window.center()
        window.orderFrontRegardless()
        NSApp.activate(ignoringOtherApps: true)
    }

    private func startPermissionMonitor() {
        permTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            self?.updatePermission()
        }
    }

    private func updatePermission() {
        let trusted = AXIsProcessTrusted()
        DispatchQueue.main.async {
            self.permLabel.stringValue = trusted
                ? "✅ 辅助功能已授权"
                : "⚠️ 未获得「辅助功能」权限，请勾选后重新打开本程序"
            self.permLabel.textColor = trusted ? NSColor.systemGreen : NSColor.systemRed
            self.permBtn.isHidden = trusted
        }
    }

    private func updateClient(_ connected: Bool) {
        clientLabel.stringValue = connected
            ? "📱 手机已连接（触控板就绪）"
            : "📵 等待手机连接…（USB 或 WiFi）"
        clientLabel.textColor = connected ? NSColor.systemGreen : NSColor.systemGray
    }

    @objc private func openPermissionSettings() {
        NSWorkspace.shared.open(URL(
            string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
    }

    private func appendLog(_ s: String) {
        guard let storage = logView?.textStorage else { return }
        storage.append(NSAttributedString(string: s + "\n",
            attributes: [.font: NSFont.monospacedSystemFont(ofSize: 11, weight: .regular),
                         .foregroundColor: NSColor.white]))
        logView.scrollToEndOfDocument(nil)
    }

    private func updateStatus() {
        statusLabel.stringValue = "● 服务运行中   端口 5907   WiFi: \(localIPSummary)"
        statusLabel.textColor = NSColor.systemGreen
    }

    private var localIPSummary: String {
        if let ip = localIPAddress() { return "\(ip):5907" }
        return "未知（未联网）"
    }
}

// MARK: - 入口

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
