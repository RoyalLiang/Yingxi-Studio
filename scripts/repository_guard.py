#!/usr/bin/env python3
"""Reject local data and high-confidence secrets from the publishable source set."""
import argparse
import re
import subprocess
from pathlib import Path

EXACT = {".gitignore", ".gitattributes", "README.md", "CONTRIBUTING.md", "version.json",
         "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
         "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
         "app/build.gradle.kts", "core/build.gradle.kts", "app/src/main/AndroidManifest.xml",
         "app/src/androidTest/AndroidManifest.xml", "app/src/androidTest/java/io/localplayer/app/FixtureDocumentsProvider.java", "app/src/androidTest/java/io/localplayer/app/GrantFixtureActivity.java", "docs/releasing.md", "docs/file-navigation.md"}

def allowed(name):
    p = Path(name)
    if any(part.startswith("._") or part in {".DS_Store", "__MACOSX", ".AppleDouble", "build", "__pycache__"} for part in p.parts): return False
    if name in EXACT: return True
    if p.parent == Path("releases") and p.suffix == ".md": return True
    return ((name.startswith(("app/src/main/java/", "app/src/androidTest/java/", "core/src/main/kotlin/", "core/src/test/kotlin/")) and p.suffix == ".kt")
            or (name.startswith("app/src/main/res/") and p.suffix == ".xml")
            or (name.startswith("scripts/") and p.suffix == ".py")
            or (name.startswith(".github/workflows/") and p.suffix == ".yml")
            or (name == ".github/dependabot.yml")
            or (name.startswith("third_party/") and p.suffix in {".txt", ".json"})
            or name in {"assets/brand/yingxi-icon.svg", "assets/brand/yingxi-icon.png", "assets/brand/yingxi-icon-preview.png"})

PATTERNS = {
    "private key": re.compile(rb"-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----"),
    "access token": re.compile(rb"(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[A-Z0-9]{16}|AIza[0-9A-Za-z_-]{30,})"),
    "credential URI": re.compile(rb"(?:https?|sftp|smb)://[^\s/@]+:[^\s/@]+@"),
    "machine path": re.compile(rb"/(?:Users|Volumes)/|/private/(?:tmp|var)/"),
    "legacy local fixture credential": re.compile(rb"fixture-only-" + rb"password"),
}

def inspect(name, data):
    errors = []
    if not allowed(name): errors.append("not in source publication allowlist")
    if data.startswith(bytes.fromhex("00051607")): errors.append("AppleDouble metadata")
    if Path(name).suffix not in {".png", ".jar"}:
        for label, pattern in PATTERNS.items():
            if pattern.search(data): errors.append(label)
        for ip in re.findall(rb"(?<![0-9])(?:10\.[0-9]+\.[0-9]+\.[0-9]+|192\.168\.[0-9]+\.[0-9]+|172\.(?:1[6-9]|2[0-9]|3[01])\.[0-9]+\.[0-9]+)(?![0-9])", data):
            if ip != b"10.0.2.2": errors.append("private-network address")
    return errors

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--git", action="store_true", help="Inspect exact index blobs, not unstaged worktree data")
    args = parser.parse_args(); root = args.root.resolve()
    if args.git:
        records = subprocess.check_output(["git", "ls-files", "--stage", "-z"], cwd=root).decode().split("\0")
        names=[]
        for record in filter(None,records):
            info,name=record.split("\t",1)
            mode,object_id,stage=info.split()
            if mode not in {"100644","100755"} or stage!="0":
                raise SystemExit("Index contains a symlink, gitlink or unresolved entry: "+name)
            names.append(name)
        names=sorted(names)
    else:
        names = sorted(p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file() and allowed(p.relative_to(root).as_posix()))
    errors = []
    for name in names:
        p = root / name
        if p.is_symlink(): errors.append((name, "symlink is not publishable")); continue
        data = subprocess.check_output(["git", "show", ":" + name], cwd=root) if args.git else p.read_bytes()
        errors.extend((name, issue) for issue in inspect(name, data))
    if errors:
        for name, issue in errors: print(f"{name}: {issue}")
        raise SystemExit(1)
    print(f"Source guard passed: {len(names)} files; no local runtime, test data or detected secrets")

if __name__ == "__main__": main()
