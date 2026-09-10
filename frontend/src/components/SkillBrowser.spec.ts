import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { SkillDocument, SkillInventory } from '@/types/domain'
import SkillBrowser from './SkillBrowser.vue'

const skill = { name: 'review', description: '检查代码行为', location: '/skills/review/SKILL.md' }
const inventory = { skills: [skill], complete: true, checkedAt: '2026-09-10T00:00:00Z' }
const mountBrowser = () => mount(SkillBrowser, { props: { projectId: '' }, global: { plugins: [ElementPlus], stubs: { Icon: true } } })
afterEach(() => vi.restoreAllMocks())

describe('Skill 浏览器', () => {
  it('按需读取 Markdown，可切换完整源文且不执行 HTML', async () => {
    vi.spyOn(api, 'getSkills').mockResolvedValue(inventory)
    const content = '# 使用说明\n\n**检查行为**\n\n<script>alert(1)</script>'
    const read = vi.spyOn(api, 'getSkillDocument').mockResolvedValue({ ...skill, content })
    const wrapper = mountBrowser(); await flushPromises()
    expect(read).not.toHaveBeenCalled()
    await wrapper.get('.skill-card').trigger('click'); await flushPromises()
    expect(read).toHaveBeenCalledWith('', 'review')
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.get('.markdown-output').text()).toContain('使用说明')
    const toggle = wrapper.findAll('button').find(button => button.text() === '查看 Markdown 源文')!
    await toggle.trigger('click')
    expect(wrapper.get('.skill-source').text()).toBe(content)
    expect(wrapper.find('script').exists()).toBe(false)
    await wrapper.get('input[aria-label="搜索 Skill"]').setValue('不存在')
    expect(wrapper.text()).toContain('没有匹配的 Skill')
    wrapper.unmount()
  })

  it('清空旧项目文档并丢弃迟到的列表和文档', async () => {
    let finishList!: (value: SkillInventory) => void
    const readList = vi.spyOn(api, 'getSkills').mockResolvedValueOnce(inventory)
      .mockImplementationOnce(() => new Promise(resolve => { finishList = resolve }))
      .mockResolvedValueOnce({ skills: [], complete: true, checkedAt: '' })
    let finishDocument!: (value: SkillDocument) => void
    vi.spyOn(api, 'getSkillDocument').mockImplementation(() => new Promise(resolve => { finishDocument = resolve }))
    const wrapper = mountBrowser(); await flushPromises()
    await wrapper.get('.skill-card').trigger('click')
    await wrapper.setProps({ projectId: 'second' }); await flushPromises()
    await wrapper.setProps({ projectId: 'third' }); await flushPromises()
    finishList(inventory); finishDocument({ ...skill, content: '旧项目文档' }); await flushPromises()
    expect(readList).toHaveBeenLastCalledWith('third')
    expect(wrapper.text()).toContain('当前范围暂无 Skill')
    expect(wrapper.text()).not.toContain('旧项目文档')
    expect(wrapper.find('.skill-card').exists()).toBe(false)
    wrapper.unmount()
  })

  it('列表与文档失败均可重试，不把读取失败当成空清单', async () => {
    vi.spyOn(api, 'getSkills').mockRejectedValueOnce(new Error('读取失败')).mockResolvedValue(inventory)
    vi.spyOn(api, 'getSkillDocument').mockRejectedValueOnce(new Error('文档读取失败')).mockResolvedValue({ ...skill, content: '# 重试成功' })
    const wrapper = mountBrowser(); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('读取失败')
    expect(wrapper.text()).not.toContain('当前范围暂无 Skill')
    await wrapper.get('[role="alert"] button').trigger('click'); await flushPromises()
    await wrapper.get('.skill-card').trigger('click'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('文档读取失败')
    await wrapper.get('[role="alert"] button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('重试成功')
    wrapper.unmount()
  })
})
