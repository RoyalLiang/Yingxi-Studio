import copy
import json
from pathlib import Path
import unittest

import test_worldbook
from world_statistics import summarize, repository_snapshot, render_statistics


class WorldCorpusValidationTests(unittest.TestCase):
    def setUp(self):
        self.fixture = test_worldbook.WorldbookTests()
        self.fixture.setUp()
        self.atlas = self.fixture.atlas

    def check(self, atlas):
        return self.fixture.check(atlas)

    def test_rejects_untranslated_diary_and_unknown_surface_author(self):
        self.atlas['dailyLines'][0]['text']['en'] = ''
        with self.assertRaisesRegex(ValueError, 'Chinese.*English'):
            self.check(self.atlas)
        self.setUp()
        self.atlas['surfaceNotes'][0]['author'] = 'unregistered'
        with self.assertRaisesRegex(ValueError, 'corpus author'):
            self.check(self.atlas)

    def test_rejects_untranslated_map_alias(self):
        next(node for node in self.atlas["nodes"] if node["id"] == "beacon")["mapLabel"]["en"] = ""
        with self.assertRaisesRegex(ValueError, "Chinese.*English"):
            self.check(self.atlas)

    def test_rejects_duplicate_diary_and_invalid_canon_status(self):
        self.atlas['dailyLines'].append(copy.deepcopy(self.atlas['dailyLines'][0]))
        with self.assertRaisesRegex(ValueError, 'corpus identity'):
            self.check(self.atlas)
        self.setUp()
        self.atlas['lexicon'][0]['canonStatus'] = 'maybe'
        with self.assertRaisesRegex(ValueError, 'canon adoption'):
            self.check(self.atlas)


class WorldStatisticsTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parents[1]
        self.world = json.loads((self.root / 'app/src/main/res/raw/world_atlas.json').read_text())

    def test_canon_adoption_does_not_mark_a_route_surveyed(self):
        counts = summarize(self.world)
        self.assertEqual(40, counts['adoptedTerms'])
        self.assertEqual(4, counts['unverifiedPlaces'])
        self.assertEqual(1, counts['surveyedRoutes'])
        self.assertFalse(next(p for p in self.world['nodes'] if p['id'] == 'ideal')['known'])

    def test_new_quote_updates_total_and_its_author_without_changing_letters(self):
        before = summarize(self.world)
        self.world['characters'][0]['quotes'].append({'zh': '测试用新增语录', 'en': 'A test quotation'})
        after = summarize(self.world)
        self.assertEqual(before['quotes'] + 1, after['quotes'])
        self.assertEqual(before['quotesByPerson']['axi'] + 1, after['quotesByPerson']['axi'])
        self.assertEqual(before['letters'], after['letters'])

    def test_generated_statistics_are_bound_to_current_data_and_version(self):
        snapshot = repository_snapshot(self.root, self.world)
        rendered = render_statistics(snapshot)
        self.assertIn(str(snapshot['counts']['terms']), rendered)
        target = self.root / 'docs/world-statistics.json'
        if target.is_file():
            self.assertEqual(snapshot, json.loads(target.read_text()))
            self.assertEqual(rendered, (self.root / 'docs/world-statistics.md').read_text())


if __name__ == '__main__':
    unittest.main()
