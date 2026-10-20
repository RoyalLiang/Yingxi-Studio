import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from release_tools import prepare_source_archive, verify_source_archive, verify_assets, sha
from versioning import validate


class SourceArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)/'repository'; self.root.mkdir()
        self.output = Path(self.temp.name)/'output'; self.output.mkdir()
        self.version = validate({'versionName':'1.2.3-alpha.1','versionCode':9})
        self.git('init','-q','-b','main')
        self.git('config','user.name','Archive Fixture')
        self.git('config','user.email','archive@example.invalid')
        (self.root/'version.json').write_text(json.dumps(self.version))
        (self.root/'README.md').write_text('Original source\n')
        self.git('add','version.json','README.md');self.git('commit','-qm','Source fixture')
        self.commit = self.git('rev-parse','HEAD').strip()

    def git(self,*args):
        return subprocess.check_output(['git','-C',str(self.root),*args],text=True)

    def test_archive_uses_immutable_commit_not_dirty_or_untracked_files(self):
        (self.root/'README.md').write_text('Uncommitted edit\n')
        (self.root/'private-local.json').write_text('Synthetic local configuration')
        (self.root/'._README.md').write_bytes(bytes.fromhex('00051607'))
        archive = prepare_source_archive(self.root,self.version,self.commit,self.output)
        with zipfile.ZipFile(self.output/archive['sourceFileName']) as z:
            self.assertEqual(z.comment.decode(),self.commit)
            self.assertEqual(z.read('yingxi-1.2.3-alpha.1/README.md'),b'Original source\n')
            self.assertFalse(any('private-local' in n or '/._' in n for n in z.namelist()))
        with self.assertRaises(ValueError): verify_source_archive(self.output/archive['sourceFileName'],self.version,'f'*40)

    def test_tracked_metadata_and_nonpublishable_files_are_rejected(self):
        (self.root/'._README.md').write_bytes(bytes.fromhex('00051607'))
        self.git('add','-f','._README.md');self.git('commit','-qm','Unsafe synthetic metadata fixture')
        with self.assertRaisesRegex(ValueError,'Unsafe source archive'):
            prepare_source_archive(self.root,self.version,self.git('rev-parse','HEAD').strip(),self.output)

    def test_audit_checksums_cover_the_source_archive(self):
        archive = prepare_source_archive(self.root,self.version,self.commit,self.output)
        for name in ['sample.apk','third-party-notices.txt','dependencies.json']:(self.output/name).write_text('Synthetic audit fixture')
        manifest={'formatVersion':2,**self.version,'sourceCommit':self.commit,'fileName':'sample.apk','sha256':sha(self.output/'sample.apk'),**archive}
        (self.output/'yingxi-release.json').write_text(json.dumps(manifest))
        (self.output/'SHA256SUMS').write_text(''.join(sha(p)+'  '+p.name+'\n' for p in sorted(self.output.iterdir())))
        verify_assets(self.output)
        with (self.output/archive['sourceFileName']).open('ab') as stream:stream.write(b'changed')
        with self.assertRaises(ValueError):verify_assets(self.output)


if __name__ == '__main__': unittest.main()
