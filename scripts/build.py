#!/usr/bin/env python3
"""Build on a local filesystem; keep Android/Gradle caches and platform metadata out of the share."""
import hashlib,json,os,shutil,subprocess,sys,tempfile
from pathlib import Path
from versioning import validate

project=Path(__file__).resolve().parents[1]
work=Path(os.environ.get('PLAYER_BUILD_DIR',str(Path(tempfile.gettempdir())/'new-player-build')))
work.mkdir(parents=True,exist_ok=True)
workspace=work/'workspace';workspace.mkdir(exist_ok=True)
if workspace.resolve()==project or project in workspace.resolve().parents:
    raise SystemExit('Use a build staging directory outside the source project')
for name in ['settings.gradle.kts','build.gradle.kts','gradle.properties','version.json','releases','gradlew','gradlew.bat','gradle','core','app','third_party']:
    source=project/name
    target=workspace/name
    if target.is_symlink():raise SystemExit('Unexpected symlink in managed build staging: '+str(target))
    if not source.exists():
        if target.is_symlink() or target.is_file():target.unlink()
        elif target.is_dir():shutil.rmtree(target)
        continue
    if source.is_dir():
        # Remove stale source files while keeping incremental build outputs.
        if name in ('core','app') and target.is_dir():
            for old in target.iterdir():
                if old.name=='build':continue
                if old.is_symlink() or old.is_file():old.unlink()
                else:shutil.rmtree(old)
        elif target.exists():shutil.rmtree(target)
        shutil.copytree(source,target,dirs_exist_ok=True,copy_function=shutil.copyfile,
            ignore=shutil.ignore_patterns('build','.gradle','._*','.DS_Store','__MACOSX'))
    else:shutil.copyfile(source,target)
env={**os.environ,'COPYFILE_DISABLE':'1','PYTHONDONTWRITEBYTECODE':'1','GRADLE_USER_HOME':os.environ.get('GRADLE_USER_HOME',str(work/'gradle-cache'))}
if env.get('PLAYER_FIXTURE_CONFIG'):
    fixture=json.loads(Path(env['PLAYER_FIXTURE_CONFIG']).read_text())
    allowed={'PLAYER_TEST_PASSWORD','PLAYER_TEST_USERNAME','PLAYER_SMB_FIXTURE_PORT','PLAYER_SFTP_FIXTURE_PORT'}
    if not set(fixture)<=allowed:raise SystemExit('Unexpected fixture config fields')
    env.update({k:str(v) for k,v in fixture.items()})
toolfile=Path(env['PLAYER_TOOLCHAIN_FILE']) if env.get('PLAYER_TOOLCHAIN_FILE') else None
tool=json.loads(toolfile.read_text()) if toolfile and toolfile.exists() else {}
java=env.get('JAVA_HOME',tool.get('java'));sdk=env.get('ANDROID_HOME',tool.get('sdk'))
if java:env['JAVA_HOME']=java
if sdk:env['ANDROID_HOME']=sdk;env['ANDROID_SDK_ROOT']=sdk
gradle=env.get('PLAYER_GRADLE',tool.get('gradle'))
command=[gradle] if gradle else ([str(workspace/'gradlew.bat')] if os.name=='nt' else ['sh',str(workspace/'gradlew')])
tasks=sys.argv[1:] or [':core:test',':app:assembleDebug',':app:lintDebug']
result=subprocess.run(command+['--no-daemon','--console=plain']+tasks,cwd=workspace,env=env)
if result.returncode==0 and not any(flag in tasks for flag in ('--dry-run','-m')):
    (project/'dist').mkdir(exist_ok=True)
    for variant in ('debug','release'):
        requested={t.rsplit(':',1)[-1] for t in tasks}
        if not requested.intersection({'assemble'+variant.title(),'package'+variant.title(),'assemble','build'}):continue
        directory=workspace/f'app/build/outputs/apk/{variant}'
        metadata=json.loads((directory/'output-metadata.json').read_text())
        if len(metadata['elements'])!=1:raise SystemExit('Expected one universal APK')
        element=metadata['elements'][0]
        version=validate({key:element[key] for key in ('versionName','versionCode')})
        apk=(directory/element['outputFile']).resolve()
        if apk.parent!=directory.resolve() or apk.suffix!='.apk':raise SystemExit('Invalid APK output path')
        target=project/f'dist/yingxi-{version["versionName"]}-{version["versionCode"]}-{variant}.apk'
        shutil.copyfile(apk,target)
        target.with_suffix('.apk.sha256').write_text(hashlib.sha256(target.read_bytes()).hexdigest()+'  '+target.name+'\n')
        print('Artifact:',target.name)
        for name in ['third-party-notices.txt','dependencies.json']:
            source=workspace/'app/build/generated/notices'/name
            if source.exists():shutil.copyfile(source,project/'dist'/name)
    reports=project/'dist/reports';reports.mkdir(parents=True,exist_ok=True)
    for name,path in [('core-tests',workspace/'core/build/reports/tests/test'),('android-lint',workspace/'app/build/reports')]:
        if path.exists():shutil.copytree(path,reports/name,dirs_exist_ok=True,copy_function=shutil.copyfile,ignore=shutil.ignore_patterns('._*','.DS_Store'))
print('Build workspace:',workspace)
raise SystemExit(result.returncode)
