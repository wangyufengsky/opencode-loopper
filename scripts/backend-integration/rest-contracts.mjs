import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, mkdtemp, readFile, realpath, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export class EnvironmentBlocked extends Error {}
export const EXPECTED_RUNTIME_REVISION = '11ca25a3bb764a2d80ac924350139a7087639121';
export const sha256 = value => createHash('sha256').update(value).digest('hex');
const key = () => `rest_${randomUUID().replaceAll('-', '')}`;
const clone = value => JSON.parse(JSON.stringify(value));
const contained = (parent, child) => child === parent || child.startsWith(`${parent}${path.sep}`);

export function validateBaseUrl(value) {
  const url = new URL(value);
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)
      || !url.port || url.username || url.password || url.search || url.hash || url.pathname !== '/') {
    throw new EnvironmentBlocked('Use an explicit HTTP loopback port, without credentials, query, hash or path');
  }
  return url.origin;
}

function dedicatedPath(value) {
  if (typeof value !== 'string' || !path.isAbsolute(value)) throw new EnvironmentBlocked('An absolute dedicated path is required');
  const resolved = path.resolve(value);
  if (!['/tmp/', '/workspace/'].some(prefix => resolved.startsWith(prefix)) || resolved.split(path.sep).length < 3) {
    throw new EnvironmentBlocked(`Path is not a dedicated temporary directory: ${resolved}`);
  }
  return resolved;
}

// This is the parent's launch declaration, not a claim that REST exposes SQLite or scheduler configuration.
export function validateIsolation(declaration, baseUrl, projectParent, expectedRevision = EXPECTED_RUNTIME_REVISION) {
  if (!declaration || typeof declaration !== 'object') throw new EnvironmentBlocked('Parent isolation declaration is required');
  if (!/^[0-9a-f]{40}$/.test(expectedRevision)) throw new EnvironmentBlocked('Expected revision must be an explicit full 40-hex SHA');
  if (declaration.ready !== true || declaration.revision !== expectedRevision
      || validateBaseUrl(declaration.baseUrl) !== validateBaseUrl(baseUrl)
      || declaration.opencodeMode !== 'fake' || declaration.schedulingEnabled !== false
      || declaration.startupRecoveryEnabled !== false || declaration.model !== 'fake/model') {
    throw new EnvironmentBlocked('The ready launch declaration must match the exact runtime revision and fake-only, scheduling-disabled endpoint');
  }
  const runRoot = dedicatedPath(declaration.runRoot);
  const dataDir = dedicatedPath(declaration.dataDir);
  const projectRoot = dedicatedPath(declaration.projectRoot);
  const parent = dedicatedPath(projectParent);
  if (runRoot === dataDir || runRoot === projectRoot || !contained(runRoot, dataDir) || !contained(runRoot, projectRoot)
      || contained(dataDir, projectRoot) || contained(projectRoot, dataDir)
      || !contained(projectRoot, parent) || contained(dataDir, parent) || contained(parent, dataDir)) {
    throw new EnvironmentBlocked('Projects must be in the declared project root and separate from the data directory');
  }
  return { ...clone(declaration), runRoot, dataDir, projectRoot, projectParent: parent, verification: 'parent-launch-declaration' };
}

export function prepareRequest(method, route, body, { localUi = true } = {}) {
  if (!['GET', 'POST', 'PUT', 'DELETE'].includes(method) || !/^\/api\/[A-Za-z0-9_/?=&.%:-]+$/.test(route)
      || route.includes('..') || route.includes('//')) throw new Error('Invalid API request');
  const bodyText = body === undefined ? undefined : JSON.stringify(body);
  return Object.freeze({ method, route, bodyText, bodySha256: bodyText === undefined ? null : sha256(bodyText),
    requestKey: body?.requestKey ?? null, localUi });
}

