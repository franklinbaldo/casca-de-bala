"""Run the specifically approved ZMake commit with a non-deployable lab profile."""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import subprocess
import sys
import zipfile

COMMIT = "47acece62528a86a1445d92414cd9a087037eb1c"
EPOCH = 1767225600


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zmake", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--lab-profile", action="store_true", required=True)
    args = parser.parse_args()
    upstream = args.zmake.resolve()
    if subprocess.check_output(["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip() != COMMIT:
        raise ValueError("wrong upstream commit")
    if subprocess.check_output(["git", "-C", str(upstream), "status", "--porcelain"], text=True).strip():
        raise ValueError("dirty upstream checkout")
    # Never erase an existing directory. Upstream mutates only this new staging tree.
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    project = output / "band7-lab"
    (project / "page").mkdir(parents=True)
    (project / "assets").mkdir()
    source = Path(__file__).resolve().parents[1]
    app = (source / "app.js").read_text()
    page = (source / "page/index.js").read_text()
    (project / "app.js").write_text(
        "(function(){ const App = config => { __$$hmAppManager$$__.currentApp.app = DeviceRuntimeCore.App(config); };\n" + app + "\n})();\n")
    (project / "page/index.js").write_text(
        "(function(){ const Page = config => { config.onInit = config.build; delete config.build; __$$hmAppManager$$__.currentApp.current.module = DeviceRuntimeCore.Page(config); };\n" + page + "\n})();\n")
    manifest = {
        "configVersion": "v2", "debug": False, "permissions": [],
        "app": {"appIdType": 0, "appType": "app", "appId": 305441741,
                "appName": "Band7 Lab", "vender": "workspace", "description": "Lab only; target and ID unverified",
                "version": {"code": 1, "name": "0.1.0"}, "icon": "icon.png"},
        "runtime": {"apiVersion": {"compatible": "1.0.0", "minVersion": "1.0.0", "target": "1.0.1"}},
        "module": {"page": {"pages": ["page/index"]}},
        "platforms": [{"name": "l66w_2", "deviceSource": 263}],
    }
    (project / "app.json").write_text(json.dumps(manifest, sort_keys=True))
    sys.path.insert(0, str(upstream))
    from PIL import Image
    from zmake.context import ZMakeContext
    from zmake import project_build as builder, image_io
    image = Image.new("RGB", (32, 32), (36, 90, 129))
    image.save(project / "assets/icon.png")
    context = ZMakeContext.__new__(ZMakeContext)
    # Skip config discovery: no user settings, hooks or external programs.
    import logging
    context.path = project
    context.path_assets = project / "assets"
    context.logger = logging.getLogger("zmake")
    context.target_dir = "page"
    context.app_json = manifest
    context.config = json.loads((upstream / "zmake/zmake.json").read_text())
    context.config.update({"esbuild": False, "with_uglifyjs": False,
        "with_adb": False, "with_zepp_preview": False, "with_zeus_compat": False,
        "pre_build_script": "", "post_build_script": "", "overrides": {},
        "common_files": [], "def_format": "TGA-16", "encode_mode": "dialog",
        "zeus_platforms": manifest["platforms"]})
    # Fixed packageInfo time; ZIP metadata is normalized in a separate, explicit step.
    builder.time.time = lambda: EPOCH
    for stage in (builder.prepare, builder.process_app_json, builder.handle_assets,
                  builder.handle_appjs, builder.handle_app, builder.handle_post_processing,
                  builder.package):
        stage(context)
    recovered, encoding = image_io.load_auto(project / "build/assets/icon.png", "dialog")
    assert recovered.size == (32, 32)
    # RGB565 quantization tolerance. The .png suffix contains a TGA device resource.
    assert all(abs(a-b) <= 8 for a, b in zip(recovered.convert("RGB").getpixel((0, 0)), (36, 90, 129)))
    raw = project / "dist/band7-lab.bin"
    candidate = output / "band7-lab.candidate.bin"
    with zipfile.ZipFile(raw) as original, zipfile.ZipFile(candidate, "w", zipfile.ZIP_STORED) as normalized:
        for name in sorted(original.namelist()):
            if name.endswith("/"):
                continue
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            normalized.writestr(entry, original.read(name))
    from package import inspect
    report = inspect(candidate.read_bytes())
    report.update({"profile": "lab-only-international-263", "appId_collision_checked": False,
                   "identified_user_device": False, "zmake_commit": COMMIT,
                   "python": sys.version.split()[0],
                   "pillow": importlib.metadata.version("Pillow"),
                   "qrcode": importlib.metadata.version("qrcode"),
                   "icon_roundtrip": "pass", "source_sha256": {
                       "app.js": hashlib.sha256(app.encode()).hexdigest(),
                       "page/index.js": hashlib.sha256(page.encode()).hexdigest()}})
    (output / "report.json").write_text(json.dumps(report, sort_keys=True, indent=2) + "\n")
    print(json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    main()
