"""Offline source preparation and conservative inspection; never executes packages."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import stat
import zipfile

MAX_BYTES = 2 * 1024 * 1024
MAX_EXPANDED = 8 * 1024 * 1024


def strict_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=pairs)


def prepare(root):
    if root.is_symlink() or (root / "page").is_symlink():
        raise ValueError("symbolic source directory")
    files = {}
    # Deliberate allowlist: excludes VM experiment, tests and host files.
    for name in ("app.js", "page/index.js"):
        path = root / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("missing or symbolic source")
        data = path.read_bytes()
        if len(data) > MAX_BYTES:
            raise ValueError("source too large")
        files[name] = {"sha256": hashlib.sha256(data).hexdigest(),
                       "utf8": data.decode("utf-8")}
    return {"kind": "band7-source-bundle-v1", "installable": False,
            "api_intent": "1.0", "files": files,
            "unresolved": ["identified_deviceSource", "collision_checked_appId",
                           "manifest", "icon_encoding", "runtime_wrappers", "approved_builder"]}


def inspect(data):
    if len(data) > MAX_BYTES:
        raise ValueError("archive too large")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        if not 1 <= len(entries) <= 64:
            raise ValueError("entry count")
        names = set()
        total = 0
        payload = {}
        for entry in entries:
            name = entry.filename
            parts = PurePosixPath(name).parts
            if (not name or name.startswith("/") or "\\" in name or ":" in name
                    or any(p in ("", ".", "..") for p in name.rstrip("/").split("/"))
                    or not parts or name in names):
                raise ValueError("unsafe or duplicate path")
            names.add(name)
            mode = entry.external_attr >> 16
            if stat.S_ISLNK(mode) or (stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)):
                raise ValueError("special file")
            if entry.flag_bits & 1 or entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                raise ValueError("encryption or compression")
            total += entry.file_size
            if total > MAX_EXPANDED or entry.file_size > MAX_BYTES:
                raise ValueError("expansion limit")
            if entry.file_size > max(1, entry.compress_size) * 200:
                raise ValueError("compression ratio")
            # Read checks CRC. No extraction and no JavaScript evaluation.
            with archive.open(entry) as stream:
                content = stream.read(MAX_BYTES + 1)
            if len(content) != entry.file_size:
                raise ValueError("size mismatch")
            if not entry.is_dir():
                payload[name] = content
        if not {"app.json", "app.js", "page/index.js"} <= payload.keys():
            raise ValueError("not the expected device payload (outer ZIP/ZPK unsupported)")
        manifest = strict_json(payload["app.json"].decode("utf-8"))
        if not isinstance(manifest, dict):
            raise ValueError("manifest object required")
        app = manifest.get("app", {})
        api = manifest.get("runtime", {}).get("apiVersion", {})
        pages = manifest.get("module", {}).get("page", {}).get("pages", [])
        if (manifest.get("configVersion") != "v2" or app.get("appType") != "app"
                or type(app.get("appId")) is not int or not 0 < app["appId"] <= 0x7fffffff
                or not isinstance(app.get("version"), dict)
                or not isinstance(pages, list) or "page/index" not in pages):
            raise ValueError("manifest profile mismatch")
        if (api.get("compatible") != "1.0.0" or api.get("minVersion") != "1.0.0"
                or api.get("target") not in ("1.0.0", "1.0.1")):
            raise ValueError("API1 profile mismatch")
        icon = app.get("icon")
        if not isinstance(icon, str) or "assets/" + icon not in payload:
            raise ValueError("icon asset missing")
        for name in ("app.js", "page/index.js"):
            payload[name].decode("utf-8")
    return {"kind": "band7-device-payload-inspection-v1", "installable": False,
            "result": "structural_profile_pass", "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data), "entries": sorted(names),
            "unverified": ["target_compatibility", "appId_collision", "asset_encoding",
                           "runtime_semantics", "authenticity", "device_installation"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("prepare").add_argument("source", type=Path)
    sub.add_parser("inspect").add_argument("package", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            result = prepare(args.source)
        else:
            with args.package.open("rb") as stream:
                data = stream.read(MAX_BYTES + 1)
            result = inspect(data)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
    except (ValueError, OSError, zipfile.BadZipFile, KeyError, TypeError, AttributeError, RuntimeError, NotImplementedError) as error:
        # Fixed summary avoids leaking paths or package-controlled exception text.
        print(json.dumps({"result": "rejected", "error_type": type(error).__name__, "installable": False}))
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
