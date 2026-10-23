#!/bin/sh
#
# Writes down what changed since the last release, as markdown for people.
#
# The bundle every build carries — the APK, this list and the tour pictures —
# exists to answer "what am I looking at, and does it do what was promised".
# This script writes the first part: the changes, as the commits themselves
# said them, newest first.
#
# Usage:
#   changes.sh [--pictures <dir>]
#
#   --pictures <dir>   also write a section for the tour pictures in <dir>,
#                      shown as pictures. The links are the file names, so this
#                      document works beside the files it talks about; a
#                      release page is different and its publisher rewrites the
#                      links to the release's own addresses (see the workflow).
#
# Needs `gh` (to ask GitHub which release came before this one) and git
# history. The history is fetched here, because the machines that build are
# given one commit and no past, and the past is exactly what this prints.
#
# Prints markdown on standard output. Never fails the build over politeness:
# if the history cannot be had, it says so in the document instead.

set -eu

PICTURES=""

while [ $# -gt 0 ]; do
    case "$1" in
        --pictures) PICTURES="${2:-}"; shift 2 ;;
        -h|--help) sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

REPOSITORY="${REPOSITORY:-muffyuniverse/muffycodes}"

# Which release came before this build. Listed rather than fetched as tags,
# because a release is a GitHub fact and a tag is only its shadow.
PREV="$(gh release list --repo "$REPOSITORY" --limit 100 --json tagName,createdAt \
    --jq 'sort_by(.createdAt) | last | .tagName' 2>/dev/null || true)"

# The history has to exist before it can be printed. A machine given one
# commit is given everything else here, which is cheap: this is a small
# repository and this happens once a build.
git fetch --quiet --unshallow --tags origin 2>/dev/null \
    || git fetch --quiet --tags origin 2>/dev/null \
    || true

echo "## What changed"
echo ""

if [ -n "$PREV" ] && git rev-parse -q --verify "$PREV^{commit}" >/dev/null 2>&1; then
    echo "Since ${PREV}:"
    echo ""
    git log --pretty=format:'- %s (%h)' "${PREV}..HEAD" 2>/dev/null || echo "- (the history could not be read)"
else
    echo "The first release, or one whose predecessor is not in the history."
    echo ""
    git log -n 20 --pretty=format:'- %s (%h)' 2>/dev/null || echo "- (the history could not be read)"
fi

echo ""
echo ""

if [ -n "$PICTURES" ] && [ -d "$PICTURES" ]; then
    echo "## The pictures"
    echo ""
    echo "Drawn by the test run on the plain JVM, one per screen. They show the"
    echo "features work: the screen is there and it renders. They are not the"
    echo "truth of how the app looks on a phone — that truth is the renders of a"
    echo "real install, which arrive on this release afterwards and are for the"
    echo "operator's eyes."
    echo ""

    found=0
    for f in "$PICTURES"/*.png; do
        [ -e "$f" ] || continue
        found=1
        name="$(basename "$f")"
        # The link is the file name, so the picture shows wherever this
        # document is read next to the files. A release page is not next to
        # them, and the publisher of the release aims these links at it.
        echo "![${name}](${name})"
        echo ""
    done

    if [ "$found" -eq 0 ]; then
        echo "(the run drew no pictures)"
        echo ""
    fi
fi
