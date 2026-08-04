import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { LoopSpec } from '@/types/domain'

const spec: LoopSpec = {
  schemaVersion: 'v1', projectId: 'project-1', goal: 'Verify the contract', context: '',
  stages: [{ objective: 'Implement', allowedPaths: ['src/**'], forbiddenPaths: [], deliverables: ['source'], verifiers: [
    { type: 'FILE_EXISTS', path: 'src/App.java' },
    { type: 'GIT_DIFF', requireChanges: true, allowedPaths: ['src/**'], forbiddenPaths: ['data/**'], forbidDeletes: true },
  ] }],
  limits: { maxStageAttempts: 3, maxTaskAttempts: 12, maxDuration: 'PT2H', attemptTimeout: 'PT30M' },
}

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

afterEach(() => vi.unstubAllGlobals())

describe('Loopper REST contract adapter', () => {
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

  it('round-trips structured verifier rules without converting them to PROCESS', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ id: 'draft-1', status: 'DRAFT_READY', updatedAt: 'now', spec })))

    const draft = await api.getDraft('draft-1')

    expect(draft.spec.stages[0]?.verifiers).toEqual([
      { type: 'FILE_EXISTS', path: 'src/App.java' },
      { type: 'GIT_DIFF', requireChanges: true, allowedPaths: ['src/**'], forbiddenPaths: ['data/**'], forbidDeletes: true },
    ])
  })

  it('uses persisted verification and error field names from TaskController', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      id: 'task-1', projectId: 'project-1', projectName: 'Project', title: 'Task', goal: 'Goal', branch: 'loopper/task-1', worktreePath: '/tmp/task',
      status: 'RUNNING', attemptCount: 1, maxAttempts: 12, createdAt: 'start', updatedAt: 'now', stages: [],
      attempts: [{ id: 'attempt-1', stageId: 'stage-1', ordinal: 1, status: 'RUNNING', summary: 'running', startedAt: 'start', verifications: [{ id: 'verification-1', type: 'PROCESS', status: 'PASS', summary: 'ok', evidence: { exitCode: 0 }, at: 'now' }] }],
      errors: [{ id: 'error-1', layer: 'SESSION', code: 'DISCONNECTED', message: 'reconnect', retryable: true, at: 'now' }],
      judges: [{ id: 'judge-1', role: 'RISK', ordinal: 1, status: 'COMPLETED', verdict: 'PASS', reason: 'No unsafe diff', createdAt: 'now', endedAt: 'later' }],
      artifacts: [{ id: 'artifact-1', kind: 'GIT_DIFF', name: 'worktree.diff', contentType: 'text/plain', content: 'diff', metadata: { available: true }, createdAt: 'now' }],
    })))

    const task = await api.getTask('task-1')
    expect(task.attempts?.[0]?.verifiers).toMatchObject([{ id: 'verification-1', name: 'PROCESS', status: 'PASS' }])
    expect(task.errors?.[0]).toMatchObject({ layer: 'SESSION', occurredAt: 'now' })
    expect(task.judges?.[0]).toMatchObject({ role: 'RISK', verdict: 'PASS' })
    expect(task.artifacts?.[0]).toMatchObject({ kind: 'DIFF', title: 'worktree.diff', content: 'diff' })
  })

  it('does not render a terminal Task attempt as still running', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      id: 'task-failed', projectId: 'project-1', title: 'Failed', status: 'FAILED', stages: [], errors: [],
      attempts: [{ id: 'attempt-failed', stageId: 'stage-1', ordinal: 1, status: 'TASK_ERROR', failureKind: 'PATH_ESCAPE', summary: 'stopped', startedAt: 'start', endedAt: 'end', verifications: [] }],
    })))

    const task = await api.getTask('task-failed')

    expect(task.attempts?.[0]?.status).toBe('TASK_ERROR')
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
      }))
      .mockResolvedValueOnce(json({
        sessionId: 'designer-1', state: 'SESSION_ERROR', notice: 'retry with a fresh session',
        persistedMessages: [{ id: 'error-1', role: 'SYSTEM', content: 'transport failed', deliveryState: 'SESSION_ERROR', createdAt: 'later' }],
      }, 202))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.getDesignerSession('designer-1')).resolves.toMatchObject({ state: 'RUNNING', updatedAt: 'now' })
    await expect(api.sendDesignerMessage('designer-1', 'continue')).resolves.toMatchObject({
      state: 'SESSION_ERROR', persistedMessages: [{ deliveryState: 'SESSION_ERROR' }],
    })
  })
})
