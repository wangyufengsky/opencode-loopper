import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PageHeader from '@/components/PageHeader.vue'

describe('PageHeader', () => {
  it('shows the concise title with the full introduction in a custom tooltip', () => {
    const wrapper = mount(PageHeader, {
      props: {
        eyebrow: '任务 / 检视',
        title: '新增转账交易责任链',
        titleTooltip: '新增转账交易责任链，包含参数检查、风控、转账、通知和完整测试。',
      },
    })

    const title = wrapper.get('h1')
    expect(title.text()).toBe('新增转账交易责任链')
    expect(title.attributes('title')).toBeUndefined()
    expect(title.classes()).toContain('page-title-tooltip')
    expect(title.attributes('tabindex')).toBe('0')
    expect(title.attributes('aria-describedby')).toBe(wrapper.get('[role="tooltip"]').attributes('id'))
    expect(wrapper.get('[role="tooltip"]').text()).toBe('新增转账交易责任链，包含参数检查、风控、转账、通知和完整测试。')
  })

  it('renders ordinary page titles without descriptive copy or a tooltip', () => {
    const wrapper = mount(PageHeader, {
      props: { eyebrow: 'System', title: '运行环境' },
    })

    expect(wrapper.find('.page-subtitle').exists()).toBe(false)
    expect(wrapper.find('[role="tooltip"]').exists()).toBe(false)
    expect(wrapper.get('h1').attributes('title')).toBeUndefined()
    expect(wrapper.get('h1').attributes('tabindex')).toBeUndefined()
  })
})
