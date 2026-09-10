import { expect, test } from '@playwright/test'

test('启动基线失败显示可执行原因且不伪造执行会话', async ({ page }) => {
  const error = {
    id: 'baseline-error', layer: 'TASK', code: 'STAGE_WORKSPACE_BASELINE_CREATE_FAILED',
    message: 'Unable to index the Stage workspace baseline: Permission denied',
    createdAt: '2026-09-10T01:49:31Z',
  }
  const task = {
    id: 'baseline-fixture', projectId: 'project', projectName: '隔离验收', title: '故事绑定任务',
    goal: '检查任务启动失败原因', status: 'AWAITING_DECISION', executionResult: 'FAILED',
    attemptCount: 0, maxAttempts: 3, loopRetryAvailable: false, cancellationAvailable: false,
    hasDesignHistory: false, archived: false, stages: [], attempts: [], errors: [error],
    createdAt: error.createdAt, updatedAt: error.createdAt,
  }
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/overview')) return route.fulfill({ json: task })
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: '' })
    return route.fulfill({ json: { attempts: [], errors: [error], judges: [], artifacts: [], items: [] } })
  })
  await page.goto('/tasks/baseline-fixture')
  await expect(page.getByText('任务已终止，不会再创建新会话', { exact: true })).toBeVisible()
  await expect(page.getByText('阶段开始前的文件基线创建失败，尚未创建执行会话。请检查项目文件读取权限、数据目录写入权限及 Git 状态后重做。', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('项目目录失败，请按页面提示处理后重试', { exact: true })).toHaveCount(0)
})
