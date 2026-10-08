"""Regression tests for ZIP type changes and macOS sealed-resource integrity."""
import errno
import os
from pathlib import Path
import platform
import plistlib
import shutil
import stat
import subprocess
import tempfile
import unittest
import zipfile

from package_integrity import (CODESIGN, add_regular_tree, extract_regular_zip,
                               materialize_runtime_legal_links, seal_macos_app,
                               verify_macos_app)


class PackageFixture:
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='annotrail-package-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.app = self.root / 'Example.app'
        self.legal = self.app / 'Contents/runtime/Contents/Home/legal'
        self.base = self.legal / 'java.base/LICENSE'
        self.base.parent.mkdir(parents=True)
        self.base.write_bytes(b'Synthetic license fixture\n')

    def link(self, path, target):
        path.parent.mkdir(parents=True, exist_ok=True)
        try:
            # Windows stores the target text verbatim; forward slashes create
            # a reparse point which later fails strict resolve with WinError 123.
            native_target = str(Path(target))
            path.symlink_to(native_target, target_is_directory=(path.parent / native_target).is_dir())
        except OSError as error:
            if getattr(error, 'winerror', None) == 1314 or error.errno in (errno.ENOSYS, errno.ENOTSUP):
                self.skipTest(f'This host does not permit test symlinks: {error}')
            raise


class PortableArchiveTests(PackageFixture, unittest.TestCase):
    def test_materializes_relative_and_chained_legal_links(self):
        one = self.legal / 'java.desktop/LICENSE'
        two = self.legal / 'java.xml/LICENSE'
        self.link(one, '../java.base/LICENSE')
        self.link(two, '../java.desktop/LICENSE')
        self.assertEqual(materialize_runtime_legal_links(self.app), 2)
        for path in (one, two):
            self.assertFalse(path.is_symlink())
            self.assertEqual(path.read_bytes(), self.base.read_bytes())
        self.assertEqual(materialize_runtime_legal_links(self.app), 0)

    def test_all_links_validated_before_any_mutation(self):
        safe = self.legal / 'a/LICENSE'
        unsafe = self.legal / 'z/LICENSE'
        self.link(safe, '../java.base/LICENSE')
        self.link(unsafe, '../missing/LICENSE')
        with self.assertRaises(ValueError):
            materialize_runtime_legal_links(self.app)
        self.assertTrue(safe.is_symlink())
        self.assertTrue(unsafe.is_symlink())

    def test_rejects_absolute_external_directory_and_cyclic_links(self):
        outside = self.root / 'outside.txt'
        outside.write_text('synthetic sentinel')
        path = self.legal / 'module/LICENSE'
        for target in (str(self.base), os.path.relpath(outside, path.parent), '../java.base', 'LICENSE'):
            with self.subTest(target=target):
                self.link(path, target)
                with self.assertRaises(ValueError):
                    materialize_runtime_legal_links(self.app)
                self.assertTrue(path.is_symlink())
                path.unlink()
        self.assertEqual(outside.read_text(), 'synthetic sentinel')

    def test_rejects_unexpected_app_link(self):
        self.link(self.app / 'Contents/unexpected', 'runtime/Contents/Home/legal/java.base/LICENSE')
        with self.assertRaises(ValueError):
            materialize_runtime_legal_links(self.app)

    def test_linux_runtime_legal_layout(self):
        app = self.root / 'LinuxApp'
        target = app / 'lib/runtime/legal/java.base/LICENSE'
        target.parent.mkdir(parents=True)
        target.write_text('synthetic Linux fixture')
        alias = app / 'lib/runtime/legal/java.xml/LICENSE'
        self.link(alias, '../java.base/LICENSE')
        self.assertEqual(materialize_runtime_legal_links(app, 'lib/runtime/legal'), 1)
        self.assertEqual(alias.read_text(), target.read_text())
        self.assertFalse(alias.is_symlink())

    def test_windows_runtime_legal_layout(self):
        app = self.root / 'WindowsApp'
        target = app / 'runtime/legal/java.base/LICENSE'
        target.parent.mkdir(parents=True)
        target.write_text('synthetic Windows fixture')
        alias = app / 'runtime/legal/java.xml/LICENSE'
        self.link(alias, '../java.base/LICENSE')
        self.assertEqual(materialize_runtime_legal_links(app, 'runtime/legal'), 1)
        self.assertEqual(alias.read_text(), target.read_text())
        self.assertFalse(alias.is_symlink())

    def test_archive_refuses_link_in_sealed_tree(self):
        self.link(self.legal / 'java.desktop/LICENSE', '../java.base/LICENSE')
        archive = self.root / 'refused.zip'
        with zipfile.ZipFile(archive, 'w') as output:
            with self.assertRaises(ValueError):
                add_regular_tree(output, self.app, self.root)
            self.assertEqual(output.namelist(), [])

    def test_regular_zip_preserves_contents_and_executable_mode(self):
        executable = self.app / 'launcher'
        executable.write_bytes(b'synthetic launcher')
        executable.chmod(0o755)
        archive = self.root / 'regular.zip'
        with zipfile.ZipFile(archive, 'w') as output:
            add_regular_tree(output, self.app, self.root)
        destination = self.root / 'extracted'
        extract_regular_zip(archive, destination)
        self.assertEqual((destination / 'Example.app/launcher').read_bytes(), executable.read_bytes())
        if os.name != 'nt':
            self.assertEqual(stat.S_IMODE((destination / 'Example.app/launcher').stat().st_mode), 0o755)

    def test_extract_rejects_paths_and_symlinks(self):
        for index, name in enumerate(('../escape', '/absolute', 'C:/drive', 'legal-link')):
            with self.subTest(name=name):
                archive = self.root / f'unsafe-{index}.zip'
                entry = zipfile.ZipInfo(name)
                if name == 'legal-link':
                    entry.create_system = 3
                    entry.external_attr = (stat.S_IFLNK | 0o777) << 16
                with zipfile.ZipFile(archive, 'w') as output:
                    output.writestr(entry, b'synthetic')
                with self.assertRaises(ValueError):
                    extract_regular_zip(archive, self.root / f'unsafe-{index}')

    def test_extract_rejects_raw_backslash_and_nul_names(self):
        # Construct valid bytes first, then patch both directory/header names.
        # ZipInfo would sanitize a malformed name before writing on Windows.
        for index, raw_name in enumerate((b'a\\b', b'a\x00b')):
            with self.subTest(raw_name=raw_name):
                archive = self.root / f'raw-name-{index}.zip'
                with zipfile.ZipFile(archive, 'w') as output:
                    output.writestr('a/b', b'synthetic')
                contents = archive.read_bytes()
                self.assertEqual(contents.count(b'a/b'), 2)
                archive.write_bytes(contents.replace(b'a/b', raw_name))
                with zipfile.ZipFile(archive) as source:
                    self.assertEqual(source.infolist()[0].orig_filename, raw_name.decode('ascii'))
                destination = self.root / f'raw-name-{index}'
                with self.assertRaises(ValueError):
                    extract_regular_zip(archive, destination)
                self.assertEqual(list(destination.iterdir()), [])

    def test_extract_rejects_normalized_duplicate(self):
        archive = self.root / 'duplicate.zip'
        with zipfile.ZipFile(archive, 'w') as output:
            output.writestr('a/b', b'first')
            output.writestr('a/./b', b'second')
        with self.assertRaises(ValueError):
            extract_regular_zip(archive, self.root / 'duplicate')


