#!/usr/bin/env python3
import os
import unittest

import ci_failure_annotations as ann

LOG = """\
> Task :app:compileAdminDebugUnitTestKotlin FAILED
e: file:///home/runner/work/OmniDev-Workspace/OmniDev-Workspace/app/src/test/java/com/x/FooTest.kt:37:46 Argument type mismatch: actual type is 'Int', but 'Char' was expected.
e: file:///home/runner/work/OmniDev-Workspace/OmniDev-Workspace/app/src/test/java/com/x/FooTest.kt:37:46 Argument type mismatch: actual type is 'Int', but 'Char' was expected.
FooTest > parsesFence FAILED
    java.lang.AssertionError at FooTest.kt:42
\x1b[31mBarTest > other FAILED\x1b[0m
    org.junit.ComparisonFailure at BarTest.kt:9
* What went wrong:
Execution failed for task ':app:compileAdminDebugUnitTestKotlin'.
"""


class FailureAnnotationTest(unittest.TestCase):
    def setUp(self):
        os.environ['GITHUB_WORKSPACE'] = '/home/runner/work/OmniDev-Workspace/OmniDev-Workspace'

    def test_compile_error_is_located_and_deduplicated(self):
        findings = ann.parse(LOG)
        compile_errors = [f for f in findings if f['kind'] == 'compile']
        self.assertEqual(1, len(compile_errors))
        self.assertEqual('app/src/test/java/com/x/FooTest.kt', compile_errors[0]['file'])
        self.assertEqual((37, 46), (compile_errors[0]['line'], compile_errors[0]['col']))

    def test_failed_tests_keep_their_first_detail_line_and_strip_ansi(self):
        tests = [f['message'] for f in ann.parse(LOG) if f['kind'] == 'test']
        self.assertIn('FooTest > parsesFence FAILED - java.lang.AssertionError at FooTest.kt:42', tests)
        self.assertIn('BarTest > other FAILED - org.junit.ComparisonFailure at BarTest.kt:9', tests)

    def test_compile_errors_are_listed_before_other_findings(self):
        kinds = [f['kind'] for f in ann.order(ann.parse(LOG))]
        self.assertEqual('compile', kinds[0])
        self.assertLess(kinds.index('compile'), kinds.index('test'))

    def test_annotation_escapes_workflow_command_characters(self):
        text = ann.annotation({'kind': 'compile', 'message': '100%\nnext', 'file': 'a,b:c.kt', 'line': 3, 'col': 4})
        self.assertEqual('::error file=a%2Cb%3Ac.kt,line=3,col=4,title=Kotlin compile error::100%25%0Anext', text)

    def test_empty_log_never_raises(self):
        self.assertEqual([], ann.parse(''))
        self.assertIn('No compile errors', ann.summary([]))


if __name__ == '__main__':
    unittest.main()
