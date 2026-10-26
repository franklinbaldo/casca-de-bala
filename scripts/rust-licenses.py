#!/usr/bin/env python3
"""Rust license inventory for the APK: every external crate compiled into
libband7_terminal.so for aarch64-linux-android (normal and build deps of
casca-ffi), with its declared license and the notice files it ships.

    python3 scripts/rust-licenses.py          # rewrite third_party/
    python3 scripts/rust-licenses.py --check  # fail if third_party/ is stale
"""
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = 'aarch64-linux-android'
OVERRIDES = ROOT / 'third_party/license-overrides'
NOTICE = re.compile(r'^(licen[cs]e|copying|notice|copyright|unlicense)([-._].*)?$', re.I)


def inventory():
    meta = json.loads(subprocess.check_output(
        ['cargo', 'metadata', '--locked', '--offline', '--format-version', '1',
         '--filter-platform', TARGET, '--manifest-path', str(ROOT / 'Cargo.toml')]))
    packages = {p['id']: p for p in meta['packages']}
    nodes = {n['id']: n for n in meta['resolve']['nodes']}
    members = set(meta['workspace_members'])
    start = next(i for i in members if packages[i]['name'] == 'casca-ffi')
    seen, stack = set(), [start]
    while stack:
        node = nodes[stack.pop()]
        for dep in node['deps']:
            kinds = {k['kind'] for k in dep['dep_kinds']}
            if kinds & {None, 'build'} and dep['pkg'] not in seen:
                seen.add(dep['pkg'])
                stack.append(dep['pkg'])
    entries = []
    for pid in sorted(seen - members, key=lambda i: (packages[i]['name'], packages[i]['version'])):
        p = packages[pid]
        source = Path(p['manifest_path']).parent
        folder = f"{p['name']}-{p['version']}"
        files = sorted(f.name for f in source.iterdir() if f.is_file() and NOTICE.match(f.name))
        if not files and (OVERRIDES / folder).is_dir():
            # Package ships no license file: use the reviewed copy (see license-overrides/README.md).
            source = OVERRIDES / folder
            files = sorted(f.name for f in source.iterdir() if f.is_file())
        entries.append(({'name': p['name'], 'version': p['version'], 'declared_license': p['license'],
                         'notice_files': [f'third_party/licenses/{folder}/{f}' for f in files]},
                        source, folder, files))
    return entries


def main():
    check = '--check' in sys.argv[1:]
    entries = inventory()
    document = json.dumps([e for e, *_ in entries], indent=2, ensure_ascii=False) + '\n'
    target = ROOT / 'third_party/rust-licenses.json'
    licenses = ROOT / 'third_party/licenses'
    wanted = {folder for _, _, folder, _ in entries}
    if check:
        stale = target.read_text() != document
        stale |= {d.name for d in licenses.iterdir()} != wanted
        for _, source, folder, files in entries:
            stale |= any(not (licenses / folder / f).is_file() or
                         (licenses / folder / f).read_bytes() != (source / f).read_bytes() for f in files)
        if stale:
            sys.exit('third_party/ is stale: run python3 scripts/rust-licenses.py')
        print(f'PASS license inventory: {len(entries)} crates compiled into the APK')
        return
    for d in licenses.iterdir():
        if d.name not in wanted:
            shutil.rmtree(d)
    for _, source, folder, files in entries:
        (licenses / folder).mkdir(parents=True, exist_ok=True)
        for f in files:
            shutil.copyfile(source / f, licenses / folder / f)
    target.write_text(document)
    missing = [e['name'] for e, *_ in entries if not e['notice_files']]
    print(f'{len(entries)} crates; without notice files in the package: {", ".join(missing) or "none"}')


if __name__ == '__main__':
    main()