export function createRestClient(baseUrl, { fetchImpl = fetch, timeoutMs = 15000, trace = [] } = {}) {
  const origin = validateBaseUrl(baseUrl);
  let uncertainWrite = false;
  return {
    trace,
    get uncertainWrite() { return uncertainWrite; },
    async send(request, { expected = 200, errorCode, purpose = 'explicit-command' } = {}) {
      if (uncertainWrite && request.method !== 'GET') {
        throw new EnvironmentBlocked('A write has an uncertain transport result; no further writes are permitted in this run');
      }
      const entry = { sequence: trace.length + 1, method: request.method, route: request.route,
        purpose, requestKey: request.requestKey, body: request.bodyText === undefined ? null : JSON.parse(request.bodyText),
        bodySha256: request.bodySha256, localUi: request.localUi, startedAt: new Date().toISOString() };
      trace.push(entry);
      let value;
      try {
        const response = await fetchImpl(`${origin}${request.route}`, {
          method: request.method, redirect: 'error', signal: AbortSignal.timeout(timeoutMs),
          headers: { Accept: 'application/json', ...(request.bodyText === undefined ? {} : { 'Content-Type': 'application/json' }),
            ...(request.localUi && request.method !== 'GET' ? { 'X-Loopper-Local-UI': '1' } : {}) },
          ...(request.bodyText === undefined ? {} : { body: request.bodyText }),
        });
        entry.status = response.status;
        const text = await response.text();
        if (text.length > 4 * 1024 * 1024) throw new Error('Response exceeds the evidence size bound');
        value = text ? JSON.parse(text) : null;
        entry.response = value;
        entry.responseSha256 = sha256(text);
      } catch (error) {
        entry.transportError = String(error);
        // Includes a malformed successful receipt: acceptance is not inferred to have failed.
        if (request.method !== 'GET') uncertainWrite = true;
        throw new EnvironmentBlocked(`Transport/receipt failure: ${request.method} ${request.route}: ${error}`);
      } finally { entry.finishedAt = new Date().toISOString(); }
      assert.equal(entry.status, expected, `${request.method} ${request.route}: ${JSON.stringify(value)}`);
      if (errorCode) {
        assert.equal(value?.status, expected, 'ProblemDetail.status');
        assert.equal(value?.errorCode, errorCode, 'ProblemDetail.errorCode');
      }
      return value;
    },
    get(route) { return this.send(prepareRequest('GET', route), { purpose: 'authoritative-read' }); },
  };
}

export function validateReceipt(receipt, { id, state, revision } = {}) {
  assert.ok(receipt && typeof receipt.id === 'string' && receipt.id.length > 0, 'receipt must have a known ID');
  for (const field of ['revision', 'version', 'layoutVersion']) {
    assert.ok(Number.isSafeInteger(receipt[field]) && receipt[field] >= (field === 'revision' ? 1 : 0), `receipt.${field}`);
  }
  assert.equal(typeof receipt.state, 'string');
  if (id !== undefined) assert.equal(receipt.id, id, 'receipt scope');
  if (state !== undefined) assert.equal(receipt.state, state);
  if (revision !== undefined) assert.equal(receipt.revision, revision);
  return receipt;
}

export function validateDetail(value, receipt, { projectId } = {}) {
  assert.equal(value?.id, receipt.id, 'readback ID must match the accepted receipt');
  assert.equal(value.revision, receipt.revision, 'readback revision');
  assert.equal(value.version, receipt.version, 'readback CAS version');
  assert.equal(value.layoutVersion, receipt.layoutVersion, 'readback layout CAS version');
  assert.equal(value.graph?.schemaVersion, 1);
  assert.ok(Array.isArray(value.graph.nodes) && Array.isArray(value.graph.edges) && Array.isArray(value.graph.inputs));
  assert.ok(value.layout && Array.isArray(value.diagnostics));
  if (projectId !== undefined) assert.equal(value.projectId, projectId);
  return value;
}

export function humanGraph(title = '人工检查') {
  return { schemaVersion: 1, nodes: [{ id: 'human', title, kind: 'HUMAN', moduleId: null, moduleVersion: 0,
    roleId: null, task: '人工检查并提交结果', inputs: [], outputs: [{ name: 'result', title: '处理结果', kind: 'TEXT', required: true }],
    outcomes: ['SUCCESS', 'REVISE'], completion: { kind: 'HUMAN', criterion: '人工确认并提交结果', expectedOutcome: null },
    maxRetries: 1, pauseAfter: true, parameters: {} }], edges: [], inputs: [] };
}
export const emptyLayout = () => ({ positions: {}, x: 0, y: 0, zoom: 1 });
export function taskSpec(projectId) {
  return { schemaVersion: 'v2', projectId, goal: '核验专属项目 README', context: '本地 Fake REST 合同，不运行外部命令',
    stages: [{ objective: '核验 README 内容', allowedPaths: ['README.md'], forbiddenPaths: [], deliverables: ['README.md'],
      implementationKind: 'NON_JAVA', acceptanceCriteria: [{ id: 'AC-1', description: 'README 内容为 fixture', verificationMode: 'MACHINE' }],
      verifiers: [{ type: 'FILE_CONTENT', path: 'README.md', matchMode: 'EXACT', expectedContent: 'fixture', criterionIds: ['AC-1'] }] }],
    model: { providerId: 'fake', modelId: 'model', thinking: false } };
}

export function createSseParser(onEvent) {
  let buffer = '', data = [], id = '', event = '';
  function line(value) {
    if (value === '') {
      if (data.length) onEvent({ id, event: event || 'message', data: data.join('\n') });
      data = []; event = ''; return;
    }
    if (value.startsWith(':')) return;
    const separator = value.indexOf(':');
    const field = separator < 0 ? value : value.slice(0, separator);
    let content = separator < 0 ? '' : value.slice(separator + 1);
    if (content.startsWith(' ')) content = content.slice(1);
    if (field === 'data') data.push(content);
    if (field === 'id' && !content.includes('\0')) id = content;
    if (field === 'event') event = content;
  }
  return { feed(chunk) {
    buffer += chunk;
    let index;
    while ((index = buffer.indexOf('\n')) >= 0) {
      const value = buffer.slice(0, index).replace(/\r$/, ''); buffer = buffer.slice(index + 1); line(value);
    }
  } };
}

