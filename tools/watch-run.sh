#!/usr/bin/env bash
#
# Waits for the newest run of a workflow and writes down how it went.
#
# The point of this script is that it is meant to be started in the background
# and then forgotten:
#
#   nohup tools/watch-run.sh > /tmp/run.log 2>&1 &
#
# Waiting for a build in the foreground costs attention for no gain: nothing
# useful can be done while a poll loop sleeps. Started in the background, the
# answer is waiting in a file whenever it is wanted, and the work in between is
# free.
#
# It prints one line per check, and a last line that starts with "RESULT:" and
# says success, failure, cancelled or timeout. That last line is what a person,
# or a script, looks for.
#
# Usage:
#   watch-run.sh [--branch NAME] [--workflow FILE] [--sha SHA] [--timeout MINUTES]
#
# The defaults are the ones this project always needs: the newest run of the
# build workflow on the current branch.
#
# Exit code: 0 when the run succeeded, 1 otherwise, 2 on a bad use.

set -uo pipefail

BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo main)"
WORKFLOW="build-and-release.yml"
SHA=""
TIMEOUT_MINUTES=45
INTERVAL=15

while [ $# -gt 0 ]; do
    case "$1" in
        --branch) BRANCH="${2:-}"; shift 2 ;;
        --workflow) WORKFLOW="${2:-}"; shift 2 ;;
        --sha) SHA="${2:-}"; shift 2 ;;
        --timeout) TIMEOUT_MINUTES="${2:-45}"; shift 2 ;;
        --interval) INTERVAL="${2:-15}"; shift 2 ;;
        -h|--help) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

REPO="$(git config --get remote.origin.url 2>/dev/null \
    | sed -e 's#.*github.com[:/]##' -e 's#\.git$##')"
if [ -z "$REPO" ]; then
    echo "RESULT: failure (this is not a checkout with a GitHub remote)"
    exit 1
fi

deadline=$(( $(date +%s) + TIMEOUT_MINUTES * 60 ))

# The name of the workflow, which is what the run list calls it. Read once, so
# that the loop below makes one request per check rather than two.
WORKFLOW_NAME="$(gh workflow list --repo "$REPO" --all --json name,path \
    --jq ".[] | select(.path | endswith(\"/$WORKFLOW\")) | .name" 2>/dev/null | head -1)"
if [ -z "$WORKFLOW_NAME" ]; then
    echo "RESULT: failure (no workflow called $WORKFLOW in $REPO)"
    exit 1
fi

# The run to watch. With a commit given, that commit is watched exactly; that
# is the reliable way, because "the newest run" can be a run of a push that came
# after the one being asked about.
#
# The run list can carry the name of the workflow, so the run that is wanted is
# chosen in the one request that lists runs. Asking for each run separately to
# find out what it belongs to would be a request per run, for no gain.
pick_run() {
    local args=(--repo "$REPO" --limit 20 --json databaseId,headSha,status,conclusion,url,workflowName
        --jq ".[] | select(.workflowName == \"$WORKFLOW_NAME\") | \"\(.databaseId) \(.headSha) \(.status) \(.conclusion // \"-\")\"")
    if [ -n "$SHA" ]; then
        args+=(--commit "$SHA")
    else
        args+=(--branch "$BRANCH")
    fi
    gh run list "${args[@]}" 2>/dev/null | head -1
}

echo "watching $REPO ${SHA:-on $BRANCH} ($WORKFLOW)"

RUN=""
while [ -z "$RUN" ]; do
    RUN="$(pick_run)"
    if [ -z "$RUN" ]; then
        if [ "$(date +%s)" -gt "$deadline" ]; then
            echo "RESULT: timeout (no run appeared)"
            exit 1
        fi
        sleep "$INTERVAL"
    fi
done

RUN_ID="$(printf '%s' "$RUN" | cut -d' ' -f1)"
echo "run $RUN_ID"

# Poll until the run is finished. The state is read from the run list, which is
# one request, rather than from the run itself, which is several.
while :; do
    state="$(gh run list --repo "$REPO" --limit 20 \
        --json databaseId,status,conclusion \
        --jq ".[] | select(.databaseId == $RUN_ID) | \"\(.status)/\(.conclusion // \"-\")\"" \
        2>/dev/null | head -1)"

    echo "$(date +%H:%M:%S) $state"

    case "$state" in
        completed/*) break ;;
    esac

    if [ "$(date +%s)" -gt "$deadline" ]; then
        echo "RESULT: timeout (still $state)"
        exit 1
    fi
    sleep "$INTERVAL"
done

conclusion="${state#completed/}"

# Which steps failed, so that the next look is a look at the right thing.
if [ "$conclusion" != "success" ]; then
    gh run view "$RUN_ID" --repo "$REPO" --json jobs \
        --jq '.jobs[] | "\(.name): \(.conclusion)"' 2>/dev/null
    gh run view "$RUN_ID" --repo "$REPO" --json jobs \
        --jq '.jobs[] | select(.conclusion == "failure") | .steps[]
              | select(.conclusion == "failure") | "  failed step: \(.name)"' \
        2>/dev/null
fi

echo "RESULT: $conclusion"
case "$conclusion" in
    success) exit 0 ;;
    *) exit 1 ;;
esac
