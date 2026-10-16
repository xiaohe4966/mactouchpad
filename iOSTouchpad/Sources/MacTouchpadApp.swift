import SwiftUI
import UIKit

@main
struct MacTouchpadApp: App {
    @StateObject private var connection = Connection.shared

    init() {
        // 触控板使用中禁止自动锁屏（屏幕常亮，类似导航/游戏 App）
        UIApplication.shared.isIdleTimerDisabled = true
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(connection)
                .statusBarHidden(true)
                .persistentSystemOverlays(.hidden)
                .preferredColorScheme(.dark)
        }
    }
}

/// 包装 TouchpadView（UIKit 多指触摸）为 SwiftUI 视图
struct TouchpadRepresentable: UIViewRepresentable {
    var rotation: Int
    var moveSens: Double
    var scrollSens: Double
    var multiScale: Double
    func makeUIView(context: Context) -> TouchpadView {
        let v = TouchpadView(frame: .zero)
        v.rotationDegrees = rotation
        v.moveSensitivity = CGFloat(moveSens)
        v.scrollSensitivity = CGFloat(scrollSens)
        v.multiMoveScale = CGFloat(multiScale)
        return v
    }
    func updateUIView(_ uiView: TouchpadView, context: Context) {
        uiView.rotationDegrees = rotation
        uiView.moveSensitivity = CGFloat(moveSens)
        uiView.scrollSensitivity = CGFloat(scrollSens)
        uiView.multiMoveScale = CGFloat(multiScale)
    }
}

struct ContentView: View {
    @EnvironmentObject var connection: Connection
    @State private var manualIP = ""
    @State private var rotation: Int = UserDefaults.standard.integer(forKey: "rotation")
    @State private var showSettings = false
    @State private var moveSens: Double = ContentView.stored("moveSens", default: 1.0)
    @State private var scrollSens: Double = ContentView.stored("scrollSens", default: 0.7)
    @State private var multiScale: Double = ContentView.stored("multiScale", default: 1.6)

    /// 读取持久化灵敏度（未设置过返回默认值）
    private static func stored(_ key: String, default d: Double) -> Double {
        let v = UserDefaults.standard.double(forKey: key)
        return v == 0 ? d : v
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            TouchpadRepresentable(rotation: rotation,
                                  moveSens: moveSens,
                                  scrollSens: scrollSens,
                                  multiScale: multiScale).ignoresSafeArea()

            VStack {
                // 顶部状态 + 旋转/设置按钮
                HStack {
                    Circle()
                        .fill(connection.connected ? Color.green : Color.red)
                        .frame(width: 10, height: 10)
                    Text(connection.status)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(connection.connected ? .green : .white.opacity(0.9))
                    Spacer()
                    // 设置：灵敏度面板
                    Button {
                        showSettings = true
                    } label: {
                        Text("设置")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(.white)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(Color(white: 0.2))
                            .cornerRadius(8)
                    }
                    // 旋转：点一下顺时针 90°（记住角度，下次打开恢复）
                    Button {
                        rotation = (rotation + 90) % 360
                        UserDefaults.standard.set(rotation, forKey: "rotation")
                    } label: {
                        Text("旋转 \(rotation)°")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(.white)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(Color(white: 0.2))
                            .cornerRadius(8)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                Spacer()
                // 底部提示
                Text("单指·移动/单击  长按·拖拽  双指·滚动/右键  三指慢拖·窗口  快滑·系统  四指·系统手势")
                    .font(.system(size: 11))
                    .foregroundColor(.gray)
                    .multilineTextAlignment(.center)
                    .padding(.bottom, 20)
            }
            // 注：UI（状态/按钮/提示）固定不动；旋转只影响触摸坐标（TouchpadView 内处理）

            // 灵敏度设置面板
            if showSettings {
                VStack(spacing: 14) {
                    Text("灵敏度设置")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(.white)
                    settingRow("指针灵敏度", value: $moveSens, key: "moveSens", def: 1.0)
                    settingRow("滚动灵敏度", value: $scrollSens, key: "scrollSens", def: 0.7)
                    settingRow("三指拖移", value: $multiScale, key: "multiScale", def: 1.6)
                    Button {
                        showSettings = false
                    } label: {
                        Text("完成")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(.white)
                            .padding(.horizontal, 24)
                            .padding(.vertical, 8)
                            .background(Color.blue)
                            .cornerRadius(8)
                    }
                    .padding(.top, 4)
                }
                .padding(20)
                .background(Color(white: 0.12).opacity(0.97))
                .cornerRadius(16)
                .padding(.horizontal, 48)
            }

            // 未连接时：手动 IP 输入覆盖层
            if !connection.connected {
                VStack(spacing: 12) {
                    Text("未连接到 Mac")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(.white)
                    TextField("输入 Mac IP（如 192.168.68.80）", text: $manualIP)
                        .textFieldStyle(.roundedBorder)
                        .keyboardType(.numbersAndPunctuation)
                        .autocorrectionDisabled()
                        .frame(maxWidth: 280)
                    HStack(spacing: 16) {
                        Button("重新自动发现") {
                            manualIP = ""
                            connection.startDiscover()
                        }
                        .font(.system(size: 14))
                        Button("连接") {
                            let ip = manualIP.trimmingCharacters(in: .whitespaces)
                            guard !ip.isEmpty else { return }
                            connection.connect(host: ip)
                        }
                        .font(.system(size: 14, weight: .semibold))
                    }
                    .padding(.top, 4)
                    Text("IP 已自动填好前 3 段，只需补最后一位")
                        .font(.system(size: 11))
                        .foregroundColor(.gray)
                }
                .padding(24)
                .background(Color(white: 0.15).opacity(0.95))
                .cornerRadius(16)
                .padding(.horizontal, 32)
            }
        }
        .onAppear {
            // 记住的 IP：下次打开直接自动连接
            if let saved = UserDefaults.standard.string(forKey: "lastIP"), !saved.isEmpty {
                manualIP = saved
                connection.connect(host: saved)
            } else {
                // 首次使用：自动填充本机 IP 前缀（如 192.168.68.），并自动发现
                if manualIP.isEmpty, let ip = localIPAddress() {
                    manualIP = ip.split(separator: ".").dropLast().joined(separator: ".") + "."
                }
                connection.startDiscover()
            }
        }
    }

    /// 灵敏度调节行：− / 值 / ＋（0.1 步进，自动保存）
    private func settingRow(_ label: String, value: Binding<Double>, key: String, def: Double) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 14))
                .foregroundColor(.white)
            Spacer()
            Button {
                let v = max(0.1, round((value.wrappedValue - 0.1) * 10) / 10)
                value.wrappedValue = v
                UserDefaults.standard.set(v, forKey: key)
            } label: {
                Text("−")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.white)
                    .frame(width: 34, height: 28)
                    .background(Color(white: 0.25))
                    .cornerRadius(6)
            }
            Text(String(format: "%.1f", value.wrappedValue))
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(.white)
                .frame(width: 42)
            Button {
                let v = min(5.0, round((value.wrappedValue + 0.1) * 10) / 10)
                value.wrappedValue = v
                UserDefaults.standard.set(v, forKey: key)
            } label: {
                Text("+")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.white)
                    .frame(width: 34, height: 28)
                    .background(Color(white: 0.25))
                    .cornerRadius(6)
            }
        }
        .padding(.horizontal, 4)
    }
}
