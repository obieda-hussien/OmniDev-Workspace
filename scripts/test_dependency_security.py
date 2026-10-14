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

    def test_rejects_empty_inventory_and_prerelease_fix(self):
        self.assertTrue(violations(""))
        self.assertTrue(violations("org.jsoup:jsoup:1.24.0-RC1"))


if __name__ == "__main__":
    unittest.main()
