#!/usr/bin/env bash
#
# The version code: the number Android compares to decide whether an APK is newer.
#
# It must only ever go up, because the alternative is an APK that will not install
# over the one already on the phone — and nothing tells you why.
#
# Seconds since the epoch are the natural choice and need no bookkeeping: they
# cannot go backwards, they are unique per build, and they are not derived from the
# name (a name is free-form and may go backwards; a release called 5 must still
# install over one called 46). That is why the two are separate.
#
# They have a horizon. Android holds a version code in a signed 32-bit integer, so
# the ceiling is 2,147,483,647 and epoch seconds reach it in January 2038.
#
# The first version of this handled that by switching to `1500000000 + run number`
# once the clock passed the ceiling. That is *lower* than the last value the clock
# produced, so the first build after the switch would have been treated as older
# than the build before it, and would not have installed. The failure would arrive
# in 2038 on a build nobody was watching.
#
# So the switch happens early instead, at a value chosen to leave room:
#
#   2,000,000,000  is 12 November 2033, comfortably after any code the clock will
#                  have produced by then (which is just under 2,000,000,000) and
#                  still under the ceiling. Adding the run number to it then keeps
#                  going up for another 147 million runs, which is long enough.
#
# Rule, in one line: **the code is the larger of the clock and 2e9 + run number**,
# which is monotonic across the boundary for any run number.
#
#   version-code.sh                    the code for now
#   version-code.sh --at 2147483000 --run 42    what it would be at a given time
#   version-code.sh --self-test        prove the boundary is monotonic
#
set -uo pipefail

SWITCH=2000000000   # 2033-11-12, deliberately before the 32-bit ceiling
CEILING=2147483647

compute() {
    local clock="$1" run="$2"
    if [ "$clock" -ge "$SWITCH" ]; then
        printf '%s\n' "$((SWITCH + run))"
    else
        printf '%s\n' "$clock"
    fi
}

self_test() {
    local failures=0

    check() {
        local clock="$1" run="$2" want="$3" what="$4"
        local got
        got="$(compute "$clock" "$run")"
        if [ "$got" != "$want" ]; then
            echo "  FAIL: $what — expected $want, got $got" >&2
            failures=$((failures + 1))
        fi
    }

    # Before the switch the clock is used unchanged.
    check 1700000000 7 1700000000 "an ordinary date uses the clock"

    # Exactly at the switch, and after it, the switch value plus the run number is
    # used — and it is larger than the last value the clock could produce.
    check "$SWITCH" 1 $((SWITCH + 1)) "at the switch"
    check $((SWITCH + 999)) 1 $((SWITCH + 1)) "just after the switch"

    # The boundary itself: the clock's last pre-switch value must be below every
    # post-switch value, or a phone would refuse the upgrade.
    local last_before first_after
    last_before="$(compute $((SWITCH - 1)) 999999)"
    first_after="$(compute "$SWITCH" 1)"
    if [ "$first_after" -le "$last_before" ]; then
        echo "  FAIL: the first post-switch code ($first_after) is not above the last" \
             "pre-switch one ($last_before)" >&2
        failures=$((failures + 1))
    fi

    # And everything stays inside the signed 32-bit ceiling, at the run number this
    # repository could plausibly reach.
    check "$SWITCH" 147483647 $CEILING "the last run number that still fits"

    if [ "$failures" -gt 0 ]; then
        echo "$failures version-code check(s) failed" >&2
        return 1
    fi
    echo "  the version code is monotonic across the 2033 switch and fits in 32 bits"
    return 0
}

case "${1:-}" in
    --self-test) self_test; exit $? ;;
    --at)
        [ $# -ge 3 ] || { echo "--at needs a time and --run needs a number" >&2; exit 2; }
        compute "$2" "${4:-0}"
        ;;
    "" ) compute "$(date +%s)" "${RUN_NUMBER:-0}" ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
esac
