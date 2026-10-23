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
#   pick-tests.sh --self-test
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
SELF_TEST=0

while [ $# -gt 0 ]; do
    case "$1" in
        --selection) SELECTION="${2:-}"; shift 2 ;;
        --shard)     SHARD="${2:-}"; shift 2 ;;
        --of)        OF="${2:-}"; shift 2 ;;
        --self-test) SELF_TEST=1; shift ;;
        -h|--help)   usage; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

# A test of this script, because the failure it guards against is silent.
#
# It once compared two spellings of the same class name and matched neither, so
# every shard decided it had no share of the change and the run went green having
# executed no tests. Nothing failed, nothing was logged, and the only reason it
# was ever found is that somebody ran a change whose answer was already known.
# That is what this is: the answers are known, so a wrong one is loud.
if [ "$SELF_TEST" = "1" ]; then
    fails=0
    complain() { echo "  not right: $*" >&2; fails=$((fails + 1)); }

    universe="$(bash "$HERE/test-shard.sh" --list)"
    total="$(printf '%s\n' "$universe" | grep -c .)"
    if [ "$total" -eq 0 ]; then
        complain "no test classes were found at all"
        exit 1
    fi

    # 1. Everything, dealt across two shards, is every class exactly once.
    seen=""
    for i in 0 1; do
        got="$(bash "$HERE/pick-tests.sh" --selection ALL --shard "$i" --of 2 \
            | grep -v '^--tests$' | grep -v '^$')"
        seen="$seen$got
"
    done
    placed="$(printf '%s' "$seen" | grep -c .)"
    unique="$(printf '%s' "$seen" | grep . | sort -u | wc -l)"
    [ "$placed" -eq "$total" ] || complain "ALL on 2 shards ran $placed classes of $total"
    [ "$unique" -eq "$total" ] || complain "ALL on 2 shards ran $unique distinct classes of $total"

    # 2. One class, named the way the test map names it, reaches exactly one
    #    shard — and it is the class that was named, under either spelling.
    #    This is the check that would have caught the bug.
    one="$(printf '%s\n' "$universe" | head -1)"
    short_one="${one##*.}"
    hits=0
    for i in 0 1; do
        got="$(bash "$HERE/pick-tests.sh" --selection "$short_one" --shard "$i" --of 2 \
            | grep -v '^--tests$' | grep -v '^$')"
        # "NONE" is the right answer from the shard that does not own the class,
        # so it is not a hit. Counting it was the first version's mistake: it made
        # this check report that one class had reached both shards.
        if [ -n "$got" ] && [ "$got" != "NONE" ]; then
            hits=$((hits + 1))
            [ "${got##*.}" = "$short_one" ] \
                || complain "asking for $short_one gave $got"
        fi
    done
    [ "$hits" -eq 1 ] || complain "asking for one class reached $hits shards, not 1"

    # 3. Nothing selected is nothing to do, and it is not an error.
    for i in 0 1; do
        got="$(bash "$HERE/pick-tests.sh" --selection NONE --shard "$i" --of 2)"
        [ "$got" = "NONE" ] || complain "NONE on shard $i gave '$got'"
    done

    # 4. A name that exists nowhere is an error, not silence. Silence here is
    #    exactly how a whole run comes to execute nothing and pass.
    if bash "$HERE/pick-tests.sh" --selection NoSuchTestAnywhere --shard 0 --of 2 \
            >/dev/null 2>&1; then
        complain "a selection that cannot be placed was allowed"
    fi

    if [ "$fails" -gt 0 ]; then
        echo "$fails check(s) failed in the test selector" >&2
        exit 1
    fi
    echo "  the selector agrees with itself: $total classes, one shard each"
    exit 0
fi

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
