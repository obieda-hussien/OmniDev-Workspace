import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class DependencyRetryTest(unittest.TestCase):
    def run_case(self, failure, succeeds_at=2, second_failure=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake = root / "gradle.sh"
            fake.write_text('''#!/bin/bash
count=$(cat "$CASE_DIR/count" 2>/dev/null || echo 0)
count=$((count + 1))
echo "$count" > "$CASE_DIR/count"
printf '%s\\n' "$*" >> "$CASE_DIR/args"
printf '%s\\n' "$OMNI_MAVEN_CENTRAL_FALLBACK" >> "$CASE_DIR/central"
if ((count >= SUCCESS_AT)); then exit 0; fi
if ((count == 2)) && [[ -n "$SECOND_FAILURE_TEXT" ]]; then
  echo "$SECOND_FAILURE_TEXT"
else
  echo "$FAILURE_TEXT"
fi
exit 17
''')
            env = dict(os.environ, CASE_DIR=str(root), SUCCESS_AT=str(succeeds_at),
                       FAILURE_TEXT=failure, SECOND_FAILURE_TEXT=second_failure or "", OMNI_GRADLE_EXECUTABLE=str(fake),
                       OMNI_GRADLE_RETRY_DELAY_SECONDS="0", OMNI_MAVEN_CENTRAL_FALLBACK="0")
            result = subprocess.run(["bash", str(Path(__file__).with_name("gradle-with-retry.sh")),
                                     ":app:assembleLiteDebug"], env=env, capture_output=True, text=True)
            return (result.returncode, int((root / "count").read_text()),
                    (root / "args").read_text(), (root / "central").read_text().splitlines())

    def test_first_missing_runtime_jar_retry_keeps_cache(self):
        code, count, args, endpoints = self.run_case("Could not find kotlin-parcelize-runtime-1.9.22.jar")
        self.assertEqual((0, 2), (code, count))
        self.assertEqual([":app:assembleLiteDebug"] * 2, args.splitlines())
        self.assertEqual(["0", "0"], endpoints)

    def test_repeated_missing_module_refreshes_once_then_falls_back(self):
        code, count, args, endpoints = self.run_case("Could not find junit:junit:4.13.2.", 4)
        self.assertEqual((0, 4), (code, count))
        self.assertEqual([
            ":app:assembleLiteDebug",
            ":app:assembleLiteDebug",
            "--refresh-dependencies :app:assembleLiteDebug",
            ":app:assembleLiteDebug",
        ], args.splitlines())

    def test_transport_failure_retries_without_metadata_refresh(self):
        code, count, args, endpoints = self.run_case("Could not GET https://repo.maven.apache.org/file", 4)
        self.assertEqual((0, 4), (code, count))
        self.assertNotIn("--refresh-dependencies", args)
        self.assertEqual(["0"] * 4, endpoints)

    def test_maven_central_missing_artifact_switches_endpoint_before_refresh(self):
        missing = ("Could not find api-13.1.5.aar (dev.rikka.shizuku:api:13.1.5).\n"
                   "    https://repo.maven.apache.org/maven2/dev/rikka/shizuku/api/13.1.5/api-13.1.5.aar")
        code, count, args, endpoints = self.run_case(missing, 3)
        self.assertEqual((0, 3), (code, count))
        self.assertEqual(["0", "0", "1"], endpoints)
        self.assertNotIn("--refresh-dependencies", args)

    def test_persistent_artifact_miss_at_alternate_refreshes_only_that_endpoint(self):
        missing = ("Could not find junit:junit:4.13.2.\n"
                   "    https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom")
        code, count, args, endpoints = self.run_case(missing, 4)
        self.assertEqual((0, 4), (code, count))
        self.assertEqual(["0", "0", "1", "1"], endpoints)
        self.assertEqual(1, args.count("--refresh-dependencies"))
        self.assertTrue(args.splitlines()[-1].startswith("--refresh-dependencies"))

    def test_compiler_failure_is_not_retried(self):
        self.assertEqual((17, 1), self.run_case("e: Unresolved reference: broken")[0:2])

    def test_ui_test_failure_is_not_retried(self):
        self.assertEqual((17, 1), self.run_case("There were failing tests")[0:2])

    def test_dependency_error_does_not_hide_a_compiler_or_lint_failure(self):
        for source_error in ("e: Unresolved reference: broken", "Lint found 2 errors and 1 warning."):
            with self.subTest(source_error=source_error):
                log = "Could not find junit:junit:4.13.2.\n" + source_error
                self.assertEqual((17, 1), self.run_case(log)[0:2])

    def test_resolution_without_missing_artifact_or_transport_error_fails_immediately(self):
        result = self.run_case("Could not resolve all files for configuration ':app:debugRuntimeClasspath'.\n"
                               "No matching variant of dependency was found.")
        self.assertEqual((17, 1), result[0:2])

    def test_persistent_dependency_failure_returns_original_status(self):
        self.assertEqual((17, 4), self.run_case("Could not find gson-2.8.9.jar", 99)[0:2])

    def test_plugin_miss_after_artifact_failure_remains_retryable(self):
        result = self.run_case("Could not find gson-2.8.9.jar", 3,
            "Plugin Repositories (could not resolve plugin artifact 'com.google.devtools.ksp:plugin:2.3.13')")
        self.assertEqual((0, 3), result[0:2])
        self.assertNotIn("--refresh-dependencies", result[2])

    def test_initial_plugin_configuration_error_is_not_retried(self):
        self.assertEqual((17, 1), self.run_case(
            "Plugin Repositories (could not resolve plugin artifact 'misspelled.plugin:plugin:1')")[0:2])

    def test_final_failure_is_republished_as_annotation_without_changing_status(self):
        code, count, _, _ = self.run_case(
            "e: file:///home/runner/work/r/r/app/src/test/java/A.kt:37:46 Argument type mismatch\n"
            "FooTest > bar FAILED")
        self.assertEqual((17, 1), (code, count))  # not retryable, original exit status preserved

    def test_annotation_script_output_reaches_stdout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake = root / "gradle.sh"
            fake.write_text("#!/bin/bash\necho 'e: file:///w/app/src/A.kt:3:4 boom'\nexit 9\n")
            env = dict(os.environ, OMNI_GRADLE_EXECUTABLE=str(fake), GITHUB_WORKSPACE="/w")
            result = subprocess.run(["bash", str(Path(__file__).with_name("gradle-with-retry.sh")), ":x"],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(9, result.returncode)
            self.assertIn("::error file=app/src/A.kt,line=3,col=4,title=Kotlin compile error::boom", result.stdout)


if __name__ == "__main__":
    unittest.main()
