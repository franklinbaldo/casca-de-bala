"""Fast Python tests for Gradle profiling log parsing (no Gradle needed)."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from profile_casca_java import TASK_RE  # noqa: E402


class GradleProfileTest(unittest.TestCase):
    def test_task_outcomes(self):
        sample = '> Task :app:compileCascaDebugJavaWithJavac\n' \
                 '> Task :app:processCascaDebugResources UP-TO-DATE\n' \
                 '> Task :app:generateCascaDebugAssets FROM-CACHE\n' \
                 '> Task :app:testCascaDebugUnitTest FAILED\n'
        self.assertEqual(TASK_RE.findall(sample), [
            (':app:compileCascaDebugJavaWithJavac', ''),
            (':app:processCascaDebugResources', 'UP-TO-DATE'),
            (':app:generateCascaDebugAssets', 'FROM-CACHE'),
            (':app:testCascaDebugUnitTest', 'FAILED'),
        ])


if __name__ == '__main__':
    unittest.main()