export async function readTaskEvents(baseUrl, taskId, { lastEventId, stopType = 'task.cancelled', fetchImpl = fetch, timeoutMs = 10000 } = {}) {
  assert.match(taskId, /^[A-Za-z0-9_-]+$/);
  if (lastEventId !== undefined) assert.match(lastEventId, /^\d+$/);
  const abort = new AbortController();
  const timeout = setTimeout(() => abort.abort(new Error('SSE deadline')), timeoutMs);
  let reader;
  const events = [];
  try {
    const response = await fetchImpl(`${validateBaseUrl(baseUrl)}/api/tasks/${taskId}/events`, {
      redirect: 'error', signal: abort.signal,
      headers: { Accept: 'text/event-stream', ...(lastEventId === undefined ? {} : { 'Last-Event-ID': lastEventId }) },
    });
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type') ?? '', /^text\/event-stream/);
    assert.ok(response.body, 'SSE body');
    reader = response.body.getReader();
    const decoder = new TextDecoder();
    let bytes = 0;
    const parser = createSseParser(raw => {
      assert.match(raw.id, /^\d+$/, 'server sequence ID');
      const value = JSON.parse(raw.data);
      assert.equal(typeof value.type, 'string');
      assert.equal(typeof value.at, 'string');
      events.push({ id: raw.id, ...value });
      assert.ok(events.length <= 1000, 'bounded SSE event count');
    });
    while (!events.some(value => value.type === stopType)) {
      const chunk = await reader.read();
      assert.equal(chunk.done, false, 'stream ended before the expected persisted event');
      bytes += chunk.value.byteLength;
      assert.ok(bytes <= 2 * 1024 * 1024, 'bounded SSE bytes');
      parser.feed(decoder.decode(chunk.value, { stream: true }));
    }
    let previous = BigInt(lastEventId ?? '0');
    for (const value of events) {
      assert.ok(BigInt(value.id) > previous, 'Last-Event-ID replay is strictly ascending and excludes the cursor');
      previous = BigInt(value.id);
    }
    return events;
  } finally {
    clearTimeout(timeout);
    abort.abort();
    if (reader) { try { await reader.cancel(); } finally { reader.releaseLock(); } }
  }
}

export const CASES = Object.freeze([
  ['P1', 'Fake-only launch and canonical isolation preflight'],
  ['V1', 'Local UI authority is required before template mutation'],
  ['V2', 'Invalid request key is a typed 400'],
  ['V3', 'Null graph is a typed 400'],
  ['V4', 'Bean field validation is a typed 400'],
  ['J1', 'Create and read one dedicated temporary project'],
  ['T1', 'Human graph execution validation and template creation'],
  ['T2', 'Explicit original template replay after discarded client receipt'],
  ['T3', 'Same key with different template body is a 409'],
  ['T4', 'Graph CAS update preserves immutable historical graph'],
  ['T5', 'Stale graph CAS is a 409 and cannot overwrite'],
  ['T6', 'Layout has independent CAS and original replay'],
  ['T7', 'Stale and invalid layout cannot overwrite'],
  ['T8', 'Copy original revision and replay its known receipt'],
  ['T9', 'Summary pagination excludes graph/layout bodies'],
  ['R1', 'Requirement original POST recovery and changed-body conflict'],
  ['R2', 'Known requirement GET preserves its source snapshot'],
  ['R3', 'Plan revision CAS, replay and immutable historical read'],
  ['R4', 'Confirm is PENDING_START and cannot start execution'],
  ['R5', 'Confirmed plan layout preserves lifecycle and graph version'],
  ['R6', 'Stale confirmation cannot replace the current plan'],
  ['R7', 'Never-started cancellation replays and retains history'],
  ['T10', 'Archive replays, hides the list item and retains snapshots'],
  ['D1', 'Public LoopDraft rejects new v1 contracts'],
  ['D2', 'Create, read, update and stale-CAS-check a valid v2 draft'],
  ['D3', 'Confirmation needs current version and does not start Task'],
  ['K1', 'Start the legitimate Fake Task with a known implementation writer'],
  ['K2', 'Cancel confirms the original writer stopped before lease release'],
  ['K3', 'Terminal start is rejected without a replacement writer'],
  ['S1', 'Persisted Task SSE reconnect honors Last-Event-ID; REST remains authoritative'],
]);

