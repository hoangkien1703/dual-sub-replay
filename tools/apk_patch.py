#!/usr/bin/env python3
"""Small update patches between two APKs (owner pick 2026-11-08).

Between releases most of an APK stays byte for byte the same: the native libraries and data files
keep their compressed bytes, and only classes.dex and a few small files change. A patch lists the
new APK as pieces copied from the installed APK plus the new bytes, so a phone downloads about
2.7 MB instead of the whole 40 MB file. The app (`data/UpdatePatches.kt`) rebuilds the new APK from
its own installed copy and still checks the result against the release's SHA-256.

Format (all numbers big-endian):

    "DSRPATCH" 1                       magic and format version
    old size (8) old SHA-256 (32)      the APK the patch starts from
    new size (8) new SHA-256 (32)      the APK it makes
    zlib stream of operations:
      1, offset (8), length (8)        copy bytes from the old APK
      2, length (4), bytes             new bytes
      0                                end
"""

import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import zipfile
import zlib


MAGIC = b"DSRPATCH"
VERSION = 1
COPY = 1
DATA = 2
END = 0
# Pieces shorter than this are cheaper as new bytes than as a copy.
MIN_COPY = 64
# Never more new bytes in one operation than this; the app reads them in one buffer.
MAX_DATA = 1 << 20
# The app refuses a patch that would make a bigger APK (MAX_UPDATE_BYTES in AppUpdates.kt).
MAX_APK_BYTES = 150 * 1024 * 1024


def _entry_ranges(data):
    """Each entry's compressed bytes in [data]: {name: (start, length, crc, compress_type)}."""
    ranges = {}
    try:
        archive = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        return ranges  # Not an archive: the whole file goes as new bytes.
    with archive:
        for info in archive.infolist():
            header = info.header_offset
            if data[header:header + 4] != b"PK\x03\x04":
                continue
            name_length, extra_length = struct.unpack("<HH", data[header + 26:header + 30])
            start = header + 30 + name_length + extra_length
            ranges[info.filename] = (start, info.compress_size, info.CRC, info.compress_type)
    return ranges


def copy_plan(old, new):
    """Sorted (new_start, old_start, length) pieces of [new] that are the same bytes in [old]."""
    old_ranges = _entry_ranges(old)
    plan = []
    for name, (start, length, crc, kind) in _entry_ranges(new).items():
        match = old_ranges.get(name)
        if match is None or length < MIN_COPY:
            continue
        old_start, old_length, old_crc, old_kind = match
        if (old_length, old_crc, old_kind) != (length, crc, kind):
            continue
        if old[old_start:old_start + length] != new[start:start + length]:
            continue
        plan.append((start, old_start, length))
    plan.sort()
    # Entries never overlap in a valid archive; drop any piece that would.
    result, end = [], 0
    for piece in plan:
        if piece[0] >= end:
            result.append(piece)
            end = piece[0] + piece[2]
    return result


def make_patch(old, new):
    """The patch that turns [old] into [new]."""
    if len(new) > MAX_APK_BYTES:
        raise ValueError("The new APK is too big for the app to accept")
    ops = bytearray()

    def data(start, end):
        while start < end:
            chunk = new[start:min(end, start + MAX_DATA)]
            ops.extend(struct.pack(">BI", DATA, len(chunk)))
            ops.extend(chunk)
            start += len(chunk)

    position = 0
    for new_start, old_start, length in copy_plan(old, new):
        data(position, new_start)
        ops.extend(struct.pack(">BQQ", COPY, old_start, length))
        position = new_start + length
    data(position, len(new))
    ops.append(END)
    header = (MAGIC + bytes([VERSION])
              + struct.pack(">Q", len(old)) + hashlib.sha256(old).digest()
              + struct.pack(">Q", len(new)) + hashlib.sha256(new).digest())
    return header + zlib.compress(bytes(ops), 9)


