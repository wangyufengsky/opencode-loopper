import { Button, Form, Layout, Modal, Table, type TableColumnsType } from 'antd'
import { useId, useLayoutEffect, useRef, useState, type HTMLAttributes, type KeyboardEvent, type ReactNode, type Ref, type RefObject } from 'react'
import { useFoundationContainer } from './provider'
import { SemanticIcon, semanticLabel, semanticName, type UiActionKey, type UiSemanticKey } from './semanticRegistry'

import type { ActionAvailability, ClosePolicy } from './contracts/types'

export type UiAvailability = ActionAvailability
export type UiClosePolicy = ClosePolicy
export type UiConfirmationPolicy = { kind: 'allow' } | { kind: 'block'; reason: string }
export type UiReturnFocus = RefObject<HTMLElement | null>

export interface UiActionButtonProps {
  actionKey: UiActionKey
  target?: string
  availability?: UiAvailability
  onAction: () => void
  variant?: 'default' | 'primary' | 'danger' | 'text'
  busy?: boolean
  iconOnly?: boolean
  id?: string
  className?: string
  expanded?: boolean
  controls?: string
  buttonRef?: Ref<HTMLButtonElement | HTMLAnchorElement>
}

/** Presentation availability is supplied by the owner, never inferred from catalogue metadata. */
export function UiActionButton({ actionKey, target, availability = { kind: 'enabled' }, onAction,
  variant = 'default', busy = false, iconOnly = false, id, className, expanded, controls, buttonRef }: UiActionButtonProps) {
  const reasonId = useId()
  if (availability.kind === 'hidden') return null
  const disabled = busy || availability.kind === 'disabled'
  return <>
    <Button id={id} ref={buttonRef} htmlType="button" type={variant === 'primary' ? 'primary' : variant === 'text' ? 'text' : 'default'}
      danger={variant === 'danger'} disabled={disabled} loading={busy} aria-busy={busy}
      aria-expanded={expanded} aria-controls={controls}
      aria-label={semanticName(actionKey, target)} aria-describedby={availability.kind === 'disabled' ? reasonId : undefined}
      title={availability.kind === 'disabled' ? availability.reason : iconOnly ? semanticName(actionKey, target) : undefined}
      data-semantic={actionKey} data-foundation-component="action"
      className={[variant === 'primary' ? 'ui-action-primary' : '', className].filter(Boolean).join(' ')}
      icon={<SemanticIcon semanticKey={actionKey} />}
      onClick={() => { if (!disabled && availability.kind === 'enabled') onAction() }}>
      {iconOnly ? null : semanticLabel(actionKey)}
    </Button>
    {availability.kind === 'disabled' && <span id={reasonId} className="ui-visually-hidden">{availability.reason}</span>}
  </>
}

export interface UiShellProps {
  navigation: ReactNode
  header: ReactNode
  children: ReactNode
  status?: ReactNode
  context?: ReactNode
  footer?: ReactNode
}

/** Slots contain controlled UI. This shell owns no history, subscriptions or commands. */
export function UiShell({ navigation, header, children, status, context, footer }: UiShellProps) {
  const mainId = useId()
  return <Layout className="ui-shell" data-foundation-component="shell">
    <a className="ui-skip-link" href={`#${mainId}`}>{semanticLabel('app.skipContent')}</a>
    <Layout.Sider width={184} className="ui-shell-navigation"><nav aria-label={semanticName('app.navigation')}>{navigation}</nav></Layout.Sider>
    <Layout className="ui-shell-workspace">
      <Layout.Header className="ui-shell-header">{header}</Layout.Header>
      {status && <div className="ui-shell-status" aria-live="polite">{status}</div>}
      <div className="ui-shell-body">
        <Layout.Content id={mainId} tabIndex={-1} className="ui-shell-main" aria-label={semanticName('app.workspace')}>{children}</Layout.Content>
        {context}
      </div>
      {footer && <Layout.Footer className="ui-shell-footer">{footer}</Layout.Footer>}
    </Layout>
  </Layout>
}

function visibleFocusTarget(element: HTMLElement): boolean {
  if (!element.isConnected || element.matches(':disabled, input[type="hidden"]')) return false
  for (let ancestor: HTMLElement | null = element; ancestor; ancestor = ancestor.parentElement) {
    if (ancestor.hidden || ancestor.hasAttribute('inert') || ancestor.getAttribute('aria-hidden') === 'true' || ancestor.getAttribute('aria-disabled') === 'true') return false
    const style = getComputedStyle(ancestor)
    if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse') return false
  }
  return true
}

