import UIKit

/// 全屏触控板视图（UIKit 多指触摸，逻辑与安卓版一致）
final class TouchpadView: UIView {

    // ---- 灵敏度（默认与安卓一致） ----
    var moveSensitivity: CGFloat = 1.0
    var scrollSensitivity: CGFloat = 0.7
    var multiMoveScale: CGFloat = 1.6

    /// 旋转角度（0/90/180/270）：触摸坐标反向旋转，保持手指方向与光标方向一致
    var rotationDegrees: Int = 0

    private let clickSlop: CGFloat = 8
    private let scrollStartSlop: CGFloat = 6
    private let gestureSlop: CGFloat = 50
    private let longPressMs: TimeInterval = 0.28
    private let tapTimeoutMs: TimeInterval = 0.4
    private let swipeVelocityPtsPerSec: CGFloat = 500
    private let classifyWindowMs: TimeInterval = 0.25

    private var points: [Int: Point] = [:]        // touch hash → 状态
    private enum Mode { case idle, single, two, multi }
    private var mode: Mode = .idle

    // 单指 / 双指
    private var pressStart: TimeInterval = 0
    private var buttonDown = false
    private var rightPending = false
    private var twoScrolled = false
    private var twoDist: CGFloat = 0
    private var lastMidX: CGFloat?
    private var lastMidY: CGFloat?

    // 多指
    private var multiActive = false
    private var multiMax = 0
    private var multiStartX: CGFloat = 0
    private var multiStartY: CGFloat = 0
    private var multiLastX: CGFloat = 0
    private var multiLastY: CGFloat = 0
    private var multiLastT: TimeInterval = 0
    private var multiFirstT: TimeInterval = 0
    private var multiLeftDown = false
    private enum MultiClass { case none, drag, swipe }
    private var multiClass: MultiClass = .none

    // 长按定时器
    private var longPressTimer: Timer?

    struct Point {
        var x: CGFloat
        var y: CGFloat
        var lastX: CGFloat
        var lastY: CGFloat
        var moved: CGFloat
        var downX: CGFloat
        var downY: CGFloat
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        isMultipleTouchEnabled = true
    }
    required init?(coder: NSCoder) { fatalError() }

