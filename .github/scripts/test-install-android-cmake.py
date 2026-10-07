"""Exercise SDK download recovery without accessing a real SDK or network."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("install-android-cmake.sh").resolve()
FAKE_SDKMANAGER = r'''#!/usr/bin/env python3
import os
from pathlib import Path
import sys

root = Path(os.environ["ANDROID_HOME"])
assert sys.argv[1:] == [f"--sdk_root={root}", "--install", "cmake;3.22.1"]
state = Path(os.environ["FIXTURE_STATE"])
count = int(state.read_text()) + 1 if state.exists() else 1
state.write_text(str(count))
target = root / "cmake/3.22.1"
cache = Path.home() / ".android/cache"
assert not target.exists(), "partial installation must be removed before retry"
assert not (root / ".temp").exists(), "SDK download cache must be removed"
assert not cache.exists(), "Android download cache must be removed"
mode = os.environ["FIXTURE_MODE"]
if mode == "always_fail" or (mode == "corrupt_once" and count == 1):
    target.mkdir(parents=True)
    (target / "source.properties").write_text("Pkg.Revision=3.22.1\n")
    for directory in (root / ".temp", cache):
        directory.mkdir(parents=True)
        (directory / "truncated.zip").write_bytes(b"PK partial archive")
    print("Error reading Zip content from a SeekableByteChannel.", file=sys.stderr)
    sys.exit(1)
(target / "bin").mkdir(parents=True)
(target / "source.properties").write_text("Pkg.Revision=3.22.1\n")
if mode == "incomplete_once" and count == 1:
    sys.exit(0)
version = "3.18.1" if mode == "wrong_binary_once" and count == 1 else "3.22.1"
for name, output in (("cmake", f"cmake version {version}"), ("ninja", "1.10.2")):
    binary = target / "bin" / name
    binary.write_text(f"#!/bin/sh\necho '{output}'\n")
    binary.chmod(0o755)
'''


class CMakeInstallTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="android cmake recovery ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.sdk = self.root / "sdk"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        (self.root / "home").mkdir()
        self.env = dict(os.environ, HOME=str(self.root / "home"),
                        ANDROID_HOME=str(self.sdk), ANDROID_CMAKE_VERSION="3.22.1",
                        FIXTURE_STATE=str(self.root / "attempts"), FIXTURE_MODE="success",
                        PATH=f"{self.bin}:{os.environ['PATH']}")
        self.env.pop("ANDROID_SDK_ROOT", None)
        self.write_command("sdkmanager", FAKE_SDKMANAGER)
        self.write_command("sleep", '#!/bin/sh\nprintf "%s\\n" "$1" >> "$HOME/backoffs"\n')
        # An unrelated installed toolchain must survive every recovery attempt.
        self.sentinel = self.sdk / "cmake/3.18.1/keep"
        self.sentinel.parent.mkdir(parents=True)
        self.sentinel.write_text("installed toolchain")

    def write_command(self, name, content):
        command = self.bin / name
        command.write_text(content)
        command.chmod(0o755)

    def run_install(self, mode="success", expected=0):
        self.env["FIXTURE_MODE"] = mode
        result = subprocess.run(["bash", str(SCRIPT)], env=self.env,
                                text=True, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        self.assertEqual(self.sentinel.read_text(), "installed toolchain")
        return result

    def attempts(self):
        return int((self.root / "attempts").read_text())

    def test_corrupted_archive_is_removed_before_successful_retry(self):
        result = self.run_install("corrupt_once")
        self.assertEqual(self.attempts(), 2)
        self.assertIn("Error reading Zip content", result.stderr)
        self.assertEqual((self.root / "home/backoffs").read_text(), "5\n")
        self.assertFalse((self.sdk / ".temp").exists())

    def test_success_exit_with_partial_installation_still_retries(self):
        self.run_install("incomplete_once")
        self.assertEqual(self.attempts(), 2)

    def test_matching_metadata_with_wrong_cmake_binary_still_retries(self):
        self.run_install("wrong_binary_once")
        self.assertEqual(self.attempts(), 2)

    def test_valid_installation_is_reused_without_download_or_cache_deletion(self):
        self.run_install()
        cache = self.sdk / ".temp/keep"
        cache.parent.mkdir()
        cache.write_text("keep")
        result = self.run_install()
        self.assertEqual(self.attempts(), 1)
        self.assertIn("already installed and valid", result.stdout)
        self.assertEqual(cache.read_text(), "keep")

    def test_broken_ninja_in_existing_installation_is_repaired(self):
        self.run_install()
        (self.sdk / "cmake/3.22.1/bin/ninja").write_text("#!/bin/sh\nexit 1\n")
        self.run_install()
        self.assertEqual(self.attempts(), 2)

    def test_persistent_failure_is_bounded_and_leaves_no_partial_installation(self):
        result = self.run_install("always_fail", expected=1)
        self.assertEqual(self.attempts(), 3)
        self.assertIn("after 3 attempts", result.stdout)
        self.assertEqual((self.root / "home/backoffs").read_text(), "5\n10\n")
        self.assertFalse((self.sdk / "cmake/3.22.1").exists())

    def test_timed_out_download_is_retried(self):
        self.write_command("timeout", '''#!/bin/sh
test "$1" = 10m || exit 2
shift
"$@" || exit $?
if [ ! -f "$HOME/timed-out" ]; then
  touch "$HOME/timed-out"
  exit 124
fi
''')
        result = self.run_install()
        self.assertEqual(self.attempts(), 2)
        self.assertIn("sdkmanager exit=124", result.stdout)

    def test_unsafe_sdk_root_or_package_version_fails_before_install(self):
        for changes in ({"ANDROID_SDK_ROOT": "/"},
                        {"ANDROID_SDK_ROOT": "relative"},
                        {"ANDROID_CMAKE_VERSION": "../../other"}):
            with self.subTest(changes=changes):
                env = dict(self.env, **changes)
                result = subprocess.run(["bash", str(SCRIPT)], env=env,
                                        text=True, capture_output=True, timeout=10)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.root / "attempts").exists())
                self.assertTrue(self.sentinel.exists())


if __name__ == "__main__":
    unittest.main()
