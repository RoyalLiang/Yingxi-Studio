import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from release_assets import keep_asset, managed_tag, apply, backup
from release_tools import historical_apk, previous_from_apk


class AssetPolicyTests(unittest.TestCase):
    def test_binary_backup_roundtrip_precedes_only_reviewed_deletion(self):
        data = b'\x00\xffaudit\x80\n'
        asset = {'id': 2, 'name': 'audit.json', 'size': len(data), 'digest': 'sha256:' + hashlib.sha256(data).hexdigest()}
        releases = [[{'id': 1, 'tag_name': 'v1.2.3', 'draft': False,
                      'assets': [asset, {'id': 3, 'name': 'app.apk'}]}]]
        def download(command, **kwargs):
            self.assertEqual(command[-1], 'Accept: application/octet-stream')
            kwargs['stdout'].write(data)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with patch('release_assets.api', return_value=releases), patch('release_assets.subprocess.run', side_effect=download) as command:
                backup('owner/repo', root)
            self.assertEqual(command.call_count, 1)
            self.assertEqual((root/'1-2.bin').read_bytes(), data)
            with patch('release_assets.subprocess.run') as command:
                command.return_value.returncode = 0
                command.return_value.stdout = json.dumps(asset)
                apply('owner/repo', root)
            self.assertEqual(command.call_count, 2)
            self.assertIn('DELETE', command.call_args.args[0])

    def test_historical_apk_verifies_source_package_and_certificate_without_json_asset(self):
        version = {'versionName': '1.2.3', 'versionCode': 8}
        release = {'tag_name': 'v1.2.3', 'assets': [{'name': 'yingxi-1.2.3-8.apk'}]}
        badging = "package: name='io.localplayer.app' versionCode='8' versionName='1.2.3'"
        signer = 'Signer #1 certificate SHA-256 digest: ' + 'a'*64
        with patch.dict('os.environ', {'ANDROID_HOME': '/sdk'}), patch('release_tools.subprocess.run') as command:
            with patch('release_tools.run', side_effect=[json.dumps(version), badging, signer]):
                result = previous_from_apk(Path('/repository'), 'owner/repo', release, Path('/download'))
            self.assertEqual(result['signerSha256'], 'a'*64)
            self.assertEqual(command.call_count, 2)
            self.assertIn('--is-ancestor', command.call_args_list[0].args[0])
            self.assertIn('yingxi-1.2.3-8.apk', command.call_args_list[1].args[0])
            for invalid in [badging.replace("'8'", "'9'"), badging+'\napplication-debuggable']:
                with patch('release_tools.run', side_effect=[json.dumps(version), invalid]) as query:
                    with self.assertRaisesRegex(ValueError, 'does not match'):
                        previous_from_apk(Path('/repository'), 'owner/repo', release, Path('/download'))
                    self.assertEqual(query.call_count, 2)
            with patch('release_tools.run', side_effect=[json.dumps(version), badging, 'no certificate']):
                with self.assertRaisesRegex(ValueError, 'verified signer'):
                    previous_from_apk(Path('/repository'), 'owner/repo', release, Path('/download'))

    def test_release_tags_include_semver_build_metadata(self):
        for tag in ['v1.2.3','v1.2.3+build.1','ios-v0.1.0-alpha.2+build.1']:
            self.assertTrue(managed_tag(tag),tag)
        for tag in ['v01.2.3','other-v1.2.3','v1.2.3-']:
            self.assertFalse(managed_tag(tag),tag)
    def test_keeps_installers_and_source_archives_only(self):
        for name in ['yingxi-0.2.0-alpha.8-10.apk','yingxi-ios-0.1.0-alpha.2-unsigned.ipa','yingxi-ios-0.1.0-alpha.2-simulator-arm64.zip','source-code.zip','source.tar.gz']:
            self.assertTrue(keep_asset(name),name)
        for name in ['yingxi-release.json','SHA256SUMS','dependencies.json','third-party-notices.txt','ios-home.png','ios-validation.json','audit.zip','../app.apk']:
            self.assertFalse(keep_asset(name),name)

    def test_historical_apk_is_bound_to_tagged_source(self):
        version={'tag':'v1.2.3-alpha.1','versionName':'1.2.3-alpha.1','versionCode':8}
        release={'tag_name':version['tag'],'assets':[{'name':'yingxi-1.2.3-alpha.1-8.apk'}]}
        self.assertEqual(historical_apk(version,release),'yingxi-1.2.3-alpha.1-8.apk')
        release['assets'].append(dict(release['assets'][0]))
        with self.assertRaises(ValueError):historical_apk(version,release)
        release['assets']=release['assets'][:1];release['tag_name']='v1.2.3-alpha.2'
        with self.assertRaises(ValueError):historical_apk(version,release)

    def plan(self,root,name='audit.json'):
        data=b'audit backup';(root/'backup.bin').write_bytes(data)
        asset={'id':1,'name':name,'size':len(data),'digest':'sha256:'+hashlib.sha256(data).hexdigest(),'backup':'backup.bin','backupSha256':hashlib.sha256(data).hexdigest(),'tag':'v1.2.3'}
        (root/'cleanup-plan.json').write_text(json.dumps({'repository':'owner/repo','assets':[asset]}))
        return asset

    def test_tampered_backup_or_installer_plan_never_deletes(self):
        for installer in [False,True]:
            with tempfile.TemporaryDirectory() as temp:
                root=Path(temp);self.plan(root,'app.apk' if installer else 'audit.json')
                if not installer:(root/'backup.bin').write_bytes(b'changed')
                with patch('release_assets.subprocess.run') as run:
                    with self.assertRaises(ValueError):apply('owner/repo',root)
                    run.assert_not_called()

    def test_changed_published_asset_is_not_deleted(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);asset=self.plan(root);asset['size']+=1
            with patch('release_assets.subprocess.run') as run:
                run.return_value.returncode=0;run.return_value.stdout=json.dumps(asset)
                with self.assertRaises(ValueError):apply('owner/repo',root)
                self.assertEqual(run.call_count,1)

    def test_only_backed_up_unchanged_attachment_is_deleted(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);asset=self.plan(root)
            with patch('release_assets.subprocess.run') as run:
                run.return_value.returncode=0;run.return_value.stdout=json.dumps(asset)
                apply('owner/repo',root)
                self.assertEqual(run.call_count,2)
                self.assertIn('DELETE',run.call_args.args[0])
            self.assertEqual(json.loads((root/'cleanup-result.json').read_text())[0]['result'],'removed')


if __name__=='__main__':unittest.main()
