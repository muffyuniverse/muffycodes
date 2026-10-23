#!/bin/sh
#
# bundle — bring the build to the phone.
#
# A build only exists as a thing on GitHub until this runs. It takes the
# bundle — the APK, the list of what changed and the pictures — and puts it in
# the phone's Downloads folder, where the handset can see it. That folder is
# the only road out of this container for a person to hold something.
#
# Usage:
#   bundle             the latest release, with everything on it right now
#   bundle v44         the release named v44
#   bundle --dev       the newest development build (the "install and look at" one)
#   bundle --run 123   the bundle one run made (ci uses this after a build)
#
# Where things land:
#   Downloads/notes-app/<name>/            the whole bundle, as it was made
#   Downloads/Notes-<name>.apk             the APK, at the convenience paths
#   Downloads/notes-app/Notes-<name>.apk   the phone has always been given
#
# The renders of a real install are put on a release a few minutes after it is
# published, in their own run. Whenever they are there, they are picked up too
# — so running this again after them grows the bundle folder with the renders.
#
# Exit codes: 0 delivered, 1 nothing to deliver, 2 bad use.

set -eu

DOWNLOADS="${DOWNLOADS:-/root/Downloads}"
REPO="${REPO:-muffyuniverse/muffycodes}"

MODE="release"
TAG=""
RUN_ID=""

while [ $# -gt 0 ]; do
    case "$1" in
        --dev) MODE="dev"; shift ;;
        --run) MODE="run"; RUN_ID="${2:-}"; shift 2 ;;
        -h|--help) sed -n '2,27p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) echo "unknown option: $1" >&2; exit 2 ;;
        *) TAG="$1"; shift ;;
    esac
done

# The bundle is gathered into a temporary folder first, so that a fetch which
# goes wrong leaves the last good delivery alone.
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

case "$MODE" in
    release)
        if [ -z "$TAG" ]; then
            TAG="$(gh release list --repo "$REPO" --limit 1 --json tagName \
                --jq '.[0].tagName' 2>/dev/null || true)"
        fi
        if [ -z "$TAG" ]; then
            echo "no release was found to deliver" >&2
            exit 1
        fi

        gh release download "$TAG" --repo "$REPO" --dir "$TMP" --clobber

        # The list of changes is the body of the release rather than a file on
        # it, so it is written here to travel with the rest.
        gh release view "$TAG" --repo "$REPO" --json body \
            --jq '.body' > "$TMP/changes.md" 2>/dev/null || true

        FOLDER="$TAG"
        LABEL="${TAG#v}"
        ;;

    dev|run)
        if [ "$MODE" = "dev" ]; then
            RUN_ID="$(gh run list --repo "$REPO" --branch development \
                --workflow build-and-release.yml --status success --limit 1 \
                --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)"
            if [ -z "$RUN_ID" ]; then
                echo "no development run was found to deliver" >&2
                exit 1
            fi
        fi

        if ! gh run download "$RUN_ID" --repo "$REPO" --name build-bundle \
                --dir "$TMP" 2>/dev/null; then
            echo "this run made no bundle (nothing in the app changed)" >&2
            exit 1
        fi

        FOLDER="dev-$RUN_ID"
        LABEL="dev-$RUN_ID"
        ;;
esac

if [ ! -f "$TMP/app-release.apk" ]; then
    echo "the bundle has no APK in it" >&2
    exit 1
fi

# Everything is in place before anything is delivered, so the phone never
# opens a folder that is still being filled.
DEST="$DOWNLOADS/notes-app/$FOLDER"
rm -rf "$DEST"
mkdir -p "$DEST" "$DOWNLOADS/notes-app"
cp "$TMP"/* "$DEST/"
cp "$TMP/app-release.apk" "$DOWNLOADS/Notes-$LABEL.apk"
cp "$TMP/app-release.apk" "$DOWNLOADS/notes-app/Notes-$LABEL.apk"

echo "delivered $FOLDER to $DEST"
ls "$DEST" | sed 's/^/  /'
echo "  + $DOWNLOADS/Notes-$LABEL.apk"
