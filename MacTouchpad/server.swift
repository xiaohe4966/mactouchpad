#!/usr/bin/env swift
//
//  MacTouchpad Server —— 安卓触控板 Mac 端服务程序（Swift 单文件，无第三方依赖）
//
//  功能：监听 TCP 5900 端口，接收安卓手机发来的二进制指令，
//        通过 CoreGraphics 模拟鼠标移动 / 点击 / 滚动 / 手势快捷键。
//
//  用法：
//    1. 编译：  swiftc -O -o touchpad-server server.swift
//    2. 运行：  ./touchpad-server
//    3. 首次运行请在「系统设置 → 隐私与安全性 → 辅助功能」中勾选本程序
//       （如果从终端启动，勾选「终端」即可），否则鼠标事件会被系统忽略。
//    4. 手机 USB 连接 Mac 后执行： adb reverse tcp:5907 tcp:5907
//
//  通信协议（大端序，每条消息 = 1 字节类型 + 载荷）：
//    0x01 MOVE        int16 dx, int16 dy     相对移动（已乘灵敏度）
//    0x02 LEFT_DOWN   （无载荷）
//    0x03 LEFT_UP     （无载荷）
//    0x04 RIGHT_DOWN  （无载荷）
//    0x05 RIGHT_UP    （无载荷）
//    0x06 SCROLL      int16 dx, int16 dy     dy>0 = 手指下滑（内容向下）
//    0x07 GESTURE     int8 id                1=调度中心 2=上一桌面 3=下一桌面 4=显示桌面 5=启动台
//

import Foundation
import CoreGraphics
import ApplicationServices
import AppKit
import Network

// MARK: - 协议常量

let MSG_MOVE: UInt8 = 1
let MSG_LEFT_DOWN: UInt8 = 2
let MSG_LEFT_UP: UInt8 = 3
let MSG_RIGHT_DOWN: UInt8 = 4
let MSG_RIGHT_UP: UInt8 = 5
let MSG_SCROLL: UInt8 = 6
let MSG_GESTURE: UInt8 = 7

let GESTURE_MISSION_CONTROL: Int8 = 1  // 三指上滑 → 调度中心
let GESTURE_SWITCH_LEFT: Int8 = 2      // 三指左滑 → 上一桌面
let GESTURE_SWITCH_RIGHT: Int8 = 3     // 三指右滑 → 下一桌面
let GESTURE_SHOW_DESKTOP: Int8 = 4     // 三指下滑 → 显示桌面
let GESTURE_LAUNCHPAD: Int8 = 5        // 四指上滑 → 启动台

let SERVER_PORT: NWEndpoint.Port = 5907

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

// MARK: - TCP 服务器（Network framework）

final class Server {

    private let queue = DispatchQueue(label: "mactouchpad.server")
    private var listener: NWListener?
    private var current: NWConnection?
    private var buffer = [UInt8]()   // 粘包缓冲

    func start() {
        do {
            listener = try NWListener(using: .tcp, on: SERVER_PORT)
        } catch {
            print("❌ 无法监听端口 \(SERVER_PORT)：\(error)")
            exit(1)
        }
        listener?.newConnectionHandler = { [weak self] conn in
            self?.accept(conn)
        }
        listener?.start(queue: queue)
        print("✅ MacTouchpad 服务已启动，监听端口 \(SERVER_PORT)")
        print("   手机 USB 连接后，在 Mac 终端执行：adb reverse tcp:\(SERVER_PORT) tcp:\(SERVER_PORT)")
    }

    private func accept(_ conn: NWConnection) {
        // 只保留最新一个客户端，旧连接释放
        if let old = current {
            print("🔄 新客户端接入，替换旧连接")
            old.cancel()
        }
        current = conn
        buffer.removeAll()

        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                print("📱 已连接客户端（触控板就绪）")
            case .failed(let err):
                print("连接结束：\(err.localizedDescription)")
                if self?.current === conn { self?.current = nil }
            case .cancelled:
                if self?.current === conn { self?.current = nil }
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
            print("DEBUG 收到 MOVE dx=\(dx) dy=\(dy)")
            Mouse.move(dx: dx, dy: dy)
        case MSG_LEFT_DOWN:
            print("DEBUG 收到 LEFT_DOWN")
            Mouse.leftDown()
        case MSG_LEFT_UP:
            print("DEBUG 收到 LEFT_UP")
            Mouse.leftUp()
        case MSG_RIGHT_DOWN:
            print("DEBUG 收到 RIGHT_DOWN")
            Mouse.rightDown()
        case MSG_RIGHT_UP:
            print("DEBUG 收到 RIGHT_UP")
            Mouse.rightUp()
        case MSG_SCROLL:
            let dx = i16(payload, 1); let dy = i16(payload, 3)
            print("DEBUG 收到 SCROLL dx=\(dx) dy=\(dy)")
            Mouse.scroll(dx: dx, dy: dy)
        case MSG_GESTURE:
            print("DEBUG 收到 GESTURE id=\(payload[1])")
            handleGesture(Int8(bitPattern: payload[1]))
        default:
            print("DEBUG 收到未知消息 type=\(type)")
        }
    }
}

// MARK: - 入口

// stdout 设为行缓冲，日志重定向到文件时也能实时写入
setvbuf(stdout, nil, _IOLBF, 0)

if !AXIsProcessTrusted() {
    print("⚠️  尚未获得「辅助功能」权限，模拟输入会被系统忽略。")
    print("   请打开：系统设置 → 隐私与安全性 → 辅助功能")
    print("   勾选本程序（若从终端运行，勾选「终端」），然后重新运行本程序。")
}

let server = Server()
server.start()
dispatchMain()
