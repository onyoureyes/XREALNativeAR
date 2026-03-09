#!/bin/bash
# ADB Wireless Connect — XREAL 개발 디바이스 자동 연결
# Usage: bash scripts/adb_connect.sh
#
# 사전 설정 (USB 연결 후 1회, 재부팅 시 반복):
#   adb tcpip 5555
# 이후 WiFi 꺼도 포트 고정 (5555)

ADB="/d/Sdk/platform-tools/adb.exe"

# Tailscale IP (고정) + 고정 포트
FOLD4="100.87.7.62:5555"

echo "=== ADB Wireless Connect ==="

# USB로 연결된 디바이스가 있으면 자동으로 tcpip 5555 설정
usb_devices=$($ADB devices 2>/dev/null | grep -v "^List" | grep -v "^$" | grep -v ":" | awk '{print $1}')
if [ -n "$usb_devices" ]; then
    for serial in $usb_devices; do
        echo "USB device detected: $serial — setting tcpip 5555..."
        $ADB -s "$serial" tcpip 5555
        sleep 1
    done
fi

# Wireless connect
echo -n "Connecting $FOLD4 ... "
$ADB connect "$FOLD4" 2>&1

echo ""
$ADB devices -l
