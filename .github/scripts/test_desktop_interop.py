"""Small, offline evidence-parser regressions; no JVM, gateway or build runs."""
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET

from desktop_interop import (compatibility, EvidenceError, EXPECTED_CASES, failure_diagnostics,
                             fixture_secrets, read_tail, redact_diagnostics, verify_junit)


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.started = time.time() - 1
        self.files = []
        for classname, name in sorted(EXPECTED_CASES):
            path = self.root / ('TEST-' + classname + '.xml')
            suite = ET.Element('testsuite', tests='1', skipped='0', failures='0', errors='0')
            ET.SubElement(suite, 'testcase', classname=classname, name=name, time='1.25')
            ET.SubElement(suite, 'system-out').text = 'Synthetic harmless runner output'
            path.write_bytes(ET.tostring(suite))
            self.files.append(path)

    def mutate(self, change):
        suite = ET.parse(self.files[0]).getroot()
        change(suite)
        self.files[0].write_bytes(ET.tostring(suite))

    def reject(self, reason=None, secrets=()):
        with self.assertRaises(EvidenceError) as caught:
            verify_junit(self.root, self.started, secrets)
        if reason:
            self.assertEqual(str(caught.exception), reason)

    def test_two_exact_actual_cases_produce_minimized_evidence_and_original_hashes(self):
        xml, hashes = verify_junit(self.root, self.started)
        suite = ET.fromstring(xml)
        self.assertEqual(suite.attrib, {'name':'actual-desktop-interop', 'tests':'2',
                                       'failures':'0', 'errors':'0', 'skipped':'0'})
        self.assertEqual({(c.attrib['classname'], c.attrib['name']) for c in suite}, EXPECTED_CASES)
        self.assertEqual(len(hashes), 2)
        self.assertTrue(all(len(h) == 64 for h in hashes.values()))
        self.assertNotIn(b'runner output', xml)
        self.assertNotIn(b'system-out', xml)

    def test_missing_report_fails(self):
        self.files[0].unlink()
        self.reject('MISSING_OR_EXTRA_TEST_REPORT')

    def test_extra_report_fails(self):
        (self.root / 'TEST-extra.xml').write_text('<testsuite/>')
        self.reject('MISSING_OR_EXTRA_TEST_REPORT')

    def test_each_failure_error_or_skip_counter_fails(self):
        original = self.files[0].read_bytes()
        for key in ('failures', 'errors', 'skipped'):
            with self.subTest(key=key):
                self.files[0].write_bytes(original)
                self.mutate(lambda suite: suite.set(key, '1'))
                self.reject('FAILED_OR_SKIPPED_TEST')

    def test_each_failure_error_or_skip_child_fails_even_with_zero_counters(self):
        original = self.files[0].read_bytes()
        for key in ('failure', 'error', 'skipped'):
            with self.subTest(key=key):
                self.files[0].write_bytes(original)
                self.mutate(lambda suite: ET.SubElement(suite.find('testcase'), key))
                self.reject('FAILED_OR_SKIPPED_TEST')

    def test_mislabeled_case_fails(self):
        self.mutate(lambda suite: suite.find('testcase').set('name', 'unrelatedCase'))
        self.reject('INVALID_TEST_IDENTITY')

    def test_duplicate_identity_fails(self):
        other = ET.parse(self.files[1]).getroot().find('testcase')
        self.mutate(lambda suite: suite.find('testcase').attrib.update(other.attrib))
        self.reject('INVALID_TEST_IDENTITY')

    def test_extra_case_fails(self):
        self.mutate(lambda suite: ET.SubElement(suite, 'testcase'))
        self.reject('MISSING_OR_EXTRA_TEST_CASE')

    def test_stale_xml_fails(self):
        os.utime(self.files[0], (self.started - 30, self.started - 30))
        self.reject('STALE_OR_OVERSIZED_TEST_REPORT')

    def test_oversized_xml_fails(self):
        self.files[0].write_bytes(b' ' * (1024 * 1024 + 1))
        self.reject('STALE_OR_OVERSIZED_TEST_REPORT')

    def test_missing_counters_and_malformed_xml_fail(self):
        self.mutate(lambda suite: suite.attrib.pop('skipped'))
        self.reject('MALFORMED_TEST_REPORT')
        self.files[0].write_bytes(b'<testsuite')
        self.reject('MALFORMED_TEST_REPORT')

    def test_nonfinite_or_negative_time_fails(self):
        for value in ('NaN', 'Infinity', '-1', '900'):
            with self.subTest(value=value):
                self.mutate(lambda suite: suite.find('testcase').set('time', value))
                self.reject('INVALID_TEST_IDENTITY')

    def test_raw_xml_cannot_publish_pairing_uri_header_or_known_secret(self):
        for marker in ('privateai://pair/v2?token=synthetic', 'X-Pair-Key', 'SYNTHETIC-sensitive-fixture'):
            with self.subTest(marker=marker):
                self.mutate(lambda suite: setattr(suite.find('system-out'), 'text', marker))
                self.reject('UNSAFE_TEST_REPORT', ['SYNTHETIC-sensitive-fixture'])

    def test_dtd_entity_is_refused(self):
        original = self.files[0].read_bytes()
        self.files[0].write_bytes(b'<!DOCTYPE testsuite [<!ENTITY injected "synthetic">]>' + original)
        self.reject('UNSAFE_TEST_REPORT')

    def test_fixture_markers_include_uris_path_and_embedded_token(self):
        pair = self.root / 'pair.json'
        pair.write_text(json.dumps({'pairUri':'privateai://pair?key=SYNTHETIC-LONG-PAIR-KEY',
                                   'bootstrapUri':'privateai://pair/v2?token=SYNTHETIC-LONG-TOKEN'}))
        markers = fixture_secrets(pair)
        self.assertIn(str(pair), markers)
        self.assertIn('SYNTHETIC-LONG-PAIR-KEY', markers)
        self.assertIn('SYNTHETIC-LONG-TOKEN', markers)

    def test_missing_bootstrap_fixture_is_refused(self):
        pair = self.root / 'pair.json'
        pair.write_text(json.dumps({'pairUri':'synthetic'}))
        with self.assertRaises(EvidenceError):
            fixture_secrets(pair)

    def test_compatibility_pin_requires_canonical_repo_and_immutable_sha(self):
        (self.root / '.github').mkdir()
        path = self.root / '.github/desktop-interop.json'
        valid = {'repository':'yosefbf-bot/Private-AI', 'commit':'a' * 40, 'version':'3.0.7-dev'}
        path.write_text(json.dumps(valid))
        self.assertEqual(compatibility(self.root), valid)
        for change in ({'repository':'unrelated/repository'}, {'commit':'main'}, {'version':'invalid'}, {'extra':True}):
            with self.subTest(change=change):
                path.write_text(json.dumps({**valid, **change}))
                with self.assertRaises(EvidenceError):
                    compatibility(self.root)

    def test_diagnostic_redactor_removes_all_known_fixture_values(self):
        markers = ['SYNTHETIC-PAIR-KEY-0123456789', 'SYNTHETIC-BOOTSTRAP-TOKEN-0123456789',
                   'SYNTHETIC-CERTIFICATE-0123456789']
        raw = 'Useful failure: expected 4 but got 5\n' + '\n'.join(markers)
        clean = redact_diagnostics(raw, markers)
        self.assertIn('expected 4 but got 5', clean)
        for marker in markers:
            self.assertNotIn(marker, clean)

    def test_diagnostic_redactor_covers_generic_uri_headers_auth_and_json(self):
        raw = ('Actual assertion failed\nprivateai://pair/v2?token=URL-CREDENTIAL\n'
               'X-Pair-Key: HEADER-CREDENTIAL\nAuthorization: Bearer AUTH-CREDENTIAL\n'
               'other Basic BASIC-CREDENTIAL\n{"token":"JSON-CREDENTIAL"}\n'
               'https://127.0.0.1/?key=QUERY-CREDENTIAL&certificate=CERT-CREDENTIAL\n'
               '-----BEGIN PRIVATE KEY-----\nPEM-CREDENTIAL\n-----END PRIVATE KEY-----\n'
               'java.lang.AssertionError: expected 4 but was 5')
        clean = redact_diagnostics(raw)
        self.assertIn('java.lang.AssertionError', clean)
        self.assertIn('expected 4 but was 5', clean)
        self.assertNotIn('privateai://', clean)
        for marker in ('URL-CREDENTIAL','HEADER-CREDENTIAL','AUTH-CREDENTIAL','BASIC-CREDENTIAL',
                       'JSON-CREDENTIAL','QUERY-CREDENTIAL','CERT-CREDENTIAL','PEM-CREDENTIAL'):
            self.assertNotIn(marker, clean)

    def test_diagnostic_paths_are_hidden_but_relative_stack_location_survives(self):
        root = r'C:\hosted\work\companion'
        raw = (root + r'\app\src\test\Example.kt:42' + '\n' + root.replace('\\','/') +
               '/app/src/test/Example.kt:43\n' + r'D:\unknown\runner\temporary.log')
        clean = redact_diagnostics(raw, paths=[(root,'[COMPANION]')])
        self.assertNotIn('C:\\hosted', clean)
        self.assertNotIn('D:\\unknown', clean)
        self.assertIn('Example.kt:42', clean)
        self.assertIn('Example.kt:43', clean)

    def test_failure_artifact_retains_exact_case_counters_type_and_redacted_details(self):
        secret = 'SYNTHETIC-SECRET-0123456789012345'
        def failing(suite):
            suite.set('failures','1')
            node = ET.SubElement(suite.find('testcase'),'failure',type='java.lang.AssertionError',
                                 message='expected 4 but was 5; token=' + secret)
            node.text = 'java.lang.AssertionError\n at com.example.Client.test(Client.kt:42)\n' + secret
        self.mutate(failing)
        (self.root / 'gradle.log').write_text('Actionable Gradle failure\nX-Pair-Key: ' + secret)
        (self.root / 'fixture-result.json').write_text(json.dumps({'failureCode':'STARTUP_HTTPS_START'}))
        details, tail = failure_diagnostics(self.root,self.root,[secret],[],'ANDROID_INTEROP_EXECUTION_FAILED')
        parsed = json.loads(details)
        self.assertEqual(parsed['fixtureFailureCode'],'STARTUP_HTTPS_START')
        report = next(row for row in parsed['junitReports'] if row.get('cases'))
        self.assertEqual(report['counters']['failures'],1)
        case = report['cases'][0]
        self.assertIn((case['class'],case['method']),EXPECTED_CASES)
        self.assertEqual(case['failures'][0]['exceptionType'],'java.lang.AssertionError')
        self.assertIn('expected 4 but was 5',case['failures'][0]['message'])
        self.assertIn('Client.kt:42',case['failures'][0]['stack'])
        self.assertIn('Actionable Gradle failure',tail)
        self.assertNotIn(secret,details + tail)

    def test_diagnostic_tail_is_bounded_after_secret_redaction(self):
        raw = '\n'.join('line ' + str(i) + ': ' + 'detail ' * 20 for i in range(1200))
        clean = redact_diagnostics(raw,maximum=2000,lines=12)
        self.assertLessEqual(len(clean),2000)
        self.assertLessEqual(len(clean.splitlines()),12)
        self.assertIn('line 1199:',clean)
        self.assertNotIn('line 0:',clean)

    def test_tail_reader_drops_a_truncated_first_line(self):
        path = self.root / 'large.log'
        path.write_bytes(b'partial-sensitive-line-' + b'x' * 4000 + b'\nUseful final failure\n')
        result = read_tail(path,maximum=64)
        self.assertEqual(result,'Useful final failure\n')

    def test_failure_diagnostics_refuse_dtd_and_unknown_fixture_error_text(self):
        self.files[0].write_bytes(b'<!DOCTYPE test [<!ENTITY value "SYNTHETIC_SECRET">]><testsuite/>')
        (self.root / 'fixture-result.json').write_text(json.dumps({'failureCode':'RAW-UNSAFE-SYNTHETIC-SECRET'}))
        details, _ = failure_diagnostics(self.root,self.root,[],[],'DESKTOP_FIXTURE_NOT_READY')
        parsed = json.loads(details)
        self.assertEqual(parsed['fixtureFailureCode'],'unavailable')
        self.assertTrue(any(row.get('parseStatus')=='unavailable-or-unsafe' for row in parsed['junitReports']))
        self.assertNotIn('SYNTHETIC_SECRET',details)
        self.assertNotIn('RAW-UNSAFE',details)

    def test_skipped_failure_diagnostic_keeps_identity_and_skip_count(self):
        self.mutate(lambda suite:(suite.set('skipped','1'),ET.SubElement(suite.find('testcase'),'skipped')))
        details, _ = failure_diagnostics(self.root,self.root,[],[],'FAILED_OR_SKIPPED_TEST')
        parsed = json.loads(details)
        report = next(row for row in parsed['junitReports'] if row.get('cases'))
        self.assertEqual(report['counters']['skipped'],1)
        self.assertEqual(report['cases'][0]['failures'][0]['kind'],'skipped')


if __name__ == '__main__':
    unittest.main()
