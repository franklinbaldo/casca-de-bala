#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Repeat warm Gradle Java/test builds, recording exact tasks and timings.

Requires an explicitly prepared SDK/Gadgetbridge worktree with matching JNI.
Never signs, assembles a release, accepts SDK terms, or changes toolchains.
"""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
TASK_RE = re.compile(r'^> Task (:\S+)(?: (UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED|FAILED))?$', re.M)


def run_task(task: str, output: Path, number: int) -> dict:
    env = dict(os.environ, GB_SKIP_RUST='1', GB_TASK=task,
               GB_GRADLE_ARGS='--offline --build-cache --profile --console=plain')
    command = ['sh', 'gb/build.sh']
    t0 = time.monotonic()
    result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True)
    duration = round(time.monotonic() - t0, 3)
    log = output / f'{task}-{number}.log'
    log.write_text(result.stdout + '\n' + result.stderr, encoding='utf8')
    tasks = [{'name': name, 'outcome': outcome or 'EXECUTED'}
             for name, outcome in TASK_RE.findall(result.stdout + '\n' + result.stderr)]
    return {'task': task, 'iteration': number, 'seconds': duration,
            'returncode': result.returncode, 'gradle_tasks': tasks, 'log': str(log)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--repeats', type=int, default=2)
    parser.add_argument('--task', action='append', choices=[
        'compileCascaDebugJavaWithJavac', 'testCascaDebugUnitTest'],
        help='Gradle task(s) to measure; default both')
    args = parser.parse_args()
    if not 1 <= args.repeats <= 10:
        parser.error('--repeats must be between 1 and 10')
    if not os.environ.get('ANDROID_HOME'):
        parser.error('ANDROID_HOME is required; build SDK first')
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    tasks = args.task or ['compileCascaDebugJavaWithJavac', 'testCascaDebugUnitTest']
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    results = []
    for task in tasks:
        for i in range(1, args.repeats + 1):
            result = run_task(task, out, i)
            print(f"{task} run={i} elapsed={result['seconds']}s rc={result['returncode']}", flush=True)
            results.append(result)
            if result['returncode']:
                break
    data = {'head': head, 'created_at': datetime.now(timezone.utc).isoformat(),
            'notes': 'First run warms cache; compare only matching tasks/toolchains and cache states.',
            'results': results}
    (out / 'java-gradle-profile.json').write_text(json.dumps(data, indent=2) + '\n', encoding='utf8')
    return 0 if all(r['returncode'] == 0 for r in results) else 1


if __name__ == '__main__':
    sys.exit(main())
