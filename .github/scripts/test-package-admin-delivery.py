#!/usr/bin/env python3
"""Exercise upload-size boundaries and byte-for-byte ZIP delivery integrity."""

import hashlib
import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path


spec = importlib.util.spec_from_file_location(
    "delivery", Path(__file__).with_name("package-admin-delivery.py")
)
delivery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delivery)


class DeliveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "original-unsigned.apk"
        self.filename = "OmniDev-admin-release-unsigned.apk"

    def package(self, content, limit=4096, filename=None):
        self.apk.write_bytes(content)
        return delivery.package_delivery(
            self.apk, filename or self.filename, self.root / "output",
            hashlib.sha256(content).hexdigest(), limit=limit,
        )

    def test_small_and_exact_limit_apks_are_sent_directly(self):
        for size in (1, 4096):
            with self.subTest(size=size):
                path, name, mime, note = self.package(b"x" * size)
                self.assertEqual(path, self.apk)
                self.assertEqual(name, self.filename)
                self.assertEqual(mime, "application/vnd.android.package-archive")
                self.assertEqual(note, "APK")
                self.assertFalse((self.root / "output").exists())

    def test_oversized_apk_becomes_one_zip_with_identical_apk(self):
        content = b"unaltered APK including its signature\0" * 2048
        for filename in (self.filename, "OmniDev-admin-release.apk"):
            with self.subTest(filename=filename):
                path, name, mime, note = self.package(content, filename=filename)
                self.assertEqual(name, filename.removesuffix(".apk") + ".zip")
                self.assertEqual(mime, "application/zip")
                self.assertIn("extract", note)
                self.assertLessEqual(path.stat().st_size, 4096)
                with zipfile.ZipFile(path) as zipped:
                    self.assertEqual(zipped.namelist(), [filename])
                    self.assertEqual(zipped.read(filename), content)
                self.assertEqual(self.apk.read_bytes(), content)

    def test_uncompressible_oversized_apk_is_not_split_or_published(self):
        # Independent SHA digests provide deterministic hard-to-compress data.
        content = b"".join(hashlib.sha256(str(n).encode()).digest()
                           for n in range(1024))
        with self.assertRaisesRegex(ValueError, "still above Telegram"):
            self.package(content)
        self.assertEqual(list((self.root / "output").iterdir()), [])
        self.assertEqual(self.apk.read_bytes(), content)

    def test_changed_apk_is_rejected_before_packaging(self):
        self.apk.write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "changed after"):
            delivery.package_delivery(self.apk, self.filename, self.root / "output",
                                      hashlib.sha256(b"verified").hexdigest())
        self.assertFalse((self.root / "output").exists())

    def test_archive_cannot_have_an_escaping_entry(self):
        with self.assertRaisesRegex(ValueError, "plain .apk"):
            self.package(b"x" * 8192, filename="../escaped.apk")


if __name__ == "__main__":
    unittest.main()
