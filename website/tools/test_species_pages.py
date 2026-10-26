import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('species_page', Path(__file__).with_name('sync-species.py'))
page = importlib.util.module_from_spec(spec)
spec.loader.exec_module(page)


class SpeciesPageTests(unittest.TestCase):
    def setUp(self):
        self.item = dict(id='reed', name='<芦>', english='Reed & tide', habitat='水边', shape='<完整形态>', feeding='食性', impact='生态关系', limits='习性')

    def test_content_is_escaped_without_losing_readable_names(self):
        output = page.render(dict(description='风与潮', species=[self.item]), 'https://example.com')
        self.assertIn('&lt;完整形态&gt;', output)
        self.assertIn('Reed &amp; tide', output)
        self.assertNotIn('<芦>', output)
        self.assertIn('id="reed"', output)

    def test_duplicate_or_path_identifiers_are_rejected(self):
        for items in [[self.item, self.item], [dict(self.item, id='../escape')]]:
            with self.assertRaises(ValueError):
                page.render(dict(description='风与潮', species=items), 'https://example.com')


if __name__ == '__main__':
    unittest.main()
