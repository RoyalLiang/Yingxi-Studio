import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from prepare_mpv import prepare


class PinnedMpvRuntimeTest(unittest.TestCase):
    def test_only_pinned_libraries_are_packaged_without_colliding_with_nextlib(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);cache=root/'cache';cache.mkdir()
            data=io.BytesIO()
            with zipfile.ZipFile(data,'w') as z:
                z.writestr('lib/arm64-v8a/libmpv.so',b'\x7fELFtest-runtime')
                z.writestr('classes.dex',b'upstream-application-must-not-be-packaged')
            content=data.getvalue();digest=hashlib.sha256(content).hexdigest()
            (cache/(digest+'.apk')).write_bytes(content)
            manifest=root/'manifest.json'
            manifest.write_text(json.dumps({'sha256':digest,'binary':'https://invalid.example/pinned.apk','abis':['arm64-v8a'],'libraries':['libmpv.so']}))
            output=root/'generated';prepare(manifest,cache,output)
            names={str(p.relative_to(output)) for p in output.rglob('*') if p.is_file()}
            self.assertEqual({'jniLibs/arm64-v8a/libyingxi_mpv_mpv.so','assets/mpv-native.json'},names)
            actual=json.loads((output/'assets/mpv-native.json').read_text())
            self.assertEqual(hashlib.sha256(b'\x7fELFtest-runtime').hexdigest(),actual['arm64-v8a']['libmpv.so'])


if __name__=='__main__':
    unittest.main()
