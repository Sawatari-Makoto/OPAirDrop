#!/usr/bin/env python3
"""Exercise Oplus/sun admission at the install and start entrypoints."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
KERNEL = '6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k'
FAMILY = '6.6.118-android15-8'
PAGE_SIZE = '4k'
NOTE_HEAD = '040000001400000003000000474e5500'
NOTE_QCA = NOTE_HEAD + 'a' * 40
NOTE_MAC = NOTE_HEAD + 'b' * 40
STOCK_QCA = NOTE_HEAD + 'c' * 40
STOCK_MAC = NOTE_HEAD + 'd' * 40


class CompatibilityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.module = self.base / 'module'
        self.bin = self.module / 'bin'
        self.bin.mkdir(parents=True)
        # The device shell resolves paths with slashes, so the fixtures use
        # the same form.
        self.module_path = self.module.as_posix()
        self.vendor = self.base / 'vendor'
        self.vendor.mkdir()
        self.vendor_modules = self.base / 'vendor-modules'
        self.vendor_modules.mkdir()
        self.tools = self.base / 'tools'
        self.tools.mkdir()
        self.props = self.base / 'props'
        self.props.mkdir()
        self.state = self.base / 'state'
        self.state.mkdir()
        self.state_path = self.state.as_posix()
        self.modules = self.base / 'modules'
        self.modules.write_text('qca_cld3_peach_v2 12365824 1 - Live 0x0\n'
                                'mac80211 1093632 0 - Live 0x0\ncnss2 42 1 - Live 0x0\n')
        self.marker = self.base / 'mutated'
        for key, value in {
            'ro.product.model': 'ANOTHER_OPLUS_MODEL',
            'ro.product.manufacturer': 'OnePlus', 'ro.product.brand': 'OnePlus',
            'ro.soc.model': 'SM8750', 'ro.board.platform': 'sun',
            'ro.build.version.sdk': '37', 'kernel': KERNEL, 'uid': '0',
        }.items():
            self.prop(key, value)
        for name, body in {
            'getprop': f'cat "{self.props}/$1" 2>/dev/null || true',
            'uname': f'cat "{self.props}/kernel"',
            'id': f'cat "{self.props}/uid"',
            'flock': 'exit 0',
        }.items():
            path = self.tools / name
            path.write_text('#!/bin/sh\n' + body + '\n')
            path.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.tools) + os.pathsep + os.environ['PATH'])
        self.write_identity()
        self.live = self.base / 'live'
        self.live.mkdir()
        for name in ('qca_cld3_peach_v2', 'mac80211'):
            (self.live / name).write_text('different-stock-build')
        shutil.copy2(ROOT / 'module/bin/compatibility.sh', self.bin)
        shutil.copy2(ROOT / 'module/bin/root-runtime.sh', self.bin)
        # Namespace entry is exercised separately with real runtime helper fixtures.
        with (self.bin / 'root-runtime.sh').open('a') as out:
            out.write('\nroot_enter_global() { :; }\n')
        (self.bin / 'mount-check.sh').write_text('exit 0\n')
        # The production library carries the identity helpers, so the tests
        # exercise the same kernel family gate the device runs.
        (self.bin / 'common.sh').write_text(f'''. "{(ROOT / 'module/bin/common.sh').as_posix()}"
STATE='{self.state_path}'
identity_load
init_state() {{ :; }}
loaded() {{ grep -q "^$1 " '{self.modules.as_posix()}'; }}
module_note() {{ cat '{self.live.as_posix()}/'$1 2>/dev/null; }}
VENDOR_MODULE_DIRS='{self.vendor_modules.as_posix()}'
''')
        (self.bin / 'transfer-power.sh').write_text('')
        (self.bin / 'transfer-idle.sh').write_text(f'touch "{self.marker.as_posix()}"; exit 88\n')
        control = (ROOT / 'module/bin/control.sh').read_text().replace('/system/bin/sh', '/bin/sh')
        control = control.replace('MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)',
                                  f"MODDIR='{self.module_path}'")
        (self.bin / 'control.sh').write_text(control)
        # Run the production installer up to the first configuration mutation.
        installer = (ROOT / 'module/customize.sh').read_text().split('# Replacement must not merge', 1)[0]
        installer += f'touch "{self.marker.as_posix()}"\n'
        (self.module / 'customize.sh').write_text(installer)
        (self.bin / 'verify.sh').write_text('exit 0\n')
        (self.module / 'module.prop').write_text('version=test\n')

    def write_identity(self, family=FAMILY, page_size=PAGE_SIZE,
                       note_qca=NOTE_QCA, note_mac=NOTE_MAC):
        """Write the identity file of the package under test."""
        text = (ROOT / 'module/bin/kernel-identity.sh').read_text()
        text = text.replace('KERNEL_ABI_FAMILY=BUILD_REQUIRED', f'KERNEL_ABI_FAMILY={family}')
        text = text.replace('KERNEL_ABI_PAGESIZE=BUILD_REQUIRED', f'KERNEL_ABI_PAGESIZE={page_size}')
        text = text.replace('NOTE_qca_cld3_peach_v2=BUILD_REQUIRED', f'NOTE_qca_cld3_peach_v2={note_qca}')
        text = text.replace('NOTE_mac80211=BUILD_REQUIRED', f'NOTE_mac80211={note_mac}')
        (self.bin / 'kernel-identity.sh').write_text(text)

    def write_stock_module(self, name, desc):
        """Write a vendor copy of a kernel module with a GNU build id note."""
        note = bytes.fromhex('040000001400000003000000') + b'GNU\x00' + bytes.fromhex(desc)
        (self.vendor_modules / f'{name}.ko').write_bytes(b'\x7fELF' + b'\x00' * 8 + note)

    def prop(self, key, value):
        (self.props / key).write_text(value + '\n')

    def run_shell(self, code, *args):
        # The snippet runs from a file, so the sourced scripts resolve their
        # own directory the same way they do on the device.
        script = self.bin / '_runner.sh'
        script.write_text('set -eu\n' + code)
        return subprocess.run(['sh', script.as_posix(), *args], env=self.env,
                              capture_output=True, text=True, timeout=10)

    def check(self, function='compatibility_device'):
        return self.run_shell('. "$1/bin/common.sh"; . "$1/bin/kernel-identity.sh"; '
                              '. "$1/bin/compatibility.sh"; ' + function, self.module_path)

    def install(self):
        return self.run_shell('MODPATH=$1; KSU=true; ui_print() { echo "$*"; }; '
                              'abort() { echo "$*" >&2; exit 2; }; . "$1/customize.sh"', self.module_path)

    def install_sourced(self):
        """Install the way a manager sources the script, so $0 is not it."""
        code = ('MODPATH=$1; KSU=true; ui_print() { echo "$*"; }; '
                'abort() { echo "$*" >&2; exit 2; }; . "$1/customize.sh"')
        return subprocess.run(['sh', '-c', code, 'installer', self.module_path],
                              env=self.env, capture_output=True, text=True, timeout=10)

    def start(self):
        return subprocess.run(['sh', (self.bin / 'control.sh').as_posix(), 'start'], env=self.env,
                              capture_output=True, text=True, timeout=10)

    def assert_rejected(self, result, reason):
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(reason, result.stderr)

    def test_other_model_with_matching_baseline_passes_both_entrypoints(self):
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('ANOTHER_OPLUS_MODEL', result.stdout)
        self.assertTrue(self.marker.exists())
        self.marker.unlink()
        result = self.start()
        self.assertEqual(result.returncode, 88, result.stderr)
        self.assertTrue(self.marker.exists())

    def test_all_oplus_brands_and_vendor_property_fallback(self):
        for brand in ('OPPO', 'OnePlus', 'realme', 'Oplus'):
            with self.subTest(brand=brand):
                self.prop('ro.product.manufacturer', '')
                self.prop('ro.product.brand', '')
                self.prop('ro.product.vendor.brand', brand)
                result = self.check()
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_platform_mismatches_reject_before_mutation(self):
        cases = [('ro.board.platform', 'other', 'sun platform'),
                 ('ro.board.platform', '', 'sun platform')]
        for prop, value, reason in cases:
            original = (self.props / prop).read_text()
            with self.subTest(prop=prop, value=value):
                self.prop(prop, value)
                for entry in (self.install, self.start):
                    self.assert_rejected(entry(), reason)
                    self.assertFalse(self.marker.exists())
                (self.props / prop).write_text(original)

    def test_non_oplus_rejected(self):
        self.prop('ro.product.manufacturer', 'Xiaomi')
        self.prop('ro.product.brand', 'Xiaomi')
        self.assert_rejected(self.install(), 'Oplus device required')
        self.assert_rejected(self.start(), 'Oplus device required')
        self.assertFalse(self.marker.exists())

    def test_missing_system_mounts_reject_before_driver_changes(self):
        (self.bin / 'mount-check.sh').write_text('echo "missing system mounts" >&2; exit 3\n')
        self.assert_rejected(self.start(), 'missing system mounts')
        self.assertFalse(self.marker.exists())

    def test_installer_accepts_other_root_managers_without_metamodule(self):
        for hints in ('APATCH=true', 'MAGISK_VER=30.0', 'KSU=true', ''):
            prefix = 'MODPATH=$1; ' + (hints + '; ' if hints else '')
            result = self.run_shell(prefix + 'ui_print() { echo "$*"; }; '
                                    'abort() { echo "$*" >&2; exit 2; }; . "$1/customize.sh"', str(self.module))
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_same_kernel_family_passes_with_any_build_id(self):
        # The release string of a device carries a build id of its own, so the
        # family is matched as a prefix and the whole string is never fixed.
        for kernel in ('6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k',
                       '6.6.118-android15-8-g0000000000000000-abogki000000000-4k',
                       '6.6.118-android15-8',
                       '6.6.118-android15-8-o-4k'):
            with self.subTest(kernel=kernel):
                self.prop('kernel', kernel)
                result = self.install()
                self.assertEqual(result.returncode, 0, result.stderr)
                result = self.start()
                self.assertEqual(result.returncode, 88, result.stderr)

    def test_other_kernel_families_are_rejected(self):
        for kernel in ('6.6.119-android15-8-g9bc34d5b0c79-abogki537459655-4k',
                       '6.6.118-android14-8-g9bc34d5b0c79-abogki537459655-4k',
                       '5.15.148-android15-8-g9bc34d5b0c79-abogki537459655-4k',
                       '6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-16k'):
            with self.subTest(kernel=kernel):
                self.prop('kernel', kernel)
                for entry in (self.install, self.start):
                    self.assert_rejected(entry(), 'ABI family')
                    self.assertFalse(self.marker.exists())

    def test_unknown_kernel_family_is_rejected(self):
        self.write_identity(family='BUILD_REQUIRED')
        for entry in (self.install, self.start):
            self.assert_rejected(entry(), 'kernel ABI family is unknown')
            self.assertFalse(self.marker.exists())

    def test_model_soc_sdk_and_stock_files_do_not_restrict_admission(self):
        for sdk, soc in [('36', 'SM8750-AB'), ('35', 'other'), ('', '')]:
            with self.subTest(sdk=sdk, soc=soc):
                self.prop('ro.product.model', 'UNLISTED_MODEL')
                self.prop('ro.build.version.sdk', sdk)
                self.prop('ro.soc.model', soc)
                # The package carries no stock build identity of any device.
                self.write_identity()
                identity = (self.bin / 'kernel-identity.sh').read_text()
                self.assertNotIn('STOCK_NOTE_', identity)
                result = self.install()
                self.assertEqual(result.returncode, 0, result.stderr)
                result = self.start()
                self.assertEqual(result.returncode, 88, result.stderr)

    def test_installer_records_the_stock_build_ids_of_the_device(self):
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        recorded = (self.state / 'kernel-identity.sh').read_text()
        # The ids come from the device itself, so they are the ones the device
        # reports and not the ones of the machine the package was built on.
        self.assertIn('STOCK_NOTE_qca_cld3_peach_v2=different-stock-build', recorded)
        self.assertIn('STOCK_NOTE_mac80211=different-stock-build', recorded)

    def test_vendor_copy_of_a_module_carries_the_stock_build_id(self):
        # The vendor copy of a module file holds the same GNU build id as the
        # live module, so it identifies the stock build of this device even
        # when the patched driver is the loaded one.
        self.write_stock_module('mac80211', STOCK_MAC[32:])
        result = self.run_shell('. "$1/bin/common.sh"; vendor_note mac80211', self.module_path)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), STOCK_MAC)

    def test_stock_identity_falls_back_to_the_vendor_copy(self):
        # The patched driver is the loaded one, so the live build id is the
        # build of this package and the vendor copy has to be read instead.
        self.write_identity(note_qca=NOTE_QCA, note_mac=NOTE_MAC)
        self.write_stock_module('mac80211', STOCK_MAC[32:])
        (self.live / 'mac80211').write_text(NOTE_MAC)
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        recorded = (self.state / 'kernel-identity.sh').read_text()
        self.assertIn(f'STOCK_NOTE_mac80211={STOCK_MAC}', recorded)

    def test_stock_identity_ignores_the_build_of_this_package(self):
        # Neither the live module nor the vendor copy may be recorded when
        # both carry the build id of this package.
        self.write_identity(note_qca=NOTE_QCA, note_mac=NOTE_MAC)
        self.write_stock_module('mac80211', NOTE_MAC[32:])
        (self.live / 'mac80211').write_text(NOTE_MAC)
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        recorded = (self.state / 'kernel-identity.sh')
        text = recorded.read_text() if recorded.exists() else ''
        self.assertNotIn('STOCK_NOTE_mac80211=', text)

    def test_stock_identity_keeps_the_value_recorded_before(self):
        # A capture that cannot see the stock build must not drop the value
        # this device recorded earlier.
        (self.state / 'kernel-identity.sh').write_text(
            f'STOCK_NOTE_qca_cld3_peach_v2={STOCK_QCA}\nSTOCK_NOTE_mac80211={STOCK_MAC}\n')
        self.write_identity(note_qca=NOTE_QCA, note_mac=NOTE_MAC)
        (self.live / 'qca_cld3_peach_v2').write_text(NOTE_QCA)
        (self.live / 'mac80211').write_text(NOTE_MAC)
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        recorded = (self.state / 'kernel-identity.sh').read_text()
        self.assertIn(f'STOCK_NOTE_qca_cld3_peach_v2={STOCK_QCA}', recorded)
        self.assertIn(f'STOCK_NOTE_mac80211={STOCK_MAC}', recorded)

    def test_sourced_installer_still_applies_the_kernel_gate_and_records_the_stock_ids(self):
        # A manager sources the installer, so $0 is not the script path. The
        # kernel gate and the identity capture must still run.
        result = self.install_sourced()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('6.6.118-android15-8', result.stdout)
        recorded = (self.state / 'kernel-identity.sh').read_text()
        self.assertIn('STOCK_NOTE_qca_cld3_peach_v2=', recorded)

    def test_missing_runtime_library_is_reported_instead_of_skipping_the_gate(self):
        # The kernel gate must never be skipped silently.
        (self.bin / 'common.sh').unlink()
        result = self.install()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('Runtime library missing', result.stdout + result.stderr)
        self.assertFalse(self.marker.exists())
        result = self.start()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertFalse(self.marker.exists())

    def test_unknown_original_build_is_allowed_during_restore(self):
        code = (self.bin / 'control.sh').read_text().split('case "${1:-status}"', 1)[0]
        code = code.replace('MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)', 'MODDIR=' + str(self.module))
        (self.bin / 'functions.sh').write_text(code)
        result = self.run_shell('. "$1/bin/functions.sh"; restore_state_allowed', str(self.module))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_restore_accepts_reloaded_originals_with_different_build_ids(self):
        code = (self.bin / 'control.sh').read_text().split('case "${1:-status}"', 1)[0]
        code = code.replace('MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)', 'MODDIR=' + str(self.module))
        (self.bin / 'functions.sh').write_text(code)
        (self.bin / 'provider.sh').write_text('exit 0\n')
        result = self.run_shell('''. "$1/bin/functions.sh"
NOTE_qca_cld3_peach_v2=our-wlan
NOTE_mac80211=our-mac
stop_native() { :; }
restore_session_wifi() { :; }
framework() { :; }
wifi_wait() { :; }
sleep() { :; }
pause_wifi_hal() { :; }
resume_wifi_hal() { :; }
wifi_restore() { :; }
same_boot() { case "$1" in */qca-swapped|*/mac-swapped) return 0;; *) return 1;; esac; }
# Restoring previously reloaded originals must not compare a build ID of
# another device.
restore_owned
''', str(self.module))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('STOCK_STACK_RESTORED', result.stdout)

    def test_restore_rejects_a_foreign_original_build(self):
        code = (self.bin / 'control.sh').read_text().split('case "${1:-status}"', 1)[0]
        code = code.replace('MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)', 'MODDIR=' + str(self.module))
        (self.bin / 'functions.sh').write_text(code)
        (self.bin / 'provider.sh').write_text('exit 0\n')
        result = self.run_shell('''. "$1/bin/functions.sh"
NOTE_qca_cld3_peach_v2=our-wlan
NOTE_mac80211=our-mac
STOCK_NOTE_qca_cld3_peach_v2=the-build-of-this-device
stop_native() { :; }
restore_session_wifi() { :; }
framework() { :; }
wifi_wait() { :; }
sleep() { :; }
pause_wifi_hal() { :; }
resume_wifi_hal() { :; }
wifi_restore() { :; }
same_boot() { case "$1" in */qca-swapped|*/mac-swapped) return 0;; *) return 1;; esac; }
loaded() { case "$1" in qca_cld3_peach_v2) return 0;; *) return 1;; esac; }
rmmod() { :; }
insmod() { :; }
restore_owned
''', str(self.module))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('not the original build of this device', result.stdout + result.stderr)

    # ------------------------------------------------------------------
    # Boot-time auto-start resilience.
    # ------------------------------------------------------------------

    def control_functions(self):
        """Write the top-level functions of control.sh for direct sourcing."""
        code = (self.bin / 'control.sh').read_text().split('case "${1:-status}"', 1)[0]
        code = code.replace('MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)', 'MODDIR=' + str(self.module))
        (self.bin / 'functions.sh').write_text(code)

    def test_start_failure_before_the_swap_restores_the_factory_stack(self):
        # A half-swapped stack has to be rolled back: the device would be
        # left without a usable WLAN driver.
        self.control_functions()
        result = self.run_shell('''. "$1/bin/functions.sh"
swap_done=0
boot_id() { echo test-boot-id; }
restore_owned() { echo STOCK_STACK_RESTORED; }
resume_wifi_hal() { :; }
start_failure_handler 6
''', str(self.module))
        self.assertIn('STOCK_STACK_RESTORED', result.stdout)
        self.assertNotIn('RECOVERABLE_START_FAILURE', result.stdout)
        self.assertTrue((self.state / 'disabled').exists())

    def test_start_failure_after_the_swap_keeps_the_patched_stack(self):
        # The drivers are already the patched ones, so the failure is in the
        # native/provider layer. Rolling back would throw away a working AWDL
        # stack and latch the module off for the whole boot.
        self.control_functions()
        result = self.run_shell('''. "$1/bin/functions.sh"
swap_done=1
restore_owned() { echo STOCK_STACK_RESTORED; }
resume_wifi_hal() { :; }
start_failure_handler 6
''', str(self.module))
        self.assertNotIn('STOCK_STACK_RESTORED', result.stdout)
        self.assertIn('RECOVERABLE_START_FAILURE', result.stdout)
        self.assertFalse((self.state / 'disabled').exists())

    def test_stack_lock_waits_for_the_running_operation(self):
        # toybox flock has no -w, so the wait is a bounded poll. The lock
        # must be acquired once the other operation releases it instead of
        # failing the whole start.
        self.control_functions()
        counter = self.base / 'flock-calls'
        counter.write_text('0')
        flock = self.tools / 'flock'
        flock.write_text(f'''#!/bin/sh
n=$(cat "{counter.as_posix()}")
n=$((n+1))
echo "$n" > "{counter.as_posix()}"
[ "$n" -ge 3 ]
''')
        flock.chmod(0o755)
        result = self.run_shell('''. "$1/bin/functions.sh"
STACK_LOCK_WAIT_TRIES=10
STACK_LOCK_WAIT_STEP=0
sleep() { :; }
lock_stack
echo LOCK_ACQUIRED
''', str(self.module))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('LOCK_ACQUIRED', result.stdout)
        self.assertEqual(counter.read_text().strip(), '3')

    def test_stack_lock_gives_up_after_the_wait_window(self):
        self.control_functions()
        flock = self.tools / 'flock'
        flock.write_text('#!/bin/sh\nexit 1\n')
        flock.chmod(0o755)
        result = self.run_shell('''. "$1/bin/functions.sh"
STACK_LOCK_WAIT_TRIES=3
STACK_LOCK_WAIT_STEP=0
sleep() { :; }
lock_stack
''', str(self.module))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Another stack operation is running', result.stdout)

    def test_service_keeps_retrying_a_recoverable_start_failure(self):
        # A recoverable failure must not latch the module off and must not
        # end the boot sequence; the module retries on its own.
        service = (ROOT / 'module/service.sh').read_text()
        self.assertIn('RECOVERABLE_START_FAILURE', service)
        self.assertIn('fatal_attempts=0', service)
        # The module is only disabled after three genuinely fatal attempts.
        self.assertIn('[ "$fatal_attempts" -ge 3 ] && break', service)

    def test_service_does_not_disable_on_a_missing_root(self):
        service = (ROOT / 'module/service.sh').read_text()
        # The `disabled` marker is only written on the fatal path.
        self.assertEqual(service.count('touch "$STATE/disabled"'), 1)
        self.assertIn('if [ "$root_seen" = "1" ] && [ "$fatal_attempts" -ge 3 ]', service)

    # ------------------------------------------------------------------
    # The provider is not registered yet during the early cold boot.
    # ------------------------------------------------------------------

    def test_start_reports_a_recoverable_failure_when_the_provider_is_not_ready(self):
        # The provider service is not registered yet right after the boot,
        # so the idle check cannot confirm its state. The stack has not been
        # swapped at this point, so the start must be reported as retryable
        # instead of being latched off as a fatal failure.
        (self.bin / 'transfer-idle.sh').write_text('exit 12\n')
        result = self.start()
        self.assertEqual(result.returncode, 12, result.stdout + result.stderr)
        self.assertIn('RECOVERABLE_START_FAILURE', result.stdout)
        self.assertFalse((self.state / 'disabled').exists())

    def test_service_retries_a_provider_not_ready_start_failure(self):
        service = (ROOT / 'module/service.sh').read_text()
        self.assertIn('if [ "$start_rc" = 12 ]; then', service)
        self.assertIn('Provider not ready', service)

    def transfer_idle(self, dump='', framework_fails=False, provider_alive=True):
        """Run the production transfer-idle.sh against a simulated provider."""
        shutil.copy2(ROOT / 'module/bin/transfer-idle.sh', self.bin)
        shutil.copy2(ROOT / 'module/bin/idle-sleep.sh', self.bin)
        common = self.bin / 'common.sh'
        common.write_text(common.read_text() + '''
framework() {
    if [ "${TEST_FRAMEWORK_FAIL:-0}" = 1 ];then return 7; fi
    printf '%s\\n' "${TEST_PROVIDER_DUMP:-}"
}
''')
        pidof = self.tools / 'pidof'
        pidof.write_text('#!/bin/sh\n' + ('echo 4242\n' if provider_alive else 'exit 1\n'))
        pidof.chmod(0o755)
        env = dict(self.env, TEST_PROVIDER_DUMP=dump,
                   TEST_FRAMEWORK_FAIL='1' if framework_fails else '0')
        return subprocess.run(['sh', (self.bin / 'transfer-idle.sh').as_posix()],
                              env=env, capture_output=True, text=True, timeout=10)

    def test_transfer_idle_reports_a_not_ready_provider_instead_of_a_busy_one(self):
        # A dump that carries no usable state means the provider is not ready
        # yet, not that a transfer is running. The caller must be able to
        # tell the two apart: one is retried, the other is refused.
        result = self.transfer_idle(dump='')
        self.assertEqual(result.returncode, 12, result.stdout + result.stderr)
        result = self.transfer_idle(dump='ServiceRecord{com.google.android.mosey/.ExternalSharingService}')
        self.assertEqual(result.returncode, 12, result.stdout + result.stderr)
        result = self.transfer_idle(dump='', framework_fails=True)
        self.assertEqual(result.returncode, 12, result.stdout + result.stderr)

    def test_transfer_idle_still_refuses_a_running_transfer(self):
        result = self.transfer_idle(dump='ExternalSharingService state: Active Transfers (1)')
        self.assertEqual(result.returncode, 9, result.stdout + result.stderr)

    def test_transfer_idle_accepts_an_idle_provider(self):
        dump = ('ExternalSharingService state: Active Transfers (0)\n'
                'Receive provider transferring: false')
        result = self.transfer_idle(dump=dump)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_transfer_idle_accepts_a_missing_provider_process(self):
        # Without a provider process there cannot be a running transfer.
        result = self.transfer_idle(dump='', provider_alive=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main(verbosity=2)
