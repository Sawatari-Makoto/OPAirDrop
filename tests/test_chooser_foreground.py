"""Run the production foreground classifier against Android activity-record fixtures."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ChooserForeground(unittest.TestCase):
    def classify(self, record, mode='both', failure=False):
        source = (ROOT / 'module/bin/ui-lifecycle.sh').read_text()
        # Extract foreground_matches() too: the production refactor moved the
        # record matching into its own helper defined before foreground_state(),
        # and foreground_state() calls it directly.
        function = source.split('foreground_matches() {', 1)[1].split('\nclose_session()', 1)[0]
        function = 'foreground_matches() {' + function
        # The production script raised the dumpsys budget from 4s to 6s when
        # the foreground re-read was added; keep the fixture in sync.
        function = function.replace('/system/bin/timeout 6 /system/bin/dumpsys', 'fake_dumpsys')
        with tempfile.TemporaryDirectory() as work:
            script = '''active_share_mode() { printf '%s\\n' "$MODE"; }
fake_dumpsys() { [ "$FAIL" = 0 ] || return 1; printf '%s\\n' "$RECORD"; }
STATE=$WORK
''' + function + '\nforeground_state\n'
            result = subprocess.run(['sh', '-c', script], capture_output=True, text=True, check=True,
                                    env=dict(os.environ, MODE=mode, RECORD=record, WORK=work, FAIL=str(int(failure))))
            return result.stdout.strip()

    def test_chooser_and_launcher_alias_in_supported_modes(self):
        for mode in ['both', 'oshare']:
            for activity in ['.ChooserActivity', 'com.android.intentresolver.ChooserActivity',
                             '.ChooserActivityLauncher', 'com.android.intentresolver.ChooserActivityLauncher']:
                with self.subTest(mode=mode, activity=activity):
                    self.assertEqual(self.classify('topResumedActivity= ActivityRecord{ab com.android.intentresolver/'
                                                   + activity + ' t12}', mode), 'open')

    def test_other_resolvers_and_prefix_lookalikes_are_closed(self):
        for activity in ['.ResolverActivity', '.ChooserActivityOther', '.ChooserActivityLauncherOther']:
            self.assertEqual(self.classify('topResumedActivity= com.android.intentresolver/' + activity + ' t12}'), 'closed')
        self.assertEqual(self.classify('topResumedActivity= unrelated.package/.ChooserActivity t12}'), 'closed')
        # A visible ChooserActivity now counts as open in every mode,
        # including quickshare: hiding it while the share mode property was
        # mid-switch closed the receiver gate and dropped the device from
        # the iPhone AirDrop list (see foreground_matches in ui-lifecycle.sh).
        self.assertEqual(self.classify('topResumedActivity= com.android.intentresolver/.ChooserActivity t12}', 'quickshare'), 'open')

    def test_existing_entrances_and_unknown_state(self):
        self.assertEqual(self.classify('topResumedActivity= com.coloros.oshare/.ui.ShareActivity t12}'), 'open')
        self.assertEqual(self.classify('topResumedActivity= com.google.android.gms/.nearby.sharing.ShareActivity t12}'), 'open')
        self.assertEqual(self.classify('topResumedActivity= com.google.android.gms/.nearby.sharing.ShareActivity t12}', 'oshare'), 'closed')
        self.assertEqual(self.classify(''), 'unknown')
        self.assertEqual(self.classify('', failure=True), 'unknown')


if __name__ == '__main__':
    unittest.main()