function restoreFocus(explicit: UiReturnFocus | undefined, captured: HTMLElement | null, getContainer: () => HTMLElement) {
  let host: HTMLElement
  try { host = getContainer() } catch { return } // A retired provider must never reclaim focus.
  if (!host.isConnected) return
  const target = [explicit?.current, captured].find(element => element && host.contains(element) && element.closest('.loopper-foundation') === host && visibleFocusTarget(element))
  const main = [...host.querySelectorAll<HTMLElement>('.ui-shell-main')].find(element => element.closest('.loopper-foundation') === host && visibleFocusTarget(element))
  ;(target ?? main)?.focus({ preventScroll: true })
}

/** Keep native Tab boundaries inside this modal, without relying on library sentinels. */
function containDialogTab(event: KeyboardEvent<HTMLDivElement>) {
  if (event.key !== 'Tab' || event.altKey || event.ctrlKey || event.metaKey) return
  const root = event.currentTarget
  // React portal events can traverse an outer modal whose DOM does not own the target.
  if (!(event.target instanceof Element) || !root.contains(event.target) || event.target.closest('[data-foundation-component="dialog-focus-scope"]') !== root) return
  const candidates = [...root.querySelectorAll<HTMLElement>('button, input, select, textarea, a[href], area[href], [tabindex], [contenteditable], audio[controls], video[controls], summary')]
    .filter(element => element.tabIndex >= 0 && visibleFocusTarget(element))
    // Positive tabindex comes before the ordinary DOM-order tab stops.
    .sort((a, b) => (a.tabIndex > 0 ? a.tabIndex : Number.MAX_SAFE_INTEGER) - (b.tabIndex > 0 ? b.tabIndex : Number.MAX_SAFE_INTEGER))
  const active = document.activeElement
  const index = candidates.findIndex(element => element === active)
  if (!candidates.length || index < 0 || (!event.shiftKey && index === candidates.length - 1) || (event.shiftKey && index === 0)) {
    event.preventDefault()
    event.stopPropagation()
    const destination = event.shiftKey ? candidates[candidates.length - 1] : candidates[0]
    ;(destination ?? root).focus({ preventScroll: true })
  }
}

export interface UiConfirmDialogProps {
  open: boolean
  title: ReactNode
  children: ReactNode
  confirmActionKey: UiActionKey
  onConfirm: () => void
  onCancel: () => void
  policy?: UiConfirmationPolicy
  returnFocus?: UiReturnFocus
  busy?: boolean
  target?: string
}

/** Confirming only invokes the current owner callback; a BLOCK policy cannot become a confirmation. */
export function UiConfirmDialog({ open, title, children, confirmActionKey, onConfirm, onCancel,
  policy = { kind: 'allow' }, returnFocus, busy = false, target }: UiConfirmDialogProps) {
  const container = useFoundationContainer()
  // Ant's portal resolves getContainer during render; the provider host ref exists after commit.
  const [portalReady, setPortalReady] = useState(false)
  const mounted = useRef(false)
  useLayoutEffect(() => { mounted.current = true; setPortalReady(true); return () => { mounted.current = false } }, [])
  const cancelButton = useRef<HTMLButtonElement | HTMLAnchorElement>(null)
  const captured = useRef<HTMLElement | null>(null)
  const wasOpen = useRef(false)
  useLayoutEffect(() => {
    if (open && !wasOpen.current) captured.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    wasOpen.current = open
  }, [open])
  if (!portalReady) return null
  // CSS reduced-motion removes the animation-end event rc-dialog otherwise
  // waits for before hiding its wrap. Public empty names close immediately.
  return <Modal open={open} title={title} getContainer={container} closable={false}
    transitionName="" maskTransitionName=""
    mask={{ closable: false }} keyboard={!busy} focusable={{ trap: true, focusTriggerAfterClose: false }}
    destroyOnHidden={false} className="ui-confirm-dialog" onCancel={() => { if (!busy) onCancel() }}
    modalRender={content => <div tabIndex={-1} data-foundation-component="dialog-focus-scope" onKeyDownCapture={containDialogTab}>{content}</div>}
    afterOpenChange={visible => { if (visible) cancelButton.current?.focus({ preventScroll: true }) }}
    afterClose={() => { if (mounted.current) restoreFocus(returnFocus, captured.current, container) }}
    footer={<div className="ui-dialog-actions">
      <UiActionButton actionKey="ui.stay" buttonRef={cancelButton} onAction={onCancel}
        availability={busy ? { kind: 'disabled', reason: '正在处理，请保留当前操作。' } : { kind: 'enabled' }} />
      <UiActionButton actionKey={confirmActionKey} target={target} variant="primary" busy={busy}
        availability={policy.kind === 'block' ? { kind: 'disabled', reason: policy.reason } : { kind: 'enabled' }}
        onAction={() => { if (!busy && policy.kind === 'allow') onConfirm() }} />
    </div>}>
    {children}
    {policy.kind === 'block' && <p className="ui-policy-reason" role="alert">{policy.reason}</p>}
  </Modal>
}