@unittest.skipUnless(platform.system() == 'Darwin', 'macOS codesign integration requires macOS')
class MacSealTests(PackageFixture, unittest.TestCase):
    def setUp(self):
        super().setUp()
        runtime = self.app / 'Contents/runtime'
        for bundle, name, kind in ((self.app, 'Example', 'APPL'), (runtime, 'libjli.dylib', 'BNDL')):
            executable = bundle / 'Contents/MacOS' / name
            executable.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile('/usr/bin/true', executable)
            executable.chmod(0o755)
            with (bundle / 'Contents/Info.plist').open('wb') as output:
                plistlib.dump({'CFBundleExecutable': name, 'CFBundleIdentifier': 'net.whago.annotrail.package-test.' + kind,
                              'CFBundlePackageType': kind, 'CFBundleVersion': '1'}, output)
        self.link(self.legal / 'java.desktop/LICENSE', '../java.base/LICENSE')
        self.payload = self.app / 'Contents/app/payload.jar'
        self.payload.parent.mkdir()
        self.payload.write_bytes(b'synthetic app payload')

    def test_fixed_roundtrip_rejects_legal_and_jar_tampering(self):
        self.assertEqual(seal_macos_app(self.app), 1)
        archive = self.root / 'fixed.zip'
        with zipfile.ZipFile(archive, 'w') as output:
            add_regular_tree(output, self.app, self.root)
        destination = self.root / 'fixed'
        extract_regular_zip(archive, destination)
        extracted = destination / self.app.name
        verify_macos_app(extracted)
        license_file = extracted / 'Contents/runtime/Contents/Home/legal/java.desktop/LICENSE'
        original = license_file.read_bytes()
        license_file.write_bytes(original + b'tampered')
        for bundle in (extracted / 'Contents/runtime', extracted):
            with self.subTest(bundle=str(bundle)):
                result = subprocess.run([CODESIGN, '--verify', '--deep', '--strict', str(bundle)], capture_output=True, timeout=30)
                self.assertNotEqual(result.returncode, 0, 'Tampered legal resource passed strict verification')
        license_file.write_bytes(original)
        verify_macos_app(extracted)
        (extracted / 'Contents/app/payload.jar').write_bytes(b'tampered app payload')
        with self.assertRaises(subprocess.CalledProcessError):
            verify_macos_app(extracted)

    def test_old_postseal_zip_flattening_is_detected(self):
        for bundle in (self.app / 'Contents/runtime', self.app):
            subprocess.run([CODESIGN, '--force', '--sign', '-', '--timestamp=none', str(bundle)], check=True, timeout=30)
        verify_macos_app(self.app)
        archive = self.root / 'old-broken.zip'
        with zipfile.ZipFile(archive, 'w') as output:
            for path in self.app.rglob('*'):
                if path.is_file():
                    output.write(path, path.relative_to(self.root))
        destination = self.root / 'old-broken'
        extract_regular_zip(archive, destination)
        with self.assertRaises(subprocess.CalledProcessError):
            verify_macos_app(destination / self.app.name)


if __name__ == '__main__':
    unittest.main()
