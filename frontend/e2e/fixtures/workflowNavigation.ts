import type { Page } from '@playwright/test'

// Follow the same disclosure controls as a user; never force clicks through panels.
export async function workflowTool(page: Page, name: string) {
  const action = page.getByRole('button', { name, exact: true })
  if (!await action.isVisible()) await page.getByRole('button', { name: '更多工具', exact: true }).click()
  await action.click()
}

export async function addWorkflowNode(page: Page, name: '人工检查' | '文件工作' | '只读分析' | '预设工作模块') {
  await page.getByRole('button', { name: '添加节点', exact: true }).click()
  await page.getByRole('button', { name: new RegExp(name) }).click()
}

export async function connectWorkflowFrom(page: Page, title: string) {
  await page.locator('.workflow-node').filter({ hasText: title }).click()
  await page.getByRole('button', { name: `从${title}连接后续节点`, exact: true }).click()
}

export async function selectWorkflowNode(page: Page, title: string) {
  await workflowTool(page, '节点列表')
  await page.getByRole('region', { name: '节点列表', exact: true }).getByRole('button', { name: new RegExp(title) }).click()
}

export async function expandWorkflowNodeSettings(page: Page) {
  const advanced = page.locator('.workflow-node-advanced')
  if (await advanced.count() && await advanced.getAttribute('open') === null) await advanced.locator('summary').click()
}
