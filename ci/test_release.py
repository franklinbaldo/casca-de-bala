import base64
import json
import io
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from release_contract import CONFIG, digest, inputs
import sign_apk
import publish_release


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.source = self.root / 'source'
        self.source.mkdir()
        self.output = self.root / 'signed'
        self.env = patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'push',
            'GITHUB_REF': 'refs/heads/main', 'GITHUB_REPOSITORY': CONFIG['repository'],
            'GITHUB_SHA': 'a' * 40, 'ANDROID_HOME': str(self.root / 'sdk'),
            'RUNNER_TEMP': str(self.root),
            'CASCA_ANDROID_KEYSTORE_B64': base64.b64encode(b'SYNTHETIC NOT A KEY').decode(),
            'CASCA_ANDROID_KEYSTORE_PASSWORD': 'SYNTHETIC NOT A PASSWORD'}, clear=True)
        self.env.start()
        self.silent = patch('sys.stdout', new=io.StringIO())
        self.silent.start()
        version = {'commit': 'a' * 40, 'versionCode': 1001,
            'versionName': 'ci.1001.' + 'a' * 12, 'tag': 'ci-v1001-' + 'a' * 12}
        (self.source / 'version.json').write_text(json.dumps(version))
        (self.source / 'casca-de-bala-source.tar.gz').write_bytes(b'synthetic source fixture')
        with zipfile.ZipFile(self.source / 'casca-de-bala-arm64-unsigned.apk', 'w') as z:
            z.writestr('classes.dex', b'synthetic bytes, not executable DEX')
        self.manifest(self.source)

    def tearDown(self):
        self.silent.stop()
        self.env.stop()
        self.tmp.cleanup()

    @staticmethod
    def manifest(directory):
        files = sorted(p for p in directory.iterdir() if p.name != 'SHA256SUMS')
        (directory / 'SHA256SUMS').write_text(''.join(f'{digest(p)}  {p.name}\n' for p in files))

    def tool(self, args, **kwargs):
        self.assertNotIn('CASCA_ANDROID_KEYSTORE_PASSWORD', os.environ)
        self.assertNotIn('CASCA_ANDROID_KEYSTORE_B64', os.environ)
        self.assertNotIn('SYNTHETIC NOT A PASSWORD', args)
        if args[1] == 'sign':
            shutil.copyfile(self.source / 'casca-de-bala-arm64-unsigned.apk', args[args.index('--out') + 1])
        out = ('Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: '
               + CONFIG['certificateSha256'] + '\n') if args[1] == 'verify' else ''
        return subprocess.CompletedProcess(args, 0, out, '')

    def signed_fixture(self):
        with patch.object(sign_apk, 'verify'), patch.object(sign_apk.subprocess, 'run', self.tool):
            sign_apk.sign(self.source, self.output)

    def test_tampered_or_traversal_manifest_refused(self):
        (self.source / 'casca-de-bala-source.tar.gz').write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            inputs(self.source)
        self.manifest(self.source)
        with (self.source / 'SHA256SUMS').open('a') as f:
            f.write('0' * 64 + '  ../outside\n')
        with self.assertRaisesRegex(ValueError, 'manifest'):
            inputs(self.source)

    def test_pr_or_other_branch_rejected_before_secret_access(self):
        for event, ref in [('pull_request', 'refs/pull/1/merge'), ('push', 'refs/heads/feature')]:
            with patch.dict(os.environ, GITHUB_EVENT_NAME=event, GITHUB_REF=ref), patch.object(sign_apk.subprocess, 'run') as child:
                with self.assertRaisesRegex(ValueError, 'canonical main'):
                    sign_apk.sign(self.source, self.output)
                self.assertIn('CASCA_ANDROID_KEYSTORE_PASSWORD', os.environ)
                child.assert_not_called()

    def test_missing_secret_no_tools_or_output(self):
        os.environ.pop('CASCA_ANDROID_KEYSTORE_B64')
        with patch.object(sign_apk.subprocess, 'run') as child:
            with self.assertRaisesRegex(ValueError, 'Missing approved'):
                sign_apk.sign(self.source, self.output)
            child.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_wrong_certificate_cleans_output_and_private_files(self):
        def wrong(args, **kw):
            r = self.tool(args, **kw)
            r.stdout = r.stdout.replace(CONFIG['certificateSha256'], '0' * 64)
            return r
        with patch.object(sign_apk, 'verify'), patch.object(sign_apk.subprocess, 'run', wrong):
            with self.assertRaisesRegex(ValueError, 'certificate'):
                sign_apk.sign(self.source, self.output)
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.root.glob('casca-sign-*')))

    def test_simulated_signer_keeps_only_public_artifacts(self):
        self.signed_fixture()
        inputs(self.output, signed=True)
        self.assertFalse(list(self.root.glob('casca-sign-*')))
        self.assertEqual(len(list(self.output.iterdir())), 5)

    def publish_peer(self, mode='new', conflict=False):
        calls = []
        view_count = 0
        names = [p.name for p in self.output.iterdir()]
        def peer(args, **kw):
            nonlocal view_count
            calls.append(args)
            out, err, code = '', '', 0
            if args[2] == 'view':
                view_count += 1
                if mode == 'network':
                    code, err = 1, 'network unavailable'
                elif mode == 'new' and view_count == 1:
                    code, err = 1, 'release not found'
                else:
                    out = json.dumps({'isDraft': mode != 'existing',
                        'targetCommitish': 'a' * 40,
                        'assets': [{'name': n} for n in names] if mode == 'existing' else []})
            if args[2] == 'download':
                name = args[args.index('--pattern') + 1]
                dest = Path(args[args.index('--dir') + 1]) / name
                dest.write_bytes(b'conflict' if conflict else (self.output / name).read_bytes())
            return subprocess.CompletedProcess(args, code, out, err)
        return peer, calls

    def test_publication_network_failure_does_not_create(self):
        self.signed_fixture()
        peer, calls = self.publish_peer('network')
        with patch.object(publish_release.subprocess, 'run', peer):
            with self.assertRaisesRegex(ValueError, 'Cannot inspect'):
                publish_release.publish(self.output)
        self.assertEqual(len(calls), 1)

    def test_publish_after_all_asset_download_checks(self):
        self.signed_fixture()
        peer, calls = self.publish_peer()
        with patch.object(publish_release.subprocess, 'run', peer):
            publish_release.publish(self.output)
        self.assertEqual(sum(a[2] == 'download' for a in calls), 5)
        self.assertEqual(calls[-1][2], 'edit')
        self.assertTrue(all('--clobber' not in a for a in calls))

    def test_existing_published_release_is_idempotent(self):
        self.signed_fixture()
        peer, calls = self.publish_peer('existing')
        with patch.object(publish_release.subprocess, 'run', peer):
            publish_release.publish(self.output)
        self.assertTrue(all(a[2] in ['view', 'download'] for a in calls))

    def test_asset_conflict_never_overwrites_or_publishes(self):
        self.signed_fixture()
        peer, calls = self.publish_peer('existing', conflict=True)
        with patch.object(publish_release.subprocess, 'run', peer):
            with self.assertRaisesRegex(ValueError, 'conflict'):
                publish_release.publish(self.output)
        self.assertTrue(all(a[2] in ['view', 'download'] for a in calls))


if __name__ == '__main__':
    unittest.main()
