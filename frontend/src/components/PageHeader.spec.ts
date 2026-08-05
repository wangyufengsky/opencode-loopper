import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PageHeader from '@/components/PageHeader.vue'

describe('PageHeader', () => {
  it('shows only the concise title and exposes the full introduction on hover', () => {
    const wrapper = mount(PageHeader, {
      props: {
        eyebrow: '任务 / 检视',
        title: '新增转账交易责任链',
        titleTooltip: '新增转账交易责任链，包含参数检查、风控、转账、通知和完整测试。',
      },
    })

    const title = wrapper.get('h1')
    expect(title.text()).toBe('新增转账交易责任链')
    expect(title.attributes('title')).toBe('新增转账交易责任链，包含参数检查、风控、转账、通知和完整测试。')
    expect(title.classes()).toContain('page-title-tooltip')
    expect(wrapper.find('.page-subtitle').exists()).toBe(false)
  })

  it('keeps the standard subtitle for other pages', () => {
    const wrapper = mount(PageHeader, {
      props: { eyebrow: 'System', title: '运行环境', subtitle: '查看本机运行时状态。' },
    })

    expect(wrapper.get('.page-subtitle').text()).toBe('查看本机运行时状态。')
    expect(wrapper.get('h1').attributes('title')).toBeUndefined()
  })
})
