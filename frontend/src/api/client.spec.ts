import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, backendAutomationRule, normalizeAutomationRule, subscribeDesignerEvents } from '@/api/client'
import type { AutomationRule, LoopSpec } from '@/types/domain'

const spec: LoopSpec = {
  schemaVersion: 'v1', projectId: 'project-1', goal: 'Verify the contract', context: '',
  stages: [{ objective: 'Implement', allowedPaths: ['src/**'], forbiddenPaths: [], deliverables: ['source'], verifiers: [
    { type: 'PROCESS', command: ['./mvnw', 'test'], outputContains: 'BUILD SUCCESS' },
    { type: 'FILE_EXISTS', path: 'src/App.java' },
    { type: 'GIT_DIFF', requireChanges: true, allowedPaths: ['src/**'], forbiddenPaths: ['data/**'], forbidDeletes: true },
  ] }],
  limits: { maxStageAttempts: 3, maxTaskAttempts: 12, sessionErrorLimit: 4, stagnationLimit: 5, maxDuration: 'PT2H', attemptTimeout: 'PT30M', verifierTimeout: 'PT7M' },
  model: { providerId: 'provider-1', modelId: 'model-1', thinking: false },
  sessionPolicy: { reuseHealthySession: false, createFreshOnVerifierFailure: false },
  nextAttemptPromptTemplate: '修复 ${failureSummary}',
}

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

afterEach(() => vi.unstubAllGlobals())

