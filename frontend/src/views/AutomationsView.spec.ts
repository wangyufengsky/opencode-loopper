import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AutomationsView from '@/views/AutomationsView.vue'

const mocks = vi.hoisted(() => ({ getProjects: vi.fn(), getLoopSpecTemplates: vi.fn(), createLoopSpecTemplate: vi.fn(), createLoopSpecTemplateVersion: vi.fn(), previewLoopSpecTemplateImport: vi.fn(), confirmLoopSpecTemplateImport: vi.fn(), exportLoopSpecTemplate: vi.fn(), getAutomationRules: vi.fn(), createAutomationRule: vi.fn(), updateAutomationRule: vi.fn(), triggerAutomationRule: vi.fn(), getAutomationRuns: vi.fn(), confirmAutomationRun: vi.fn() }))
vi.mock('@/api/client', () => ({ api: mocks }))

const projects = [{ id: 'p1', name: 'Loopper' }]
const template = { id: 't1', name: '发布模板', description: '发布前检查', versions: [{ id: 'v1', versionNumber: 1, autoStartApproved: true, createdAt: 'now' }, { id: 'v2', versionNumber: 2, autoStartApproved: false, createdAt: 'now' }] }
const manualRule = { id: 'r-manual', name: '手工检查', projectId: 'p1', templateVersionId: 'v1', triggerType: 'MANUAL', state: 'DISABLED', approvalMode: 'REVIEW_REQUIRED', triggerConfig: {}, updatedAt: 'now', version: 3 }
const cronRule = { id: 'r-cron', name: '定时检查', projectId: 'p1', templateVersionId: 'v2', triggerType: 'CRON', state: 'DISABLED', approvalMode: 'REVIEW_REQUIRED', triggerConfig: { expression: '0 2 * * *', timezone: 'Asia/Shanghai' }, updatedAt: 'now', version: 4 }
const reviewRun = { id: 'run-review', ruleId: 'r-manual', triggerType: 'MANUAL', state: 'REVIEW_REQUIRED', detectedAt: '2026-08-05T12:00:00Z' }
const workspace = { serverTime: '2026-08-05T12:00:00Z', templates: [template], rules: [manualRule, cronRule], runs: [reviewRun] }

function mountView() { return mount(AutomationsView, { global: { plugins: [ElementPlus], stubs: { Icon: true, PageHeader: { template: '<header><slot name="actions" /></header>' } } } }) }

beforeEach(() => {
  Object.values(mocks).forEach(mock => mock.mockReset())
  mocks.getProjects.mockResolvedValue(structuredClone(projects))
  mocks.getLoopSpecTemplates.mockResolvedValue(structuredClone(workspace.templates))
  mocks.getAutomationRules.mockResolvedValue(structuredClone(workspace.rules))
  mocks.getAutomationRuns.mockResolvedValue({ runs: structuredClone(workspace.runs), serverTime: workspace.serverTime })
  mocks.createLoopSpecTemplate.mockResolvedValue({ ...template, id: 't2' })
  mocks.createLoopSpecTemplateVersion.mockResolvedValue(template.versions[0]!)
  mocks.previewLoopSpecTemplateImport.mockResolvedValue({ previewId: 'preview-1', templates: workspace.templates, rules: workspace.rules })
  mocks.confirmLoopSpecTemplateImport.mockResolvedValue({ templates: structuredClone(workspace.templates), rules: [] })
  mocks.exportLoopSpecTemplate.mockResolvedValue('{"templates":[]}')
  mocks.createAutomationRule.mockResolvedValue({ rule: { id: 'r-webhook', name: 'Webhook', projectId: 'p1', templateVersionId: 'v1', triggerType: 'WEBHOOK', state: 'DISABLED', approvalMode: 'REVIEW_REQUIRED', triggerConfig: {}, updatedAt: 'now', version: 1 }, webhookToken: 'once-secret', webhookPath: '/automations/webhook/once-secret' })
  mocks.updateAutomationRule.mockImplementation(async (rule: unknown) => rule)
  mocks.triggerAutomationRule.mockResolvedValue({ id: 'run-manual', ruleId: 'r-manual', triggerType: 'MANUAL', state: 'QUEUED', queueState: 'QUEUED', detectedAt: '2026-08-05T12:00:01Z' })
  mocks.confirmAutomationRun.mockResolvedValue({ ...reviewRun, state: 'QUEUED', taskId: 'task-1' })
})
afterEach(() => vi.restoreAllMocks())

