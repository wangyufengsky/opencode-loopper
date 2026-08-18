import { expect, test, type Page } from '@playwright/test'

type Phase = 'requirement-question' | 'requirement-review' | 'wp1-question' | 'wp1-review' | 'wp2-question' | 'wp2-review' | 'final-review'

const now = '2026-08-17T02:00:00Z'
const project = {
  id: 'e2e-project', name: 'Designer E2E', rootPath: '/tmp/loopper-designer-e2e',
  status: 'READY', executionMode: 'WORKTREE', updatedAt: now, taskCount: 0, openDesignerSessionCount: 0,
}
const settings = {
  cliPath: 'opencode', allowedRoot: '/tmp', provider: 'openai', model: 'gpt-5',
  maxTaskAttempts: 7, timeoutMinutes: 45, autoApprove: false, updatedAt: now,
}
const finalSpec = {
  schemaVersion: 'v2', projectId: project.id, goal: '通过两个工作包完成用户功能', context: '只读设计后人工确认',
  stages: [
    {
      workPackageId: 'WP-1', objective: '建立核心能力', allowedPaths: ['src/**'], forbiddenPaths: [], deliverables: ['核心实现'], implementationKind: 'NON_JAVA',
      acceptanceCriteria: [{ id: 'WP-1-AC-1', description: '核心能力可验证', verificationMode: 'MACHINE' }],
      verifiers: [{ type: 'PROCESS', command: ['node', '--version'], processPurpose: 'SELF_CHECK', outputContains: 'v', criterionIds: ['WP-1-AC-1'] }],
    },
    {
      workPackageId: 'WP-2', objective: '接入用户界面', allowedPaths: ['frontend/**'], forbiddenPaths: [], deliverables: ['交互界面'], implementationKind: 'NON_JAVA',
      acceptanceCriteria: [{ id: 'WP-2-AC-1', description: '界面路径可验证', verificationMode: 'MACHINE' }],
      verifiers: [{ type: 'PROCESS', command: ['node', '--version'], processPurpose: 'SELF_CHECK', outputContains: 'v', criterionIds: ['WP-2-AC-1'] }],
    },
  ],
  limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: 'PT2H', attemptTimeout: 'PT45M' },
  sessionPolicy: { reuseHealthySession: true, createFreshOnVerifierFailure: true },
}

function draft(status = 'DRAFT_READY') {
  return { id: 'draft-e2e', status, updatedAt: now, spec: finalSpec }
}

function question(id: string, scope: string, prompt: string, discussionRevision: number) {
  return [{
    id, scope, discussionRevision,
    questions: [{
      header: '设计选择', question: prompt, multiple: false, custom: true,
      options: [
        { label: '保持最小范围（推荐）', description: '优先完成可验证的最小交付' },
        { label: '扩展范围', description: '同时加入额外功能' },
      ],
    }],
  }]
}

function packages(phase: Phase) {
  const wp1Approved = ['wp2-question', 'wp2-review', 'final-review'].includes(phase)
  const wp2Approved = phase === 'final-review'
  const state = (id: 'WP-1' | 'WP-2') => {
    if (id === 'WP-1') {
      if (wp1Approved) return 'APPROVED'
      if (phase === 'wp1-question') return 'QUESTIONING'
      if (phase === 'wp1-review') return 'REVIEWING'
      return 'PENDING'
    }
    if (wp2Approved) return 'APPROVED'
    if (phase === 'wp2-question') return 'QUESTIONING'
    if (phase === 'wp2-review') return 'REVIEWING'
    return 'PENDING'
  }
  return [
    { id: 'WP-1', ordinal: 1, title: '核心能力', objective: '建立核心能力', state: state('WP-1'), dependencies: [], redesignCount: 0, compilerRepairCount: 0, compilerPlanningRepairCount: 0, designRevision: 1, approvedDesignRevision: wp1Approved ? 1 : undefined, discussionRoundCount: 0, approvedAt: wp1Approved ? now : undefined },
    { id: 'WP-2', ordinal: 2, title: '用户界面', objective: '接入用户界面', state: state('WP-2'), dependencies: ['WP-1'], redesignCount: 0, compilerRepairCount: 0, compilerPlanningRepairCount: 0, designRevision: 1, approvedDesignRevision: wp2Approved ? 1 : undefined, discussionRoundCount: 0, approvedAt: wp2Approved ? now : undefined },
  ]
}

