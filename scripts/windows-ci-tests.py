"""Partition the default Surefire test classes and require complete Windows coverage."""
import argparse
import fnmatch
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

SHARDS = 8
PATTERNS = ('Test*.java', '*Test.java', '*Tests.java', '*TestCase.java')


def discover(root):
    classes = []
    for path in sorted((root / 'src/test/java').rglob('*.java')):
        if not any(fnmatch.fnmatchcase(path.name, pattern) for pattern in PATTERNS):
            continue
        name = '.'.join(path.relative_to(root / 'src/test/java').with_suffix('').parts)
        if name == 'io.opencode.loopper.TestJvm':
            # This existing child-JVM utility matches Test*.java but has no JUnit tests.
            if re.search(r'@(?:[\w.]*\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b', path.read_text()):
                raise ValueError('TestJvm now contains tests; update the test inventory')
            continue
        classes.append(name)
    if not classes or len(classes) != len(set(classes)):
        raise ValueError('Test inventory must be nonempty and unique')
    return sorted(classes)


def partition(classes, index):
    if not 0 <= index < SHARDS:
        raise ValueError('Invalid shard index')
    return classes[index::SHARDS]


def inventory_hash(classes):
    return hashlib.sha256('\n'.join(classes).encode()).hexdigest()


def verify_reports(directory, expected):
    reports = [ET.parse(path).getroot().attrib for path in directory.glob('TEST-*.xml')]
    names = [report['name'] for report in reports]
    if sorted(names) != expected:
        raise ValueError(f'Test coverage mismatch: missing={sorted(set(expected)-set(names))}, '
                         f'unexpected={sorted(set(names)-set(expected))}, duplicate={len(names)!=len(set(names))}')
    totals = {key: sum(int(report[key]) for report in reports) for key in ('tests', 'failures', 'errors', 'skipped')}
    if totals['tests'] <= 0 or totals['failures'] or totals['errors']:
        raise ValueError(f'Test execution did not pass: {totals}')
    return totals


def aggregate(reports, expected, commit):
    if sorted(report['index'] for report in reports) != list(range(SHARDS)):
        raise ValueError('Every Windows shard must finish exactly once')
    for report in reports:
        if report['commit'] != commit or report['inventory'] != inventory_hash(expected):
            raise ValueError('Mixed candidate commits or test inventories')
        if report['classes'] != partition(expected, report['index']):
            raise ValueError('Shard class coverage changed')
        if report['totals']['tests'] <= 0 or report['totals']['failures'] or report['totals']['errors']:
            raise ValueError('A Windows shard did not pass')
    names = [name for report in reports for name in report['classes']]
    if sorted(names) != expected or len(names) != len(set(names)):
        raise ValueError('Incomplete or overlapping Windows coverage')
    return {key: sum(report['totals'][key] for report in reports) for key in ('tests', 'failures', 'errors', 'skipped')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'verify', 'aggregate'))
    parser.add_argument('index', nargs='?', type=int)
    args = parser.parse_args()
    root = Path.cwd()
    classes = discover(root)
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    if args.action == 'aggregate':
        reports = [json.loads(path.read_text()) for path in Path('windows-results').rglob('windows-test-result.json')]
        totals = aggregate(reports, classes, commit)
        print(json.dumps({'commit': commit, 'classes': len(classes), 'shards': SHARDS, 'totals': totals}))
        return
    selected = partition(classes, args.index)
    if not selected:
        raise ValueError('Empty Windows shard')
    if args.action == 'prepare':
        includes = Path(os.environ['RUNNER_TEMP']) / f'loopper-test-includes-{args.index}.txt'
        includes.write_text('\n'.join(name.replace('.', '/') + '.java' for name in selected) + '\n', encoding='utf-8')
        with open(os.environ['GITHUB_ENV'], 'a', encoding='utf-8') as stream:
            stream.write('LOOPPER_CI_TEST_INCLUDES=' + includes.as_posix() + '\n')
        print(f'Windows shard {args.index}: {len(selected)} of {len(classes)} discovered classes')
    else:
        totals = verify_reports(root / 'target/surefire-reports', selected)
        report = {'index': args.index, 'commit': commit, 'inventory': inventory_hash(classes),
                  'classes': selected, 'totals': totals}
        Path('windows-test-result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'index': args.index, 'classes': len(selected), 'totals': totals}))


if __name__ == '__main__':
    main()
