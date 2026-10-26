#!/usr/bin/env python3
"""Create an immutable draft; no signing key and no publication capability here."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

directory = Path(sys.argv[1])
v = json.loads((directory / 'version.json').read_text())
assert os.environ.get('GITHUB_EVENT_NAME') == 'push'
assert os.environ.get('GITHUB_REF') == 'refs/heads/main'
assert v['commit'] == os.environ['GITHUB_SHA']
assert re.fullmatch(r'ci-v[0-9]+-[a-f0-9]{12}', v['tag'])
assert v['tag'] == f"ci-v{v['versionCode']}-{v['commit'][:12]}"
repo = os.environ['GITHUB_REPOSITORY']
assert repo == 'franklinbaldo/casca-de-bala'
base = ['gh', 'release', 'view', v['tag'], '--repo', repo, '--json', 'isDraft,targetCommitish']
existing = subprocess.run(base, capture_output=True, text=True)
if existing.returncode == 0:
    release = json.loads(existing.stdout)
    assert release['targetCommitish'] == v['commit'], 'Tag/version collision'
    print('Release already exists; leaving all assets/state unchanged')
else:
    # Distinguish missing release from authentication/network failures.
    assert 'release not found' in existing.stderr.lower(), existing.stderr
    notes = directory / 'release-notes.md'
    notes.write_text(f"Commit: {v['commit']}\n\n"
                     'Rascunho aguardando assinatura permanente e verificação.\n'
                     'APK unsigned disponível apenas como artefato do workflow; não é instalador.\n'
                     'Nenhuma chave foi obtida pelo CI. ART/BLE/hardware: NOT_RUN.\n')
    subprocess.run(['gh', 'release', 'create', v['tag'], '--repo', repo,
                    '--target', v['commit'], '--draft', '--prerelease',
                    '--title', v['versionName'], '--notes-file', str(notes),
                    str(directory / 'casca-de-bala-source.tar.gz'),
                    str(directory / 'version.json')], check=True)
