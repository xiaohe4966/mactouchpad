import Foundation

/// 与 Mac 服务端一致的二进制协议（大端序，每条 = 1 字节类型 + 载荷）
enum Proto {
    static func move(_ dx: Int16, _ dy: Int16) -> Data {
        var d = Data([0x01])
        d.append(contentsOf: byte2(dx))
        d.append(contentsOf: byte2(dy))
        return d
    }
    static func leftDown() -> Data { Data([0x02]) }
    static func leftUp() -> Data { Data([0x03]) }
    static func rightDown() -> Data { Data([0x04]) }
    static func rightUp() -> Data { Data([0x05]) }
    static func scroll(_ dx: Int16, _ dy: Int16) -> Data {
        var d = Data([0x06])
        d.append(contentsOf: byte2(dx))
        d.append(contentsOf: byte2(dy))
        return d
    }
    static func gesture(_ id: Int8) -> Data {
        Data([0x07, UInt8(bitPattern: id)])
    }
    /// 保活包：服务端忽略，用于防止 Mac 端 5 秒读超时断开
    static let keepalive = Data([0x00])

    private static func byte2(_ v: Int16) -> [UInt8] {
        [UInt8(truncatingIfNeeded: v >> 8), UInt8(truncatingIfNeeded: v & 0xFF)]
    }
}

/// 手势 id（与安卓端一致）
enum GestureID {
    static let missionControl: Int8 = 1   // 调度中心
    static let switchLeft: Int8 = 2       // 上一桌面
    static let switchRight: Int8 = 3      // 下一桌面
    static let showDesktop: Int8 = 4      // 显示桌面
    static let launchpad: Int8 = 5        // 启动台
}
