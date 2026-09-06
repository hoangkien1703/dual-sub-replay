#!/usr/bin/env python3
"""
Inspect and report on DualSub Replay APK size, structure, permissions, and forbidden components.
Enforces size limits (hard cap 80MB, stretch target 40MB) and asserts absence of forbidden components.
"""

import argparse
import io
import json
import os
import struct
import sys
import zipfile

FORBIDDEN_PERMISSIONS = {
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.WAKE_LOCK",
    "android.permission.RECEIVE_BOOT_COMPLETED",
}

FORBIDDEN_PATH_SUBSTRINGS = [
    "libffmpeg",
    "libpython",
    "youtubedl",
    "media3",
]


def parse_axml_strings_and_permissions(raw_bytes: bytes):
    """
    Lightweight parser for Android binary XML (AXML) to extract strings and permissions.
    """
    permissions = set()
    package_name = None
    version_name = None
    version_code = None

    # Simple string table extraction from binary XML
    try:
        if len(raw_bytes) < 8:
            return package_name, version_code, version_name, permissions
        magic, file_size = struct.unpack("<II", raw_bytes[:8])
        if magic != 0x00080003:
            return package_name, version_code, version_name, permissions

        offset = 8
        strings = []
        while offset < len(raw_bytes):
            chunk_type, chunk_size = struct.unpack("<II", raw_bytes[offset : offset + 8])
            if chunk_type == 0x001C0001:  # String pool chunk
                string_count, style_count, flags, strings_start, styles_start = struct.unpack(
                    "<IIIII", raw_bytes[offset + 8 : offset + 28]
                )
                is_utf8 = bool(flags & (1 << 8))
                offsets = struct.unpack(
                    f"<{string_count}I",
                    raw_bytes[offset + 28 : offset + 28 + string_count * 4],
                )
                data_start = offset + strings_start
                for str_off in offsets:
                    pos = data_start + str_off
                    if is_utf8:
                        # UTF-8: length prefix could be 1 or 2 bytes
                        u16len = raw_bytes[pos]
                        pos += 1
                        if u16len & 0x80:
                            pos += 1
                        u8len = raw_bytes[pos]
                        pos += 1
                        if u8len & 0x80:
                            pos += 1
                        s = raw_bytes[pos : pos + u8len].decode("utf-8", errors="replace")
                    else:
                        # UTF-16
                        u16len = struct.unpack("<H", raw_bytes[pos : pos + 2])[0]
                        pos += 2
                        if u16len & 0x8000:
                            pos += 2
                        s = raw_bytes[pos : pos + u16len * 2].decode("utf-16le", errors="replace")
                    strings.append(s)
                break
            offset += chunk_size

        for s in strings:
            if "permission." in s or s.endswith("_PERMISSION"):
                permissions.add(s)
            if s.startswith("com.kienhoang.dualsubreplay"):
                if package_name is None:
                    package_name = s
    except Exception as e:
        print(f"Warning: AXML string parsing encountered: {e}", file=sys.stderr)

    return package_name, version_code, version_name, permissions


