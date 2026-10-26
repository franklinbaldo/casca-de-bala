import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'ci'))
import version  # noqa: E402


class VersionTest(unittest.TestCase):
    def test_repeat_and_first_parent_growth(self):
        with tempfile.TemporaryDirectory() as d:
            def git(*args):
                return subprocess.check_output(['git', '-C', d, *args], text=True).strip()
            git('init', '-q', '-b', 'main')
            git('config', 'user.email', 'fixture@example.invalid')
            git('config', 'user.name', 'Synthetic fixture')
            git('commit', '-q', '--allow-empty', '-m', 'one')
            previous = os.getcwd()
            try:
                os.chdir(d)
                a = version.version()
                self.assertEqual(a, version.version())
                git('commit', '-q', '--allow-empty', '-m', 'two')
                b = version.version()
                self.assertEqual(b['versionCode'], a['versionCode'] + 1)
                self.assertNotEqual(a['tag'], b['tag'])
                git('checkout', '-q', '--detach', a['commit'])
                self.assertEqual(a, version.version())
            finally:
                os.chdir(previous)

    def test_shallow_refused(self):
        original = version.git
        try:
            version.git = lambda *args: 'a' * 40 if args[-1] == 'HEAD' else 'true'
            with self.assertRaisesRegex(ValueError, 'Full history'):
                version.version()
        finally:
            version.git = original


class DraftTest(unittest.TestCase):
    def run_case(self, event='push', ref='refs/heads/main', mode='missing'):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            (p / 'version.json').write_text(json.dumps({'commit': 'a' * 40,
                'versionCode': 1001, 'tag': 'ci-v1001-' + 'a' * 12,
                'versionName': 'ci.1001.' + 'a' * 12}))
            fake = p / 'gh'
            fake.write_text('#!/usr/bin/env python3\nimport sys,os,json\n'
                'with open(os.environ["CALLS"],"a") as f:f.write(json.dumps(sys.argv[1:])+"\\n")\n'
                'if sys.argv[2]=="view":\n'
                ' m=os.environ["MODE"]\n'
                ' if m=="existing":print(json.dumps({"isDraft":False,"targetCommitish":"a"*40}));sys.exit(0)\n'
                ' print("release not found" if m=="missing" else "network unavailable",file=sys.stderr);sys.exit(1)\n')
            fake.chmod(0o700)
            env = dict(os.environ, PATH=d + os.pathsep + os.environ['PATH'],
                GITHUB_EVENT_NAME=event, GITHUB_REF=ref, GITHUB_SHA='a' * 40,
                GITHUB_REPOSITORY='franklinbaldo/casca-de-bala', MODE=mode,
                CALLS=str(p / 'calls'))
            env.pop('GH_TOKEN', None)
            r = subprocess.run([sys.executable, str(ROOT / 'ci/prepare-draft.py'), d],
                env=env, capture_output=True, text=True)
            calls = [json.loads(l) for l in (p / 'calls').read_text().splitlines()] if (p / 'calls').exists() else []
            return r, calls

    def test_missing_creates_only_draft(self):
        r, calls = self.run_case()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(len(calls), 2)
        self.assertIn('--draft', calls[1])
        self.assertIn('--prerelease', calls[1])
        self.assertNotIn('edit', calls[1])

    def test_existing_release_untouched(self):
        r, calls = self.run_case(mode='existing')
        self.assertEqual(r.returncode, 0)
        self.assertEqual(len(calls), 1)

    def test_network_failure_does_not_create(self):
        r, calls = self.run_case(mode='network')
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(len(calls), 1)

    def test_pr_and_other_branch_cannot_create(self):
        for event, ref in [('pull_request', 'refs/pull/1/merge'), ('push', 'refs/heads/feature')]:
            r, calls = self.run_case(event, ref)
            self.assertNotEqual(r.returncode, 0)
            self.assertEqual(calls, [])


class AndroidSetupTest(unittest.TestCase):
    def run_case(self, licence=True, manager=True, fail=False):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / 'SDK with spaces'
            binary = root / 'cmdline-tools/latest/bin/sdkmanager'
            binary.parent.mkdir(parents=True)
            if manager:
                binary.write_text('#!' + sys.executable + '\n'
                    'import os,sys,json\n'
                    'assert sys.stdin.read()==""\n'
                    'open(os.environ["CALLS"],"w").write(json.dumps(sys.argv[1:]))\n'
                    'sys.exit(9 if os.environ["FAIL"]=="1" else 0)\n')
                binary.chmod(0o700)
            if licence:
                (root / 'licenses').mkdir()
                (root / 'licenses/android-sdk-license').write_text('synthetic fixture')
            calls = Path(d) / 'calls'
            env = dict(os.environ, ANDROID_HOME=str(root), CALLS=str(calls),
                       FAIL='1' if fail else '0')
            r = subprocess.run(['/bin/sh', str(ROOT / 'ci/setup-android.sh')],
                               env=env, capture_output=True, text=True)
            return r, json.loads(calls.read_text()) if calls.exists() else None

    def test_absolute_manager_with_spaces_and_closed_stdin(self):
        r, calls = self.run_case()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(calls[1:], ['--install', 'platforms;android-35',
                                    'build-tools;35.0.0', 'ndk;27.0.12077973'])

    def test_missing_prerequisite_never_accepts_licence(self):
        for licence, manager in [(False, True), (True, False)]:
            r, calls = self.run_case(licence=licence, manager=manager)
            self.assertEqual(r.returncode, 2)
            self.assertIsNone(calls)

    def test_manager_failure_propagates(self):
        r, _ = self.run_case(fail=True)
        self.assertEqual(r.returncode, 9)


if __name__ == '__main__':
    unittest.main()