export interface UiContextPanelProps {
  open: boolean
  title: string
  children: ReactNode
  onClose: () => void
  closePolicy?: UiClosePolicy
  onConfirmClose?: () => void
  returnFocus?: UiReturnFocus
  expanded?: boolean
  onExpandedChange?: (expanded: boolean) => void
}

/** Nonmodal presentation. Hiding preserves mounted fields; only the owner may discard its draft. */
export function UiContextPanel({ open, title, children, onClose, closePolicy = { kind: 'allow' },
  onConfirmClose, returnFocus, expanded = false, onExpandedChange }: UiContextPanelProps) {
  const container = useFoundationContainer()
  const panel = useRef<HTMLElement>(null)
  const captured = useRef<HTMLElement | null>(null)
  const wasOpen = useRef(false)
  const [confirming, setConfirming] = useState(false)
  const titleId = useId()
  useLayoutEffect(() => {
    if (open && !wasOpen.current) {
      captured.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
      panel.current?.focus({ preventScroll: true })
    } else if (!open && wasOpen.current) restoreFocus(returnFocus, captured.current, container)
    wasOpen.current = open
    if (!open) setConfirming(false)
  }, [open, returnFocus, container])
  const requestClose = () => {
    if (closePolicy.kind === 'block') return
    if (closePolicy.kind === 'confirm') setConfirming(true)
    else onClose()
  }
  return <>
    <aside ref={panel} hidden={!open} tabIndex={-1} aria-labelledby={titleId}
      className={`ui-context-panel${expanded ? ' ui-context-expanded' : ''}`} data-foundation-component="context"
      onKeyDown={event => { if (event.key === 'Escape' && !confirming) { event.preventDefault(); event.stopPropagation(); requestClose() } }}>
      <header className="ui-context-header"><h2 id={titleId}>{title}</h2><div className="ui-context-actions">
        {onExpandedChange && <UiActionButton actionKey={expanded ? 'ui.collapse' : 'ui.expand'} target={title} iconOnly expanded={expanded}
          onAction={() => onExpandedChange(!expanded)} />}
        <UiActionButton actionKey="ui.close" target={title} iconOnly onAction={requestClose}
          availability={closePolicy.kind === 'block' ? { kind: 'disabled', reason: closePolicy.reason } : { kind: 'enabled' }} />
      </div></header>
      {closePolicy.kind === 'block' && <p className="ui-policy-reason" role="alert">{closePolicy.reason}</p>}
      {children}
    </aside>
    <UiConfirmDialog open={open && confirming} title={semanticLabel('ui.cancelEditing')}
      returnFocus={!open ? returnFocus ?? captured : undefined}
      confirmActionKey="ui.discardChanges" onCancel={() => setConfirming(false)}
      policy={closePolicy.kind === 'block' ? { kind: 'block', reason: closePolicy.reason ?? '原操作尚未确认，请留在当前页面。' } : closePolicy.kind === 'confirm' && !onConfirmClose
        ? { kind: 'block', reason: '页面尚未提供草稿处理入口，请留在当前页面。' } : { kind: 'allow' }}
      onConfirm={() => {
        if (closePolicy.kind === 'block') return
        setConfirming(false)
        if (closePolicy.kind === 'confirm') onConfirmClose?.()
        else onClose()
      }}>
      {closePolicy.kind !== 'allow' ? closePolicy.reason : null}
    </UiConfirmDialog>
  </>
}

