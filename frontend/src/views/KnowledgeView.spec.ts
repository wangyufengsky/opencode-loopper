import { flushPromises, mount } from '@/pages/w6-tests/knowledge-ppt-template/react-test-root'
import {pageProps} from '@/pages/w3/ppt/testFixture'

import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {KnowledgePage as KnowledgeView} from '@/pages/w3/knowledge/KnowledgePage'
import type {KnowledgeController} from '@/pages/w3/knowledge/controller'
import {messageFixture} from '@/pages/w3/knowledge/fixtures'

const sources = [
  { id: 'code', kind: 'CODE', name: '项目代码', state: 'READY', version: 0 },
  { id: 'documents', kind: 'DOCUMENTS', name: '项目文档', state: 'READY', version: 0 },
]
const summary = { id: 'saved', projectId: 'p', title: '项目问题', model: 'original/frozen', state: 'IDLE', sources, createdAt: '', updatedAt: '', version: 0 }
const choice = (provider: string, model: string) => ({ id: `${provider}/${model}`, provider, model, label: `${provider} / ${model}` })
let catalogWait: Promise<void> | undefined, settingsWait: Promise<void> | undefined
let configured: { provider: string; model: string }, catalog: ReturnType<typeof choice>[], failModels: boolean
let wrapper:ReturnType<typeof mount<typeof KnowledgeView extends React.ComponentType<infer P> ? P : never>> |undefined
let owner:KnowledgeController
let transportMessages:ReturnType<typeof messageFixture>[]=[]
let context:ReturnType<typeof pageProps>|undefined
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
      body = { ...summary, id:request.id, model: request.model }
    } else body = { items: [], nextCursor: null }
  } else if (path.endsWith('/messages') && init?.method==='POST') body={...messageFixture(),id:'sent',userText:JSON.parse(String(init.body)).text}
  else if ((path.endsWith('/messages') || path.endsWith('/messages/updates'))) body = { items: transportMessages, nextCursor: null }
  else if (path.includes('/requests/')) body = { accepted: true }
  else if (/^\/api\/knowledge\/conversations\/[^/]+$/.test(path)) body = {...summary,id:path.split('/').at(-1),model:created.at(-1)?.model ?? summary.model}
  else throw new Error(`Unexpected test request: ${path}`)
  return new Response(JSON.stringify(body), { status: 200 })
})
async function render(path='/knowledge'){context=pageProps();context.props.route={path,fullPath:path,params:{conversationId:path.endsWith('/saved')?'saved':''},query:{}};const retain=context.props.lifecycle.retain;context.props.lifecycle.retain=(value,release)=>{retain(value,release);if((value as KnowledgeController).identity?.domain==='knowledge')owner=value as KnowledgeController};wrapper=mount(KnowledgeView,{props:context.props});await flushPromises();return wrapper}

const modelPanel=()=>wrapper!.findAll('[data-foundation-component="context"]').find(panel=>panel.text().includes('全局默认模型：'))!
const modelSelect = () => wrapper!.get<HTMLSelectElement>('select[aria-label="问答模型"]')
const question = () => wrapper!.get<HTMLTextAreaElement>('textarea[aria-label="向项目提问"]')
const sendButton = () => wrapper!.get<HTMLButtonElement>('form button[data-semantic="knowledge.send"]')
async function openModels() { await wrapper!.get('button[data-semantic="knowledge.selectModel"]').trigger('click'); await flushPromises() }
async function retry() { await wrapper!.get('button[data-semantic="ui.refresh"][aria-label*="知识库"]').trigger('click'); await flushPromises() }

