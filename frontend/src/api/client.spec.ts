import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, subscribeDesignerEvents } from '@/api/client'
import type { LoopSpec } from '@/types/domain'

const spec: LoopSpec = {
  schemaVersion: 'v1', projectId: 'project-1', goal: 'Verify the contract', context: '',
  stages: [{ objective: 'Implement', allowedPaths: ['src/**'], forbiddenPaths: [], deliverables: ['source'], verifiers: [
    { type: 'PROCESS', command: ['./mvnw', 'test'], outputContains: 'BUILD SUCCESS' },
    { type: 'FILE_EXISTS', path: 'src/App.java' },
    { type: 'GIT_DIFF', requireChanges: true, allowedPaths: ['src/**'], forbiddenPaths: ['data/**'], forbidDeletes: true },
  ] }],
  limits: { maxStageAttempts: 3, maxTaskAttempts: 12, maxDuration: 'PT2H', attemptTimeout: 'PT30M' },
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

  it('wraps LoopSpec, reads taskId, and maps AVAILABLE to ONLINE', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ id: 'draft-1', status: 'DRAFT_READY', updatedAt: 'now', spec }, 201))
      .mockResolvedValueOnce(json({ taskId: 'task-1' }))
      .mockResolvedValueOnce(json({ status: 'AVAILABLE', managed: true, endpoint: 'http://127.0.0.1:4096', checkedAt: 'now' }))
    vi.stubGlobal('fetch', fetchMock)

    await api.createDraft(spec)
    const createBody = JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))
    expect(createBody).toEqual({ spec: expect.objectContaining({ projectId: 'project-1' }) })
    expect(createBody.spec.stages[0].verifiers).toEqual(spec.stages[0]?.verifiers)

    await expect(api.confirmDraft('draft-1')).resolves.toEqual({ taskId: 'task-1' })
    await expect(api.getRuntime()).resolves.toMatchObject({ status: 'ONLINE', managed: true })
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

  it('round-trips structured verifier rules without converting them to PROCESS', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ id: 'draft-1', status: 'DRAFT_READY', updatedAt: 'now', spec })))

    const draft = await api.getDraft('draft-1')

    expect(draft.spec.stages[0]?.verifiers).toEqual([
      { type: 'PROCESS', command: ['./mvnw', 'test'], outputContains: 'BUILD SUCCESS' },
      { type: 'FILE_EXISTS', path: 'src/App.java' },
      { type: 'GIT_DIFF', requireChanges: true, allowedPaths: ['src/**'], forbiddenPaths: ['data/**'], forbidDeletes: true },
    ])
  })

  it('uses persisted verification and error field names from TaskController', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      id: 'task-1', projectId: 'project-1', projectName: 'Project', title: 'Task', goal: 'Goal', branch: 'loopper/task-1', worktreePath: '/tmp/task',
      status: 'RUNNING', hasDesignHistory: true, attemptCount: 1, maxAttempts: 12, createdAt: 'start', updatedAt: 'now', stages: [],
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

  it('starts a prepared Task through the explicit start endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({
      id: 'task-ready', projectId: 'project-1', title: 'Ready task', status: 'RUNNING', stages: [], errors: [], attempts: [],
    }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.startTask('task-ready')).resolves.toMatchObject({ id: 'task-ready', status: 'RUNNING' })
    expect(fetchMock).toHaveBeenCalledWith('/api/tasks/task-ready/start', expect.objectContaining({ method: 'POST' }))
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
})