function session(phase: Phase) {
  const requirement = phase.startsWith('requirement')
  const wp1 = phase.startsWith('wp1')
  const wp2 = phase.startsWith('wp2')
  const reviewing = phase.endsWith('review')
  const revision = requirement ? 1 : wp1 ? 2 : wp2 ? 3 : 4
  const activeWorkPackageId = wp1 ? 'WP-1' : wp2 ? 'WP-2' : undefined
  const pendingQuestions = phase === 'requirement-question'
    ? question('q-requirement', 'REQUIREMENT', '应优先保证哪个设计目标？', revision)
    : phase === 'wp1-question'
      ? question('q-wp1', 'WP-1', 'WP-1 采用哪种边界？', revision)
      : phase === 'wp2-question'
        ? question('q-wp2', 'WP-2', 'WP-2 采用哪种交互？', revision)
        : []
  const workflowPhase = requirement
    ? 'DISCUSSING_REQUIREMENT'
    : phase === 'final-review'
      ? 'FINAL_REVIEW'
      : phase.endsWith('question') ? 'QUESTIONING_PACKAGE' : 'REVIEWING_PACKAGE'
  return {
    id: 'designer-e2e', projectId: project.id, projectName: project.name,
    state: reviewing ? 'REVIEWING' : 'RUNNING', workflowPhase,
    activeActor: reviewing ? 'SYSTEM' : 'DESIGNER', readOnly: true, updatedAt: now,
    draft: draft(), messages: [{ id: `message-${phase}`, role: 'ASSISTANT', actor: 'DESIGNER', content: `# ${phase}\n\n完整设计快照`, deliveryState: 'PERSISTED', workPackageId: activeWorkPackageId, requirementRevision: 1, createdAt: now }],
    pendingQuestions, requirement: { revision: 1, state: 'FROZEN', modelCallsUsed: 12, maxModelCalls: 96, sourceDraftVersion: 1 },
    decomposition: requirement ? undefined : { id: 'decomp-e2e', state: 'COMPLETED', resultType: 'DECOMPOSED', repairCount: 0, transportRetryCount: 0, workflowStep: 'FINAL_JSON', planningRepairCount: 0 },
    workPackages: requirement ? [] : packages(phase), requirementRevision: 1, activeWorkPackageId,
    discussionScope: requirement ? 'REQUIREMENT' : phase === 'final-review' ? 'FINAL' : activeWorkPackageId,
    discussionRevision: revision,
    candidate: reviewing && !requirement
      ? { syncState: 'SYNCED', discussionRevision: revision, workPackageId: activeWorkPackageId, spec: finalSpec, detail: '候选已通过确定性校验' }
      : { syncState: 'NONE', discussionRevision: revision },
    finalConfirmationEligible: phase === 'final-review',
  }
}

