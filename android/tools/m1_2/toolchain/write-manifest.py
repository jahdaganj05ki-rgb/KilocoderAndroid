#!/usr/bin/env python3
"""Write manifest.json for the M1.2 toolchain pack."""
from __future__ import annotations

import datetime
import json
import os
import sys


def pin(name: str) -> dict:
    size = os.environ.get(f"{name}_SIZE", "0")
    return {
        "url": os.environ.get(f"{name}_URL", ""),
        "sha256": os.environ.get(f"{name}_SHA", ""),
        "size": int(size) if size.isdigit() else 0,
    }


def main() -> None:
    out, version, unpacked, rootfs_sha, rootfs_size = sys.argv[1:6]

    env = {}
    with open(os.path.join(out, "toolchain.env"), encoding="utf-8") as fh:
        for line in fh:
            key, _, value = line.rstrip("\n").partition("=")
            if key:
                env[key] = value

    with open(os.path.join(out, "elf-report.json"), encoding="utf-8") as fh:
        elf = json.load(fh)

    created = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    manifest = {
        "schema": 1,
        "pack": "kilocoder-android-toolchain",
        "version": version,
        "created_at": created.isoformat().replace("+00:00", "Z"),
        "source": {
            "commit": os.environ.get("GITHUB_SHA", "local"),
            "run_id": os.environ.get("GITHUB_RUN_ID", ""),
            "run_number": os.environ.get("GITHUB_RUN_NUMBER", ""),
        },
        "base": pin("ROOTFS"),
        "components": {
            "jdk": {
                "version": env.get("JAVA_VERSION", ""),
                "source": "Debian bookworm openjdk-17-jdk-headless",
            },
            "gradle": {"version": env.get("GRADLE_VERSION", ""), **pin("GRADLE")},
            "android_platforms": {
                "35": pin("PLATFORM_35"),
                "36": pin("PLATFORM_36"),
            },
            "build_tools": {"version": "35.0.0", **pin("BUILD_TOOLS")},
            "aapt2": {
                "version": env.get("AAPT2_VERSION", ""),
                "source": "android-sdk-tools 35.0.2, static aarch64",
                **pin("SDK_TOOLS"),
                "load_align": "0x1000",
                "note": "static guest executable; 4-KB p_align is a documented exception",
            },
            "zipalign": {
                "version": "35.0.2",
                "source": "android-sdk-tools 35.0.2, static aarch64",
                **pin("SDK_TOOLS"),
                "load_align": "0x1000",
                "note": "static guest executable; 4-KB p_align is a documented exception",
            },
            "apksigner": {
                "version": env.get("APKSIGNER_VERSION", ""),
                "source": "build-tools 35.0.0 (Java)",
                **pin("BUILD_TOOLS"),
            },
        },
        "tests": env,
        "elf": elf,
        "files": [
            {
                "name": "rootfs.tar.zst",
                "size": int(rootfs_size),
                "sha256": rootfs_sha,
            }
        ],
        "unpacked_bytes": int(unpacked),
    }

    with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2)
        fh.write("\n")


if __name__ == "__main__":
    main()
