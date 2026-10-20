#!/usr/bin/env python3
"""Publish verified unsigned iOS alpha assets without modifying an existing tag or release."""
import argparse,json,os,subprocess,hashlib
from pathlib import Path

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);a=p.parse_args();out=a.output.resolve()
    metadata=json.loads((out/'yingxi-ios-release.json').read_text());report=json.loads((out/'ios-validation.json').read_text())
    if metadata['sourceCommit']!=os.environ['GITHUB_SHA'] or report['sourceCommit']!=metadata['sourceCommit'] or not report['coreTestsPassed'] or not report['simulatorLaunchPassed'] or metadata['developerSigned']:
        raise ValueError('iOS source, validation or signing state mismatch')
    assets=[]
    for line in (out/'IOS-SHA256SUMS').read_text().splitlines():
        digest,name=line.split(maxsplit=1);path=out/name.strip()
        if path.parent!=out or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest()!=digest:raise ValueError('iOS asset digest mismatch')
        assets.append(path)
    tag='ios-v'+metadata['versionName'];repo=os.environ['GITHUB_REPOSITORY']
    existing=subprocess.run(['gh','api','repos/'+repo+'/releases/tags/'+tag],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    if existing.returncode==0:raise ValueError('This iOS version already exists; use a new version rather than overwriting it')
    if '404' not in existing.stderr:raise ValueError('Cannot establish whether this release already exists')
    ref=subprocess.run(['gh','api','repos/'+repo+'/git/ref/tags/'+tag],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    if ref.returncode==0:
        obj=json.loads(ref.stdout)['object']
        for _ in range(4):
            if obj['type']=='commit':break
            if obj['type']!='tag':raise ValueError('Unsupported existing tag type')
            tagged=json.loads(subprocess.check_output(['gh','api','repos/'+repo+'/git/tags/'+obj['sha']],text=True));obj=tagged['object']
        if obj['type']!='commit' or obj['sha']!=metadata['sourceCommit']:raise ValueError('Existing tag refers to a different source; no overwrite allowed')
    elif '404' not in ref.stderr:raise ValueError('Cannot verify release tag state')
    command=['gh','release','create',tag,'--repo',repo,'--target',metadata['sourceCommit'],'--title','映汐播放器 iOS '+metadata['versionName']+'（未签名）','--prerelease','--notes-file',str(out/'release-notes.md')]
    subprocess.run(command+[str(p) for p in assets if p.suffix in {'.ipa','.zip'}],check=True)

if __name__=='__main__':main()