export interface UiSelectableListProps<T> {
  items: readonly T[]
  selectedKey?: string
  getKey: (item: T) => string
  getName: (item: T) => string
  renderItem: (item: T) => ReactNode
  onSelect: (item: T) => void
  labelKey?: UiSemanticKey
}
export function UiSelectableList<T>({ items, selectedKey, getKey, getName, renderItem, onSelect,
  labelKey = 'section.workspace' }: UiSelectableListProps<T>) {
  return <ul className="ui-selectable-list" aria-label={semanticName(labelKey)} data-foundation-component="list">
    {items.map(item => <li key={getKey(item)}><button type="button" aria-pressed={selectedKey === getKey(item)}
      aria-label={semanticName('selection.select', getName(item))} data-semantic="selection.select"
      onClick={() => onSelect(item)}>{renderItem(item)}</button></li>)}
  </ul>
}
export interface UiTableColumn<T> { key: string; titleKey: UiSemanticKey; render: (item: T) => ReactNode }
export interface UiSelectableTableProps<T extends object> extends Omit<UiSelectableListProps<T>, 'renderItem' | 'labelKey'> {
  columns: readonly UiTableColumn<T>[]
  labelKey?: UiSemanticKey
}
export function UiSelectableTable<T extends object>({ items, selectedKey, getKey, getName, columns, onSelect,
  labelKey = 'object.task' }: UiSelectableTableProps<T>) {
  const tableColumns: TableColumnsType<T> = columns.map(column => ({ key: column.key,
    title: semanticLabel(column.titleKey), render: (_: unknown, item: T) => column.render(item) }))
  const interactive = (target: EventTarget | null) => target instanceof Element && !!target.closest('button,a,input,select,textarea,[role="button"]')
  return <Table<T> className="ui-selectable-table" aria-label={semanticName(labelKey)} pagination={false}
    dataSource={[...items]} rowKey={getKey} columns={tableColumns}
    onRow={item => ({ tabIndex: 0, 'aria-selected': selectedKey === getKey(item),
      'aria-label': semanticName('selection.select', getName(item)),
      onClick: event => { if (!interactive(event.target)) onSelect(item) },
      onKeyDown: event => {
        if (event.target === event.currentTarget && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); onSelect(item) }
      },
    })} />
}

export interface UiFieldControlProps { id: string; 'aria-describedby'?: string; 'aria-invalid'?: boolean; required?: boolean }
export interface UiFieldProps {
  labelKey: UiSemanticKey
  id?: string
  hint?: ReactNode
  error?: string
  required?: boolean
  children: (props: UiFieldControlProps) => ReactNode
}
export function UiField({ labelKey, id, hint, error, required, children }: UiFieldProps) {
  const generatedId = useId(), controlId = id ?? generatedId, helpId = `${controlId}-help`
  return <Form.Item className="ui-field" label={semanticLabel(labelKey)} htmlFor={controlId} required={required}
    validateStatus={error ? 'error' : undefined} help={(error || hint) && <span id={helpId} role={error ? 'alert' : undefined}>{error || hint}</span>}>
    {children({ id: controlId, 'aria-describedby': error || hint ? helpId : undefined, 'aria-invalid': !!error, required })}
  </Form.Item>
}

export interface UiDisclosureProps extends Pick<HTMLAttributes<HTMLElement>, 'className'> {
  titleKey: UiSemanticKey
  open: boolean
  onOpenChange: (open: boolean) => void
  children: ReactNode
}
export function UiDisclosure({ titleKey, open, onOpenChange, children, className }: UiDisclosureProps) {
  const contentId = useId()
  return <section className={`ui-disclosure ${className ?? ''}`} data-foundation-component="disclosure">
    <Button htmlType="button" type="text" aria-expanded={open} aria-controls={contentId}
      aria-label={semanticName(open ? 'ui.collapse' : 'ui.expand', semanticLabel(titleKey))}
      data-semantic={open ? 'ui.collapse' : 'ui.expand'} icon={<SemanticIcon semanticKey={open ? 'ui.collapse' : 'ui.expand'} />}
      onClick={() => onOpenChange(!open)}>{semanticLabel(titleKey)}</Button>
    <div id={contentId} hidden={!open}>{children}</div>
  </section>
}
