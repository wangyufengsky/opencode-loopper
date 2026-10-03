import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { resolveSkin } from '@/themes/registry'
import { MermaidDiagram } from './MermaidDiagram'

const mocks = vi.hoisted(() => ({ initialize: vi.fn(), render: vi.fn() }))
vi.mock('mermaid', () => ({ default: mocks }))
const skin = resolveSkin('tech-blue')
beforeEach(() => { mocks.render.mockReset(); mocks.render.mockResolvedValue({ svg: '<svg><foreignObject width="80" height="20"><div>安全标签<br><img src="x" onerror="alert(1)"></div></foreignObject><script>alert(1)</script></svg>' }) })
afterEach(cleanup)

describe('React 托管 Mermaid', () => {
  it('实际由 React 渲染安全 SVG，保留引擎支持的时序图语义', async () => {
    const source = 'sequenceDiagram\n Alice->>Bob: 你好'
    const { container } = render(<MermaidDiagram source={source} skin={skin} />)
    await waitFor(() => expect(container.querySelector('.react-mermaid-svg svg')).not.toBeNull())
    expect(container.querySelector('[data-canvas-runtime="react"][data-canvas-kind="mermaid"]')).not.toBeNull()
    expect(mocks.render).toHaveBeenCalledWith(expect.stringMatching(/^loopper-mermaid-/), source)
    expect(screen.getByText('安全标签')).toBeTruthy()
    expect(container.querySelector('foreignObject, script, [onerror]')).toBeNull()
  })
  it('只在安全 SVG 提交后绑定 Mermaid 原有交互', async () => {
    const bind = vi.fn((element: Element) => {
      expect(element.querySelector('svg')).not.toBeNull()
      expect(element.querySelector('foreignObject, [onerror]')).toBeNull()
    })
    mocks.render.mockResolvedValueOnce({ svg: '<svg><foreignObject><div onmouseover="alert(1)">标签</div></foreignObject></svg>', bindFunctions: bind })
    render(<MermaidDiagram source={'flowchart LR\n A --> B'} skin={skin} />)
    await waitFor(() => expect(bind).toHaveBeenCalledTimes(1))
  })

  it('主题或内容变化后的迟到结果不替换当前图，卸载后不回写', async () => {
    let release: ((value: { svg: string }) => void) | undefined
    mocks.render.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
    const { container, rerender, unmount } = render(<MermaidDiagram source={'flowchart LR\n A --> B'} skin={skin} />)
    await waitFor(() => expect(mocks.render).toHaveBeenCalledTimes(1))
    rerender(<MermaidDiagram source={'gantt\n title 当前图'} skin={skin} />)
    await act(async () => { release!({ svg: '<svg><text>过期图</text></svg>' }) })
    await waitFor(() => expect(container.querySelector('.react-mermaid-svg svg')).not.toBeNull())
    expect(container.textContent).not.toContain('过期图')
    expect(mocks.render.mock.calls.at(-1)?.[1]).toBe('gantt\n title 当前图')
    unmount()
    expect(container.innerHTML).toBe('')
  })

  it('解析失败显示中文恢复提示并清除引擎附着到页面的错误产物', async () => {
    mocks.render.mockImplementation(async (id: string) => {
      const residue = document.createElement('div'); residue.id = `d${id}`; residue.textContent = 'Syntax error in text'; document.body.append(residue)
      throw new Error('invalid source')
    })
    render(<MermaidDiagram source="invalid" skin={skin} />)
    expect(await screen.findByRole('status')).toBeTruthy()
    expect(screen.getByRole('status').textContent).toBe('流程图语法无法渲染，请检查 Mermaid 文本。')
    expect(document.querySelector('[id^="dloopper-mermaid-"]')).toBeNull()
    expect(document.body.textContent).not.toContain('Syntax error in text')
  })
  it('Mermaid 绑定失败也只显示中文图错误，不抛出组件异常', async () => {
    mocks.render.mockResolvedValueOnce({ svg: '<svg><text>绑定前图</text></svg>', bindFunctions: () => { throw new Error('binding failed') } })
    const { container } = render(<MermaidDiagram source="flowchart LR" skin={skin} />)
    expect((await screen.findByRole('status')).textContent).toBe('流程图语法无法渲染，请检查 Mermaid 文本。')
    expect(container.querySelector('svg')).toBeNull()
  })
  it('根 StrictMode 重放实际渲染 effect，旧渲染不绑定且卸载清除全部引擎产物', async () => {
    let release!: (value: { svg: string; bindFunctions: (element: Element) => void }) => void
    const obsoleteBind = vi.fn(), currentBind = vi.fn(), status = vi.fn()
    mocks.render.mockImplementationOnce((id: string) => {
      for (const prefix of ['', 'd', 'i']) {
        const residue = document.createElement('div'); residue.id = `${prefix}${id}`; document.body.append(residue)
      }
      return new Promise(resolve => { release = resolve })
    }).mockResolvedValue({ svg: '<svg><text>StrictMode 当前图</text></svg>', bindFunctions: currentBind })
    const { container, unmount } = render(<StrictMode><MermaidDiagram source={'flowchart LR\n A --> B'} skin={skin} onStatus={status} /></StrictMode>)
    await waitFor(() => expect(mocks.render).toHaveBeenCalledTimes(1))
    // Replayed setup queued a second render; the first effect has already been disposed.
    await act(async () => { release({ svg: '<svg><text>StrictMode 旧 effect 图</text></svg>', bindFunctions: obsoleteBind }) })
    await waitFor(() => expect(mocks.render).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(currentBind).toHaveBeenCalledTimes(1))
    expect(obsoleteBind).not.toHaveBeenCalled(); expect(container.textContent).not.toContain('旧 effect')
    expect(status.mock.calls.filter(([value]) => value === 'ready')).toHaveLength(1)
    expect(document.querySelector('[id^="dloopper-mermaid-"], [id^="iloopper-mermaid-"]')).toBeNull()
    unmount(); expect(container.innerHTML).toBe('')
    expect(document.querySelector('[id^="loopper-mermaid-"]')).toBeNull()
  })
  it('反复挂载卸载时迟到成功和失败不通知已卸载组件，也不保留渲染产物', async () => {
    for (const fail of [false, true]) {
      let release!: () => void
      const status = vi.fn(), bind = vi.fn()
      mocks.render.mockImplementationOnce((id: string) => new Promise((resolve, reject) => {
        release = () => {
          const residue = document.createElement('div'); residue.id = `d${id}`; document.body.append(residue)
          if (fail) reject(new Error('obsolete render failed'))
          else resolve({ svg: '<svg><text>卸载后结果</text></svg>', bindFunctions: bind })
        }
      }))
      const count = mocks.render.mock.calls.length, { container, unmount } = render(<MermaidDiagram source={`flowchart LR\n A --> ${fail ? 'C' : 'B'}`} skin={skin} onStatus={status} />)
      await waitFor(() => expect(mocks.render).toHaveBeenCalledTimes(count + 1))
      unmount(); const notified = status.mock.calls.length
      await act(async () => { release() })
      expect(status).toHaveBeenCalledTimes(notified); expect(bind).not.toHaveBeenCalled()
      expect(container.innerHTML).toBe(''); expect(document.querySelector('[id^="dloopper-mermaid-"]')).toBeNull()
    }
  })
})
