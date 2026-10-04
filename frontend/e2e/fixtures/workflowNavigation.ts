import type { Page } from '@playwright/test'
import { semanticName } from '../w3/semantics'

const toolKeys: Record<string, string> = {
  '更多工具': 'workflow.tools', '节点列表': 'workflow.nodeList', '自动排列': 'workflow.autoLayout',
  '提前结束需求': 'workflow.finish', '候选计划': 'workflow.reviewCandidate',
  '调整后续计划': 'workflow.adjustPlan', '查看代码成果': 'workflow.publicationSources',
  '另存为流程模板': 'workflow.saveTemplate',
}

// Follow the same disclosure controls as a user; never force clicks through panels.
export async function workflowTool(page: Page, name: string) {
  const action = page.getByRole('button', { name: toolKeys[name] ? semanticName(toolKeys[name]!) : name, exact: true })
  if (!await action.isVisible()) {
    const editorTools = page.getByRole('button', { name: semanticName('workflow.tools'), exact: true })
    await (await editorTools.isVisible() ? editorTools : page.getByRole('button', { name: semanticName('workflow.moreTools'), exact: true })).click()
  }
  await action.click()
}

export async function addWorkflowNode(page: Page, name: '人工检查' | '文件工作' | '只读分析' | '预设工作模块') {
  await page.getByRole('button', { name: '添加节点', exact: true }).click()
  const editorKeys = { '人工检查': 'workflow.addHuman', '文件工作': 'workflow.addWrite', '只读分析': 'workflow.addReadonly', '预设工作模块': 'workflow.presets' }
  const editorAction = page.getByRole('button', { name: semanticName(editorKeys[name]), exact: true })
  if (await editorAction.isVisible()) await editorAction.click()
  else await page.getByRole('button', { name: name === '预设工作模块' ? semanticName('ui.open', name) : semanticName('workflow.addNode', name === '文件工作' ? '自由写入任务' : name === '只读分析' ? '自由只读任务' : name), exact: true }).click()
}

export async function connectWorkflowFrom(page: Page, title: string) {
  await page.locator('.workflow-node').filter({ hasText: title }).click()
  await page.getByRole('button', { name: `从${title}连接后续节点`, exact: true }).click()
}

export async function selectWorkflowNode(page: Page, title: string) {
  await workflowTool(page, '节点列表')
  const panel = page.getByRole('complementary', { name: '查找节点', exact: true })
  const editorAction = panel.getByRole('button', { name: semanticName('ui.focus', title), exact: true })
  await (await editorAction.isVisible() ? editorAction : panel.getByRole('button', { name: semanticName('selection.select', title), exact: true })).click()
}

export async function expandWorkflowNodeSettings(page: Page) {
  const advanced = page.locator('.workflow-node-advanced')
  if (await advanced.count() && await advanced.getAttribute('open') === null) await advanced.locator('summary').click()
}
