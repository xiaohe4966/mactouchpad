import Foundation
import Network
import Darwin

/// 获取本机 WiFi 局域网 IPv4（en0 优先，仅取 192.168/10/172.16-31 私有网段）。
/// 用于自动填充 Mac IP 前缀——排除 USB/蜂窝等非 WiFi 接口的 IP。
func localIPAddress() -> String? {
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
    defer { freeifaddrs(ifaddr) }

    var en0IP: String?
    var otherIP: String?
    for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
        let flags = Int32(ptr.pointee.ifa_flags)
        var addr = ptr.pointee.ifa_addr.pointee
        if (flags & (IFF_UP | IFF_RUNNING)) == (IFF_UP | IFF_RUNNING),
           addr.sa_family == UInt8(AF_INET) {
            let name = String(cString: ptr.pointee.ifa_name)
            guard name.hasPrefix("en") else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(&addr, socklen_t(addr.sa_len), &host, socklen_t(host.count),
                              nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let ip = String(cString: host)
            guard isPrivateLAN(ip) else { continue }
            if name == "en0" {
                en0IP = ip
            } else if otherIP == nil {
                otherIP = ip
            }
        }
    }
    return en0IP ?? otherIP
}

/// 是否为局域网私有网段（与安卓版判定一致）
private func isPrivateLAN(_ ip: String) -> Bool {
    let parts = ip.split(separator: ".").compactMap { Int($0) }
    guard parts.count == 4 else { return false }
    if parts[0] == 192 && parts[1] == 168 { return true }
    if parts[0] == 10 { return true }
    if parts[0] == 172 && parts[1] >= 16 && parts[1] <= 31 { return true }
    return false
}

/// Mac 服务端连接（TCP 客户端）：优先 Bonjour 自动发现，失败可手动输 IP
final class Connection: ObservableObject {

    static let shared = Connection()

    @Published var status: String = "正在发现 Mac…"
    @Published var connected = false

    private var conn: NWConnection?
    private var browser: NWBrowser?
    private var keepaliveTimer: Timer?
    private let queue = DispatchQueue(label: "mactouchpad.ios.conn")
    private let sendQueue = DispatchQueue(label: "mactouchpad.ios.send")
    /// 当前目标（自动发现或手动）
    private var targetHost: String?

    private init() {}

    // MARK: - Bonjour 自动发现

    func startDiscover() {
        let params = NWParameters()
        params.includePeerToPeer = false
        browser = NWBrowser(for: .bonjour(type: "_mactouchpad._tcp", domain: nil), using: params)
        browser?.stateUpdateHandler = { [weak self] state in
            if case .failed(let e) = state {
                DispatchQueue.main.async {
                    self?.status = "自动发现失败：\(e.localizedDescription)（请手动输 IP）"
                }
            }
        }
        browser?.browseResultsChangedHandler = { [weak self] results, _ in
            let endpoints = results.compactMap { result -> String? in
                guard case .hostPort(let host, let port) = result.endpoint else { return nil }
                return "\(host):\(port.rawValue)"
            }
            if let first = endpoints.first {
                self?.browser?.cancel()
                self?.browser = nil
                let parts = first.split(separator: ":")
                if let h = parts.first {
                    self?.connect(host: String(h))
                }
            }
        }
        browser?.start(queue: queue)
        // 5 秒后如果还没发现，提示手动输入
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
            if self?.browser != nil {
                self?.status = "未自动发现 Mac，请手动输入 IP"
            }
        }
    }

    // MARK: - 手动连接

    func connect(host: String, port: UInt16 = 5907) {
        targetHost = host
        disconnect()
        let c = NWConnection(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: port)!,
            using: .tcp
        )
        c.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                switch state {
                case .ready:
                    self?.status = "已连接 \(host)"
                    self?.connected = true
                    // 连接成功自动记住 IP，下次打开直接自动连接
                    UserDefaults.standard.set(host, forKey: "lastIP")
                case .failed(let e):
                    self?.status = "连接失败：\(e.localizedDescription)"
                    self?.connected = false
                case .waiting:
                    self?.status = "等待连接 \(host)…"
                default:
                    break
                }
            }
        }
        c.start(queue: queue)
        conn = c
        startKeepalive()
    }

    func disconnect() {
        conn?.cancel()
        conn = nil
        keepaliveTimer?.invalidate()
        keepaliveTimer = nil
        DispatchQueue.main.async { self.connected = false }
    }

    // MARK: - 发送

    func send(_ data: Data) {
        guard let c = conn, c.state == .ready else { return }
        sendQueue.async {
            c.send(content: data, completion: .contentProcessed { _ in })
        }
    }

    /// 空闲保活：每 5 秒发 0x00，防 Mac 端读超时断开
    private func startKeepalive() {
        keepaliveTimer?.invalidate()
        let t = Timer(timeInterval: 5, repeats: true) { [weak self] _ in
            self?.send(Proto.keepalive)
        }
        keepaliveTimer = t
        RunLoop.main.add(t, forMode: .common)
    }
}
