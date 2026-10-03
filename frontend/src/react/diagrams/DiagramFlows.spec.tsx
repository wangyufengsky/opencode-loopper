import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RoleSlotBinding, Stage } from '@/types/domain'
import { projectStages } from '@/domain/diagrams/projections'
import { StageDiagram } from './StageDiagram'
import { RoleDiagram } from './RoleDiagram'
import { TemplateStepDiagram } from './TemplateStepDiagram'
afterEach(cleanup)

describe('真实 React Flow 只读图', () => {
  it('保留完整目标和权威尝试数，用真实边投影阶段序列，并响应新的状态', async () => {
    const stages: Stage[] = [{ id: 'a', ordinal: 1, objective: '完整阶段目标\n第二行目标也应保留', status: 'SUCCEEDED', attemptCount: 3, attempts: [] },
      { id: 'b', ordinal: 2, objective: '核对确定性验收与最终证据', status: 'RUNNING', attempts: [] }]
    const { container, rerender } = render(<StageDiagram stages={stages} />)
    expect(container.querySelector('[data-canvas-runtime="react"][data-canvas-kind="stages"]')).not.toBeNull()
    expect(container.querySelectorAll('.react-flow__node')).toHaveLength(2)
    expect(screen.getByText('3 次尝试')).toBeTruthy()
    expect(container.querySelector('.phase-objective p')?.textContent).toBe(stages[0]!.objective)
    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(1))
    expect(container.querySelector('.connector-active')).not.toBeNull()
    expect(container.querySelector('.react-flow__node.draggable')).toBeNull()
    rerender(<StageDiagram stages={[stages[0]!, { ...stages[1]!, status: 'SUCCEEDED', attemptCount: 1 }]} />)
    await waitFor(() => expect(container.querySelector('.connector-complete')).not.toBeNull())
    expect(screen.getByText('1 次尝试')).toBeTruthy()
    expect(container.textContent).not.toContain('SUCCEEDED')
  })

  it('按展示顺序连接现有阶段，不从顺序推断或更改业务依赖', () => {
    const stages: Stage[] = [{ id: 'later-id', ordinal: 4, objective: '保持已冻结阶段次序', status: 'PAUSED', attempts: [] },
      { id: 'earlier-id', ordinal: 8, objective: '恢复时保留原阶段', status: 'FAILED', attempts: [] }]
    const before = JSON.stringify(stages), graph = projectStages(stages)
    expect(graph.edges.map(edge => [edge.source, edge.target])).toEqual([['later-id', 'earlier-id']])
    expect(JSON.stringify(stages)).toBe(before)
  })

  it('显示当前角色负责阶段，并把准确绑定版本交给已有查看事件', async () => {
    const binding: RoleSlotBinding = { slot: 'PACKAGE_DESIGNER', profile: 'DEFAULT', activeRoleId: 'role', activeRevisionId: 'bound-revision', bindingVersion: 1, label: '任务设计' }
    const onRevision = vi.fn()
    const { container } = render(<RoleDiagram bindings={[binding]} latestRevisionId="newer-revision" onRevision={onRevision} />)
    expect(container.querySelector('[data-canvas-kind="roles"] .react-flow')).not.toBeNull()
    expect(container.querySelector('.workflow-track .current')?.textContent).toContain('细化阶段与验收')
    expect(screen.getByText('使用其他已发布版本')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '查看此阶段使用的版本' }))
    expect(onRevision).toHaveBeenCalledWith('bound-revision')
    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(2))
  })

  it('模板步骤保持 ACTIVE、INTERRUPTED 和 UNKNOWN 独立语义', async () => {
    const { container, rerender } = render(<TemplateStepDiagram steps={[{ key: 'prepare', label: '准备范围', state: 'COMPLETE' }, { key: 'review', label: '问题复核', state: 'ACTIVE' }, { key: 'old', label: '历史步骤', state: 'UNKNOWN' }]} />)
    expect(container.querySelector('[data-canvas-kind="template-progress"] .react-flow')).not.toBeNull()
    expect(container.querySelector('[aria-current="step"]')?.textContent).toContain('问题复核')
    expect(screen.getByText('历史记录不足')).toBeTruthy()
    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(2))
    rerender(<TemplateStepDiagram steps={[{ key: 'review', label: '问题复核', state: 'INTERRUPTED' }]} />)
    expect(container.querySelector('.template-step.interrupted')).not.toBeNull()
    expect(container.querySelector('[aria-current="step"]')).toBeNull()
    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(0))
  })

  it('长序列的后续阶段可通过键盘聚焦定位，保持只读并允许选择目标文字', async () => {
    const stages: Stage[] = Array.from({ length: 20 }, (_, index) => ({ id: `stage-${index}`, ordinal: index + 1, objective: `阶段 ${index + 1} 的完整目标`, status: 'PENDING', attempts: [] }))
    const { container } = render(<StageDiagram stages={stages} />)
    const last = container.querySelector<HTMLElement>('[data-id="stage-19"]')!
    const viewport = container.querySelector<HTMLElement>('.react-flow__viewport')!
    expect(last.tabIndex).toBe(0)
    expect(last.style.pointerEvents).toBe('all'); expect(last.style.userSelect).toBe('text')
    expect(last.classList.contains('nopan')).toBe(true)
    await waitFor(() => expect(last.style.visibility).toBe('visible'))
    const originalViewport = viewport.style.transform, originalPosition = last.style.transform
    last.focus(); fireEvent.focus(last)
    await waitFor(() => expect(viewport.style.transform).not.toBe(originalViewport))
    for (const key of ['ArrowRight', 'Enter', ' ', 'Delete']) fireEvent.keyDown(last, { key })
    expect(last.style.transform).toBe(originalPosition)
    expect(last.classList.contains('selected')).toBe(false); expect(container.querySelectorAll('.react-flow__node')).toHaveLength(20)
  })
})
