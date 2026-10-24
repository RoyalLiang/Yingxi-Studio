import unittest
from repository_guard import inspect

class RepositoryGuardTest(unittest.TestCase):
    def test_website_source_is_bounded_and_still_checks_content(self):
        for name in ("website/public/index.html", "website/public/_headers", "website/public/assets/sans.woff2", "website/public/assets/playback.webm", "website/tools/check.mjs", "website/wrangler.jsonc"):
            self.assertFalse(inspect(name, b"public content"), name)
        for name in ("website/.env", "website/public/private.apk", "website/node_modules/index.js", "website/public/._icon.webp", "website/public/build/index.html"):
            self.assertTrue(inspect(name, b"local data"), name)
        self.assertTrue(inspect("website/public/index.html", b"ghp_" + b"x" * 36))

    def test_local_data_and_metadata_cannot_be_staged_by_force_add(self):
        for name in (".agent-runtime/state-lite.json", "WORKLOG.md", "dist/app.apk", "app/src/androidTest/assets/private.mp4", "assets/brand/._yingxi-icon.png", "release.jks"):
            self.assertTrue(inspect(name, b"local data"), name)
        self.assertTrue(inspect("scripts/example.py", bytes.fromhex("00051607")))

    def test_detects_secret_shapes_without_printing_values(self):
        for data in (b"ghp_" + b"x"*36, b"-----BEGIN " + b"PRIVATE KEY-----", b"sftp://" + b"user:credential@server", b"192.168." + b"42.19", b"/Use" + b"rs/someone/file"):
            self.assertTrue(inspect("scripts/example.py", data))
        self.assertFalse(inspect("scripts/example.py", b"os.environ['YINGXI_KEYSTORE_PASSWORD']"))
        self.assertFalse(inspect("app/src/androidTest/java/Test.kt", b'val host="10.0.2.2"'))

if __name__ == "__main__": unittest.main()
