#!/usr/bin/env python3
"""Publish only a verified signed artifact; never replace existing assets."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from release_contract import CONFIG, digest, inputs


def publish(directory):
    version, files = inputs(directory, signed=True)
    files.append('SHA256SUMS')
    repo, tag = CONFIG['repository'], version['tag']
    def gh(*args):
        return subprocess.run(['gh', *args, '--repo', repo], capture_output=True, text=True)
    existing = gh('release', 'view', tag, '--json', 'isDraft,targetCommitish,assets')
    if existing.returncode:
        if 'release not found' not in existing.stderr.lower():
            raise ValueError('Cannot inspect release; no publication attempted')
        with tempfile.NamedTemporaryFile(mode='w', suffix='.md') as notes:
            notes.write(f"Fonte do produto: {CONFIG['productVersion']}. Commit: {version['commit']}.\n\n"
                        'APK ARM64 para Android 12+, com assinatura estável do projeto.\n'
                        'A primeira migração de uma chave de laboratório diferente pode exigir remover '
                        'somente o Casca anterior; preserve Zepp Life e pareamento.\n'
                        'Build e assinatura não comprovam autenticação ou funções físicas da Band.\n')
            notes.flush()
            result = gh('release', 'create', tag, '--target', version['commit'], '--draft', '--prerelease',
                        '--title', version['versionName'], '--notes-file', notes.name)
            if result.returncode:
                raise ValueError('Draft creation failed')
        existing = gh('release', 'view', tag, '--json', 'isDraft,targetCommitish,assets')
        if existing.returncode:
            raise ValueError('Draft state uncertain; inspect before retry')
    release = json.loads(existing.stdout)
    if release['targetCommitish'] != version['commit']:
        raise ValueError('Release target collision')
    asset_names = {a['name'] for a in release['assets']}
    if asset_names - set(files):
        raise ValueError('Unexpected existing release assets; refusing publication')
    for name in files:
        if name not in asset_names:
            if not release['isDraft']:
                raise ValueError('Published release incomplete; refusing mutation')
            if gh('release', 'upload', tag, str(directory / name)).returncode:
                raise ValueError('Asset upload failed; draft preserved')
        # Verify public delivery bytes before publishing, including resumed drafts.
        with tempfile.TemporaryDirectory(prefix='casca-release-check-') as d:
            if gh('release', 'download', tag, '--pattern', name, '--dir', d).returncode:
                raise ValueError('Release asset verification download failed')
            if digest(Path(d) / name) != digest(directory / name):
                raise ValueError('Existing release asset conflict; no overwrite')
    if release['isDraft']:
        # A resumed unsigned draft may still say it is waiting for signature.
        # Replace its notes only after every delivery byte has been verified.
        with tempfile.NamedTemporaryFile(mode='w', suffix='.md') as notes:
            notes.write(f"Fonte do produto: {CONFIG['productVersion']}. Commit: {version['commit']}.\n\n"
                        'APK ARM64 assinado e verificado para Android 12+.\n'
                        'Fonte AGPL, hashes e recibo de assinatura acompanham esta versão.\n'
                        'A migração de uma chave temporária diferente pode exigir remover somente '
                        'o Casca anterior; preserve Zepp Life e pareamento.\n'
                        'Build e assinatura não comprovam funções físicas da Band.\n')
            notes.flush()
            if gh('release', 'edit', tag, '--notes-file', notes.name,
                  '--draft=false', '--prerelease', '--latest=false').returncode:
                raise ValueError('Publication outcome uncertain; inspect before retry')
    print('PASS release assets verified; existing assets never overwritten')


if __name__ == '__main__':
    publish(Path(sys.argv[1]))
