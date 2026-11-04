#!/usr/bin/env python3
"""Write the GitHub Actions job summary for the toolchain pack."""
from __future__ import annotations

import json
import os
import sys


def main() -> None:
    out = sys.argv[1]
    manifest_path = os.path.join(out, "manifest.json")
    if not os.path.isfile(manifest_path):
        print("No manifest produced.")
        return
    with open(manifest_path, encoding="utf-8") as fh:
        manifest = json.load(fh)
    print(f"- Version: {manifest.get('version')}")
    print(f"- Unpacked: {manifest.get('unpacked_bytes', 0) / (1024 ** 3):.2f} GiB")
    for item in manifest.get("files", []):
        print(f"- `{item['name']}`: {item['size'] / (1024 ** 2):.1f} MiB, SHA-256 `{item['sha256']}`")
    elf = manifest.get("elf", {})
    print(f"- Required binaries below 16 KB: {len(elf.get('required_below_16k', []))}")
    print(f"- Documented static exceptions: {len(elf.get('static_below_16k', []))}")


if __name__ == "__main__":
    main()
