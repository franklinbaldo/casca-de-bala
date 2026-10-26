"""Public release inputs and fail-closed checks shared by signer/publisher."""
import hashlib
import json
import os
from pathlib import Path
import re

CONFIG = json.loads((Path(__file__).parent / 'release-config.json').read_text())


def digest(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def main_only():
    if (os.environ.get('GITHUB_EVENT_NAME') != 'push' or
            os.environ.get('GITHUB_REF') != 'refs/heads/main' or
            os.environ.get('GITHUB_REPOSITORY') != CONFIG['repository']):
        raise ValueError('Signing/publication requires push on canonical main')


def check_certificate(output):
    fingerprints = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$', output, re.M)
    if fingerprints != [CONFIG['certificateSha256']] or 'Number of signers: 1' not in output:
        raise ValueError('Unexpected APK signing certificate')


def inputs(directory, signed=False):
    main_only()
    for name in ['version.json', 'SHA256SUMS']:
        path = directory / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 1048576:
            raise ValueError('Invalid release metadata file')
    version = json.loads((directory / 'version.json').read_text())
    sha = version['commit']
    if not re.fullmatch('[a-f0-9]{40}', sha) or sha != os.environ.get('GITHUB_SHA'):
        raise ValueError('Artifact commit does not match workflow')
    code = version['versionCode']
    if type(code) is not int or not 1000 < code <= 2100000000:
        raise ValueError('Invalid release version code')
    if version['versionName'] != f'ci.{code}.{sha[:12]}' or version['tag'] != f'ci-v{code}-{sha[:12]}':
        raise ValueError('Invalid release version identity')
    apk = 'casca-de-bala-arm64.apk' if signed else 'casca-de-bala-arm64-unsigned.apk'
    expected = {apk, 'casca-de-bala-source.tar.gz', 'version.json'}
    if signed:
        expected.add('signing-receipt.json')
    seen = set()
    for line in (directory / 'SHA256SUMS').read_text().splitlines():
        checksum, name = line.split('  ', 1)
        if name not in expected or name in seen or not re.fullmatch('[a-f0-9]{64}', checksum):
            raise ValueError('Invalid artifact manifest')
        path = directory / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 134217728:
            raise ValueError('Artifact must be a bounded regular file')
        if digest(path) != checksum:
            raise ValueError('Artifact checksum mismatch')
        seen.add(name)
    if seen != expected:
        raise ValueError('Artifact manifest incomplete')
    if signed:
        receipt = json.loads((directory / 'signing-receipt.json').read_text())
        if receipt['certificateSha256'] != CONFIG['certificateSha256'] or receipt['commit'] != sha:
            raise ValueError('Signing identity mismatch')
    return version, sorted(expected)
