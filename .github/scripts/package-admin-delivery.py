#!/usr/bin/env python3
"""Select a single Telegram document without modifying or splitting the APK."""

import hashlib
import os
import sys
import zipfile
from pathlib import Path


TELEGRAM_UPLOAD_LIMIT = 50_000_000


def sha256(stream):
    digest = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(chunk)
    return digest.hexdigest()


def package_delivery(apk, filename, output_dir, expected_sha256,
                     limit=TELEGRAM_UPLOAD_LIMIT):
    apk = Path(apk)
    if Path(filename).name != filename or not filename.endswith(".apk"):
        raise ValueError("Delivery filename must be a plain .apk filename")
    with apk.open("rb") as source:
        if sha256(source) != expected_sha256:
            raise ValueError("Admin APK changed after signature/output verification")

    if apk.stat().st_size <= limit:
        return apk, filename, "application/vnd.android.package-archive", "APK"

    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    archive = output_dir / (Path(filename).stem + ".zip")
    try:
        # The outer ZIP compresses stored native libraries and alignment padding.
        # Never rewrite the APK's internal ZIP: that would invalidate signing.
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED,
                             compresslevel=9) as zipped:
            zipped.write(apk, arcname=filename)
        with zipfile.ZipFile(archive) as zipped:
            if zipped.namelist() != [filename]:
                raise ValueError("Admin delivery ZIP must contain exactly one APK")
            with zipped.open(filename) as extracted:
                if sha256(extracted) != expected_sha256:
                    raise ValueError("Admin delivery ZIP failed APK integrity verification")
        compressed_size = archive.stat().st_size
        if compressed_size > limit:
            raise ValueError(
                f"Admin APK is {apk.stat().st_size} bytes; its complete ZIP is "
                f"{compressed_size} bytes, still above Telegram's {limit}-byte limit. "
                "A ZIP cannot bypass this limit. No parts or public artifact were created."
            )
    except Exception:
        archive.unlink(missing_ok=True)
        raise
    return archive, archive.name, "application/zip", "ZIP - extract the APK first"


def main():
    try:
        apk = Path(os.environ["ADMIN_APK"])
        delivery, filename, mime, note = package_delivery(
            apk, os.environ["ADMIN_APK_FILENAME"],
            Path(os.environ["RUNNER_TEMP"]) / "admin-delivery",
            os.environ["ADMIN_APK_SHA256"],
        )
        with delivery.open("rb") as stream:
            delivery_sha256 = sha256(stream)
        values = {
            "ADMIN_DELIVERY_FILE": str(delivery),
            "ADMIN_DELIVERY_FILENAME": filename,
            "ADMIN_DELIVERY_MIME": mime,
            "ADMIN_DELIVERY_FORMAT": note,
            "ADMIN_DELIVERY_SHA256": delivery_sha256,
        }
        if any("\n" in value or "\r" in value for value in values.values()):
            raise ValueError("Invalid newline in delivery metadata")
        with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as env:
            for key, value in values.items():
                env.write(f"{key}={value}\n")
        print(f"Admin APK: {apk.stat().st_size} bytes; "
              f"delivery {filename}: {delivery.stat().st_size} bytes")
        print(f"Delivery format: {note}; APK SHA-256 preserved")
    except (KeyError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
