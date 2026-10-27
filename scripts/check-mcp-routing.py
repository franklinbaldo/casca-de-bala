# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Fail when a Rust MCP phone job has no Java dispatch case.

This is a source-level contract guard, not an Android compilation.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
rust = (ROOT / "crates/casca-mcp/src/rpc.rs").read_text()
java = (ROOT / "gb/overlay/app/src/casca/java/org/workspace/casca/CascaMcpService.java").read_text()
jobs = set(re.findall(r'on_phone\(\s*id(?:\.clone\(\))?\s*,\s*"(band\.[^"]+)"', rust))
cases = set(re.findall(r'case\s+"(band\.[^"]+)":', java))
assert jobs, "No Rust phone jobs found; update extraction to match Rust implementation"
missing = jobs - cases
assert not missing, f"Unimplemented Android MCP jobs: {sorted(missing)}"
assert "CascaBandLists.read(device" in java
assert "CascaBandLists.setAlarm(device" in java
print(f"PASS: {len(jobs)} Rust phone routes have Java cases; band.lists read/set linked")
