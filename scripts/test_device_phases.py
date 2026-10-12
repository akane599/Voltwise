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

# Complete public before-core APP receipt from native push 38255659823 reports.zip.
NATIVE_V2_RECEIPT = (
    "V2 Signer: certificate DN: C=US, O=Android, CN=Android Debug\n"
    "V2 Signer: certificate SHA-256 digest: 9e8f41f89317efefe0c42a6c967d65f892503f4171169d163232529c909b39d8\n"
    "V2 Signer: certificate SHA-1 digest: b54520e0d46ead43cf5db4c4e4d27f92b166a091\n"
    "V2 Signer: certificate MD5 digest: cf3e8310ccf30291228609c381d5b3a9\n"
)
RECEIPT_BOUNDARIES = ('before-app', 'before-test', 'after-app', 'after-test')

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
if args == ['get-serialno']:
    print(config.get('serial', 'emulator-5554')); sys.exit(config.get('serial_exit', 0))
elif args[:2] == ['shell', 'getprop'] and len(args) == 3:
    defaults = {'ro.build.version.sdk': '36', 'ro.hardware': 'ranchu', 'sys.boot_completed': '1'}
    if args[2] not in defaults: refuse('unknown getprop')
    print(config.get(args[2], defaults[args[2]]))
    sys.exit(config.get('getprop_exits', {}).get(args[2], 0))
elif args == ['shell', 'getconf', 'PAGE_SIZE']:
    print(config.get('page', '4096')); sys.exit(config.get('page_exit', 0))
elif args == ['shell', 'rm', '-rf', '/sdcard/Download/batstats-validation-screenshots']:
    if config.get('screenshot_cleanup_fail'): sys.exit(23)