def inspect_apk(apk_path: str, max_bytes: int, stretch_bytes: int):
    if not os.path.isfile(apk_path):
        raise FileNotFoundError(f"APK not found: {apk_path}")

    total_bytes = os.path.getsize(apk_path)
    dex_compressed = 0
    res_compressed = 0
    assets_compressed = 0
    abi_compressed = {}
    largest_entries = []
    forbidden_components_found = []

    with zipfile.ZipFile(apk_path, "r") as zf:
        infolist = zf.infolist()
        for info in infolist:
            name = info.filename
            comp_size = info.compress_size
            largest_entries.append((name, comp_size, info.file_size))

            if name.endswith(".dex"):
                dex_compressed += comp_size
            elif name == "resources.arsc" or name.startswith("res/"):
                res_compressed += comp_size
            elif name.startswith("assets/"):
                assets_compressed += comp_size
            elif name.startswith("lib/"):
                parts = name.split("/")
                if len(parts) >= 2:
                    abi = parts[1]
                    abi_compressed[abi] = abi_compressed.get(abi, 0) + comp_size

            for forbidden in FORBIDDEN_PATH_SUBSTRINGS:
                if forbidden in name:
                    forbidden_components_found.append(f"{forbidden} matched by {name}")

        manifest_raw = zf.read("AndroidManifest.xml")
        package_name, version_code, version_name, permissions = parse_axml_strings_and_permissions(manifest_raw)

    largest_entries.sort(key=lambda x: x[1], reverse=True)
    top_10 = largest_entries[:10]

    forbidden_perms_found = [p for p in permissions if p in FORBIDDEN_PERMISSIONS]

    report = {
        "apk_path": apk_path,
        "total_bytes": total_bytes,
        "total_mb": round(total_bytes / (1024 * 1024), 2),
        "max_bytes_limit": max_bytes,
        "stretch_target_bytes": stretch_bytes,
        "below_limit": total_bytes <= max_bytes,
        "below_stretch_target": total_bytes <= stretch_bytes,
        "compressed_breakdown": {
            "dex_bytes": dex_compressed,
            "resources_bytes": res_compressed,
            "assets_bytes": assets_compressed,
            "per_abi_bytes": abi_compressed,
        },
        "top_10_largest_entries": [
            {"path": name, "compressed_bytes": c_size, "uncompressed_bytes": u_size}
            for name, c_size, u_size in top_10
        ],
        "package_name": package_name,
        "permissions": sorted(list(permissions)),
        "forbidden_permissions_found": forbidden_perms_found,
        "forbidden_components_found": forbidden_components_found,
    }

    return report


def main():
    parser = argparse.ArgumentParser(description="Report and verify DualSub Replay APK")
    parser.add_argument("apk", help="Path to APK file")
    parser.add_argument("--max-bytes", type=int, default=80_000_000, help="Hard size limit in bytes (default 80MB)")
    parser.add_argument("--stretch-bytes", type=int, default=40_000_000, help="Stretch target in bytes (default 40MB)")
    parser.add_argument("--output-json", help="Optional path to write JSON report")

    args = parser.parse_args()
    report = inspect_apk(args.apk, args.max_bytes, args.stretch_bytes)

    print("=" * 60)
    print("DualSub Replay APK Inspection Report")
    print("=" * 60)
    print(f"APK:                 {report['apk_path']}")
    print(f"Total Size:          {report['total_bytes']:,} bytes ({report['total_mb']} MB)")
    print(f"Limit (80 MB):       {'PASS' if report['below_limit'] else 'FAIL'}")
    print(f"Stretch Target (40MB):{'PASS' if report['below_stretch_target'] else 'EXCEEDED'}")
    print("\nCompressed Size Breakdown:")
    print(f"  DEX:               {report['compressed_breakdown']['dex_bytes']:,} bytes")
    print(f"  Resources:         {report['compressed_breakdown']['resources_bytes']:,} bytes")
    print(f"  Assets:            {report['compressed_breakdown']['assets_bytes']:,} bytes")
    print("  Per-ABI native libs:")
    for abi, size in sorted(report['compressed_breakdown']['per_abi_bytes'].items()):
        print(f"    {abi:16} {size:,} bytes")

    print("\nTop 5 Largest Entries:")
    for entry in report["top_10_largest_entries"][:5]:
        print(f"  {entry['compressed_bytes']:>10,} B  {entry['path']}")

    print("\nDetected Permissions:")
    for p in report["permissions"]:
        print(f"  - {p}")

    print("-" * 60)
    has_errors = False
    if not report["below_limit"]:
        print(f"ERROR: APK size {report['total_bytes']} exceeds limit of {args.max_bytes} bytes!", file=sys.stderr)
        has_errors = True

    if report["forbidden_permissions_found"]:
        print(f"ERROR: Forbidden permissions found: {report['forbidden_permissions_found']}", file=sys.stderr)
        has_errors = True

    if report["forbidden_components_found"]:
        print(f"ERROR: Forbidden components found: {report['forbidden_components_found']}", file=sys.stderr)
        has_errors = True

    if not has_errors:
        print("ALL VERIFICATION CHECKS PASSED: No forbidden permissions or components detected.")

    if args.output_json:
        with open(args.output_json, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
        print(f"\nWrote report to {args.output_json}")

    print("=" * 60)
    sys.exit(1 if has_errors else 0)


if __name__ == "__main__":
    main()
