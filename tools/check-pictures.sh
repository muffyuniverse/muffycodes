#!/usr/bin/env bash
#
# Looks at the pictures the tour made, and says how many there are.
#
# The run is a failure when there are no pictures at all: a tour that made
# nothing means that the app could not be reached, and that should not pass
# quietly.
#
set -uo pipefail

DIR="${1:-screenshots}"

count="$(find "$DIR" -name '*.png' -type f 2>/dev/null | wc -l | tr -d ' ')"
echo "The tour made ${count} pictures in ${DIR}."

if [ "${count:-0}" -lt 1 ]; then
    echo "::error::The tour made no pictures at all."
    exit 1
fi

find "$DIR" -name '*.png' -type f 2>/dev/null | sort | while IFS= read -r f; do
    echo "- $(basename "$f")"
done

exit 0