describe('Loopper REST contract adapter', () => {
  it('streams normalized Designer partial output and connection state', () => {
    class FakeEventSource {
      static latest?: FakeEventSource
      onopen?: () => void
      onmessage?: (message: MessageEvent<string>) => void
      onerror?: () => void
      constructor(readonly url: string) { FakeEventSource.latest = this }
      close = vi.fn()
    }
    vi.stubGlobal('EventSource', FakeEventSource)
    const onEvent = vi.fn()
    const onState = vi.fn()

    const stream = subscribeDesignerEvents('designer 1', onEvent, onState)
    FakeEventSource.latest?.onopen?.()
    FakeEventSource.latest?.onmessage?.({ data: JSON.stringify({
      sequence: 4, sessionId: 'designer 1', type: 'PARTIAL', state: 'RUNNING', remoteState: 'busy',
      runtimeConnected: true, content: '## streamed', detail: 'receiving', at: 'now',
    }) } as MessageEvent<string>)

    expect(FakeEventSource.latest?.url).toBe('/api/designer-sessions/designer%201/events')
    expect(onState).toHaveBeenCalledWith('connected')
    expect(onEvent).toHaveBeenCalledWith(expect.objectContaining({ type: 'PARTIAL', content: '## streamed', runtimeConnected: true }))
    stream.close()
    expect(FakeEventSource.latest?.close).toHaveBeenCalled()
  })

  it('requests a native project directory only through the local UI endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ selected: true, path: '/tmp/example-project', name: 'example-project' }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.pickProjectDirectory()).resolves.toEqual({ selected: true, path: '/tmp/example-project', name: 'example-project' })
    expect(fetchMock).toHaveBeenCalledWith('/api/projects/pick-directory', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }),
    }))
  })

  it('persists project descriptions and repository execution metadata', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({
      id: 'project-1', name: 'Example', rootPath: '/tmp/example', description: 'Useful context',
      status: 'READY', executionMode: 'WORKTREE', branch: 'main', updatedAt: 'now', taskCount: 0, openDesignerSessionCount: 2,
    }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.createProject({ name: 'Example', rootPath: '/tmp/example', description: '  Useful context  ' }))
      .resolves.toMatchObject({ description: 'Useful context', executionMode: 'WORKTREE', branch: 'main', openDesignerSessionCount: 2 })
    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).toEqual({
      name: 'Example', rootPath: '/tmp/example', description: 'Useful context',
    })
  })

  it('wraps LoopSpec, reads taskId, and maps AVAILABLE to ONLINE', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ id: 'draft-1', status: 'DRAFT_READY', updatedAt: 'now', spec }, 201))
      .mockResolvedValueOnce(json({ taskId: 'task-1' }))
      .mockResolvedValueOnce(json({ loopperVersion: '0.1.53', status: 'OFFLINE', managed: false, endpoint: 'http://127.0.0.1:51234', checkedAt: 'now', startupFailure: 'Managed OpenCode exited with code 1 before it became healthy' }))
    vi.stubGlobal('fetch', fetchMock)

    await api.createDraft(spec)
    const createBody = JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))
    expect(createBody).toEqual({ spec: expect.objectContaining({ projectId: 'project-1' }) })
    expect(createBody.spec.stages[0].verifiers).toEqual(spec.stages[0]?.verifiers)

    await expect(api.confirmDraft('draft-1')).resolves.toEqual({ taskId: 'task-1' })
    await expect(api.getRuntime()).resolves.toMatchObject({
      loopperVersion: '0.1.53', status: 'OFFLINE', managed: false, endpoint: 'http://127.0.0.1:51234',
      startupFailure: 'Managed OpenCode exited with code 1 before it became healthy',
    })
  })

  it('loads and persists settings with a dynamic model catalog', async () => {
    const settings = { cliPath: 'opencode', allowedRoot: '', provider: 'deepseek', model: 'deepseek-chat', maxTaskAttempts: 12, timeoutMinutes: 30, autoApprove: false }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(settings))
      .mockResolvedValueOnce(json([{ id: 'deepseek/deepseek-chat', provider: 'deepseek', model: 'deepseek-chat', label: 'deepseek / deepseek-chat' }]))
      .mockResolvedValueOnce(json(settings))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.getSettings()).resolves.toMatchObject({ provider: 'deepseek', model: 'deepseek-chat' })
    await expect(api.getSettingsModels('opencode')).resolves.toMatchObject([{ id: 'deepseek/deepseek-chat' }])
    await api.updateSettings(settings)

    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/settings/models?cliPath=opencode')
    expect(fetchMock.mock.calls[2]?.[0]).toBe('/api/settings')
    expect(fetchMock.mock.calls[2]?.[1]).toMatchObject({ method: 'PUT' })
  })

  it('starts and checks OpenCode only through the explicit local UI endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({
      status: 'AVAILABLE', managed: true, pid: 6400, endpoint: 'http://127.0.0.1:34020',
      version: '1.18.16', checkedAt: '2026-08-12T06:30:00Z',
    }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.startRuntime()).resolves.toMatchObject({ status: 'ONLINE', managed: true, pid: 6400 })
    expect(fetchMock).toHaveBeenCalledWith('/api/runtime/opencode/start', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }),
    }))
  })

  it('restarts a managed OpenCode runtime only through the local UI endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({
      status: 'AVAILABLE', managed: true, pid: 6500, endpoint: 'http://127.0.0.1:35020',
      version: '1.18.16', checkedAt: '2026-08-12T06:31:00Z',
    }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.restartRuntime()).resolves.toMatchObject({ status: 'ONLINE', managed: true, pid: 6500 })
    expect(fetchMock).toHaveBeenCalledWith('/api/runtime/opencode/restart', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }),
    }))
  })

  it('round-trips snapshot-bound dirty-workspace decisions through local UI endpoints', async () => {
    const workspace = {
      branch: 'main', head: 'abc123', snapshotId: 'snapshot-1', clean: false,
      files: [{ path: 'README.md', originalPath: null, indexStatus: ' ', workTreeStatus: 'M', untracked: false }],
    }
    const readyTask = {
      id: 'task-1', projectId: 'project-1', projectName: 'Example', title: 'Task', goal: '',
      branch: 'loopper/Task', worktreePath: '/tmp/example', status: 'READY', waitingReasonCode: null,
      loopRetryAvailable: false, hasDesignHistory: true, archived: false, attemptCount: 0, maxAttempts: 3,
      createdAt: 'now', updatedAt: 'later', stages: [], attempts: [], errors: [], judges: [], artifacts: [],
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(workspace))
      .mockResolvedValueOnce(json({ task: readyTask, workspace: { ...workspace, clean: true, files: [] } }))
      .mockResolvedValueOnce(json({ ...readyTask, status: 'FAILED', branch: null, worktreePath: null }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.getDirtyWorkspace('task 1')).resolves.toMatchObject({ snapshotId: 'snapshot-1', files: [{ path: 'README.md' }] })
    await expect(api.resolveDirtyWorkspace('task 1', {
      snapshotId: 'snapshot-1', resolutions: [{ path: 'README.md', action: 'COMMIT' }], commitMessage: 'save work',
    })).resolves.toMatchObject({ task: { status: 'READY' }, workspace: { clean: true } })
    await expect(api.cancelDirtyWorkspace('task 1')).resolves.toMatchObject({ status: 'FAILED' })

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/tasks/task%201/workspace-dirty')
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({ method: 'POST', headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }) })
    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body))).toEqual({
      snapshotId: 'snapshot-1', resolutions: [{ path: 'README.md', action: 'COMMIT' }], commitMessage: 'save work',
    })
    expect(fetchMock.mock.calls[2]?.[1]).toMatchObject({ method: 'POST', headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }) })
  })

  it('round-trips structured verifier rules without converting them to PROCESS', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ id: 'draft-1', status: 'DRAFT_READY', updatedAt: 'now', spec })))

    const draft = await api.getDraft('draft-1')

    expect(draft.spec.stages[0]?.verifiers).toEqual([
      { type: 'PROCESS', command: ['./mvnw', 'test'], outputContains: 'BUILD SUCCESS' },
      { type: 'FILE_EXISTS', path: 'src/App.java' },
      { type: 'GIT_DIFF', requireChanges: true, allowedPaths: ['src/**'], forbiddenPaths: ['data/**'], forbidDeletes: true },
    ])
  })

  it('round-trips every retry policy field without replacing non-default values', async () => {
    const backendSpec = {
      ...spec,
      limits: { maxStageAttempts: 7, maxTaskAttempts: 19, sessionErrorLimit: 6, stagnationLimit: 4, maxDurationSeconds: 9100, attemptTimeoutSeconds: 2300, verifierTimeoutSeconds: 321 },
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ id: 'draft-1', status: 'DRAFT_READY', updatedAt: 'now', spec: backendSpec }))
      .mockResolvedValueOnce(json({ id: 'draft-1', status: 'DRAFT_READY', updatedAt: 'later', spec: backendSpec }))
    vi.stubGlobal('fetch', fetchMock)

    const draft = await api.getDraft('draft-1')
    await api.updateDraft('draft-1', draft.spec)

    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body)).spec).toMatchObject({
      limits: { maxStageAttempts: 7, maxTaskAttempts: 19, sessionErrorLimit: 6, stagnationLimit: 4, maxDurationSeconds: 9100, attemptTimeoutSeconds: 2300, verifierTimeoutSeconds: 321 },
      model: { providerId: 'provider-1', modelId: 'model-1', thinking: false },
      sessionPolicy: { reuseHealthySession: false, createFreshOnVerifierFailure: false },
      nextAttemptPromptTemplate: '修复 ${failureSummary}',
    })
  })

  it('round-trips aggregate Stage workPackageId values without flattening the Task', async () => {
    const aggregate: LoopSpec = {
      ...spec,
      stages: [
        { ...spec.stages[0]!, workPackageId: 'WP-1' },
        { ...spec.stages[0]!, objective: 'Integrate', workPackageId: 'WP-2' },
      ],
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ id: 'draft-packages', status: 'DRAFT_READY', updatedAt: 'now', spec: aggregate }))
      .mockResolvedValueOnce(json({ id: 'draft-packages', status: 'DRAFT_READY', updatedAt: 'later', spec: aggregate }))
    vi.stubGlobal('fetch', fetchMock)

    const draft = await api.getDraft('draft-packages')
    expect(draft.spec.stages.map((stage) => stage.workPackageId)).toEqual(['WP-1', 'WP-2'])

    await api.updateDraft(draft.id, draft.spec)

    const persisted = JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body)).spec
    expect(persisted.stages.map((stage: { workPackageId?: string }) => stage.workPackageId)).toEqual(['WP-1', 'WP-2'])
  })

  it('round-trips v2 acceptance coverage and managed-runtime fields and calls the shared validator', async () => {
    const v2: LoopSpec = {
      ...spec,
      schemaVersion: 'v2',
      stages: [{
        objective: 'API behavior', allowedPaths: [], forbiddenPaths: [], deliverables: ['running API'],
        acceptanceCriteria: [{ id: 'AC-1', description: 'health endpoint returns UP', verificationMode: 'BOTH', judgeRubric: 'review health semantics' }],
        verificationRuntime: {
          startCommand: ['java', '-jar', 'app.jar', '--server.port={{LOOPPER_PORT}}'],
          readiness: { path: '/health', expectedStatus: 200, jsonPath: '$.status', expectedValue: 'UP', matchMode: 'EXACT' },
          startupTimeoutSeconds: 30, shutdownTimeoutSeconds: 5,
        },
        verifiers: [{
          type: 'JSON_PATH', url: 'http://127.0.0.1:{{LOOPPER_PORT}}/health', httpMethod: 'GET',
          jsonPath: '$.status', expectedValue: 'UP', matchMode: 'EXACT', criterionIds: ['AC-1'],
        }],
      }],
    }
    const assessment = {
      valid: true, schemaVersion: 'v2', legacy: false, errors: [],
      stageAssessments: [{ stageIndex: 0, criteria: [{ id: 'AC-1', description: 'health endpoint returns UP', verificationMode: 'BOTH', covered: true, machineCovered: true, judgePlanned: true, overallPlanned: true, judgeRubric: 'review health semantics', verifierIndexes: [0] }], verifiers: [{ index: 0, type: 'JSON_PATH', category: 'BEHAVIOR', blocking: true, criterionIds: ['AC-1'], reason: 'managed runtime' }] }],
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ id: 'draft-v2', status: 'DRAFT_READY', updatedAt: 'now', spec: v2 }))
      .mockResolvedValueOnce(json({ id: 'draft-v2', status: 'DRAFT_READY', updatedAt: 'later', spec: v2 }))
      .mockResolvedValueOnce(json(assessment))
    vi.stubGlobal('fetch', fetchMock)

    const draft = await api.getDraft('draft-v2')
    await api.updateDraft('draft-v2', draft.spec)
    await expect(api.validateDraft(draft.spec)).resolves.toEqual(assessment)

    const persisted = JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body)).spec
    expect(persisted.stages[0]).toMatchObject({
      acceptanceCriteria: [{ id: 'AC-1', description: 'health endpoint returns UP', verificationMode: 'BOTH', judgeRubric: 'review health semantics' }],
      verificationRuntime: { startupTimeoutSeconds: 30, shutdownTimeoutSeconds: 5 },
      verifiers: [{ type: 'JSON_PATH', criterionIds: ['AC-1'], matchMode: 'EXACT' }],
    })
    expect(fetchMock.mock.calls[2]?.[0]).toBe('/api/loop-drafts/validate')
  })

  it('reads legacy PROCESS argv without writing the alias back', async () => {
    const legacy = structuredClone(spec) as unknown as { stages: Array<{ verifiers: unknown[] }> }
    legacy.stages[0]!.verifiers = [{ type: 'PROCESS', argv: ['mvn', 'test'] }]
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ id: 'draft-1', status: 'DRAFT_READY', updatedAt: 'now', spec: legacy }))
      .mockResolvedValueOnce(json({ id: 'draft-1', status: 'DRAFT_READY', updatedAt: 'now', spec }))
    vi.stubGlobal('fetch', fetchMock)

    const draft = await api.getDraft('draft-1')
    expect(draft.spec.stages[0]!.verifiers).toEqual([{ type: 'PROCESS', command: ['mvn', 'test'] }])
    await api.updateDraft('draft-1', draft.spec)
    const body = JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body))
    expect(body.spec.stages[0].verifiers[0]).toEqual({ type: 'PROCESS', command: ['mvn', 'test'] })
  })

  it('rejects unknown verifier discriminators and missing native admission fields', async () => {
    const invalid = structuredClone(spec) as unknown as { stages: Array<{ verifiers: unknown[] }> }
    invalid.stages[0]!.verifiers = [{ type: 'SHELL_MAGIC', command: ['unsafe'] }]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ id: 'draft-1', status: 'DRAFT_READY', updatedAt: 'now', spec: invalid })))
    await expect(api.getDraft('draft-1')).rejects.toThrow('Unsupported verifier type')

    invalid.stages[0]!.verifiers = [{ type: 'HTTP_STATUS', url: 'http://127.0.0.1:8080/health' }]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ id: 'draft-1', status: 'DRAFT_READY', updatedAt: 'now', spec: invalid })))
    await expect(api.getDraft('draft-1')).rejects.toThrow('expectedStatus is required')

    invalid.stages[0]!.verifiers = [{ type: 'PROCESS', command: [] }]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ id: 'draft-1', status: 'DRAFT_READY', updatedAt: 'now', spec: invalid })))
    await expect(api.getDraft('draft-1')).rejects.toThrow('PROCESS.command is required')

    invalid.stages[0]!.verifiers = [{ type: 'BROWSER', url: 'http://127.0.0.1:8080', assertions: [] }]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ id: 'draft-1', status: 'DRAFT_READY', updatedAt: 'now', spec: invalid })))
    await expect(api.getDraft('draft-1')).rejects.toThrow('requires at least one assertion')
  })

  it('round-trips the backend automation triggerType and triggerConfig wire shape', () => {
    const rule = normalizeAutomationRule({
      id: 'rule-1', name: 'Nightly', projectId: 'project-1', templateVersionId: 'version-1',
      triggerType: 'CRON', triggerConfig: { expression: '0 2 * * *', timezone: 'Asia/Shanghai' },
      state: 'DISABLED', approvalMode: 'REVIEW_REQUIRED', updatedAt: 'now', version: 2,
    })

    expect(rule).toMatchObject({ triggerType: 'CRON', triggerConfig: { expression: '0 2 * * *', timezone: 'Asia/Shanghai' } })
    expect(backendAutomationRule(rule as AutomationRule)).toEqual({
      id: 'rule-1', name: 'Nightly', projectId: 'project-1', templateVersionId: 'version-1',
      triggerType: 'CRON', triggerConfig: { expression: '0 2 * * *', timezone: 'Asia/Shanghai' },
      state: 'DISABLED', approvalMode: 'REVIEW_REQUIRED', updatedAt: 'now', version: 2,
    })
    expect(() => normalizeAutomationRule({ ...backendAutomationRule(rule), triggerType: 'EXEC' }))
      .toThrow('Unsupported automation trigger type')
  })

  it('creates an inert webhook rule and keeps the one-time token outside list payloads', async () => {
    const persistedRule = {
      id: 'rule-webhook', name: 'Local hook', projectId: 'project-1', templateVersionId: 'version-1',
      triggerType: 'WEBHOOK', triggerConfig: {}, state: 'DISABLED', approvalMode: 'REVIEW_REQUIRED',
      updatedAt: 'now', version: 0,
    }
    const fetchMock = vi.fn().mockResolvedValue(json({
      rule: persistedRule,
      webhookToken: 'one-time-secret',
      webhookPath: '/api/automations/webhooks/rule-webhook/{token}',
    }))
    vi.stubGlobal('fetch', fetchMock)

    const mutation = await api.createAutomationRule({
      name: 'Local hook', projectId: 'project-1', templateVersionId: 'version-1',
      triggerType: 'WEBHOOK', triggerConfig: {},
    })

    expect(mutation.rule).toMatchObject(persistedRule)
    expect(mutation.webhookToken).toBe('one-time-secret')
    const requestBody = JSON.parse(String((fetchMock.mock.calls[0]?.[1] as RequestInit).body)) as Record<string, unknown>
    expect(requestBody).toEqual({
      name: 'Local hook', projectId: 'project-1', templateVersionId: 'version-1',
      triggerType: 'WEBHOOK', triggerConfig: {},
    })
    expect(requestBody).not.toHaveProperty('state')
    expect(requestBody).not.toHaveProperty('approvalMode')
    expect(JSON.stringify(persistedRule)).not.toContain('one-time-secret')
  })

  it('uses the persisted Recovery wire contract and local UI mutation guard', async () => {
    const recovery = {
      taskId: 'child-1', parentTaskId: 'parent 1', mode: 'VERIFY_ONLY', parentStageId: 'stage-2',
      workspaceFingerprint: 'fingerprint-1', writableSession: false,
    }
    const fetchMock = vi.fn().mockResolvedValueOnce(json([recovery])).mockResolvedValueOnce(json(recovery))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.getTaskRecoveries('parent 1')).resolves.toEqual([recovery])
    await expect(api.createTaskRecovery('parent 1', 'VERIFY_ONLY')).resolves.toEqual(recovery)

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/tasks/parent%201/recoveries')
    expect(fetchMock.mock.calls[1]).toEqual(['/api/tasks/parent%201/recoveries', expect.objectContaining({
      method: 'POST', headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }),
      body: JSON.stringify({ mode: 'VERIFY_ONLY' }),
    })])
  })

  it('uses local execution Session ids for persisted lifecycle snapshots and guarded mutations', async () => {
    const todo = { id: 'todo-local', externalTodoId: 'todo-remote', content: '同步状态', status: 'OPEN', ordinal: 1, observedAt: 'now' }
    const checkpoint = { id: 'checkpoint-1', taskId: 'task-1', sessionId: 'session-1', attemptId: 'attempt-1', externalMessageId: 'message-1', contentSha256: 'a'.repeat(64), createdAt: 'now' }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([todo]))
      .mockResolvedValueOnce(json(checkpoint))
      .mockResolvedValueOnce(json({ sessionId: 'session-fork', attemptId: 'attempt-2', externalSessionId: 'remote-fork', state: 'COMPLETED', createdAt: 'later' }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.getTaskSessionTodos('task 1', 'session 1')).resolves.toEqual([todo])
    await expect(api.createTaskSessionCheckpoint('task 1', 'session 1', 'message-1')).resolves.toEqual(checkpoint)
    await expect(api.forkTaskSession('task 1', 'session 1', 'message-1')).resolves.toMatchObject({ state: 'COMPLETED' })

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/tasks/task%201/sessions/session%201/todos')
    expect(fetchMock.mock.calls[1]?.[1]).toEqual(expect.objectContaining({
      method: 'POST', headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }),
      body: JSON.stringify({ externalMessageId: 'message-1' }),
    }))
    expect(fetchMock.mock.calls[2]?.[1]).toEqual(expect.objectContaining({
      method: 'POST', headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }),
    }))
  })

  it('keeps unknown insight usage null and costs separated by currency', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      tasks: [], generatedAt: 'now',
      usage: { inputTokens: null, outputTokens: null, totalTokens: null, unknownUsageCount: 2, costByCurrency: { USD: '1.20', CNY: '8.00' } },
    })))

    await expect(api.getInsights()).resolves.toEqual({
      tasks: [], generatedAt: 'now',
      usage: { inputTokens: null, outputTokens: null, totalTokens: null, unknownUsageCount: 2, costByCurrency: { USD: '1.20', CNY: '8.00' } },
    })
  })

  it('uses persisted verification and error field names from TaskController', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      id: 'task-1', projectId: 'project-1', projectName: 'Project', title: 'Task', goal: 'Goal', branch: 'loopper/task-1', worktreePath: '/tmp/task',
      status: 'WAITING_INPUT', waitingReasonCode: 'LOOP_STAGNATION_DETECTED', loopRetryAvailable: true, hasDesignHistory: true, archived: true, attemptCount: 1, maxAttempts: 12, createdAt: 'start', updatedAt: 'now', stages: [],
      attempts: [{ id: 'attempt-1', stageId: 'stage-1', ordinal: 1, status: 'RUNNING', summary: 'running', startedAt: 'start', verifications: [{ id: 'verification-1', type: 'PROCESS', status: 'PASS', summary: 'ok', evidence: { argv: ['mvn', 'test'], exitCode: 0, output: 'BUILD SUCCESS' }, at: 'now' }] }],
      errors: [{ id: 'error-1', layer: 'SESSION', code: 'DISCONNECTED', message: 'reconnect', retryable: true, at: 'now' }],
      judges: [{ id: 'judge-1', role: 'RISK', ordinal: 1, status: 'COMPLETED', verdict: 'PASS', reason: 'No unsafe diff', createdAt: 'now', endedAt: 'later' }],
      artifacts: [{ id: 'artifact-1', kind: 'GIT_DIFF', name: 'worktree.diff', contentType: 'text/plain', content: 'diff', metadata: { available: true }, createdAt: 'now' }],
    })))

    const task = await api.getTask('task-1')
    expect(task.attempts?.[0]?.verifiers).toMatchObject([{ id: 'verification-1', name: 'PROCESS', status: 'PASS', output: 'BUILD SUCCESS', evidence: { argv: ['mvn', 'test'], exitCode: 0 } }])
    expect(task.errors?.[0]).toMatchObject({ layer: 'SESSION', occurredAt: 'now' })
    expect(task.judges?.[0]).toMatchObject({ role: 'RISK', verdict: 'PASS' })
    expect(task.artifacts?.[0]).toMatchObject({ kind: 'DIFF', title: 'worktree.diff', content: 'diff' })
    expect(task.hasDesignHistory).toBe(true)
    expect(task.archived).toBe(true)
    expect(task.waitingReasonCode).toBe('LOOP_STAGNATION_DETECTED')
    expect(task.loopRetryAvailable).toBe(true)
  })

  it('archives, restores and deletes task history only through the local UI contract', async () => {
    const response = { id: 'task-1', projectId: 'project-1', projectName: 'Project', title: 'Task', goal: 'Goal', branch: 'DIRECT', worktreePath: '/tmp/project', status: 'CANCELLED', archived: true, attemptCount: 1, maxAttempts: 3, createdAt: 'start', updatedAt: 'now' }
    const fetchMock = vi.fn().mockResolvedValueOnce(json(response)).mockResolvedValueOnce(json({ ...response, archived: false })).mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.archiveTask('task 1')).resolves.toMatchObject({ archived: true })
    await expect(api.restoreArchivedTask('task 1')).resolves.toMatchObject({ archived: false })
    await expect(api.deleteArchivedTask('task 1')).resolves.toBeUndefined()

    expect(fetchMock.mock.calls[0]).toEqual(['/api/tasks/task%201/archive', expect.objectContaining({ method: 'PUT', headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }) })])
    expect(fetchMock.mock.calls[1]).toEqual(['/api/tasks/task%201/archive', expect.objectContaining({ method: 'DELETE', headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }) })])
    expect(fetchMock.mock.calls[2]).toEqual(['/api/tasks/task%201', expect.objectContaining({ method: 'DELETE', headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }) })])
  })

  it('loads queue holder diagnostics and reconciles only through the local UI contract', async () => {
    const queue = {
      taskId: 'task 1', state: 'QUEUED', queuePosition: 1, leaseState: 'RELEASE_PENDING',
      holderTaskId: 'holder-1', holderTaskTitle: '旧任务', holderTaskState: 'CANCELLED', holderArchived: true,
      releaseReason: 'SOURCE_BRANCH_WORKSPACE_DIRTY', reconcileAvailable: true,
    }
    const fetchMock = vi.fn().mockResolvedValueOnce(json(queue)).mockResolvedValueOnce(json({
      ...queue, state: 'ADMITTED', queuePosition: null, leaseState: 'HELD', holderTaskId: 'task 1',
      holderTaskTitle: '等待任务', holderTaskState: 'QUEUED', holderArchived: false, releaseReason: 'MANUAL_QUEUE_RECONCILIATION', reconcileAvailable: false,
    }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.getTaskQueue('task 1')).resolves.toMatchObject({
      holderTaskId: 'holder-1', holderArchived: true, releaseReason: 'SOURCE_BRANCH_WORKSPACE_DIRTY', reconcileAvailable: true,
    })
    await expect(api.reconcileTaskQueue('task 1')).resolves.toMatchObject({ state: 'ADMITTED', reconcileAvailable: false })

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/tasks/task%201/queue')
    expect(fetchMock.mock.calls[1]).toEqual(['/api/tasks/task%201/queue/reconcile', expect.objectContaining({
      method: 'POST', headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }),
    })])
  })

  it('loads an encoded task file diff preview', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({
      path: 'src/new file.ts', changeType: 'NEW', patch: '--- /dev/null\n+++ b/src/new file.ts\n+const ready = true', truncated: false,
    }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.getTaskDiffPreview('task 1', 'src/new file.ts')).resolves.toMatchObject({
      path: 'src/new file.ts', changeType: 'NEW', truncated: false,
    })
    expect(fetchMock).toHaveBeenCalledWith('/api/tasks/task%201/diff-preview?path=src%2Fnew%20file.ts', expect.any(Object))
  })

  it('loads persisted task design history without opening a live Designer session', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({
      taskId: 'task-1', taskTitle: 'Durable history', projectName: 'Project',
      draft: { id: 'draft-1', status: 'CONFIRMED', updatedAt: 'now', spec },
      designerSession: {
        id: 'designer-1', state: 'COMPLETED', accessMode: 'READ_ONLY', createdAt: 'start', updatedAt: 'now',
        messages: [{ id: 'message-1', ordinal: 1, role: 'USER', content: 'Original requirement', deliveryState: 'PERSISTED', createdAt: 'start' }],
      },
    }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.getTaskDesignHistory('task 1')).resolves.toMatchObject({
      taskId: 'task-1', draft: { id: 'draft-1', status: 'CONFIRMED', spec: { goal: 'Verify the contract' } },
      designerSession: { id: 'designer-1', state: 'COMPLETED', messages: [{ role: 'USER', content: 'Original requirement' }] },
    })
    expect(fetchMock).toHaveBeenCalledWith('/api/tasks/task%201/design-history', expect.any(Object))
  })

  it('does not render a terminal Task attempt as still running', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      id: 'task-failed', projectId: 'project-1', title: 'Failed', status: 'FAILED', stages: [], errors: [],
      attempts: [{ id: 'attempt-failed', stageId: 'stage-1', ordinal: 1, status: 'TASK_ERROR', failureKind: 'PATH_ESCAPE', summary: 'stopped', startedAt: 'start', endedAt: 'end', verifications: [] }],
    })))

    const task = await api.getTask('task-failed')

    expect(task.attempts?.[0]?.status).toBe('TASK_ERROR')
  })

  it('preserves historical terminal Judge states instead of fabricating RUNNING', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      id: 'task-judge-history', projectId: 'project-1', title: 'Judge history', status: 'FAILED',
      stages: [], attempts: [], errors: [],
      judges: [
        { id: 'judge-failed', role: 'REQUIREMENT', ordinal: 1, status: 'FAILED', createdAt: 'start', endedAt: 'end' },
        { id: 'judge-timeout', role: 'RISK', ordinal: 1, status: 'TIMED_OUT', createdAt: 'start', endedAt: 'end' },
      ],
    })))

    const task = await api.getTask('task-judge-history')

    expect(task.judges?.map((judge) => judge.status)).toEqual(['FAILED', 'TIMED_OUT'])
  })

  it('starts a prepared Task through the explicit start endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({
      id: 'task-ready', projectId: 'project-1', title: 'Ready task', status: 'RUNNING', stages: [], errors: [], attempts: [],
    }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.startTask('task-ready')).resolves.toMatchObject({ id: 'task-ready', status: 'RUNNING' })
    expect(fetchMock).toHaveBeenCalledWith('/api/tasks/task-ready/start', expect.objectContaining({ method: 'POST' }))
  })

  it('retries task judges only through the local UI contract', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({
      id: 'task-review', projectId: 'project-1', title: 'Review task', status: 'JUDGING', stages: [], errors: [], attempts: [],
    }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.retryTaskJudges('task review')).resolves.toMatchObject({ id: 'task-review', status: 'JUDGING' })
    expect(fetchMock).toHaveBeenCalledWith('/api/tasks/task%20review/judges/retry', expect.objectContaining({
      method: 'POST', headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }),
    }))
  })

  it('retries a noise-protected loop only through the local UI contract', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({
      id: 'task-loop', projectId: 'project-1', title: 'Loop task', status: 'RUNNING', stages: [], errors: [], attempts: [],
    }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.retryWaitingTaskLoop('task loop')).resolves.toMatchObject({ id: 'task-loop', status: 'RUNNING' })
    expect(fetchMock).toHaveBeenCalledWith('/api/tasks/task%20loop/loop/retry', expect.objectContaining({
      method: 'POST', headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }),
    }))
  })

  it('normalizes live Task Session thinking and output parts', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([{ key: 'execution:local-1', kind: 'IMPLEMENTATION', label: 'Implementation Session', localSessionId: 'local-1', externalSessionId: 'remote-1', state: 'RUNNING', stageId: 'stage-1', stageOrdinal: 1, stageObjective: '实现阶段目标', createdAt: 'now' }]))
      .mockResolvedValueOnce(json({
        session: { key: 'execution:local-1', kind: 'IMPLEMENTATION', label: 'Implementation Session', localSessionId: 'local-1', externalSessionId: 'remote-1', state: 'RUNNING', createdAt: 'now' },
        remoteState: 'busy', live: true, observedAt: 'later',
        parts: [{ id: 'reason-1', type: 'THINKING', label: 'Thinking', content: 'Inspecting files', status: 'running', startedAt: '2026-08-04T08:00:01Z' }, { id: 'text-1', type: 'OUTPUT', label: '模型输出', content: 'Implementing now' }],
      }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.getTaskSessions('task-1')).resolves.toMatchObject([{
      key: 'execution:local-1', state: 'RUNNING', stageOrdinal: 1, stageObjective: '实现阶段目标',
    }])
    await expect(api.getTaskSessionActivity('task-1', 'execution:local-1')).resolves.toMatchObject({
      live: true, remoteState: 'busy', parts: [{ type: 'THINKING', content: 'Inspecting files', startedAt: '2026-08-04T08:00:01Z' }, { type: 'OUTPUT', content: 'Implementing now' }],
    })
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/tasks/task-1/sessions/execution%3Alocal-1')
  })

  it('preserves paused Stage state and exposed execution Session id', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      id: 'task-paused', projectId: 'project-1', title: 'Paused', status: 'PAUSED', errors: [],
      stages: [{ id: 'stage-1', ordinal: 0, objective: 'Wait', status: 'PAUSED' }],
      attempts: [{ id: 'attempt-1', stageId: 'stage-1', ordinal: 1, sessionId: 'session-1', status: 'SESSION_ERROR', verifications: [] }],
    })))

    const task = await api.getTask('task-paused')

    expect(task.stages?.[0]?.status).toBe('PAUSED')
    expect(task.attempts?.[0]?.sessionId).toBe('session-1')
  })

  it('preserves real Designer lifecycle and session-error delivery states', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({
        id: 'designer-1', projectId: 'project-1', state: 'RUNNING', accessMode: 'READ_ONLY', readOnly: true,
        updatedAt: 'now', messages: [{ id: 'user-1', role: 'USER', content: 'plan', deliveryState: 'PERSISTED', createdAt: 'now' }],
        pendingQuestions: [{ id: 'question-1', questions: [{ question: 'Which scope?', header: 'Scope', options: [{ label: 'New chain', description: 'Add it' }], multiple: false, custom: false }] }],
      }))
      .mockResolvedValueOnce(json({
        sessionId: 'designer-1', state: 'SESSION_ERROR', notice: 'retry with a fresh session',
        persistedMessages: [{ id: 'error-1', role: 'SYSTEM', content: 'transport failed', deliveryState: 'SESSION_ERROR', createdAt: 'later' }],
      }, 202))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.getDesignerSession('designer-1')).resolves.toMatchObject({
      state: 'RUNNING', updatedAt: 'now', pendingQuestions: [{ id: 'question-1', questions: [{ custom: false }] }],
    })
    await expect(api.sendDesignerMessage('designer-1', 'continue')).resolves.toMatchObject({
      state: 'SESSION_ERROR', persistedMessages: [{ deliveryState: 'SESSION_ERROR' }],
    })
    await expect(api.replyDesignerQuestion('designer-1', 'question-1', [['New chain']])).resolves.toBeUndefined()
    await expect(api.rejectDesignerQuestion('designer-1', 'question-2')).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/designer-sessions/designer-1/questions/question-1/reply', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ answers: [['New chain']] }),
    }))
    expect(fetchMock).toHaveBeenNthCalledWith(4, '/api/designer-sessions/designer-1/questions/question-2/reject', expect.objectContaining({ method: 'POST' }))
  })

  it('lists persistent unconfirmed Designer sessions by project', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json([{
      id: 'designer-1', projectId: 'project one', state: 'WAITING_INPUT', workflowPhase: 'FAILED',
      updatedAt: 'now', draftId: 'draft-1', draftStatus: 'DRAFT_READY', goal: 'Resume me',
      requirementRevision: 2, activeWorkPackageId: 'WP-2',
    }]))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.listOpenDesignerSessions('project one')).resolves.toEqual([expect.objectContaining({
      id: 'designer-1', state: 'WAITING_INPUT', workflowPhase: 'FAILED', goal: 'Resume me', activeWorkPackageId: 'WP-2',
    })])
    expect(fetchMock).toHaveBeenCalledWith('/api/designer-sessions?projectId=project%20one', expect.any(Object))
  })

  it('lists and archives Designer history through local-UI guarded endpoints', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([{
        id: 'designer-1', projectId: 'project one', projectName: 'Example', state: 'WAITING_INPUT', workflowPhase: 'FAILED',
        createdAt: 'created', updatedAt: 'updated', draftId: 'draft-1', draftStatus: 'DRAFT_READY', goal: 'Resume me',
        requirementRevision: 2, activeWorkPackageId: 'WP-2', archived: false,
      }]))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.listDesignerHistory('project one')).resolves.toEqual([expect.objectContaining({
      id: 'designer-1', projectName: 'Example', archived: false, createdAt: 'created',
    })])
    await expect(api.archiveDesignerSession('designer-1')).resolves.toBeUndefined()
    await expect(api.restoreDesignerSession('designer-1')).resolves.toBeUndefined()

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/designer-sessions/history?projectId=project%20one', expect.any(Object))
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/designer-sessions/designer-1/archive', expect.objectContaining({
      method: 'PUT', headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }),
    }))
    expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/designer-sessions/designer-1/archive', expect.objectContaining({
      method: 'DELETE', headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }),
    }))
  })

  it('uses explicit optimistic-revision endpoints for requirement and work-package discussion', async () => {
    const accepted = { sessionId: 'designer-1', state: 'RUNNING', persistedMessages: [], notice: 'accepted' }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(accepted, 202))
      .mockResolvedValueOnce(new Response(null, { status: 202 }))
      .mockResolvedValueOnce(new Response(null, { status: 202 }))
      .mockResolvedValueOnce(json(accepted, 202))
      .mockResolvedValueOnce(new Response(null, { status: 202 }))
      .mockResolvedValueOnce(json({ invalidatedPackageIds: ['WP-3'] }))
    vi.stubGlobal('fetch', fetchMock)

    await api.sendRequirementMessage('designer-1', '补充整体边界', 2)
    await expect(api.confirmDesignerRequirement('designer-1', 3)).resolves.toBeUndefined()
    await expect(api.reopenDesignerRequirement('designer-1', 4)).resolves.toBeUndefined()
    await api.sendWorkPackageMessage('designer-1', 'WP-2', '只修改这个包', 5, 7)
    await expect(api.approveWorkPackage('designer-1', 'WP-2', 6, 8)).resolves.toBeUndefined()
    await expect(api.reopenWorkPackage('designer-1', 'WP-2', 7, 8)).resolves.toEqual(['WP-3'])

    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      '/api/designer-sessions/designer-1/requirement/messages',
      '/api/designer-sessions/designer-1/requirement/confirm',
      '/api/designer-sessions/designer-1/requirement/reopen',
      '/api/designer-sessions/designer-1/work-packages/WP-2/messages',
      '/api/designer-sessions/designer-1/work-packages/WP-2/approve',
      '/api/designer-sessions/designer-1/work-packages/WP-2/reopen',
    ])
    expect(fetchMock.mock.calls[3]?.[1]).toEqual(expect.objectContaining({
      method: 'POST', body: JSON.stringify({ content: '只修改这个包', expectedDiscussionRevision: 5, expectedDesignRevision: 7 }),
    }))
  })
})
