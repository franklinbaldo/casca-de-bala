"""Fast Python tests for Gradle profiling log parsing (no Gradle needed)."""
import sys
from pathlib import Path
import unittest
import tempfile
from unittest.mock import patch
from types import SimpleNamespace

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

    def test_profiler_limits_gradle_junit_to_casca_classes(self):
        from profile_casca_java import run_task
        with tempfile.TemporaryDirectory() as folder:
            with patch('profile_casca_java.subprocess.run', return_value=SimpleNamespace(stdout='', stderr='', returncode=0)) as run:
                run_task('testCascaDebugUnitTest', Path(folder), 1)
                self.assertEqual(run.call_args.kwargs['env']['GB_TEST_FILTER'], 'org.workspace.casca.*')
                run_task('compileCascaDebugJavaWithJavac', Path(folder), 2)
                self.assertNotIn('GB_TEST_FILTER', run.call_args.kwargs['env'])


if __name__ == '__main__':
    unittest.main()
