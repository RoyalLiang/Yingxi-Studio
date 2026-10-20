import tempfile,unittest,zipfile,stat
from pathlib import Path
from build_ios import pack_application
class IOSPackagingTests(unittest.TestCase):
    def test_payload_keeps_executable_content_and_permissions(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root);app=root/'Player.app';app.mkdir();exe=app/'Player';exe.write_bytes(b'compiled-fixture');exe.chmod(0o755)
            archive=root/'test.ipa';pack_application(app,archive,'Payload/Player.app')
            with zipfile.ZipFile(archive) as z:
                info=z.getinfo('Payload/Player.app/Player');self.assertEqual(z.read(info),b'compiled-fixture');self.assertTrue(info.external_attr>>16 & stat.S_IXUSR)
    def test_metadata_is_rejected_without_deleting_input(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root);app=root/'Player.app';app.mkdir();sidecar=app/'._Player';sidecar.write_bytes(bytes.fromhex('00051607')+b'metadata')
            with self.assertRaises(ValueError):pack_application(app,root/'test.ipa','Payload/Player.app')
            self.assertTrue(sidecar.exists())
    def test_symlinks_cannot_copy_external_files(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root);app=root/'Player.app';app.mkdir();outside=root/'external';outside.write_text('outside');(app/'link').symlink_to(outside)
            with self.assertRaises(ValueError):pack_application(app,root/'test.ipa','Payload/Player.app')
