import { useSyncExternalStore } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { pptApi } from '@/api/ppt'
import { FoundationProvider } from '@/foundation/provider'
import { skins } from '@/themes/registry'
import { semanticName } from '@/foundation/semanticRegistry'
import { createPptStudioController } from './controller'
import { PptPlanEditor } from './Plan'
import { ArtifactDownload } from './Download'
import { studioFixture } from './testFixture'
const live: ReturnType<typeof createPptStudioController>[] = []
const originalObjectUrls = { create: Object.getOwnPropertyDescriptor(URL, 'createObjectURL'), revoke: Object.getOwnPropertyDescriptor(URL, 'revokeObjectURL') }
beforeEach(() => { sessionStorage.clear(); vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() })) })
afterEach(() => { cleanup(); live.forEach(owner => owner.retire()); live.length = 0; vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); for (const [key, descriptor] of [['createObjectURL', originalObjectUrls.create], ['revokeObjectURL', originalObjectUrls.revoke]] as const) { if (descriptor) Object.defineProperty(URL, key, descriptor); else Reflect.deleteProperty(URL, key) } })
function Plan({ owner, confirm = (_title, action) => action() }: { owner: ReturnType<typeof createPptStudioController>; confirm?: React.ComponentProps<typeof PptPlanEditor>['confirm'] }) { const state = useSyncExternalStore(owner.subscribe, owner.getSnapshot); return <FoundationProvider skin={skins[0]!} reducedMotion><PptPlanEditor owner={owner} state={state} confirm={confirm} /></FoundationProvider> }
describe('PPT full React plan and artifact editors', () => {
  it('exposes all seven complete plan modules without issuing writes from opening tabs', async () => {
    const f = studioFixture(); f.setDocument({ phase: 'DESIGN' }); const owner = createPptStudioController('doc'); live.push(owner); await owner.start(); const confirm = vi.fn(); render(<Plan owner={owner} confirm={confirm} />)
    expect((await screen.findByLabelText('受众') as HTMLInputElement).value).toBe('管理层')
    for (const label of ['叙事结构', '页面内容', '视觉规范', '图表素材', '演讲辅助', '交付设置']) { fireEvent.click(screen.getByRole('tab', { name: label })); if (label === '页面内容') { fireEvent.click(screen.getByRole('button', { name: semanticName('ppt.deleteSlide') })); expect(confirm).toHaveBeenCalledWith('移除这页设计？历史版本仍会保留。', expect.any(Function), 'ppt.deleteSlide') } }
    expect(screen.getByLabelText('目标办公软件')).toBeTruthy(); expect(screen.getByText('正式导出为可编辑 PPTX，页面预览为 PNG。')).toBeTruthy(); expect(pptApi.savePlan).not.toHaveBeenCalled(); expect(pptApi.createJob).not.toHaveBeenCalled()
  })
  it('preserves stale plan input while a changed server revision prevents autosave', async () => {
    const f = studioFixture(); f.setDocument({ phase: 'DIRECTION' }); const owner = createPptStudioController('doc'); live.push(owner); await owner.start(); render(<Plan owner={owner} />); fireEvent.change(await screen.findByLabelText('受众'), { target: { value: '本地草稿' } }); f.setDocument({ revision: 4 }); await act(async () => { await owner.refresh() }); expect((screen.getByLabelText('受众') as HTMLInputElement).value).toBe('本地草稿'); await act(async () => { await new Promise(resolve => setTimeout(resolve, 1000)) }); expect(pptApi.savePlan).not.toHaveBeenCalled(); expect(screen.getByRole('alert').textContent).toContain('自动保存已暂停'); fireEvent.click(screen.getByRole('button', { name: semanticName('ui.refresh', '读取最新方案') })); expect((screen.getByLabelText('受众') as HTMLInputElement).value).toBe('管理层')
  })
  it('pauses a pending autosave when its editor is closed without discarding the draft', async () => {
    const f = studioFixture(); f.setDocument({ phase: 'DIRECTION' }); const owner = createPptStudioController('doc'); live.push(owner); await owner.start(); const view = render(<Plan owner={owner} />); fireEvent.change(await screen.findByLabelText('受众'), { target: { value: '仍保留的输入' } }); view.unmount(); await new Promise(resolve => setTimeout(resolve, 1000)); expect(pptApi.savePlan).not.toHaveBeenCalled(); expect(owner.canLeave().kind).toBe('CONFIRM_DISCARD'); expect(sessionStorage.getItem('loopper.ppt.plan.doc')).toContain('仍保留的输入')
  })
  it('downloads only the document-owned artifact URL and preserves the existing file after transport failure', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: false }); vi.stubGlobal('fetch', fetch); render(<FoundationProvider skin={skins[0]!} reducedMotion><ArtifactDownload documentId="doc" artifact={{ id: 'export', name: '季度汇报.pptx', mediaType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', url: 'https://untrusted.invalid/file' }} /></FoundationProvider>); const link = screen.getByRole('link', { name: '季度汇报.pptx' }); expect(link.getAttribute('href')).toBe('/api/ppt/documents/doc/artifacts/export'); fireEvent.click(link); await screen.findByRole('alert'); expect(screen.getByRole('alert').textContent).toContain('已有导出仍会保留'); fireEvent.click(screen.getByRole('button', { name: semanticName('ui.retry', '下载') })); await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2)); expect(fetch.mock.calls[0]?.[0]).toBe('/api/ppt/documents/doc/artifacts/export')
  })
  it('cancels fetch and revokes owned Blob URLs and their timers immediately on unmount', async () => {
    vi.useFakeTimers(); const fetch = vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['pptx']) }); vi.stubGlobal('fetch', fetch); const create = vi.fn(() => 'blob:owned'), revoke = vi.fn(); Object.defineProperties(URL, { createObjectURL: { configurable: true, value: create }, revokeObjectURL: { configurable: true, value: revoke } }); vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {}); const view = render(<FoundationProvider skin={skins[0]!} reducedMotion><ArtifactDownload documentId="doc" artifact={{ id: 'export', name: '汇报.pptx', mediaType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', url: '' }} /></FoundationProvider>); await act(async () => { fireEvent.click(screen.getByRole('link')); await Promise.resolve(); await Promise.resolve() }); expect(create).toHaveBeenCalledOnce(); view.unmount(); expect(revoke).toHaveBeenCalledWith('blob:owned'); await vi.advanceTimersByTimeAsync(30000); expect(revoke).toHaveBeenCalledTimes(1)
  })
})
