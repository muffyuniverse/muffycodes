#!/usr/bin/env bash
#
# Drives the app with taps and swipes and takes a picture of every step.
#
# The tour is a list of steps. A step is one of:
#
#   tap    X Y   [name]      a tap on a point of the screen
#   taptext  words [name]    a tap on the text that is shown
#   swipe  X1 Y1 X2 Y2 [ms] [name]   a swipe from one point to another
#   text   words            typing into the field that is open
#   key    KEYCODE          a key, e.g. BACK, ENTER
#   shot   name             a picture without touching the screen
#   wait   seconds          a pause
#
# The points are given for a screen of 1080 wide and 2400 high. They are
# scaled to the screen of the device that is actually used, so the same tour
# works on any phone.
#
# A tap on a point is the simple way, but it breaks when the layout moves.
# A tap on a text keeps working, because the app is asked where that text
# is. Both are allowed in the same tour.
#
# The app must be installed and started before the tour begins.
#
set -uo pipefail

# --- where the pictures go -------------------------------------------------
SHOT_DIR="${SHOT_DIR:-screenshots}"
PACKAGE="${PACKAGE:-com.example.notes}"
ACTIVITY="${ACTIVITY:-com.example.notes.MainActivity}"
ADB="${ADB:-adb}"

# --- the size the points in the tour are written for ------------------------
BASE_W=1080
BASE_H=2400

adb() { "$ADB" "$@"; }

# Runs adb in a session of its own. The emulator's adb sometimes crashes,
# and when it does it can take the whole tour down with it. A session of its
# own keeps the crash where it belongs.
if command -v setsid >/dev/null 2>&1; then
    adb() { setsid "$ADB" "$@"; }
fi

# Every command that talks to the device is written down, so that a problem
# can be found afterwards.
TRACE_FILE="${TRACE_FILE:-}"

trace() {
    [ -z "$TRACE_FILE" ] && return 0
    echo "$(date +%H:%M:%S) $*" >> "$TRACE_FILE"
}

# Runs a command that talks to the device, and tries again when it does not
# work. The emulator is sometimes not ready yet, even after it says it has
# booted.
retry() {
    local tries=3 i
    for i in $(seq 1 "$tries"); do
        if "$@"; then
            return 0
        fi
        echo "  .. trying again ($i of $tries)" >&2
        sleep 3
    done
    return 1
}

# Waits until the device answers.
wait_for_device() {
    local i
    for i in $(seq 1 30); do
        if adb shell true >/dev/null 2>&1; then
            trace "the device answers"
            return 0
        fi
        sleep 2
    done
    echo "!! the device did not answer" >&2
    return 1
}

# The size of the screen of the device, and the factor to scale the points by.
read_screen_size() {
    local size w h
    size="$(adb shell wm size 2>/dev/null | tr -d '\r')"
    w="$(printf '%s' "$size" | sed -n 's/.*Override size: \([0-9]*\)x\([0-9]*\).*/\1/p')"
    h="$(printf '%s' "$size" | sed -n 's/.*Override size: \([0-9]*\)x\([0-9]*\).*/\2/p')"
    if [ -z "$w" ] || [ -z "$h" ]; then
        w="$(printf '%s' "$size" | sed -n 's/.*Physical size: \([0-9]*\)x\([0-9]*\).*/\1/p')"
        h="$(printf '%s' "$size" | sed -n 's/.*Physical size: \([0-9]*\)x\([0-9]*\).*/\2/p')"
    fi
    W="${w:-1080}"
    H="${h:-2400}"
    SCALE_X="$(awk -v a="$W" -v b="$BASE_W" 'BEGIN{printf "%.4f", a/b}')"
    SCALE_Y="$(awk -v a="$H" -v b="$BASE_H" 'BEGIN{printf "%.4f", a/b}')"
    echo "The screen of the device is ${W}x${H}; the points are scaled by ${SCALE_X} and ${SCALE_Y}."
}

