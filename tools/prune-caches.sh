#!/usr/bin/env bash
#
# Tidy the repository's action caches, without deleting the ones that matter.
#
# The cache ceiling is ten gigabytes for the whole repository, and the workflow
# deliberately never prunes (`cache-cleanup: never`, which saved twenty-eight
# seconds a run). That trade only works while the entries stay small, and nothing
# makes them: measured 2026-10-23 there were 235 entries holding 5.33 GB — half
# the ceiling — accumulated over a couple of days of changing the workflows.
#
# The first version of this script was unsafe in three ways, all of them found by
# re-reading it against what the keys actually are:
#
#   1. It asked for page 1 sorted oldest-first and page 2 sorted *newest*-first,
#      then treated the concatenation as "oldest first". Anything after the keep
#      count was therefore deleted by a rule the file did not describe.
#   2. It ordered by creation time, not by use. The Robolectric runtime entry and
#      the toolchain entries are created once and read on every run — the oldest
#      entries in the list are the busiest ones. Deleting by age would have thrown
#      away exactly the caches worth keeping, and the cost would have shown up as
#      a slow run days later with nothing connecting it to this.
#   3. It was described as pruning "a namespace" but was not restricted to one.
#
# So this version is built the other way round: the load-bearing keys are named,
# their newest entry is never touched, and everything else is deleted newest-last
# up to a keep count. It is a dry run unless told otherwise, and it refuses to do
# anything at all while there is room.
#
#   bash tools/prune-caches.sh              show what would go, change nothing
#   bash tools/prune-caches.sh --apply      do it
#   bash tools/prune-caches.sh --apply --keep 40
#
set -uo pipefail

REPO="${REPO:-muffyuniverse/muffycodes}"
KEEP=30
APPLY=0
# Delete nothing below this. Half the ceiling is the point at which growth is
# worth acting on; before that the entries are doing more good than the bytes cost.
FLOOR_BYTES=$((4 * 1024 * 1024 * 1024))

# Keys whose newest entry is never deleted, whatever its age. Each name here is a
# thing that is expensive or impossible to rebuild quickly, and each is created
# once and then read on every run:
#
#   robolectric  the Android runtime the screen tests need — a download of tens of
#                megabytes on every cold run, and the entry never changes
#   wrapper      the Gradle distribution itself
#   dependencies the resolved dependency graph
#   kotlin-dsl   the compiled build scripts
#   instrumented the jars Gradle instruments for the coverage agent
#
# `gradle-home` and `gradle-build-cache` are deliberately NOT protected: they are
# per-run entries that are cheap to rebuild from the ones above, and they are what
# fills the ceiling.
PROTECTED="robolectric-runtime gradle-wrapper-zips gradle-dependencies gradle-kotlin-dsl gradle-instrumented-jars config-cache-"

while [ $# -gt 0 ]; do
    case "$1" in
        --apply) APPLY=1; shift ;;
        --keep) KEEP="${2:-}"; shift 2 ;;
        -h|--help) sed -n '2,45p' "$0"; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

case "$KEEP" in
    ''|*[!0-9]*) echo "--keep needs a whole number" >&2; exit 2 ;;
esac

usage="$(gh api "repos/$REPO/actions/cache/usage" \
    --jq '"\(.active_caches_size_in_bytes) \(.active_caches_count)"' 2>/dev/null)"
if [ -z "$usage" ]; then
    echo "could not read the cache usage for $REPO" >&2
    exit 1
fi
size="${usage%% *}"
count="${usage##* }"
printf 'now: %.2f GB in %s entries (ceiling 10 GB, floor %.0f GB)\n' \
    "$(awk -v s="$size" 'BEGIN {print s/1073741824}')" "$count" \
    "$(awk -v f="$FLOOR_BYTES" 'BEGIN {print f/1073741824}')"

if [ "$size" -lt "$FLOOR_BYTES" ]; then
    echo "below the floor, so nothing is deleted. This script is for when the"
    echo "repository is near its ceiling, not for routine tidying."
    exit 0
fi

# One consistent order, from one paginated call. `created_at desc` means the file
# is newest-first, which is what the keep rule below assumes.
gh api "repos/$REPO/actions/caches?per_page=100&sort=created_at&direction=desc" \
    --paginate \
    --jq '.actions_caches[] | "\(.created_at)\t\(.id)\t\(.size_in_bytes)\t\(.key)"' \
    > /tmp/caches-newest-first.txt 2>/dev/null

total="$(wc -l < /tmp/caches-newest-first.txt)"
if [ "$total" -eq 0 ]; then
    echo "no caches were listed; nothing to do" >&2
    exit 1
fi
echo "listed $total entries"

# Two passes over the newest-first list: the protected families are all kept (each
# one, not just the newest — there are only two or three of each and they are the
# entries that make a cold run bearable), and the rest is trimmed to KEEP.
kept_protected=0
kept_plain=0
victims=""
victim_bytes=0

while IFS=$'\t' read -r created id bytes key; do
    [ -n "${id:-}" ] || continue
    protected=0
    for prefix in $PROTECTED; do
        case "$key" in
            "$prefix"*) protected=1; break ;;
        esac
    done
    if [ "$protected" = "1" ]; then
        kept_protected=$((kept_protected + 1))
        continue
    fi
    if [ "$kept_plain" -lt "$KEEP" ]; then
        kept_plain=$((kept_plain + 1))
        continue
    fi
    victims="$victims$id $key"$'\n'
    victim_bytes=$((victim_bytes + bytes))
done < /tmp/caches-newest-first.txt

echo "keeping $kept_protected load-bearing entries and $kept_plain recent ones"
printf 'deleting %s entries, freeing %.2f GB\n' \
    "$(printf '%s' "$victims" | grep -c .)" \
    "$(awk -v b="$victim_bytes" 'BEGIN {print b/1073741824}')"

if [ -z "$victims" ]; then
    echo "nothing is old enough to delete yet"
    exit 0
fi

printf '%s' "$victims" | while read -r id key; do
    [ -n "${id:-}" ] || continue
    printf '  %s\n' "$key"
done | head -20
extra="$(printf '%s' "$victims" | grep -c .)"
if [ "$extra" -gt 20 ]; then
    echo "  … and $((extra - 20)) more"
fi

if [ "$APPLY" != "1" ]; then
    echo
    echo "This was a dry run. Add --apply to delete them."
    exit 0
fi

# Written to a file rather than piped, so the counter survives. A `while` on the
# right of a pipe runs in a subshell and every increment is thrown away with it —
# which would have reported zero deleted after deleting everything.
printf '%s' "$victims" > /tmp/cache-victims.txt
deleted=0
while read -r id key; do
    [ -n "${id:-}" ] || continue
    if gh api -X DELETE "repos/$REPO/actions/caches/$id" >/dev/null 2>&1; then
        deleted=$((deleted + 1))
    else
        echo "  could not delete $id ($key)" >&2
    fi
done < /tmp/cache-victims.txt
echo "deleted $deleted entries"

after="$(gh api "repos/$REPO/actions/cache/usage" \
    --jq '"\(.active_caches_size_in_bytes) \(.active_caches_count)"' 2>/dev/null)"
printf 'now: %.2f GB in %s entries\n' \
    "$(awk -v s="${after%% *}" 'BEGIN {print s/1073741824}')" "${after##* }"
