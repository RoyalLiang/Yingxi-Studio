import unittest
from versioning import validate

class VersioningTest(unittest.TestCase):
    def test_alpha_beta_rc_stable_and_custom_prerelease_are_configurable(self):
        for name in ("0.2.0-alpha.1", "1.0.0-beta.2", "1.0.0-rc.1", "1.0.0", "2.4.1-preview.2026+build.7"):
            result = validate({"versionName": name, "versionCode": 4})
            self.assertEqual("v" + name, result["tag"])
            self.assertEqual("-" in name.split("+")[0], result["prerelease"])

    def test_rejects_ambiguous_versions_codes_and_typo_keys(self):
        for name in ("0.02.0", "1.0", "1.0.0-alpha.01", "v1.0.0", "1.0.0\n", "1.0.0-", "1.0.0+", "1.0.0/alpha"):
            with self.assertRaises(ValueError): validate({"versionName": name, "versionCode": 3})
        for code in (True, "3", 0, -1, 2_100_000_001):
            with self.assertRaises(ValueError): validate({"versionName": "0.2.0-alpha.1", "versionCode": code})
        with self.assertRaises(ValueError): validate({"versionName": "1.0.0", "versionCode": 3, "versoinCode": 4})

if __name__ == "__main__": unittest.main()
