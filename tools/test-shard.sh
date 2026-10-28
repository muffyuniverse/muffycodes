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
WEIGHTS="${WEIGHTS:-$ROOT/tools/test-weights.txt}"

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


# The classes, **packed into shards by what they cost** rather than dealt out by name.
#
# This replaced a hash-and-round-robin: sort the class names by a hash of each, then hand them to
# shard 0, 1, 0, 1. That is even by *count* and was never even by *time*. Measured on this
# repository, two shards of 23 classes apiece ran 40.7s and 51.1s - a quarter of a shard's work
# sitting on one runner while the other waited for it. A Robolectric screen test and a pure parser
# test are one class each and do not cost the same, so counting them is counting the wrong thing.
#
# So the weights are measured (tools/test-weights.py, from a real run's JUnit XML) and committed,
# and the classes are packed **longest-first into whichever shard is currently cheapest**. That is
# the classic greedy for this problem: not optimal, within a small factor of it, and
# **deterministic** - the same weights always produce the same split, which is what the
# configuration cache needs.
#
# A class with no measured weight is given the average. A new test therefore joins the split
# straight away rather than being left out or skewing it, and its true cost is learned the next
# time the weights are refreshed.
#
# Prints `shard<TAB>class` for every class, ordered by shard then by cost, which is the order the
# caller deals out. The packing is the whole answer; the position is only how it is carried.
packed_classes() {
    awk -v of="$OF" -v weights="$WEIGHTS" '
        BEGIN {
            sum = 0; n = 0
            while ((getline line < weights) > 0) {
                if (line ~ /^#/ || line ~ /^[ \t]*$/) continue
                split(line, parts, /[ \t]+/)
                w[parts[1]] = parts[2] + 0
                sum += parts[2] + 0
                n++
            }
            close(weights)
            average = (n > 0 ? sum / n : 1.0)
        }
        {
            weight = ($0 in w ? w[$0] : average)
            # **Longest first**, because a greedy only behaves when the big items are placed before
            # the small ones can fragment the room. The name is the tie-break, so two classes of
            # equal cost always land the same way round.
            printf "%012.4f\t%s\n", 1000000 - weight, $0
        }
    ' < <(class_names) | sort -k1,1n -k2,2 | cut -f2- | awk -v of="$OF" -v weights="$WEIGHTS" '
        BEGIN {
            sum = 0; n = 0
            while ((getline line < weights) > 0) {
                if (line ~ /^#/ || line ~ /^[ \t]*$/) continue
                split(line, parts, /[ \t]+/)
                w[parts[1]] = parts[2] + 0
                sum += parts[2] + 0
                n++
            }
            close(weights)
            average = (n > 0 ? sum / n : 1.0)
            for (i = 0; i < of; i++) load[i] = 0
        }
        {
            name = $0
            weight = (name in w ? w[name] : average)
            # The cheapest shard that can take it. Ties go to the lowest index, so the result does
            # not depend on the order awk happens to compare equal numbers in.
            best = 0
            for (i = 1; i < of; i++) if (load[i] < load[best] - 1e-9) best = i
            load[best] += weight
            printf "%d\t%s\n", best, name
        }
    '
}

# Which shard a class belongs to: **the packer's answer**, carried as the first column.
#
# This used to be arithmetic on the class's position - `(position - 1) % OF` - which is what made
# the split even by count and uneven by time. The packing decides, and the position is only how it
# is carried through the rest of this script.
shard_of_line() {
    echo "${1%%	*}"
}
name_of_line() {
    echo "${1#*	}"
}

# Every class, in the order the split uses. Asked for by the selector, which has
# to know whether a name it was given exists at all — a name that matches nothing
# would otherwise be answered with "nothing to do" by every shard at once.
if [ "$LIST" = "1" ]; then
    # The names alone, in the packer's order, which is what the selector checks names against.
    packed_classes | cut -f2-
    exit 0
fi

if [ "$VERIFY" = "1" ]; then
    all="$(packed_classes)"
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
        while IFS= read -r line; do
            [ -n "$line" ] || continue
            if [ "$(shard_of_line "$line")" = "$i" ]; then
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

    # **The balance, measured rather than assumed.** A split that is even by count and uneven by
    # time is the exact failure this script was rewritten to stop, and the only way to know which
    # one it is is to add up the weights each shard got. The figure that matters is the worst
    # shard's share of the average: 1.00 is perfect, and anything past the budget below means one
    # runner is waiting for another.
    awk -v of="$OF" -v weights="$WEIGHTS" '
        BEGIN {
            sum = 0; n = 0
            while ((getline line < weights) > 0) {
                if (line ~ /^#/ || line ~ /^[ \t]*$/) continue
                split(line, parts, /[ \t]+/)
                w[parts[1]] = parts[2] + 0
                sum += parts[2] + 0
                n++
            }
            close(weights)
            average = (n > 0 ? sum / n : 1.0)
            for (i = 0; i < of; i++) load[i] = 0
        }
        {
            shard = $1 + 0
            name = $2
            load[shard] += (name in w ? w[name] : average)
        }
        END {
            worst = 0; all = 0
            for (i = 0; i < of; i++) {
                if (load[i] > worst) worst = load[i]
                all += load[i]
            }
            mean = all / of
            printf "  balance: worst shard %.1fs against %.1fs average (%.2fx)\n", worst, mean, (mean > 0 ? worst / mean : 1)
            if (mean > 0 && worst / mean > 1.15)
                printf "  **unbalanced**: one runner does %.0f%% more than its share - refresh the weights or repack\n", (worst / mean - 1) * 100
        }
    ' <<< "$all"
    exit 0
fi

if [ "$OF" = "1" ]; then
    # One shard is the whole suite, and the whole suite needs no filter. Printing
    # nothing keeps the command line identical to a run with no sharding at all,
    # which is what lets today's single-runner setup reuse its cache.
    exit 0
fi

packed_classes | while IFS= read -r line; do
    [ -n "$line" ] || continue
    if [ "$(shard_of_line "$line")" = "$SHARD" ]; then
        printf -- '--tests\n%s\n' "$(name_of_line "$line")"
    fi
done
