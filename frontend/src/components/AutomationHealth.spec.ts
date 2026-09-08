import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import AutomationHealth from './AutomationHealth.vue'

it('distinguishes never checked, failure and recovered detection from rule enablement', async () => {
  const wrapper = mount(AutomationHealth, { props: { enabled: true, scheduled: true } })
  expect(wrapper.text()).toBe('等待首次检测')
  await wrapper.setProps({ health: { status: 'FAILED', lastCheckedAt: '2026-09-08T00:00:00Z', lastSuccessAt: '2026-09-07T00:00:00Z', consecutiveFailures: 3, errorMessage: 'Git 检查超时，请检查项目目录后刷新。' } })
  expect(wrapper.text()).toContain('检测失败 · 连续 3 次')
  expect(wrapper.text()).toContain('最近成功')
  expect(wrapper.text()).toContain('请检查项目目录')
  await wrapper.setProps({ health: { status: 'CHECKED', lastCheckedAt: '2026-09-08T01:00:00Z', consecutiveFailures: 0 } })
  expect(wrapper.text()).toContain('检测正常')
  expect(wrapper.text()).not.toContain('检查超时')
  wrapper.unmount()
})
