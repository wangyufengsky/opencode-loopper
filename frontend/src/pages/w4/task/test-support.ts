import { api } from '@/api/client'
import { vi } from 'vitest'
import type { Task, TaskSessionActivity, TaskSessionSummary } from '@/types/domain'
export { deferred, flush, pageProps, pageFrame, foundationDOM } from '@/pages/w2/workflow/page.test-support'
export function taskFixture(id = 'A', changes: Partial<Task> = {}): Task { return { id, projectId: 'p', projectName: '项目', title: `任务${id}`, goal: '完成冻结目标', branch: 'task/A', worktreePath: '/tmp/task/A', status: 'PENDING_START', version: 3, attemptCount: 0, maxAttempts: 3, createdAt: '2026-10-03T10:00:00Z', updatedAt: '2026-10-03T10:00:00Z', cancellationAvailable: true, loopRetryAvailable: false, hasDesignHistory: true, archived: false, errors: [], judges: [], artifacts: [], attempts: [], stages: [], ...changes } }
export function sessionFixture(key = 'execution:local-1'): TaskSessionSummary { return { key, kind: 'IMPLEMENTATION', label: 'implementation', localSessionId: key.split(':')[1]!, state: 'RUNNING', createdAt: '2026-10-03T10:00:00Z', stageOrdinal: 1 } }
export function activityFixture(key = 'execution:local-1', changes: Partial<TaskSessionActivity> = {}): TaskSessionActivity { return { session: sessionFixture(key), remoteState: 'busy', live: true, observedAt: '2026-10-03T10:00:00Z', parts: [], pendingQuestions: [], todoCapability: 'AVAILABLE', todos: [], todoTruncated: false, usage: { totalTokens: 100, unknownUsageCount: 0, observedAt: '2026-10-03T10:00:00Z' }, ...changes } }
export function mockReads(task = taskFixture()) {
  vi.spyOn(api, 'getTaskOverview').mockResolvedValue(task)
  vi.spyOn(api, 'getTaskAudit').mockResolvedValue({ attempts: [], artifacts: [], errors: [], judges: [] })
  vi.spyOn(api, 'getTaskSessions').mockResolvedValue([sessionFixture()]); vi.spyOn(api, 'getTaskSessionActivity').mockResolvedValue(activityFixture())
}
