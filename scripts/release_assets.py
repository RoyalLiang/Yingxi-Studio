#!/usr/bin/env python3
"""Back up and remove non-installer attachments from this repository's published releases."""
import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path
from versioning import validate


def managed_tag(tag):
    name=tag[5:] if tag.startswith('ios-v') else tag[1:] if tag.startswith('v') else ''
    try:validate({'versionName':name,'versionCode':1});return True
    except ValueError:return False


def keep_asset(name):
    return bool(re.fullmatch(r'[^/\\]+\.(?:apk|ipa)',name) or
                re.fullmatch(r'yingxi-ios-[0-9A-Za-z.+-]+-simulator-(?:arm64|x86_64)\.zip',name) or
                re.fullmatch(r'(?:source|source-code|source_code)(?:[.-][0-9A-Za-z.+-]+)?\.(?:zip|tar\.gz|tar\.bz2|tar\.xz)',name))


def api(repo,path,*args):
    return json.loads(subprocess.check_output(['gh','api',f'repos/{repo}/{path}',*args],text=True))


def backup(repo,out):
    if out.exists() and any(out.iterdir()):raise ValueError('Backup destination must be empty')
    out.mkdir(parents=True,exist_ok=True)
    pages=api(repo,'releases','--paginate','--slurp')
    planned=[]
    for page in pages:
        for release in page:
            if release['draft'] or not managed_tag(release['tag_name']):continue
            for asset in release['assets']:
                if keep_asset(asset['name']):continue
                filename=f'{release["id"]}-{asset["id"]}.bin'
                path=out/filename
                with path.open('wb') as stream:
                    subprocess.run(['gh','api',f'repos/{repo}/releases/assets/{asset["id"]}','-H','Accept: application/octet-stream'],stdout=stream,check=True)
                digest=hashlib.sha256(path.read_bytes()).hexdigest()
                if path.stat().st_size!=asset['size'] or (asset.get('digest') and asset['digest']!='sha256:'+digest):raise ValueError('Backup does not match published asset')
                planned.append({'tag':release['tag_name'],'releaseId':release['id'],'id':asset['id'],'name':asset['name'],'size':asset['size'],'digest':asset.get('digest'),'backup':filename,'backupSha256':digest})
    plan={'repository':repo,'assets':planned}
    (out/'cleanup-plan.json').write_text(json.dumps(plan,indent=2)+'\n')
    print(f'Backed up {len(planned)} non-installer assets; no release was changed')


def apply(repo,out):
    plan=json.loads((out/'cleanup-plan.json').read_text())
    if plan['repository']!=repo:raise ValueError('Backup repository mismatch')
    results=[]
    for asset in plan['assets']:
        path=out/asset['backup']
        if path.parent!=out or hashlib.sha256(path.read_bytes()).hexdigest()!=asset['backupSha256'] or keep_asset(asset['name']):raise ValueError('Unsafe or damaged cleanup plan')
        current=subprocess.run(['gh','api',f'repos/{repo}/releases/assets/{asset["id"]}'],capture_output=True,text=True)
        if current.returncode:
            if '404' in current.stderr:results.append({'id':asset['id'],'result':'already absent'});continue
            raise ValueError('Cannot verify current release asset')
        now=json.loads(current.stdout)
        if any(now.get(k)!=asset.get(k) for k in ['id','name','size','digest']):raise ValueError('Release asset changed after backup')
        subprocess.run(['gh','api','--method','DELETE',f'repos/{repo}/releases/assets/{asset["id"]}'],check=True)
        results.append({'id':asset['id'],'name':asset['name'],'tag':asset['tag'],'result':'removed'})
    (out/'cleanup-result.json').write_text(json.dumps(results,indent=2)+'\n')
    print(f'Cleanup complete: {len(results)} assets; installers and automatic source archives retained')


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('mode',choices=['backup','apply']);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    repo=os.environ['GITHUB_REPOSITORY']
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+',repo):raise ValueError('Invalid repository')
    (backup if args.mode=='backup' else apply)(repo,args.output.resolve())


if __name__=='__main__':main()
