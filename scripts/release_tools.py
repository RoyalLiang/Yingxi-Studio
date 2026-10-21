#!/usr/bin/env python3
"""Prepare audited installers and tagged source archives; never replace a release."""
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
from versioning import load, validate
from apk_variants import ABIS, apk_name, verify_abi
from release_notes import load_notes, render_notes
from repository_guard import allowed, inspect

def run(*args): return subprocess.check_output(args, text=True).strip()
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def verify_source_archive(path, version, commit):
    prefix=f'yingxi-{version["versionName"]}/'
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None or archive.comment.decode('ascii') != commit:
            raise ValueError('Source archive does not match the immutable commit')
        names=set()
        for item in archive.infolist():
            if item.is_dir(): continue
            if not item.filename.startswith(prefix): raise ValueError('Unexpected source archive root')
            name=item.filename[len(prefix):]
            if name in names or '\\' in name or not allowed(name) or any(p in {'..','.',''} for p in name.split('/')) or (item.external_attr>>16)&0o170000==0o120000:
                raise ValueError('Unsafe source archive entry: '+name)
            names.add(name)
            if inspect(name,archive.read(item)): raise ValueError('Source archive guard rejected: '+name)
        if not {'version.json','README.md'}.issubset(names): raise ValueError('Incomplete source archive')
        stored=json.loads(archive.read(prefix+'version.json'))
        if any(stored.get(k)!=version[k] for k in ['versionName','versionCode']): raise ValueError('Source archive version mismatch')

def prepare_source_archive(root, version, commit, output):
    name=f'source-code-{version["versionName"]}.zip'
    path=output/name
    data=subprocess.check_output(['git','-C',str(root),'archive','--format=zip',f'--prefix=yingxi-{version["versionName"]}/',commit])
    path.write_bytes(data)
    verify_source_archive(path,version,commit)
    return {'sourceFileName':name,'sourceSha256':sha(path),'sourceSizeBytes':path.stat().st_size}

def check_previous(current, previous):
    for item in previous:
        if current["versionCode"] <= item["versionCode"]:
            raise ValueError("versionCode must exceed every published release")
        if current["signerSha256"] != item["signerSha256"]:
            raise ValueError("Signing identity changed; do not break installed-app upgrades")

def verify_assets(directory):
    manifest=json.loads((directory/"yingxi-release.json").read_text())
    split=manifest.get("formatVersion",1)>=3
    apks=manifest.get("apks",[]) if split else [{"fileName":manifest["fileName"],"sha256":manifest["sha256"]}]
    if split:
        if len(apks)!=len(ABIS) or {a["abi"] for a in apks}!=set(ABIS):raise ValueError("Missing or duplicate architecture")
        if any(a["fileName"]!=apk_name(manifest,a["abi"]) for a in apks):raise ValueError("Architecture APK name mismatch")
    names={a["fileName"] for a in apks}
    if len(names)!=len(apks) or any(Path(name).name!=name for name in names):raise ValueError("Unsafe or duplicate APK name")
    expected=names|{"yingxi-release.json","third-party-notices.txt","dependencies.json","SHA256SUMS"}
    if split:expected.add("mapping.txt")
    if manifest.get('formatVersion',1)>=2:
        if manifest.get('sourceFileName')!=f'source-code-{manifest["versionName"]}.zip':raise ValueError('Missing or invalid source archive')
        expected.add(manifest['sourceFileName'])
    if {p.name for p in directory.iterdir()}!=expected:raise ValueError("Unexpected release assets")
    seen=set()
    for line in (directory/"SHA256SUMS").read_text().splitlines():
        digest,name=line.split("  ",1)
        if name in seen or name not in expected-{"SHA256SUMS"} or sha(directory/name)!=digest:raise ValueError("Release asset checksum mismatch")
        seen.add(name)
    if seen!=expected-{"SHA256SUMS"}:raise ValueError("Release checksum coverage incomplete")
    for apk in apks:
        path=directory/apk["fileName"]
        if sha(path)!=apk["sha256"]:raise ValueError("APK checksum mismatch")
        if split:
            if path.stat().st_size!=apk["sizeBytes"]:raise ValueError("APK size mismatch")
            verify_abi(path,apk["abi"])
    if split and sha(directory/"mapping.txt")!=manifest["mappingSha256"]:raise ValueError("Mapping checksum mismatch")
    if manifest.get('formatVersion',1)>=2:
        source=directory/manifest['sourceFileName']
        if sha(source)!=manifest['sourceSha256'] or source.stat().st_size!=manifest['sourceSizeBytes']:raise ValueError('Source archive checksum mismatch')
        verify_source_archive(source,manifest,manifest['sourceCommit'])
    return manifest

