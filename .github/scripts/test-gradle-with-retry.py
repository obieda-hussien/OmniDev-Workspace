import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class DependencyRetryTest(unittest.TestCase):
    def run_case(self, failure, succeeds_at=2):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake = root / "gradle.sh"
            fake.write_text('''#!/bin/bash
count=$(cat "$CASE_DIR/count" 2>/dev/null || echo 0)
count=$((count + 1))
echo "$count" > "$CASE_DIR/count"
printf '%s\\n' "$*" >> "$CASE_DIR/args"
if ((count >= SUCCESS_AT)); then exit 0; fi
echo "$FAILURE_TEXT"
exit 17
''')
            env = dict(os.environ, CASE_DIR=str(root), SUCCESS_AT=str(succeeds_at),
                       FAILURE_TEXT=failure, OMNI_GRADLE_EXECUTABLE=str(fake),
                       OMNI_GRADLE_RETRY_DELAY_SECONDS="0")
            result = subprocess.run(["bash", str(Path(__file__).with_name("gradle-with-retry.sh")),
                                     ":app:assembleLiteDebug"], env=env, capture_output=True, text=True)
            return result.returncode, int((root / "count").read_text()), (root / "args").read_text()

    def test_missing_runtime_jar_is_refreshed(self):
        code, count, args = self.run_case("Could not find kotlin-parcelize-runtime-1.9.22.jar")
        self.assertEqual((0, 2), (code, count))
        self.assertIn("--refresh-dependencies :app:assembleLiteDebug", args)

    def test_transport_failure_is_retried(self):
        self.assertEqual((0, 2), self.run_case("Could not GET https://repo.maven.apache.org/file")[0:2])

    def test_compiler_failure_is_not_retried(self):
        self.assertEqual((17, 1), self.run_case("e: Unresolved reference: broken")[0:2])

    def test_ui_test_failure_is_not_retried(self):
        self.assertEqual((17, 1), self.run_case("There were failing tests")[0:2])

    def test_persistent_dependency_failure_returns_original_status(self):
        self.assertEqual((17, 3), self.run_case("Could not find gson-2.8.9.jar", 99)[0:2])


if __name__ == "__main__":
    unittest.main()
