"""Portable regular-file archives and final macOS ad-hoc resource sealing.

A ZIP writer must never silently dereference a link after codesign has sealed its
link type. jlink creates legal-document links; materialize those before signing.
Ad-hoc signatures provide integrity only, not publisher identity or notarization.
"""
from __future__ import annotations

import os
from pathlib import Path, PurePosixPath
import platform
import plistlib
import stat
import subprocess
import tempfile
import zipfile

CODESIGN = '/usr/bin/codesign'
# Mach-O (both byte orders), including universal/fat binaries.
MACHO_MAGIC = {bytes.fromhex(value) for value in
               ('feedface', 'cefaedfe', 'feedfacf', 'cffaedfe',
                'cafebabe', 'bebafeca', 'cafebabf', 'bfbafeca')}


def materialize_runtime_legal_links(
        app: Path, legal_relative: str = 'Contents/runtime/Contents/Home/legal') -> int:
    """Replace bounded legal-file links; reject all other or unsafe app links."""
    app = app.resolve(strict=True)
    legal = app / legal_relative
    replacements = []
    for path in sorted(app.rglob('*')):
        if not path.is_symlink():
            continue
        if not path.is_relative_to(legal):
            raise ValueError(f'Unexpected app symlink outside runtime legal directory: {path}')
        target_text = os.readlink(path)
        if Path(target_text).is_absolute():
            raise ValueError(f'Absolute legal symlink is not portable: {path}')
        try:
            target = path.resolve(strict=True)
        except (OSError, RuntimeError) as error:
            raise ValueError(f'Broken or cyclic legal symlink: {path}') from error
        if not target.is_relative_to(legal) or not target.is_file():
            raise ValueError(f'Legal symlink must resolve to a file within runtime legal: {path}')
        # Snapshot before mutating anything, including chains of legal links.
        replacements.append((path, target.read_bytes(), stat.S_IMODE(target.stat().st_mode)))
    for path, contents, mode in replacements:
        descriptor, temporary = tempfile.mkstemp(prefix='.materialized-', dir=path.parent)
        try:
            with os.fdopen(descriptor, 'wb') as output:
                output.write(contents)
            os.chmod(temporary, mode)
            os.replace(temporary, path)
        finally:
            Path(temporary).unlink(missing_ok=True)
    return len(replacements)


def _codesign(arguments: list[str], path: Path) -> None:
    subprocess.run([CODESIGN, *arguments, str(path)], check=True, timeout=90)


def verify_macos_app(app: Path) -> None:
    """Check both nested runtime and app seals before any executable is launched."""
    if platform.system() != 'Darwin':
        raise RuntimeError('macOS codesign verification requires macOS')
    runtime = app / 'Contents/runtime'
    if not runtime.is_dir():
        raise ValueError(f'Missing app runtime bundle: {runtime}')
    for bundle in (runtime, app):
        _codesign(['--verify', '--deep', '--strict', '--verbose=2'], bundle)


def seal_macos_app(app: Path) -> int:
    """Finalize contents first; sign Mach-O leaves, runtime, then outer app."""
    if platform.system() != 'Darwin':
        raise RuntimeError('macOS ad-hoc signing requires macOS')
    count = materialize_runtime_legal_links(app)
    # Explicit inside-out signing avoids codesign's deprecated --deep signing.
    # Preserve any jpackage/JDK entitlements and flags; use no key or timestamp.
    options = ['--force', '--sign', '-', '--timestamp=none',
               '--preserve-metadata=identifier,entitlements,flags,runtime']
    bundles = (app / 'Contents/runtime', app)
    bundle_executables = set()
    for bundle in bundles:
        with (bundle / 'Contents/Info.plist').open('rb') as source:
            executable = plistlib.load(source)['CFBundleExecutable']
        bundle_executables.add(bundle / 'Contents/MacOS' / executable)
    for path in sorted(app.rglob('*')):
        if path.is_file() and path not in bundle_executables:
            with path.open('rb') as source:
                is_macho = source.read(4) in MACHO_MAGIC
            if is_macho:
                _codesign(options, path)
    for bundle in bundles:
        _codesign(options, bundle)
    verify_macos_app(app)
    return count


def add_regular_tree(archive: zipfile.ZipFile, tree: Path, relative_to: Path) -> None:
    """Write only regular files; never flatten links in an already sealed tree."""
    paths = [tree] if not tree.is_dir() else sorted(tree.rglob('*'))
    # Preflight first so unexpected links cannot produce a seemingly valid ZIP.
    for path in paths:
        mode = path.lstat().st_mode
        if not stat.S_ISREG(mode) and not stat.S_ISDIR(mode):
            raise ValueError(f'Portable archive refuses links and special files: {path}')
    for path in paths:
        if path.is_file():
            archive.write(path, path.relative_to(relative_to))


def extract_regular_zip(archive_path: Path, destination: Path) -> None:
    """Extract the portable format with bounds checks and executable modes."""
    destination.mkdir(parents=True, exist_ok=True)
    destination = destination.resolve()
    with zipfile.ZipFile(archive_path) as archive:
        seen = set()
        for entry in archive.infolist():
            member = PurePosixPath(entry.filename)
            mode = entry.external_attr >> 16
            kind = stat.S_IFMT(mode)
            if (member.is_absolute() or '..' in member.parts or not member.parts
                    or '\\' in entry.filename or ':' in entry.filename
                    or tuple(member.parts) in seen
                    or kind not in (0, stat.S_IFREG, stat.S_IFDIR)):
                raise ValueError(f'Unsafe or unsupported ZIP entry: {entry.filename}')
            target = destination.joinpath(*member.parts)
            if not target.resolve().is_relative_to(destination):
                raise ValueError(f'ZIP entry escapes destination: {entry.filename}')
            seen.add(tuple(member.parts))
        for entry in archive.infolist():
            archive.extract(entry, destination)
            if os.name != 'nt':
                mode = (entry.external_attr >> 16) & 0o777
                if mode:
                    (destination / entry.filename).chmod(mode)
