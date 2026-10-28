#!/usr/bin/env python3
"""Headless regressions for documentation copied into all desktop packages."""
import importlib.util
import re
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('package_windows', Path(__file__).with_name('package-windows.py'))
PACKAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGE)


class ReleaseHistoryTests(unittest.TestCase):
    def test_changelog_release_links_resolve_in_packaged_history(self):
        current = re.search(r'^version = "([^"]+)"', (PACKAGE.ROOT / 'Cargo.toml').read_text(encoding='utf-8'), re.M)[1]
        links = re.findall(r'\]\((docs/releases/[^)#]+\.md)(?:#[^)]*)?\)', (PACKAGE.ROOT / 'CHANGELOG.md').read_text(encoding='utf-8'))
        self.assertTrue(links)
        with tempfile.TemporaryDirectory(prefix='openrad-history-test-') as temporary:
            destination = Path(temporary)
            PACKAGE.copy_release_history(destination)
            # Each packager separately stages its current notes and inventory.
            for name in (f'{current}.md', f'{current}-changes.md'):
                target = destination / 'docs/releases' / name
                target.write_bytes((PACKAGE.ROOT / 'docs/releases' / name).read_bytes())
            for link in links:
                with self.subTest(link=link):
                    self.assertTrue((destination / link).is_file(), f'Packaged CHANGELOG link is missing: {link}')

    def test_historical_source_inventory_links_keep_tag_reference(self):
        with tempfile.TemporaryDirectory(prefix='openrad-history-test-') as temporary:
            destination = Path(temporary)
            PACKAGE.copy_release_history(destination)
            inventory = (destination / 'docs/releases/1.0.0-changes.md').read_text(encoding='utf-8')
            self.assertNotIn('](../../', inventory)
            self.assertIn('https://github.com/gringoestrangeiro/openrad/blob/v1.0.0/', inventory)


if __name__ == '__main__':
    unittest.main(verbosity=2)