sx() { awk -v v="$1" -v s="$SCALE_X" 'BEGIN{printf "%d", v*s}'; }
sy() { awk -v v="$1" -v s="$SCALE_Y" 'BEGIN{printf "%d", v*s}'; }

step_number=0
SHOT_FILE=""

take_shot() {
    local name="$1"
    step_number=$((step_number + 1))
    SHOT_FILE="$SHOT_DIR/$(printf '%02d-%s' "$step_number" "$name").png"
    # A moment of waiting, so the screen has finished moving.
    sleep 1

    # Sometimes the connection to the emulator is lost for a moment, so the
    # picture is taken again when it fails.
    local attempt
    for attempt in 1 2 3; do
        trace "picture: $SHOT_FILE"
        if capture_picture; then
            echo "  picture: $SHOT_FILE"
            return 0
        fi
        echo "  .. the picture failed, trying again ($attempt of 3)" >&2
        sleep 3
    done
    echo "  !! the picture failed: $SHOT_FILE" >&2
    return 1
}

# Takes a picture of the screen.
#
# The picture is first made on the device and then fetched, because the
# straight way of reading it out can come back empty when the emulator is
# busy.
capture_picture() {
    local remote="/sdcard/muffy-shot.png"

    adb shell screencap -p "$remote" >/dev/null 2>&1 || return 1
    adb pull "$remote" "$SHOT_FILE" >/dev/null 2>&1 || return 1
    adb shell rm -f "$remote" >/dev/null 2>&1 || true

    [ -s "$SHOT_FILE" ]
}

