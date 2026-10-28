"""Synthetic cache-invalidation cases; no SDK, Cargo, network or APK needed."""

from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from jni_fingerprint import fingerprint, included_inputs  # noqa: E402


class JniFingerprintTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write('Cargo.toml', '[workspace]\nmembers=["crates/*"]\n')
        self.write('Cargo.lock', 'lock 1')
        self.write('rust-toolchain.toml', 'rust 1')
        self.write('crates/casca-mcp-jni/Cargo.toml',
                   '[package]\nname="casca-mcp-jni"\n[dependencies]\ncasca-mcp={path="../casca-mcp"}\n')
        self.write('crates/casca-mcp-jni/src/lib.rs', 'fn native() {}\n')
        self.write('crates/casca-mcp/Cargo.toml', '[package]\nname="casca-mcp"\n')
        self.write('crates/casca-mcp/src/lib.rs', 'fn mcp() {}\n')
        self.write('crates/casca-core/src/lib.rs', 'fn unrelated() {}\n')
        self.write('crates/casca-mcp/tests/fixture.rs', 'fn test_only() {}\n')

    def write(self, path, text):
        p = self.root / path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)

    def test_test_and_unrelated_crate_edits_preserve_jni(self):
        old = fingerprint(self.root)
        self.write('crates/casca-mcp/tests/fixture.rs', 'fn modified_test() {}\n')
        self.write('crates/casca-core/src/lib.rs', 'fn modified_unrelated() {}\n')
        self.assertEqual(old, fingerprint(self.root))

    def test_production_lock_and_toolchain_changes_invalidate(self):
        for path, changed in [
            ('crates/casca-mcp/src/lib.rs', 'fn changed() {}\n'),
            ('crates/casca-mcp-jni/src/lib.rs', 'fn changed_jni() {}\n'),
            ('Cargo.lock', 'lock 2'),
            ('rust-toolchain.toml', 'rust 2'),
        ]:
            old = fingerprint(self.root)
            previous = (self.root / path).read_text()
            self.write(path, changed)
            self.assertNotEqual(old, fingerprint(self.root), path)
            self.write(path, previous)

    def test_added_module_and_build_script_invalidate(self):
        old = fingerprint(self.root)
        self.write('crates/casca-mcp/src/new.rs', 'fn new_module() {}\n')
        self.assertNotEqual(old, fingerprint(self.root))
        old = fingerprint(self.root)
        self.write('crates/casca-mcp/build.rs', 'fn main() {}\n')
        self.assertNotEqual(old, fingerprint(self.root))

    def test_new_path_dependency_is_included(self):
        old = fingerprint(self.root)
        self.write('crates/casca-mcp/Cargo.toml',
                   '[package]\nname="casca-mcp"\n[dependencies]\nnew-crate={path="../new-crate"}\n')
        with self.assertRaises((OSError, ValueError)):
            fingerprint(self.root)
        self.write('crates/new-crate/Cargo.toml', '[package]\nname="new-crate"\n')
        self.write('crates/new-crate/src/lib.rs', 'fn new() {}\n')
        self.assertNotEqual(old, fingerprint(self.root))
        self.assertIn(self.root / 'crates/new-crate/src/lib.rs', included_inputs(self.root))

    def test_workspace_path_dependency_resolved_from_root(self):
        self.write('Cargo.toml',
                   '[workspace]\nmembers=["crates/*"]\n'
                   '[workspace.dependencies]\nextra={path="crates/new-crate"}\n')
        self.write('crates/casca-mcp/Cargo.toml',
                   '[package]\nname="casca-mcp"\n[dependencies]\nextra={workspace=true}\n')
        self.write('crates/new-crate/Cargo.toml', '[package]\nname="extra"\n')
        self.write('crates/new-crate/src/lib.rs', 'pub fn dependency() {}\n')
        self.assertIn(self.root / 'crates/new-crate/src/lib.rs', included_inputs(self.root))

    def test_external_dependency_path_is_rejected(self):
        self.write('crates/casca-mcp/Cargo.toml',
                   '[package]\nname="casca-mcp"\n[dependencies]\nexternal={path="../../../unsafe"}\n')
        with self.assertRaisesRegex(ValueError, 'outside repository crates'):
            fingerprint(self.root)


if __name__ == '__main__':
    unittest.main()
