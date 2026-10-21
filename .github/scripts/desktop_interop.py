"""Required Windows Android-client -> actual Desktop Core HTTPS acceptance.

Raw process logs, encrypted stores and pairing files remain in fresh RUNNER_TEMP.
Only allowlisted receipts and minimized JUnit XML may be uploaded.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile
import time
from urllib.parse import parse_qs, urlparse
import xml.etree.ElementTree as ET


EXPECTED_CASES = {
    ('com.example.DesktopGatewayInteropTest', 'realDesktopRoundTripReplayHistoryEvidenceActionsDraftsAndNotices'),
    ('com.example.BootstrapDesktopInteropTest', 'desktopBootstrapCommitsVerifiedConfigurationAndRejectsRealReplay'),
}


class EvidenceError(Exception):
    """Messages are fixed reason codes, never raw process/XML/fixture errors."""


def redact_diagnostics(text, secrets=(), paths=(), maximum=24000, lines=300):
    """Redact before bounding, so trimming cannot expose a partial known secret."""
    text = str(text)
    for marker in sorted(set(secrets), key=len, reverse=True):
        if marker:
            text = text.replace(marker, '[REDACTED_FIXTURE]')
    # Known roots retain useful relative file/line context in Java/Gradle stacks.
    for marker, label in sorted(paths, key=lambda item:len(str(item[0])), reverse=True):
        for value in {str(marker), str(marker).replace('\\', '/'), str(marker).replace('\\', '\\\\')}:
            if value:
                text = re.sub(re.escape(value), lambda _:label, text, flags=re.IGNORECASE)
    text = re.sub(r'-----BEGIN [A-Z ]+-----.*?-----END [A-Z ]+-----',
                  '[REDACTED_PEM]', text, flags=re.DOTALL)
    text = re.sub(r'''(?i)privateai://[^\s<>"']*''', '[REDACTED_PAIRING_URI]', text)
    text = re.sub(r'(?im)\b(?:x-pair-key|authorization|proxy-authorization|cookie|set-cookie)\b[^\r\n]*',
                  '[REDACTED_AUTH_HEADER]', text)
    text = re.sub(r'''(?i)\b(?:bearer|basic)\s+[A-Za-z0-9_+/=.-]+''', '[REDACTED_AUTH]', text)
    text = re.sub(r'''(?i)([?&](?:key|token|certificate|pairKey|notificationKey)=)[^&\s<>"']*''',
                  r'\1[REDACTED]', text)
    text = re.sub(r'''(?i)(["']?(?:pairUri|bootstrapUri|pairKey|notificationKey|token|key|certificate|privateKey)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;}\]]+)''',
                  r'\1[REDACTED]', text)
    # Wrapped certificate/key bodies and unknown absolute runner paths are never useful evidence.
    text = re.sub(r'(?m)^[A-Za-z0-9+/=_-]{40,}\s*$', '[REDACTED_OPAQUE_LINE]', text)
    text = re.sub(r'''(?i)\b[A-Z]:[\\/][^\s<>"'|\r\n]*''', '[RUNNER_PATH]', text)
    text = re.sub(r'''(?i)/(?:home/runner|Users/runner|runner/_work|tmp)/[^\s<>"'|\r\n]*''', '[RUNNER_PATH]', text)
    text = re.sub(r'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]', '', text)
    if any(marker and marker in text for marker in secrets):
        return '[DIAGNOSTIC_REDACTION_FAILED]'
    return '\n'.join(text.splitlines()[-lines:])[-maximum:]


def read_tail(path, maximum=256 * 1024):
    if not path.is_file():
        return 'No Gradle log was produced before this failure.'
    with path.open('rb') as stream:
        stream.seek(0, 2)
        start = max(0, stream.tell() - maximum)
        stream.seek(start)
        raw = stream.read(maximum)
    if start:
        # Discard a potentially truncated first line (including a partial credential).
        raw = raw.partition(b'\n')[2]
    return raw.decode('utf-8', errors='replace')


def failure_diagnostics(results, scratch, secrets, paths, reason):
    """Preserve bounded technical failures, with no arbitrary properties/test output."""
    reports = []
    expected_files = ['TEST-' + classname + '.xml' for classname, _ in sorted(EXPECTED_CASES)]
    for source in (results / name for name in expected_files if (results / name).is_file()):
        raw = source.read_bytes() if source.stat().st_size <= 1024 * 1024 else b''
        row = {'report':redact_diagnostics(source.name, secrets, paths, 250)}
        if not raw or b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():
            row['parseStatus'] = 'unavailable-or-unsafe'
            reports.append(row)
            continue
        try:
            suite = ET.fromstring(raw)
            row['counters'] = {key:int(suite.attrib[key]) for key in ('tests','failures','errors','skipped')}
            row['cases'] = []
            for case in suite.findall('testcase')[:4]:
                failed = [node for node in case if node.tag in ('failure','error','skipped')]
                if not failed:
                    continue
                identity = (case.attrib.get('classname','unknown'),case.attrib.get('name','unknown'))
                item = {'class':identity[0] if identity in EXPECTED_CASES else redact_diagnostics(identity[0],secrets,paths,250),
                        'method':identity[1] if identity in EXPECTED_CASES else redact_diagnostics(identity[1],secrets,paths,250),
                        'failures':[]}
                for node in failed[:1]:
                    kind = node.attrib.get('type','')
                    item['failures'].append({'kind':node.tag,
                        'exceptionType':redact_diagnostics(kind,secrets,paths,200) if re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_.$]{0,199}',kind) else 'unavailable',
                        'message':redact_diagnostics(node.attrib.get('message',''),secrets,paths,500),
                        'stack':redact_diagnostics(node.text or '',secrets,paths,2500,60)})
                row['cases'].append(item)
            reports.append(row)
        except (ET.ParseError, KeyError, ValueError):
            row['parseStatus'] = 'malformed'
            reports.append(row)
    fixture_code = 'unavailable'
    try:
        value = json.loads((scratch / 'fixture-result.json').read_text(encoding='utf-8')).get('failureCode','')
        if value in {'STARTUP_ENVIRONMENT','STARTUP_CORE_LOAD','STARTUP_STORE_OPEN','STARTUP_CORE_SETTINGS',
                     'STARTUP_HTTPS_START','STARTUP_PAIRING_CREATE','STARTUP_READY_WRITE'}:
            fixture_code = value
    except (OSError, ValueError, AttributeError):
        pass
    summary = {'schemaVersion':1, 'status':'failed', 'reason':reason,
               'fixtureFailureCode':fixture_code, 'junitReports':reports}
    # Only bounded redacted fields and exact allowlisted test IDs enter this JSON.
    safe_json = json.dumps(summary, indent=2)
    if len(safe_json) > 40000 or any(marker and marker in safe_json for marker in secrets):
        safe_json = json.dumps({'schemaVersion':1,'status':'failed','reason':reason,
                                'diagnostics':'DIAGNOSTIC_REDACTION_OR_SIZE_LIMIT'})
    log_tail = redact_diagnostics(read_tail(scratch / 'gradle.log'),secrets,paths)
    return safe_json + '\n', 'Failure: ' + reason + '\nFixture: ' + fixture_code + '\n\nSanitized Gradle tail:\n' + log_tail + '\n'


def compatibility(companion):
    value = json.loads((companion / '.github/desktop-interop.json').read_text(encoding='utf-8'))
    if (set(value) != {'repository', 'commit', 'version'}
            or value['repository'] != 'yosefbf-bot/Private-AI'
            or not re.fullmatch(r'[a-f0-9]{40}', value['commit'])
            or not re.fullmatch(r'\d+\.\d+\.\d+(?:-dev)?', value['version'])):
        raise EvidenceError('INVALID_COMPATIBILITY_PIN')
    return value


def verify_junit(results, started, secrets=()):
    files = sorted(results.glob('TEST-*.xml'))
    expected_files = {'TEST-' + name + '.xml' for name, _ in EXPECTED_CASES}
    if {p.name for p in files} != expected_files:
        raise EvidenceError('MISSING_OR_EXTRA_TEST_REPORT')
    cases, hashes = {}, {}
    for source in files:
        raw = source.read_bytes()
        if len(raw) > 1024 * 1024 or source.stat().st_mtime < started - 2:
            raise EvidenceError('STALE_OR_OVERSIZED_TEST_REPORT')
        if (b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper()
                or any(marker and marker.encode('utf-8') in raw for marker in secrets)
                or b'privateai://pair' in raw.lower() or b'x-pair-key' in raw.lower()):
            raise EvidenceError('UNSAFE_TEST_REPORT')
        try:
            suite = ET.fromstring(raw)
            if (suite.tag != 'testsuite' or int(suite.attrib['tests']) != 1
                    or any(int(suite.attrib[k]) != 0 for k in ('failures', 'errors', 'skipped'))):
                raise EvidenceError('FAILED_OR_SKIPPED_TEST')
            children = suite.findall('testcase')
            if len(children) != 1:
                raise EvidenceError('MISSING_OR_EXTRA_TEST_CASE')
            case = children[0]
            identity = (case.attrib['classname'], case.attrib['name'])
            elapsed = float(case.attrib['time'])
            if identity not in EXPECTED_CASES or identity in cases or not 0 <= elapsed < 900:
                raise EvidenceError('INVALID_TEST_IDENTITY')
            if any(case.find(k) is not None for k in ('failure', 'error', 'skipped')):
                raise EvidenceError('FAILED_OR_SKIPPED_TEST')
            cases[identity] = elapsed
            hashes[source.name] = hashlib.sha256(raw).hexdigest()
        except (ET.ParseError, KeyError, ValueError, OverflowError):
            raise EvidenceError('MALFORMED_TEST_REPORT') from None
    if set(cases) != EXPECTED_CASES:
        raise EvidenceError('MISSING_OR_EXTRA_TEST_CASE')
    # Drop arbitrary properties, stdout/stderr and exception text. Preserve exact
    # case identities/timings and bind each minimized report to its original hash.
    sanitized = ET.Element('testsuite', name='actual-desktop-interop', tests='2',
                           failures='0', errors='0', skipped='0')
    for (classname, name), elapsed in sorted(cases.items()):
        ET.SubElement(sanitized, 'testcase', classname=classname, name=name, time=str(elapsed))
    return ET.tostring(sanitized, encoding='utf-8', xml_declaration=True), hashes


def fixture_secrets(pair_file):
    value = json.loads(pair_file.read_text(encoding='utf-8'))
    if not all(isinstance(value.get(k), str) for k in ('pairUri', 'bootstrapUri')):
        raise EvidenceError('MISSING_FIXTURE_INPUT')
    markers = [str(pair_file)]
    for key in ('pairUri', 'bootstrapUri'):
        markers.append(value[key])
        for values in parse_qs(urlparse(value[key]).query).values():
            markers.extend(v for v in values if len(v) >= 16)
    return markers


def git_value(root, *args):
    result = subprocess.run(['git', '-C', str(root), *args], capture_output=True, timeout=30)
    if result.returncode:
        raise EvidenceError('SOURCE_IDENTITY_FAILED')
    return result.stdout.decode('ascii').strip()


def identity(companion, desktop, pin):
    desktop_commit = git_value(desktop, 'rev-parse', 'HEAD')
    android_commit = git_value(companion, 'rev-parse', 'HEAD')
    if desktop_commit != pin['commit'] or android_commit != os.environ.get('GITHUB_SHA'):
        raise EvidenceError('SOURCE_COMMIT_MISMATCH')
    for root in (companion, desktop):
        git_value(root, 'diff', '--exit-code', 'HEAD', '--')
        if git_value(root, 'ls-files', '--others', '--exclude-standard'):
            raise EvidenceError('UNTRACKED_SOURCE')
    if json.loads((desktop / 'package.json').read_text(encoding='utf-8'))['version'] != pin['version']:
        raise EvidenceError('DESKTOP_VERSION_MISMATCH')
    gradle = (companion / 'app/build.gradle.kts').read_text(encoding='utf-8')
    version = re.search(r'versionName\s*=\s*"([\w.\-]+)"', gradle)
    code = re.search(r'versionCode\s*=\s*(\d+)', gradle)
    if not version or not code:
        raise EvidenceError('ANDROID_VERSION_MISSING')
    return {'desktopRepository':pin['repository'], 'desktopCommit':desktop_commit,
            'desktopTree':git_value(desktop, 'rev-parse', 'HEAD^{tree}'),
            'desktopVersion':pin['version'], 'androidCheckoutCommit':android_commit,
            'androidTree':git_value(companion, 'rev-parse', 'HEAD^{tree}'),
            'androidVersion':version[1], 'androidVersionCode':int(code[1])}


def stop_owned(process):
    if process.poll() is not None:
        return
    try:
        process.stdin.write('stop\n')
        process.stdin.flush()
        process.wait(timeout=15)
    except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
        process.kill()  # Only the Node child created by this invocation.
        process.wait(timeout=15)


def run(args):
    companion, desktop = args.companion_root.resolve(), args.desktop_root.resolve()
    temp = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
    evidence = args.evidence_root.resolve()
    if (platform.system() != 'Windows' or os.environ.get('RUNNER_OS') != 'Windows'
            or os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted'
            or evidence.parent != temp or companion == desktop or companion in desktop.parents
            or desktop in companion.parents or companion in temp.parents or desktop in temp.parents):
        raise EvidenceError('HOSTED_WINDOWS_ISOLATION_REQUIRED')
    evidence.mkdir(exist_ok=False)
    pin = compatibility(companion)
    source = identity(companion, desktop, pin)
    results = companion / 'app/build/test-results/testDebugUnitTest'
    if list(results.glob('TEST-*.xml')):
        raise EvidenceError('PREEXISTING_TEST_RESULTS')
    scratch = Path(tempfile.mkdtemp(prefix='privateai-interop-', dir=temp))
    fixture, markers = None, []
    try:
        fixture = subprocess.Popen(['node', str(companion / '.github/scripts/desktop_interop_fixture.cjs'),
                                    str(desktop), str(scratch)], stdin=subprocess.PIPE,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, text=True)
        deadline = time.monotonic() + 60
        while not (scratch / 'ready.json').is_file():
            if fixture.poll() is not None or time.monotonic() >= deadline:
                raise EvidenceError('DESKTOP_FIXTURE_NOT_READY')
            time.sleep(0.1)
        ready = json.loads((scratch / 'ready.json').read_text(encoding='utf-8'))
        if (ready.get('pid') != fixture.pid or ready.get('platform') != 'win32'
                or not re.fullmatch(r'24\.\d+\.\d+', ready.get('nodeVersion', ''))
                or ready.get('actualDesktopCore') is not True or ready.get('loopbackOnly') is not True):
            raise EvidenceError('INVALID_FIXTURE_IDENTITY')
        pair_file = scratch / 'pair.json'
        markers = fixture_secrets(pair_file)
        env = dict(os.environ, PRIVATE_AI_GATEWAY_TEST_PAIR_FILE=str(pair_file))
        command = ['cmd.exe', '/d', '/c', 'gradlew.bat', '--no-daemon', '--no-build-cache',
                   '--no-configuration-cache', 'testDebugUnitTest']
        for name, _ in sorted(EXPECTED_CASES):
            command.extend(['--tests', name])
        started = time.time()
        with (scratch / 'gradle.log').open('wb') as log:
            test = subprocess.Popen(command, cwd=companion, env=env, stdout=log, stderr=subprocess.STDOUT)
            try:
                code = test.wait(timeout=12 * 60)
            except subprocess.TimeoutExpired:
                # PID-scoped owned process tree only; never process-name/global cleanup.
                subprocess.run(['taskkill.exe', '/PID', str(test.pid), '/T', '/F'],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
                test.wait(timeout=30)
                raise EvidenceError('ANDROID_INTEROP_TIMEOUT') from None
        if code:
            raise EvidenceError('ANDROID_INTEROP_EXECUTION_FAILED')
        xml, hashes = verify_junit(results, started, markers)
        stop_owned(fixture)
        if fixture.returncode != 0:
            raise EvidenceError('DESKTOP_FIXTURE_EXIT_FAILED')
        result = json.loads((scratch / 'fixture-result.json').read_text(encoding='utf-8'))
        expected = {'schemaVersion':1, 'actualDesktopCore':True, 'loopbackOnly':True,
                    'providerCalls':0, 'calculatorRoundTrip':True, 'idle':True,
                    'localModelInferenceExecuted':False, 'meetingTranscriptionExecuted':False}
        if result != expected:
            raise EvidenceError('DESKTOP_FIXTURE_ASSERTION_FAILED')
        if identity(companion, desktop, pin) != source:
            raise EvidenceError('SOURCE_CHANGED_DURING_TEST')
        receipt = {'schemaVersion':1, 'status':'passed',
                   'verifiedUTC':datetime.now(timezone.utc).isoformat(), **source,
                   'runnerOS':os.environ['RUNNER_OS'], 'runnerImage':os.environ.get('ImageOS'),
                   'runnerImageVersion':os.environ.get('ImageVersion'),
                   'windowsVersion':platform.version(), 'nodeVersion':ready['nodeVersion'],
                   'githubRunId':os.environ.get('GITHUB_RUN_ID'),
                   'githubRunAttempt':os.environ.get('GITHUB_RUN_ATTEMPT'),
                   'testCases':[{'class':c, 'method':m} for c, m in sorted(EXPECTED_CASES)],
                   'passed':2, 'failed':0, 'skipped':0, 'originalJUnitSHA256':hashes,
                   'junitEvidence':'Minimized from actual JUnit XML; arbitrary outputs removed',
                   'fixture':expected,
                   'scope':'Actual Kotlin client and desktop Core/SQLite/pinned HTTPS; synthetic data; calculator, not AI inference. No physical phone, OS authentication, camera, LAN or live account claim.'}
        (evidence / 'junit.xml').write_bytes(xml)
        (evidence / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
        print('Actual desktop interoperability passed: 2 cases, 0 failures, 0 skips. Sanitized evidence saved.')
    except Exception as error:
        reason = str(error) if isinstance(error, EvidenceError) else 'INTEROP_HARNESS_FAILED'
        pair_file = scratch / 'pair.json'
        if pair_file.is_file():
            try:
                markers.extend(fixture_secrets(pair_file))
            except (EvidenceError, OSError, ValueError):
                pass
        if fixture is not None:
            stop_owned(fixture)
        paths = [(scratch,'[FIXTURE_TEMP]'),(temp,'[RUNNER_TEMP]'),
                 (companion,'[COMPANION]'),(desktop,'[DESKTOP]')]
        workspace = os.environ.get('GITHUB_WORKSPACE')
        if workspace:
            paths.append((workspace,'[WORKSPACE]'))
        try:
            diagnostic_json, diagnostic_text = failure_diagnostics(results,scratch,markers,paths,reason)
            (evidence / 'failure.json').write_text(diagnostic_json,encoding='utf-8')
            (evidence / 'failure.txt').write_text(diagnostic_text,encoding='utf-8')
        except Exception:
            (evidence / 'failure.txt').write_text('Sanitized diagnostic extraction failed; no raw output published.\n',encoding='utf-8')
        (evidence / 'receipt.json').write_text(json.dumps({'schemaVersion':1, 'status':'failed',
                                                        'reason':reason,'diagnostics':'failure.txt/failure.json'}) + '\n', encoding='utf-8')
        raise
    finally:
        if fixture is not None:
            stop_owned(fixture)
        # This invocation created scratch directly under verified RUNNER_TEMP.
        resolved_scratch = scratch.resolve(strict=True)
        if resolved_scratch.parent != temp or not resolved_scratch.name.startswith('privateai-interop-'):
            raise EvidenceError('SCRATCH_CLEANUP_BOUNDARY')
        shutil.rmtree(scratch)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--companion-root', type=Path, required=True)
    parser.add_argument('--desktop-root', type=Path, required=True)
    parser.add_argument('--evidence-root', type=Path, required=True)
    args = parser.parse_args()
    try:
        run(args)
    except Exception as error:
        reason = str(error) if isinstance(error, EvidenceError) else 'INTEROP_HARNESS_FAILED'
        # Fixed reason codes only. Never print raw Gradle/Node/XML exceptions.
        print('Desktop interoperability gate failed: ' + reason)
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
