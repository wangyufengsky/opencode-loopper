import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RolePromptView from '@/components/roles/RolePromptView.vue'
import { rolePrompt } from './rolePrompt'
import type { RoleRevision } from '@/types/domain'
const base: RoleRevision = { roleId: 'role', revisionId: 'revision', revisionNumber: 1, contentSha256: '', manifest: {}, promptFragments: {} }
describe('role prompt reading view', () => {
  it('joins complete static content in natural order and preserves JSON examples', () => {
    const result = rolePrompt({ ...base, promptFragments: { 'segment-10': '最后', 'segment-2': '{"answer": "{value}"}', 'segment-1': '开头' } })
    expect(result.text).toBe('开头\n\n{"answer": "{value}"}\n\n最后')
    expect(result.variables).toEqual([])
  })
  it('normalizes declared placeholders and describes them once', () => {
    const result = rolePrompt({ ...base, promptVariables: ['projectName', '{projectName}', 'context'],
      promptFragments: { main: '${projectName} / {{projectName}} / {{ context }} / {context} / {untouched}' } })
    expect(result.text).toBe('{projectName} / {projectName} / {context} / {context} / {untouched}')
    expect(result.variables).toEqual([{ name: 'projectName', description: '当前项目名称' }, { name: 'context', description: '当前工作流上下文' }])
  })
  it('recognizes explicit placeholders in built-in content when metadata is absent', () => {
    const result = rolePrompt({ ...base, promptVariables: [], promptFragments: { main: '端口 {{  LOOPPER_PORT  }}，项目 ${projectName}，JSON {\"value\":1}' } })
    expect(result.text).toBe('端口 {LOOPPER_PORT}，项目 {projectName}，JSON {\"value\":1}')
    expect(result.variables).toEqual([{ name: 'LOOPPER_PORT', description: '验证服务端口，由程序在验证时分配' }, { name: 'projectName', description: '当前项目名称' }])
  })
  it('hides absent variables and renders content safely without fragment controls', async () => {
    const wrapper = mount(RolePromptView, { props: { revision: { ...base, promptFragments: { main: '<script>unsafe</script>' } } } })
    expect(wrapper.find('dl').exists()).toBe(false)
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.get('pre').text()).toBe('<script>unsafe</script>')
    await wrapper.setProps({ revision: { ...base, promptFragments: { main: '${projectName}' }, promptVariables: ['projectName'] } })
    expect(wrapper.get('dl').text()).toContain('{projectName}当前项目名称')
    expect(wrapper.get('pre').text()).toBe('{projectName}')
  })
})
