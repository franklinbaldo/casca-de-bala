#!/usr/bin/env python3
"""Only the already reviewed official BC artifact; no implicit offline fetch."""
import hashlib
import os
from pathlib import Path
import tempfile
import urllib.request

SHA = '2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f'
SIZE = 7224011
URL = 'https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/1.86/bcprov-jdk18on-1.86.jar'


def check(path):
    if not path.is_file() or path.stat().st_size != SIZE:
        raise ValueError('BC artifact missing or unexpected size')
    with path.open('rb') as f:
        if hashlib.file_digest(f, 'sha256').hexdigest() != SHA:
            raise ValueError('BC artifact checksum mismatch')
    return path.resolve()


def prepare():
    if os.environ.get('BC_JAR'):
        return check(Path(os.environ['BC_JAR']))
    target = Path(__file__).resolve().parent.parent / 'artifacts/deps/bcprov-jdk18on-1.86.jar'
    if target.exists():
        return check(target)
    if os.environ.get('CI_PREPARE_NETWORK') != '1':
        raise ValueError('Set BC_JAR or explicitly enable CI_PREPARE_NETWORK=1')
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='bc-download-', dir=target.parent)
    temp = Path(name)
    try:
        with os.fdopen(fd, 'wb') as f, urllib.request.urlopen(URL, timeout=60) as response:
            received = 0
            while chunk := response.read(262144):
                received += len(chunk)
                if received > SIZE:
                    raise ValueError('BC download exceeds expected size')
                f.write(chunk)
        check(temp)
        temp.replace(target)
        return target.resolve()
    finally:
        temp.unlink(missing_ok=True)


if __name__ == '__main__':
    print(prepare())