describe('AutomationsView expected public API contract', () => {
  it('renders loading before the server responds', () => {
    mocks.getLoopSpecTemplates.mockReturnValue(new Promise(() => {}))
    const wrapper = mountView()
    expect(wrapper.text()).toContain('正在读取服务端自动化记录')
  })

  it('reads the create mutation wrapper and only offers manual triggering for MANUAL rules', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('input[placeholder="规则名称"]').setValue('Webhook')
    await wrapper.find('select[aria-label="项目"]').setValue('p1')
    await wrapper.findAll('select')[2]!.setValue('v1')
    await wrapper.findAll('select')[3]!.setValue('WEBHOOK')
    await wrapper.findAll('button').find(button => button.text().includes('创建默认停用规则'))!.trigger('click')
    await flushPromises()
    expect(mocks.createAutomationRule).toHaveBeenCalledWith({ name: 'Webhook', projectId: 'p1', templateVersionId: 'v1', triggerType: 'WEBHOOK', triggerConfig: {} })
    expect(wrapper.text()).toContain('once-secret')
    const manualTriggers = wrapper.findAll('button').filter(button => button.text().includes('立即触发'))
    expect(manualTriggers).toHaveLength(1)
    await manualTriggers[0]!.trigger('click')
    await flushPromises()
    expect(mocks.triggerAutomationRule).toHaveBeenCalledWith('r-manual')
  })

  it('explicitly updates rules, gates AUTO_START by version approval, and confirms review runs', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.findAll('button').filter(button => button.text().includes('启用 AUTO_START'))).toHaveLength(1)
    await wrapper.findAll('button').find(button => button.text().includes('启用 REVIEW_REQUIRED'))!.trigger('click')
    await flushPromises()
    expect(mocks.updateAutomationRule).toHaveBeenCalledWith(expect.objectContaining({ id: 'r-manual', state: 'ENABLED', approvalMode: 'REVIEW_REQUIRED', version: 3 }))
    await wrapper.findAll('button').find(button => button.text().includes('启用 AUTO_START'))!.trigger('click')
    await flushPromises()
    expect(mocks.updateAutomationRule).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'r-manual', state: 'ENABLED', approvalMode: 'AUTO_START', version: 3 }))
    await wrapper.findAll('button').find(button => button.text().includes('确认生成任务'))!.trigger('click')
    await flushPromises()
    expect(mocks.confirmAutomationRun).toHaveBeenCalledWith('run-review')
    expect(wrapper.find('tbody').text()).toContain('task-1')
    expect(wrapper.find('tbody').text()).not.toContain('REVIEW_REQUIRED')
  })

  it('previews before import, reveals imported webhook tokens once, and exports persisted configuration', async () => {
    mocks.confirmLoopSpecTemplateImport.mockResolvedValueOnce({
      templates: structuredClone(workspace.templates),
      rules: [{
        rule: { id: 'r-imported-hook', name: 'Imported hook', projectId: 'p1', templateVersionId: 'v1', triggerType: 'WEBHOOK', state: 'DISABLED', approvalMode: 'REVIEW_REQUIRED', triggerConfig: {}, updatedAt: 'now', version: 0 },
        webhookToken: 'imported-once-secret', webhookPath: '/api/automations/webhooks/r-imported-hook/{token}',
      }],
    })
    const wrapper = mountView()
    await flushPromises()
    expect(mocks.confirmLoopSpecTemplateImport).not.toHaveBeenCalled()
    await wrapper.find('textarea[aria-label="导入 JSON"]').setValue('{"formatVersion":1,"templates":[],"rules":[]}')
    await wrapper.findAll('button').find(button => button.text().includes('预览导入'))!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('未创建任何记录')
    await wrapper.findAll('button').find(button => button.text().includes('确认创建'))!.trigger('click')
    await flushPromises()
    expect(mocks.confirmLoopSpecTemplateImport).toHaveBeenCalledWith('preview-1')
    expect(wrapper.text()).toContain('imported-once-secret')
    await wrapper.findAll('button').find(button => button.text().includes('导出'))!.trigger('click')
    await flushPromises()
    expect((wrapper.find('textarea[aria-label="导出 JSON"]').element as HTMLTextAreaElement).value).toBe('{"templates":[]}')
  })
})
