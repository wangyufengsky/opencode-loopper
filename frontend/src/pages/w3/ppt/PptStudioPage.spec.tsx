import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { webcrypto, randomUUID } from 'node:crypto'
import { pptApi } from '@/api/ppt'
import { FoundationProvider } from '@/foundation/provider'
import { skins } from '@/themes/registry'
import { semanticName } from '@/foundation/semanticRegistry'
import { pptAgent, pptGeneration, pptText } from '@/components/ppt/pptTestFixtures'
import { PptStudioPage } from './PptStudioPage'
import { message, pageProps, studioFixture } from './testFixture'

const live: ReturnType<typeof pageProps>[] = []
beforeEach(() => { sessionStorage.clear(); localStorage.clear(); vi.stubGlobal('crypto', { randomUUID, subtle: webcrypto.subtle }); vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() })); vi.spyOn(pptApi, 'action').mockImplementation(async (_id, action, _revision, _key, extra) => ({ id: 'doc', title: '季度汇报', projectId: null, model: 'local/model', phase: action === 'reopen' ? 'DESIGN' : 'REVIEW', revision: 4, version: 4, archived: Boolean(extra?.archived), createdAt: '2026-10-03', updatedAt: '2026-10-03' })); vi.spyOn(pptApi, 'knowledge').mockResolvedValue({ project: null, sources: [], detail: '' }) })
afterEach(() => { cleanup(); for (const f of live) f.retire(); live.length = 0; vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
function page(strict = false) { const f = pageProps(); live.push(f); const child = <FoundationProvider skin={f.props.skin} reducedMotion><PptStudioPage {...f.props} /></FoundationProvider>; const view = render(strict ? <StrictMode>{child}</StrictMode> : child); return { ...f, view } }
const click = (key: Parameters<typeof semanticName>[0], target?: string) => fireEvent.click(screen.getByRole('button', { name: semanticName(key, target) }))
async function manual() { click('ppt.editObject', '手动编辑'); await waitFor(() => expect(document.querySelector('[data-canvas-kind="ppt"]')).toBeTruthy()) }
describe('PPT Studio full production React page', () => {
  it('mounts a real React page and verified scene under root StrictMode without any implicit write', async () => {
    const f = studioFixture(); const p = page(true); await screen.findByRole('heading', { name: '季度汇报' }); await manual(); const canvas = document.querySelector('[data-canvas-runtime="react"][data-canvas-kind="ppt"]')!, navigator = document.querySelector('[data-canvas-kind="ppt-navigator"]')!; expect(canvas).toBeTruthy(); expect(navigator).toBeTruthy(); expect(canvas.closest('.ppt-page')?.parentElement?.classList.contains('w3-ppt-scene')).toBe(true); expect(navigator.closest('.ppt-page')?.querySelector('[data-react-page]')).toBeNull(); expect(document.querySelector('[data-react-page]')?.closest('.ppt-page')).toBeNull(); expect(f.operations).not.toHaveBeenCalled(); expect(pptApi.send).not.toHaveBeenCalled(); expect(pptApi.createJob).not.toHaveBeenCalled(); expect(f.events).toHaveLength(2); expect(f.events[0]!.close).toHaveBeenCalledOnce(); expect(f.events[1]!.close).not.toHaveBeenCalled(); p.view.unmount(); p.retire(); expect(f.events[1]!.close).toHaveBeenCalledOnce()
  })
  it('keeps discussion separate from explicit execution and freezes the document scope', async () => {
    const f = studioFixture(); f.setDocument({ phase: 'BRIEFING' }); vi.mocked(pptApi.messages).mockResolvedValue({ items: [message()], facets: {} }); const confirm = vi.spyOn(pptApi, 'confirmGeneration').mockResolvedValue(pptGeneration()); const generate = vi.spyOn(pptApi, 'generate'); page(); await screen.findByRole('heading', { name: '需求讨论中' }); click('ui.open', '作品菜单'); click('ppt.openSources'); const sources = screen.getByRole('complementary', { name: '资料与素材' }); expect(sources).toBeTruthy(); expect(pptApi.send).not.toHaveBeenCalled(); expect(generate).not.toHaveBeenCalled(); fireEvent.click(within(sources).getByRole('button', { name: semanticName('ui.close', '资料与素材') })); const input = screen.getByLabelText('向 PPT 助手发送要求'); fireEvent.change(input, { target: { value: '补充：面向开发者' } }); click('ppt.send'); await waitFor(() => expect(pptApi.send).toHaveBeenCalledOnce()); expect(vi.mocked(pptApi.send).mock.calls[0]![1]).toMatchObject({ text: '补充：面向开发者', expectedRevision: 3, scope: { kind: 'DOCUMENT' } }); expect(generate).not.toHaveBeenCalled(); await waitFor(() => expect(screen.getByRole('button', { name: semanticName('ppt.confirm') })).toBeTruthy()); click('ppt.confirm'); await waitFor(() => expect(confirm).toHaveBeenCalledOnce())
  })
  it('reopens a readonly plan and autosaves the next explicit audience edit', async () => {
    const f = studioFixture(); f.setDocument({ phase: 'EXPORTED' }); const generate = vi.spyOn(pptApi, 'generate');
    vi.mocked(pptApi.action).mockImplementation(async () => { f.setDocument({ phase: 'BRIEFING', revision: 4 }); return { id: 'doc', title: '季度汇报', projectId: null, model: 'local/model', phase: 'BRIEFING', revision: 4, version: 4, archived: false, createdAt: '2026-10-03', updatedAt: '2026-10-03' } })
    page(); await screen.findByRole('heading', { name: '季度汇报' }); click('ui.open', '作品菜单'); click('ppt.openPlan');
    const input = await screen.findByRole('textbox', { name: /^受众$/ }); expect((input as HTMLInputElement).disabled || input.closest('fieldset')?.disabled).toBe(true);
    click('ppt.reopenPlan'); const dialog = await screen.findByRole('dialog'); fireEvent.click(within(dialog).getByRole('button', { name: semanticName('ppt.reopenPlan') }));
    await waitFor(() => expect(input.closest('fieldset')?.disabled).toBe(false));
    fireEvent.change(input, { target: { value: '内网项目评审委员会' } });
    await waitFor(() => expect(pptApi.savePlan).toHaveBeenCalledOnce(), { timeout: 2200 });
    expect(vi.mocked(pptApi.savePlan).mock.calls[0]?.[2].brief.audience).toBe('内网项目评审委员会'); expect(generate).not.toHaveBeenCalled();
  })
  it('holds four-field pending question identity and sends confirmed boolean only for the confirmation card', async () => {
    const f = studioFixture(); f.setDocument({ phase: 'BRIEFING' }); vi.mocked(pptApi.agent).mockResolvedValue({ ...pptAgent(), state: 'WAITING_INPUT', requirementsState: 'AWAITING_CONFIRMATION', questions: [{ id: 'q', kind: 'REQUIREMENTS_CONFIRMATION', prompt: '## 季度需求\n八页商务风格', options: [], state: 'PENDING', answer: null, version: 5 }] }); page(); await screen.findByRole('heading', { name: '需求已整理好' }); expect(screen.getByText('八页商务风格')).toBeTruthy(); const answer = screen.getByLabelText('补充或修改需求'); fireEvent.change(answer, { target: { value: '增加风险说明' } }); expect(screen.getByRole('button', { name: semanticName('ppt.confirm') }).hasAttribute('disabled')).toBe(true); click('ppt.send', '补充意见，继续沟通'); await waitFor(() => expect(pptApi.reply).toHaveBeenCalledOnce()); expect(vi.mocked(pptApi.reply).mock.calls[0]![2]).toMatchObject({ version: 5, confirmed: false, answer: '增加风险说明' }); await waitFor(() => expect((answer as HTMLTextAreaElement).value).toBe('')); click('ppt.confirm'); await waitFor(() => expect(pptApi.reply).toHaveBeenCalledTimes(2)); expect(vi.mocked(pptApi.reply).mock.calls[1]![2].confirmed).toBe(true)
  })
  it('preserves ordinary property dirty drafts and pauses invalid autosave with exact baseline', async () => {
    studioFixture(); const p = page(); await screen.findByRole('heading', { name: '季度汇报' }); await manual(); const object = document.querySelector<HTMLButtonElement>('.ppt-canvas-object')!; fireEvent.click(object); await screen.findByLabelText('宽度'); fireEvent.change(screen.getByLabelText('宽度'), { target: { value: '0' } }); expect(p.leave()?.kind).toBe('CONFIRM_DISCARD'); const finish = screen.getByRole('button', { name: semanticName('ui.cancelEditing', '结束手动编辑') }); expect((finish as HTMLButtonElement).disabled).toBe(true); fireEvent.click(finish); expect(document.querySelector('[data-canvas-kind="ppt"]')).toBeTruthy(); expect(screen.getByLabelText('宽度')).toBeTruthy(); await act(async () => { await new Promise(resolve => setTimeout(resolve, 950)) }); expect(pptApi.operations).not.toHaveBeenCalled(); expect(JSON.parse(sessionStorage.getItem('loopper.ppt.element.doc.text-1')!).draft.width).toBe(0); click('ui.next', '页面'); expect(screen.getAllByRole('alert').some(alert => alert.textContent?.includes('输入尚未保存'))).toBe(true); expect(screen.getByRole('heading', { name: '核心成果' })).toBeTruthy()
  })
  it('allows restored unknown property drafts to be viewed while preserving write, finish and leave guards', async () => {
    const f = studioFixture(), element = { ...pptText(), text: '等待回执的草稿' }
    const operations = [{ op: 'update_element', slideId: 'slide-1', elementId: 'text-1', patch: { text: element.text } }]
    const pending = { kind: 'operations', key: 'original-key', revision: 3, payload: { operations } }
    const draft = { draft: element, baseline: JSON.stringify(pptText()), revision: 3 }
    sessionStorage.setItem('loopper.ppt.pending.doc', JSON.stringify(pending))
    sessionStorage.setItem('loopper.ppt.element.doc.text-1', JSON.stringify(draft))
    const p = page(); await screen.findByRole('heading', { name: '季度汇报' })
    const root = document.querySelector('[data-ppt-document]'), stream = f.events[0]
    expect(screen.getByRole('button', { name: semanticName('ppt.editObject', '手动编辑') }).hasAttribute('disabled')).toBe(false)
    await manual(); const object = document.querySelector<HTMLButtonElement>('.ppt-canvas-object')!; fireEvent.click(object)
    const input = await screen.findByLabelText('文字')
    expect((input as HTMLTextAreaElement).value).toBe(element.text); expect(input.matches(':disabled')).toBe(true)
    const finish = screen.getByRole('button', { name: semanticName('ui.cancelEditing', '结束手动编辑') })
    expect(finish.hasAttribute('disabled')).toBe(true); fireEvent.click(finish); fireEvent.keyDown(object, { key: 'ArrowRight' })
    expect(screen.getByRole('button', { name: semanticName('ui.save', '对象属性') }).hasAttribute('disabled')).toBe(true)
    expect(screen.getByRole('button', { name: semanticName('ppt.removeObject') }).hasAttribute('disabled')).toBe(true)
    expect(p.leave()?.kind).toBe('BLOCK'); expect(document.querySelector('[data-ppt-document]')).toBe(root)
    await act(async () => { await new Promise(resolve => setTimeout(resolve, 1200)) })
    expect(f.operations).not.toHaveBeenCalled(); expect(pptApi.createJob).not.toHaveBeenCalled()
    expect(f.events).toHaveLength(1); expect(stream?.close).not.toHaveBeenCalled()
    expect(JSON.parse(sessionStorage.getItem('loopper.ppt.pending.doc')!)).toMatchObject(pending)
    expect(JSON.parse(sessionStorage.getItem('loopper.ppt.element.doc.text-1')!)).toEqual(draft)
    click('receipt.retryOriginal'); await waitFor(() => expect(p.leave()?.kind).toBe('ALLOW'))
    expect(f.operations).toHaveBeenCalledTimes(1); expect(f.operations.mock.calls[0]).toEqual(['doc', 3, operations, 'original-key'])
    expect((screen.getByLabelText('文字') as HTMLTextAreaElement).value).toBe(element.text)
    expect(screen.getByLabelText('文字').matches(':disabled')).toBe(false)
    expect(screen.getByRole('button', { name: semanticName('ui.cancelEditing', '结束手动编辑') }).hasAttribute('disabled')).toBe(false)
    click('ui.cancelEditing', '结束手动编辑'); expect(document.querySelector('[data-ppt-document]')).toBe(root)
    expect(f.operations).toHaveBeenCalledTimes(1)
  })
  it('uses the real canvas keyboard callback and blocks further input after an unknown result', async () => {
    const f = studioFixture(); f.operations.mockRejectedValueOnce(new Error('lost')); const p = page(); await screen.findByRole('heading', { name: '季度汇报' }); await manual(); const object = document.querySelector<HTMLButtonElement>('.ppt-canvas-object')!; fireEvent.click(object); fireEvent.keyDown(object, { key: 'ArrowRight' }); await waitFor(() => expect(document.querySelector('[data-operation-phase="UNKNOWN"]')).toBeTruthy()); expect(f.operations.mock.calls[0]).toEqual(['doc', 3, [{ op: 'update_element', slideId: 'slide-1', elementId: 'text-1', patch: { x: 81, y: 70 } }], expect.any(String)]); expect(p.leave()?.kind).toBe('BLOCK'); fireEvent.keyDown(object, { key: 'ArrowRight' }); expect(f.operations).toHaveBeenCalledTimes(1); click('receipt.retryOriginal'); await waitFor(() => expect(p.leave()?.kind).toBe('ALLOW')); expect(f.operations.mock.calls[1]).toEqual(f.operations.mock.calls[0])
  })
  it('keeps the native object menu mounted through pointerdown before the click writes once', async () => {
    const f = studioFixture(); page(); await screen.findByRole('heading', { name: '季度汇报' }); await manual(); click('ppt.addObject'); fireEvent.keyDown(screen.getByRole('button', { name: semanticName('ppt.addObject', '文本框') }), { key: 'Escape' }); expect(screen.queryByRole('button', { name: semanticName('ppt.addObject', '文本框') })).toBeNull(); expect(f.operations).not.toHaveBeenCalled(); click('ppt.addObject'); const text = screen.getByRole('button', { name: semanticName('ppt.addObject', '文本框') }); fireEvent.pointerDown(text); expect(text.isConnected).toBe(true); fireEvent.click(text); await waitFor(() => expect(f.operations).toHaveBeenCalledOnce()); expect(f.operations.mock.calls[0]![2]).toEqual([expect.objectContaining({ op: 'add_element', slideId: 'slide-1', element: expect.objectContaining({ type: 'text' }) })])
  })
  it('preserves unknown body and DOM across three in-place theme changes without extra subscriptions or writes', async () => {
    studioFixture(); vi.mocked(pptApi.send).mockRejectedValueOnce(new Error('lost')); const p = page(); await screen.findByRole('heading', { name: '季度汇报' }); click('ui.open', 'PPT 助手'); const input = screen.getByLabelText('向 PPT 助手发送要求'); fireEvent.change(input, { target: { value: '冻结正文' } }); click('ppt.send'); await screen.findByText('原操作结果待核对'); const root = document.querySelector('[data-ppt-document]'), original = vi.mocked(pptApi.send).mock.calls[0]; for (const skin of skins) p.view.rerender(<FoundationProvider skin={skin} reducedMotion><PptStudioPage {...p.props} skin={skin} /></FoundationProvider>); expect(document.querySelector('[data-ppt-document]')).toBe(root); expect(screen.getByLabelText('向 PPT 助手发送要求')).toBe(input); expect((input as HTMLTextAreaElement).value).toBe('冻结正文'); expect(pptApi.events).toHaveBeenCalledTimes(1); expect(pptApi.send).toHaveBeenCalledTimes(1); expect(vi.mocked(pptApi.send).mock.calls[0]).toEqual(original); expect(p.leave()?.kind).toBe('BLOCK')
  })
  it('restores unknown state without autosave and exposes explicit read-only recovery after accepted failure', async () => {
    const f = studioFixture(); f.get.mockResolvedValueOnce({ id: 'doc', title: '季度汇报', projectId: null, model: 'local/model', phase: 'REVIEW', revision: 3, version: 3, archived: false, createdAt: '', updatedAt: '' }); page(); await screen.findByRole('heading', { name: '季度汇报' }); await manual(); f.get.mockRejectedValueOnce(new Error('read offline')); const object = document.querySelector<HTMLButtonElement>('.ppt-canvas-object')!; fireEvent.click(object); fireEvent.keyDown(object, { key: 'ArrowRight' }); await screen.findByText('原写入已接受，等待读取结果'); click('receipt.readOriginal'); await waitFor(() => expect(document.querySelector('[data-operation-phase]')).toBeNull()); expect(f.operations).toHaveBeenCalledTimes(1)
  })
  it('shows current-only checks and keeps historic scenes readonly while preserving current version', async () => {
    studioFixture(); page(); await screen.findByRole('heading', { name: '季度汇报' }); click('ui.open', '作品菜单'); click('ppt.openHistory'); const detail = screen.getByRole('complementary', { name: '版本与导出' }); fireEvent.click(within(detail).getByRole('button', { name: semanticName('ui.open', '版本 3') })); await screen.findByText(/正在查看历史版本 3/); expect(pptApi.deck).toHaveBeenCalledWith('doc', 3); expect(screen.getByRole('button', { name: semanticName('ppt.editObject', '手动编辑') }).hasAttribute('disabled')).toBe(true); click('ui.open', '回到当前草稿'); await waitFor(() => expect(screen.queryByText(/正在查看历史版本 3/)).toBeNull())
  })
  it('uses the archive semantic confirmation and defaults to Stay without issuing a write', async () => {
    studioFixture(); page(); await screen.findByRole('heading', { name: '季度汇报' }); click('ui.open', '作品菜单'); click('ppt.archive'); const dialog = await screen.findByRole('dialog'); expect(within(dialog).getByRole('button', { name: semanticName('ppt.archive') })).toBeTruthy(); expect(within(dialog).queryByRole('button', { name: semanticName('ui.delete') })).toBeNull(); fireEvent.click(within(dialog).getByRole('button', { name: semanticName('ui.stay') })); expect(pptApi.action).not.toHaveBeenCalled()
  })
  it('retains real question thinking/tool disclosure and sanitizes assistant/source Markdown', async () => {
    const f = studioFixture(); f.setDocument({ phase: 'BRIEFING' }); vi.mocked(pptApi.messages).mockResolvedValue({ items: [{ ...message(), answer: '<think>检查资料</think>## 完成设计\n<script>bad()</script>\n[危险](javascript:alert(1))', calls: [{ id: 'call', tool: 'ppt_check_layout', state: 'RUNNING', detail: '第一版' }], state: 'RUNNING' }], facets: {} }); page(); await screen.findByText('完成设计'); const thinking = screen.getByLabelText('思考'); expect(thinking.textContent).toContain('检查资料'); expect(screen.getByLabelText('工具调用').textContent).toContain('检查页面布局'); expect(document.querySelector('.w3-ppt-chat script')).toBeNull(); expect(document.querySelector('.w3-ppt-chat a[href^="javascript:"]')).toBeNull(); expect(screen.queryByText('正在思考…')).toBeNull()
  })
  it('keeps chat history scroll and can explicitly return to newest messages', async () => {
    const f = studioFixture(); vi.mocked(pptApi.messages).mockResolvedValue({ items: [message('one')], facets: {} }); page(); await screen.findByRole('heading', { name: '季度汇报' }); click('ui.open', 'PPT 助手'); const timeline = document.querySelector<HTMLElement>('.ppt-chat-timeline')!; Object.defineProperty(timeline, 'scrollHeight', { configurable: true, value: 1200 }); Object.defineProperty(timeline, 'clientHeight', { configurable: true, value: 200 }); timeline.scrollTop = 100; fireEvent.scroll(timeline); vi.mocked(pptApi.messages).mockResolvedValue({ items: [message('one', '讨论内容'), { ...message('two'), answer: '新的回复' }], facets: {} }); await act(async () => { f.events[0]!.onmessage?.(new MessageEvent('message')); await Promise.resolve() }); await screen.findByText('新的回复'); expect(timeline.scrollTop).toBe(100); click('ui.next', '回到最新回复'); expect(timeline.scrollTop).toBe(1200)
  })
  it('cancels the owned assistant resize capture immediately on jsdom window blur (not trusted browser evidence) and root unmount', async () => {
    studioFixture(); const p = page(); await screen.findByRole('heading', { name: '季度汇报' }); await manual(); const separator = screen.getByRole('separator', { name: '调整助手宽度' }); let captured = false; Object.defineProperties(separator, { setPointerCapture: { value: () => { captured = true } }, hasPointerCapture: { value: () => captured }, releasePointerCapture: { value: vi.fn(() => { captured = false }) } }); const down = new MouseEvent('pointerdown', { bubbles: true, button: 0, clientX: 600 }); Object.defineProperty(down, 'pointerId', { value: 7 }); fireEvent(separator, down); expect(captured).toBe(true); fireEvent(window, new FocusEvent('blur')); expect(captured).toBe(false); fireEvent(separator, down); expect(captured).toBe(true); p.view.unmount(); p.retire(); expect(captured).toBe(false); expect(separator.releasePointerCapture).toHaveBeenCalledTimes(2)
  })
})
