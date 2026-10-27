#!/usr/bin/env python3
"""Check actual Android output; an unsigned artifact is never an installer release."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile


def verify(apk, signed=False):
    if signed:
        from release_contract import inputs
        inputs(apk.parent, signed=True)
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    def run(*args):
        return subprocess.check_output([str(x) for x in args], text=True,
                                       stderr=subprocess.STDOUT)
    run(tools / 'zipalign', '-c', '-P', '16', '4', apk)
    badging = run(tools / 'aapt', 'dump', 'badging', apk)
    expected = json.loads((apk.parent / 'version.json').read_text())
    assert "name='org.workspace.band7.basicreader'" in badging
    assert f"versionCode='{expected['versionCode']}'" in badging
    assert f"versionName='{expected['versionName']}'" in badging
    assert "sdkVersion:'23'" in badging  # Gadgetbridge's minSdk
    assert "native-code: 'arm64-v8a'" in badging
    # Gadgetbridge asks for many permissions; the reviewed list lives in
    # gb/permissions.txt, so any change shows up in the pull request diff.
    permissions = set(re.findall(r"uses-permission: name='([^']+)'", badging))
    reviewed = Path(__file__).resolve().parent.parent / 'gb/permissions.txt'
    expected_permissions = {line for line in reviewed.read_text().split('\n')
                            if line and not line.startswith('#')}
    assert permissions == expected_permissions, sorted(permissions ^ expected_permissions)
    xml = run(tools / 'aapt', 'dump', 'xmltree', apk, 'AndroidManifest.xml')
    assert not re.search(r'android:(debuggable|testOnly).*0xffffffff', xml)
    with zipfile.ZipFile(apk) as z:
        assert z.testzip() is None
        assert {n.split('/')[1] for n in z.namelist() if n.startswith('lib/')} == {'arm64-v8a'}
        assert 'lib/arm64-v8a/libcasca_mcp.so' in z.namelist()
        assert all(not n.startswith('META-INF/') or not n.endswith(('.RSA', '.SF'))
                   for n in z.namelist())
    if signed:
        from release_contract import check_certificate
        check_certificate(run(tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk))
        print('PASS signed ARM64 APK and fixed public certificate')
    else:
        result = subprocess.run([str(tools / 'apksigner'), 'verify', str(apk)],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        assert result.returncode != 0, 'Unsigned build unexpectedly signed'
        print('PASS ARM64/package/version/permissions/alignment; UNSIGNED, NOT INSTALLABLE')


if __name__ == '__main__':
    verify(Path(sys.argv[1]), signed='--signed' in sys.argv[2:])
