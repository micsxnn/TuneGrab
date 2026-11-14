import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import passgen


class PassgenCliTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "invites.json"
        self.pepper = b"x" * 32  # Synthetic test value; never a real invitation secret.
        self.password = passgen.derive_password(passgen.normalize_name("Security Test"), self.pepper)[0]

    def run_cli(self, *args):
        return subprocess.run(
            [sys.executable, str(Path(passgen.__file__)), "--json", str(self.path), *args],
            env=dict(os.environ, TUNE_PEPPER=self.pepper.hex()), capture_output=True, text=True,
        )

    def test_quiet_generation_does_not_print_password(self):
        result = self.run_cli("--name", "Security Test", "--quiet")
        self.assertEqual(0, result.returncode)
        self.assertNotIn(self.password, result.stdout + result.stderr)
        self.assertIn("security-test", result.stdout)

    def test_quiet_show_does_not_override_the_silence_request(self):
        result = self.run_cli("--name", "Security Test", "--show", "--quiet")
        self.assertEqual(0, result.returncode)
        self.assertNotIn(self.password, result.stdout + result.stderr)

    def test_author_can_still_explicitly_show_the_password(self):
        result = self.run_cli("--name", "Security Test", "--show")
        self.assertEqual(0, result.returncode)
        self.assertIn(self.password, result.stdout)

    def test_generator_refuses_parameters_the_app_cannot_accept(self):
        result = self.run_cli("--name", "Security Test", "--iters", "2147483647")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.path.exists())


if __name__ == "__main__":
    unittest.main()