describe('知识库真实设置接口与模型选择', () => {
  beforeEach(() => {
    configured = { provider: 'deepseek', model: 'shared-model' }
    catalog = [choice('deepseek', 'shared-model'), choice('opencode-go', 'shared-model')]
    catalogWait = undefined; settingsWait = undefined; failModels = false; created = []; transportMessages=[]; vi.clearAllMocks(); sessionStorage.clear()
    vi.stubGlobal('fetch', fetchMock); vi.stubGlobal('crypto', webcrypto)
    vi.stubGlobal('EventSource', class { close() {} })
  })
  afterEach(() => { wrapper?.unmount();context?.retire();context=undefined; wrapper = undefined; vi.unstubAllGlobals() })

  it('switches empty-turn feedback to embedded thinking and answer content as server messages arrive', async () => {
    await render('/knowledge/saved')
    const store=owner
    const message = {...messageFixture(), id: 'turn', ordinal: 1, state: 'RUNNING', userText: '核心流程是什么？', answer: '', detail: '', inputTokens: null, outputTokens: null, createdAt: '', citations: [], calls: [] }
    transportMessages=[message];await store.refresh(); await flushPromises()
    expect(wrapper!.get('.knowledge-waiting').text()).toBe('正在思考')
    transportMessages=[{ ...message, answer: '<think>检查项目入口</think>' }];await store.refresh(); await flushPromises()
    expect(wrapper!.find('.knowledge-waiting').exists()).toBe(false)
    expect(wrapper!.get('[aria-label="思考"]').text()).toContain('检查项目入口')
    transportMessages=[{ ...message, answer: '首先接收请求。' }];await store.refresh(); await flushPromises()
    expect(wrapper!.find('.knowledge-waiting').exists()).toBe(false)
    expect(wrapper!.find('[aria-label="思考"]').exists()).toBe(false)
    transportMessages=[{ ...message, state: 'FAILED', detail: '模型连接失败，请重试' }];await store.refresh(); await flushPromises()
    expect(wrapper!.find('.knowledge-waiting').exists()).toBe(false)
    expect(wrapper!.text()).toContain('模型连接失败，请重试')
    await store.refresh();await flushPromises()
    await wrapper!.findAll('button').find(button => button.attributes('data-semantic')==='knowledge.retryQuestion')!.trigger('click')
    expect(question().element.value).toBe(message.userText)
    expect(created).toHaveLength(0)
  })

  it('combines the real separate provider/model fields and sends the exact catalog id', async () => {
    await render(); await openModels(); expect(modelSelect().element.value).toBe('deepseek/shared-model')
    await question().setValue('当前项目有几个模块？'); await wrapper!.get('form').trigger('submit'); await flushPromises()
    expect(created).toHaveLength(1); expect(created[0]?.model).toBe('deepseek/shared-model')
    expect(owner.getSnapshot().error).toBe('');expect(wrapper!.find('.w2-status [role="alert"]').exists()).toBe(false)
  })

  it('refreshes configuration and catalog without clearing the question or creating a conversation', async () => {
    configured.model = 'retired'; await render(); await openModels(); await question().setValue('保留这个问题')
    expect(sendButton().element.disabled).toBe(false)
    await flushPromises()
    configured.model = 'new-model'; catalog = [choice('deepseek', 'new-model')]
    await retry()
    expect(modelSelect().element.value).toBe('deepseek/new-model'); expect(question().element.value).toBe('保留这个问题')
    expect(sendButton().element.disabled).toBe(false); expect(created).toEqual([])
    expect(fetchMock.mock.calls.filter(([url]) => String(url) === '/api/settings/models')).toHaveLength(2)
  })

  it('keeps an explicitly selected provider when reloading the system default', async () => {
    await render(); await openModels(); await modelSelect().setValue('opencode-go/shared-model'); await question().setValue('继续')
    await flushPromises()
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
    expect(sendButton().element.disabled).toBe(false); expect(owner.getSnapshot().error).toBe('');expect(wrapper!.find('.w2-status [role="alert"]').exists()).toBe(false)
    await openModels(); expect(modelPanel().get('.ant-alert').text()).toContain('仍可使用全局默认')
    failModels = false; await modelPanel().get('button[data-semantic="ui.refresh"]').trigger('click'); await flushPromises()
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
    expect(modelPanel().text()).toContain('正在读取')
    expect(modelSelect().element.value).toBe('deepseek/shared-model'); expect(sendButton().element.disabled).toBe(false)
    expect(fetchMock.mock.calls.filter(([url]) => String(url) === '/api/settings/models')).toHaveLength(1)
    await wrapper!.get('form').trigger('submit'); await flushPromises()
    expect(created[0]?.model).toBe('deepseek/shared-model'); release(); await flushPromises()
  })

  it('keeps the frozen model of an existing conversation when settings are reloaded', async () => {
    await render('/knowledge/saved'); expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/settings/models')).toBe(false); await question().setValue('追问')
    await flushPromises(); configured.model = 'new-default'; await retry()
    expect(owner.getSnapshot().conversation?.model).toBe('original/frozen')
    expect(wrapper!.get('select[aria-label="问答模型"]').element.closest('[hidden]')).not.toBeNull()
    expect(question().element.value).toBe('追问'); expect(sendButton().element.disabled).toBe(false)
  })

  it('opens history before global settings return and never loads a model catalog for it', async () => {
    let release!: () => void; settingsWait = new Promise<void>(resolve => { release = resolve })
    await render('/knowledge/saved')
    expect(owner.getSnapshot().conversation?.model).toBe('original/frozen')
    expect(question().element.disabled).toBe(false); await question().setValue('加载时输入的追问')
    release(); await flushPromises()
    expect(question().element.value).toBe('加载时输入的追问'); expect(sendButton().element.disabled).toBe(false)
    expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/settings/models')).toBe(false)
  })
})