elif args in (['logcat', '-c'], ['logcat', '-b', 'crash', '-c'], ['logcat', '-d', '-b', 'crash'], ['logcat', '-d', '-v', 'threadtime', '-t', '4000']): pass
elif len(args) == 3 and args[:2] == ['pull', '/sdcard/Download/batstats-validation-screenshots']: sys.exit(1)
elif args in (['install', '-r', 'app/build/outputs/apk/debug/app-universal-debug.apk'], ['install', '-r', '-t', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']):
    if state['slept'] and not state['on']: refuse('install while off')
    if config.get('install_fail') or (state['core'] and config.get('reinstall_fail')): sys.exit(7)
    package = 'test' if '-t' in args else 'app'
    if config.get('install_fail_package') == package or state['core'] and config.get('reinstall_fail_package') == package: sys.exit(7)
    signer = Path(args[-1]).read_text()
    if state['installed'][package] not in (None, signer):
        print('INSTALL_FAILED_UPDATE_INCOMPATIBLE', file=sys.stderr); sys.exit(7)
    state['installed'][package] = signer; save(); print('Success')
elif len(args) == 2 and args[0] == 'uninstall':
    packages = {'com.akane.voltwise.debug.test': 'test', 'com.akane.voltwise.debug': 'app'}
    if args[1] not in packages: refuse('unknown removal package')
    if state['slept']: refuse('removal after off preparation')
    if config.get('remove_fail') == args[1]: sys.exit(24)
    if config.get('remove_bad_output') == args[1]: print('Failure'); sys.exit(0)
    state['installed'][packages[args[1]]] = None; save(); print('Success')
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
    if not all(state['installed'].values()): refuse('instrumentation without APK pair')
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
    if state['core'] and config.get('post_inventory_substitution') == phase:
        identities = identities[:-1] + ['com.akane.voltwise.Other#substitute']
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
state = json.loads(Path('state.json').read_text())
if state['slept']: print('build after off preparation', file=sys.stderr); sys.exit(97)
if args == [':app:assembleDebug', ':app:assembleDebugAndroidTest', '--no-daemon', '--no-configuration-cache', '--stacktrace']:
    sys.exit(config.get('prepare_exit', 0))
if args != expected: print('unknown/unsplit Gradle invocation', file=sys.stderr); sys.exit(97)
if not state['on']: sys.exit(97)
state['core'] = True
if config.get('signer_transition'):
    for apk in ('app/build/outputs/apk/debug/app-universal-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
        Path(apk).write_text('B')
# AGP's failed install still exits Gradle0, exactly as the native failure did.
if any(signer not in (None, Path(apk).read_text()) for signer, apk in zip(
        (state['installed']['app'], state['installed']['test']),
        ('app/build/outputs/apk/debug/app-universal-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))):
    state['incompatible'] = True
    if config.get('agp_uninstall', True): state['installed'] = {'app': None, 'test': None}
    Path('state.json').write_text(json.dumps(state))
    print('INSTALL_FAILED_UPDATE_INCOMPATIBLE', file=sys.stderr); sys.exit(0)
state['executed_core'] = list(CORE)
state['installed'] = {'app': None, 'test': None} if config.get('agp_uninstall', True) else {
    'app': Path('app/build/outputs/apk/debug/app-universal-debug.apk').read_text(),
    'test': Path('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk').read_text()}
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


FAKE_METADATA = r'''
import hashlib, json, sys
from pathlib import Path
args = sys.argv[1:]
tool = Path(sys.argv[0]).name
with Path('calls.jsonl').open('a') as stream: stream.write(json.dumps([tool] + args) + '\n')
config = json.loads(Path('config.json').read_text())
state = json.loads(Path('state.json').read_text())
apks = ('app/build/outputs/apk/debug/app-universal-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if state['slept']: sys.exit(97)
if (tool == 'apksigner' and args[:-1] != ['verify', '--print-certs']) or (tool == 'sha256sum' and len(args) != 1) or args[-1] not in apks: sys.exit(97)
stage = 'after' if state['core'] else 'before'
if config.get('metadata_fail') == stage and tool == 'apksigner' or config.get('hash_fail') == stage and tool == 'sha256sum': sys.exit(25)
digest = hashlib.sha256(Path(args[-1]).read_bytes()).hexdigest()
if tool == 'sha256sum': print(digest + '  ' + args[-1])
else:
    boundary = stage + '-' + ('app' if args[-1] == apks[0] else 'test')
    output = config.get('metadata_outputs', {}).get(boundary)
    if output is not None: print(output, end='')
    elif config.get('metadata_bad_output') == stage: print('missing fingerprint')
    else: print('Signer #1 certificate SHA-256 digest: ' + ('a' if Path(args[-1]).read_text() == 'A' else 'b') * 64)
    status = config.get('metadata_exits', {}).get(boundary, 0)
    state.setdefault('signer_results', {})[boundary] = {'tool_path': sys.argv[0], 'verify_exit': status}
    Path('state.json').write_text(json.dumps(state))
    sys.exit(status)
'''


class DevicePhasesTest(unittest.TestCase):
    def run_phases(self, prebuilt=False, runner=RUNNER, **config):
        temporary = tempfile.TemporaryDirectory(prefix="batstats-phase-test-")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.assertEqual([], list(root.iterdir()), 'fixture must start empty')
        (root / "bin").mkdir()
        (root / "scripts").mkdir()
        for name in ("check_instrumentation_result.py", "test_device_reports.py"):
            shutil.copyfile(RUNNER.with_name(name), root / "scripts" / name)
        for name, content in (("bin/adb", FAKE_ADB), ("gradlew", FAKE_GRADLE),
                              ("bin/apksigner", FAKE_METADATA), ("bin/sha256sum", FAKE_METADATA)):
            path = root / name
            path.write_text("#!/usr/bin/env python3\n" + content)
            path.chmod(0o755)
        (root / "scripts/prepare_shizuku.py").write_text(
            "import json,sys\nfrom pathlib import Path\n"
            "with Path('calls.jsonl').open('a') as stream: stream.write(json.dumps(['setup'])+'\\n')\n"
            "sys.exit(json.loads(Path('config.json').read_text()).get('setup_exit',0))\n")
        (root / "config.json").write_text(json.dumps(config))
        (root / "state.json").write_text(json.dumps(dict(on=True, slept=False, installed={'app': None, 'test': None}, wakes=0, core=False)))
        for apk in ('app/build/outputs/apk/debug/app-universal-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
            path = root / apk
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('A')
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
        if config.get('discover_serial'):
            environment.pop('ANDROID_SERIAL', None)
        if config.get('parser_fail'):
            (root / 'scripts/check_instrumentation_result.py').write_text('raise SystemExit(13)\n')
        result = subprocess.run(["bash", str(runner)] + (["--prebuilt"] if prebuilt else []),
                                cwd=root, env=environment, text=True, capture_output=True, timeout=30)
        calls = [json.loads(line) for line in (root / "calls.jsonl").read_text().splitlines()] if (root / "calls.jsonl").exists() else []
        return root, result, calls

    @staticmethod
    def executions(calls):
        phases = []
        for call in calls:
            if call[0] == 'gradle' and call[1] == ':app:connectedDebugAndroidTest': phases.append('core')
            if call[:6] == ['adb', 'shell', 'am', 'instrument', '-w', '-r'] and 'log' not in call:
                if ON in call: phases.append('screen-on')
                elif OFF in call: phases.append('screen-off')
                elif SHIZUKU in call: phases.append('shizuku')
                else: phases.append('core')
        return phases

    def assert_signer_command(self, root, boundary, status):
        stage, name = boundary.split('-')
        reports = root / 'app/build/reports/device-validation/standard'
        actual = json.loads((root / 'state.json').read_text())['signer_results'][boundary]
        self.assertEqual({'tool_path': str(root / 'bin/apksigner'), 'verify_exit': status}, actual)
        self.assertEqual(f'tool_path={actual["tool_path"]}\nverify_exit={status}\n',
                         (reports / f'{stage}-core-{name}-signer-command.txt').read_text())

    def assert_metadata_stops_dependents(self, root, result, calls, boundary, status, verify_status=0):
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(f'apk_metadata_exit={status}\n', result.stdout)
        self.assertIn(f'ordinary_exit={status}\n', result.stdout)
        self.assert_signer_command(root, boundary, verify_status)
        before = boundary.startswith('before-')
        self.assertEqual([] if before else ['core'], self.executions(calls))
        self.assertEqual(0 if before else 2, sum(call[1:2] == ['install'] for call in calls))
        self.assertEqual(0 if before else 2, sum(call[1:2] == ['uninstall'] for call in calls))
        self.assertEqual(0 if before else 2, sum('log' in call for call in calls))
        self.assertNotIn(['setup'], calls)
        self.assertNotIn(['adb', 'shell', 'input', 'keyevent', '223'], calls)
        state = json.loads((root / 'state.json').read_text())
        if before:
            self.assertEqual({'app': None, 'test': None}, state['installed'])
        else:
            self.assertEqual(CORE, state['executed_core'])

    def test_native_v2_receipts_execute_original_core_and_full_inventory(self):
        root, result, calls = self.run_phases(
            signer_transition=True,
            metadata_outputs=dict.fromkeys(RECEIPT_BOUNDARIES, NATIVE_V2_RECEIPT))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(['core', 'screen-on', 'screen-off', 'shizuku'], self.executions(calls))
        state = json.loads((root / 'state.json').read_text())
        self.assertEqual(CORE, state['executed_core'])
        reports = root / 'app/build/reports/device-validation/standard'
        self.assertEqual(set(CORE), set((reports / 'core-identities.txt').read_text().splitlines()))
        self.assertEqual(set(CORE + [ON, OFF]), set((reports / 'ordinary-identities.txt').read_text().splitlines()))
        self.assertEqual([SHIZUKU], (reports / 'shizuku-identities.txt').read_text().splitlines())
        for boundary in RECEIPT_BOUNDARIES:
            stage, name = boundary.split('-')
            self.assertEqual(NATIVE_V2_RECEIPT, (reports / f'{stage}-core-{name}-signer.txt').read_text())
            self.assert_signer_command(root, boundary, 0)

    def test_native_v2_malformed_receipts_fail_closed_at_all_boundaries(self):
        label = 'V2 Signer: certificate SHA-256 digest: '
        line = NATIVE_V2_RECEIPT.splitlines()[1]
        controls = {
            'empty': '',
            'missing-sha256': NATIVE_V2_RECEIPT.replace(line + '\n', ''),
            'arbitrary-checksum': 'SHA256 checksum: ' + 'a' * 64 + '\n',
            'wrong-signer-label': 'V3 Signer: certificate SHA-256 digest: ' + 'a' * 64 + '\n',
            'wrong-digest-label': 'V2 Signer: certificate SHA-1 digest: ' + 'a' * 64 + '\n',
            'empty-digest': label + '\n',
            'short-digest': label + 'a' * 63 + '\n',
            'long-digest': label + 'a' * 65 + '\n',
            'nonhex-digest': label + 'g' * 64 + '\n',
            'leading-junk': 'prefix ' + line + '\n',
            'trailing-junk': line + ' suffix\n',
        }
        for boundary in RECEIPT_BOUNDARIES:
            for control, output in controls.items():
                with self.subTest(boundary=boundary, control=control):
                    outputs = dict.fromkeys(RECEIPT_BOUNDARIES, NATIVE_V2_RECEIPT)
                    outputs[boundary] = output
                    root, result, calls = self.run_phases(metadata_outputs=outputs)
                    self.assert_metadata_stops_dependents(root, result, calls, boundary, 1)
                    stage, name = boundary.split('-')
                    receipt = root / f'app/build/reports/device-validation/standard/{stage}-core-{name}-signer.txt'
                    self.assertEqual(output, receipt.read_text())

    def test_native_v2_valid_stdout_nonzero_preserves_actual_status_at_all_boundaries(self):
        for boundary in RECEIPT_BOUNDARIES:
            with self.subTest(boundary=boundary):
                root, result, calls = self.run_phases(
                    metadata_outputs=dict.fromkeys(RECEIPT_BOUNDARIES, NATIVE_V2_RECEIPT),
                    metadata_exits={boundary: 25})
                self.assert_metadata_stops_dependents(root, result, calls, boundary, 25, verify_status=25)
                stage, name = boundary.split('-')
                receipt = root / f'app/build/reports/device-validation/standard/{stage}-core-{name}-signer.txt'
                self.assertEqual(NATIVE_V2_RECEIPT, receipt.read_text())

    def test_connected_signer_transition_fresh_install_executes_original_core(self):
        root, result, calls = self.run_phases(signer_transition=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        state = json.loads((root / 'state.json').read_text())
        self.assertEqual(CORE, state['executed_core'])
        self.assertFalse(state.get('incompatible', False))
        reports = root / 'app/build/reports/device-validation/standard'
        self.assertEqual(set(CORE), set((reports / 'core-identities.txt').read_text().splitlines()))
        removals = [call for call in calls if call[1:2] == ['uninstall']]
        self.assertEqual([['adb', 'uninstall', 'com.akane.voltwise.debug.test'],
                          ['adb', 'uninstall', 'com.akane.voltwise.debug']], removals)
        for stage, fingerprint in (('before-core', 'a'), ('after-core', 'b')):
            for apk in ('app', 'test'):
                self.assertIn(fingerprint * 64, (reports / f'{stage}-{apk}-signer.txt').read_text())
                self.assertEqual(64, len((reports / f'{stage}-{apk}-sha256.txt').read_text().split()[0]))
        self.assertNotEqual((reports / 'before-core-app-sha256.txt').read_text(),
                            (reports / 'after-core-app-sha256.txt').read_text())

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
                    prepare = next(i for i, call in enumerate(calls) if call[1:2] == [':app:assembleDebug'])
                    first_install = next(i for i, call in enumerate(calls) if call[1:2] == ['install'])
                    privileged_inventory = next(i for i, call in enumerate(calls) if 'annotation' in call and 'log' in call)
                    removal = next(i for i, call in enumerate(calls) if call[1:2] == ['uninstall'])
                    core = next(i for i, call in enumerate(calls) if call[1:2] == [':app:connectedDebugAndroidTest'])
                    self.assertLess(prepare, first_install)
                    self.assertLess(first_install, inventory)
                    self.assertLess(privileged_inventory, removal)
                    self.assertLess(removal, core)
                    self.assertEqual(4, sum('log' in call for call in calls))
                    restored_inventory = max(i for i, call in enumerate(calls) if 'log' in call)
                    self.assertLess(core, restored_inventory)
                    self.assertLess(restored_inventory, on)
                else:
                    self.assertFalse(any(call[0] in ('gradle', 'apksigner', 'sha256sum') or call[1:2] == ['uninstall'] for call in calls))
                self.assertFalse(any(call[0] == 'gradle' or call[1:2] in (['install'], ['uninstall']) for call in calls[sleep:]))
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
        configs += [dict(page_exit=20), dict(discover_serial=True, serial_exit=21),
                    dict(discover_serial=True, serial='physical')]
        for prebuilt in (False, True):
            for config in configs:
                with self.subTest(prebuilt=prebuilt, config=config):
                    root, result, calls = self.run_phases(prebuilt, **config)
                    self.assertNotEqual(0, result.returncode, f'executions={self.executions(calls)}')
                    self.assertEqual([], self.executions(calls))
                    self.assertTrue(all(call[1:3] in (['shell', 'getprop'], ['shell', 'getconf']) or call == ['adb', 'get-serialno'] for call in calls), calls)
                    self.assertEqual(dict(on=True, slept=False, installed={'app': None, 'test': None}, wakes=0, core=False),
                                     json.loads((root / 'state.json').read_text()))
                    self.assertFalse((root / 'app/build/reports/device-validation/standard').exists())
                    self.assertEqual('stale', (root / 'app/build/outputs/androidTest-results/connected/debug/TEST-stale.xml').read_text())
                    if config.get('getprop_exits'):
                        self.assertEqual(next(iter(config['getprop_exits'].values())), result.returncode)

    def test_setup_and_privileged_failures_cannot_be_hidden(self):
        for prebuilt in (False, True):
            for config in (dict(setup_exit=5), dict(raw_skip='shizuku'), dict(wrong_singleton='shizuku'), dict(wrong_privileged=True)):
                with self.subTest(prebuilt=prebuilt, config=config):
                    _, result, calls = self.run_phases(prebuilt, **config)
                    self.assertNotEqual(0, result.returncode)
                    expected = [] if not prebuilt and config.get('wrong_privileged') else ['core', 'screen-on', 'screen-off']
                    self.assertEqual(expected, self.executions(calls)[:3])
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

    def test_connected_boundary_failures_stop_dependent_execution(self):
        cases = [(dict(prepare_exit=22), []), (dict(metadata_fail='before'), []),
                 (dict(metadata_bad_output='before'), []), (dict(hash_fail='before'), []),
                 (dict(metadata_fail='after'), ['core']), (dict(metadata_bad_output='after'), ['core']),
                 (dict(hash_fail='after'), ['core']),
                 (dict(install_fail_package='app'), []), (dict(install_fail_package='test'), []),
                 (dict(reinstall_fail_package='app'), ['core']), (dict(reinstall_fail_package='test'), ['core'])]
        for package in ('com.akane.voltwise.debug.test', 'com.akane.voltwise.debug'):
            cases += [(dict(remove_fail=package), []), (dict(remove_bad_output=package), [])]
        for config, expected in cases:
            with self.subTest(config=config):
                root, result, calls = self.run_phases(**config)
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(expected, self.executions(calls))
                self.assertNotIn('unknown', result.stderr)
                if config.get('prepare_exit') or any(config.get(key) == 'before' for key in ('metadata_fail', 'metadata_bad_output', 'hash_fail')):
                    self.assertFalse(any(call[1:2] in (['install'], ['uninstall']) for call in calls))
                if config.get('remove_fail') or config.get('remove_bad_output'):
                    self.assertEqual(1, sum(call == ['adb', 'uninstall', config.get('remove_fail', config.get('remove_bad_output'))] for call in calls))
                    self.assertIn('core_exit=1', result.stdout)
                    self.assertIn('apk_restore_exit=0', result.stdout)

    def test_post_build_full_inventory_substitution_never_relearns_expected_sets(self):
        for phase in ('inventory-ordinary', 'inventory-shizuku'):
            with self.subTest(phase=phase):
                root, result, calls = self.run_phases(post_inventory_substitution=phase)
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(['core'], self.executions(calls))
                reports = root / 'app/build/reports/device-validation/standard'
                self.assertEqual(set(CORE + [ON, OFF]), set((reports / 'ordinary-identities.txt').read_text().splitlines()))
                self.assertEqual([SHIZUKU], (reports / 'shizuku-identities.txt').read_text().splitlines())
                self.assertIn('Test identities differ', result.stderr)
                self.assertIn('apk_restore_exit=1', result.stdout)

    def test_prebuilt_ignores_connected_preparation_and_removal_failures(self):
        _, result, calls = self.run_phases(True, prepare_exit=22, metadata_fail='before',
                                         remove_fail='com.akane.voltwise.debug.test', signer_transition=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(any(call[0] in ('gradle', 'apksigner', 'sha256sum') or call[1:2] == ['uninstall'] for call in calls))

    def test_prebuilt_16k_retains_device_evidence(self):
        root, result, _ = self.run_phases(True, page='16384', expected_page='16384', group='16k')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('page_size=16384', (root / 'app/build/reports/device-validation/16k/device-info.txt').read_text())


if __name__ == '__main__':
    unittest.main()
