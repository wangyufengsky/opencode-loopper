import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, symlink } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {
  CASES, EXPECTED_RUNTIME_REVISION, EnvironmentBlocked, createRestClient, createSseParser, parseArguments, prepareRequest,
  readTaskEvents, runRestContracts, sha256, validateBaseUrl, validateDetail, validateIsolation, validateReceipt,
} from './rest-contracts.mjs';

const baseUrl = 'http://127.0.0.1:41893';
const response = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const receipt = { id: 'owned', revision: 1, version: 0, layoutVersion: 0, state: 'ACTIVE' };
const runtime = { version: 'fake', status: 'AVAILABLE', managed: false, pid: null, model: 'fake/model' };
async function fixture(t) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'loopper-rest-unit-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const projectRoot = path.join(root, 'projects'), dataDir = path.join(root, 'data');
  const projectParent = path.join(projectRoot, 'A');
  await Promise.all([mkdir(projectParent, { recursive: true }), mkdir(dataDir)]);
  return { baseUrl, projectParent, isolation: { baseUrl, dataDir, projectRoot, runRoot: root, ready: true, revision: EXPECTED_RUNTIME_REVISION,
    opencodeMode: 'fake', schedulingEnabled: false, startupRecoveryEnabled: false, model: 'fake/model' } };
}

test('only explicit loopback HTTP endpoints are allowed', () => {
  for (const value of ['http://127.0.0.1:41893', 'http://localhost:41893/', 'http://[::1]:41893']) assert.ok(validateBaseUrl(value));
  for (const value of ['https://127.0.0.1:41893', 'http://example.com:41893', 'http://127.0.0.1',
    'http://user:secret@localhost:41893', 'http://localhost:41893/api', 'http://localhost:41893/?token=secret', 'http://localhost:41893/#hash']) {
    assert.throws(() => validateBaseUrl(value), EnvironmentBlocked);
  }
});

test('parent isolation declaration locks endpoint, Fake model, disabled scheduling and disjoint roots', async t => {
  const options = await fixture(t);
  assert.equal(validateIsolation(options.isolation, baseUrl, options.projectParent).verification, 'parent-launch-declaration');
  for (const delta of [{ schedulingEnabled: true }, { startupRecoveryEnabled: true }, { model: 'real/paid' },
    { opencodeMode: 'managed' }, { baseUrl: 'http://localhost:41999' }, { dataDir: options.projectParent }, { projectRoot: '/workspace' },
    { ready: false }, { ready: undefined }, { revision: '11ca25a3' }, { revision: 'foreign' }, { runRoot: options.isolation.dataDir },
    { dataDir: path.join(options.isolation.projectRoot, 'data') }]) {
    assert.throws(() => validateIsolation({ ...options.isolation, ...delta }, baseUrl, options.projectParent), EnvironmentBlocked);
  }
  assert.throws(() => validateIsolation(options.isolation, baseUrl, '/workspace/unrelated/A'), EnvironmentBlocked);
  const infrastructureRevision = 'a'.repeat(40);
  assert.throws(() => validateIsolation({ ...options.isolation, revision: infrastructureRevision }, baseUrl, options.projectParent), EnvironmentBlocked);
  assert.equal(validateIsolation({ ...options.isolation, revision: infrastructureRevision }, baseUrl, options.projectParent, infrastructureRevision).revision, infrastructureRevision);
  assert.throws(() => validateIsolation(options.isolation, baseUrl, options.projectParent, '11ca25a3'), EnvironmentBlocked);
});

test('symlinked project parent fails before even the runtime GET', async t => {
  const options = await fixture(t);
  const alias = path.join(options.isolation.projectRoot, 'alias');
  await symlink(options.projectParent, alias);
  let calls = 0;
  const report = await runRestContracts({ ...options, projectParent: alias, fetchImpl: async () => { calls++; return response(runtime); } });
  assert.equal(calls, 0); assert.equal(report.cases[0].status, 'ENV_BLOCKED');
  assert.equal(report.counts.NOT_RUN, CASES.length - 1);
});

