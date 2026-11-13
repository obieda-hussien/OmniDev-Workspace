"""Exercise the real EXIT handler with a child that writes during shutdown."""
import pathlib
import os
import shlex
import signal
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
HELPER = ROOT / '.github/scripts/neural-emulator-cleanup.sh'


class EmulatorCleanupTest(unittest.TestCase):
    def run_cleanup(self, status, child=None, fail_remove=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            avd = root / 'test.avd'
            ready = root / 'ready'
            avd.mkdir()
            command = f'''set -Eeuo pipefail
neural_avd_dir={shlex.quote(str(avd))}
neural_emulator_pid=
source {shlex.quote(str(HELPER))}
trap cleanup_neural_emulator EXIT
'''
            if child:
                command += f'''{shlex.quote(sys.executable)} -c {shlex.quote(child)} {shlex.quote(str(avd))} {shlex.quote(str(ready))} &
neural_emulator_pid=$!
for attempt in {{1..100}}; do
  if [[ -f {shlex.quote(str(ready))} ]]; then break; fi
  sleep 0.02
done
if [[ ! -f {shlex.quote(str(ready))} ]]; then exit 99; fi
'''
            if fail_remove:
                command += 'rm() { return 1; }\n'
            command += f'exit {status}\n'
            process = subprocess.Popen(['bash', '-c', command], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
            try:
                # Include scheduling/startup slack around the handler's two-second grace.
                stdout, stderr = process.communicate(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate(timeout=5)
                raise
            result = subprocess.CompletedProcess(command, process.returncode, stdout, stderr)
            return result, avd.exists()

    def test_waits_for_late_shutdown_write_before_removing_avd(self):
        child = '''import pathlib, signal, sys, time
directory, ready = map(pathlib.Path, sys.argv[1:])
def stop(*unused):
    time.sleep(0.2)
    (directory / 'late-shutdown-state').write_text('flushed')
    sys.exit(0)
signal.signal(signal.SIGTERM, stop)
ready.touch()
while True: time.sleep(0.05)
'''
        result, exists = self.run_cleanup(0, child)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(exists)
        self.assertNotIn('Traceback', result.stderr)

    def test_stubborn_owned_child_is_stopped_with_bounded_grace(self):
        child = '''import pathlib, signal, sys, time
signal.signal(signal.SIGTERM, signal.SIG_IGN)
pathlib.Path(sys.argv[2]).touch()
while True: time.sleep(0.05)
'''
        result, exists = self.run_cleanup(0, child)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(exists)

    def test_cleanup_does_not_turn_a_failed_test_into_success(self):
        result, exists = self.run_cleanup(17)
        self.assertEqual(result.returncode, 17)
        self.assertFalse(exists)

    def test_removal_diagnostic_preserves_both_success_and_failure_status(self):
        for status in (0, 17):
            with self.subTest(status=status):
                result, exists = self.run_cleanup(status, fail_remove=True)
                self.assertEqual(result.returncode, status)
                self.assertTrue(exists)
                self.assertIn('::warning::', result.stderr)


if __name__ == '__main__':
    unittest.main()
