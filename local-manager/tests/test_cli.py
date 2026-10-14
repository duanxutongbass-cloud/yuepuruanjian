from pathlib import Path
import tempfile
import unittest

from demo_workflow import demonstrate


class CliTest(unittest.TestCase):
    def test_real_cli_workflow_and_complete_vs_legacy_recovery(self):
        with tempfile.TemporaryDirectory() as temp:
            result = demonstrate(Path(temp) / "demo")
            self.assertTrue(result["passed"])
            self.assertGreater(result["cliInvocations"], 30)
