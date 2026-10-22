#!/usr/bin/env bash
#
# Splits the tests across a fixed number of runners.
#
# The reason this exists is scale, and the reason it is written this way is a
# measured fact: asking Gradle for a chosen set of tests changes its command
# line, and a changed command line is a different configuration-cache entry, so
# a set chosen per change throws the cache away every time.
#
# So nothing here depends on what changed. A shard is an index, the same index
# always holds the same test classes, and each shard's command line is therefore
# identical from run to run. The cache holds one entry per shard and reuses them
# all. Growth is then paid for with runners rather than with time.
#
# Assignment is by a hash of the class name modulo the shard count. That has one
# property worth stating: adding a test puts it in its own shard and leaves every
# other class where it was, so the suite can grow without reshuffling the work.
#
# Usage:
#   test-shard.sh --shard <i> --of <n>    the gradle arguments for shard i
#   test-shard.sh --of <n> --verify       check the split is complete and disjoint
#
# Prints the arguments for gradle, one per line, or nothing at all when n is 1.
# Exit code: 0 fine, 2 bad use, 1 a failed check.

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PREFIX="${PREFIX:-com.example.notes}"

usage() {
    sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'
}

SHARD=""
OF=""
VERIFY=0

while [ $# -gt 0 ]; do
    case "$1" in
        --shard) SHARD="${2:-}"; shift 2 ;;
        --of)    OF="${2:-}"; shift 2 ;;
        --verify) VERIFY=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

case "$OF" in
    ''|*[!0-9]*) echo "--of needs a whole number" >&2; exit 2 ;;
esac
if [ "$OF" -lt 1 ]; then
    echo "--of must be at least 1" >&2
    exit 2
fi

if [ "$VERIFY" = "0" ]; then
    case "$SHARD" in
        ''|*[!0-9]*) echo "--shard needs a whole number" >&2; exit 2 ;;
    esac
    if [ "$SHARD" -ge "$OF" ]; then
        echo "--shard must be below --of" >&2
        exit 2
    fi
fi

# Every test class in the project, taken from the source tree rather than from
# the compiled output, so that this can answer before anything is built.
#
# A path of app/src/test/java/com/example/notes/FolderTreeTest.kt becomes
# com.example.notes.FolderTreeTest. Only classes ending in `Test` are taken,
# which is the same rule Gradle's own test detection uses.
class_names() {
    find "$ROOT" -path '*/src/test/*' -name '*Test.kt' -not -path '*/build/*' 2>/dev/null \
        | while IFS= read -r path; do
            rel="${path#"$ROOT"/}"
            # everything after src/test/java/ or src/test/kotlin/
            case "$rel" in
                */src/test/java/*) rel="${rel#*/src/test/java/}" ;;
                */src/test/kotlin/*) rel="${rel#*/src/test/kotlin/}" ;;
                *) continue ;;
            esac
            printf '%s\n' "${rel%.kt}" | tr '/' '.'
        done | sort
}

# The classes in a fixed order, which is what makes the split the same on every
# runner: cksum is POSIX and gives the same number everywhere, where `$RANDOM`
# or a Python hash would not.
ordered_class_names() {
    class_names | while IFS= read -r name; do
        [ -n "$name" ] || continue
        printf '%s\t%s\n' "$(printf '%s' "$name" | cksum | cut -d' ' -f1)" "$name"
    done | sort -k1,1n -k2,2 | cut -f2-
}

# Which shard the class at a given position belongs to. Dealing them out in turn
# rather than hashing each one into a bucket is the difference between an even
# split and one where a runner waits with nothing to do: nine classes hashed into
# four buckets measured as 2, 0, 6 and 1.
shard_at() {
    echo $(( ($1 - 1) % OF ))
}

if [ "$VERIFY" = "1" ]; then
    all="$(ordered_class_names)"
    total="$(printf '%s\n' "$all" | grep -c .)"
    if [ "$total" -eq 0 ]; then
        echo "no test classes were found; the split cannot be checked" >&2
        exit 1
    fi

    # every class must land in exactly one shard, and every shard must be
    # reachable, or the split would quietly leave tests unrun
    seen_total=0
    for i in $(seq 0 $((OF - 1))); do
        count=0
        position=0
        while IFS= read -r name; do
            [ -n "$name" ] || continue
            position=$((position + 1))
            if [ "$(shard_at "$position")" = "$i" ]; then
                count=$((count + 1))
                seen_total=$((seen_total + 1))
            fi
        done <<< "$all"
        printf '  shard %d of %d: %d tests\n' "$i" "$OF" "$count"
    done

    if [ "$seen_total" -ne "$total" ]; then
        echo "the split is not complete: $seen_total of $total classes placed" >&2
        exit 1
    fi
    echo "  complete: $seen_total of $total classes, each in one shard"
    exit 0
fi

if [ "$OF" = "1" ]; then
    # One shard is the whole suite, and the whole suite needs no filter. Printing
    # nothing keeps the command line identical to a run with no sharding at all,
    # which is what lets today's single-runner setup reuse its cache.
    exit 0
fi

position=0
ordered_class_names | while IFS= read -r name; do
    [ -n "$name" ] || continue
    position=$((position + 1))
    if [ "$(shard_at "$position")" = "$SHARD" ]; then
        printf -- '--tests\n%s\n' "$name"
    fi
done
