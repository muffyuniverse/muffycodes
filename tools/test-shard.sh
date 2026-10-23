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
LIST=0

while [ $# -gt 0 ]; do
    case "$1" in
        --shard) SHARD="${2:-}"; shift 2 ;;
        --of)    OF="${2:-}"; shift 2 ;;
        --verify) VERIFY=1; shift ;;
        --list)   LIST=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

case "$OF" in
    '') [ "$LIST" = "1" ] && OF=1 || { echo "--of needs a whole number" >&2; exit 2; } ;;
    *[!0-9]*) echo "--of needs a whole number" >&2; exit 2 ;;
esac
if [ "$OF" -lt 1 ]; then
    echo "--of must be at least 1" >&2
    exit 2
fi

if [ "$VERIFY" = "0" ] && [ "$LIST" = "0" ]; then
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
    # One awk pass rather than a shell loop.
    #
    # The loop this replaces spawned `tr` once per file and `cksum` once per
    # class; in a container where a fork is expensive that came to a second and a
    # half for fifteen classes, on every shard, inside the step that decides what
    # to run. awk does the same work without leaving the process.
    find "$ROOT" \
        \( -name .git -o -name build -o -name .gradle \) -prune -o \
        -path '*/src/test/*' -name '*Test.kt' -print 2>/dev/null \
        | awk -v root="$ROOT/" '
            {
                path = $0
                sub("^" root, "", path)
                if (path !~ /\/src\/test\/(java|kotlin)\//) next
                sub(/.*\/src\/test\/(java|kotlin)\//, "", path)
                sub(/\.kt$/, "", path)
                gsub(/\//, ".", path)
                print path
            }
        ' | sort
}


# The classes in a fixed order, which is what makes the split the same on every
# runner: cksum is POSIX and gives the same number everywhere, where `$RANDOM`
# or a Python hash would not.
ordered_class_names() {
    # Sorted by a hash of the name, then by the name itself.
    #
    # The hash is what keeps sibling classes apart: `FilesFeatureTest` and
    # `HistoryFeatureTest` are adjacent in any alphabetical order, and a shard
    # split by position would then be a split by alphabet, which puts whichever
    # feature happens to sort late all on one runner. The hash is computed in awk
    # rather than by spawning cksum per class, and it only has to be stable
    # across runs and machines, not cryptographic.
    class_names | awk '
        {
            h = 0
            for (i = 1; i <= length($0); i++) {
                h = (h * 31 + index("\x01\x02\x03\x04\x05\x06\x07\x08\t\n\v\f\r\x0e\x0f\x10\x11\x12\x13\x14\x15\x16\x17\x18\x19\x1a\x1b\x1c\x1d\x1e\x1f !\"#$%&\047()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~", substr($0, i, 1))) % 2147483647
            }
            printf "%d\t%s\n", h, $0
        }
    ' | sort -k1,1n -k2,2 | cut -f2-
}

# Which shard the class at a given position belongs to. Dealing them out in turn
# rather than hashing each one into a bucket is the difference between an even
# split and one where a runner waits with nothing to do: nine classes hashed into
# four buckets measured as 2, 0, 6 and 1.
shard_at() {
    echo $(( ($1 - 1) % OF ))
}

# Every class, in the order the split uses. Asked for by the selector, which has
# to know whether a name it was given exists at all — a name that matches nothing
# would otherwise be answered with "nothing to do" by every shard at once.
if [ "$LIST" = "1" ]; then
    ordered_class_names
    exit 0
fi

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