    // MARK: - 触摸事件

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            let p = rotate(t.location(in: self))
            points[t.hash] = Point(x: p.x, y: p.y, lastX: p.x, lastY: p.y, moved: 0, downX: p.x, downY: p.y)
        }
        let n = points.count
        if n == 1 {
            mode = .single
            pressStart = now()
            scheduleLongPress()
        } else if n == 2 {
            mode = .two
            rightPending = true
            twoScrolled = false
            twoDist = 0
            lastMidX = nil; lastMidY = nil
            cancelLongPress()
        } else if n >= 3 {
            cancelLongPress()
            if !multiActive {
                multiActive = true
                multiMax = n
                multiClass = .none
                let (cx, cy) = centroid()
                multiStartX = cx; multiStartY = cy
                multiLastX = cx; multiLastY = cy
                multiLastT = now()
                multiFirstT = 0
            } else {
                multiMax = max(multiMax, n)
            }
            mode = .multi
            rightPending = false
            if buttonDown { send(Proto.leftUp()); buttonDown = false }
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        var anyChange = false
        for t in touches {
            guard var p = points[t.hash] else { continue }
            let loc = rotate(t.location(in: self))
            p.moved += abs(loc.x - p.x) + abs(loc.y - p.y)
            p.lastX = p.x; p.lastY = p.y
            p.x = loc.x; p.y = loc.y
            points[t.hash] = p
            anyChange = true
        }
        guard anyChange else { return }

        switch mode {
        case .single:
            guard !multiActive, let p = points.values.first else { return }
            if p.moved < clickSlop { return }
            cancelLongPress()
            rightPending = false
            let dx = (p.x - p.lastX) * moveSensitivity
            let dy = (p.y - p.lastY) * moveSensitivity
            send(Proto.move(Int16(dx), Int16(dy)))
        case .two:
            guard points.count == 2 else { return }
            let (mx, my) = centroid()
            if let lx = lastMidX, let ly = lastMidY {
                let dx = mx - lx
                let dy = my - ly
                twoDist += abs(dx) + abs(dy)
                if twoDist >= scrollStartSlop {
                    rightPending = false
                    twoScrolled = true
                    send(Proto.scroll(Int16(dx * scrollSensitivity), Int16(-dy * scrollSensitivity)))
                }
            }
            lastMidX = mx; lastMidY = my
        case .multi:
            guard points.count >= 3 else { return }
            let (cx, cy) = centroid()
            let nowT = now()
            if multiFirstT == 0 {
                multiFirstT = nowT
                multiLastT = nowT
                multiLastX = cx; multiLastY = cy
                return
            }
            let dt = max(0.001, nowT - multiLastT)
            let dist = hypot(cx - multiLastX, cy - multiLastY)
            let vel = dist / dt
            if multiClass == .none {
                if multiMax >= 4 {
                    multiClass = .swipe
                } else if vel >= swipeVelocityPtsPerSec {
                    multiClass = .swipe
                } else if nowT - multiFirstT >= classifyWindowMs {
                    multiClass = .drag
                }
            }
            if multiClass == .drag {
                if !multiLeftDown {
                    multiLeftDown = true
                    send(Proto.leftDown())
                }
                let dx = cx - multiLastX
                let dy = cy - multiLastY
                if abs(dx) > 0.5 || abs(dy) > 0.5 {
                    send(Proto.move(Int16(dx * multiMoveScale), Int16(dy * multiMoveScale)))
                }
            }
            multiLastX = cx; multiLastY = cy
            multiLastT = nowT
        case .idle:
            break
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        // 先记录抬起手指的累计移动（用于单击判定：移动超阈值不算点击）
        var liftedMoved: CGFloat = 0
        var hadLift = false
        for t in touches {
            if let p = points[t.hash] {
                liftedMoved = p.moved
                hadLift = true
            }
            points[t.hash] = nil
        }
        cancelLongPress()
        let n = points.count
        if multiActive && n == 1 {
            mode = .single
        }
        if n == 0 {
            finishGesture(liftedMoved: liftedMoved, hadLift: hadLift)
        }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        points.removeAll()
        resetAll()
    }

    // MARK: - 收尾

    private func finishGesture(liftedMoved: CGFloat = 0, hadLift: Bool = false) {
        if multiActive {
            let dx = multiLastX - multiStartX
            let dy = multiLastY - multiStartY
            if multiClass == .drag, multiLeftDown {
                multiLeftDown = false
                send(Proto.leftUp())
            } else if multiClass == .swipe,
                      abs(dx) >= gestureSlop || abs(dy) >= gestureSlop {
                let id: Int8
                if abs(dy) > abs(dx) {
                    id = dy < 0 ? (multiMax >= 4 ? GestureID.launchpad : GestureID.missionControl)
                                : GestureID.showDesktop
                } else {
                    id = dx < 0 ? GestureID.switchLeft : GestureID.switchRight
                }
                send(Proto.gesture(id))
            }
        } else if rightPending {
            rightPending = false
            send(Proto.rightDown())
            send(Proto.rightUp())
        } else if twoScrolled {
            twoScrolled = false
            // 滚动收尾：不触发单击
        } else if mode == .single, !buttonDown, hadLift,
                  liftedMoved < clickSlop,
                  Date().timeIntervalSinceReferenceDate - pressStart < tapTimeoutMs {
            // 单指轻点（未移动、短按）→ 左键
            send(Proto.leftDown())
            send(Proto.leftUp())
        }
        resetAll()
    }

    // MARK: - 长按拖拽（单指按住 280ms 按下左键，随后移动拖拽）

    private func scheduleLongPress() {
        cancelLongPress()
        let timer = Timer(timeInterval: longPressMs, repeats: false) { [weak self] _ in
            guard let self, self.mode == .single, self.points.count == 1,
                  let p = self.points.values.first, p.moved < self.clickSlop else { return }
            self.buttonDown = true
            self.send(Proto.leftDown())
        }
        longPressTimer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    private func cancelLongPress() {
        longPressTimer?.invalidate()
        longPressTimer = nil
    }

    // MARK: - 辅助

    /// 按旋转角度反向旋转触摸坐标（与安卓版 onTouchEvent 的 transform 一致）
    private func rotate(_ p: CGPoint) -> CGPoint {
        guard rotationDegrees != 0 else { return p }
        let r = Double(rotationDegrees) * .pi / 180
        let cx = bounds.midX, cy = bounds.midY
        let dx = p.x - cx, dy = p.y - cy
        let cosR = cos(r), sinR = sin(r)
        // 反向旋转（-rotationDegrees）：用户以旋转视角触摸 → 还原到 view 坐标系
        return CGPoint(x: cx + dx * cosR + dy * sinR,
                       y: cy - dx * sinR + dy * cosR)
    }

    private func centroid() -> (CGFloat, CGFloat) {
        var sx: CGFloat = 0, sy: CGFloat = 0
        for p in points.values { sx += p.x; sy += p.y }
        let n = CGFloat(points.count)
        return n > 0 ? (sx / n, sy / n) : (0, 0)
    }

    private func now() -> TimeInterval { Date().timeIntervalSinceReferenceDate }

    private func send(_ data: Data) { Connection.shared.send(data) }

    private func resetAll() {
        points.removeAll()
        mode = .idle
        rightPending = false
        twoScrolled = false
        multiActive = false
        multiMax = 0
        multiClass = .none
        multiFirstT = 0
        buttonDown = false
        multiLeftDown = false
        lastMidX = nil; lastMidY = nil
        cancelLongPress()
    }
}
