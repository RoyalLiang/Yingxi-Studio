import copy
import json
import unittest
from pathlib import Path

from worldbook import render_glossary
from world_statistics import summarize


class SpeciesProposalTests(unittest.TestCase):
    def setUp(self):
        root = Path(__file__).resolve().parents[1]
        self.world = json.loads((root / 'app/src/main/res/raw/world_atlas.json').read_text())

    def test_author_proposal_does_not_create_a_station_report(self):
        before = summarize(self.world)
        item = copy.deepcopy(self.world['lexicon'][0])
        item.update(id='test_author_species', canonStatus='proposal', status='rumor')
        self.world['lexicon'].append(item)
        after = summarize(self.world)
        self.assertEqual(before['terms'] + 1, after['terms'])
        for field in ['adoptedTerms', 'stationHearsayTerms', 'surveyedPlaces', 'surveyedRoutes']:
            self.assertEqual(before[field], after[field], field)

    def test_glossary_distinguishes_proposals_from_canon_hearsay(self):
        proposal = next(term for term in self.world['lexicon'] if term['canonStatus'] == 'proposal')
        archive = next(term for term in self.world['lexicon'] if term['id'] == 'archive')
        lines = render_glossary(self.world).splitlines()
        proposed_row = next(line for line in lines if line.startswith('| ' + proposal['id'] + ' |'))
        archive_row = next(line for line in lines if line.startswith('| ' + archive['id'] + ' |'))
        self.assertIn('演进提案 | 未核作者提案', proposed_row)
        self.assertNotIn('站内传闻', proposed_row)
        self.assertIn('正式设定 | 站内传闻', archive_row)
        self.assertEqual('rumor', archive['status'])


if __name__ == '__main__':
    unittest.main()
