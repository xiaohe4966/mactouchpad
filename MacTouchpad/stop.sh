#!/bin/bash
# MacTouchpad 服务停止脚本
pids=$(pgrep -x touchpad-server)
if [ -z "$pids" ]; then
    echo "服务未运行"
    exit 0
fi

kill $pids 2>/dev/null
sleep 1

if pgrep -x touchpad-server >/dev/null; then
    echo "⚠️  仍有进程未退出，请手动执行：kill -9 $(pgrep -x touchpad-server | tr '\n' ' ')"
else
    echo "✅ 服务已停止"
fi
