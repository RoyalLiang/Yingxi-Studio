import json
import unittest
from pathlib import Path


# These released identities must not be rebound to the older local draft.
PUBLISHED_DIARIES = {'diary_019': {'author': 'chideng', 'text': {'zh': '我把桌边的旧纸签裁齐了。片名长一点没关系，别把旁边那一匣挤下去。', 'en': 'I trimmed the old labels along the desk. A long title is fine; just leave room for the case beside it.'}}, 'diary_020': {'author': 'mitu', 'text': {'zh': '我把来处写在片匣角上。写下一个地名，不等于那条路已经走通；地图上的空白，仍得留着。', 'en': 'I wrote the place of origin on the case’s corner. Naming a place does not mean the road is clear; the blank on the map must stay.'}}, 'diary_021': {'author': 'chideng', 'text': {'zh': '夜里我把灯罩压低一格，检修纸看得清，旁人的眼睛也能歇一会儿。走到哪一格，就记哪一格。', 'en': 'At night I lower the lampshade one notch. The repair sheet stays legible and passing eyes can rest. I mark only the steps actually taken.'}}}


class AlphaWorldCarryoverTests(unittest.TestCase):
    def setUp(self):
        self.world = json.loads((Path(__file__).resolve().parents[1] / 'app/src/main/res/raw/world_atlas.json').read_text())

    def test_released_diary_identities_keep_their_authors_and_words(self):
        entries = {item['id']: item for item in self.world['dailyLines']}
        for identity, expected in PUBLISHED_DIARIES.items():
            self.assertEqual(expected, {key: entries[identity][key] for key in ['author', 'text']})

    def test_carryover_corpus_has_distinct_ids_without_adopting_new_places_or_chapters(self):
        diary_ids = {item['id'] for item in self.world['dailyLines']}
        carried = {'diary_station_logbook_cloth_20261026', 'diary_station_chair_cloth_20261026', 'diary_station_trade_clips_20261026',
                   'diary_salt_blank_tally_20261026', 'diary_station_index_cord_20261026', 'diary_station_frequency_log_20261026'}
        self.assertTrue(carried <= diary_ids)
        quote_ids = {quote['id'] for person in self.world['characters'] for quote in person['quotes']}
        self.assertTrue({'quote_axi_alpha_carryover_20261026', 'quote_chideng_alpha_carryover_20261026', 'quote_mitu_alpha_carryover_20261026'} <= quote_ids)
        self.assertFalse(diary_ids & quote_ids)
        terms = {item['id']: item for item in self.world['lexicon']}
        for identity in ['wayfinder_tally', 'canister_ledger_cord', 'frequency_logbook']:
            self.assertEqual('confirmed', terms[identity]['canonStatus'])
        self.assertEqual(['chapter_01', 'chapter_02'], [chapter['id'] for chapter in self.world['chapters']])
        self.assertEqual(2, sum(place['known'] for place in self.world['nodes']))
        self.assertEqual(1, sum(edge['known'] for edge in self.world['edges']))


if __name__ == '__main__':
    unittest.main()
