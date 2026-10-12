"""Strict host-only adb/Gradle fixtures. No real Android commands are executed."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from test_device_reports import CADENCE, CORE, OFF, ON, SHIZUKU

RUNNER = Path(__file__).with_name("check_android_device.sh")

# Commands not explicitly admitted below are errors, not silently successful mocks.
FAKE_ADB = r'''
import json, os, sys
from pathlib import Path
sys.path.insert(0, str(Path.cwd() / 'scripts'))
from test_device_reports import raw, CORE, ON, OFF, SHIZUKU, CADENCE
config = json.loads(Path('config.json').read_text())
state_path = Path('state.json')
state = json.loads(state_path.read_text())
args = sys.argv[1:]
with Path('calls.jsonl').open('a') as stream: stream.write(json.dumps(['adb'] + args) + '\n')
def save(): state_path.write_text(json.dumps(state))
def refuse(message):
    print(message, file=sys.stderr); sys.exit(97)
if args == ['get-serialno']: print(config.get('serial', 'emulator-5554'))
elif args[:2] == ['shell', 'getprop'] and len(args) == 3:
    defaults = {'ro.build.version.sdk': '36', 'ro.hardware': 'ranchu', 'sys.boot_completed': '1'}
    if args[2] not in defaults: refuse('unknown getprop')
    print(config.get(args[2], defaults[args[2]]))
    sys.exit(config.get('getprop_exits', {}).get(args[2], 0))
elif args == ['shell', 'getconf', 'PAGE_SIZE']: print(config.get('page', '4096'))
elif args == ['shell', 'rm', '-rf', '/sdcard/Download/batstats-validation-screenshots']:
    if config.get('screenshot_cleanup_fail'): sys.exit(23)
elif args in (['logcat', '-c'], ['logcat', '-b', 'crash', '-c'], ['logcat', '-d', '-b', 'crash'], ['logcat', '-d', '-v', 'threadtime', '-t', '4000']): pass
elif len(args) == 3 and args[:2] == ['pull', '/sdcard/Download/batstats-validation-screenshots']: sys.exit(1)
elif args in (['install', '-r', 'app/build/outputs/apk/debug/app-universal-debug.apk'], ['install', '-r', '-t', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']):
    if state['slept'] and not state['on']: refuse('install while off')
    if config.get('install_fail') or (state['core'] and config.get('reinstall_fail')): sys.exit(7)
    state['installed'] = True; save(); print('Success')
elif len(args) == 4 and args[:3] == ['shell', 'pm', 'path']:
    if args[3] not in ('com.akane.voltwise.debug', 'com.akane.voltwise.debug.test'): refuse('unknown package')
    if state['installed']: print('package:/data/app/base.apk')
elif args in (['shell', 'input', 'keyevent', '224'], ['shell', 'input', 'keyevent', '223']):
    wake = args[-1] == '224'
    if wake:
        state['wakes'] += 1
        if config.get('wake_fail') or (config.get('cleanup_fail') and state['wakes'] >= 4): save(); sys.exit(8)
    elif config.get('sleep_fail'): sys.exit(9)
    state['on'] = wake
    if not wake: state['slept'] = True
    save()
elif args == ['shell', 'wm', 'dismiss-keyguard']:
    if config.get('dismiss_fail'): sys.exit(8)
elif args == ['shell', 'dumpsys', 'power']:
    if config.get('power_adb_fail'): sys.exit(11)
    if config.get('bad_power'): print('unrecognized state')
    elif config.get('stuck_off') and state['slept']: print('  mWakefulness=Awake')
    elif config.get('stuck_on') and not state['slept']: print('  mWakefulness=Asleep')
    else: print('  mWakefulness=' + ('Awake' if state['on'] else 'Asleep'))
    if config.get('power_groups'): print('    mWakefulness=' + ('Awake' if state['on'] else 'Asleep'))
    if config.get('conflicting_power'): print('    mWakefulness=Dreaming')
elif args[:5] == ['shell', 'am', 'instrument', '-w', '-r']:
    if not state['installed']: refuse('instrumentation without APK pair')
    if args[-1] != 'com.akane.voltwise.debug.test/androidx.test.runner.AndroidJUnitRunner': refuse('unknown runner')
    flags = args[5:-1]
    if len(flags) % 3: refuse('malformed extras')
    extras = {}
    for i in range(0, len(flags), 3):
        if flags[i] != '-e' or flags[i+1] in extras: refuse('bad extra')
        extras[flags[i+1]] = flags[i+2]
    if extras.pop('expectedPageSize', None) != config.get('page', '4096'): refuse('missing page assertion')
    annotation = 'com.akane.voltwise.test.RequiresShizuku'
    if extras == {'log': 'true', 'notAnnotation': annotation}:
        phase = 'inventory-ordinary'; identities = CORE + [ON, OFF]
        if config.get('third_cadence'): identities += [CADENCE + '#third']
    elif extras == {'log': 'true', 'annotation': annotation}:
        phase = 'inventory-shizuku'; identities = [SHIZUKU]
        if config.get('wrong_privileged'): identities = [ON]
    elif extras == {'notAnnotation': annotation, 'notClass': CADENCE}:
        phase = 'core'; identities = CORE
        if not state['on']: refuse('core while off')
        state['core'] = True; save()
    elif extras == {'class': ON}:
        phase = 'screen-on'; identities = [ON]
        if not state['on']: refuse('on while off')
    elif extras == {'class': OFF}:
        phase = 'screen-off'; identities = [OFF]
        if state['on']: refuse('off while on')
    elif extras == {'class': SHIZUKU}:
        phase = 'shizuku'; identities = [SHIZUKU]
        if not state['on']: refuse('Shizuku while off')
    else: refuse('unknown or unsplit selector: ' + repr(extras))
    if config.get('substitute_core') and phase == 'core': identities = [CORE[0], 'com.akane.voltwise.Other#substitute']
    if config.get('wrong_singleton') == phase: identities = [CORE[0]]
    if config.get('duplicate_singleton') == phase: identities += identities
    if config.get('missing_singleton') == phase: identities = []
    output = raw(identities)
    if config.get('raw_skip') == phase: output = output.replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -4')
    if config.get('truncated') == phase: output = output.split('INSTRUMENTATION_STATUS_CODE: 0')[0]
    print(output, end='')
    if config.get('adb_fail') == phase: sys.exit(12)
else: refuse('unknown adb command: ' + repr(args))
'''

FAKE_GRADLE = r'''
import json, sys
from pathlib import Path
sys.path.insert(0, str(Path.cwd() / 'scripts'))
from test_device_reports import CORE, CADENCE, xml
config = json.loads(Path('config.json').read_text())
args = sys.argv[1:]
with Path('calls.jsonl').open('a') as stream: stream.write(json.dumps(['gradle'] + args) + '\n')
expected = [':app:connectedDebugAndroidTest', '--no-daemon', '--no-configuration-cache', '--stacktrace',
            '-Pandroid.testInstrumentationRunnerArguments.expectedPageSize=' + config.get('page', '4096'),
            '-Pandroid.testInstrumentationRunnerArguments.additionalTestOutputDir=/sdcard/Download/batstats-validation-screenshots',
            '-Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.akane.voltwise.test.RequiresShizuku',
            '-Pandroid.testInstrumentationRunnerArguments.notClass=' + CADENCE]
if args != expected: print('unknown/unsplit Gradle invocation', file=sys.stderr); sys.exit(97)
state = json.loads(Path('state.json').read_text())
if not state['on']: sys.exit(97)
state['core'] = True
state['installed'] = not config.get('agp_uninstall', True)
Path('state.json').write_text(json.dumps(state))
identities = CORE if not config.get('substitute_core') else [CORE[0], 'com.akane.voltwise.Other#substitute']
canonical = Path('app/build/outputs/androidTest-results/connected/debug')
canonical.mkdir(parents=True, exist_ok=True)
content = xml(identities)
if config.get('xml_assumption'): content = xml(identities, '<failure message="AssumptionViolatedException"/>', failures=2)
if config.get('xml_duplicate'): content = xml([CORE[0], CORE[0]])
if config.get('xml_counts'): content = xml(identities, tests=3)
if not config.get('xml_missing'): (canonical / 'TEST-core.xml').write_text(content)
if config.get('xml_mirror'): (canonical / 'TEST-mirror.xml').write_text(content)
# A green noncanonical mirror must never rescue bad/missing canonical reports.
mirror = Path('app/build/reports/androidTests/connected/debug'); mirror.mkdir(parents=True, exist_ok=True)
(mirror / 'TEST-green.xml').write_text(xml(CORE))
output = Path('app/build/outputs/connected_android_test_additional_output'); output.mkdir(parents=True, exist_ok=True)
(output / 'core.png').write_text('fake screenshot')
sys.exit(config.get('gradle_exit', 0))
'''


class DevicePhasesTest(unittest.TestCase):
    def run_phases(self, prebuilt=False, **config):
        temporary = tempfile.TemporaryDirectory(prefix="batstats-phase-test-")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / "bin").mkdir()
        (root / "scripts").mkdir()
        for name in ("check_instrumentation_result.py", "test_device_reports.py"):
            shutil.copyfile(RUNNER.with_name(name), root / "scripts" / name)
        for name, content in (("bin/adb", FAKE_ADB), ("gradlew", FAKE_GRADLE)):
            path = root / name
            path.write_text("#!/usr/bin/env python3\n" + content)
            path.chmod(0o755)
        (root / "scripts/prepare_shizuku.py").write_text(
            "import json,sys\nfrom pathlib import Path\n"
            "with Path('calls.jsonl').open('a') as stream: stream.write(json.dumps(['setup'])+'\\n')\n"
            "sys.exit(json.loads(Path('config.json').read_text()).get('setup_exit',0))\n")
        (root / "config.json").write_text(json.dumps(config))
        (root / "state.json").write_text(json.dumps(dict(on=True, slept=False, installed=False, wakes=0, core=False)))
        sibling = root / "app/build/reports/device-validation/other-image"
        sibling.mkdir(parents=True)
        (sibling / "retained.txt").write_text("earlier evidence")
        # Stale canonical results must be removed before the connected core phase.
        stale = root / "app/build/outputs/androidTest-results/connected/debug"
        stale.mkdir(parents=True)
        (stale / "TEST-stale.xml").write_text("stale")
        environment = dict(os.environ, PATH=str(root / "bin") + os.pathsep + os.environ["PATH"],
                           ANDROID_SERIAL=config.get("serial", "emulator-5554"),
                           BATSTATS_EXPECTED_PAGE_SIZE=config.get("expected_page", "4096"),
                           BATSTATS_REPORT_GROUP=config.get("group", "standard"), PYTHONDONTWRITEBYTECODE="1")
        if config.get('parser_fail'):
            (root / 'scripts/check_instrumentation_result.py').write_text('raise SystemExit(13)\n')
        result = subprocess.run(["bash", str(RUNNER)] + (["--prebuilt"] if prebuilt else []),
                                cwd=root, env=environment, text=True, capture_output=True, timeout=30)
        calls = [json.loads(line) for line in (root / "calls.jsonl").read_text().splitlines()] if (root / "calls.jsonl").exists() else []
        return root, result, calls

    @staticmethod
    def executions(calls):
        phases = []
        for call in calls:
            if call[0] == 'gradle': phases.append('core')
            if call[:6] == ['adb', 'shell', 'am', 'instrument', '-w', '-r'] and 'log' not in call:
                if ON in call: phases.append('screen-on')
                elif OFF in call: phases.append('screen-off')
                elif SHIZUKU in call: phases.append('shizuku')
                else: phases.append('core')
        return phases

    def test_both_modes_execute_complete_disjoint_phases_in_verified_states(self):
        for prebuilt in (False, True):
            with self.subTest(prebuilt=prebuilt):
                root, result, calls = self.run_phases(prebuilt)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(['core', 'screen-on', 'screen-off', 'shizuku'], self.executions(calls))
                reports = root / 'app/build/reports/device-validation/standard'
                for phase in ('core', 'screen-on', 'screen-off', 'shizuku'):
                    self.assertIn(f'{phase.replace("-", "_")}_exit=0', (reports / 'phase-status.txt').read_text())
                for phase in ('screen-on', 'screen-off', 'shizuku'):
                    self.assertTrue((reports / f'{phase}-instrumentation.txt').is_file())
                self.assertEqual(set(CORE), set((reports / 'core-identities.txt').read_text().splitlines()))
                self.assertEqual(set(CORE + [ON, OFF]), set((reports / 'ordinary-identities.txt').read_text().splitlines()))
                off = next(i for i, call in enumerate(calls) if OFF in call)
                sleep = calls.index(['adb', 'shell', 'input', 'keyevent', '223'])
                self.assertEqual([['adb', 'shell', 'dumpsys', 'power']], calls[sleep+1:off])
                inventory = next(i for i, call in enumerate(calls) if 'notAnnotation' in call and 'log' in call)
                wake = calls.index(['adb', 'shell', 'input', 'keyevent', '224'])
                on = next(i for i, call in enumerate(calls) if ON in call)
                self.assertLess(inventory, wake)
                self.assertLess(wake, on)
                self.assertLess(on, sleep)
                self.assertEqual(['adb', 'shell', 'input', 'keyevent', '224'], calls[off+1])
                if not prebuilt:
                    self.assertTrue((reports / 'core-screenshots/core.png').is_file())
                    self.assertEqual(4, sum(call[1:2] == ['install'] for call in calls))
                    self.assertTrue((reports / 'core-results/TEST-core.xml').is_file())
                    self.assertFalse((reports / 'core-results/TEST-stale.xml').exists())
                self.assertEqual('earlier evidence', (root / 'app/build/reports/device-validation/other-image/retained.txt').read_text())

    def test_invalid_ordinary_results_fail_aggregate_but_safe_phases_continue(self):
        shared = [dict(wrong_singleton='screen-on'), dict(missing_singleton='screen-off'),
                  dict(duplicate_singleton='screen-off'), dict(raw_skip='screen-off'),
                  dict(truncated='screen-on'), dict(adb_fail='screen-off'), dict(substitute_core=True)]
        connected = [dict(xml_assumption=True), dict(xml_duplicate=True), dict(xml_counts=True),
                     dict(xml_missing=True), dict(xml_mirror=True), dict(gradle_exit=3)]
        for prebuilt in (False, True):
            for config in shared + ([] if prebuilt else connected):
                with self.subTest(prebuilt=prebuilt, config=config):
                    root, result, calls = self.run_phases(prebuilt, **config)
                    self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertEqual(['core', 'screen-on', 'screen-off', 'shizuku'], self.executions(calls))
                    self.assertNotIn('ordinary_exit=0', (root / 'app/build/reports/device-validation/standard/phase-status.txt').read_text())
                    self.assertIn('shizuku_exit=0', result.stdout)
                    if config.get('gradle_exit'): self.assertIn('core_exit=3', result.stdout)

    def test_inventory_errors_never_learn_authority_from_execution(self):
        for prebuilt in (False, True):
            for config in (dict(third_cadence=True), dict(adb_fail='inventory-ordinary'), dict(truncated='inventory-ordinary'), dict(parser_fail=True)):
                with self.subTest(prebuilt=prebuilt, config=config):
                    _, result, calls = self.run_phases(prebuilt, **config)
                    self.assertNotEqual(0, result.returncode)
                    self.assertFalse(set(self.executions(calls)) & {'core', 'screen-on', 'screen-off'})
                    if not config.get('parser_fail'): self.assertEqual(['shizuku'], self.executions(calls))

    def test_guard_refusal_has_zero_mutations_even_cleanup(self):
        configs = [dict(serial='physical'), dict(**{'ro.hardware': 'qcom'}), dict(**{'ro.build.version.sdk': '35'}),
                   dict(**{'sys.boot_completed': '0'}), dict(page='8192'), dict(expected_page='16384'), dict(group='../escape')]
        # Valid stdout must not authorize mutation when the guard command failed.
        configs += [dict(getprop_exits={prop: status}) for prop, status in
                    (('ro.build.version.sdk', 17), ('sys.boot_completed', 18), ('ro.hardware', 19))]
        for prebuilt in (False, True):
            for config in configs:
                with self.subTest(prebuilt=prebuilt, config=config):
                    _, result, calls = self.run_phases(prebuilt, **config)
                    self.assertNotEqual(0, result.returncode, f'executions={self.executions(calls)}')
                    self.assertEqual([], self.executions(calls))
                    self.assertTrue(all(call[1:3] in (['shell', 'getprop'], ['shell', 'getconf']) for call in calls), calls)
                    if config.get('getprop_exits'):
                        self.assertEqual(next(iter(config['getprop_exits'].values())), result.returncode)

    def test_setup_and_privileged_failures_cannot_be_hidden(self):
        for prebuilt in (False, True):
            for config in (dict(setup_exit=5), dict(raw_skip='shizuku'), dict(wrong_singleton='shizuku'), dict(wrong_privileged=True)):
                with self.subTest(prebuilt=prebuilt, config=config):
                    _, result, calls = self.run_phases(prebuilt, **config)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual(['core', 'screen-on', 'screen-off'], self.executions(calls)[:3])
                    self.assertNotIn('shizuku_exit=0', result.stdout)

    def test_install_and_state_failures_only_allow_safe_unaffected_phases(self):
        cases = [(dict(install_fail=True), []), (dict(reinstall_fail=True), ['core']),
                 (dict(wake_fail=True), []), (dict(dismiss_fail=True), []),
                 (dict(stuck_on=True), []), (dict(bad_power=True), []), (dict(power_adb_fail=True), []),
                 (dict(conflicting_power=True), []),
                 (dict(sleep_fail=True), ['core', 'screen-on', 'shizuku']),
                 (dict(stuck_off=True), ['core', 'screen-on', 'shizuku'])]
        for prebuilt in (False, True):
            for config, expected in cases:
                if prebuilt and config.get('reinstall_fail'): continue
                with self.subTest(prebuilt=prebuilt, config=config):
                    _, result, calls = self.run_phases(prebuilt, **config)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual(expected, self.executions(calls))

    def test_early_adb_failure_keeps_original_exit_even_when_restoration_fails(self):
        for prebuilt in (False, True):
            root, result, calls = self.run_phases(prebuilt, screenshot_cleanup_fail=True, wake_fail=True)
            self.assertEqual(23, result.returncode)
            self.assertEqual([], self.executions(calls))
            status = (root / 'app/build/reports/device-validation/standard/phase-status.txt').read_text()
            self.assertIn('ordinary_exit=1', status)
            self.assertIn('cleanup_exit=1', status)

    def test_cleanup_failure_changes_success_and_preserves_prior_failure(self):
        for prebuilt in (False, True):
            for config in (dict(cleanup_fail=True), dict(cleanup_fail=True, gradle_exit=3)):
                with self.subTest(prebuilt=prebuilt, config=config):
                    root, result, calls = self.run_phases(prebuilt, **config)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual(['core', 'screen-on', 'screen-off', 'shizuku'], self.executions(calls))
                    self.assertIn('cleanup_exit=1', (root / 'app/build/reports/device-validation/standard/phase-status.txt').read_text())
                    if not prebuilt and config.get('gradle_exit'): self.assertIn('ordinary_exit=3', result.stdout)

    def test_multiple_consistent_power_groups_and_retained_apks_are_supported(self):
        for prebuilt in (False, True):
            _, result, calls = self.run_phases(prebuilt, power_groups=True, agp_uninstall=False)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(['core', 'screen-on', 'screen-off', 'shizuku'], self.executions(calls))

    def test_prebuilt_16k_retains_device_evidence(self):
        root, result, _ = self.run_phases(True, page='16384', expected_page='16384', group='16k')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('page_size=16384', (root / 'app/build/reports/device-validation/16k/device-info.txt').read_text())


if __name__ == '__main__':
    unittest.main()
