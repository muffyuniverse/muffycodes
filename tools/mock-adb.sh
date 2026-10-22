#!/usr/bin/env bash
# A fake adb, used to test the tour script without a device.
# It records what was asked of it and pretends there is a screen.
set -uo pipefail
LOG="${MOCK_ADB_LOG:-/tmp/mock-adb.log}"
echo "=== $*" >> "$LOG"

case "$1 $2" in
    "shell wm")
        echo "Physical size: 1080x2400"
        ;;
    "shell uiautomator")
        echo "UI hierchary dumped to: /sdcard/window.xml"
        ;;
    "shell cat")
        cat "${MOCK_ADB_XML:-/tmp/mock-window.xml}" 2>/dev/null
        ;;
    "shell screencap")
        # Pretend the picture was made on the device.
        echo "created on the device"
        ;;
    "shell rm")
        echo ""
        ;;
    "pull "*)
        # A one-pixel PNG, so the file is not empty.
        python3 -c "
import base64,sys
png=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==')
open(sys.argv[1],'wb').write(png)
" "${3:-/tmp/shot.png}"
        ;;
    "exec-out screencap")
        python3 -c "
import base64,sys
png=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==')
sys.stdout.buffer.write(png)
"
        ;;
esac
exit 0