test('symlinked data or declared project root fails canonical ownership before any request', async t => {
  const options = await fixture(t);
  const dataAlias = path.join(options.isolation.runRoot, 'data-alias');
  const rootAlias = path.join(options.isolation.runRoot, 'projects-alias');
  await symlink(options.isolation.dataDir, dataAlias); await symlink(options.isolation.projectRoot, rootAlias);
  for (const delta of [{ dataDir: dataAlias }, { projectRoot: rootAlias }]) {
    let calls = 0;
    const report = await runRestContracts({ ...options, isolation: { ...options.isolation, ...delta },
      fetchImpl: async () => { calls++; return response(runtime); } });
    assert.equal(calls, 0); assert.equal(report.cases[0].status, 'ENV_BLOCKED');
    assert.equal(report.counts.NOT_RUN, CASES.length - 1);
  }
});

test('a non-Fake runtime blocks every mutation and leaves all dependent cases NOT_RUN', async t => {
  const options = await fixture(t), methods = [];
  const report = await runRestContracts({ ...options, fetchImpl: async (_url, init) => { methods.push(init.method); return response({ ...runtime, version: 'production' }); } });
  assert.deepEqual(methods, ['GET']); assert.equal(report.counts.ENV_BLOCKED, 1);
  assert.equal(report.counts.NOT_RUN, CASES.length - 1); assert.equal(report.artifacts.projectPath, undefined);
});

test('prepared replay freezes raw body, original request key and digest despite external draft edits', async () => {
  const body = { requestKey: 'original_request_key_16', expectedVersion: 7, graph: { nodes: [{ id: 'first' }] } };
  const request = prepareRequest('POST', '/api/workflows/requirements', body);
  body.requestKey = 'new_request_key'; body.expectedVersion = 99; body.graph.nodes[0].id = 'changed';
  const writes = [], trace = [];
  const client = createRestClient(baseUrl, { trace, fetchImpl: async (_url, init) => { writes.push(init); return response(receipt); } });
  await client.send(request); await client.send(request, { purpose: 'explicit-original-replay' });
  assert.equal(request.requestKey, 'original_request_key_16'); assert.equal(request.bodySha256, sha256(request.bodyText));
  assert.equal(writes[0].body, writes[1].body); assert.equal(JSON.parse(writes[1].body).expectedVersion, 7);
  assert.equal(JSON.parse(writes[1].body).graph.nodes[0].id, 'first'); assert.equal(writes[1].redirect, 'error');
  assert.equal(trace[1].purpose, 'explicit-original-replay'); assert.equal(trace[0].bodySha256, trace[1].bodySha256);
});

test('an uncertain write is attempted once and blocks later writes while allowing authoritative GET', async () => {
  let calls = 0;
  const client = createRestClient(baseUrl, { fetchImpl: async (_url, init) => {
    calls++; if (init.method !== 'GET') throw new TypeError('connection closed after send'); return response({ id: 'owned' });
  } });
  const request = prepareRequest('POST', '/api/workflows/requirements', { requestKey: 'original_request_key_16' });
  await assert.rejects(client.send(request), EnvironmentBlocked);
  assert.equal(calls, 1); assert.equal(client.uncertainWrite, true);
  await assert.rejects(client.send(request), EnvironmentBlocked); assert.equal(calls, 1);
  assert.deepEqual(await client.get('/api/workflows/requirements/owned'), { id: 'owned' }); assert.equal(calls, 2);
});

test('malformed fulfilled write receipt remains uncertain rather than authorizing a new write', async () => {
  let calls = 0;
  const client = createRestClient(baseUrl, { fetchImpl: async () => { calls++; return new Response('not JSON', { status: 200 }); } });
  const request = prepareRequest('POST', '/api/workflows/templates', { requestKey: 'original_request_key_16' });
  await assert.rejects(client.send(request), EnvironmentBlocked); assert.equal(client.uncertainWrite, true);
  await assert.rejects(client.send(request), EnvironmentBlocked); assert.equal(calls, 1);
});

test('ProblemDetail and local UI header assertions preserve 409 and 400 semantics', async () => {
  const calls = [];
  const client = createRestClient(baseUrl, { fetchImpl: async (_url, init) => {
    calls.push(init); return response({ status: 409, errorCode: 'WORKFLOW_REQUEST_CONFLICT', detail: 'same key different body' }, 409);
  } });
  await client.send(prepareRequest('POST', '/api/workflows/templates', { requestKey: 'original_request_key_16' }), { expected: 409, errorCode: 'WORKFLOW_REQUEST_CONFLICT' });
  assert.equal(calls[0].headers['X-Loopper-Local-UI'], '1');
  await client.send(prepareRequest('POST', '/api/workflows/templates', {}, { localUi: false }), { expected: 409, errorCode: 'WORKFLOW_REQUEST_CONFLICT' });
  assert.equal(calls[1].headers['X-Loopper-Local-UI'], undefined);
  await assert.rejects(client.send(prepareRequest('POST', '/api/workflows/templates', {}), { expected: 400, errorCode: 'FIELD_VALIDATION' }));
});

