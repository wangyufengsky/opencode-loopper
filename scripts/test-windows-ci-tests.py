import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('windows_ci_tests', Path(__file__).with_name('windows-ci-tests.py'))
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


class WindowsCoverageTest(unittest.TestCase):
    def test_new_source_classes_automatically_join_exactly_one_shard(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            sources = root / 'src/test/java/example'
            sources.mkdir(parents=True)
            for name in ['FirstTest.java', 'TestSecond.java', 'ThirdTests.java', 'FourthTestCase.java', 'Helper.java']:
                (sources / name).write_text('package example; class Fixture {}')
            before = ci.discover(root)
            (sources / 'NewFeatureTest.java').write_text('package example; class NewFeatureTest {}')
            after = ci.discover(root)
            self.assertEqual(set(after) - set(before), {'example.NewFeatureTest'})
            self.assertNotIn('example.Helper', after)
            selected = [name for index in range(ci.SHARDS) for name in ci.partition(after, index)]
            self.assertEqual(sorted(selected), after)
            self.assertEqual(len(selected), len(set(selected)))

    def test_helper_cannot_silently_gain_skipped_tests(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            helper = root / 'src/test/java/io/opencode/loopper/TestJvm.java'
            helper.parent.mkdir(parents=True)
            helper.write_text('class TestJvm { @org.junit.jupiter.api.Test void added() {} }')
            with self.assertRaisesRegex(ValueError, 'now contains tests'):
                ci.discover(root)

    def test_xml_reports_require_complete_successful_execution(self):
        with tempfile.TemporaryDirectory() as temp:
            reports = Path(temp)
            report = reports / 'TEST-example.FirstTest.xml'
            report.write_text('<testsuite name="example.FirstTest" tests="2" failures="0" errors="0" skipped="1"/>')
            self.assertEqual(ci.verify_reports(reports, ['example.FirstTest'])['tests'], 2)
            with self.assertRaisesRegex(ValueError, 'coverage mismatch'):
                ci.verify_reports(reports, ['example.FirstTest', 'example.MissingTest'])
            report.write_text('<testsuite name="example.FirstTest" tests="2" failures="1" errors="0" skipped="0"/>')
            with self.assertRaisesRegex(ValueError, 'did not pass'):
                ci.verify_reports(reports, ['example.FirstTest'])

    def test_aggregate_rejects_missing_duplicate_mixed_and_failed_shards(self):
        expected = [f'example.T{i:02}Test' for i in range(16)]
        reports = [{'index': i, 'commit': 'candidate', 'inventory': ci.inventory_hash(expected),
                    'classes': ci.partition(expected, i), 'totals': {'tests': 2, 'failures': 0, 'errors': 0, 'skipped': 0}}
                   for i in range(ci.SHARDS)]
        self.assertEqual(ci.aggregate(reports, expected, 'candidate')['tests'], 16)
        for altered in [reports[:-1], reports + [reports[0]]]:
            with self.assertRaises(ValueError):
                ci.aggregate(altered, expected, 'candidate')
        for key, value in [('commit', 'other'), ('classes', ['example.OtherTest']),
                           ('totals', {'tests': 2, 'failures': 0, 'errors': 1, 'skipped': 0})]:
            altered = json.loads(json.dumps(reports))
            altered[0][key] = value
            with self.assertRaises(ValueError):
                ci.aggregate(altered, expected, 'candidate')


if __name__ == '__main__':
    unittest.main()
