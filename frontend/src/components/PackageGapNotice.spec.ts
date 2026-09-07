import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PackageGapNotice from './PackageGapNotice.vue'

describe('PackageGapNotice', () => {
  it('keeps an unconfirmed claim distinct from a proven business decision', async () => {
    const wrapper = mount(PackageGapNotice, { props: { code: 'PACKAGE_GAP_UNCONFIRMED', detail: '来源尚未确认' } })
    expect(wrapper.text()).toContain('不足以确认缺少需求或能力')
    expect(wrapper.text()).not.toContain('请在本地反馈中明确不同选择')
    await wrapper.setProps({ code: 'PACKAGE_GAP_BUSINESS_DECISION' })
    expect(wrapper.text()).toContain('业务选择待确认')
    expect(wrapper.text()).toContain('不同选择对应的行为')
  })
  it('does not manufacture a classification for legacy or unknown codes', () => {
    expect(mount(PackageGapNotice, { props: { code: 'PACKAGE_DESIGN_NEEDS_INPUT' } }).find('aside').exists()).toBe(false)
  })
})
