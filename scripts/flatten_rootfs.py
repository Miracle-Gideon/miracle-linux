#!/usr/bin/env python3
"""
flatten_rootfs.py — Miracle Linux's own original tool for working around
Android's exec-from-writable-storage restriction.

Confirmed by direct testing: Android blocks executing ANY file that our app
itself wrote at runtime, regardless of internal vs external storage. The one
place Android still allows execution is nativeLibraryDir (files the SYSTEM
extracts from the APK's own jniLibs at install time).

This script walks a prepared minimal Debian rootfs and finds every file that
actually needs to be executable (binaries + shared libraries — NOT regular
data/config files, which aren't affected by this restriction at all and can
keep extracting normally). Each one gets copied into a flat output folder
with a generated jniLibs-safe name, and a lookup table records the mapping
so the app can create a symlink from the real rootfs path to the correct
file in nativeLibraryDir at runtime.

Usage:
    python3 flatten_rootfs.py <rootfs_path> <output_libs_dir> <lookup_table_path>

Example:
    python3 flatten_rootfs.py \\
        "$(find $PREFIX/var/lib/proot-distro -type d -name miracle-base)/rootfs" \\
        ./flattened_libs \\
        ./rootfs_exec_map.tsv
"""
import os
import re
import shutil
import stat
import sys

EXEC_BITS = stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH
SO_PATTERN = re.compile(r"\.so(\.\d+)*$")


def needs_flattening(path: str, mode: int) -> bool:
    """A file needs flattening if it's executable OR a shared library
    (many .so files aren't individually +x but still get loaded/executed
    as code by the dynamic linker, so they hit the same restriction)."""
    if mode & EXEC_BITS:
        return True
    if SO_PATTERN.search(os.path.basename(path)):
        return True
    return False


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(1)

    rootfs_path, output_dir, lookup_path = sys.argv[1:4]
    os.makedirs(output_dir, exist_ok=True)

    count = 0
    with open(lookup_path, "w") as lookup_file:
        for dirpath, _dirnames, filenames in os.walk(rootfs_path):
            for filename in filenames:
                full_path = os.path.join(dirpath, filename)

                # Skip symlinks — we only care about REAL file content here;
                # symlinks get recreated normally at extraction time and will
                # correctly point at whatever their target resolves to.
                if os.path.islink(full_path):
                    continue

                try:
                    file_mode = os.stat(full_path).st_mode
                except OSError:
                    continue  # unreadable file, skip rather than crash the whole run

                if not needs_flattening(full_path, file_mode):
                    continue

                relative_path = os.path.relpath(full_path, rootfs_path)
                generated_name = f"libflat{count:05d}.so"
                count += 1

                shutil.copy2(full_path, os.path.join(output_dir, generated_name))
                lookup_file.write(f"{relative_path}\t{generated_name}\n")

    print(f"Flattened {count} executable/library files.")
    print(f"Copies written to: {output_dir}")
    print(f"Lookup table written to: {lookup_path}")


if __name__ == "__main__":
    main()
