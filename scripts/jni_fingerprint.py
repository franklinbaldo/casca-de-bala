#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Fail-closed source fingerprint for the actual Casca Android JNI crate closure.

The fingerprint ignores unrelated crates, integration tests, and examples, but
includes every local path dependency (including workspace/target dependencies),
its build scripts and source tree, and global Rust toolchain/lockfile inputs.
"""

import hashlib
from pathlib import Path
import sys
import tomllib


ROOT = Path(__file__).resolve().parents[1]


def _read_toml(path: Path) -> dict:
    with path.open('rb') as fp:
        return tomllib.load(fp)


def _dependencies(manifest: dict):
    for key in ('dependencies', 'build-dependencies'):
        yield from manifest.get(key, {}).items()
    for target in manifest.get('target', {}).values():
        for key in ('dependencies', 'build-dependencies'):
            yield from target.get(key, {}).items()


def included_inputs(root: Path) -> list[Path]:
    root = root.resolve()
    workspace = _read_toml(root / 'Cargo.toml').get('workspace', {})
    inherited = workspace.get('dependencies', {})
    pending = [root / 'crates/casca-mcp-jni']
    seen = set()
    paths = {root / name for name in ('Cargo.toml', 'Cargo.lock', 'rust-toolchain.toml')}
    # Configuration may affect native ABI and feature selection.
    for name in ('.cargo/config.toml', '.cargo/config'):
        if (root / name).is_file():
            paths.add(root / name)
    while pending:
        crate = pending.pop().resolve()
        if not crate.is_relative_to(root / 'crates') or crate == root / 'crates':
            raise ValueError(f'JNI dependency outside repository crates: {crate}')
        if crate in seen:
            continue
        seen.add(crate)
        manifest_path = crate / 'Cargo.toml'
        manifest = _read_toml(manifest_path)
        paths.add(manifest_path)
        source_dir = crate / 'src'
        if not source_dir.is_dir():
            raise ValueError(f'JNI dependency has no source tree: {crate}')
        # Capture included source/data files too. Do not capture tests/ or examples/.
        paths.update(f for f in source_dir.rglob('*') if f.is_file())
        for name in ('build.rs',):
            if (crate / name).is_file():
                paths.add(crate / name)
        for dep_name, spec in _dependencies(manifest):
            if not isinstance(spec, dict):
                continue
            if spec.get('workspace'):
                spec = inherited.get(dep_name)
                if spec is None:
                    raise ValueError(f'Workspace dependency missing: {dep_name}')
            if isinstance(spec, dict) and 'path' in spec:
                # Workspace paths resolve from the root; local paths from crate.
                base = root if manifest.get('workspace') is not None else crate
                if isinstance(inherited.get(dep_name), dict) and spec is inherited[dep_name]:
                    base = root
                pending.append(base / spec['path'])
    for p in paths:
        if not p.is_file() or not p.resolve().is_relative_to(root):
            raise ValueError(f'Missing or unsafe JNI source: {p}')
    return sorted(paths, key=lambda p: p.relative_to(root).as_posix())


def fingerprint(root: Path = ROOT) -> str:
    root = root.resolve()
    digest = hashlib.sha256()
    for path in included_inputs(root):
        digest.update(path.relative_to(root).as_posix().encode('utf-8'))
        digest.update(b'\0')
        digest.update(path.read_bytes())
        digest.update(b'\0')
    return digest.hexdigest()


if __name__ == '__main__':
    try:
        print(fingerprint(Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT))
    except (OSError, ValueError, tomllib.TOMLDecodeError) as exc:
        print(f'Cannot validate JNI sources: {exc}', file=sys.stderr)
        sys.exit(1)