async function installDesignerApi(page: Page) {
  let phase: Phase = 'requirement-question'
  let confirmed = false
  await page.route('http://127.0.0.1:41773/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    const fulfill = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: status === 204 ? '' : JSON.stringify(body) })

    if (path.endsWith('/events')) return route.fulfill({ status: 204 })
    if (path === '/api/projects' || path === '/api/projects/summaries') return fulfill([project])
    if (path === '/api/tasks' || path === '/api/tasks/summaries') return fulfill(path.endsWith('summaries') ? { items: [], facets: {} } : [])
    if (path === '/api/runtime/opencode') return fulfill({ status: 'OFFLINE', managed: false, checkedAt: now })
    if (path === '/api/settings') return fulfill(settings)
    if (path === '/api/designer-sessions' && method === 'GET') return fulfill([])
    if (path === '/api/loop-drafts' && method === 'POST') return fulfill(draft())
    if (path === '/api/loop-drafts/validate' && method === 'POST') return fulfill({ valid: true, schemaVersion: 'v2', legacy: false, errors: [], stageAssessments: [] })
    if (path === '/api/loop-drafts/draft-e2e' && method === 'PUT') return fulfill(draft())
    if (path === '/api/loop-drafts/draft-e2e' && method === 'GET') return fulfill(draft(confirmed ? 'CONFIRMED' : 'DRAFT_READY'))
    if (path === '/api/loop-drafts/draft-e2e/confirm' && method === 'POST') { confirmed = true; return fulfill({ taskId: 'task-e2e' }) }
    if (path === '/api/designer-sessions' && method === 'POST') return fulfill(session(phase))
    if (path === '/api/designer-sessions/designer-e2e' && method === 'GET') return fulfill(session(phase))
    if (path === '/api/designer-sessions/designer-e2e/questions/q-requirement/reply' && method === 'POST') { phase = 'requirement-review'; return fulfill(undefined, 204) }
    if (path === '/api/designer-sessions/designer-e2e/requirement/confirm' && method === 'POST') { phase = 'wp1-question'; return fulfill(undefined, 204) }
    if (path === '/api/designer-sessions/designer-e2e/questions/q-wp1/reply' && method === 'POST') { phase = 'wp1-review'; return fulfill(undefined, 204) }
    if (path === '/api/designer-sessions/designer-e2e/work-packages/WP-1/approve' && method === 'POST') { phase = 'wp2-question'; return fulfill(undefined, 204) }
    if (path === '/api/designer-sessions/designer-e2e/questions/q-wp2/reply' && method === 'POST') { phase = 'wp2-review'; return fulfill(undefined, 204) }
    if (path === '/api/designer-sessions/designer-e2e/work-packages/WP-2/approve' && method === 'POST') { phase = 'final-review'; return fulfill(undefined, 204) }
    if (path === '/api/tasks/task-e2e' || path === '/api/tasks/task-e2e/overview') return fulfill({
      id: 'task-e2e', projectId: project.id, projectName: project.name, title: '两包设计任务', goal: finalSpec.goal,
      status: 'PENDING_START', attemptCount: 0, maxAttempts: 7, hasDesignHistory: true, archived: false,
      createdAt: now, updatedAt: now, stages: [], attempts: [], errors: [], judges: [], artifacts: [], workPackages: [],
    })
    if (path === '/api/tasks/task-e2e/audit') return fulfill({ attempts: [], errors: [], judges: [], artifacts: [] })
    return fulfill({})
  })
}

test('需求提问后逐包讨论并确认为 PENDING_START 任务', async ({ page }) => {
  await installDesignerApi(page)
  await page.goto('/designer')

  await page.getByLabel('草案设计目标').fill('通过两个可独立验证的工作包完成用户功能')
  await page.getByRole('button', { name: '开始设计' }).click()

  await expect(page.getByText('应优先保证哪个设计目标？')).toBeVisible()
  await page.getByRole('button', { name: '采用全部推荐项' }).click()
  await expect(page.getByRole('button', { name: '需求已明确，开始拆包' })).toBeVisible()
  await page.getByRole('button', { name: '需求已明确，开始拆包' }).click()

  await expect(page.getByText('WP-1 采用哪种边界？')).toBeVisible()
  await page.getByRole('button', { name: '采用全部推荐项' }).click()
  await expect(page.getByRole('button', { name: '接受 WP-1 并继续' })).toBeVisible()
  await page.getByRole('button', { name: '接受 WP-1 并继续' }).click()

  await expect(page.getByText('WP-2 采用哪种交互？')).toBeVisible()
  await page.getByRole('button', { name: '采用全部推荐项' }).click()
  await expect(page.getByRole('button', { name: '接受 WP-2 并继续' })).toBeVisible()
  await page.getByRole('button', { name: '接受 WP-2 并继续' }).click()

  await expect(page.getByRole('navigation', { name: 'Designer 流程' })).toContainText('总体确认')
  await page.getByRole('button', { name: '确认设计并创建任务' }).last().click()

  await expect(page).toHaveURL(/\/tasks\/task-e2e$/)
  await expect(page.getByText('执行环境尚未申请')).toBeVisible()
  await expect(page.getByRole('button', { name: '开始执行' })).toBeVisible()
})

