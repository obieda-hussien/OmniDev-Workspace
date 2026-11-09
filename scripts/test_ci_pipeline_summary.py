#!/usr/bin/env python3
"""Regression coverage for false green aggregate checks and deliberate skips."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from ci_pipeline_summary import REQUIRED, OPTIONAL, unsuccessful_stages


class PipelineSummaryTest(unittest.TestCase):
    def needs(self):
        return {key: {"result": "success"} for key in REQUIRED + OPTIONAL}

    def test_success_and_private_delivery_skip_pass(self):
        needs = self.needs()
        self.assertEqual([], unsuccessful_stages(needs))
        needs[OPTIONAL[0]]["result"] = "skipped"
        self.assertEqual([], unsuccessful_stages(needs))

    def test_each_required_failure_cancellation_or_skip_fails(self):
        for key in REQUIRED:
            for result in ("failure", "cancelled", "skipped", "unknown"):
                with self.subTest(key=key, result=result):
                    needs = self.needs()
                    needs[key]["result"] = result
                    self.assertEqual([key], unsuccessful_stages(needs))

    def test_private_delivery_failure_or_cancellation_fails(self):
        for result in ("failure", "cancelled"):
            needs = self.needs()
            needs[OPTIONAL[0]]["result"] = result
            self.assertEqual([OPTIONAL[0]], unsuccessful_stages(needs))

    def test_missing_stage_fails_closed(self):
        needs = self.needs()
        del needs["tests"]
        self.assertEqual(["tests"], unsuccessful_stages(needs))

    def test_only_explicitly_allowed_entire_skipped_pipeline_passes(self):
        needs = {key: {"result": "skipped"} for key in REQUIRED + OPTIONAL}
        self.assertEqual(list(REQUIRED), unsuccessful_stages(needs))
        self.assertEqual([], unsuccessful_stages(needs, allow_skipped_pipeline=True))
        needs["tests"]["result"] = "failure"
        self.assertIn("tests", unsuccessful_stages(needs, allow_skipped_pipeline=True))

    def test_cli_writes_report_before_exiting_with_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "summary.md"
            needs = self.needs()
            needs["tests"]["result"] = "failure"
            result = subprocess.run(
                [sys.executable, str(Path(__file__).with_name("ci_pipeline_summary.py"))],
                env={**os.environ, "PIPELINE_NEEDS_JSON": json.dumps(needs),
                     "GITHUB_STEP_SUMMARY": str(report), "ALLOW_SKIPPED_PIPELINE": "false"},
                capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertIn("| tests | failure |", report.read_text())
            self.assertIn("::error::", result.stdout)


if __name__ == "__main__":
    unittest.main()
