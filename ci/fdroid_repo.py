#!/usr/bin/env python3
"""Rebuild the project F-Droid repository from published GitHub releases.

Stateless: every run downloads the newest signed releases again, checks each
APK against the pinned certificate and lets fdroidserver write a freshly
signed index. Nothing here can sign an APK; the repo key signs only the index.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from release_contract import CONFIG, check_certificate, main_only

APP_ID = 'org.workspace.band7.basicreader'
KEEP = 5
ROOT = Path(__file__).resolve().parent.parent
REPO_URL = 'https://franklinbaldo.github.io/casca-de-bala/fdroid/repo'


def run(*args, **kw):
    return subprocess.run([str(a) for a in args], check=True, capture_output=True, text=True, **kw).stdout


def releases():
    listing = json.loads(run('gh', 'release', 'list', '--repo', CONFIG['repository'], '--limit', '50',
                             '--json', 'tagName,isDraft'))
    tags = [r['tagName'] for r in listing if not r['isDraft']]
    tags = [t for t in tags if re.fullmatch(r'ci-v[0-9]+-[a-f0-9]{12}', t)]
    return sorted(tags, key=lambda t: int(t.split('-')[1][1:]), reverse=True)[:KEEP]


def fingerprint(keystore, alias):
    der = subprocess.run(['keytool', '-exportcert', '-keystore', keystore, '-alias', alias,
                          '-storepass:env', 'CASCA_FDROID_KEYSTORE_PASSWORD'],
                         check=True, capture_output=True).stdout
    return hashlib.sha256(der).hexdigest().upper()


def build(site, apk_source=None):
    main_only()
    key = os.environ.pop('CASCA_FDROID_KEYSTORE_B64', None)
    if not key or not os.environ.get('CASCA_FDROID_KEYSTORE_PASSWORD'):
        raise ValueError('Missing F-Droid repo key secrets')
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    if site.exists():
        raise ValueError('Refusing to overwrite site output')
    with tempfile.TemporaryDirectory(prefix='casca-fdroid-', dir=os.environ.get('RUNNER_TEMP')) as d:
        work = Path(d)
        os.chmod(work, 0o700)
        keystore = work / 'repo-key.p12'
        keystore.write_bytes(base64.b64decode(key, validate=True))
        key = None
        (work / 'repo').mkdir()
        shutil.copytree(ROOT / 'fdroid/metadata', work / 'metadata')
        # repo_icon resolves against the working directory, not repo/icons.
        shutil.copyfile(ROOT / f'fdroid/metadata/{APP_ID}/en-US/icon.png', work / 'icon.png')
        tags = releases() if apk_source is None else [p.name for p in sorted(apk_source.iterdir())]
        if not tags:
            raise ValueError('No published release to index')
        for tag in tags:
            if apk_source is None:
                run('gh', 'release', 'download', tag, '--repo', CONFIG['repository'],
                    '--pattern', 'casca-de-bala-arm64.apk', '--dir', work / 'dl' / tag)
                apk = work / 'dl' / tag / 'casca-de-bala-arm64.apk'
            else:
                apk = apk_source / tag
            # Never index an APK signed by anything but the pinned project key.
            check_certificate(run(tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk))
            code = re.search(r"versionCode='([0-9]+)'", run(tools / 'aapt', 'dump', 'badging', apk)).group(1)
            shutil.copyfile(apk, work / 'repo' / f'{APP_ID}_{code}.apk')
        (work / 'config.yml').write_text(
            f"repo_url: {REPO_URL}\n"
            "repo_name: Casca de Bala\n"
            "repo_description: >-\n"
            "  APKs assinados do Casca de Bala, publicados pelo CI a partir das releases do GitHub.\n"
            f"repo_icon: icon.png\narchive_older: 0\nsdk_path: {os.environ['ANDROID_HOME']}\n"
            "repo_keyalias: casca-fdroid-repo\nkeystore: repo-key.p12\n"
            "keystorepass: {env: CASCA_FDROID_KEYSTORE_PASSWORD}\n"
            "keypass: {env: CASCA_FDROID_KEYSTORE_PASSWORD}\n"
            "keydname: CN=Casca de Bala F-Droid repo\n")
        os.chmod(work / 'config.yml', 0o600)
        subprocess.run(['fdroid', 'update', '--pretty'], cwd=work, check=True)
        repo_fp = fingerprint(keystore, 'casca-fdroid-repo')
        (site / 'fdroid').mkdir(parents=True)
        shutil.copytree(work / 'repo', site / 'fdroid/repo')
    for private in ['repo-key.p12', 'config.yml']:
        if any(site.rglob(private)):
            raise ValueError('Private repo material leaked into site')
    link = f'{REPO_URL}?fingerprint={repo_fp}'
    (site / 'index.html').write_text(
        '<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">'
        '<title>Casca de Bala F-Droid</title><body style="font-family:sans-serif;max-width:40em;margin:2em auto;padding:0 16px">'
        '<h1>Casca de Bala</h1><p>Repositório F-Droid do projeto. No celular, abra o link abaixo '
        'com o F-Droid instalado, ou adicione-o em Configurações → Repositórios.</p>'
        f'<p><a href="{link}">{REPO_URL}</a></p>'
        f'<p>Impressão digital do repositório (SHA-256):<br><code style="word-break:break-all">{repo_fp}</code></p>'
        '<p><a href="https://github.com/franklinbaldo/casca-de-bala">Código-fonte (AGPL-3.0-or-later)</a></p>\n')
    print(f'PASS F-Droid repo with {len(tags)} verified APK(s); fingerprint {repo_fp}')
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as f:
            f.write(f'F-Droid repo: {link}\n')


if __name__ == '__main__':
    build(Path(sys.argv[1]), Path(sys.argv[2]) if len(sys.argv) > 2 else None)
