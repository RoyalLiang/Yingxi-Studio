import unittest
from release_tools import check_previous

class ReleaseTest(unittest.TestCase):
    def test_monotonic_code_across_all_channels_and_stable_signer(self):
        old={"versionCode":7,"signerSha256":"same"}
        check_previous({"versionCode":8,"signerSha256":"same"},[old])
        for current in ({"versionCode":7,"signerSha256":"same"},{"versionCode":6,"signerSha256":"same"},{"versionCode":8,"signerSha256":"different"}):
            with self.assertRaises(ValueError):check_previous(current,[old])


class ReleaseAssetTest(unittest.TestCase):
    def test_missing_duplicate_or_changed_checksum_is_rejected(self):
        import json,tempfile
        from pathlib import Path
        from release_tools import sha,verify_assets
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            for name in ('sample.apk','third-party-notices.txt','dependencies.json'):(root/name).write_text('synthetic')
            (root/'yingxi-release.json').write_text(json.dumps({'fileName':'sample.apk','sha256':sha(root/'sample.apk')}))
            complete=''.join(sha(p)+'  '+p.name+'\n' for p in sorted(root.iterdir()))
            (root/'SHA256SUMS').write_text(complete)
            verify_assets(root)
            for invalid in ('',complete+complete,complete.replace('sample.apk','unexpected.apk')):
                (root/'SHA256SUMS').write_text(invalid)
                with self.assertRaises(ValueError):verify_assets(root)
            (root/'SHA256SUMS').write_text(complete)
            (root/'third-party-notices.txt').write_text('changed')
            with self.assertRaises(ValueError):verify_assets(root)

if __name__=="__main__": unittest.main()