def apply_patch(old, patch):
    """The APK [patch] makes from [old]; raises ValueError when it does not fit [old] or is damaged."""
    if patch[:8] != MAGIC or patch[8] != VERSION:
        raise ValueError("Not an update patch")
    old_size, = struct.unpack(">Q", patch[9:17])
    old_hash = patch[17:49]
    new_size, = struct.unpack(">Q", patch[49:57])
    new_hash = patch[57:89]
    if len(old) != old_size or hashlib.sha256(old).digest() != old_hash:
        raise ValueError("The patch starts from another APK")
    ops = zlib.decompress(patch[89:])
    out = bytearray()
    index = 0
    while True:
        kind = ops[index]
        index += 1
        if kind == END:
            break
        if kind == COPY:
            offset, length = struct.unpack(">QQ", ops[index:index + 16])
            index += 16
            if offset + length > len(old):
                raise ValueError("The patch copies past the end of the old APK")
            out.extend(old[offset:offset + length])
        elif kind == DATA:
            length, = struct.unpack(">I", ops[index:index + 4])
            index += 4
            out.extend(ops[index:index + length])
            index += length
        else:
            raise ValueError("Unknown patch operation")
        if len(out) > new_size:
            raise ValueError("The patch makes a bigger APK than it says")
    if len(out) != new_size or hashlib.sha256(out).digest() != new_hash:
        raise ValueError("The patched APK is not the one the patch names")
    return bytes(out)


def patch_name(version):
    """The release asset of the patch from [version] (`update-from-v1.8.24.patch`)."""
    return f"update-from-v{version}.patch"


def make_release_patches(new_apk, old_dir, out_dir):
    """Patches from each `<old_dir>/<version>/` (its DualSub-Replay.apk and update-info.json) to [new_apk].

    Returns the `patches` entries for the new release's update-info.json, newest first. An old
    release whose APK does not match its own update-info.json is left out.
    """
    new = Path(new_apk).read_bytes()
    entries = []
    for folder in sorted(Path(old_dir).iterdir()):
        info_file, apk_file = folder / "update-info.json", folder / "DualSub-Replay.apk"
        if not info_file.is_file() or not apk_file.is_file():
            continue
        info = json.loads(info_file.read_text())
        old = apk_file.read_bytes()
        if hashlib.sha256(old).hexdigest() != info.get("sha256") or not info.get("version_code"):
            print(f"Skipping {folder.name}: its APK does not match its update-info.json")
            continue
        patch = make_patch(old, new)
        # A patch that saves little is not worth the extra step on the phone.
        if len(patch) > len(new) // 2:
            print(f"Skipping {folder.name}: the patch saves too little ({len(patch):,} bytes)")
            continue
        apply_patch(old, patch)
        name = patch_name(info["version"])
        Path(out_dir, name).write_bytes(patch)
        print(f"{name}: {len(patch):,} bytes instead of {len(new):,}")
        entries.append({"from_version_code": int(info["version_code"]), "from_sha256": info["sha256"],
                        "name": name, "sha256": hashlib.sha256(patch).hexdigest(), "size": len(patch)})
    entries.sort(key=lambda entry: entry["from_version_code"], reverse=True)
    return entries


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="action", required=True)
    make = sub.add_parser("make", help="make one patch")
    make.add_argument("old")
    make.add_argument("new")
    make.add_argument("patch")
    apply = sub.add_parser("apply", help="apply one patch (for checks)")
    apply.add_argument("old")
    apply.add_argument("patch")
    apply.add_argument("new")
    release = sub.add_parser("release", help="patches from earlier releases, as update-info.json entries")
    release.add_argument("--apk", required=True)
    release.add_argument("--old-releases", required=True)
    release.add_argument("--output-dir", required=True)
    release.add_argument("--entries", required=True, help="JSON file for update_info's patches")
    args = parser.parse_args()
    if args.action == "make":
        Path(args.patch).write_bytes(make_patch(Path(args.old).read_bytes(), Path(args.new).read_bytes()))
    elif args.action == "apply":
        Path(args.new).write_bytes(apply_patch(Path(args.old).read_bytes(), Path(args.patch).read_bytes()))
    else:
        Path(args.output_dir).mkdir(parents=True, exist_ok=True)
        entries = make_release_patches(args.apk, args.old_releases, args.output_dir)
        Path(args.entries).write_text(json.dumps(entries, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
