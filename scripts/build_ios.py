#!/usr/bin/env python3
"""Build an unsigned iOS 27 alpha and a simulator bundle using a matched Xcode installation."""
import argparse
import hashlib
import json
import os
import platform
import plistlib
import re
import shutil
import stat
import subprocess
import tempfile
import time
import zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
BUNDLE='io.localplayer.app'
NAME='映汐播放器'


def run(args,log=None):
    p=subprocess.run([str(x) for x in args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    if log:Path(log).write_text(p.stdout)
    if p.returncode:
        errors=[line for line in p.stdout.splitlines() if 'error:' in line.lower() or 'failed' in line.lower()]
        for line in errors[-12:]:print('::error::'+line.replace('%','%25').replace('\r','%0D').replace('\n','%0A'),flush=True)
        raise RuntimeError('Command failed: '+str(args[0])+'; see build log')
    return p.stdout.strip()


def forbidden(path):
    return any(p.startswith('._') or p in {'.DS_Store','__MACOSX','.AppleDouble','xcuserdata'} for p in path.parts)


def pack_application(app,output,prefix):
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for path in sorted(Path(app).rglob('*')):
            relative=path.relative_to(app)
            if path.is_symlink():raise ValueError('Symlinks are not allowed in the alpha bundle')
            if forbidden(relative):raise ValueError('Prohibited metadata in application bundle')
            if not path.is_file():continue
            info=zipfile.ZipInfo((Path(prefix)/relative).as_posix());info.create_system=3
            info.external_attr=(stat.S_IFREG | (path.stat().st_mode & 0o755))<<16;info.compress_type=zipfile.ZIP_DEFLATED
            z.writestr(info,path.read_bytes())
    with zipfile.ZipFile(output) as z:
        if any(forbidden(Path(n)) for n in z.namelist()):raise ValueError('Prohibited archive metadata')


def preflight():
    version=run(['xcodebuild','-version'])
    sdk=run(['xcrun','--sdk','iphoneos','--show-sdk-version'])
    if int(sdk.split('.')[0])<27:raise ValueError('The requested build requires an iOS 27 or newer SDK')
    return version,sdk


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,required=True);a=parser.parse_args()
    xcode,sdk=preflight();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    version=json.loads((ROOT/'ios/version.json').read_text());name=version['versionName'];code=version['versionCode']
    if not re.fullmatch(r'\d+\.\d+\.\d+-alpha\.\d+',name) or not isinstance(code,int) or code<1:raise ValueError('Invalid alpha version')
    source=run(['git','rev-parse','HEAD']);stage=Path(tempfile.mkdtemp(prefix='yingxi-ios-build-',dir=os.environ.get('RUNNER_TEMP')))
    project=stage/'project';shutil.copytree(ROOT/'ios',project,copy_function=shutil.copyfile,ignore=shutil.ignore_patterns('.build','build','._*','.DS_Store','__MACOSX','xcuserdata'))
    run(['xcrun','swift','test','--package-path',project,'--scratch-path',stage/'core'],out/'core-tests.log')
    common=['xcodebuild','build','-project',project/'YingxiPlayer.xcodeproj','-scheme','YingxiPlayer','CODE_SIGNING_ALLOWED=NO','CODE_SIGNING_REQUIRED=NO','CURRENT_PROJECT_VERSION='+str(code),'MARKETING_VERSION='+name.split('-')[0],'YINGXI_ALPHA_VERSION='+name]
    device=stage/'device';sim=stage/'simulator'
    run(common+['-configuration','Release','-destination','generic/platform=iOS','-derivedDataPath',device],out/'device-build.log')
    run(common+['-configuration','Debug','-destination','generic/platform=iOS Simulator','-derivedDataPath',sim,'ARCHS='+platform.machine(),'ONLY_ACTIVE_ARCH=YES'],out/'simulator-build.log')
    app=device/'Build/Products/Release-iphoneos/YingxiPlayer.app';simapp=sim/'Build/Products/Debug-iphonesimulator/YingxiPlayer.app'
    for bundle in (app,simapp):
        info=plistlib.loads((bundle/'Info.plist').read_bytes())
        if info.get('CFBundleIdentifier')!=BUNDLE or info.get('CFBundleDisplayName')!=NAME or info.get('CFBundleVersion')!=str(code):raise ValueError('Built app identity does not match requested version')
    if (app/'embedded.mobileprovision').exists():raise ValueError('Unsigned build unexpectedly contains a provisioning profile')
    ipa=out/('yingxi-ios-'+name+'-unsigned.ipa');archive=out/('yingxi-ios-'+name+'-simulator-'+platform.machine()+'.zip')
    pack_application(app,ipa,'Payload/YingxiPlayer.app');pack_application(simapp,archive,'YingxiPlayer.app')
    devices=json.loads(run(['xcrun','simctl','list','devices','available','--json']))['devices']
    runtime=next((key for key in devices if '.iOS-27' in key and devices[key]),None)
    if runtime is None:raise ValueError('No iOS 27 simulator runtime is available for launch validation')
    sample=devices[runtime][0];device_type=sample.get('deviceTypeIdentifier')
    if not device_type:
        types=json.loads(run(['xcrun','simctl','list','devicetypes','--json']))['devicetypes'];device_type=next(x['identifier'] for x in types if x['name']==sample['name'])
    udid=run(['xcrun','simctl','create','YingxiAlpha-'+os.environ.get('GITHUB_RUN_ID',str(os.getpid())),device_type,runtime])
    try:
        run(['xcrun','simctl','boot',udid]);run(['xcrun','simctl','bootstatus',udid,'-b']);run(['xcrun','simctl','install',udid,simapp])
        container=Path(run(['xcrun','simctl','get_app_container',udid,BUNDLE,'data']));media=container/'Documents/Imported';media.mkdir(parents=True,exist_ok=True)
        clip=media/'alpha-fixture.mp4';run(['xcrun','swift',project/'Tests/SmokeFixture.swift',clip],out/'fixture-build.log')
        run(['xcrun','simctl','launch',udid,BUNDLE]);time.sleep(3);run(['xcrun','simctl','io',udid,'screenshot',out/'ios-home.png'])
        run(['xcrun','simctl','launch','--terminate-running-process',udid,BUNDLE,'-ui-play-first'])
        proof=container/'Documents/alpha-smoke.json'
        for _ in range(45):
            if proof.exists():break
            time.sleep(1)
        if not proof.exists():raise ValueError('Simulator did not confirm a decoded local video frame')
        playback=json.loads(proof.read_text());assert playback['ready'] and playback['width']>0 and playback['position']>0
        run(['xcrun','simctl','io',udid,'screenshot',out/'ios-playback.png'])
    finally:
        subprocess.run(['xcrun','simctl','shutdown',udid],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        subprocess.run(['xcrun','simctl','delete',udid],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    report={'sourceCommit':source,'xcode':xcode,'sdkVersion':sdk,'simulatorRuntime':runtime,'simulatorArchitecture':platform.machine(),'coreTestsPassed':True,'simulatorLaunchPassed':True,'decodedLocalPlayback':playback,'physicalDeviceTested':False,'realWebDAVTested':False,'developerSigned':False}
    (out/'ios-validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    metadata={'platform':'iOS','displayName':NAME,'bundleIdentifier':BUNDLE,'versionName':name,'versionCode':code,'minimumOSVersion':'27.0','sdkVersion':sdk,'sourceCommit':source,'developerSigned':False,'requiresResigningForDevice':True,'artifacts':[{'fileName':p.name,'sizeBytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in (ipa,archive)]}
    (out/'yingxi-ios-release.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2))
    names=[ipa.name,archive.name,'yingxi-ios-release.json','ios-validation.json','ios-home.png','ios-playback.png']
    (out/'IOS-SHA256SUMS').write_text(''.join(hashlib.sha256((out/n).read_bytes()).hexdigest()+'  '+n+'\n' for n in names))
    notes=(ROOT/'ios/releases'/('v'+name+'.md')).read_text();(out/'release-notes.md').write_text(notes+'\n\nSource commit: `'+source+'`\n\nSDK: `'+sdk+'`\n')
    print('iOS alpha built and validated:',ipa.name,archive.name,flush=True)

if __name__=='__main__':main()
