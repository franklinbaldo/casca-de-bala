#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Fast JUnit 4 checks of Casca pure Java rules, without Android/Gradle/Rust.

Network access is opt-in via --prepare-network; JARs use pinned SHA-256.
The exact same test files also run in Gadgetbridge's testCascaDebugUnitTest.
"""

import argparse
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / 'gb/overlay/app/src/casca/java/org/workspace/casca'
TESTS = ROOT / 'gb/overlay/app/src/testCasca/java/org/workspace/casca'
JARS = {
    'junit-4.13.2.jar': ('https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar',
                        '8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3'),
    'hamcrest-core-1.3.jar': ('https://repo.maven.apache.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar',
                              '66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9'),
}
PRODUCTION = ('CascaJobDispatch.java', 'CascaTermuxJobBook.java')
TEST_CLASSES = ('CascaJobDispatchTest', 'CascaTermuxJobBookTest')


def jars(cache: Path, prepare_network: bool) -> list[Path]:
    cache.mkdir(parents=True, exist_ok=True)
    resolved = []
    for name, (url, sha) in JARS.items():
        file = cache / name
        if not file.exists():
            if not prepare_network:
                raise RuntimeError(f'{file} missing; use --prepare-network explicitly')
            with urlopen(url, timeout=20) as remote:
                data = remote.read(1_500_000)
            if hashlib.sha256(data).hexdigest() != sha:
                raise RuntimeError(f'Untrusted downloaded JUnit dependency: {name}')
            file.write_bytes(data)
        if hashlib.sha256(file.read_bytes()).hexdigest() != sha:
            raise RuntimeError(f'JUnit dependency checksum mismatch: {name}')
        resolved.append(file)
    return resolved


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache-dir', type=Path, default=ROOT / 'target/casca-junit-cache')
    parser.add_argument('--prepare-network', action='store_true')
    args = parser.parse_args()
    try:
        cp = ':'.join(str(x) for x in jars(args.cache_dir, args.prepare_network))
    except (OSError, RuntimeError) as exc:
        parser.error(str(exc))
    with tempfile.TemporaryDirectory(prefix='casca-junit-') as temp:
        output = Path(temp)
        subprocess.run(['javac', '--release', '8', '-encoding', 'UTF-8', '-cp', cp,
                        '-d', str(output), *(str(SOURCES / name) for name in PRODUCTION),
                        *(str(TESTS / (name + '.java')) for name in TEST_CLASSES)], check=True)
        subprocess.run(['java', '-cp', cp + ':' + str(output), 'org.junit.runner.JUnitCore',
                        *(f'org.workspace.casca.{name}' for name in TEST_CLASSES)], check=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
