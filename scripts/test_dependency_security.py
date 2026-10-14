import unittest
from verify_dependency_security import violations


class DependencySecurityTest(unittest.TestCase):
    def test_finds_vulnerable_transitives_even_when_top_level_plugin_is_new(self):
        result = violations("org.owasp:dependency-check-gradle:13.0.0\n"
                            "org.jsoup:jsoup:1.23.1\n"
                            "io.netty:netty-handler:4.1.93.Final\n")
        self.assertEqual(2, len(result))

    def test_accepts_patched_versions_and_future_stable_versions(self):
        self.assertEqual([], violations("org.jsoup:jsoup:1.23.2\n"
                                        "io.netty:netty-handler:4.1.137.Final\n"
                                        "io.netty:netty-common:4.1.138.Final\n"
                                        "org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r\n"))

    def test_does_not_compare_netty_native_library_to_java_version(self):
        self.assertEqual([], violations("io.netty:netty-tcnative-boringssl-static:2.0.74.Final"))

    def test_jackson_annotations_has_an_independent_version_scheme(self):
        self.assertEqual([], violations("com.fasterxml.jackson.core:jackson-annotations:2.22\n"
                                        "com.fasterxml.jackson.core:jackson-databind:2.22.1"))

    def test_rejects_empty_inventory_and_prerelease_fix(self):
        self.assertTrue(violations(""))
        self.assertTrue(violations("org.jsoup:jsoup:1.24.0-RC1"))

    def test_runtime_core_cannot_be_upgraded_by_an_unrelated_library(self):
        self.assertEqual([], violations("androidx.core:core:1.17.0\nandroidx.core:core-ktx:1.17.0", enforce_core=True))
        self.assertTrue(violations("androidx.core:core:1.18.0", enforce_core=True))


if __name__ == "__main__":
    unittest.main()
