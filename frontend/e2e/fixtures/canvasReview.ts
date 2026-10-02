import type { Page } from '@playwright/test'
import { newNode } from '../../src/components/workflow/graph'
import { template } from '../../src/components/workflow/workflowTestFixtures'
import { execution, requirement } from '../../src/components/workflow/workflowRunTestFixtures'

// Explicitly simulated HTTP data: no backend, process, provider or paid model is used.
export async function canvasReviewFixture(page: Page, running = false) {
  const graph = {
    schemaVersion: 1,
    inputs: [],
    nodes: [
      { ...newNode('human'), id: 'scope', title: '确认需求', task: '核对需求范围与交付目标。' },
      { ...newNode('free.readonly'), id: 'design', title: '分析与设计', task: '形成可以检查的实施方案。' },
      { ...newNode('human'), id: 'review', title: '检查交付', task: '核对交付物与验收标准。' },
    ],
    edges: [
      { id: 'scope-design', from: 'scope', to: 'design', outcome: null },
      { id: 'design-review', from: 'design', to: 'review', outcome: null },
    ],
  }
  const layout = { positions: { scope: { x: 80, y: 60 }, design: { x: 360, y: 245 }, review: { x: 640, y: 430 } }, x: 32, y: 36, zoom: 1 }
  const flow = template({ id: 'canvas-review', title: '需求交付流程 · 模拟数据', graph, layout })
  const req = requirement({ title: '需求交付 · 模拟数据', graph, layout, state: running ? 'PAUSED' : 'PLANNING' })
  const snapshot = execution(req.state)
  snapshot.execution.nodes = graph.nodes.map((node, index) => ({ id: `summary-${index}`, nodeKey: node.id, state: 'PENDING', attemptCount: 0, latestAttemptId: null, version: 0, outcome: null }))
  const mutations: string[] = []
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route(/^http:\/\/127\.0\.0\.1:\d+\/api\//, async route => {
    const path = new URL(route.request().url()).pathname
    if (route.request().method() !== 'GET') {
      mutations.push(path)
      return route.fulfill({ status: 501, json: { message: '此预览仅使用模拟数据，不执行真实操作。' } })
    }
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/templates/canvas-review') return route.fulfill({ json: flow })
    if (path === '/api/workflows/templates/builtin.workflow.development') return route.fulfill({ json: flow })
    if (path === '/api/workflows/templates') return route.fulfill({ json: { items: [{ ...flow, createdAt: '2026-10-02T08:00:00Z', updatedAt: '2026-10-02T08:00:00Z' }], nextCursor: null } })
    if (path === '/api/workflows/requirements') return route.fulfill({ json: { items: [{ ...req, createdAt: '2026-10-02T08:00:00Z', updatedAt: '2026-10-02T08:00:00Z' }], nextCursor: null } })
    if (path === '/api/template-tasks/projects') return route.fulfill({ json: { items: [{ id: 'project', name: '演示项目 · 模拟数据' }], nextCursor: null } })
    if (path === '/api/template-tasks/projects/project') return route.fulfill({ json: { id: 'project', name: '演示项目 · 模拟数据' } })
    if (path === '/api/workflows/requirements/req') return route.fulfill({ json: req })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/publication') || path.endsWith('/writeback') || path.endsWith('/push')) return route.fulfill({ json: null })
    if (path.endsWith('/attempts') || path.endsWith('/candidates') || path.startsWith('/api/roles') || path.endsWith('/node-presets')) return route.fulfill({ json: { items: [], nextCursor: null } })
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  return { flow, req, snapshot, mutations, errors }
}
