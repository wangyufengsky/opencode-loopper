import type { Page } from '@playwright/test'
import { canvasReviewFixture } from './canvasReview'

export type ReadonlyKind = 'stages' | 'template-progress' | 'roles'
export type CanvasStreams = { opened: string[]; closed: string[] }
declare global { interface Window { __allCanvasStreams: CanvasStreams } }

export async function holdCanvasStreams(page: Page) {
  await page.addInitScript(() => {
    const streams: CanvasStreams = { opened: [], closed: [] }; window.__allCanvasStreams = streams
    class FixtureStream extends EventTarget {
      onopen: ((event: Event) => void) | null = null
      onmessage: ((event: MessageEvent) => void) | null = null
      onerror: ((event: Event) => void) | null = null
      readyState = 1
      constructor(readonly url: string) { super(); streams.opened.push(url); queueMicrotask(() => this.onopen?.(new Event('open'))) }
      close() { this.readyState = 2; streams.closed.push(this.url) }
    }
    window.EventSource = FixtureStream as unknown as typeof EventSource
  })
}

/** Dedicated simulated route DTOs; reuse the shared fixture for unrelated API reads. */
export async function allCanvasReviewFixture(page: Page, kind: ReadonlyKind) {
  const fixture = await canvasReviewFixture(page)
  const id = `cleanup-${kind}`
  const task = { id, projectId: 'project', projectName: '清理门禁 · 模拟数据', title: `长序列清理 ${kind} · 模拟数据`, goal: '核对原订阅与视图手势',
    status: 'RUNNING', version: 4, attemptCount: 20, maxAttempts: 30, hasDesignHistory: false, archived: false,
    executionMode: kind === 'template-progress' ? 'TEMPLATE_REPORT' : 'WORKTREE', branch: 'main', worktreePath: '/tmp/mock-project',
    loopRetryAvailable: false, cancellationAvailable: true, createdAt: '2026-10-03T00:00:00Z', updatedAt: '2026-10-03T00:00:00Z',
    stages: Array.from({ length: 20 }, (_, index) => ({ id: `stage-${index + 1}`, ordinal: index,
      objective: `核对阶段${index + 1}的完整目标与验收证据。`, status: index === 19 ? 'RUNNING' : 'SUCCEEDED', attemptCount: 1, attempts: [] })),
    attempts: [], errors: [], judges: [], artifacts: [], workPackages: [],
    ...(kind === 'template-progress' ? { templateProgress: { reviewBatches: 20, contributorBatches: 0, completedReviews: 19,
      completedContributors: 0, activeBatches: 1, failedBatches: 0, repairRound: 0, reportCount: 0, documentPath: '/tmp/mock-report',
      steps: Array.from({ length: 20 }, (_, index) => ({ key: `step-${index + 1}`, label: `核对步骤${index + 1}的证据`, state: index === 19 ? 'ACTIVE' : 'COMPLETE' })) } } : {}),
  }
  const slot = 'PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY'
  const role = { roleId: 'builtin.package-designer', displayName: '工作包设计师', description: '整理工作包设计', origin: 'BUILTIN',
    latestRevisionId: 'revision-2', latestRevisionNumber: 2, activeSlots: [slot], groupKey: 'package-designer', groupLabel: '工作包设计师' }
  await holdCanvasStreams(page)
  await page.route(/^http:\/\/127\.0\.0\.1:\d+\/api\//, async route => {
    if (route.request().method() !== 'GET') return route.fallback()
    const path = new URL(route.request().url()).pathname
    if (path === `/api/tasks/${id}/overview` || path === `/api/tasks/${id}`) return route.fulfill({ json: task })
    if (path === '/api/tasks/summaries') return route.fulfill({ json: { items: [task], facets: {}, nextCursor: null } })
    if (path.endsWith('/audit')) return route.fulfill({ json: { attempts: [], errors: [], judges: [], artifacts: [] } })
    if (path.endsWith('/template-progress')) return route.fulfill({ json: task.templateProgress ?? null })
    if (path.endsWith('/failed-batches')) return route.fulfill({ json: { items: [], facets: {}, nextCursor: null } })
    if (path.endsWith('/sessions/current')) return route.fulfill({ status: 204 })
    if (path === '/api/roles') return route.fulfill({ json: { items: [role], nextCursor: null } })
    if (path === `/api/roles/${role.roleId}`) return route.fulfill({ json: role })
    if (path === '/api/role-bindings') return route.fulfill({ json: [{ slot, profile: slot, activeRoleId: role.roleId,
      activeRevisionId: role.latestRevisionId, bindingVersion: 3, label: '工作包设计 · 候选', purpose: '制作工作包设计候选' }] })
    if (path.endsWith('/revisions/revision-2')) return route.fulfill({ json: {
      roleId: role.roleId, revisionId: 'revision-2', revisionNumber: 2, contentSha256: 'a'.repeat(64), publishedAt: task.createdAt,
      manifest: { roleId: role.roleId, allowedSlots: [slot], runtimePolicy: 'WORKFLOW_ADAPTER' },
      promptFragments: { 'machine-role.package-designer': '请完成当前工作包设计。' }, promptVariables: [], permissionMode: 'BASELINE',
      modelPolicy: 'INHERIT_WORKFLOW', nativeTools: ['read'], mcpTools: [], requiredMcpTools: [],
    } })
    return route.fallback()
  })
  return { ...fixture, id, task }
}
