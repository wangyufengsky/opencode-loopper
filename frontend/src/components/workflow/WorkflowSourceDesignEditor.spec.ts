import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, resolveDialog } from '@/pages/w6-tests/workflow/react-test-root'
import type { WorkflowNode } from '@/types/domain'
import { newNode } from './graph'
import { WorkflowSourceDesignEditor as WorkflowSourceDesignEditor } from '@/pages/w5/workflow/NodeSpecialists'

const node = (parameters: Record<string, string> = {}): WorkflowNode => ({ ...newNode('free.readonly'), moduleId: 'source.design', parameters })
afterEach(() => vi.restoreAllMocks())
describe('专业源码批次范围', () => {
  it('保留路径空白、其他配置和原节点，并可恢复为全部适用目标', async () => {
    const original = node({ retained: 'keep' }), view = mount(WorkflowSourceDesignEditor, { props: { node: original } })
    await view.get('textarea').setValue('src/With Space.java\n src/Leading.java\n')
    const changed = view.emitted('change')!.at(-1)![0] as WorkflowNode
    expect(JSON.parse(changed.parameters.targetPaths!)).toEqual(['src/With Space.java', ' src/Leading.java'])
    expect(changed.parameters.retained).toBe('keep'); expect(original.parameters.targetPaths).toBeUndefined()
    await view.setProps({ node: changed }); await view.get('textarea').setValue('')
    expect((view.emitted('change')!.at(-1)![0] as WorkflowNode).parameters).toEqual({ retained: 'keep' })
  })
  it('无法解析的配置须明确确认才替换', async () => {

    const view = mount(WorkflowSourceDesignEditor, { props: { node: node({ targetPaths: '{broken' }) } })
    expect(view.get('[role=alert]').text()).toContain('原配置已保留')
    await view.get('button').trigger('click'); await resolveDialog(view, false); expect(view.emitted('change')).toBeUndefined()
    await view.get('button').trigger('click'); await resolveDialog(view, true)
    expect((view.emitted('change')![0]![0] as WorkflowNode).parameters.targetPaths).toBeUndefined()
  })
  it('锁定画布不可改变批次范围', async () => {
    const value = { ...node(), moduleId: 'source.design-review' }
    const view = mount(WorkflowSourceDesignEditor, { props: { node: value, disabled: true } })
    await view.get('textarea').setValue('src/Other.java'); expect(view.emitted('change')).toBeUndefined()
    expect(view.text()).toContain('其他模块需绑定相关设计稿')
  })
  it.each(['null', '{}', '[42]', '["a\\nb"]'])('不隐式丢失不支持的路径配置 %s', targetPaths => {
    const view = mount(WorkflowSourceDesignEditor, { props: { node: node({ targetPaths }) } })
    expect(view.find('[role=alert]').exists()).toBe(true); expect(view.emitted('change')).toBeUndefined()
  })
})
