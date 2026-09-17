import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import KnowledgeView from './KnowledgeView.vue'
import { useKnowledgeStore } from '@/stores/knowledgeStore'

const sources = [
  { id: 'code', kind: 'CODE', name: '项目代码', state: 'READY', version: 0 },
  { id: 'documents', kind: 'DOCUMENTS', name: '项目文档', state: 'READY', version: 0 },
]
const summary = { id: 'saved', projectId: 'p', title: '项目问题', model: 'original/frozen', state: 'IDLE', sources, createdAt: '', updatedAt: '', version: 0 }
const choice = (provider: string, model: string) => ({ id: `${provider}/${model}`, provider, model, label: `${provider} / ${model}` })
let catalogWait: Promise<void> | undefined, settingsWait: Promise<void> | undefined
let configured: { provider: string; model: string }, catalog: ReturnType<typeof choice>[], failModels: boolean
let wrapper: VueWrapper | undefined
let created: Record<string, unknown>[]
const fetchMock = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
  const path = new URL(String(input), 'http://localhost').pathname
  let body: unknown
  if (path === '/api/settings') { await settingsWait; body = { openCode: { mode: 'managed', ...configured } } }
  else if (path === '/api/settings/models') {
    await catalogWait
    if (failModels) return new Response(JSON.stringify({ detail: '暂时无法读取模型列表' }), { status: 503 })
    body = catalog
  } else if (path === '/api/projects/summaries') body = [{ id: 'p', name: '项目', rootPath: '/project' }]
  else if (path.endsWith('/knowledge-sources')) body = { items: sources, nextCursor: null }
  else if (path === '/api/knowledge/conversations') {
    if (init?.method === 'POST') {
      const request = JSON.parse(String(init.body)); created.push(request)
      if (request.model !== `${configured.provider}/${configured.model}` && !catalog.some(item => item.id === request.model)) return new Response(JSON.stringify({ detail: '所选模型不可用，请刷新模型列表' }), { status: 400 })
      body = { ...summary, model: request.model }
    } else body = { items: [], nextCursor: null }
  } else if (path.endsWith('/messages')) body = { items: [], nextCursor: null }
  else if (path.includes('/requests/')) body = { accepted: true }
  else if (path === '/api/knowledge/conversations/saved') body = summary
  else throw new Error(`Unexpected test request: ${path}`)
  return new Response(JSON.stringify(body), { status: 200 })
})
async function render(path = '/knowledge') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/knowledge/:conversationId?', component: KnowledgeView }] })
  await router.push(path)
  const pinia = createPinia(); setActivePinia(pinia)
  wrapper = mount(KnowledgeView, { global: { plugins: [pinia, router], stubs: { Icon: true, MarkdownDocument: true, KnowledgeSourcesPanel: true, KnowledgeEvidence: true } } })
  await flushPromises()
  return wrapper
}
const modelSelect = () => wrapper!.get<HTMLSelectElement>('select[aria-label="问答模型"]')
const question = () => wrapper!.get<HTMLTextAreaElement>('textarea[aria-label="向项目提问"]')
const sendButton = () => wrapper!.get<HTMLButtonElement>('form .knowledge-send')
async function openModels() { await wrapper!.get('[aria-label="更换问答模型"]').trigger('click'); await flushPromises() }
async function retry() { await wrapper!.get('[role="alert"] button').trigger('click'); await flushPromises() }

