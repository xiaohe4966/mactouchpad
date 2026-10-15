#!/bin/bash
# MacTouchpad 服务启动脚本
cd "$(dirname "$0")"

if pgrep -x touchpad-server >/dev/null; then
    echo "服务已在运行 (PID: $(pgrep -x touchpad-server | tr '\n' ' '))"
    exit 0
fi

nohup ./touchpad-server > /tmp/mactouchpad-server.log 2>&1 &
sleep 1

if pgrep -x touchpad-server >/dev/null; then
    echo "✅ 服务已启动 (PID: $(pgrep -x touchpad-server | tr '\n' ' '))，监听端口 5907"
    echo "   日志：/tmp/mactouchpad-server.log"
else
    echo "❌ 启动失败，请查看日志：/tmp/mactouchpad-server.log"
fi
