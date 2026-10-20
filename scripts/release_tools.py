#!/usr/bin/env python3
"""Prepare audited APK-only release assets; publish without replacing releases."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path
from versioning import load
from release_notes import load_notes, render_notes

def run(*args): return subprocess.check_output(args, text=True).strip()
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def check_previous(current, previous):
    for item in previous:
        if current["versionCode"] <= item["versionCode"]:
            raise ValueError("versionCode must exceed every published release")
        if current["signerSha256"] != item["signerSha256"]:
            raise ValueError("Signing identity changed; do not break installed-app upgrades")

def verify_assets(directory):
    manifest=json.loads((directory/"yingxi-release.json").read_text())
    expected={manifest["fileName"],"yingxi-release.json","third-party-notices.txt","dependencies.json","SHA256SUMS"}
    if {p.name for p in directory.iterdir()} != expected: raise ValueError("Unexpected or missing release assets")
    seen=set()
    for line in (directory/"SHA256SUMS").read_text().splitlines():
        digest,name=line.split("  ",1)
        if name in seen or name not in expected-{"SHA256SUMS"} or sha(directory/name)!=digest: raise ValueError("Release asset checksum mismatch")
        seen.add(name)
    if seen!=expected-{"SHA256SUMS"}: raise ValueError("Release checksum coverage is incomplete")
    if sha(directory/manifest["fileName"])!=manifest["sha256"]: raise ValueError("APK checksum mismatch")
    return manifest

def prepare(args):
    root=Path(__file__).resolve().parents[1];version=load(root)
    load_notes(root,version)
    if args.apk is None:args.apk=root/f'dist/yingxi-{version["versionName"]}-{version["versionCode"]}-release.apk'
    if args.tag!=version["tag"]: raise ValueError("Tag/config mismatch")
    commit=run("git","rev-parse","HEAD")
    if run("git","rev-parse",f"refs/tags/{args.tag}^{{commit}}")!=commit: raise ValueError("Tag/checkout mismatch")
    subprocess.run(["git","merge-base","--is-ancestor",commit,"origin/main"],check=True)
    sdk=Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])/"build-tools/36.0.0"
    badging=run(str(sdk/"aapt"),"dump","badging",str(args.apk))
    package=re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'",badging)
    if not package or package.groups()!=("io.localplayer.app",str(version["versionCode"]),version["versionName"]): raise ValueError("Compiled APK/config mismatch")
    if "application-debuggable" in badging: raise ValueError("Debug APKs cannot be published")
    signature=run(str(sdk/"apksigner"),"verify","--verbose","--print-certs",str(args.apk))
    signer=re.search(r"Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})",signature)
    if not signer: raise ValueError("APK signer missing")
    fingerprint=signer.group(1).lower()
    expected=os.environ.get("YINGXI_SIGNER_SHA256","").replace(":","").lower()
    if expected and fingerprint!=expected: raise ValueError("APK signer does not match configured identity")
    output=args.output;output.mkdir(parents=True,exist_ok=True)
    if any(output.iterdir()): raise ValueError("Release output must be empty")
    name=f'yingxi-{version["versionName"]}-{version["versionCode"]}.apk'
    shutil.copyfile(args.apk,output/name)
    with zipfile.ZipFile(args.apk) as apk:
        if any(any(p.startswith("._") or p in {".DS_Store","__MACOSX"} for p in Path(n).parts) for n in apk.namelist()): raise ValueError("APK contains mac metadata")
        for asset in ("third-party-notices.txt","dependencies.json"):
            (output/asset).write_bytes(apk.read("assets/"+asset))
    metadata={"formatVersion":1,**version,"sourceCommit":commit,"fileName":name,"sizeBytes":args.apk.stat().st_size,"sha256":sha(args.apk),"signerSha256":fingerprint}
    (output/"yingxi-release.json").write_text(json.dumps(metadata,indent=2)+"\n")
    (output/"SHA256SUMS").write_text("".join(sha(p)+"  "+p.name+"\n" for p in sorted(output.iterdir())))
    verify_assets(output)
    print("Verified signed release assets:",version["tag"])

def publish(args):
    directory=args.output;metadata=verify_assets(directory)
    if metadata["tag"]!=args.tag: raise ValueError("Prepared tag mismatch")
    repo=os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+",repo): raise ValueError("Invalid repository identity")
    releases=json.loads(run("gh","api",f"repos/{repo}/releases","--paginate","--slurp"))
    previous=[];draft=None
    with tempfile.TemporaryDirectory() as temp:
        for release in (r for page in releases for r in page):
            if release["tag_name"]==args.tag:
                if not release["draft"]: raise ValueError("This version is already published; releases are never replaced")
                if release["target_commitish"]!=metadata["sourceCommit"]: raise ValueError("Existing draft belongs to a different commit")
                draft=release;continue
            if release["draft"]: continue
            # iOS has an independent version/signing history and a separate manifest.
            if re.fullmatch(r"ios-v\d+\.\d+\.\d+-alpha\.\d+", release["tag_name"]): continue
            if not any(a["name"]=="yingxi-release.json" for a in release["assets"]): raise ValueError("Published version history lacks metadata; migrate it before publishing")
            destination=Path(temp)/str(release["id"]);destination.mkdir()
            subprocess.run(["gh","release","download",release["tag_name"],"--repo",repo,"--pattern","yingxi-release.json","--dir",str(destination)],check=True)
            previous.append(json.loads((destination/"yingxi-release.json").read_text()))
    check_previous(metadata,previous)
    with tempfile.TemporaryDirectory() as temp:
        notes=Path(temp)/"notes.md"
        root=Path(__file__).resolve().parents[1]
        notes.write_text(render_notes(root,metadata,metadata["sourceCommit"]),encoding="utf-8")
        if draft is None:
            command=["gh","release","create",args.tag,"--repo",repo,"--verify-tag","--target",metadata["sourceCommit"],"--draft","--title","映汐播放器 "+metadata["versionName"],"--notes-file",str(notes)]
            if metadata["prerelease"]: command.append("--prerelease")
            subprocess.run(command,check=True)
        subprocess.run(["gh","release","upload",args.tag,"--repo",repo,"--clobber",*[str(p) for p in sorted(directory.iterdir())]],check=True)
        command=["gh","release","edit",args.tag,"--repo",repo,"--draft=false","--prerelease="+str(metadata["prerelease"]).lower(),"--notes-file",str(notes)]
        command.append("--latest=false" if metadata["prerelease"] else "--latest")
        subprocess.run(command,check=True)
    print("Published:",args.tag)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode",choices=("prepare","publish"))
    parser.add_argument("--apk",type=Path)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--tag",required=True)
    args=parser.parse_args()
    (prepare if args.mode=="prepare" else publish)(args)

if __name__=="__main__": main()
