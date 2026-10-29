#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Guard the Casca flavor against restoring a Termux dependency."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'gb/overlay/app/src/casca/java/org/workspace/casca'


class NoTermuxContractTest(unittest.TestCase):
    def test_no_termux_in_android_flavor(self):
        manifest = (ROOT / 'gb/overlay/app/src/casca/AndroidManifest.xml').read_text()
        root = ET.fromstring(manifest)
        # Upstream Gadgetbridge declares this permission. The flavor must
        # explicitly delete it from the merged APK with a manifest-merger rule.
        android_name = '{http://schemas.android.com/apk/res/android}name'
        tools_node = '{http://schemas.android.com/tools}node'
        removed = [element for element in root.findall('uses-permission')
                   if element.get(android_name) == 'com.termux.permission.RUN_COMMAND']
        self.assertEqual(len(removed), 1)
        self.assertEqual(removed[0].get(tools_node), 'remove')
        self.assertNotIn('<package android:name="com.termux"', manifest)
        self.assertFalse(list(JAVA.glob('*Termux*')))
        activity = (JAVA / 'CascaActivity.java').read_text()
        self.assertNotIn('Termux', activity)
        # Removing the old setup UI must not remove Android's notification
        # permission checks (or the import they still require).
        if 'PackageManager.PERMISSION_GRANTED' in activity:
            self.assertIn('import android.content.pm.PackageManager;', activity)
        self.assertNotIn('gb.shell', (JAVA / 'CascaMcpService.java').read_text())
        self.assertNotIn('gb_shell', (JAVA / 'CascaGateway.java').read_text())
        self.assertNotIn('com.termux.permission.RUN_COMMAND',
                         (ROOT / 'gb/permissions.txt').read_text())

    def test_no_termux_in_mcp_or_host_tests(self):
        rpc = (ROOT / 'crates/casca-mcp/src/rpc.rs').read_text()
        self.assertNotIn('"gb_shell"', rpc)
        self.assertNotIn('"gb_shell_result"', rpc)
        self.assertNotIn('"gb.shell"', rpc)
        host_tests = (ROOT / 'scripts/test_java_host.py').read_text()
        self.assertNotIn('CascaTermux', host_tests)


if __name__ == '__main__':
    unittest.main()
