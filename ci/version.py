#!/usr/bin/env python3
"""Deterministic version per commit; main must not be rewritten."""
import json
import re
import subprocess


def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()


def version():
    sha = git('rev-parse', 'HEAD')
    if not re.fullmatch(r'[a-f0-9]{40}', sha):
        raise ValueError('Invalid commit SHA')
    if git('rev-parse', '--is-shallow-repository') != 'false':
        raise ValueError('Full history required; use fetch-depth: 0')
    code = 1000 + int(git('rev-list', '--first-parent', '--count', 'HEAD'))
    if not 1000 < code <= 2100000000:
        raise ValueError('versionCode outside supported range')
    return {'commit': sha, 'versionCode': code,
            'versionName': f'ci.{code}.{sha[:12]}',
            'tag': f'ci-v{code}-{sha[:12]}'}


if __name__ == '__main__':
    print(json.dumps(version(), indent=2))
