import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RollingPackageWorkbench from '@/components/RollingPackageWorkbench.vue'
import type { RollingPackageRun, Task } from '@/types/domain'

const apiMocks = vi.hoisted(() => ({
  getRollingPackageWorkbench: vi.fn(), getRollingPackageDetail: vi.fn(),
  approveRollingPackageDesign: vi.fn(), startRollingPackage: vi.fn(),
  retryRollingPackageCheckpoint: vi.fn(), redesignRollingPackage: vi.fn(),
  discussRollingPackage: vi.fn(), resolveRollingPackageFailure: vi.fn(),
  proposeRollingPlan: vi.fn(), confirmRollingPlan: vi.fn(), addRollingCorrection: vi.fn(),
  suggestRollingPlan: vi.fn(), getRollingPlanRevisions: vi.fn(),
}))
vi.mock('@/api/client', () => ({ api: apiMocks }))

const frozen: RollingPackageRun = {
  id: 'run-1', packageKey: 'WP-1', ordinal: 0, title: '基础能力', state: 'FACT_FROZEN',
  version: 4, discussionRevision: 1, designRevision: 2, acceptedDesignRevision: 2, dependencies: [],
}
const reviewing: RollingPackageRun = {
  id: 'run-2', packageKey: 'WP-2', ordinal: 0, title: '业务接入', state: 'DESIGN_REVIEW',
  version: 3, discussionRevision: 5, designRevision: 6, dependencies: ['WP-1'],
}

function task(run: RollingPackageRun, capabilities: NonNullable<Task['packageCapabilities']>): Task {
  return {
    id: 'task-1', projectId: 'project-1', projectName: 'Project', title: '三包任务', goal: '逐包交付',
    branch: 'loopper/task-1', worktreePath: '/tmp/project', status: 'PACKAGE_DESIGNING',
    executionMode: 'ROLLING_PACKAGES', workspacePolicy: 'RELEASE_BETWEEN_PACKAGES', currentPackage: run,
    plannedPackageCount: 2, frozenPackageCount: 1, packageCapabilities: capabilities,
    cancellationAvailable: true, hasDesignHistory: true, archived: false, attemptCount: 1, maxAttempts: 6,
    createdAt: 'start', updatedAt: 'now', stages: [], attempts: [], errors: [], judges: [], artifacts: [],
  }
}

function mountWorkbench(input: Task) {
  return mount(RollingPackageWorkbench, {
    props: { task: input }, global: { plugins: [ElementPlus], stubs: {
      Icon: true, StatusBadge: { props: ['status'], template: '<span>{{ status }}</span>' },
      MarkdownDocument: { props: ['content'], template: '<div>{{ content }}</div>' },
    } },
  })
}

