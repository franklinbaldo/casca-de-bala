#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Fail-closed contracts for the local Android isolated-shell feasibility probe.

This does NOT certify isolation; a device test must verify runtime UID,
SELinux restrictions, private-file denial and whether sh can start.
"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OVERLAY = ROOT / "gb/overlay/app/src/casca"
JAVA = OVERLAY / "java/org/workspace/casca"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class IsolatedShellProbeContractTest(unittest.TestCase):
    def test_shell_service_private_separate_process(self):
        root = ET.parse(OVERLAY / "AndroidManifest.xml").getroot()
        services = [
            el for el in root.findall("./application/service")
            if el.get(ANDROID + "name") == "org.workspace.casca.CascaIsolatedShellService"
        ]
        self.assertEqual(1, len(services))
        service = services[0]
        self.assertEqual("false", service.get(ANDROID + "exported"))
        self.assertEqual("true", service.get(ANDROID + "isolatedProcess"))
        self.assertEqual(":casca_shell_isolated", service.get(ANDROID + "process"))
        self.assertIsNone(service.get(ANDROID + "permission"))

    def test_probe_only_not_remote_shell(self):
        service = (JAVA / "CascaIsolatedShellService.java").read_text()
        activity = (JAVA / "CascaActivity.java").read_text()
        rpc = (ROOT / "crates/casca-mcp/src/rpc.rs").read_text()
        self.assertIn('"/system/bin/sh"', service)
        self.assertIn('"id; pwd"', service)
        self.assertIn("privateFileReadable", service)
        self.assertIn("Process.myUid()", service)
        self.assertIn("Process.isIsolated()", service)
        self.assertIn("destroyForcibly()", service)
        self.assertIn("TIMEOUT_MS", service)
        self.assertIn("MAX_OUTPUT_BYTES", service)
        self.assertIn("bindService(", activity)
        self.assertIn("CascaIsolatedShellService.class", activity)
        self.assertIn('"gb_shell"', rpc)
        self.assertIn('"gb_shell_result"', rpc)
        # Unverified probes are hard-coded; remote execution requires a distinct
        # approved-only operation in the isolated service.
        self.assertIn('return execute("id; pwd", TIMEOUT_MS', service)
        self.assertIn('MSG_EXEC', service)
        self.assertIn('Process.isIsolated()', service)
        # The proof must read the *main app's* marker path, not a possibly
        # different getFilesDir() in the isolated UID.
        self.assertIn('args.getString("private_path"', service)
        self.assertIn('evidence.putString("private_path"', activity)
        main_service = (JAVA / 'CascaMcpService.java').read_text()
        self.assertIn('private_marker_missing', main_service)
        self.assertNotIn('com.termux.permission.RUN_COMMAND', service)

    def test_gadgetbridge_bootstrap_skipped_for_isolated_process(self):
        patch = (ROOT / "gb/patches/0003-casca-isolated-shell-bootstrap.patch").read_text()
        self.assertIn('":casca_shell_isolated"', patch)
        self.assertIn('android.app.Application.getProcessName()', patch)
        self.assertIn('super.onCreate();', patch)
        self.assertIn('return;', patch)
        self.assertIn('Do not start', patch)
        # An explicit product-flavor/process-name gate prevents changes to the
        # normal GB application lifecycle on Android devices and other flavors.
        self.assertIn('getPackageName()', patch)
        self.assertNotIn('+        android.os.Process.killProcess(', patch)

    def test_no_new_permission_or_remote_door(self):
        root = ET.parse(OVERLAY / "AndroidManifest.xml").getroot()
        dangerous = [
            el.get(ANDROID + "name") for el in root.findall("uses-permission")
            if el.get(ANDROID + "name", "") in {
                "android.permission.REQUEST_INSTALL_PACKAGES",
                "android.permission.WRITE_SECURE_SETTINGS",
            }
        ]
        self.assertEqual([], dangerous)
        self.assertNotIn("gb.shell", (JAVA / "CascaMcpService.java").read_text())


if __name__ == "__main__":
    unittest.main()
