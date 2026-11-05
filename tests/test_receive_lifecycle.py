#!/usr/bin/env python3
"""Exercise production receiver/radio ownership with Android command fixtures."""
from pathlib import Path
import json
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ReceiveLifecycle(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.module = self.base / 'module'
        self.bin = self.module / 'bin'
        self.bin.mkdir(parents=True)
        self.state = self.base / 'state'
        self.state.mkdir()
        self.tools = self.base / 'tools'
        self.tools.mkdir()
        self.params = self.base / 'parameters'
        self.params.mkdir()
        self.log = self.base / 'commands'
        self.log.touch()
        for name, value in {
            'opairshare_bridge_pending': '0',
            'opairshare_channel_state': 'busy=0 fault=0 error=0 accepting=0 vdev=255 freq=0',
            'opairshare_awdl_rx_mac_filter': 'used=0 armed=0 pending=0 active=0 fault=0',
            'passthru_awdl_rx_ring_select': '0',
            'passthru_sta_coexist': '3',
            **{key: 'N' for key in ['passthru_awdl_allow_concurrent', 'passthru_mgmt_home_channel']},
            **{key: '0' for key in ['passthru_awdl_amsdu', 'passthru_awdl_phy', 'passthru_awdl_mcs', 'passthru_awdl_nss', 'passthru_awdl_collect_us']},
        }.items():
            (self.params / name).write_text(value)
        self.dispatch = self.tools / 'dispatch.py'
        self.dispatch.write_text('''#!/usr/bin/env -S python3 -S -E
import json, os, pathlib, sys, time
b=pathlib.Path(os.environ['RX_TEST_BASE']);s=b/'state';name=pathlib.Path(sys.argv[0]).name;a=sys.argv[1:]
frames=json.loads((b/'frames').read_text());tick=int((b/'tick').read_text())
f=frames[min(tick,len(frames)-1)]
def log(v):
 with (b/'commands').open('a') as out:out.write(v+'\\n')
if name=='timeout':
 if a[0]=='-k':a=a[2:]
 # Windows os.execvp silently drops the child's stdio; run through sh via
 # subprocess so the shebang resolution matches a direct PATH invocation.
 import shutil, subprocess
 sys.exit(subprocess.run([shutil.which('sh') or '/bin/sh','-c','exec "$@"','sh']+a[1:]).returncode)
elif name=='settings':
 # v1.2.0 admission: a module-owned session is armed by oshare_state==1
 # plus the opairshare_ios_receive marker; remaining native reads are
 # vestigial and must never gate reception (ERROR included).
 key=a[-1]
 if key=='opairshare_share_mode':
  p=b/'saved-mode';print(p.read_text() if p.exists() else 'both')
 elif key=='oshare_state':print(f.get('oshare_state','1'))
 elif key=='opairshare_ios_receive':print(f.get('ios_receive','1'))
 else:
  if f.get('setting')=='ERROR':sys.exit(1)
  print(f.get('setting','0'))
elif name=='getprop':
 p=b/('prop-'+a[0]);print(p.read_text() if p.exists() else '')
elif name=='setprop':
 if a[0]=='debug.opairshare.receive.ready':
  (b/'ready').write_text(a[1]);log('ready:'+a[1])
 else:(b/('prop-'+a[0])).write_text(a[1]);log('property:'+a[0]+':'+a[1])
elif name=='id':print('0')
elif name=='pidof':
 if (b/'provider').exists():print('42')
elif name=='flock':
 if f.get('settings_locked') and a[-1]=='6':sys.exit(1)
elif name=='sleep':
 log('tick:%d ready:%s provider:%s'%(tick,(b/'ready').read_text() if (b/'ready').exists() else '?',(b/'provider').exists()))
 tick+=1;(b/'tick').write_text(str(tick))
 if tick>=len(frames):(s/'disabled').touch()
 else:
  if frames[tick].get('kill_provider'):(b/'provider').unlink(missing_ok=True)
  if 'mode' in frames[tick]:(b/'saved-mode').write_text(frames[tick]['mode'])
  if 'coex_off' in frames[tick]:
   if frames[tick]['coex_off']:(s/'coex-off').touch()
   else:(s/'coex-off').unlink(missing_ok=True)
elif name=='dumpsys':
 if a[:2]==['activity','activities']:
  fg=f.get('foreground','closed')
  if fg=='open':print('topResumedActivity= com.coloros.oshare/.ui.ShareActivity t9}')
  elif fg=='quickshare':print('topResumedActivity= com.google.android.gms/.nearby.sharing.ShareActivity t9}')
  elif fg=='documents':print('topResumedActivity= com.android.documentsui/.PickerActivity t9}\\nActivityRecord com.google.android.gms/.nearby.sharing.ShareActivity t9}')
  elif fg=='closed':print('topResumedActivity= com.android.launcher/.Launcher t2}')
 elif a[:3]==['activity','services','com.coloros.oshare']:
  if (b/'oshare').exists():print('ServiceRecord com.coloros.oshare/com.oplus.oshare.OShareServer')
  if (b/'oshare-delegate').exists():print('ServiceRecord com.coloros.oshare isFgsDelegate=true')
 elif a[:2]==['activity','service']:
  if f.get('transfer_unknown'):print('Unrecognized provider dump');sys.exit(0)
  print('ExternalSharingService state:\\nActive Transfers (0)\\nReceive provider transferring: false')
  print('OPAIRSHARE_RECEIVE busy='+str(f.get('busy',0)))
  print('OPAIRSHARE_MODE switch_ready='+str(0 if f.get('bridge_busy') else 1))
elif name=='am':
 log('oshare:start');(b/'oshare').touch();print('Starting service: Intent { }')
elif name=='cmd':
 if a==['wifi','status']:
  if f.get('wifi_error'):sys.exit(1)
  if f.get('wifi_malformed'):print('unavailable');sys.exit(0)
  if not f.get('wifi_enabled',True):print('Wifi is disabled');sys.exit(0)
  print('Wifi is enabled')
  if f.get('connected',True):
   print('Wifi is connected to test-network')
   print('Supplicant state: %s, Frequency: %sMHz, Net ID: %s,'%(f.get('supplicant','COMPLETED'),f.get('frequency',5745),f.get('network',7)))
  sys.exit(0)
 action=a[-1];log('oshare:delegate:'+action)
 if f.get('delegate_fail'):sys.exit(1)
 if action=='start':(b/'oshare-delegate').touch()
 else:(b/'oshare-delegate').unlink(missing_ok=True)
elif name=='service':
 log('native:stop')
 (b/'parameters/opairshare_channel_state').write_text('busy=0 fault=0 error=0 accepting=0 vdev=255 freq=0')
 (b/'parameters/opairshare_awdl_rx_mac_filter').write_text('used=0 armed=0 pending=0 active=0 fault=0')
 print('Parcel(00000000)')
elif name=='app_process':
 op=a[2];path=pathlib.Path(a[3]);log('wifi:'+op)
 if op=='capture':path.write_text('boot')
 else:path.unlink()
elif name=='provider.sh':
 log('provider:'+a[0])
 if a[0]=='ensure':
  if f.get('wait_for_warmup'):
   for _ in range(1000):
    if (b/'oshare-delegate').exists():break
    time.sleep(.01)
   else:log('provider:missing-warmup');sys.exit(5)
  if f.get('provider_fail'):sys.exit(5)
  (b/'provider').touch()
  (b/'parameters/opairshare_channel_state').write_text('busy=0 fault=0 error=0 accepting=1 vdev=3 freq=5745')
  (b/'parameters/opairshare_awdl_rx_mac_filter').write_text('used=1 armed=1 pending=0 active=1 fault=0 freq=5745 ')
 else:(b/'provider').unlink(missing_ok=True)
elif name in ['speed-history.sh','opairshare-socket-buffer']:pass
else:raise RuntimeError((name,a))
''')
        self.dispatch.chmod(0o755)
        for name in ['timeout', 'settings', 'setprop', 'getprop', 'id', 'pidof', 'flock',
                     'sleep', 'dumpsys', 'am', 'cmd', 'service', 'app_process']:
            (self.tools / name).hardlink_to(self.dispatch)
        for name in ['provider.sh', 'speed-history.sh']:
            # ui-lifecycle executes these with sh, so use an executable wrapper.
            wrapper = self.bin / name
            target = self.tools / name
            target.hardlink_to(self.dispatch)
            wrapper.write_text(f'#!/bin/sh\nexec "{target}" "$@"\n')
        native = self.module / 'payload/native'
        native.mkdir(parents=True)
        (native / 'opairshare-socket-buffer').hardlink_to(self.dispatch)
        (self.bin / 'kernel-identity.sh').write_text('')
        # Shell scripts embed $STATE and rm -rf it; the sandbox rm shim
        # rejects Windows drive prefixes, so hand them an MSYS-style path.
        msys_state = '/' + self.state.as_posix()[0].lower() + self.state.as_posix()[2:]
        (self.bin / 'common.sh').write_text(f'''STATE='{msys_state}'
PKG=com.google.android.mosey
COMP=$PKG/.ExternalSharingService
SERVICE=test.native
boot_id() {{ echo boot; }}
same_boot() {{ [ -r "$1" ] && [ "$(cat "$1")" = boot ]; }}
init_state() {{ :; }}
startup_trace() {{ :; }}
kernel_matches() {{ return 0; }}
pid_is() {{ return 0; }}
driver_rx_filter_idle() {{ return 0; }}
driver_radio_idle() {{ return 0; }}
framework() {{ "$@"; }}
''')
        for name in ['ui-lifecycle.sh', 'receive-session.sh', 'transfer-idle.sh', 'coex-session.sh', 'coex-wifi.sh', 'receive-startup.sh', 'share-mode.sh', 'idle-sleep.sh']:
            code = (ROOT / 'module/bin' / name).read_text()
            code = code.replace('/system/bin/sh', '/bin/sh')
            # Windows/MSYS cannot apply mkdir permission modes.
            code = code.replace('mkdir -m 700', 'mkdir')
            code = code.replace('/system/bin/', self.tools.as_posix() + '/')
            code = code.replace('/sys/module/qca_cld3_peach_v2/parameters', self.params.as_posix())
            (self.bin / name).write_text(code)
        for name in ['stack-boot', 'server-boot']:
            (self.state / name).write_text('boot')
        callback = self.state / 'callback'
        callback.mkdir(exist_ok=True)
        (callback / 'boot').write_text('boot')
        # Read association state on every tick: the production default
        # (OPAIRSHARE_WIFI_POLL_INTERVAL=3) only refreshes every third tick,
        # which would mask per-tick state changes these tests drive.
        self.env = dict(os.environ, RX_TEST_BASE=str(self.base),
                        OPAIRSHARE_WIFI_POLL_INTERVAL='1',
                        PATH=str(self.tools) + os.pathsep + os.environ['PATH'])

    def frames(self, frames):
        (self.base / 'frames').write_text(json.dumps(frames))
        (self.base / 'tick').write_text('0')

    def run_script(self, name, *args):
        return subprocess.run(['sh', (self.bin / name).as_posix(), *args], env=self.env,
                              capture_output=True, text=True, timeout=120)

    def run_lifecycle(self, frames):
        self.frames(frames)
        result = self.run_script('ui-lifecycle.sh')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return self.log.read_text()

    def test_boot_with_native_receive_off_starts_background_without_activity(self):
        log = self.run_lifecycle([{'setting': '0'}] * 4)
        self.assertIn('property:debug.opairshare.discovery:same149', log)
        self.assertIn('provider:ensure', log)
        self.assertIn('tick:0 ready:1 provider:True', log)
        self.assertEqual(log.count('oshare:start'), 1)
        self.assertEqual(log.count('oshare:delegate:start'), 1)
        self.assertNotIn('wifi:restore', log)
        self.assertNotIn('provider:release', log)
        self.assertEqual((self.base / 'ready').read_text(), '0')

    def test_receiver_warms_while_provider_startup_is_still_waiting(self):
        log = self.run_lifecycle([{'wait_for_warmup': True}] * 2)
        self.assertNotIn('provider:missing-warmup', log)
        self.assertIn('tick:0 ready:1 provider:True', log)
        self.assertEqual(log.count('oshare:delegate:start'), 1)

    def test_switching_native_modes_preserves_background_session(self):
        log = self.run_lifecycle([{'setting': '2'}, {'setting': '1'}, {'setting': '2'}] +
                                 [{'setting': '0'}] * 4)
        self.assertIn('tick:0 ready:1 provider:True', log)
        self.assertIn('tick:1 ready:1 provider:True', log)
        self.assertIn('tick:2 ready:1 provider:True', log)
        self.assertEqual(log.count('property:debug.opairshare.discovery:same149'), 1)
        self.assertEqual(log.count('oshare:start'), 1)
        self.assertNotIn('provider:release', log)
        self.assertNotIn('wifi:restore', log)

    def test_native_settings_errors_do_not_gate_module_owned_reception(self):
        log = self.run_lifecycle([{'setting': setting} for setting in ['0', 'null', '3', 'ERROR']])
        self.assertEqual(log.count('property:debug.opairshare.discovery:same149'), 1)
        for tick in range(4):
            self.assertIn(f'tick:{tick} ready:1 provider:True', log)




    def test_provider_death_and_failed_recovery_close_admission(self):
        log = self.run_lifecycle([{'setting': '2'},
                                 {'setting': '2', 'kill_provider': True, 'provider_fail': True},
                                 {'setting': '2', 'provider_fail': True}])
        self.assertIn('tick:0 ready:1 provider:True', log)
        self.assertIn('tick:1 ready:0 provider:False', log)

    def test_unsupported_frequency_never_advertises(self):
        log = self.run_lifecycle([{'setting': '2', 'frequency': 9000}] * 2)
        self.assertNotIn('provider:ensure', log)
        self.assertNotIn('ready:1', log)


    def test_unsupported_frequency_keeps_armed_session_and_marks_suspension(self):
        # An unsupported band suspends coex Wi-Fi tracking but must NOT
        # release the armed AWDL session: releasing it restarts the native
        # server, re-applies the regulatory domain and flushes the armed
        # wondertap, killing the AirDrop session within ~2s. The session
        # stays armed even while a transfer is busy.
        log = self.run_lifecycle([{}] + [{'frequency': 9000, 'busy': 1}] * 2 +
                                 [{'frequency': 9000}])
        lines = log.splitlines()
        for tick in range(4):
            self.assertIn(f'tick:{tick} ready:1 provider:True', lines)
        self.assertNotIn('provider:release', log)
        self.assertNotIn('wifi:restore', log)
        self.assertEqual(log.count('property:debug.opairshare.discovery:same149'), 1)
        self.assertEqual((self.state / 'coex-wifi-blocked').read_text().strip(),
                         'wifi_unsupported')

    def test_disconnect_during_transfer_keeps_armed_session_until_reconnect(self):
        # A dropped STA association suspends coex tracking but keeps the
        # armed AWDL session (AirDrop traffic runs on wonder0, never on the
        # STA). Once Wi-Fi reconnects on the same network the suspension is
        # cleared and the session continues without a rebuild.
        log = self.run_lifecycle([{}, {'connected': False, 'busy': 1},
                                 {'connected': False, 'transfer_unknown': True},
                                 {'busy': 1}, {}, {}, {}])
        lines = log.splitlines()
        for tick in range(7):
            self.assertIn(f'tick:{tick} ready:1 provider:True', lines)
        self.assertNotIn('provider:release', log)
        self.assertNotIn('wifi:restore', log)
        self.assertEqual(log.count('property:debug.opairshare.discovery:same149'), 1)
        self.assertFalse((self.state / 'coex-wifi-blocked').exists())

    def test_same149_network_change_rebuilds_session_and_restores_sta(self):
        log = self.run_lifecycle([{}, {'network': 8, 'busy': 1},
                                 {'network': 8}, {'network': 8}, {'network': 8}])
        lines = log.splitlines()
        self.assertIn('tick:1 ready:0 provider:True', lines)
        self.assertIn('tick:2 ready:0 provider:False', lines)
        self.assertIn('tick:3 ready:1 provider:True', lines)
        self.assertEqual((self.state / 'coex-session/network').read_text().strip(), '8')
        self.assertEqual(log.count('provider:release'), 1)
        self.assertEqual(log.count('property:debug.opairshare.discovery:same149'), 2)
        # v1.2.0: closing the session restores the STA network captured at
        # arm time, and the rebuild re-captures it before re-arming AWDL.
        self.assertEqual(log.count('wifi:restore'), 1)
        self.assertEqual(log.count('wifi:capture'), 2)
        self.assertGreater(log.index('wifi:restore'), log.index('provider:release'))



    def test_wifi_read_errors_keep_armed_session_without_legacy_fallback(self):
        # A failed or malformed `cmd wifi status` read suspends coex
        # tracking but never tears down the module-owned session and never
        # falls back to the legacy foreground model: the provider and the
        # advertised readiness survive until Wi-Fi reads cleanly again.
        log = self.run_lifecycle([{}, {'wifi_error': True, 'foreground': 'open'},
                                 {'wifi_malformed': True, 'foreground': 'open'}, {}, {}])
        lines = log.splitlines()
        for tick in range(5):
            self.assertIn(f'tick:{tick} ready:1 provider:True', lines)
        self.assertNotIn('provider:release', log)
        self.assertNotIn('wifi:restore', log)
        self.assertEqual(log.count('property:debug.opairshare.discovery:same149'), 1)
        self.assertFalse((self.state / 'coex-wifi-blocked').exists())


    def test_ready_rechecks_live_connection_and_network(self):
        (self.state / 'ui-session').write_text('boot')
        p = self.state / 'coex-session'
        p.mkdir()
        (p / 'boot').write_text('boot')
        (p / 'network').write_text('7')
        for frame in [{'frequency': 9000}, {'connected': False}, {'network': 8},
                      {'wifi_error': True}, {'supplicant': 'ASSOCIATING'}, {}]:
            with self.subTest(frame=frame):
                self.frames([frame])
                result = self.run_script('receive-session.sh', 'ready')
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), '0' if frame else '1')

    def test_ready_requires_current_boot_independently_of_native_setting(self):
        for state in ['0', '1', '2']:
            self.frames([{'setting': state}])
            (self.state / 'ui-session').write_text('boot')
            p = self.state / 'coex-session'
            p.mkdir(exist_ok=True)
            (p / 'network').write_text('7')
            (p / 'frequency').write_text('5745')
            for boot in ['old-boot', 'boot']:
                (p / 'boot').write_text(boot)
                result = self.run_script('receive-session.sh', 'ready')
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), '1' if boot == 'boot' else '0')

    def test_disabled_removed_or_unowned_module_never_requests_receive(self):
        self.frames([{'setting': '2'}])
        for path in [self.state/'disabled', self.module/'disable', self.module/'remove']:
            path.touch()
            self.assertEqual(self.run_script('receive-session.sh', 'wanted').stdout.strip(), '0')
            path.unlink()
        (self.state/'stack-boot').write_text('old-boot')
        self.assertEqual(self.run_script('receive-session.sh', 'wanted').stdout.strip(), '0')

    def test_host_delegate_failure_keeps_receive_admission_closed(self):
        log = self.run_lifecycle([{'delegate_fail': True}] * 3)
        self.assertIn('oshare:delegate:start', log)
        self.assertNotIn('ready:1', log)

    def test_clearing_admission_retains_busy_host_and_release_checks_idle(self):
        self.frames([{'busy': 1}])
        # v1.2.0 transfer-idle fast-path: no provider process means no
        # transfer can run, so the busy gate only applies with a live pid.
        (self.base/'provider').touch()
        (self.state/'receive-delegate-owned').write_text('boot')
        (self.base/'oshare-delegate').touch()
        self.assertEqual(self.run_script('receive-session.sh', 'clear').returncode, 0)
        self.assertTrue((self.base/'oshare-delegate').exists())
        self.assertEqual(self.run_script('receive-session.sh', 'release-host').returncode, 9)
        self.frames([{'busy': 0}])
        self.assertEqual(self.run_script('receive-session.sh', 'release-host').returncode, 0)
        self.assertFalse((self.base/'oshare-delegate').exists())

    def test_pending_dump_overrides_native_idle(self):
        self.frames([{'busy': 1}])
        # A live provider process is required for the busy gate to apply.
        (self.base/'provider').touch()
        result = self.run_script('transfer-idle.sh')
        self.assertEqual(result.returncode, 9)
        self.frames([{'busy': 0}])
        self.assertEqual(self.run_script('transfer-idle.sh').returncode, 0)


if __name__ == '__main__':
    unittest.main(verbosity=2)