describe('知识库真实设置接口与模型选择', () => {
  beforeEach(() => {
    configured = { provider: 'deepseek', model: 'shared-model' }
    catalog = [choice('deepseek', 'shared-model'), choice('opencode-go', 'shared-model')]
    catalogWait = undefined; settingsWait = undefined; failModels = false; created = []; vi.clearAllMocks(); sessionStorage.clear()
    vi.stubGlobal('fetch', fetchMock); vi.stubGlobal('crypto', webcrypto)
    vi.stubGlobal('EventSource', class { close() {} })
  })
  afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.unstubAllGlobals() })

  it('combines the real separate provider/model fields and sends the exact catalog id', async () => {
    await render(); await openModels(); expect(modelSelect().element.value).toBe('deepseek/shared-model')
    await question().setValue('当前项目有几个模块？'); await wrapper!.get('form').trigger('submit'); await flushPromises()
    expect(created).toHaveLength(1); expect(created[0]?.model).toBe('deepseek/shared-model')
    expect(wrapper!.find('[role="alert"]').exists()).toBe(false)
  })

  it('refreshes configuration and catalog without clearing the question or creating a conversation', async () => {
    configured.model = 'retired'; await render(); await openModels(); await question().setValue('保留这个问题')
    expect(sendButton().element.disabled).toBe(false)
    useKnowledgeStore().error = '重新核对模型'; await flushPromises()
    configured.model = 'new-model'; catalog = [choice('deepseek', 'new-model')]
    await retry()
    expect(modelSelect().element.value).toBe('deepseek/new-model'); expect(question().element.value).toBe('保留这个问题')
    expect(sendButton().element.disabled).toBe(false); expect(created).toEqual([])
    expect(fetchMock.mock.calls.filter(([url]) => String(url) === '/api/settings/models')).toHaveLength(2)
  })

  it('keeps an explicitly selected provider when reloading the system default', async () => {
    await render(); await openModels(); await modelSelect().setValue('opencode-go/shared-model'); await question().setValue('继续')
    useKnowledgeStore().error = '重新核对模型'; await flushPromises()
    configured.model = 'new-default'; catalog.push(choice('deepseek', 'new-default')); await retry()
    expect(modelSelect().element.value).toBe('opencode-go/shared-model'); expect(question().element.value).toBe('继续')
  })

  it('does not guess a provider from a bare name shared by several providers', async () => {
    configured.provider = ''; await render(); await openModels(); await question().setValue('问题')
    expect(modelSelect().element.value).toBe(''); expect(sendButton().element.disabled).toBe(true)
    await modelSelect().setValue('opencode-go/shared-model'); expect(sendButton().element.disabled).toBe(false)
  })

  it('keeps the default usable when discovery fails and retries only inside the picker', async () => {
    failModels = true; await render(); await question().setValue('未发送的内容')
    expect(sendButton().element.disabled).toBe(false); expect(wrapper!.find('[role="alert"]').exists()).toBe(false)
    await openModels(); expect(wrapper!.get('.knowledge-model-popover [role="alert"]').text()).toContain('仍可使用全局默认')
    failModels = false; await wrapper!.get('.knowledge-model-popover [role="alert"] button').trigger('click'); await flushPromises()
    expect(question().element.value).toBe('未发送的内容'); expect(modelSelect().element.value).toBe('deepseek/shared-model')
    expect(sendButton().element.disabled).toBe(false)
  })

  it('shows the welcome and accepts typing before any configuration response', async () => {
    let release!: () => void; settingsWait = new Promise<void>(resolve => { release = resolve })
    await render(); expect(wrapper!.get('h2').text()).toBe('让项目知识，成为答案')
    expect(question().element.disabled).toBe(false); await question().setValue('先输入问题')
    expect(sendButton().element.disabled).toBe(true)
    release(); await flushPromises(); expect(sendButton().element.disabled).toBe(false)
    expect(question().element.value).toBe('先输入问题')
  })

  it('sends with the global default while the catalog remains pending and deduplicates picker loading', async () => {
    let release!: () => void; catalogWait = new Promise<void>(resolve => { release = resolve })
    await render(); await question().setValue('直接使用默认模型'); await openModels()
    expect(wrapper!.get('.knowledge-model-popover [role="status"]').text()).toContain('正在读取')
    expect(modelSelect().element.value).toBe('deepseek/shared-model'); expect(sendButton().element.disabled).toBe(false)
    expect(fetchMock.mock.calls.filter(([url]) => String(url) === '/api/settings/models')).toHaveLength(1)
    await wrapper!.get('form').trigger('submit'); await flushPromises()
    expect(created[0]?.model).toBe('deepseek/shared-model'); release(); await flushPromises()
  })

  it('keeps the frozen model of an existing conversation when settings are reloaded', async () => {
    await render('/knowledge/saved'); expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/settings/models')).toBe(false); await question().setValue('追问')
    useKnowledgeStore().error = '重新核对会话'; await flushPromises(); configured.model = 'new-default'; await retry()
    expect(useKnowledgeStore().conversation?.model).toBe('original/frozen')
    expect(wrapper!.find('select[aria-label="问答模型"]').exists()).toBe(false)
    expect(question().element.value).toBe('追问'); expect(sendButton().element.disabled).toBe(false)
  })

  it('opens history before global settings return and never loads a model catalog for it', async () => {
    let release!: () => void; settingsWait = new Promise<void>(resolve => { release = resolve })
    await render('/knowledge/saved')
    expect(useKnowledgeStore().conversation?.model).toBe('original/frozen')
    expect(question().element.disabled).toBe(false); await question().setValue('加载时输入的追问')
    release(); await flushPromises()
    expect(question().element.value).toBe('加载时输入的追问'); expect(sendButton().element.disabled).toBe(false)
    expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/settings/models')).toBe(false)
  })
})
