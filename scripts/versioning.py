#!/usr/bin/env python3
"""Shared release-tool view of the repository version (never allocates codes)."""
import argparse
import json
import re
from pathlib import Path

NUMBER = r"(?:0|[1-9][0-9]*)"
PRE = r"(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
SEMVER = re.compile(rf"{NUMBER}\.{NUMBER}\.{NUMBER}(?:-{PRE}(?:\.{PRE})*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?")

def validate(data):
    if set(data) != {"versionName", "versionCode"}:
        raise ValueError("version.json must contain only versionName and versionCode")
    name, code = data["versionName"], data["versionCode"]
    if not isinstance(name, str) or not SEMVER.fullmatch(name):
        raise ValueError("versionName must be a valid SemVer, e.g. 0.2.0-alpha.1")
    if type(code) is not int or not 1 <= code <= 2_100_000_000:
        raise ValueError("versionCode must be an integer in 1..2100000000")
    return {"versionName": name, "versionCode": code, "tag": "v" + name,
            "prerelease": "-" in name.split("+", 1)[0]}

def load(root):
    return validate(json.loads((Path(root) / "version.json").read_text(encoding="utf-8")))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--tag", help="Require exact release tag/config agreement")
    parser.add_argument("--previous-code", type=int, help="Require a newer Android install code")
    args = parser.parse_args()
    version = load(args.root)
    if args.tag is not None and args.tag != version["tag"]:
        raise SystemExit("Release tag does not match version.json")
    if args.previous_code is not None and version["versionCode"] <= args.previous_code:
        raise SystemExit("versionCode must increase for every published build")
    print(json.dumps(version))

if __name__ == "__main__":
    main()