def historical_apk(version, release):
    if release['tag_name'] != version['tag']:
        raise ValueError('Historical tag and source version disagree')
    name=f'yingxi-{version["versionName"]}-{version["versionCode"]}.apk'
    matches=[a for a in release['assets'] if a['name']==name]
    if len(matches)!=1:
        raise ValueError('Historical release must contain its unique official APK')
    return name


def previous_from_apk(root, repo, release, destination):
    tag=release['tag_name']
    if not re.fullmatch(r'v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?',tag):
        raise ValueError('Unrecognized Android release tag')
    version=validate(json.loads(run('git','-C',str(root),'show',f'refs/tags/{tag}:version.json')))
    name=historical_apk(version,release)
    subprocess.run(['git','-C',str(root),'merge-base','--is-ancestor',f'refs/tags/{tag}','origin/main'],check=True)
    subprocess.run(['gh','release','download',tag,'--repo',repo,'--pattern',name,'--dir',str(destination)],check=True)
    sdk=Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])/'build-tools/36.0.0'
    badging=run(str(sdk/'aapt'),'dump','badging',str(destination/name))
    package=re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'",badging)
    if not package or package.groups()!=('io.localplayer.app',str(version['versionCode']),version['versionName']) or 'application-debuggable' in badging:
        raise ValueError('Historical APK does not match its tagged source')
    signature=run(str(sdk/'apksigner'),'verify','--verbose','--print-certs',str(destination/name))
    signer=re.search(r'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})',signature)
    if not signer:raise ValueError('Historical APK has no verified signer')
    return {**version,'signerSha256':signer.group(1).lower()}

