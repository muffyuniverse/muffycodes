#!/usr/bin/env bash
#
# ci — run the project's tests on GitHub Actions and say how they went.
#
# This is the command that tests the code. Building and testing do not happen on
# this machine any more — the Android toolchain needs hardware and an SDK this
# container cannot provide — so the way to find out whether a change works is to
# hand it to GitHub and wait for the answer. That is what this wraps.
#
# It watches the run, and when the run fails it does the part that costs the most
# time by hand: it goes into the failing job's log and pulls out the reason. Not
# "Test failed", but the block that says what went wrong and where.
#
# Usage:
#   ci [--branch NAME | --sha SHA] [--workflow FILE] [--timeout MIN]
#      [--push] [--watch-only]
#
#   --push        push the current branch first (a push is what starts a run)
#   --watch-only  never push; only watch whatever run already exists
#
# Run it in the foreground and wait, or — better, because waiting for a build in
# the foreground costs attention for no gain — start it in the background and
# read the answer later:
#
#   nohup ci --branch my-change > /tmp/ci.log 2>&1 &
#   # ... do something else ...
#   cat /tmp/ci.log          # the last line is the verdict
#
# The last line always starts with "RESULT:" and names success, failure,
# cancelled or timeout. That line is what a script looks for. Exit code is 0 on
# success, 1 on anything else, 2 on bad use.

set -uo pipefail

BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo main)"
SHA=""
WORKFLOW="build-and-release.yml"
TIMEOUT_MINUTES=20
INTERVAL=15
PUSH=0

while [ $# -gt 0 ]; do
    case "$1" in
        --branch) BRANCH="${2:-}"; shift 2 ;;
        --sha) SHA="${2:-}"; shift 2 ;;
        --workflow) WORKFLOW="${2:-}"; shift 2 ;;
        --timeout) TIMEOUT_MINUTES="${2:-20}"; shift 2 ;;
        --interval) INTERVAL="${2:-15}"; shift 2 ;;
        --push) PUSH=1; shift ;;
        --watch-only) PUSH=0; shift ;;
        -h|--help) sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

REPO="$(git config --get remote.origin.url 2>/dev/null \
    | sed -e 's#.*github.com[:/]##' -e 's#\.git$##')"
if [ -z "$REPO" ]; then
    echo "RESULT: failure (not a checkout with a GitHub remote)"
    exit 1
fi

# A push is what starts a run, so --push makes this the one command after an
# edit. Uncommitted work is the user's to commit: naming a change is not this
# script's job, and doing it silently would be a surprise.
if [ "$PUSH" = 1 ]; then
    if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
        echo "note: uncommitted changes are present — commit them first if they should be tested."
    fi
    if [ -n "$(git log --oneline "@{u}..HEAD" 2>/dev/null)" ]; then
        echo "pushing $BRANCH to start a run…"
        git push --quiet origin "$BRANCH" 2>&1 | sed 's/^/  /'
    else
        echo "nothing to push; watching the run for $BRANCH."
    fi
fi

deadline=$(( $(date +%s) + TIMEOUT_MINUTES * 60 ))

WORKFLOW_NAME="$(gh workflow list --repo "$REPO" --all --json name,path \
    --jq ".[] | select(.path | endswith(\"/$WORKFLOW\")) | .name" 2>/dev/null | head -1)"
if [ -z "$WORKFLOW_NAME" ]; then
    echo "RESULT: failure (no workflow $WORKFLOW in $REPO)"
    exit 1
fi

pick_run() {
    local args=(--repo "$REPO" --limit 20 --json databaseId,headSha,status,conclusion,workflowName
        --jq ".[] | select(.workflowName == \"$WORKFLOW_NAME\") | \"\(.databaseId) \(.status) \(.conclusion // \"-\")\"")
    if [ -n "$SHA" ]; then args+=(--commit "$SHA"); else args+=(--branch "$BRANCH"); fi
    gh run list "${args[@]}" 2>/dev/null | head -1
}

echo "watching $REPO ${SHA:-on $BRANCH} ($WORKFLOW_NAME)"

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
echo "run $RUN_ID  https://github.com/$REPO/actions/runs/$RUN_ID"

while :; do
    state="$(gh run list --repo "$REPO" --limit 20 \
        --json databaseId,status,conclusion \
        --jq ".[] | select(.databaseId == $RUN_ID) | \"\(.status)/\(.conclusion // \"-\")\"" \
        2>/dev/null | head -1)"
    echo "$(date +%H:%M:%S) $state"
    case "$state" in completed/*) break ;; esac
    if [ "$(date +%s)" -gt "$deadline" ]; then
        echo "RESULT: timeout (still $state)"
        exit 1
    fi
    sleep "$INTERVAL"
done

conclusion="${state#completed/}"

# The verdict is not the interesting part — the reason is. When a run fails, go
# into the jobs, find the one that broke, and pull the block from its log that
# says what went wrong. Reading that by hand, one API call at a time, is the slow
# part of testing remotely; this is what makes the command worth having.
if [ "$conclusion" != "success" ]; then
    echo ""
    echo "— what broke —"
    gh run view "$RUN_ID" --repo "$REPO" --json jobs \
        --jq '.jobs[] | select(.conclusion == "failure") | .id as $id | "\($id)  \(.name)"' \
        2>/dev/null | while read -r jid jname; do
            [ -z "${jid:-}" ] && continue
            echo ""
            echo "job: $jname"
            echo "failed steps:"
            gh run view "$RUN_ID" --repo "$REPO" --json jobs \
                --jq ".jobs[] | select(.id == $jid) | .steps[] | select(.conclusion == \"failure\") | \"  · \(.name)\"" 2>/dev/null
            echo "log excerpt:"
            gh api "repos/$REPO/actions/jobs/$jid/logs" 2>/dev/null \
                | sed 's/^[0-9T:.Z-]* //' \
                | grep -iE "what went wrong|error:|FAILED|Could not|> Task .*FAILED|tests? (failed|completed)|Caused by|^\* What" \
                | grep -viE "DEP[0-9]|DeprecationWarning|warning:|actionable tasks" \
                | head -25 | sed 's/^/    /'
        done
fi

# A build that passed on main or development leaves its bundle on GitHub,
# but a bundle nobody can hold is not a delivery. Bring it to the phone here,
# so that waiting on the run is the only thing a person does: when this says
# success, the APK is already in the Downloads folder.
#
# The renders of a real install arrive on a release minutes later, in a run
# of their own; running `bundle` again afterwards grows the folder with them.
if [ "$conclusion" = "success" ] && [ "$WORKFLOW" = "build-and-release.yml" ]; then
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
echo ""
if [ "$BRANCH" = "main" ]; then
bash "$HERE/fetch-bundle.sh" 2>&1 | sed 's/^/  /' \
|| echo "  (nothing was delivered)"
else
bash "$HERE/fetch-bundle.sh" --run "$RUN_ID" 2>&1 | sed 's/^/  /' \
|| echo "  (this run made no bundle — nothing in the app changed)"
fi
fi

echo ""
echo "RESULT: $conclusion   https://github.com/$REPO/actions/runs/$RUN_ID"
case "$conclusion" in
    success) exit 0 ;;
    *) exit 1 ;;
esac
