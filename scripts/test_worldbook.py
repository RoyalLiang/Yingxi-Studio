import copy
import json
import tempfile
import unittest
from pathlib import Path
from worldbook import validate, render_glossary

class WorldbookTests(unittest.TestCase):
    def setUp(self):
        self.atlas=json.loads((Path(__file__).resolve().parents[1]/'app/src/main/res/raw/world_atlas.json').read_text())
    def check(self,atlas):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);file=root/'app/src/main/res/raw/world_atlas.json';file.parent.mkdir(parents=True)
            file.write_text(json.dumps(atlas));return validate(root)
    def test_real_atlas_is_bilingual_and_connected(self):
        world=self.check(self.atlas)
        self.assertGreaterEqual(len(world['characters']),3);self.assertGreaterEqual(len(world['nodes']),5)
    def test_rejects_dangling_relationship(self):
        self.atlas['edges'][0]['to']='missing'
        with self.assertRaisesRegex(ValueError,'endpoint'):self.check(self.atlas)
    def test_rejects_incomplete_quote_translation(self):
        del self.atlas['characters'][0]['quotes'][0]['en']
        with self.assertRaisesRegex(ValueError,'Chinese and English'):self.check(self.atlas)

    def test_rejects_proposal_disguised_as_established_status(self):
        self.atlas['lexicon'][0]['status']='proposed'
        with self.assertRaisesRegex(ValueError,'status'):self.check(self.atlas)

    def test_rejects_missing_proposal_translation(self):
        entry=next(t for t in self.atlas['lexicon'] if 'proposal' in t)
        del entry['proposal']['en']
        with self.assertRaisesRegex(ValueError,'Chinese and English'):self.check(self.atlas)

    def test_rejects_rumor_promoted_without_map_change(self):
        entry=next(t for t in self.atlas['lexicon'] if t['status']=='rumor')
        entry['status']='confirmed'
        with self.assertRaisesRegex(ValueError,'map knowledge'):self.check(self.atlas)

    def test_rejects_duplicate_term_identity(self):
        self.atlas['lexicon'].append(copy.deepcopy(self.atlas['lexicon'][0]))
        with self.assertRaisesRegex(ValueError,'identity'):self.check(self.atlas)

    def test_published_glossary_matches_the_app_data(self):
        source=Path(__file__).resolve().parents[1]
        self.assertEqual(render_glossary(self.atlas),(source/'docs/world-glossary.md').read_text())

    def test_release_rejects_an_unknown_speaker(self):
        from release_notes import load_notes
        from versioning import load
        source=Path(__file__).resolve().parents[1]
        version=load(source)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            atlas=root/'app/src/main/res/raw/world_atlas.json';atlas.parent.mkdir(parents=True);atlas.write_text(json.dumps(self.atlas))
            notes=root/'releases'/f'{version["tag"]}.md';notes.parent.mkdir()
            notes.write_text(f'# 映汐播放器 {version["versionName"]} / Yingxi {version["versionName"]}\n\n## 中文\n【拾荒者信笺】\n修好柜台，留下新台词。\n> 灯还亮着。——陌生人\n\n## English\n[Scavenger letter]\nThe counter is repaired and a letter arrives.\n')
            with self.assertRaisesRegex(ValueError,'known atlas character'):load_notes(root,version)
