import json
import os
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fdroid_repo  # noqa: E402


class ReleaseSelectionTest(unittest.TestCase):
    def test_newest_published_ci_tags_only(self):
        listing = [{'tagName': f'ci-v{n}-{"a" * 12}', 'isDraft': False} for n in range(1001, 1009)]
        listing += [{'tagName': 'ci-v2000-bbbbbbbbbbbb', 'isDraft': True},
                    {'tagName': 'v0.5-lab', 'isDraft': False}]
        original = fdroid_repo.run
        try:
            fdroid_repo.run = lambda *args, **kw: json.dumps(listing)
            tags = fdroid_repo.releases()
        finally:
            fdroid_repo.run = original
        self.assertEqual(tags, [f'ci-v{n}-{"a" * 12}' for n in range(1008, 1003, -1)])

    def test_refuses_outside_main_and_without_key(self):
        env = {'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/heads/main',
               'GITHUB_REPOSITORY': 'franklinbaldo/casca-de-bala'}
        saved = dict(os.environ)
        try:
            os.environ.update(env | {'GITHUB_REF': 'refs/pull/1/merge'})
            with self.assertRaisesRegex(ValueError, 'canonical main'):
                fdroid_repo.build(Path('/nonexistent'))
            os.environ.update(env)
            os.environ.pop('CASCA_FDROID_KEYSTORE_B64', None)
            with self.assertRaisesRegex(ValueError, 'Missing F-Droid'):
                fdroid_repo.build(Path('/nonexistent'))
        finally:
            os.environ.clear()
            os.environ.update(saved)


if __name__ == '__main__':
    unittest.main()