test('accepted readback must match known identity and all independent CAS coordinates', () => {
  validateReceipt(receipt, { id: 'owned', state: 'ACTIVE', revision: 1 });
  const detail = { ...receipt, graph: { schemaVersion: 1, nodes: [], edges: [], inputs: [] }, layout: {}, diagnostics: [], projectId: 'project' };
  validateDetail(detail, receipt, { projectId: 'project' });
  for (const delta of [{ id: 'foreign' }, { revision: 2 }, { version: 1 }, { layoutVersion: 1 }, { projectId: 'foreign' }]) {
    assert.throws(() => validateDetail({ ...detail, ...delta }, receipt, { projectId: 'project' }));
  }
  for (const delta of [{ id: '' }, { version: -1 }, { revision: 0 }, { layoutVersion: 0.5 }]) assert.throws(() => validateReceipt({ ...receipt, ...delta }));
});

test('SSE parser supports split CRLF, comments, multiline data and persistent IDs', () => {
  const events = [], parser = createSseParser(value => events.push(value));
  parser.feed(': heartbeat\r\nid: 41\r\nevent: message\r\ndata: {"type":\r');
  parser.feed('\ndata: "task.started"}\r\n\r\ndata: {"type":"task.cancelled"}\n\n');
  assert.deepEqual(events, [
    { id: '41', event: 'message', data: '{"type":\n"task.started"}' },
    { id: '41', event: 'message', data: '{"type":"task.cancelled"}' },
  ]);
});

test('SSE uses explicit Last-Event-ID and cancels its reader immediately after bounded replay', async () => {
  let cancelled = 0, input;
  const stream = new ReadableStream({ start(controller) {
    controller.enqueue(new TextEncoder().encode('id: 42\ndata: {"type":"task.cancelled","at":"now","data":{"state":"CANCELLED"}}\n\n'));
  }, cancel() { cancelled++; } });
  const events = await readTaskEvents(baseUrl, 'owned-task', { lastEventId: '41', fetchImpl: async (_url, init) => {
    input = init; return new Response(stream, { headers: { 'Content-Type': 'text/event-stream' } });
  } });
  assert.equal(input.headers['Last-Event-ID'], '41'); assert.equal(input.signal.aborted, true);
  assert.equal(input.redirect, 'error'); assert.equal(cancelled, 1); assert.deepEqual(events.map(value => value.id), ['42']);
});

test('SSE rejects duplicate/older sequences and still releases the reader', async () => {
  let cancelled = 0;
  const stream = new ReadableStream({ start(controller) {
    controller.enqueue(new TextEncoder().encode('id: 41\ndata: {"type":"task.cancelled","at":"now","data":{}}\n\n'));
  }, cancel() { cancelled++; } });
  await assert.rejects(readTaskEvents(baseUrl, 'owned-task', { lastEventId: '41', fetchImpl: async () => new Response(stream, { headers: { 'Content-Type': 'text/event-stream' } }) }), /excludes the cursor/);
  assert.equal(cancelled, 1);
});

test('route and CLI validation cannot escape to a second origin or silently ignore an option', () => {
  for (const route of ['http://example.com/api', '//example.com/api', '/api/../secret']) assert.throws(() => prepareRequest('GET', route));
  const args = ['--base-url', baseUrl, '--project-parent', '/tmp/run/projects/A', '--report', '/tmp/run/results/A.json', '--isolation', '/tmp/run/isolation.json'];
  assert.equal(parseArguments(args).report, '/tmp/run/results/A.json');
  assert.throws(() => parseArguments([...args, '--unsafe', 'true']));
  assert.throws(() => parseArguments([...args, '--base-url', baseUrl]));
  assert.throws(() => parseArguments(args.slice(0, 2)));
  assert.equal(parseArguments([...args, '--expected-revision', 'a'.repeat(40)])['expected-revision'], 'a'.repeat(40));
  assert.throws(() => parseArguments([...args, '--expected-revision', '11ca25a3']));
});
