#!/usr/bin/env bash
#
# Works out which tests a change has to run.
#
# The idea is atomicity: a pull request that touched the folder-tree rules has
# no business running the git tests, and a suite that runs everything on every
# change stops being quick enough to be run on every change.
#
# The rule that makes this safe is the fallback. A changed file that is named on
# no line of tools/test-map.txt runs the whole suite. Unknown means everything,
# never nothing, so a file added tomorrow cannot slip through a gap in the
# table. That is the opposite of the usual test-selection mistake, which is to
# treat "I could not match this" as "this affects nothing".
#
# Usage:
#   changed-tests.sh --base <ref>    the tests to run for the changes since <ref>
#   changed-tests.sh --all           every test, which is what main always does
#
# Prints one of:
#   ALL                      run the whole suite
#   <Class> <Class> ...      run only these, in that order
#
# Exit code: 0 when it worked out an answer, 2 on a bad use.

set -uo pipefail

MAP_FILE="$(dirname "$0")/test-map.txt"

usage() {
    sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'
}

BASE=""
WANT_ALL=0

while [ $# -gt 0 ]; do
    case "$1" in
        --base) BASE="${2:-}"; shift 2 ;;
        --all) WANT_ALL=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

if [ "$WANT_ALL" = "1" ]; then
    echo "ALL"
    exit 0
fi

if [ -z "$BASE" ]; then
    echo "--base is needed, or --all" >&2
    exit 2
fi

if [ ! -f "$MAP_FILE" ]; then
    # No table means no way to tell anything apart, so everything runs.
    echo "ALL"
    exit 0
fi

CHANGED="$(git diff --name-only "$BASE"..."HEAD" 2>/dev/null || git diff --name-only "$BASE" HEAD 2>/dev/null)"
if [ -z "$CHANGED" ]; then
    # Nothing changed is not a reason to skip the tests: a branch can be empty
    # of source changes and still be the first run of a new workflow.
    echo "ALL"
    exit 0
fi

# The build itself changing means every test is affected, because the thing
# under test is how the app is put together.
if printf '%s\n' "$CHANGED" | grep -qE '(^|/)(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradle/|gradle\.wrapper)'; then
    echo "ALL"
    exit 0
fi

SELECTED=""
UNKNOWN=0

while IFS= read -r file; do
    [ -n "$file" ] || continue
    # A change outside the tested sources affects nothing here: notes, scripts
    # and workflows are not part of the app.
    case "$file" in
        app/src/main/*|app/src/test/*) ;;
        *) continue ;;
    esac

    # A test that changed runs, obviously, and does not drag in the rest.
    case "$file" in
        app/src/test/*/*Test.kt|app/src/test/*/*/*Test.kt|app/src/test/java/*/*/*/*Test.kt)
            # A test that was deleted has nothing left to run, and asking
            # Gradle for a class that no longer exists fails the whole run.
            [ -f "$file" ] || continue
            name="$(basename "$file" .kt)"
            case " $SELECTED " in
                *" $name "*) ;;
                *) SELECTED="$SELECTED $name" ;;
            esac
            continue
            ;;
    esac

    matched=0
    while read -r test_class sources; do
        case "$test_class" in ''|'#'*) continue ;; esac
        for source in $sources; do
            if [ "$source" = "$file" ]; then
                matched=1
                case " $SELECTED " in
                    *" $test_class "*) ;;
                    *) SELECTED="$SELECTED $test_class" ;;
                esac
                break
            fi
        done
    done < "$MAP_FILE"

    if [ "$matched" = "0" ]; then
        UNKNOWN=1
        break
    fi
done <<< "$CHANGED"

if [ "$UNKNOWN" = "1" ] || [ -z "$SELECTED" ]; then
    echo "ALL"
    exit 0
fi

echo "$SELECTED" | tr -s ' ' | sed 's/^ //'
