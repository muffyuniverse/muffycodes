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
#   Downloads/muffycodes/<name>/            the whole bundle, as it was made
#   Downloads/muffycodes-<name>.apk         the APK, at the convenience path
#   Downloads/muffycodes/muffycodes-<name>.apk  the phone is given
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

# Puts the flattened scenario pictures back into folders, and takes the report
# from the release's own body.
restore_scenarios() {
    local dir="$1"
    local scenarios="$dir/scenarios"
    local found=0
    local picture name half scenario

    for picture in "$dir"/scenario-*-before.png "$dir"/scenario-*-after.png; do
        [ -e "$picture" ] || continue
        name="$(basename "$picture" .png)"
        half="${name##*-}"
        scenario="${name#scenario-}"
        scenario="${scenario%-$half}"
        mkdir -p "$scenarios/$scenario"
        mv "$picture" "$scenarios/$scenario/$half.png"
        found=$((found + 1))
    done

    # Each scenario's own page, which the report links to.
    for page in "$dir"/scenario-*-page.md; do
        [ -e "$page" ] || continue
        scenario="$(basename "$page" .md)"
        scenario="${scenario#scenario-}"
        scenario="${scenario%-page}"
        mkdir -p "$scenarios/$scenario"
        mv "$page" "$scenarios/$scenario/README.md"
        found=$((found + 1))
    done

    [ "$found" -eq 0 ] && return 0

    # The report travels as an asset of its own, with links relative to the
    # scenario folders — which is exactly the shape this function just restored.
    # That is why it is preferred over the release's body: the body cannot link to
    # folders a release does not have, so there its index is plain text.
    if [ -f "$dir/scenarios.md" ]; then
        cp "$dir/scenarios.md" "$scenarios/README.md"
        rm -f "$dir/scenarios.md"
    elif [ -f "$dir/changes.md" ] && grep -q '^# What the app does' "$dir/changes.md"; then
        sed -n '/^# What the app does$/,$p' "$dir/changes.md" > "$scenarios/README.md"
        sed -E -i "s#https://github[^ )]*/scenario-([^ )]+)-(before|after)\.png#\1/\2.png#g" \
            "$scenarios/README.md"
    else
        bash "$(dirname "$0")/scenario-report.sh" "$scenarios" \
            --out "$scenarios/README.md" 2>/dev/null || true
    fi

    # The body also carries the report, and on the phone the report has its own
    # folder. Leaving both would show the scenarios twice, once with links that
    # only work on GitHub.
    if [ -f "$dir/changes.md" ] && grep -q '^# What the app does' "$dir/changes.md"; then
        sed -n '1,/^# What the app does$/p' "$dir/changes.md" \
            | sed '$d' > "$dir/changes.trimmed" && mv "$dir/changes.trimmed" "$dir/changes.md"
    fi
}

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

        # A release cannot hold folders, so the scenarios arrive flattened as
        # `scenario-<name>-before.png`. They are put back into the shape a reader
        # needs: a folder per scenario holding its two pictures and the page that
        # explains them, with the report on top. Without this the phone would get a
        # bag of pictures and no words, which is the thing this exists to stop.
        restore_scenarios "$TMP"

        FOLDER="$TAG"
        LABEL="${TAG#v}"
        ;;

    dev|run)
        if [ "$MODE" = "dev" ]; then
            # "The newest successful run" and "the newest run that has a bundle"
            # are different questions, and they part company after any push that
            # touched no code: that run is green, delivers nothing, and used to be
            # the one chosen here — so `bundle --dev` said "this run made no
            # bundle" while an installable build sat one run further back. The
            # command exists to put a build on the phone, so it looks for the
            # newest run that has one and only reports nothing when there is
            # genuinely nothing.
            RUN_ID=""
            for candidate in $(gh run list --repo "$REPO" --branch development \
                    --workflow build-and-release.yml --status success --limit 10 \
                    --json databaseId --jq '.[].databaseId' 2>/dev/null || true); do
                if gh api "repos/$REPO/actions/runs/$candidate/artifacts" \
                        --jq '.artifacts[].name' 2>/dev/null | grep -qx "build-bundle"; then
                    RUN_ID="$candidate"
                    break
                fi
            done
            if [ -z "$RUN_ID" ]; then
                echo "no development run has produced a bundle yet" >&2
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

# The attestation is produced on every published release and was never read.
# It proves that this APK came from this repository's release workflow on the
# commit it names — the one question a downloaded binary cannot answer about
# itself. A replaced release asset would otherwise be delivered to the phone
# exactly as smoothly as the real one.
#
# Only `main` publishes, so a development build has no provenance to check.
# Saying that plainly is better than implying it was either verified or broken.
if [ "$MODE" = "release" ]; then
    if ! command -v gh >/dev/null 2>&1; then
        echo "  (no gh here, so the APK's provenance is unchecked)" >&2
    elif gh attestation verify "$TMP/app-release.apk" --repo "$REPO" >/dev/null 2>&1; then
        echo "  provenance verified: this APK came from $REPO's release workflow"
    else
        echo "the APK's provenance could not be verified against $REPO." >&2
        echo "Refusing to hand the phone a binary whose origin cannot be shown." >&2
        exit 1
    fi
fi

# Everything is in place before anything is delivered, so the phone never
# opens a folder that is still being filled.
DEST="$DOWNLOADS/muffycodes/$FOLDER"
rm -rf "$DEST"
mkdir -p "$DEST" "$DOWNLOADS/muffycodes"
# `-R` because the bundle holds folders now: the scenarios keep their shape so
# that the report's links resolve. Without it cp drops them, says so quietly
# enough that a green run looks fine, and the phone gets no report.
cp -R "$TMP"/* "$DEST/"
cp "$TMP/app-release.apk" "$DOWNLOADS/muffycodes-$LABEL.apk"

# The dev copy, when the run was asked for one. A debug build signed with the release
# key, so it installs over the release APK on the same phone without uninstalling —
# which is the whole point: live tracing without losing what is already in the app.
if [ -f "$TMP/app-debug.apk" ]; then
  cp "$TMP/app-debug.apk" "$DOWNLOADS/muffycodes-$LABEL-dev.apk"
  echo "  + $DOWNLOADS/muffycodes-$LABEL-dev.apk (dev: live trace on)"
fi
cp "$TMP/app-release.apk" "$DOWNLOADS/muffycodes/muffycodes-$LABEL.apk"

echo "delivered $FOLDER to $DEST"
ls "$DEST" | sed 's/^/  /'
echo "  + $DOWNLOADS/muffycodes-$LABEL.apk"
