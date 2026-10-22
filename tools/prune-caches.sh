#!/usr/bin/env bash
# Deletes the repository's oldest action caches, keeping the newest ones.
#
# The cache ceiling is ten gigabytes for the whole repository, and the workflow
# deliberately never prunes (`cache-cleanup: never`, which saved twenty-eight
# seconds a run). That trade only works while the caches stay small, and nothing
# makes them: measured on 2026-10-22 there were 169 entries holding 5.6 GB, about
# half the ceiling, from a single day of changing the workflows. Growth is
# unbounded and nothing reports it until the ceiling is reached.
#
# This is housekeeping, not a fix for a measured slowdown: a run on that day took
# 161 seconds of Gradle work where the same work had taken 21, but the cause
# turned out to be a changed Gradle command line giving a cold cache rather than
# the entries below being evicted.
#
# Run it by hand when the figure gets close to the ceiling:
#   bash tools/prune-caches.sh 25        keep 25 entries, delete the rest
set -uo pipefail

REPO="${REPO:-muffyuniverse/muffycodes}"
KEEP="${1:-25}"

case "$KEEP" in
    ''|*[!0-9]*) echo "give a whole number of entries to keep" >&2; exit 2 ;;
esac

usage_now="$(gh api "repos/$REPO/actions/cache/usage" --jq '.active_caches_size_in_bytes')"
printf 'before: %.2f GB in %s entries\n' \
    "$(echo "$usage_now" | awk '{print $1/1024/1024/1024}')" \
    "$(gh api "repos/$REPO/actions/cache/usage" --jq '.active_caches_count')"

# Oldest first, so everything after the first KEEP is what goes.
gh api "repos/$REPO/actions/caches?per_page=100&sort=created_at&direction=asc" \
    --jq '.actions_caches[]|"\(.created_at)\t\(.id)\t\(.size_in_bytes)"' \
    > /tmp/caches-oldest.txt

gh api "repos/$REPO/actions/caches?per_page=100&page=2&sort=created_at&direction=desc" \
    --jq '.actions_caches[]|"\(.created_at)\t\(.id)\t\(.size_in_bytes)"' \
    >> /tmp/caches-oldest.txt

python3 - "$KEEP" <<'PY'
import sys
keep = int(sys.argv[1])
rows = []
seen = set()
for line in open('/tmp/caches-oldest.txt'):
    created, cid, size = line.strip().split('\t')
    if cid in seen:
        continue
    seen.add(cid)
    rows.append((created, cid, int(size)))
rows.sort()
drop = rows[:max(0, len(rows) - keep)]
with open('/tmp/caches-drop.txt', 'w') as f:
    for _, cid, size in drop:
        f.write('%s %d\n' % (cid, size))
total = sum(s for _, _, s in drop)
print('entries known: %d, deleting %d (%.2f GB), keeping %d'
      % (len(rows), len(drop), total / 1024 / 1024 / 1024, keep))
PY

while read -r cid _unused_size; do
    gh api -X DELETE "repos/$REPO/actions/caches/$cid" > /dev/null 2>&1 \
        && echo "  deleted $cid" || echo "  could not delete $cid"
done < /tmp/caches-drop.txt

after="$(gh api "repos/$REPO/actions/cache/usage" --jq '.active_caches_size_in_bytes')"
printf 'after: %.2f GB in %s entries\n' \
    "$(echo "$after" | awk '{print $1/1024/1024/1024}')" \
    "$(gh api "repos/$REPO/actions/cache/usage" --jq '.active_caches_count')"
