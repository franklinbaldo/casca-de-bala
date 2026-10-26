#!/usr/bin/env python3
"""Existing environment key only, on main. Never generate a signing identity."""
import base64
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from release_contract import CONFIG, check_certificate, digest, inputs, main_only
from verify import verify


def sign(source, output):
    main_only()
    # Remove values from the environment before spawning ANY child tool.
    key = os.environ.pop('CASCA_ANDROID_KEYSTORE_B64', None)
    password = os.environ.pop('CASCA_ANDROID_KEYSTORE_PASSWORD', None)
    if not key or not password:
        raise ValueError('Missing approved signing secrets; no debug fallback')
    version, _ = inputs(source)
    verify(source / 'casca-de-bala-arm64-unsigned.apk')
    if output.exists():
        raise ValueError('Refusing to overwrite release output')
    output.mkdir(parents=True, mode=0o700)
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    def run(*args):
        result = subprocess.run([str(a) for a in args], capture_output=True, text=True)
        if result.returncode:
            raise ValueError('Signing/verification tool failed; sensitive diagnostics suppressed')
        return result.stdout
    os.umask(0o077)
    try:
        with tempfile.TemporaryDirectory(prefix='casca-sign-', dir=os.environ.get('RUNNER_TEMP')) as d:
            private = Path(d)
            (private / 'key.p12').write_bytes(base64.b64decode(key, validate=True))
            (private / 'password').write_text(password + '\n')
            key = password = None
            run(tools / 'apksigner', 'sign', '--ks', private / 'key.p12',
                '--ks-type', 'PKCS12', '--ks-key-alias', CONFIG['alias'],
                '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
                '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false',
                '--ks-pass', 'file:' + str(private / 'password'), '--out',
                output / 'casca-de-bala-arm64.apk', source / 'casca-de-bala-arm64-unsigned.apk')
        apk = output / 'casca-de-bala-arm64.apk'
        result = run(tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk)
        check_certificate(result)
        run(tools / 'zipalign', '-c', '-P', '16', '4', apk)
        with zipfile.ZipFile(source / 'casca-de-bala-arm64-unsigned.apk') as before, zipfile.ZipFile(apk) as after:
            if after.testzip() is not None:
                raise ValueError('Signed ZIP corrupt')
            for name in before.namelist():
                if before.read(name) != after.read(name):
                    raise ValueError('Signing changed original payload')
            extra = set(after.namelist()) - set(before.namelist())
            if any(not re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[A-Z0-9_-]+\.(?:SF|RSA))', n) for n in extra):
                raise ValueError('Unexpected files added while signing')
        for name in ['casca-de-bala-source.tar.gz', 'version.json']:
            shutil.copyfile(source / name, output / name)
        receipt = {'commit': version['commit'], 'versionCode': version['versionCode'],
                   'versionName': version['versionName'], 'productVersion': CONFIG['productVersion'],
                   'certificateSha256': CONFIG['certificateSha256'], 'apkSha256': digest(apk),
                   'verification': 'apksigner, certificate, payload, alignment PASS',
                   'hardware': 'NOT_RUN', 'sigstoreAttestation': 'NOT_IMPLEMENTED'}
        (output / 'signing-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
        files = sorted(p for p in output.iterdir() if p.is_file())
        (output / 'SHA256SUMS').write_text(''.join(f'{digest(p)}  {p.name}\n' for p in files))
        inputs(output, signed=True)
        print('PASS signed APK verified against fixed public certificate; no key in artifacts')
    except Exception:
        shutil.rmtree(output)
        raise


if __name__ == '__main__':
    try:
        sign(Path(sys.argv[1]), Path(sys.argv[2]))
    except Exception:
        print('APK signing failed closed; no release output retained', file=sys.stderr)
        sys.exit(1)