export async function runRestContracts(options) {
  const report = { schemaVersion: 1, startedAt: new Date().toISOString(), source: 'real-public-rest',
    baseUrl: options.baseUrl, cases: CASES.map(([id, title]) => ({ id, title, status: 'NOT_RUN' })), requests: [], artifacts: {},
    limitations: [
      'SQLite and scheduler configuration are verified by the parent launch declaration, not by a public REST DTO.',
      'Client-receipt discard is deliberate and is not a server/network fault, process restart, or cross-refresh recovery proof.',
      'Fake abort failure/held prompt/concurrent abort injection has no public REST control; use existing Java integration tests.',
      'Closing the SSE reader proves this client releases its resources; server subscriber cleanup requires Java lifecycle tests.',
    ] };
  let client;
  let stopped = false;
  async function check(id, action) {
    if (stopped) return;
    const row = report.cases.find(value => value.id === id);
    row.startedAt = new Date().toISOString(); row.firstRequest = report.requests.length + 1;
    try { await action(); row.status = 'PASS'; }
    catch (error) {
      row.status = error instanceof EnvironmentBlocked ? 'ENV_BLOCKED' : 'REPRODUCED_FAIL';
      row.failure = { name: error.name, message: error.message, stack: error.stack };
      stopped = true;
    } finally { row.finishedAt = new Date().toISOString(); row.lastRequest = report.requests.length; }
  }
  const send = (method, route, body, expected = 200, errorCode) => client.send(prepareRequest(method, route, body), { expected, errorCode });
  const get = route => client.get(route);
  const graph = humanGraph();
  const prefix = `REST-A-${randomUUID().slice(0, 8)}`;
  let project, template, createTemplate, templateReceipt, originalTemplate, revisedTemplate, copy, requirement, reqCreate, reqConfirmed, reqLayout;
  let draft, draftUpdated, task, taskBeforeStop, sessionsBeforeStop, queueBeforeStop;

  await check('P1', async () => {
    report.isolation = validateIsolation(options.isolation, options.baseUrl, options.projectParent, options.expectedRevision);
    let paths;
    try {
      paths = await Promise.all([
        realpath(report.isolation.runRoot), realpath(report.isolation.projectRoot), realpath(report.isolation.projectParent), realpath(report.isolation.dataDir),
      ]);
    } catch (error) { throw new EnvironmentBlocked(`Parent-declared isolation directories are unavailable: ${error}`); }
    const [runRoot, declaredRoot, parent, data] = paths;
    if (runRoot !== report.isolation.runRoot || declaredRoot !== report.isolation.projectRoot
        || parent !== report.isolation.projectParent || data !== report.isolation.dataDir) {
      throw new EnvironmentBlocked('Run, data, project root and project parent must all match their canonical declarations');
    }
    client = createRestClient(options.baseUrl, { fetchImpl: options.fetchImpl, timeoutMs: options.timeoutMs, trace: report.requests });
    const runtime = await get('/api/runtime/opencode');
    if (runtime.version !== 'fake' || runtime.status !== 'AVAILABLE' || runtime.managed !== false || runtime.pid !== null
        || runtime.model !== 'fake/model') throw new EnvironmentBlocked(`Runtime is not the declared Fake instance: ${JSON.stringify(runtime)}`);
    report.runtimePreflight = runtime;
  });
  await check('V1', () => client.send(prepareRequest('POST', '/api/workflows/templates', {
    requestKey: key(), title: prefix, description: '', graph, layout: emptyLayout(),
  }, { localUi: false }), { expected: 400, errorCode: 'LOCAL_UI_HEADER_REQUIRED' }));
  await check('V2', () => send('POST', '/api/workflows/templates', { requestKey: 'bad', title: prefix, graph }, 400, 'WORKFLOW_REQUEST_KEY_INVALID'));
  await check('V3', () => send('POST', '/api/workflows/templates', { requestKey: key(), title: prefix, graph: null }, 400, 'WORKFLOW_REQUIRED'));
  await check('V4', async () => {
    const problem = await send('POST', '/api/projects', { name: '', rootPath: '' }, 400, 'FIELD_VALIDATION');
    assert.ok(problem.fields.name && problem.fields.rootPath);
  });
  await check('J1', async () => {
    const root = await mkdtemp(path.join(report.isolation.projectParent, 'rest-contract-'));
    assert.ok(contained(report.isolation.projectParent, await realpath(root)));
    await writeFile(path.join(root, 'README.md'), 'fixture', { flag: 'wx' });
    report.artifacts.projectPath = root;
    project = await send('POST', '/api/projects', { name: prefix, rootPath: root, description: 'isolated fake REST contracts' }, 201);
    assert.ok(project.id); assert.equal(project.rootPath, root);
    const reread = await get(`/api/projects/${project.id}`); assert.equal(reread.id, project.id); assert.equal(reread.rootPath, root);
    report.artifacts.projectId = project.id;
  });
  await check('T1', async () => {
    assert.deepEqual(await send('POST', '/api/workflows/templates/validate', graph), []);
    createTemplate = prepareRequest('POST', '/api/workflows/templates', { requestKey: key(), title: prefix, description: 'isolated', graph, layout: emptyLayout() });
    templateReceipt = validateReceipt(await client.send(createTemplate), { state: 'ACTIVE', revision: 1 });
    template = validateDetail(await get(`/api/workflows/templates/${templateReceipt.id}`), templateReceipt);
    assert.equal(template.builtin, false); assert.equal(template.archived, false);
    originalTemplate = clone(template); report.artifacts.templateId = template.id;
  });
  await check('T2', async () => {
    // The first receipt is deliberately excluded from the simulated client state; the trace retains server evidence.
    const recovered = validateReceipt(await client.send(createTemplate, { purpose: 'explicit-original-replay-after-client-receipt-discard' }));
    assert.deepEqual(recovered, templateReceipt);
    validateDetail(await get(`/api/workflows/templates/${recovered.id}`), recovered);
  });
  await check('T3', () => send('POST', createTemplate.route, { ...JSON.parse(createTemplate.bodyText), title: `${prefix}-changed` }, 409, 'WORKFLOW_REQUEST_CONFLICT'));
  await check('T4', async () => {
    const request = prepareRequest('PUT', `/api/workflows/templates/${template.id}`, { requestKey: key(), expectedVersion: template.version,
      expectedRevision: template.revision, title: `${prefix}-revised`, description: 'revised', graph: humanGraph('修订人工检查') });
    revisedTemplate = validateReceipt(await client.send(request), { id: template.id, revision: 2 });
    assert.deepEqual(await client.send(request, { purpose: 'explicit-original-replay' }), revisedTemplate);
    template = validateDetail(await get(`/api/workflows/templates/${template.id}`), revisedTemplate);
    assert.deepEqual((await get(`/api/workflows/templates/${template.id}?revision=1`)).graph, originalTemplate.graph);
    assert.ok(template.version > originalTemplate.version);
  });
  await check('T5', async () => {
    await send('PUT', `/api/workflows/templates/${template.id}`, { requestKey: key(), expectedVersion: originalTemplate.version,
      expectedRevision: 1, title: 'stale', description: '', graph }, 409, 'WORKFLOW_VERSION_CONFLICT');
    assert.deepEqual((await get(`/api/workflows/templates/${template.id}`)).graph, template.graph);
  });
  await check('T6', async () => {
    const request = prepareRequest('PUT', `/api/workflows/templates/${template.id}/layout`, { requestKey: key(), expectedRevision: template.revision,
      expectedLayoutVersion: template.layoutVersion, layout: { positions: { human: { x: 105, y: 20 } }, x: 7, y: 8, zoom: 1.5 } });
    const saved = validateReceipt(await client.send(request), { id: template.id });
    assert.equal(saved.version, template.version); assert.equal(saved.revision, template.revision);
    assert.equal(saved.layoutVersion, template.layoutVersion + 1);
    assert.deepEqual(await client.send(request, { purpose: 'explicit-original-replay' }), saved);
    template = validateDetail(await get(`/api/workflows/templates/${template.id}`), saved);
    assert.deepEqual(template.layout, JSON.parse(request.bodyText).layout);
  });
  await check('T7', async () => {
    await send('PUT', `/api/workflows/templates/${template.id}/layout`, { requestKey: key(), expectedRevision: template.revision,
      expectedLayoutVersion: template.layoutVersion - 1, layout: emptyLayout() }, 409, 'WORKFLOW_VERSION_CONFLICT');
    await send('PUT', `/api/workflows/templates/${template.id}/layout`, { requestKey: key(), expectedRevision: template.revision,
      expectedLayoutVersion: template.layoutVersion, layout: { ...emptyLayout(), zoom: 0 } }, 400, 'CANVAS_LAYOUT_INVALID');
    assert.deepEqual((await get(`/api/workflows/templates/${template.id}`)).layout, template.layout);
  });
  await check('T8', async () => {
    const request = prepareRequest('POST', `/api/workflows/templates/${template.id}/copy`, { requestKey: key(), sourceRevision: 1, title: `${prefix}-copy` });
    const receipt = validateReceipt(await client.send(request));
    assert.deepEqual(await client.send(request, { purpose: 'explicit-original-replay' }), receipt);
    copy = validateDetail(await get(`/api/workflows/templates/${receipt.id}`), receipt);
    assert.equal(copy.sourceTemplateId, template.id); assert.equal(copy.sourceRevision, 1);
    assert.deepEqual(copy.graph, originalTemplate.graph); report.artifacts.copyId = copy.id;
  });
  await check('T9', async () => {
    const first = await get(`/api/workflows/templates?kind=CUSTOM&query=${prefix}&limit=1`);
    assert.equal(first.items.length, 1); assert.ok(first.nextCursor); assert.deepEqual(first.facets, {});
    const second = await get(`/api/workflows/templates?kind=CUSTOM&query=${prefix}&limit=1&cursor=${encodeURIComponent(first.nextCursor)}`);
    assert.equal(second.items.length, 1); assert.equal(second.nextCursor, null);
    assert.deepEqual([first.items[0].id, second.items[0].id].sort(), [template.id, copy.id].sort());
    for (const item of [...first.items, ...second.items]) for (const field of ['graph', 'layout', 'definitionJson', 'layoutJson']) assert.equal(field in item, false);
  });
  await check('R1', async () => {
    reqCreate = prepareRequest('POST', '/api/workflows/requirements', { requestKey: key(), projectId: project.id,
      title: `${prefix}-requirement`, objective: '人工核验隔离需求', templateId: copy.id, templateRevision: 1 });
    const receipt = validateReceipt(await client.send(reqCreate), { state: 'PLANNING' });
    assert.deepEqual(await client.send(reqCreate, { purpose: 'explicit-original-replay-after-client-receipt-discard' }), receipt);
    await send('POST', reqCreate.route, { ...JSON.parse(reqCreate.bodyText), objective: 'changed' }, 409, 'WORKFLOW_REQUEST_CONFLICT');
    requirement = validateDetail(await get(`/api/workflows/requirements/${receipt.id}`), receipt, { projectId: project.id });
    report.artifacts.requirementId = requirement.id;
  });
  await check('R2', async () => {
    assert.equal(requirement.sourceTemplateId, copy.id); assert.equal(requirement.sourceRevision, 1);
    assert.deepEqual(requirement.graph, originalTemplate.graph);
    assert.deepEqual((await get(`/api/workflows/requirements/${requirement.id}`)).graph, requirement.graph);
    const list = await get(`/api/workflows/requirements?projectId=${project.id}&limit=1`);
    assert.equal(list.items[0].id, requirement.id);
    assert.equal('graph' in list.items[0], false);
  });
  await check('R3', async () => {
    const before = clone(requirement);
    const request = prepareRequest('PUT', `/api/workflows/requirements/${requirement.id}/plan`, { requestKey: key(), expectedVersion: before.version,
      expectedRevision: before.revision, graph: humanGraph('独立需求人工检查') });
    const receipt = validateReceipt(await client.send(request), { id: before.id, revision: 2, state: 'PLANNING' });
    assert.deepEqual(await client.send(request, { purpose: 'explicit-original-replay' }), receipt);
    requirement = validateDetail(await get(`/api/workflows/requirements/${before.id}`), receipt);
    assert.deepEqual((await get(`/api/workflows/requirements/${before.id}?revision=1`)).graph, before.graph);
    assert.deepEqual((await get(`/api/workflows/templates/${copy.id}`)).graph, copy.graph);
    await send('PUT', request.route, { requestKey: key(), expectedVersion: before.version, expectedRevision: before.revision, graph }, 409, 'WORKFLOW_VERSION_CONFLICT');
  });
  await check('R4', async () => {
    const request = prepareRequest('POST', `/api/workflows/requirements/${requirement.id}/confirm`, { requestKey: key(), expectedVersion: requirement.version });
    reqConfirmed = validateReceipt(await client.send(request), { id: requirement.id, state: 'PENDING_START' });
    assert.deepEqual(await client.send(request, { purpose: 'explicit-original-replay' }), reqConfirmed);
    const execution = await get(`/api/workflows/requirements/${requirement.id}/execution`);
    assert.equal(execution.execution.id, requirement.id); assert.equal(execution.execution.state, 'PENDING_START');
    assert.equal(execution.control.configured, false); assert.equal(execution.control.reasonCode, 'WORKFLOW_NOT_STARTED');
    const attempts = await get(`/api/workflows/requirements/${requirement.id}/nodes/human/attempts`);
    assert.deepEqual(attempts.items, []);
    const tasks = await get(`/api/tasks/summaries?projectId=${project.id}&limit=100`); assert.deepEqual(tasks.items, []);
    requirement = validateDetail(await get(`/api/workflows/requirements/${requirement.id}`), reqConfirmed);
  });
  await check('R5', async () => {
    const request = prepareRequest('PUT', `/api/workflows/requirements/${requirement.id}/layout`, { requestKey: key(), expectedRevision: requirement.revision,
      expectedLayoutVersion: requirement.layoutVersion, layout: { positions: { human: { x: 55, y: 40 } }, x: 2, y: 3, zoom: 2 } });
    reqLayout = validateReceipt(await client.send(request), { id: requirement.id, state: 'PENDING_START' });
    assert.equal(reqLayout.version, reqConfirmed.version); assert.equal(reqLayout.revision, reqConfirmed.revision);
    assert.equal(reqLayout.layoutVersion, reqConfirmed.layoutVersion + 1);
    assert.deepEqual(await client.send(request, { purpose: 'explicit-original-replay' }), reqLayout);
    validateDetail(await get(`/api/workflows/requirements/${requirement.id}`), reqLayout);
    await send('PUT', request.route, { ...JSON.parse(request.bodyText), requestKey: key(), expectedLayoutVersion: reqConfirmed.layoutVersion }, 409, 'WORKFLOW_VERSION_CONFLICT');
  });
  await check('R6', () => send('POST', `/api/workflows/requirements/${requirement.id}/confirm`, { requestKey: key(), expectedVersion: reqConfirmed.version - 1 }, 409, 'WORKFLOW_VERSION_CONFLICT'));
  await check('R7', async () => {
    const request = prepareRequest('POST', `/api/workflows/requirements/${requirement.id}/cancel`, { requestKey: key(), expectedVersion: reqConfirmed.version });
    const cancelled = validateReceipt(await client.send(request), { id: requirement.id, state: 'CANCELLED' });
    assert.deepEqual(await client.send(request, { purpose: 'explicit-original-replay' }), cancelled);
    validateDetail(await get(`/api/workflows/requirements/${requirement.id}`), cancelled);
    assert.deepEqual((await get(`/api/workflows/requirements/${requirement.id}?revision=1`)).graph, originalTemplate.graph);
    assert.deepEqual((await get(`/api/workflows/requirements/${requirement.id}/nodes/human/attempts`)).items, []);
    await send('PUT', `/api/workflows/requirements/${requirement.id}/plan`, { requestKey: key(), expectedVersion: cancelled.version,
      expectedRevision: cancelled.revision, graph }, 409, 'WORKFLOW_EDIT_UNAVAILABLE');
  });
  await check('T10', async () => {
    const request = prepareRequest('DELETE', `/api/workflows/templates/${copy.id}`, { requestKey: key(), expectedVersion: copy.version });
    const receipt = validateReceipt(await client.send(request), { id: copy.id, state: 'ARCHIVED' });
    assert.deepEqual(await client.send(request, { purpose: 'explicit-original-replay' }), receipt);
    const history = await get(`/api/workflows/templates/${copy.id}?revision=1`); assert.equal(history.archived, true); assert.deepEqual(history.graph, copy.graph);
    const list = await get(`/api/workflows/templates?kind=CUSTOM&query=${prefix}&limit=100`); assert.deepEqual(list.items.map(value => value.id), [template.id]);
    assert.equal((await get(`/api/workflows/requirements/${requirement.id}`)).sourceTemplateId, copy.id);
  });
  await check('D1', () => send('POST', '/api/loop-drafts', { spec: { ...taskSpec(project.id), schemaVersion: 'v1' } }, 400, 'LOOPSPEC_V2_REQUIRED'));
  await check('D2', async () => {
    const spec = taskSpec(project.id);
    assert.equal((await send('POST', '/api/loop-drafts/validate', { spec })).valid, true);
    draft = await send('POST', '/api/loop-drafts', { spec }, 201);
    assert.equal(draft.spec.schemaVersion, 'v2'); assert.equal(draft.version, 0); assert.equal(draft.status, 'DRAFT_READY');
    assert.deepEqual(await get(`/api/loop-drafts/${draft.id}`), draft);
    draftUpdated = await send('PUT', `/api/loop-drafts/${draft.id}`, { spec: { ...draft.spec, goal: '核验更新后的 README 合同' }, expectedVersion: draft.version });
    assert.equal(draftUpdated.version, draft.version + 1);
    await send('PUT', `/api/loop-drafts/${draft.id}`, { spec: draft.spec, expectedVersion: draft.version }, 409, 'DESIGNER_DRAFT_CHANGED');
    assert.deepEqual(await get(`/api/loop-drafts/${draft.id}`), draftUpdated); report.artifacts.draftId = draft.id;
  });
  await check('D3', async () => {
    await send('POST', `/api/loop-drafts/${draft.id}/confirm`, { title: prefix }, 400, 'DRAFT_VERSION_REQUIRED');
    await send('POST', `/api/loop-drafts/${draft.id}/confirm`, { title: prefix, expectedVersion: draft.version }, 409, 'DRAFT_VERSION_CONFLICT');
    const receipt = await send('POST', `/api/loop-drafts/${draft.id}/confirm`, { title: prefix, expectedVersion: draftUpdated.version });
    assert.ok(receipt.taskId); task = await get(`/api/tasks/${receipt.taskId}`);
    assert.equal(task.status, 'PENDING_START'); assert.equal(task.projectId, project.id); assert.equal(task.attemptCount, 0);
    assert.deepEqual(await get(`/api/tasks/${task.id}/sessions`), []); report.artifacts.taskId = task.id;
  });
  await check('K1', async () => {
    const started = await send('POST', `/api/tasks/${task.id}/start`);
    assert.equal(started.status, 'RUNNING', 'positive setup must establish an actual active Task');
    taskBeforeStop = await get(`/api/tasks/${task.id}`);
    sessionsBeforeStop = await get(`/api/tasks/${task.id}/sessions`);
    assert.ok(sessionsBeforeStop.some(value => value.kind === 'IMPLEMENTATION' && value.externalSessionId && value.state === 'RUNNING'),
      `positive setup must establish the original writer: ${JSON.stringify(sessionsBeforeStop)}`);
    queueBeforeStop = await get(`/api/tasks/${task.id}/queue`);
    assert.equal(queueBeforeStop.leaseState, 'HELD');
  });
  await check('K2', async () => {
    const cancelled = await send('POST', `/api/tasks/${task.id}/cancel`);
    assert.equal(cancelled.status, 'CANCELLED');
    const authority = await get(`/api/tasks/${task.id}/overview`); assert.equal(authority.id, task.id); assert.equal(authority.status, 'CANCELLED');
    const sessions = await get(`/api/tasks/${task.id}/sessions`);
    assert.deepEqual(sessions.map(value => value.externalSessionId).sort(), sessionsBeforeStop.map(value => value.externalSessionId).sort());
    for (const original of sessionsBeforeStop.filter(value => value.kind === 'IMPLEMENTATION' && value.externalSessionId)) {
      const same = sessions.find(value => value.localSessionId === original.localSessionId);
      assert.ok(same); assert.equal(same.state, 'ABORTED'); assert.equal(same.externalSessionId, original.externalSessionId);
      const activity = await get(`/api/tasks/${task.id}/sessions/${encodeURIComponent(same.key)}`);
      assert.equal(activity.session.externalSessionId, original.externalSessionId); assert.equal(activity.remoteState, 'ABORTED');
    }
    const queue = await get(`/api/tasks/${task.id}/queue`); assert.equal(queue.leaseState, 'RELEASED');
    const after = await get(`/api/tasks/${task.id}`); assert.equal(after.attemptCount, taskBeforeStop.attemptCount);
    const audit = await get(`/api/tasks/${task.id}/audit`);
    assert.ok(audit.attempts.length > 0); assert.ok(audit.attempts.every(value => value.status !== 'RUNNING'));
    report.stopProof = { taskId: task.id, originalSessions: sessionsBeforeStop, sessionsAfter: sessions, queueBefore: queueBeforeStop,
      queueAfter: queue, authority, boundary: 'public Fake positive abort path; not injected unacknowledged-abort/concurrency proof' };
  });
  await check('K3', async () => {
    const before = await get(`/api/tasks/${task.id}/sessions`);
    await send('POST', `/api/tasks/${task.id}/start`, undefined, 409, 'TASK_TERMINAL');
    assert.deepEqual(await get(`/api/tasks/${task.id}/sessions`), before);
    assert.equal((await get(`/api/tasks/${task.id}/overview`)).status, 'CANCELLED');
  });
  await check('S1', async () => {
    const all = await readTaskEvents(options.baseUrl, task.id, { fetchImpl: options.fetchImpl, timeoutMs: options.timeoutMs });
    const cursorEvent = all.find(value => value.type !== 'task.cancelled');
    assert.ok(cursorEvent, 'positive replay requires an earlier persisted event');
    const replay = await readTaskEvents(options.baseUrl, task.id, { lastEventId: cursorEvent.id, fetchImpl: options.fetchImpl, timeoutMs: options.timeoutMs });
    assert.deepEqual(replay, all.filter(value => BigInt(value.id) > BigInt(cursorEvent.id)));
    assert.equal((await get(`/api/tasks/${task.id}/overview`)).status, 'CANCELLED');
    report.sse = { taskId: task.id, lastEventId: cursorEvent.id, initial: all, replay, clientReadersClosed: true };
  });
  report.finishedAt = new Date().toISOString();
  report.counts = Object.fromEntries(['PASS', 'REPRODUCED_FAIL', 'ENV_BLOCKED', 'NOT_RUN'].map(status => [status, report.cases.filter(value => value.status === status).length]));
  return report;
}

