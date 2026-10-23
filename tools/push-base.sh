#!/usr/bin/env bash
#
# Print the commit a push started from.
#
# The question "did this change the app" has to be asked of the whole push, not
# of its last commit. It used to be asked with `--base HEAD~1`, which is the
# difference between the tip and the commit before it — so a push carrying an app
# commit and then a documentation commit looked documentation-only, and lint and
# the APK were skipped while the run went green. The base of a push is the commit
# the branch pointed at before it, which GitHub hands over as `event.before`.
#
# The commit is usually not in the checkout: fetch-depth is 2, enough to answer
# the old question and not the new one. So it is fetched, shallowly, by its own
# hash — a commit is fetchable by hash whether or not it is on a branch.
#
# When it cannot be fetched the answer is `HEAD~1`, which is what this whole thing
# used to be. That is the conservative stop: the selector treats an unreadable
# base as "everything changed", so a push whose base is older than the checkout
# depth costs a rebuild rather than skipping one.
#
#   push-base.sh <before-sha>
#
set -uo pipefail

before="${1:-}"

if [ -n "$before" ] && git cat-file -e "${before}^{commit}" 2>/dev/null; then
    printf '%s\n' "$before"
    exit 0
fi

if [ -n "$before" ]; then
    git fetch --quiet --no-tags --depth=1 origin "$before" 2>/dev/null || true
    if git cat-file -e "${before}^{commit}" 2>/dev/null; then
        printf '%s\n' "$before"
        exit 0
    fi
fi

echo "Could not read the commit this push started from; treating it as one commit." >&2
printf 'HEAD~1\n'
