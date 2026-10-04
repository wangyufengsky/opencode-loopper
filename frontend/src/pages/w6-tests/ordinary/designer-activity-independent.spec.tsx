import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSyncExternalStore } from 'react'
import { api } from '@/api/client'
import { createDesignerController } from '@/pages/w5/designer/controller'
import { mockDesigner, pageProps, session } from '@/pages/w5/designer/test-support'
import { Discussion } from '@/pages/w5/designer/Discussion'
import type { SkinDefinition } from '@/themes/types'
import { mount, flushPromises } from './render'

vi.mock('@/api/client', async original => ({ ...await original<typeof import('@/api/client')>(), subscribeDesignerEvents: vi.fn() }))
const owners: ReturnType<typeof createDesignerController>[] = []
afterEach(() => { owners.splice(0).forEach(owner => owner.retire(true)) })
function ActivityFrame({ owner, skin }: { owner: ReturnType<typeof createDesignerController>; skin: SkinDefinition }) {
  const state = useSyncExternalStore(owner.subscribe, owner.getSnapshot, owner.getSnapshot)
  return <Discussion owner={owner} state={state} skin={skin} stopAttachment={() => {}} />
}
describe('B independent initial Designer activity read', () => {
  it('shows the actual first activity failure before any activity DTO exists', async () => {
    mockDesigner(session('designer-initial-failure', { state: 'RUNNING', activeActor: 'DESIGNER' }))
    const activity = vi.spyOn(api, 'getDesignerActivity').mockRejectedValue(new Error('connection reset'))
    const owner = createDesignerController({ sessionId: 'designer-initial-failure', navigation: pageProps().props.navigation })
    owners.push(owner)
    owner.attachView()
    const view = mount(ActivityFrame, { props: { owner } })
    await flushPromises()
    expect(activity).toHaveBeenCalledWith('designer-initial-failure')
    expect(owner.getSnapshot().activity).toBeUndefined()
    expect(owner.getSnapshot().activityError).toContain('当前角色活动暂时无法刷新')
    expect(view.text()).toContain('当前角色活动暂时无法刷新')
  })
})