export function parseArguments(args) {
  const required = ['base-url', 'project-parent', 'isolation', 'report'];
  const allowed = new Set([...required, 'expected-revision']);
  const values = {};
  for (let index = 0; index < args.length; index += 2) {
    const name = args[index]?.replace(/^--/, '');
    if (!args[index]?.startsWith('--') || !allowed.has(name) || !args[index + 1] || values[name]) throw new Error(`Invalid option ${args[index]}`);
    values[name] = args[index + 1];
  }
  for (const name of required) if (!values[name]) throw new Error(`Required --${name}`);
  if (values['expected-revision'] && !/^[0-9a-f]{40}$/.test(values['expected-revision'])) throw new Error('Expected revision must be a full 40-hex SHA');
  return values;
}

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const reportPath = dedicatedPath(args.report);
  const isolation = JSON.parse(await readFile(args.isolation, 'utf8'));
  await mkdir(path.dirname(reportPath), { recursive: true });
  // Reserve evidence before any HTTP request; an earlier run can never be overwritten.
  await writeFile(reportPath, '', { flag: 'wx' });
  const report = await runRestContracts({ baseUrl: args['base-url'], projectParent: args['project-parent'], isolation, expectedRevision: args['expected-revision'] });
  await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`);
  console.log(JSON.stringify({ report: reportPath, counts: report.counts }));
  process.exitCode = report.counts.REPRODUCED_FAIL || report.counts.ENV_BLOCKED || report.counts.NOT_RUN ? 1 : 0;
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch(error => { console.error(error.stack ?? String(error)); process.exitCode = 1; });
}