def prepare(args):
    root=Path(__file__).resolve().parents[1];version=load(root)
    if args.tag!=version["tag"]:raise ValueError("Tag/config mismatch")
    commit=run("git","rev-parse","HEAD")
    if run("git","rev-parse",f"refs/tags/{args.tag}^{{commit}}")!=commit:raise ValueError("Tag/checkout mismatch")
    subprocess.run(["git","merge-base","--is-ancestor",commit,"origin/main"],check=True)
    sdk=Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])/"build-tools/36.0.0"
    expected=os.environ.get("YINGXI_SIGNER_SHA256","").replace(":","").lower()
    if not re.fullmatch(r"[a-f0-9]{64}",expected):raise ValueError("Configure stable release signer")
    output=args.output;output.mkdir(parents=True,exist_ok=True)
    if any(output.iterdir()):raise ValueError("Release asset output must be empty")
    directory=args.apk.parent if args.apk else root/"dist"
    apks=[]
    notices={}
    for abi in ABIS:
        path=args.apk if args.apk and abi=="arm64-v8a" else directory/apk_name(version,abi,"release")
        verify_abi(path,abi)
        badging=run(str(sdk/"aapt"),"dump","badging",str(path))
        package=re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'",badging)
        if not package or package.groups()!=("io.localplayer.app",str(version["versionCode"]),version["versionName"]):raise ValueError("Compiled APK/config mismatch")
        if "application-debuggable" in badging:raise ValueError("Debug APKs cannot be released")
        signature=run(str(sdk/"apksigner"),"verify","--verbose","--print-certs",str(path))
        signer=re.search(r"Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})",signature)
        if not signer or signer.group(1).lower()!=expected:raise ValueError("APK does not use stable release signer")
        name=apk_name(version,abi);shutil.copyfile(path,output/name)
        with zipfile.ZipFile(path) as apk:
            if any(any(p.startswith("._") or p in {".DS_Store","__MACOSX"} for p in n.split("/")) for n in apk.namelist()):raise ValueError("APK includes platform metadata")
            current={asset:apk.read("assets/"+asset) for asset in ("third-party-notices.txt","dependencies.json")}
            if notices and current!=notices:raise ValueError("Architecture notices differ")
            notices=current
        apks.append({"abi":abi,"fileName":name,"sizeBytes":path.stat().st_size,"sha256":sha(path)})
    for name,data in notices.items():(output/name).write_bytes(data)
    mapping=root/"dist/reports/r8/mapping.txt"
    if not mapping.is_file() or mapping.stat().st_size==0:raise ValueError("R8 mapping is missing")
    shutil.copyfile(mapping,output/"mapping.txt")
    source=prepare_source_archive(root,version,commit,output)
    primary=next(apk for apk in apks if apk["abi"]=="arm64-v8a")
    metadata={"formatVersion":3,**version,"sourceCommit":commit,**{k:v for k,v in primary.items() if k!="abi"},"apks":apks,"signerSha256":expected,"mappingSha256":sha(output/"mapping.txt"),**source}
    (output/"yingxi-release.json").write_text(json.dumps(metadata,indent=2)+"\n")
    (output/"SHA256SUMS").write_text("".join(sha(p)+"  "+p.name+"\n" for p in sorted(output.iterdir())))
    verify_assets(output)

def publish(args):
    directory=args.output;metadata=verify_assets(directory)
    if metadata["tag"]!=args.tag: raise ValueError("Prepared tag mismatch")
    repo=os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+",repo): raise ValueError("Invalid repository identity")
    releases=json.loads(run("gh","api",f"repos/{repo}/releases","--paginate","--slurp"))
    previous=[];draft=None
    published_names={a['fileName'] for a in metadata['apks']} if metadata.get('formatVersion',1)>=3 else {metadata['fileName']}
    if metadata.get('sourceFileName'): published_names.add(metadata['sourceFileName'])
    with tempfile.TemporaryDirectory() as temp:
        for release in (r for page in releases for r in page):
            if release["tag_name"]==args.tag:
                if not release["draft"]: raise ValueError("This version is already published; releases are never replaced")
                if release["target_commitish"]!=metadata["sourceCommit"]: raise ValueError("Existing draft belongs to a different commit")
                if any(asset['name'] not in published_names for asset in release['assets']):
                    raise ValueError('Existing draft has extra assets; back them up and clean them before publication')
                draft=release;continue
            if release["draft"]: continue
            # iOS has an independent version/signing history and a separate manifest.
            if re.fullmatch(r"ios-v\d+\.\d+\.\d+-alpha\.\d+", release["tag_name"]): continue
            destination=Path(temp)/str(release["id"]);destination.mkdir()
            previous.append(previous_from_apk(Path(run("git","rev-parse","--show-toplevel")),repo,release,destination))
    check_previous(metadata,previous)
    with tempfile.TemporaryDirectory() as temp:
        notes=Path(temp)/"notes.md"
        root=Path(__file__).resolve().parents[1]
        notes.write_text(render_notes(root,metadata,metadata["sourceCommit"]),encoding="utf-8")
        if draft is None:
            command=["gh","release","create",args.tag,"--repo",repo,"--verify-tag","--target",metadata["sourceCommit"],"--draft","--title","映汐播放器 "+metadata["versionName"],"--notes-file",str(notes)]
            if metadata["prerelease"]: command.append("--prerelease")
            subprocess.run(command,check=True)
        subprocess.run(["gh","release","upload",args.tag,"--repo",repo,"--clobber",*[str(directory/name) for name in sorted(published_names)]],check=True)
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