# Asks the app where a piece of text is on the screen, and answers with the
# middle of it as "x,y". The screen is read with uiautomator, which knows
# the bounds of every piece of text that is shown.
find_text_bounds() {
    local wanted="$1"
    local dump node box l t r b
    dump="$(adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1; adb shell cat /sdcard/window.xml 2>/dev/null | tr -d '\r')"
    [ -z "$dump" ] && return 0

    # The text may be the text of a label, the label of a button that has no
    # text of its own (a picture button, for example), or the hint of a field
    # that is empty.
    node="$(printf '%s' "$dump" | tr '>' '\n' | while IFS= read -r n; do
        case "$n" in
            *"text=\"$wanted\""*|*"content-desc=\"$wanted\""*|*"hint=\"$wanted\""*) printf '%s\n' "$n"; break ;;
        esac
    done)"
    [ -z "$node" ] && return 0

    # The bounds look like [left,top][right,bottom].
    box="$(printf '%s' "$node" | sed -n 's/.*bounds="\([^"]*\)".*/\1/p')"
    box="${box//[\[\]]/}"
    box="${box//,/ }"
    read -r l t r b <<<"$box"
    [ -z "$l" ] || [ -z "$t" ] || [ -z "$r" ] || [ -z "$b" ] && return 0
    printf '%d,%d\n' $(( (l + r) / 2 )) $(( (t + b) / 2 ))
}

# Writes the tree of the screen to a file, so that the steps of a tour can be
# written with the real names that the app shows.
dump_screen() {
    adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1
    adb shell cat /sdcard/window.xml 2>/dev/null | tr -d '\r' \
        | sed 's/></>\n</g' \
        | grep -oE '(text|content-desc|hint)="[^"]*"' \
        | sort -u
}

# --- the tour ---------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

run_tour() {
    local line verb rest
    # The tour is read from the file, and the file is read again for every
    # line. The commands that talk to the device would otherwise eat the
    # lines that are still to come, because they read from the same place.
    local tour_file="${TOUR_FILE:-}"
    if [ -z "$tour_file" ] || [ ! -f "$tour_file" ]; then
        tour_file="$SCRIPT_DIR/default-tour.txt"
    fi

    while IFS= read -r line <&3; do
        # Skip the empty lines and the comments.
        case "$line" in
            ''|'#'*) continue ;;
        esac

        verb="$(printf '%s' "$line" | awk '{print $1}')"
        rest="$(printf '%s' "$line" | cut -d' ' -f2-)"

        case "$verb" in
            tap)
                local x y name
                read -r x y name <<<"$rest"
                [ -z "$name" ] && name="tap"
                echo "tap at ${x},${y}"
                adb shell input tap "$(sx "$x")" "$(sy "$y")" || true
                take_shot "$name"
                ;;
            taptext)
                # The place of the text is asked from the app itself, so the
                # tap lands on the right place even when the layout moved.
                # The text may be in quotes, so that it can have spaces.
                local wanted name bounds x y
                if [[ "$rest" == \"*\"* ]]; then
                    wanted="${rest%%\" *}"
                    wanted="${wanted#\"}"
                    # What is left is the name of the picture.
                    name="${rest#*\" }"
                    name="${name%% *}"
                else
                    wanted="${rest%% *}"
                    name="${rest#* }"
                fi
                [ "$name" = "$rest" ] || [ -z "$name" ] && name="taptext"
                bounds="$(find_text_bounds "$wanted")"
                if [ -z "$bounds" ]; then
                    echo "  !! the text was not found: $wanted" >&2
                    continue
                fi
                x="$(printf '%s' "$bounds" | cut -d, -f1)"
                y="$(printf '%s' "$bounds" | cut -d, -f2)"
                echo "tap on the text \"$wanted\" at ${x},${y}"
                adb shell input tap "$x" "$y" || true
                take_shot "$name"
                ;;
            swipe)
                local x1 y1 x2 y2 ms name
                read -r x1 y1 x2 y2 ms name <<<"$rest"
                [ -z "$ms" ] && ms=300
                [ -z "$name" ] && name="swipe"
                echo "swipe ${x1},${y1} -> ${x2},${y2}"
                adb shell input swipe "$(sx "$x1")" "$(sy "$y1")" "$(sx "$x2")" "$(sy "$y2")" "$ms" || true
                take_shot "$name"
                ;;
            text)
                echo "typing: $rest"
                adb shell input text "${rest// /%s}" || true
                ;;
            key)
                echo "key: $rest"
                adb shell input keyevent "$rest" || true
                ;;
            dump)
                # The names that are on the screen, so that a tour can be
                # written with them.
                dump_screen
                ;;
            dumpfile)
                # The names that are on the screen, written to a file, so that
                # they can be looked at later.
                local file
                file="$SHOT_DIR/$(printf '%02d-%s' "$step_number" "$rest").txt"
                dump_screen > "$file"
                echo "  names: $file"
                ;;
            shot)
                take_shot "$rest"
                ;;
            wait)
                echo "waiting ${rest} seconds"
                sleep "$rest"
                ;;
            *)
                echo "?? unknown step: $line" >&2
                ;;
        esac
    done 3< "$tour_file"
}

# --- start ------------------------------------------------------------------
mkdir -p "$SHOT_DIR"
read_screen_size

if [ -z "${SKIP_LAUNCH:-}" ]; then
    echo "Starting the app..."
    wait_for_device
    # The app is stopped first, so that the tour always starts at the same
    # place. A failure here is not fatal: the app may already be running.
    trace "stopping the app"
    adb shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
    sleep 1
    trace "starting the app"
    retry adb shell am start -n "$PACKAGE/$ACTIVITY" >/dev/null 2>&1 || true
    sleep 4
fi

run_tour

echo "The tour is done. The pictures are in $SHOT_DIR."

# The count of the pictures is written out, so that the run can tell whether
# the tour made anything.
find "$SHOT_DIR" -name '*.png' -size +0c 2>/dev/null | wc -l | tr -d ' ' > "$SHOT_DIR/count.txt" 2>/dev/null || echo "0" > "$SHOT_DIR/count.txt"
