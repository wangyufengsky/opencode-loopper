#!/usr/bin/env python3
"""Isolated JAR/HTTP/Git qualification; uses only fake OpenCode and temporary projects."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('jar', type=Path)
parser.add_argument('--expect-blocked', action='store_true', help='Reproduce the pre-fix rejection')
args = parser.parse_args()
directory = Path(tempfile.mkdtemp(prefix='loopper-branch-release-')).resolve()
with socket.socket() as port_socket:
    port_socket.bind(('127.0.0.1', 0))
    port = port_socket.getsockname()[1]
endpoint = f'http://127.0.0.1:{port}'
java = os.environ.get('JAVA_EXECUTABLE', 'java')
environment = dict(os.environ, LOOPPER_DATA_DIR=str(directory / 'data'),
                   LOOPPER_ALLOWED_ROOT=str(directory), LOOPPER_OPENCODE_MODE='fake', LOOPPER_OPEN_BROWSER='false')
log = (directory / 'loopper.log').open('w')
process = subprocess.Popen([java, '-jar', str(args.jar.resolve()), f'--server.port={port}',
                            '--loopper.monitor-delay=1h', '--loopper.designer-monitor-delay=1h'],
                           env=environment, stdout=log, stderr=subprocess.STDOUT)
results = {'jar': str(args.jar.resolve()), 'directory': str(directory), 'pid': process.pid,
           'endpoint': endpoint, 'expectBlocked': args.expect_blocked, 'scenarios': []}
print(json.dumps(results), flush=True)


def call(path, body=None, expected=200):
    request = urllib.request.Request(endpoint + path, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', 'X-Loopper-Local-UI': '1', 'Origin': endpoint})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            status, data = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, data = error.code, error.read()
    value = json.loads(data) if data else None
    assert status == expected, (path, status, value)
    return value


def git(root, *arguments):
    return subprocess.check_output(['git', *arguments], cwd=root, stderr=subprocess.STDOUT, text=True).strip()


def pending(project_id, title):
    draft = call('/api/loop-drafts', {'spec': {'schemaVersion': 'v2', 'projectId': project_id,
        'goal': 'Keep README content intact', 'stages': [{'objective': 'Verify README',
        'allowedPaths': ['README.md'], 'deliverables': ['README.md'], 'implementationKind': 'NON_JAVA',
        'acceptanceCriteria': [{'id': 'AC-1', 'description': 'README retains fixture content', 'verificationMode': 'MACHINE'}],
        'verifiers': [{'type': 'FILE_CONTENT', 'path': 'README.md', 'matchMode': 'EXACT',
                       'expectedContent': 'fixture\n', 'criterionIds': ['AC-1']}]}]}}, expected=201)
    return call(f"/api/loop-drafts/{draft['id']}/confirm", {'title': title})['taskId']


try:
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            call('/actuator/health')
            break
        except (OSError, AssertionError):
            if process.poll() is not None:
                raise RuntimeError('Isolated JVM exited; inspect loopper.log')
            time.sleep(0.3)
    else:
        raise TimeoutError('Isolated JVM startup timed out')
    runtime = call('/api/runtime/opencode')
    results['loopperVersion'] = runtime['loopperVersion']
    assert runtime['version'] == 'fake' and runtime['pid'] is None, runtime
    for rework in [False, True]:
        root = directory / ('rework' if rework else 'normal')
        root.mkdir()
        (root / 'README.md').write_text('fixture\n')
        git(root, 'init', '--initial-branch=main')
        git(root, 'config', 'user.name', 'Loopper Test')
        git(root, 'config', 'user.email', 'test@example.invalid')
        git(root, 'add', '.')
        git(root, 'commit', '-m', 'fixture')
        git(root, 'switch', '-c', 'develop')
        project = call('/api/projects', {'name': root.name, 'rootPath': str(root)}, expected=201)
        holder = pending(project['id'], 'old holder')
        assert call(f'/api/tasks/{holder}/start', {})['status'] == 'RUNNING'
        if rework:
            assert call(f'/api/tasks/{holder}/cancel', {})['status'] == 'CANCELLED'
            git(root, 'switch', 'develop')
            holder = call(f'/api/tasks/{holder}/recoveries', {'mode': 'REWORK_ALL_STAGES'})['taskId']
            assert call(f'/api/tasks/{holder}/start', {})['status'] == 'RUNNING'
        waiter = pending(project['id'], 'waiting task')
        assert call(f'/api/tasks/{waiter}/start', {})['status'] == 'QUEUED'
        git(root, 'switch', '-c', 'external-branch')
        assert call(f'/api/tasks/{holder}/cancel', {})['status'] == 'CANCELLED'
        blocked = call(f'/api/tasks/{waiter}/queue/reconcile', {}, expected=409)
        assert blocked['errorCode'] == 'TASK_SOURCE_BRANCH_RESTORE_MISMATCH'
        assert git(root, 'branch', '--show-current') == 'external-branch'
        git(root, 'switch', 'develop')
        (root / 'user-work.txt').write_text('preserve source work\n')
        dirty = call(f'/api/tasks/{waiter}/queue/reconcile', {}, expected=409)
        assert (root / 'user-work.txt').read_text() == 'preserve source work\n'
        assert git(root, 'branch', '--show-current') == 'develop'
        git(root, 'add', 'user-work.txt')
        git(root, 'commit', '-m', 'preserve fixture source work')
        source_head = git(root, 'rev-parse', 'develop')
        released = call(f'/api/tasks/{waiter}/queue/reconcile', {}, expected=409 if args.expect_blocked else 200)
        if args.expect_blocked:
            assert released['errorCode'] == 'TASK_SOURCE_BRANCH_RESTORE_MISMATCH'
            assert call(f'/api/tasks/{waiter}')['status'] == 'QUEUED'
        else:
            assert released['state'] == 'ADMITTED', released
            assert call(f'/api/tasks/{waiter}')['status'] == 'RUNNING'
            assert call(f'/api/tasks/{waiter}/queue/reconcile', {})['state'] == 'ADMITTED'
            assert call(f'/api/tasks/{waiter}/cancel', {})['status'] == 'CANCELLED'
            assert git(root, 'branch', '--show-current') == 'main'
        assert git(root, 'rev-parse', 'develop') == source_head
        assert git(root, 'show', 'develop:user-work.txt') == 'preserve source work'
        results['scenarios'].append({'rework': rework, 'holder': holder, 'waiter': waiter,
            'foreignBranchBlocker': blocked, 'dirtySourceBlocker': dirty, 'release': released,
            'sourceHeadPreserved': source_head, 'result': 'EXPECTED_BLOCK' if args.expect_blocked else 'PASS'})
        print(json.dumps(results['scenarios'][-1]), flush=True)
    results['result'] = 'PASS'
except BaseException as failure:
    results['result'] = 'FAIL'
    results['error'] = str(failure)
    raise
finally:
    process.terminate()
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
    results['processStopped'] = process.poll() is not None
    (directory / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n')
    log.close()
    print('Evidence: ' + str(directory / 'results.json'), flush=True)
