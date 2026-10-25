import io
import json
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile
from package import inspect, prepare, MAX_BYTES


def fixture(extra=(), modify=None):
    manifest = {"configVersion": "v2", "app": {"appType": "app", "appId": 123456,
                "version": {"code": 1, "name": "test"}, "icon": "icon.png"},
                "runtime": {"apiVersion": {"compatible": "1.0.0", "minVersion": "1.0.0", "target": "1.0.1"}},
                "module": {"page": {"pages": ["page/index"]}}}
    if modify:
        modify(manifest)
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as archive:
        for name, content in [("app.json", json.dumps(manifest)), ("app.js", "/* synthetic */"),
                              ("page/index.js", "/* synthetic */"), ("assets/icon.png", b"not-a-real-icon"), *extra]:
            archive.writestr(name, content)
    return out.getvalue()


class PackagingTests(unittest.TestCase):
    def test_structural_pass_never_claims_installability(self):
        result = inspect(fixture())
        self.assertEqual(result["result"], "structural_profile_pass")
        self.assertFalse(result["installable"])
        self.assertIn("asset_encoding", result["unverified"])

    def test_paths_and_duplicates_rejected(self):
        for path in ("../outside", "/absolute", "page/../escape", "a\\b", "a//b", "C:drive", "app.js"):
            with self.subTest(path=path), warnings.catch_warnings():
                warnings.simplefilter("ignore")
                with self.assertRaises(ValueError):
                    inspect(fixture([(path, "bad")]))

    def test_manifest_and_api_rejected(self):
        for change in (lambda m: m["app"].update(appId=True),
                       lambda m: m["runtime"]["apiVersion"].update(target="3.0.0"),
                       lambda m: m["app"].update(icon="absent.png")):
            with self.assertRaises(ValueError):
                inspect(fixture(modify=change))
        with self.assertRaises(ValueError):
            inspect(b"a" * (MAX_BYTES + 1))
        with self.assertRaises(zipfile.BadZipFile):
            inspect(b"not a zip")

    def test_bomb_and_symlink_rejected(self):
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as arc:
            arc.writestr("bomb", b"a" * 1000000)
        with self.assertRaises(ValueError):
            inspect(out.getvalue())
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w") as arc:
            link = zipfile.ZipInfo("link")
            link.external_attr = 0o120777 << 16
            arc.writestr(link, "../outside")
        with self.assertRaises(ValueError):
            inspect(out.getvalue())

    def test_prepare_is_deterministic_and_rejects_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "page").mkdir()
            (root / "app.js").write_text("App({})")
            (root / "page/index.js").write_text("Page({})")
            first = prepare(root)
            (root / "unrelated").write_text("ignored")
            self.assertEqual(first, prepare(root))
            self.assertFalse(first["installable"])
            (root / "page/index.js").unlink()
            (root / "page/index.js").symlink_to(root / "app.js")
            with self.assertRaises(ValueError):
                prepare(root)


if __name__ == "__main__":
    unittest.main()
