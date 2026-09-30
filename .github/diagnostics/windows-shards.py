"""Temporary Windows full-suite diagnostic; candidate inputs and coverage are fixed."""
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

manifest = json.loads(Path('.github/diagnostics/windows-shards.json').read_text())
expected = manifest['expectedClasses']
flattened = [name for shard in manifest['shards'] for name in shard]
assert sorted(flattened) == expected and len(flattened) == len(set(flattened))
for path, tree in manifest['trees'].items():
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD:' + path], text=True).strip()
    assert actual == tree, 'Candidate changed: ' + path

action = sys.argv[1]
if action == 'prepare':
    index = int(sys.argv[2])
    selected = manifest['shards'][index]
    includes = Path(os.environ['RUNNER_TEMP']) / f'loopper-includes-{index}.txt'
    includes.write_text('\n'.join(name.replace('.', '/') + '.java' for name in selected) + '\n')
    helper = Path('scripts/verify-windows-ci.ps1').read_text()
    needle = '"-DreuseForks=false" clean verify'
    assert helper.count(needle) == 1
    helper = helper.replace(needle, '"-DreuseForks=false" "-Dsurefire.includesFile=$env:LOOPPER_CI_INCLUDES" clean verify')
    wrapper = Path(os.environ['RUNNER_TEMP']) / 'loopper-shard-wrapper.ps1'
    wrapper.write_text(helper)
    with open(os.environ['GITHUB_ENV'], 'a') as stream:
        stream.write('LOOPPER_CI_INCLUDES=' + includes.as_posix() + '\n')
    print(f'[DEBUG-windows-shards] shard {index}: {len(selected)} of {len(expected)} fixed classes')
elif action == 'verify':
    index = int(sys.argv[2])
    results = [ET.parse(p).getroot().attrib for p in Path('target/surefire-reports').glob('TEST-*.xml')]
    names = [result['name'] for result in results]
    assert sorted(names) == manifest['shards'][index], 'Missing, duplicate, or unexpected test classes'
    totals = {key: sum(int(result[key]) for result in results) for key in ['tests', 'failures', 'errors', 'skipped']}
    assert totals['tests'] > 0 and totals['failures'] == totals['errors'] == 0, totals
    report = {'index': index, 'classes': sorted(names), 'totals': totals, 'candidate': manifest['candidate']}
    Path('windows-shard-result.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'index': index, 'classCount': len(names), 'totals': totals}))
elif action == 'aggregate':
    reports = [json.loads(p.read_text()) for p in Path('diagnostics-artifacts').rglob('windows-shard-result.json')]
    assert sorted(report['index'] for report in reports) == list(range(len(manifest['shards'])))
    for report in reports:
        assert report['candidate'] == manifest['candidate']
        assert report['classes'] == manifest['shards'][report['index']]
    names = [name for report in reports for name in report['classes']]
    assert sorted(names) == expected and len(names) == len(set(names))
    totals = {key: sum(report['totals'][key] for report in reports) for key in ['tests', 'failures', 'errors', 'skipped']}
    assert totals['failures'] == totals['errors'] == 0
    print(json.dumps({'candidate': manifest['candidate'], 'classes': len(names), 'shards': len(reports), 'totals': totals}))
else:
    raise ValueError(action)
