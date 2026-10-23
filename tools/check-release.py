#!/usr/bin/env python3
"""
Prove that the release that was just published is the one that was just built.

Publishing is several operations against something that cannot be undone: a tag
is created, an APK is uploaded, twenty pictures are uploaded, a body is written.
Any of them can fail on its own, and a failure halfway leaves a release that
looks published and is not. Worse, a run that is cancelled during the upload
leaves exactly that, and the *next* run of the same commit finds the name already
taken and would happily report "already published" for a version that is missing
its assets.

So the result is read back rather than assumed. Four things must hold:

  1. the tag points at the commit this run built — not whatever the branch was
     pointing at when the tag was created;
  2. the APK is attached;
  3. every picture the report links to is attached, so no link in the published
     report is dead;
  4. the attached APK is byte for byte the file that was built and attested.

Anything else is a failure with the reason named, because a release is the one
thing in this pipeline that a person installs and cannot take back.

    check-release.py --version 74
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path


def gh(*args: str) -> str:
    result = subprocess.run(["gh", *args], capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True, help="The release name, without the v.")
    parser.add_argument("--repo", default="muffyuniverse/muffycodes")
    parser.add_argument("--sha", required=True, help="The commit this run built.")
    parser.add_argument("--apk", default="bundle/app-release.apk")
    parser.add_argument("--report", default="bundle/scenarios/README.md")
    args = parser.parse_args()

    tag = f"v{args.version}"
    problems: list[str] = []

    try:
        published = json.loads(
            gh("release", "view", tag, "--repo", args.repo, "--json", "targetCommitish,assets")
        )
    except (RuntimeError, json.JSONDecodeError) as problem:
        # Not being able to read the release is not evidence that it is fine.
        print(f"::error title=Release:: could not read {tag}: {problem}")
        return 1
    assets = {asset["name"] for asset in published.get("assets", [])}

    if published.get("targetCommitish") != args.sha:
        problems.append(
            f"the tag points at {published.get('targetCommitish')!r}, but this run built "
            f"{args.sha} — the release would carry an APK from a commit it does not name"
        )

    if "app-release.apk" not in assets:
        problems.append("app-release.apk is not attached")

    # Every picture the published report points at must exist, or the report is a
    # page full of broken images — which is how a report quietly stops being read.
    report = Path(args.report)
    linked: set[str] = set()
    if report.is_file():
        text = report.read_text()
        for match in re.finditer(r"scenario-[^)\s]+\.png", text):
            linked.add(match.group(0))
        if not linked:
            problems.append(f"{report} links to no pictures at all")
        missing = sorted(linked - assets)
        if missing:
            problems.append(f"the report links to {len(missing)} picture(s) not attached: {', '.join(missing[:5])}")
    else:
        problems.append(f"{report} is missing, so the report cannot be checked")

    # The bytes. An asset with the right name and the wrong content is the failure
    # a structural check cannot see, and the APK is what the phone installs.
    local = Path(args.apk)
    if local.is_file():
        with tempfile.TemporaryDirectory() as folder:
            try:
                gh(
                    "release", "download", tag,
                    "--repo", args.repo,
                    "--pattern", "app-release.apk",
                    "--dir", folder,
                    "--clobber",
                )
                built = sha256(local)
                shipped = sha256(Path(folder) / "app-release.apk")
                if built != shipped:
                    problems.append(
                        f"the published APK is not the one that was built "
                        f"(built {built[:16]}, published {shipped[:16]})"
                    )
                else:
                    print(f"  published APK matches the built one: {built[:16]}")
            except RuntimeError as problem:
                problems.append(f"could not download the published APK: {problem}")

    pictures = sum(1 for name in assets if name.startswith("scenario-") and name.endswith(".png"))
    print(f"  {tag}: {len(assets)} assets, {pictures} pictures, {len(linked)} linked from the report")

    for problem in problems:
        print(f"::error title=Release:: {problem}")
    if problems:
        print("  A release that is not what was built must not be reported as published.")
        return 1
    print(f"  {tag} is published, from {args.sha[:8]}, and holds what it claims")
    return 0


if __name__ == "__main__":
    sys.exit(main())
