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
# Where things land — one folder, emptied on every delivery, holding only the
# latest build:
#   Downloads/muffycodes/muffycodes-<name>.apk      the shipped app, no log file
#   Downloads/muffycodes/muffycodes-<name>-dev.apk  the debug build, writes the trace
#   Downloads/muffycodes/<name>/                    the bundle: changes, pictures
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
        # **The label is the version the app will report, not the run id.**
        #
        # The operator, looking at a phone and at this folder: *"when I update the app its version
        # doesnt change"*. Two different names were in play — the run id named the *file*, the
        # version named the *app* — so a file called `muffycodes-dev-41669824765.apk` contained an app
        # that called itself `190`, and nothing on either side said they were the same build.
        #
        # The bundle carries `version` because the release job writes it, so the file is named for
        # what the app says it is. A file name and a version that agree is the whole point of naming
        # it at all.
        LABEL="$(cat "$TMP/version" 2>/dev/null || echo "$RUN_ID")"
        LABEL="${LABEL#v}"
        ;;
esac

# **One APK or the other, never both.** *"debug for dev, release for main"* — so a development
# bundle carries `app-debug.apk` and nothing else, and the check is for an APK rather than for the
# release one specifically. It used to demand `app-release.apk`, which would have made every
# development delivery fail the moment the release stopped being built there.
if [ ! -f "$TMP/app-release.apk" ] && [ ! -f "$TMP/app-debug.apk" ]; then
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
#
# ## One folder, and only the latest
#
# The operator, after finding ninety-two APKs and 3.6 GB in Downloads: *"you ship both app release
# and muffycodes dev to downloads. and old releases arent deleted - but should, downloads should
# keep only the latest version. and all in one muffycodes folder"*.
#
# The old shape put an APK at the Downloads root **and** a copy inside the bundle folder, and never
# removed the previous run's files — so every build left two new APKs behind for ever. Worse, the
# root copy was the *release* build and the bundle copy was the same file, while the debug build sat
# beside them under a `-dev` suffix that is easy to miss on a phone. That is how a report came from a
# release build while the log was expected from a debug one.
#
# So: **one folder, emptied each time, holding this run's two APKs and its bundle.** Nothing at the
# Downloads root. The two files are named for what they are rather than for the run id, because the
# question at the phone is "which one do I tap", not "which run was this".
DEST="$DOWNLOADS/muffycodes"
rm -rf "$DEST"
mkdir -p "$DEST"
# `-R` because the bundle holds folders: the scenarios keep their shape so that the report's links
# resolve. Without it cp drops them, says so quietly enough that a green run looks fine, and the
# phone gets no report.
cp -R "$TMP"/* "$DEST/"
# **The staging names are not delivered.** `app-release.apk` and `app-debug.apk` are what the bundle
# arrives as; the folder a person opens holds the two files named below, the change list and the
# pictures. `version` stays, because it is what the names above are built from and it is one line.
rm -f "$DEST/app-release.apk" "$DEST/app-debug.apk"

# **The names say which is which**, and the dev one says what it is for. A person tapping a file
# called `muffycodes-190.apk` has no way to know it keeps no log.
if [ -f "$TMP/app-release.apk" ]; then
    mv "$TMP/app-release.apk" "$DEST/muffycodes-$LABEL.apk"
    echo "  + muffycodes-$LABEL.apk (the shipped app, no log file)"
fi
if [ -f "$TMP/app-debug.apk" ]; then
  # A debug build signed with the release key, so it installs over the release APK on the same phone
  # without uninstalling — which is the whole point: live tracing without losing what is in the app.
  # **`-dev` says what the build is, not that it is a spare.** On a development branch it is the
  # only APK, so the suffix is the label rather than a second choice beside a release — and it is the
  # one fact a person needs at the phone, because this is the build that writes a log.
  mv "$TMP/app-debug.apk" "$DEST/muffycodes-$LABEL-dev.apk"
  echo "  + muffycodes-$LABEL-dev.apk (the debug build: writes the trace to Downloads)"
else
  echo "  ! no dev build in this run, so there is no build that writes a trace"
fi

echo "delivered $FOLDER to $DEST"
ls "$DEST" | sed 's/^/  /' 
