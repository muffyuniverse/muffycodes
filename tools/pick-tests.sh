#!/usr/bin/env bash
#
# Works out what one shard has to do, on this change.
#
# Two ideas meet here and they used to cancel each other out:
#
#   * **Targeting** — a change cannot affect a test that does not touch what it
#     changed, so only the affected classes run. The selector (changed-tests.sh)
#     decides which, and falls back to everything for anything it cannot place.
#
#   * **Sharding** — the affected classes are dealt across a fixed number of
#     runners, so their cost is divided instead of added to.
#
# Targeting alone leaves a long suite on one runner; sharding alone makes every
# runner pay for tests the change could not have broken. Together they are the
# flat-scaling law: what is left to run is small, and if it is not small it is
# spread.
#
# The split is by a fixed position in a fixed order (test-shard.sh), so a shard
# keeps the same tests from run to run and its configuration cache stays warm.
# What changes is only which of them are worth running — and a shard with
# nothing to do says so and stops, rather than paying for a build.
#
# Usage:
#   pick-tests.sh --selection <ALL|NONE|"A B C"> --shard <i> --of <n>
#
# Prints one of:
#   NONE                     this shard has nothing to run
#   --tests\nA\n--tests\nB   the gradle arguments for this shard
#
# Exit code: 0 fine, 2 bad use.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

usage() {
    sed -n '2,29p' "$0" | sed 's/^# \{0,1\}//'
}

SELECTION=""
SHARD=""
OF="1"

while [ $# -gt 0 ]; do
    case "$1" in
        --selection) SELECTION="${2:-}"; shift 2 ;;
        --shard)     SHARD="${2:-}"; shift 2 ;;
        --of)        OF="${2:-}"; shift 2 ;;
        -h|--help)   usage; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

if [ -z "$SHARD" ]; then
    echo "--shard is required" >&2
    exit 2
fi
case "$SHARD" in
    ''|*[!0-9]*) echo "--shard needs a whole number" >&2; exit 2 ;;
esac
case "$OF" in
    ''|*[!0-9]*) echo "--of needs a whole number" >&2; exit 2 ;;
esac
if [ "$SHARD" -ge "$OF" ]; then
    echo "--shard must be below --of" >&2
    exit 2
fi

# The whole answer, as the shard script would give it: nothing when there is one
# shard, and the class list otherwise.
shard_args="$(bash "$HERE/test-shard.sh" --shard "$SHARD" --of "$OF")"

if [ "$OF" = "1" ]; then
    # One shard: the names come from the selection instead of the shard script,
    # because with one shard there is no split to intersect with.
    if [ -z "$SELECTION" ] || [ "$SELECTION" = "NONE" ]; then
        echo "NONE"
        exit 0
    fi
    if [ "$SELECTION" = "ALL" ]; then
        # Everything: print nothing, so the command line stays exactly what an
        # unfiltered run has and its configuration cache remains valid.
        exit 0
    fi
    for name in $SELECTION; do
        printf -- '--tests\n%s\n' "$name"
    done
    exit 0
fi

# Several shards: the shard's own list, narrowed to the selection.
#
# The two sides spell a class differently, and it cost every targeted pull
# request its tests. The selector names a class the way tools/test-map.txt does
# — `HistoryFeatureTest` — and the shard script names it the way a file path
# becomes a class — `com.example.notes.HistoryFeatureTest`. Compared literally
# they never match, so every shard decided it had no share of the change, and
# the run went green having executed nothing at all.
#
# So both sides are reduced to the name without its package before comparing.
all_classes() {
    printf '%s\n' "$shard_args" | grep -v '^--tests$' | grep -v '^$'
}

short_name() {
    printf '%s' "${1##*.}"
}

# True when the selection names this class, under either spelling.
selected_has() {
    case "$SELECTION" in
        ALL) return 0 ;;
        ''|NONE) return 1 ;;
    esac
    local wanted got
    wanted="$(short_name "$1")"
    for got in $SELECTION; do
        if [ "$(short_name "$got")" = "$wanted" ]; then
            return 0
        fi
    done
    return 1
}

found=0
while IFS= read -r name; do
    [ -n "$name" ] || continue
    if selected_has "$name"; then
        printf -- '--tests\n%s\n' "$name"
        found=$((found + 1))
    fi
done <<< "$(all_classes)"

if [ "$found" -eq 0 ]; then
    # Before answering "nothing to do", check the selection can be placed at all.
    # A class the shard split has never heard of means the test map and the tests
    # have drifted apart, and the honest answer to that is to stop: every shard
    # would say NONE, no test would run, and the run would go green.
    if [ -n "$SELECTION" ] && [ "$SELECTION" != "NONE" ] && [ "$SELECTION" != "ALL" ]; then
        universe="$(bash "$HERE/test-shard.sh" --list)"
        for want in $SELECTION; do
            placed=0
            while IFS= read -r name; do
                [ -n "$name" ] || continue
                if [ "$(short_name "$name")" = "$(short_name "$want")" ]; then
                    placed=1
                    break
                fi
            done <<< "$universe"
            if [ "$placed" = "0" ]; then
                echo "the selection names $want, but no test class has that name;" >&2
                echo "the test map and the tests have drifted apart, so nothing would run." >&2
                exit 3
            fi
        done
    fi
    echo "NONE"
fi
