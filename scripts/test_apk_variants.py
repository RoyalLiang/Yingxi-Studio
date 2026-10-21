import json,tempfile,unittest,zipfile
from pathlib import Path
from apk_variants import ABIS,apk_name,outputs,verify_abi
class ApkVariantsTest(unittest.TestCase):
    def test_complete_architecture_outputs_have_no_universal_package(self):
        version={"versionName":"1.2.3","versionCode":42}
        with tempfile.TemporaryDirectory() as folder:
            directory=Path(folder);elements=[]
            for abi in ABIS:
                path=directory/(abi+".apk")
                with zipfile.ZipFile(path,"w") as z:z.writestr("lib/"+abi+"/libdecoder.so",b"native-code")
                elements.append({"filters":[{"filterType":"ABI","value":abi}],"outputFile":path.name,**version})
            self.assertEqual(set(ABIS),set(outputs({"elements":elements},directory,version)))
            self.assertEqual("yingxi-1.2.3-42.apk",apk_name(version,"arm64-v8a"))
            self.assertEqual("yingxi-1.2.3-42-x86_64.apk",apk_name(version,"x86_64"))
            for invalid in (elements[:-1],elements+elements[:1],[{**elements[0],"filters":[]}]):
                with self.assertRaises(ValueError):outputs({"elements":invalid},directory,version)
            with zipfile.ZipFile(directory/"fat.apk","w") as z:
                z.writestr("lib/arm64-v8a/libdecoder.so",b"a");z.writestr("lib/x86/libdecoder.so",b"x")
            with self.assertRaises(ValueError):verify_abi(directory/"fat.apk","arm64-v8a")

    def test_release_manifest_checks_every_architecture_and_mapping(self):
        from unittest.mock import patch
        from release_tools import verify_assets,sha
        version={"versionName":"1.2.3","versionCode":42}
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);apks=[]
            for abi in ABIS:
                name=apk_name(version,abi);path=root/name
                with zipfile.ZipFile(path,"w") as z:z.writestr("lib/"+abi+"/libdecoder.so",b"decoder")
                apks.append({"abi":abi,"fileName":name,"sizeBytes":path.stat().st_size,"sha256":sha(path)})
            for name in ("third-party-notices.txt","dependencies.json","mapping.txt","source-code-1.2.3.zip"):(root/name).write_bytes(b"synthetic")
            manifest={"formatVersion":3,**version,"apks":apks,"sourceFileName":"source-code-1.2.3.zip","sourceSha256":sha(root/"source-code-1.2.3.zip"),"sourceSizeBytes":9,"sourceCommit":"a"*40,"mappingSha256":sha(root/"mapping.txt")}
            def save():
                (root/"yingxi-release.json").write_text(json.dumps(manifest))
                (root/"SHA256SUMS").write_text("".join(sha(p)+"  "+p.name+"\n" for p in sorted(root.iterdir()) if p.name!="SHA256SUMS"))
            save()
            with patch("release_tools.verify_source_archive"):
                self.assertEqual(4,len(verify_assets(root)["apks"]))
                bad=root/apks[1]["fileName"]
                with zipfile.ZipFile(bad,"w") as z:z.writestr("lib/arm64-v8a/libdecoder.so",b"decoder")
                apks[1].update(sizeBytes=bad.stat().st_size,sha256=sha(bad));save()
                with self.assertRaises(ValueError):verify_assets(root)
