import { useState } from 'react'
import { cleanup, fireEvent, render } from '@testing-library/react'
import { foundationDOM } from '@/pages/w2/workflow/page.test-support'
import { LoopSpecEditor } from '@/pages/w5/designer/LoopSpecEditor'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { createDesignerController } from '@/pages/w5/designer/controller'
import { mockDesigner, session, pageProps, flush, frame, spec } from '@/pages/w5/designer/test-support'
vi.mock('@/api/client', async original => ({ ...await original<typeof import('@/api/client')>(), subscribeDesignerEvents: vi.fn() }))
const releases: (() => void)[] = []
beforeEach(() => { sessionStorage.clear() })
afterEach(() => { cleanup(); for (const release of releases.splice(0)) release(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
async function mounted(initial = false) { const mock = mockDesigner(), owner = createDesignerController({ navigation: pageProps().props.navigation, historyOnly: !initial, sessionId: initial ? undefined : 'A' }); const detach = owner.attachView(); releases.push(() => { detach(); owner.retire(true) }); await flush(); return { owner, mock } }
it.each(['project', 'draft'] as const)('independent: initial fulfilled %s foreign receipt keeps accepted blocked and original File unconsumed', async dimension => {
  const { owner, mock } = await mounted(true), file = new File(['original-byte-content'], 'original.txt'); owner.setPrompt('原项目目标'); owner.stageFiles([file])
  const value = session('foreign-session', dimension === 'project' ? { projectId: 'foreign-project' } : { draft: { ...session().draft!, id: 'foreign-draft' } }); vi.mocked(api.createDesignerContextTurn).mockResolvedValue(value)
  await owner.initialSubmit(); console.info('FOREIGN_RECEIPT_PROOF', JSON.stringify({ dimension, phase: owner.getSnapshot().command.phase, project: owner.getSnapshot().session?.projectId, sessionDraft: owner.getSnapshot().session?.draft?.id, initialFileCount: owner.fileRefs().initial.length, followupFileCount: owner.fileRefs().followup.length, streamIds: mock.streams.map(stream => stream.id) })); expect(api.createDesignerContextTurn).toHaveBeenCalledTimes(1); expect(vi.mocked(api.createDesignerContextTurn).mock.calls[0]![1][0]).toBe(file)
  expect(owner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK'); expect(owner.getSnapshot().session).toBeUndefined(); expect(owner.fileRefs().initial).toEqual([file]); expect(owner.canLeave().kind).toBe('BLOCK'); expect(mock.streams).toHaveLength(0)
  await owner.recover(); expect(api.createDesignerContextTurn).toHaveBeenCalledTimes(1); expect(owner.fileRefs().initial[0]).toBe(file)
})
it('independent: foreign followup accepted receipt retains original File/message and recovery never repeats POST', async () => {
  const { owner } = await mounted(), file = new File(['original-byte-content'], 'original.txt'); owner.setMessage('原消息'); owner.stageFiles([file]); vi.mocked(api.sendDesignerContextTurn).mockResolvedValue({ sessionId: 'foreign-session', state: 'REVIEWING', persistedMessages: [], notice: '' }); await owner.send(); expect(owner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK'); expect(owner.fileRefs().followup[0]).toBe(file); expect(owner.getSnapshot().message).toBe('原消息'); expect(owner.getSnapshot().session?.id).toBe('A'); await owner.recover(); expect(api.sendDesignerContextTurn).toHaveBeenCalledTimes(1); expect(owner.canLeave().kind).toBe('BLOCK')
})

it.each([['每阶段最大尝试次数', 20], ['阶段 1 启动超时秒数', 300], ['阶段 1 停止超时秒数', 60]] as const)('independent: structured %s preserves its existing public numeric maximum %i', (label, maximum) => {
  foundationDOM(); const value = spec(); value.stages[0]!.verificationRuntime = { startCommand: ['run'], readiness: { path: '/health', expectedStatus: 200 }, startupTimeoutSeconds: 60, shutdownTimeoutSeconds: 10 }; let projected = value
  function Fixture() { const [source, change] = useState(JSON.stringify(value)); return <LoopSpecEditor source={source} onChange={source => { projected = JSON.parse(source); change(source) }} /> }
  const root = render(frame(<Fixture />)), input = root.getByRole('spinbutton', { name: label }); fireEvent.change(input, { target: { value: String(maximum + 1) } }); const actual = label === '每阶段最大尝试次数' ? projected.limits.maxStageAttempts : label.includes('启动') ? projected.stages[0]!.verificationRuntime!.startupTimeoutSeconds : projected.stages[0]!.verificationRuntime!.shutdownTimeoutSeconds; console.info('NUMERIC_BOUND_PROOF', JSON.stringify({ label, maximum, maxAttribute: input.getAttribute('max'), projected: actual })); expect(actual).toBeLessThanOrEqual(maximum); expect(input.getAttribute('max')).toBe(String(maximum))
})
