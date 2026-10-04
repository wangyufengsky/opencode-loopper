import { afterEach, expect, it, vi } from 'vitest'
import { api, ApiError } from '@/api/client'
import { createDesignerController } from '@/pages/w5/designer/controller'
import { mockDesigner, pageProps } from '@/pages/w5/designer/test-support'
import { flushPromises } from './render'

vi.mock('@/api/client', async original => ({ ...await original<typeof import('@/api/client')>(), subscribeDesignerEvents: vi.fn() }))
const owners: ReturnType<typeof createDesignerController>[] = []
afterEach(() => { owners.splice(0).forEach(owner => owner.retire(true)); sessionStorage.clear() })

it('B independent initial attachment keeps partial-session 400 identity and only replays the explicit original request', async () => {
  sessionStorage.clear()
  mockDesigner()
  const submit = vi.mocked(api.createDesignerContextTurn)
    .mockRejectedValueOnce(new ApiError('附件累计超过 50 MiB，原会话可能已建立', 400, { code: 'ATTACHMENT_SESSION_TOO_LARGE' }))
    .mockRejectedValueOnce(new ApiError('已有设计会话，初始附件交接尚未完成', 409, { code: 'ATTACHMENT_INITIAL_SUBMISSION_INCOMPLETE' }))
  const owner = createDesignerController({ navigation: pageProps().props.navigation })
  owners.push(owner)
  owner.attachView()
  await flushPromises()
  const file = new File(['original bytes'], 'context.txt', { type: 'text/plain' })
  owner.setPrompt('原附件目标')
  owner.stageFiles([file])
  await owner.initialSubmit()
  expect(submit).toHaveBeenCalledTimes(1)
  const original = submit.mock.calls[0]!
  expect(original[0].submissionId).toBeTruthy()
  expect(original[1][0]).toBe(file)
  expect(owner.getSnapshot().command.phase).toBe('UNKNOWN')
  expect(owner.canLeave().kind).toBe('BLOCK')
  owner.setPrompt('后来编辑的目标')
  owner.stageFiles([new File(['new bytes'], 'context.txt', { type: 'text/plain' })])
  await flushPromises()
  expect(submit).toHaveBeenCalledTimes(1)
  await owner.initialSubmit()
  expect(submit).toHaveBeenCalledTimes(2)
  expect(submit.mock.calls[1]![0]).toEqual(original[0])
  expect(submit.mock.calls[1]![1]).toHaveLength(1)
  expect(submit.mock.calls[1]![1][0]).toBe(file)
  expect(api.createDraft).toHaveBeenCalledTimes(1)
  expect(owner.getSnapshot().command.phase).toBe('UNKNOWN')
  expect(owner.canLeave().kind).toBe('BLOCK')
})
