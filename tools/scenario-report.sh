#!/usr/bin/env bash
#
# Assembles the scenarios a build drew into one report a person can read.
#
# Each scenario is a folder with two pictures and a page. The index is built
# from the folders that are actually there, in the order their names sort, so
# the report describes the scenarios this build really ran — not a list somebody
# wrote down once and then forgot to keep true.
#
# What goes in the report is written for the person reading it: what a person
# would see, what they did, and what came of it. Nothing here mentions tests,
# classes, milliseconds or stack traces, because a reader cannot act on those.
# The technical half of a failure lives in the run's own log, where an agent
# reads it.
#
# Usage:
#   scenario-report.sh <scenario-dir> [--out <file>]
#
# Prints the report to stdout unless --out is given.

set -uo pipefail

DIR=""
OUT=""

while [ $# -gt 0 ]; do
    case "$1" in
        --out) OUT="${2:-}"; shift 2 ;;
        -h|--help) sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) DIR="$1"; shift ;;
    esac
done

if [ -z "$DIR" ] || [ ! -d "$DIR" ]; then
    echo "no scenarios were drawn" >&2
    exit 1
fi

scenarios=()
while IFS= read -r page; do
    scenarios+=("$page")
done < <(find "$DIR" -mindepth 2 -maxdepth 2 -name 'README.md' | sort)

count="${#scenarios[@]}"
if [ "$count" -eq 0 ]; then
    echo "no scenarios were drawn" >&2
    exit 1
fi

body="$(
    printf '# What the app does\n\n'
    printf 'Each scenario below is one thing a person does with the app, shown as the '
    printf 'screen before, what happened, the screen after, and what the result was.\n\n'
    printf 'This build ran %s scenario%s.\n\n' "$count" "$([ "$count" -eq 1 ] && echo "" || echo "s")"

    for page in "${scenarios[@]}"; do
        folder="$(basename "$(dirname "$page")")"
        # The title is the first heading and the summary the quoted line under it.
        title="$(grep -m1 '^# ' "$page" | sed 's/^# //')"
        about="$(grep -m1 '^> ' "$page" | sed 's/^> //')"
        printf -- '- **[%s](%s/README.md)**' "$title" "$folder"
        if [ -n "$about" ]; then
            printf -- ' — %s' "$about"
        fi
        printf '\n'
    done
    printf '\n---\n\n'

    # Then the scenarios themselves, so the report reads as one document and the
    # pictures show up inline rather than as a list of files to open.
    for page in "${scenarios[@]}"; do
        # Rewrite the picture links so they resolve from the report's own place.
        folder="$(basename "$(dirname "$page")")"
        sed -E "s#\]\((before|after)\.png\)#]($folder/\1.png)#g" "$page"
        printf '\n---\n\n'
    done
)"

if [ -n "$OUT" ]; then
    mkdir -p "$(dirname "$OUT")"
    printf '%s\n' "$body" > "$OUT"
    echo "wrote $OUT ($count scenarios)" >&2
else
    printf '%s\n' "$body"
fi
