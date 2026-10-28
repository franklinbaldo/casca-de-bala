"""Guard against accidental Rust integration-test executable proliferation."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class RustIntegrationLayoutTests(unittest.TestCase):
    def test_at_most_one_integration_binary_per_crate(self):
        for crate in sorted((ROOT / "crates").glob("*/Cargo.toml")):
            tests = crate.parent / "tests"
            if not tests.is_dir():
                continue
            targets = sorted(tests.glob("*.rs")) + sorted(tests.glob("*/main.rs"))
            self.assertLessEqual(
                len(targets),
                1,
                f"{crate.parent.name} has {len(targets)} integration binaries: {targets}",
            )


if __name__ == "__main__":
    unittest.main()
