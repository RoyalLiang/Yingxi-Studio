#!/usr/bin/env python3
"""Resolve one immutable release candidate; create its missing tag after CI/build."""
import argparse
import json
import os
import re
import subprocess
from pathlib import Path
from release_notes import load_notes
from versioning import load


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def plan(root, requested_tag="", allow_create=False):
    version = load(root)
    tag = requested_tag or version["tag"]
    if tag != version["tag"]:
        raise ValueError("Requested tag must match version.json")
    load_notes(root, version)
    commit = git(root, "rev-parse", "HEAD")
    subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", commit, "origin/main"], check=True)
    tag_exists = subprocess.run(["git", "-C", str(root), "show-ref", "--verify", "--quiet", f"refs/tags/{tag}"]).returncode
    if tag_exists not in (0, 1):
        raise ValueError("Could not inspect release tag")
    if tag_exists == 0:
        if git(root, "rev-parse", f"refs/tags/{tag}^{{commit}}") != commit:
            raise ValueError("Existing version tag points to another commit; bump the version")
    elif not allow_create:
        raise ValueError("Manual and tag-triggered releases require an existing tag")
    return {"tag": tag, "ref": commit, "create_tag": str(tag_exists == 1).lower()}


def ensure_tag(root, tag):
    candidate = plan(root, tag, allow_create=True)
    tag = candidate["tag"]
    if candidate["create_tag"] == "false":
        return
    if os.environ.get("GITHUB_EVENT_NAME") != "push" or os.environ.get("GITHUB_REF") != "refs/heads/main":
        raise ValueError("Automatic tag creation requires a main push")
    if os.environ.get("GITHUB_SHA") != candidate["ref"]:
        raise ValueError("Release candidate must match the triggering commit")
    repo = os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Invalid repository identity")
    result = json.loads(subprocess.check_output([
        "gh", "api", "--method", "POST", f"repos/{repo}/git/refs",
        "-f", f"ref=refs/tags/{tag}", "-f", f'sha={candidate["ref"]}',
    ], text=True))
    if result.get("ref") != f"refs/tags/{tag}" or result.get("object", {}).get("sha") != candidate["ref"]:
        raise ValueError("Created tag identity mismatch")
    subprocess.run(["git", "-C", str(root), "fetch", "origin", "tag", tag], check=True)
    plan(root, tag)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("plan", "ensure-tag"))
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--tag", default="")
    parser.add_argument("--allow-create-tag", action="store_true")
    args = parser.parse_args()
    if args.mode == "ensure-tag":
        ensure_tag(args.root, args.tag)
    else:
        candidate = plan(args.root, args.tag, args.allow_create_tag)
        if os.environ.get("GITHUB_OUTPUT"):
            with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
                output.write("".join(f"{key}={value}\n" for key, value in candidate.items()))
        print(json.dumps(candidate))


if __name__ == "__main__":
    main()
