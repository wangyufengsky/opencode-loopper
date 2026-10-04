import { mount } from '@/pages/w6-tests/workflow/react-test-root'
import { describe, expect, it } from 'vitest'
import { WorkflowSourceDesignReport as WorkflowSourceDesignReport } from '@/pages/w5/workflow/reports'

const references = [{ path: 'src/Main.java', sha256: 'private-hash', startLine: 1, endLine: 2, quote: '<img src=x onerror=alert(1)>' }]
const design = { title: '订单模块设计', summary: '基于冻结源码说明职责', sections: [{ key: 'main', title: '核心流程', markdown: '**事务边界**\n\n<img src=x>\n\n![外部图片](https://invalid.example/image)', paths: ['src/Main.java'], references }], limitations: ['外部依赖行为待确认'] }
const review = { verdict: 'PASS', reason: '设计与源码一致', checkedPaths: ['src/Main.java'], references, issues: [] }
describe('专业源码交付展示', () => {
  it('渲染章节和局限，引用默认折叠且不可执行 HTML 或加载图片', () => {
    const view = mount(WorkflowSourceDesignReport, { props: { content: design } })
    expect(view.get('h3').text()).toBe('订单模块设计'); expect(view.text()).toContain('外部依赖行为待确认')
    expect(view.find('img').exists()).toBe(false); expect(view.find('[onerror]').exists()).toBe(false)
    expect(view.get('.w3-code-text').text()).toBe(references[0]!.quote)
    expect(view.findAll('details').every(item => item.attributes('open') === undefined)).toBe(true)
    expect(view.text()).not.toContain('private-hash')
  })
  it('展示真实返修结论及问题，不显示复核通过', () => {
    const content = { ...review, verdict: 'REVISE', reason: '遗漏异常路径', issues: [{ sectionKey: 'main', detail: '<script>alert(1)</script>', recommendation: '补充异常回滚说明' }] }
    const view = mount(WorkflowSourceDesignReport, { props: { content, review: true } })
    expect(view.text()).toContain('需要返修'); expect(view.text()).toContain('补充异常回滚说明')
    expect(view.text()).not.toContain('复核通过'); expect(view.find('script').exists()).toBe(false)
  })
  it('展示独立复核的通过结果和覆盖范围', () => {
    const view = mount(WorkflowSourceDesignReport, { props: { content: review, review: true } })
    expect(view.text()).toContain('复核通过'); expect(view.text()).toContain('已复核 1 个源码文件')
  })
  it.each([null, {}, { ...review, issues: [{ sectionKey: 'main', detail: '问题', recommendation: '修订' }] }, { ...review, verdict: 'REVISE' }, { ...review, references: [] }, { ...review, checkedPaths: [] }])('矛盾或损坏的复核报告不显示通过', content => {
    const view = mount(WorkflowSourceDesignReport, { props: { content, review: true } })
    expect(view.text()).toContain('无法读取'); expect(view.text()).not.toContain('复核通过')
  })
})
