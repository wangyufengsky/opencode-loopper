import type { Page, Route } from '@playwright/test'
import { productFixture, project } from '../w2/productFixture'
import type { Task, Interaction, TaskDesignHistory } from '../../src/types/domain'
const at = '2026-10-03T10:00:00Z'
export const taskDto = (id = 'w4-task', patch: Partial<Task> = {}): Task => ({ id, projectId: project.id, projectName: project.name, title: `${id} · 核算任务 · 模拟`, goal: '依据冻结合同核对报表，保留会话、验证和恢复证据。', branch: 'loopper/mock-task', worktreePath: '/fixture/task', status: 'RUNNING', version: 3, executionMode: 'WORKTREE', cancellationAvailable: true, loopRetryAvailable: false, hasDesignHistory: true, archived: false, attemptCount: 1, maxAttempts: 3, createdAt: at, updatedAt: at, errors: [], judges: [], attempts: [], artifacts: [], stages: [{ id: 'stage-1', ordinal: 1, objective: '核对报表与接口', status: 'RUNNING', attempts: [], attemptCount: 1 }], ...patch })
export const sessionDto = { key: 'execution:session', kind: 'IMPLEMENTATION', label: '核算执行会话 · 模拟', localSessionId: 'session', state: 'RUNNING', createdAt: at, stageOrdinal: 1 }
export const question = { id: 'question', questions: [{ header: '处理方式', question: '是否保留核对说明？', options: [{ label: '保留（推荐）', description: '随结果记录依据' }, { label: '稍后处理', description: '保留原问题' }], multiple: false, custom: true }] }
export const activityDto = (pending = false) => ({ session: sessionDto, remoteState: 'busy', live: true, observedAt: at, parts: [{ id: 'output', type: 'OUTPUT', label: '核算执行输出 · 模拟', content: '## 核对进度\n已读取冻结输入，正在核对接口。', status: 'COMPLETED', startedAt: at }], pendingQuestions: pending ? [question] : [], usage: { totalTokens: 105, unknownUsageCount: 0, observedAt: at }, todoCapability: 'AVAILABLE', todoTruncated: false, todos: [{ id: 'todo', content: '核对接口验收', status: 'IN_PROGRESS', priority: 'HIGH', ordinal: 1 }] })
export const permission: Interaction = { id: 'permission', kind: 'PERMISSION', state: 'PENDING', taskId: 'w4-task', sessionId: 'session', externalRequestId: 'external', version: 4, createdAt: at, updatedAt: at, payload: { permission: 'bash', patterns: ['git status'], metadata: {}, title: '读取仓库状态 · 模拟', hardDenied: false } }
export const inboxQuestion: Interaction = { id: 'question', kind: 'QUESTION', state: 'PENDING', taskId: 'w4-task', sessionId: 'session', externalRequestId: 'external-question', version: 3, createdAt: at, updatedAt: at, payload: { questions: question.questions } }
export const historyDto = (id = 'w4-task'): TaskDesignHistory => ({ taskId: id, taskTitle: `冻结设计 · ${id} · 模拟`, projectName: project.name, draft: { id: `draft-${id}`, status: 'CONFIRMED', updatedAt: at, spec: { schemaVersion: 'v1', projectId: project.id, goal: `冻结合同 ${id}`, context: '只读查看已确认输入，原内容不会追随新设计。', stages: [{ objective: '核对报表与接口', allowedPaths: ['frontend/src/**'], forbiddenPaths: ['data/**'], deliverables: ['核对说明'], verifiers: [{ type: 'PROCESS', command: ['npm', 'test'] }] }], limits: { maxStageAttempts: 3, maxTaskAttempts: 12, maxDuration: 'PT2H', attemptTimeout: 'PT30M' } } }, frozenAttachments: [{ id: 'same-file', filename: '冻结核对说明.md', mediaType: 'text/markdown', sizeBytes: 105, sha256: 'a'.repeat(64), scopeKey: 'REQUIREMENT', frozenAt: at }], requirement: { revision: 2, state: 'COMPLETED', requirementText: '保留原冻结输入与明确恢复入口。', modelCallsUsed: 7, maxModelCalls: 24 }, designerSession: { id: 'designer', state: 'COMPLETED', accessMode: 'READ_ONLY', createdAt: at, updatedAt: at, messages: [{ id: 'user', role: 'USER', actor: 'USER', content: '请保留核对与恢复说明。', deliveryState: 'PERSISTED', createdAt: at }, { id: 'assistant', role: 'ASSISTANT', actor: 'DESIGNER', content: '## 冻结设计\n保持原合同与任务证据。', deliveryState: 'PERSISTED', createdAt: at }], answeredQuestions: [] } })
export const publicationDto = { state: 'READY', available: true, branch: 'loopper/mock-task', remoteName: 'origin', remoteUrl: 'https://example.test/mock/repo.git', targetBranch: 'main', targetBranches: ['main', 'develop'], provider: 'GITLAB', hasChanges: true, conflictCount: 0, resolvedCount: 0, deliveryState: 'NOT_STARTED', deliveryFinal: false, reconciliationAvailable: true }
export async function w4ProductFixture(page: Page, options: { native?: boolean; task?: Partial<Task>; questions?: boolean; write?: (route: Route) => Promise<void>; read?: (route: Route) => Promise<boolean> } = {}) {
  const base = await productFixture(page, undefined, { nativeTaskStreams: options.native })
  const requests: { method: string; path: string; body: string | null; cursor: string | null }[] = [], unexpected: string[] = []
  let interactions: Interaction[] = [permission, inboxQuestion], pendingQuestion = !!options.questions
  const states = new Map<string, Task>()
  const state = (id: string) => { if (!states.has(id)) states.set(id, taskDto(id, options.task)); return states.get(id)! }
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname, method = route.request().method()
    if (options.native && /^\/api\/tasks\/[^/]+\/events$/.test(path)) return route.continue()
    const match = /^\/api\/tasks\/([^/]+)(.*)$/.exec(path)
    if (!match && path !== '/api/interactions' && !path.startsWith('/api/interactions/')) return route.fallback()
    requests.push({ method, path, body: route.request().postData(), cursor: route.request().headers()['last-event-id'] ?? null })
    if (method !== 'GET') { if (options.write) return options.write(route); unexpected.push(`${method} ${path}`); return route.fulfill({ status: 501, json: { message: '此场景禁止隐式写入' } }) }
    if (options.read && await options.read(route)) return
    if (path === '/api/interactions') return route.fulfill({ json: interactions })
    if (match) {
      const id = decodeURIComponent(match[1]!), tail = match[2]!, dto = state(id)
      const values: Record<string, unknown> = {
        '': dto, '/overview': dto, '/audit': { attempts: dto.attempts, artifacts: dto.artifacts, errors: dto.errors, judges: dto.judges },
        '/sessions': [sessionDto], [`/sessions/${encodeURIComponent(sessionDto.key)}`]: activityDto(pendingQuestion),
        '/judge-approval': { available: false, approved: false, taskVersion: dto.version, cycleId: 'cycle', cycleVersion: 0, reviewBatchId: 'batch' }, '/decision': { taskId: id, taskState: dto.status, taskVersion: dto.version, stages: [], availableActions: [] }, '/git-diff-scope-approval': null, '/workspace-dirty': { branch: 'main', head: 'head', snapshotId: 'snapshot', clean: true, files: [] }, '/design-history': historyDto(id), '/recoveries': [], '/publication': publicationDto,
        '/queue': { taskId: id, state: 'QUEUED', leaseState: 'RELEASE_PENDING', queuePosition: 1, releaseReason: 'SESSION_WRITER_UNCONFIRMED', reconcileAvailable: true },
      }
      if (Object.hasOwn(values, tail)) return route.fulfill({ json: values[tail] })
      if (/^\/design-attachments\/[^/]+\/preview$/.test(tail)) return route.fulfill({ json: { text: `${id} 原冻结正文 · 模拟` } })
    }
    unexpected.push(`${method} ${path}`); return route.fulfill({ status: 500, json: { message: '缺少准确 Task 运输夹具' } })
  })
  return { base, requests, unexpected, state, setTask: (id: string, patch: Partial<Task>) => states.set(id, { ...state(id), ...patch }), setInteractions: (value: Interaction[]) => { interactions = value }, setQuestions: (value: boolean) => { pendingQuestion = value } }
}