test('只开启全自动后无需人工审批即可进入已启动任务', async ({ page }) => {
  let poll = 0
  await page.route('http://127.0.0.1:41773/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    const fulfill = (body: unknown, status = 200) => route.fulfill({
      status, contentType: 'application/json', body: status === 204 ? '' : JSON.stringify(body),
    })
    if (path.endsWith('/events')) return route.fulfill({ status: 204 })
    if (path === '/api/projects' || path === '/api/projects/summaries') return fulfill([project])
    if (path === '/api/tasks' || path === '/api/tasks/summaries') return fulfill(path.endsWith('summaries') ? { items: [], facets: {} } : [])
    if (path === '/api/settings') return fulfill(settings)
    if (path === '/api/loop-drafts' && method === 'POST') return fulfill(draft())
    if (path === '/api/loop-drafts/validate' && method === 'POST') return fulfill({ valid: true, schemaVersion: 'v2', legacy: false, errors: [], stageAssessments: [] })
    if (path === '/api/designer-sessions' && method === 'GET') return fulfill([])
    const autoSession = (completed: boolean) => ({
      ...session(completed ? 'final-review' : 'requirement-question'),
      state: completed ? 'REVIEWING' : 'RUNNING',
      autoMode: completed
        ? { enabled: false, state: 'COMPLETED', version: 7, lastAction: 'TASK_START_REQUESTED', taskId: 'task-auto-e2e' }
        : { enabled: true, state: 'ACTIVE', version: poll, lastAction: poll ? 'QUESTION_AUTO_REPLIED' : 'MODE_ENABLED' },
    })
    if (path === '/api/designer-sessions' && method === 'POST') {
      expect(request.headers()['x-loopper-local-ui']).toBe('1')
      expect(request.postDataJSON()).toMatchObject({ autoModeEnabled: true })
      return fulfill(autoSession(false), 201)
    }
    if (path === '/api/designer-sessions/designer-e2e' && method === 'GET') {
      poll += 1
      return fulfill(autoSession(poll >= 3))
    }
    if (path === '/api/tasks/task-auto-e2e/overview' || path === '/api/tasks/task-auto-e2e') return fulfill({
      id: 'task-auto-e2e', projectId: project.id, projectName: project.name, title: '全自动设计任务', goal: finalSpec.goal,
      status: 'QUEUED', attemptCount: 0, maxAttempts: 7, hasDesignHistory: true, archived: false,
      createdAt: now, updatedAt: now, stages: [], attempts: [], errors: [], judges: [], artifacts: [], workPackages: [],
    })
    if (path === '/api/tasks/task-auto-e2e/audit') return fulfill({ attempts: [], errors: [], judges: [], artifacts: [] })
    return fulfill({})
  })

  await page.goto('/designer')
  await page.locator('.designer-auto-create .el-switch').click()
  await page.getByRole('button', { name: '确认开启' }).click()
  await page.getByLabel('草案设计目标').fill('自动完成设计并启动任务')
  await page.getByRole('button', { name: '开始设计' }).click()

  await expect(page).toHaveURL(/\/tasks\/task-auto-e2e$/, { timeout: 10_000 })
  await expect(page.getByText('全自动设计任务')).toBeVisible()
})
