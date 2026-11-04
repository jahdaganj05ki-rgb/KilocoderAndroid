#!/usr/bin/env python3
"""Check every ELF file in the toolchain rootfs for 16-KB LOAD alignment.

The hard gate covers everything Android or PRoot loads as a shared object,
plus the four PRoot libraries. Static guest executables such as aapt2 and
zipalign from android-sdk-tools 35.0.2 are 4-KB aligned; they are reported as
a documented exception instead of silently passing or failing the pack.
"""
from __future__ import annotations

import json
import os
import struct
import sys

REQUIRED_ALIGN = 0x4000
AARCH64 = 183


def elf_info(path: str):
    try:
        with open(path, "rb") as fh:
            header = fh.read(64)
            if len(header) < 64 or header[:4] != b"\x7fELF":
                return None
            if header[4] != 2 or header[5] != 1:
                return {"class": header[4], "machine": None, "type": None, "align": None, "loads": 0}
            e_type = struct.unpack_from("<H", header, 16)[0]
            e_machine = struct.unpack_from("<H", header, 18)[0]
            e_phoff = struct.unpack_from("<Q", header, 32)[0]
            e_phentsize = struct.unpack_from("<H", header, 54)[0]
            e_phnum = struct.unpack_from("<H", header, 56)[0]
            if e_phentsize != 56 or e_phnum == 0:
                return {"class": 2, "machine": e_machine, "type": e_type, "align": None, "loads": 0}
            fh.seek(e_phoff)
            table = fh.read(e_phentsize * e_phnum)
            if len(table) != e_phentsize * e_phnum:
                return {"class": 2, "machine": e_machine, "type": e_type, "align": None, "loads": 0}
            aligns = []
            for i in range(e_phnum):
                off = i * e_phentsize
                p_type = struct.unpack_from("<I", table, off)[0]
                if p_type == 1:
                    aligns.append(struct.unpack_from("<Q", table, off + 48)[0])
            return {
                "class": 2,
                "machine": e_machine,
                "type": e_type,
                "align": min(aligns) if aligns else None,
                "loads": len(aligns),
            }
    except OSError:
        return None


def main() -> int:
    root = os.path.abspath(sys.argv[1])
    proot = os.path.abspath(sys.argv[2])
    text_out = sys.argv[3]
    json_out = sys.argv[4]

    checked = 0
    required = 0
    required_below = []
    static_below = []
    wrong_arch = []
    no_load = []
    min_required = None
    min_static = None

    for base, dirs, names in os.walk(root, followlinks=False):
        dirs[:] = [d for d in dirs if d not in ("proc", "sys", "dev")]
        for name in names:
            path = os.path.join(base, name)
            if os.path.islink(path) or not os.path.isfile(path):
                continue
            info = elf_info(path)
            if info is None:
                continue
            rel = os.path.relpath(path, root)
            checked += 1
            is_proot = path == proot or path.startswith(proot + os.sep)
            is_shared = ".so" in name
            is_required = is_proot or is_shared
            align = info["align"]

            if info["class"] != 2 or info["machine"] != AARCH64:
                wrong_arch.append({"path": rel, "class": info["class"], "machine": info["machine"]})
                if is_required:
                    required += 1
                continue

            if is_required:
                required += 1
                if align is None:
                    no_load.append(rel)
                elif min_required is None or align < min_required:
                    min_required = align
                if align is None or align < REQUIRED_ALIGN:
                    required_below.append({"path": rel, "align": align, "type": info["type"]})

            if not is_required:
                if min_static is None or (align is not None and align < min_static):
                    min_static = align
                if align is not None and align < REQUIRED_ALIGN:
                    static_below.append({"path": rel, "align": align, "type": info["type"]})

    required_below.sort(key=lambda item: item["path"])
    static_below.sort(key=lambda item: item["path"])
    wrong_arch.sort(key=lambda item: item["path"])
    no_load.sort()

    passed = not required_below and not wrong_arch and not no_load
    summary = {
        "checked": checked,
        "required": required,
        "required_align": f"0x{min_required:x}" if min_required is not None else None,
        "static_align": f"0x{min_static:x}" if min_static is not None else None,
        "required_below_16k": required_below,
        "static_below_16k": static_below,
        "wrong_arch": wrong_arch,
        "no_load_segments": no_load,
        "passed": passed,
    }

    lines = [
        f"ELF files: {checked}",
        f"Required (shared libraries and PRoot): {required}",
        f"Minimum required LOAD alignment: {summary['required_align'] or 'none'}",
        f"Static guest executables below 16 KB: {len(static_below)}",
    ]
    for item in static_below:
        lines.append(f"  {item['path']} (0x{item['align']:x}, ET_{'EXEC' if item['type'] == 2 else 'DYN'})")
    if required_below:
        lines.append("Required binaries below 16 KB:")
        for item in required_below:
            align = "no LOAD" if item["align"] is None else f"0x{item['align']:x}"
            lines.append(f"  {item['path']} ({align})")
    if wrong_arch:
        lines.append("Non-aarch64 ELF files:")
        for item in wrong_arch:
            lines.append(f"  {item['path']} (class {item['class']}, machine {item['machine']})")
    if no_load:
        lines.append("Required files without a LOAD segment:")
        for item in no_load:
            lines.append(f"  {item}")
    lines.append("Result: " + ("PASS" if passed else "FAIL"))
    text = "\n".join(lines) + "\n"

    with open(text_out, "w", encoding="utf-8") as fh:
        fh.write(text)
    with open(json_out, "w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=2)
        fh.write("\n")

    sys.stdout.write(text)
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
