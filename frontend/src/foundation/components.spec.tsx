import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { StrictMode, createRef, useState, type ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { skins } from '@/themes/registry'
import { FoundationProvider } from './provider'
import { semanticName } from './semanticRegistry'
import { UiActionButton, UiShell, UiContextPanel, UiConfirmDialog, UiSelectableList, UiSelectableTable, UiField, UiDisclosure } from './components'

beforeEach(() => {
  vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
  // jsdom lacks pseudo-element styles. All actual element style reads still call through.
  const computedStyle = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation(element => computedStyle(element))
})
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
const frame = (children: ReactNode, skin = 0) => <FoundationProvider skin={skins[skin]!} reducedMotion>{children}</FoundationProvider>

describe('W1 controlled shared components', () => {
  it('uses fixed action semantics and denies hidden, disabled and busy callbacks', () => {
    const onAction = vi.fn(), view = render(frame(<UiActionButton actionKey="ui.save" target="目标" onAction={onAction} />))
    const save = screen.getByRole('button', { name: semanticName('ui.save', '目标') })
    expect(save.querySelector('svg')?.getAttribute('data-semantic-icon')).toBe('ui.save')
    expect(save.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true')
    expect(onAction).not.toHaveBeenCalled(); fireEvent.click(save); expect(onAction).toHaveBeenCalledTimes(1)
    view.rerender(frame(<UiActionButton actionKey="ui.save" target="目标" onAction={onAction} availability={{ kind: 'disabled', reason: '等待原回执' }} />))
    expect((save as HTMLButtonElement).disabled).toBe(true); expect(save.getAttribute('aria-describedby')).toBeTruthy()
    fireEvent.click(save); expect(onAction).toHaveBeenCalledTimes(1)
    view.rerender(frame(<UiActionButton actionKey="ui.save" busy onAction={onAction} />))
    fireEvent.click(screen.getByRole('button')); expect(onAction).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button').getAttribute('aria-busy')).toBe('true')
    view.rerender(frame(<UiActionButton actionKey="ui.save" onAction={onAction} availability={{ kind: 'hidden' }} />))
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('keeps critical status outside context and never owns browser history', () => {
    const push = vi.spyOn(history, 'pushState'), replace = vi.spyOn(history, 'replaceState'), callback = vi.fn()
    const shell = (context?: ReactNode) => <UiShell navigation={<UiActionButton actionKey="ui.open" onAction={callback} />} header="项目"
      status={<p role="alert">结果未知</p>} context={context}><p>简洁主体</p></UiShell>
    const view = render(frame(shell(<aside>按需详情</aside>)))
    view.rerender(frame(shell()))
    expect(screen.getByRole('alert').textContent).toBe('结果未知'); expect(screen.queryByText('按需详情')).toBeNull()
    expect(screen.getByRole('main').textContent).toBe('简洁主体')
    expect(push).not.toHaveBeenCalled(); expect(replace).not.toHaveBeenCalled(); expect(callback).not.toHaveBeenCalled()
  })

  it('presentation close retains its input and returns focus without cancelling a task', () => {
    const origin = createRef<HTMLButtonElement>(), cancelTask = vi.fn()
    function Fixture() {
      const [open, setOpen] = useState(false)
      return <><button ref={origin} onClick={() => setOpen(true)}>选择目标</button><UiContextPanel open={open} title="详情" returnFocus={origin} onClose={() => setOpen(false)}>
        <input aria-label="本地草稿" defaultValue="原始目标" /><UiActionButton actionKey="task.cancel" onAction={cancelTask} />
      </UiContextPanel></>
    }
    const view = render(frame(<Fixture />)); fireEvent.click(origin.current!)
    const panel = screen.getByRole('complementary', { name: '详情' }), input = screen.getByLabelText('本地草稿') as HTMLInputElement
    expect(document.activeElement).toBe(panel); fireEvent.change(input, { target: { value: '未提交内容' } })
    fireEvent.keyDown(panel, { key: 'Escape' })
    expect(screen.queryByRole('complementary')).toBeNull(); expect(view.container.querySelector('input')).toBe(input)
    expect(input.value).toBe('未提交内容'); expect(document.activeElement).toBe(origin.current); expect(cancelTask).not.toHaveBeenCalled()
  })

  it('BLOCK denies both close and Escape and exposes no discard confirmation', () => {
    const onClose = vi.fn(), onConfirmClose = vi.fn()
    render(frame(<UiContextPanel open title="原任务" closePolicy={{ kind: 'block', reason: '未知写操作必须恢复' }} onClose={onClose} onConfirmClose={onConfirmClose}>草稿</UiContextPanel>))
    const close = screen.getByRole('button', { name: semanticName('ui.close', '原任务') }) as HTMLButtonElement
    expect(close.disabled).toBe(true); fireEvent.click(close); fireEvent.keyDown(screen.getByRole('complementary'), { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled(); expect(onConfirmClose).not.toHaveBeenCalled(); expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByRole('alert').textContent).toContain('未知写操作')
  })

  it('ordinary dirty confirmation is owned by the caller and cannot bypass a later BLOCK', async () => {
    const onClose = vi.fn(), onConfirmClose = vi.fn()
    const panel = (kind: 'confirm' | 'block') => <UiContextPanel open title="草稿" onClose={onClose} onConfirmClose={onConfirmClose}
      closePolicy={{ kind, reason: kind === 'confirm' ? '需要决定是否放弃' : '回执未确认' }}>原内容</UiContextPanel>
    const view = render(frame(panel('confirm')))
    fireEvent.click(screen.getByRole('button', { name: semanticName('ui.close', '草稿') })); await screen.findByRole('dialog')
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: semanticName('ui.stay') })))
    view.rerender(frame(panel('block')))
    const discard = screen.getByRole('button', { name: semanticName('ui.discardChanges') }) as HTMLButtonElement
    expect(discard.disabled).toBe(true); fireEvent.click(discard); expect(onConfirmClose).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: semanticName('ui.stay') }))
    view.rerender(frame(panel('confirm'))); fireEvent.click(screen.getByRole('button', { name: semanticName('ui.close', '草稿') }))
    fireEvent.click(await screen.findByRole('button', { name: semanticName('ui.discardChanges') }))
    expect(onConfirmClose).toHaveBeenCalledTimes(1); expect(onClose).not.toHaveBeenCalled()
  })

  it('real Ant confirmation uses the provider portal, defaults to stay and restores the caller on Escape', async () => {
    const origin = createRef<HTMLButtonElement>(), onConfirm = vi.fn()
    function Fixture() {
      const [open, setOpen] = useState(false)
      return <><button ref={origin} onClick={() => setOpen(true)}>申请删除</button><UiConfirmDialog open={open} title="明确确认"
        confirmActionKey="ui.delete" returnFocus={origin} onCancel={() => setOpen(false)} onConfirm={onConfirm}>服务端许可仍由页面处理。</UiConfirmDialog></>
    }
    const view = render(frame(<Fixture />)); fireEvent.click(origin.current!)
    const dialog = await screen.findByRole('dialog')
    expect(view.container.querySelector('.loopper-foundation')?.contains(dialog)).toBe(true)
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: semanticName('ui.stay') })))
    fireEvent.keyDown(dialog, { key: 'Escape', keyCode: 27 })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(document.activeElement).toBe(origin.current); expect(onConfirm).not.toHaveBeenCalled()
  })

  it('StrictMode and skin changes retain the same draft without executing owner callbacks', () => {
    const close = vi.fn(), save = vi.fn(), fixture = (skin: number) => <StrictMode>{frame(<UiContextPanel open title="对象" onClose={close}>
      <input aria-label="保留草稿" defaultValue="原始内容" /><UiActionButton actionKey="ui.save" onAction={save} />
    </UiContextPanel>, skin)}</StrictMode>
    const view = render(fixture(0)), input = screen.getByRole('textbox') as HTMLInputElement
    fireEvent.change(input, { target: { value: '仍持有同一草稿' } }); view.rerender(fixture(2))
    expect(screen.getByRole('textbox')).toBe(input); expect(input.value).toBe('仍持有同一草稿')
    view.unmount(); expect(close).not.toHaveBeenCalled(); expect(save).not.toHaveBeenCalled()
  })

  it('list remains controlled and replaces old callbacks', () => {
    const items = [{ id: 'one', name: '第一项目' }], before = vi.fn(), after = vi.fn()
    const list = (onSelect: typeof before, selectedKey?: string) => <UiSelectableList items={items} getKey={row => row.id} getName={row => row.name}
      renderItem={row => row.name} selectedKey={selectedKey} onSelect={onSelect} />
    const view = render(frame(list(before))), first = screen.getByRole('button', { name: semanticName('selection.select', '第一项目') })
    fireEvent.click(first); expect(before).toHaveBeenCalledExactlyOnceWith(items[0]); expect(first.getAttribute('aria-pressed')).toBe('false')
    view.rerender(frame(list(after, 'one'))); expect(first.getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(first); expect(after).toHaveBeenCalledExactlyOnceWith(items[0]); expect(before).toHaveBeenCalledTimes(1)
  })

  it('table selects by keyboard while nested actions remain independent', () => {
    const items = [{ id: 'one', name: '第一任务' }], select = vi.fn(), open = vi.fn()
    render(frame(<UiSelectableTable items={items} getKey={row => row.id} getName={row => row.name} onSelect={select}
      columns={[{ key: 'name', titleKey: 'field.title', render: row => row.name },
        { key: 'open', titleKey: 'section.advanced', render: () => <UiActionButton actionKey="task.open" onAction={open} /> }]} />))
    const row = screen.getByRole('row', { name: semanticName('selection.select', '第一任务') })
    fireEvent.keyDown(row, { key: 'Enter' }); expect(select).toHaveBeenCalledExactlyOnceWith(items[0]); expect(row.getAttribute('aria-selected')).toBe('false')
    fireEvent.click(screen.getByRole('button', { name: semanticName('task.open') })); expect(open).toHaveBeenCalledTimes(1); expect(select).toHaveBeenCalledTimes(1)
  })

  it('binds field label/error and keeps collapsed disclosure drafts mounted', () => {
    const changed = vi.fn(), fixture = (open: boolean) => <UiDisclosure titleKey="section.advanced" open={open} onOpenChange={changed}>
      <UiField labelKey="field.title" error="标题需要填写" required>{props => <input {...props} defaultValue="未提交标题" />}</UiField>
    </UiDisclosure>
    const view = render(frame(fixture(true))), input = screen.getByRole('textbox', { name: /标题/ }) as HTMLInputElement
    expect(input.getAttribute('aria-invalid')).toBe('true'); expect(document.getElementById(input.getAttribute('aria-describedby')!)?.textContent).toBe('标题需要填写')
    fireEvent.change(input, { target: { value: '用户草稿' } }); fireEvent.click(screen.getByRole('button', { name: semanticName('ui.collapse', '高级操作') }))
    expect(changed).toHaveBeenCalledExactlyOnceWith(false); expect(screen.getByRole('textbox')).toBe(input)
    view.rerender(frame(fixture(false))); expect(view.container.querySelector('input')).toBe(input); expect(input.value).toBe('用户草稿')
  })

  it('a presentation-only panel keeps the exact File owner while controlled expansion remains explicit', () => {
    const attachment = new File(['local bytes'], 'evidence.txt', { type: 'text/plain' })
    const owner = { files: [attachment], draft: '原目标' }, close = vi.fn(), expand = vi.fn()
    const fixture = (open: boolean, expanded = false) => <UiContextPanel open={open} expanded={expanded} title="实际附件草稿"
      onClose={close} onExpandedChange={expand}><p>{owner.files[0]!.name}</p></UiContextPanel>
    const view = render(frame(fixture(true)))
    const button = screen.getByRole('button', { name: semanticName('ui.expand', '实际附件草稿') })
    expect(button.getAttribute('aria-expanded')).toBe('false'); fireEvent.click(button)
    expect(expand).toHaveBeenCalledExactlyOnceWith(true)
    expect(button.getAttribute('aria-expanded')).toBe('false')
    view.rerender(frame(fixture(false, true)))
    expect(owner.files[0]).toBe(attachment); expect(owner.draft).toBe('原目标'); expect(close).not.toHaveBeenCalled()
    view.rerender(frame(fixture(true, true)))
    expect(screen.getByText('evidence.txt')).toBeTruthy()
    expect(screen.getByRole('button', { name: semanticName('ui.collapse', '实际附件草稿') }).getAttribute('aria-expanded')).toBe('true')
  })

  it('missing owner confirmation and busy operations never substitute a local destructive action', async () => {
    const close = vi.fn(), confirm = vi.fn(), cancel = vi.fn()
    const view = render(frame(<UiContextPanel open title="普通草稿" onClose={close} closePolicy={{ kind: 'confirm', reason: '需要 owner 决定' }}>内容</UiContextPanel>))
    fireEvent.click(screen.getByRole('button', { name: semanticName('ui.close', '普通草稿') }))
    const discard = await screen.findByRole('button', { name: semanticName('ui.discardChanges') }) as HTMLButtonElement
    expect(discard.disabled).toBe(true); fireEvent.click(discard); expect(close).not.toHaveBeenCalled()
    view.unmount()
    render(frame(<UiConfirmDialog open busy title="原操作仍在处理" confirmActionKey="ui.delete" onConfirm={confirm} onCancel={cancel}>保持原操作</UiConfirmDialog>))
    const dialog = await screen.findByRole('dialog')
    fireEvent.keyDown(dialog, { key: 'Escape', keyCode: 27 })
    fireEvent.click(screen.getByRole('button', { name: semanticName('ui.stay') }))
    fireEvent.click(screen.getByRole('button', { name: semanticName('ui.delete') }))
    expect(cancel).not.toHaveBeenCalled(); expect(confirm).not.toHaveBeenCalled()
  })


  it('an expired real trigger falls back to the named main in its own provider, keeping the other island alone', () => {
    const origin = createRef<HTMLButtonElement>()
    function Fixture() {
      const [open, setOpen] = useState(false), [visible, setVisible] = useState(true)
      return <UiShell navigation="项目" header="当前工作区" context={<UiContextPanel open={open} title="当前对象"
        returnFocus={origin} onClose={() => setOpen(false)}>详情</UiContextPanel>}>
        {visible && <button ref={origin} onClick={() => setOpen(true)}>选择当前对象</button>}
        <button onClick={() => setVisible(false)}>移除原入口</button>本实例主体
      </UiShell>
    }
    const first = render(frame(<UiShell navigation="另一项目" header="另一工作区">另一实例主体</UiShell>))
    const otherMain = first.container.querySelector<HTMLElement>('.ui-shell-main')!, otherFocus = vi.spyOn(otherMain, 'focus')
    const second = render(frame(<Fixture />)), ownMain = second.container.querySelector<HTMLElement>('.ui-shell-main')!
    const originalTrigger = origin.current!
    originalTrigger.focus(); fireEvent.click(originalTrigger)
    expect(document.activeElement).toBe(second.container.querySelector('[data-foundation-component="context"]'))
    fireEvent.click(screen.getByRole('button', { name: '移除原入口' }))
    expect(originalTrigger.isConnected).toBe(false); expect(origin.current).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: semanticName('ui.close', '当前对象') }))
    expect(document.activeElement).toBe(ownMain)
    expect(ownMain.getAttribute('aria-label')).toBe(semanticName('app.workspace'))
    expect(otherFocus).not.toHaveBeenCalled()
    otherMain.focus(); second.unmount()
    expect(document.activeElement).toBe(otherMain)
  })

  it('enabled icon-only hints use the same semantic name and target; disabled hints retain the reason', () => {
    const callback = vi.fn(), view = render(frame(<UiActionButton actionKey="ui.close" target="节点详情" iconOnly onAction={callback} />))
    const button = screen.getByRole('button', { name: semanticName('ui.close', '节点详情') })
    expect(button.getAttribute('title')).toBe(button.getAttribute('aria-label'))
    view.rerender(frame(<UiActionButton actionKey="ui.close" target="节点详情" iconOnly onAction={callback}
      availability={{ kind: 'disabled', reason: '原写入结果尚未确认' }} />))
    expect(button.getAttribute('title')).toBe('原写入结果尚未确认')
    expect(button.getAttribute('aria-label')).toBe(semanticName('ui.close', '节点详情'))
    fireEvent.click(button); expect(callback).not.toHaveBeenCalled()
  })

  it('modal Tab boundaries wrap through its actual input and buttons, excluding unavailable DOM stops', async () => {
    const confirm = vi.fn(), cancel = vi.fn()
    render(frame(<UiConfirmDialog open title="字段确认" confirmActionKey="ui.delete" onConfirm={confirm} onCancel={cancel}>
      <input aria-label="disabled" disabled />
      <fieldset disabled><input aria-label="disabled fieldset" /></fieldset>
      <div hidden><input aria-label="hidden" /></div>
      <div aria-hidden="true"><input aria-label="aria hidden" /></div>
      <div aria-disabled="true"><input aria-label="aria disabled" /></div>
      <div inert><input aria-label="inert" /></div>
      <div style={{ display: 'none' }}><input aria-label="display none" /></div>
      <div style={{ visibility: 'hidden' }}><input aria-label="visibility hidden" /></div>
      <input aria-label="negative tabindex" tabIndex={-1} />
      <input type="hidden" />
      <input aria-label="实际确认字段" />
    </UiConfirmDialog>))
    const input = await screen.findByRole('textbox', { name: '实际确认字段' })
    const stay = screen.getByRole('button', { name: semanticName('ui.stay') })
    const last = screen.getByRole('button', { name: semanticName('ui.delete') })
    await waitFor(() => expect(document.activeElement).toBe(stay))
    last.focus()
    expect(fireEvent.keyDown(last, { key: 'Tab', cancelable: true })).toBe(false)
    expect(document.activeElement).toBe(input)
    expect(fireEvent.keyDown(input, { key: 'Tab', shiftKey: true, cancelable: true })).toBe(false)
    expect(document.activeElement).toBe(last)
    input.focus()
    expect(fireEvent.keyDown(input, { key: 'Tab', cancelable: true })).toBe(true)
    expect(confirm).not.toHaveBeenCalled(); expect(cancel).not.toHaveBeenCalled()
  })

  it('modal Tab recomputes endpoints when confirmation becomes blocked or all controls become disabled', async () => {
    const confirm = vi.fn(), cancel = vi.fn()
    const fixture = (blocked: boolean, busy = false, disabledInput = false) => frame(<UiConfirmDialog open busy={busy} title="动态确认"
      confirmActionKey="ui.delete" onConfirm={confirm} onCancel={cancel}
      policy={blocked ? { kind: 'block', reason: '原写操作结果未知' } : { kind: 'allow' }}>
      <input aria-label="动态字段" disabled={disabledInput} />
    </UiConfirmDialog>)
    const view = render(fixture(false)), input = await screen.findByRole('textbox', { name: '动态字段' })
    const stay = screen.getByRole('button', { name: semanticName('ui.stay') })
    await waitFor(() => expect(document.activeElement).toBe(stay))
    view.rerender(fixture(true))
    expect((screen.getByRole('button', { name: semanticName('ui.delete') }) as HTMLButtonElement).disabled).toBe(true)
    stay.focus()
    expect(fireEvent.keyDown(stay, { key: 'Tab', cancelable: true })).toBe(false)
    expect(document.activeElement).toBe(input)
    expect(fireEvent.keyDown(input, { key: 'Tab', shiftKey: true, cancelable: true })).toBe(false)
    expect(document.activeElement).toBe(stay)
    view.rerender(fixture(true, true))
    input.focus()
    expect(fireEvent.keyDown(input, { key: 'Tab', cancelable: true })).toBe(false)
    expect(document.activeElement).toBe(input)
    view.rerender(fixture(true, true, true))
    expect(fireEvent.keyDown(input, { key: 'Tab', shiftKey: true, cancelable: true })).toBe(false)
    expect(document.activeElement).toBe(view.container.querySelector('[data-foundation-component="dialog-focus-scope"]'))
    expect(confirm).not.toHaveBeenCalled(); expect(cancel).not.toHaveBeenCalled()
  })

  it('a nested real Ant modal keeps its own Tab endpoints despite React portal capture through the outer modal', async () => {
    const confirm = vi.fn(), cancel = vi.fn()
    function Fixture() {
      const [innerOpen, setInnerOpen] = useState(false)
      return <UiConfirmDialog open title="外层确认" confirmActionKey="ui.delete" target="外层对象" onConfirm={confirm} onCancel={cancel}>
        <input aria-label="外层字段" /><button onClick={() => setInnerOpen(true)}>打开内层确认</button>
        <UiConfirmDialog open={innerOpen} title="内层确认" confirmActionKey="ui.delete" target="内层对象" onConfirm={confirm} onCancel={cancel}>
          <input aria-label="内层字段" />
        </UiConfirmDialog>
      </UiConfirmDialog>
    }
    render(frame(<Fixture />))
    const outerField = await screen.findByRole('textbox', { name: '外层字段' })
    const outer = outerField.closest<HTMLElement>('[role="dialog"]')!
    await waitFor(() => expect(document.activeElement).toBe(within(outer).getByRole('button', { name: semanticName('ui.stay') })))
    fireEvent.click(screen.getByRole('button', { name: '打开内层确认' }))
    // The library's test-only IDs coincide; identify each real portal by its own field.
    const first = await screen.findByRole('textbox', { name: '内层字段' })
    const inner = first.closest<HTMLElement>('[role="dialog"]')!
    const last = within(inner).getByRole('button', { name: semanticName('ui.delete', '内层对象') })
    const innerScope = first.closest('[data-foundation-component="dialog-focus-scope"]')!
    const outerScope = outer.querySelector('[data-foundation-component="dialog-focus-scope"]')!
    expect(outerScope.contains(innerScope)).toBe(false)
    await waitFor(() => expect(document.activeElement?.closest('[data-foundation-component="dialog-focus-scope"]')).toBe(innerScope))
    last.focus()
    expect(fireEvent.keyDown(last, { key: 'Tab', cancelable: true })).toBe(false)
    expect(document.activeElement).toBe(first)
    expect(fireEvent.keyDown(first, { key: 'Tab', shiftKey: true, cancelable: true })).toBe(false)
    expect(document.activeElement).toBe(last)
    expect(confirm).not.toHaveBeenCalled(); expect(cancel).not.toHaveBeenCalled()
  })

  it.each([false, true])('dirty discard restores the caller after the real modal closes, with expired trigger=%s', async expired => {
    const origin = createRef<HTMLButtonElement>(), discarded = vi.fn()
    function Fixture() {
      const [open, setOpen] = useState(false), [visible, setVisible] = useState(true)
      return <UiShell navigation="项目" header="当前页面" context={<UiContextPanel open={open} title="草稿对象" returnFocus={origin}
        closePolicy={{ kind: 'confirm', reason: '还有未保存草稿' }} onClose={() => setOpen(false)}
        onConfirmClose={() => { discarded(); setOpen(false) }}><input aria-label="面板草稿" defaultValue="原内容" /></UiContextPanel>}>
        {visible && <button ref={origin} onClick={() => setOpen(true)}>选择草稿对象</button>}
        <button onClick={() => setVisible(false)}>移除草稿入口</button>
      </UiShell>
    }
    const view = render(frame(<Fixture />)), originalTrigger = origin.current!
    originalTrigger.focus(); fireEvent.click(originalTrigger)
    const panel = screen.getByRole('complementary', { name: '草稿对象' }), input = screen.getByRole('textbox', { name: '面板草稿' }) as HTMLInputElement
    fireEvent.change(input, { target: { value: '保留的本地草稿' } })
    if (expired) fireEvent.click(screen.getByRole('button', { name: '移除草稿入口' }))
    const close = screen.getByRole('button', { name: semanticName('ui.close', '草稿对象') })
    close.focus(); fireEvent.click(close)
    await screen.findByRole('dialog')
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: semanticName('ui.stay') })))
    fireEvent.click(screen.getByRole('button', { name: semanticName('ui.discardChanges') }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(expired ? view.container.querySelector('.ui-shell-main') : originalTrigger))
    expect(panel.hidden).toBe(true); expect(input.value).toBe('保留的本地草稿'); expect(discarded).toHaveBeenCalledOnce()
    expect(originalTrigger.isConnected).toBe(!expired)
  })

  it('cancelling a dirty dialog returns to the visible panel close trigger without discarding or returning to the page', async () => {
    const origin = createRef<HTMLButtonElement>(), discarded = vi.fn()
    function Fixture() {
      const [open, setOpen] = useState(false)
      return <><button ref={origin} onClick={() => setOpen(true)}>选择待确认对象</button><UiContextPanel open={open} title="待确认对象"
        returnFocus={origin} closePolicy={{ kind: 'confirm', reason: '需要保留草稿' }} onClose={() => setOpen(false)} onConfirmClose={discarded}>
        <input aria-label="未丢草稿" defaultValue="原草稿" />
      </UiContextPanel></>
    }
    render(frame(<Fixture />)); origin.current!.focus(); fireEvent.click(origin.current!)
    const close = screen.getByRole('button', { name: semanticName('ui.close', '待确认对象') })
    close.focus(); fireEvent.click(close)
    await screen.findByRole('dialog')
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: semanticName('ui.stay') })))
    fireEvent.click(screen.getByRole('button', { name: semanticName('ui.stay') }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(document.activeElement).toBe(close); expect(screen.getByRole('complementary')).toBeTruthy()
    expect((screen.getByRole('textbox', { name: '未丢草稿' }) as HTMLInputElement).value).toBe('原草稿')
    expect(discarded).not.toHaveBeenCalled()
  })

})