describe('RollingPackageWorkbench', () => {
  beforeEach(() => {
    Object.values(apiMocks).forEach(mock => mock.mockReset())
    apiMocks.getRollingPackageWorkbench.mockResolvedValue({
      taskId: 'task-1', title: '三包任务', taskState: 'PACKAGE_DESIGNING', taskVersion: 8,
      executionMode: 'ROLLING_PACKAGES', workspacePolicy: 'RELEASE_BETWEEN_PACKAGES',
      planRevisionId: 'plan-2', planRevision: 2, plannedPackageCount: 2, frozenPackageCount: 1,
      packages: [frozen, reviewing],
    })
    apiMocks.getRollingPackageDetail.mockImplementation((_taskId: string, runId: string) => Promise.resolve({
      packageRun: runId === frozen.id ? frozen : reviewing, objective: '接入冻结后的真实接口',
      deliverablesJson: '[]', acceptanceIntentJson: '[]', designMarkdown: '# 当前设计',
      fact: runId === frozen.id ? { id: 'fact-1', packageRunId: frozen.id, checkpointId: 'checkpoint-1',
        successfulAttemptId: 'attempt-1', provenJson: '{"tree":"abc"}',
        acceptedContractJson: '{"designRevision":2}', navigationSummary: '仅用于定位', createdAt: 'now' } : undefined,
    }))
    apiMocks.approveRollingPackageDesign.mockResolvedValue(undefined)
    apiMocks.startRollingPackage.mockResolvedValue(undefined)
    apiMocks.retryRollingPackageCheckpoint.mockResolvedValue(undefined)
  })

  it('keeps design approval and execution start as separate manual boundaries', async () => {
    const capabilities = { canDiscuss: true, canApproveDesign: true, canStartPackage: false,
      canRetryPackage: false, canRedesignPackage: false, canReplanRemaining: true,
      canAddCorrectionPackage: true }
    const wrapper = mountWorkbench(task(reviewing, capabilities))
    await flushPromises()

    expect(wrapper.text()).toContain('已冻结 1/2 包')
    expect(wrapper.text()).toContain('每包事实冻结后释放登记目录租约')
    expect(wrapper.text()).toContain('第 1 包')
    expect(wrapper.text()).toContain('第 2 包')
    expect(wrapper.text()).toContain('确认本包设计')
    expect(wrapper.text()).not.toContain('开始本包执行')
    await wrapper.findAll('button').find(button => button.text().includes('确认本包设计'))!.trigger('click')
    await flushPromises()

    expect(apiMocks.approveRollingPackageDesign).toHaveBeenCalledWith('task-1', 'run-2', {
      expectedTaskVersion: 8, expectedPackageVersion: 3,
      expectedDiscussionRevision: 5, expectedDesignRevision: 6,
    })

    await wrapper.setProps({ task: { ...task({ ...reviewing, state: 'EXECUTION_READY', version: 4 }, {
      ...capabilities, canDiscuss: false, canApproveDesign: false, canStartPackage: true,
    }), updatedAt: 'later' } })
    await flushPromises()
    expect(wrapper.text()).toContain('开始本包执行')
    expect(wrapper.text()).not.toContain('确认本包设计')
  })

  it('shows separated fact layers and a versioned checkpoint-release recovery action', async () => {
    const blocked = { ...reviewing, state: 'WAITING_INPUT' as const, version: 9,
      waitingReasonCode: 'PACKAGE_CHECKPOINT_BLOCKED' }
    apiMocks.getRollingPackageWorkbench.mockResolvedValue({
      taskId: 'task-1', title: '三包任务', taskState: 'WAITING_INPUT', taskVersion: 11,
      executionMode: 'ROLLING_PACKAGES', workspacePolicy: 'RELEASE_BETWEEN_PACKAGES',
      planRevisionId: 'plan-2', planRevision: 2, plannedPackageCount: 2, frozenPackageCount: 1,
      packages: [frozen, blocked],
    })
    const wrapper = mountWorkbench(task(blocked, { canDiscuss: false, canApproveDesign: false,
      canStartPackage: false, canRetryPackage: true, canRedesignPackage: false,
      canReplanRemaining: false, canAddCorrectionPackage: true }))
    await flushPromises()

    expect(wrapper.text()).toContain('已证明')
    expect(wrapper.text()).toContain('已接受合同')
    expect(wrapper.text()).toContain('AI 导航摘要 · 非证据')
    const retry = wrapper.findAll('button').find(button => button.text().includes('重新检查并释放租约'))
    expect(retry).toBeDefined()
    await retry!.trigger('click')
    await flushPromises()

    expect(apiMocks.retryRollingPackageCheckpoint).toHaveBeenCalledWith('task-1', 'run-2', {
      expectedTaskVersion: 11, expectedPackageVersion: 9,
      expectedDiscussionRevision: 5, expectedDesignRevision: 6,
    })
  })

  it('polls an AI read-only plan suggestion and keeps confirmation manual', async () => {
    const capabilities = { canDiscuss: true, canApproveDesign: true, canStartPackage: false,
      canRetryPackage: false, canRedesignPackage: true, canReplanRemaining: true,
      canAddCorrectionPackage: true }
    apiMocks.suggestRollingPlan.mockResolvedValue({ id: 'proposal-ai', revision: 3, state: 'GENERATING',
      version: 1, planJson: '[]', impactJson: '{}', origin: 'AI', externalSessionState: 'RUNNING',
      createdAt: 'now', updatedAt: 'now' })
    apiMocks.getRollingPlanRevisions.mockResolvedValue([{ id: 'proposal-ai', revision: 3, state: 'PROPOSED',
      version: 2, planJson: '[{"packageKey":"WP-2"}]', impactJson: '{"added":[]}', origin: 'AI',
      externalSessionState: 'COMPLETED', createdAt: 'now', updatedAt: 'later' }])
    apiMocks.confirmRollingPlan.mockResolvedValue({ id: 'proposal-ai', revision: 3, state: 'ACTIVE',
      version: 3, planJson: '[]', impactJson: '{}', origin: 'AI', createdAt: 'now', updatedAt: 'later' })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue({ action: 'confirm' } as never)
    const wrapper = mountWorkbench(task(reviewing, capabilities))
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text().includes('AI 调整剩余拆包'))!.trigger('click')
    await flushPromises()

    expect(apiMocks.suggestRollingPlan).toHaveBeenCalledWith('task-1', {
      expectedTaskVersion: 8, expectedPackageRunId: 'run-2', expectedPackageVersion: 3,
      expectedDiscussionRevision: 5, expectedDesignRevision: 6,
    })
    expect(apiMocks.getRollingPlanRevisions).toHaveBeenCalledWith('task-1')
    expect(apiMocks.confirmRollingPlan).toHaveBeenCalledWith('task-1', 'proposal-ai', expect.objectContaining({
      expectedProposalVersion: 2,
    }))
  })
})
