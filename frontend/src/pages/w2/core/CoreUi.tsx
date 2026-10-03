import { Alert, Form, Input } from 'antd'
import { useEffect, useId, useMemo, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { UiActionButton } from '@/foundation/components'
import { UiConfirmDialog, UiContextPanel, type UiContextPanelProps } from '@/foundation/components'
import type { LeaveDecision } from '@/foundation/contracts/types'
import { MermaidDiagram } from '@/react/diagrams/MermaidDiagram'
import { splitThinkingContent } from '@/utils/thinkingContent'
import type { SkinDefinition } from '@/themes/types'
import type { CoreState } from './owner'

/** The page owns the draft revision; shared presentation never discards a newer draft. */
export function CoreContextPanel({ readPolicy, discard, ...props }: Omit<UiContextPanelProps, 'closePolicy' | 'onConfirmClose'> & {
  readPolicy(): LeaveDecision; discard(): void
}) {
  const [confirmation, setConfirmation] = useState<Extract<LeaveDecision, { kind: 'CONFIRM_DISCARD' }>>()
  const current = readPolicy()
  const stale = !!confirmation && current.kind === 'CONFIRM_DISCARD' && current.draftRevision !== confirmation.draftRevision
  const reason = current.kind === 'BLOCK' ? current.reason : stale ? '草稿已更新，请留在当前面板，重新确认最新修改。' : undefined
  return <>
    <UiContextPanel {...props} closePolicy={current.kind === 'BLOCK' ? { kind: 'block', reason: current.reason } : { kind: 'allow' }} onClose={() => {
      const decision = readPolicy()
      if (decision.kind === 'CONFIRM_DISCARD') setConfirmation(decision)
      else if (decision.kind === 'ALLOW') props.onClose()
    }} />
    <UiConfirmDialog open={props.open && !!confirmation} title="放弃当前修改？" confirmActionKey="ui.discardChanges"
      returnFocus={!props.open ? props.returnFocus : undefined} policy={reason ? { kind: 'block', reason } : { kind: 'allow' }}
      onCancel={() => setConfirmation(undefined)} onConfirm={() => {
        const latest = readPolicy()
        if (latest.kind === 'BLOCK' || latest.kind === 'CONFIRM_DISCARD' && latest.draftRevision !== confirmation?.draftRevision) return
        setConfirmation(undefined); discard()
      }}>
      {confirmation?.description}
    </UiConfirmDialog>
  </>
}

export function CoreStatus({ state, recover, retryOriginal }: { state: Readonly<CoreState>; recover: () => Promise<void>; retryOriginal?: () => Promise<void> }) {
  const unresolved = !['IDLE', 'SETTLED'].includes(state.mutation.phase)
  return <div className="core-status">
    {state.error && <Alert role="alert" title={state.error} type="error" showIcon />}
    {state.message && <Alert role="status" title={state.message} type="success" showIcon />}
    {state.dirty && <p className="core-draft" role="status">修改尚未保存；离开或关闭时会先确认。</p>}
    {unresolved && <Alert role="alert" type="warning" showIcon title={`${state.mutation.label}：${state.mutation.busy ? '正在处理，请先等待。' : state.mutation.phase === 'UNKNOWN' ? '结果尚未确认，原操作身份与输入已保留。' : '写入已接受，正在核对读取结果。'}`}
      description={<><p>请保留当前页面。关闭面板不会取消服务端操作。</p>
        {state.mutation.canRead && <UiActionButton actionKey="ui.retry" busy={state.mutation.busy} onAction={() => { void recover() }} />}
        {state.mutation.canRetry && retryOriginal && <UiActionButton actionKey="receipt.retryOriginal" busy={state.mutation.busy} onAction={() => { void retryOriginal() }} />}
        {!(state.mutation.canRead || state.mutation.canRetry) &&
          <p>原接口无法核对本次结果，不能重复写入或放弃原身份离开。</p>}</>} />}
  </div>
}
export function Field({ label, children, hint }: { label: string; hint?: string; children: (id: string) => ReactNode }) {
  const id = useId()
  return <Form.Item label={label} htmlFor={id} extra={hint}>{children(id)}</Form.Item>
}
export function DirectoryField({ value, label, picking, disabled, demo, change, pick }: {
  value: string; label: string; picking: boolean; disabled: boolean; demo?: boolean; change(value: string): void; pick(): void
}) {
  return <Field label={label}>{id => <div className="core-directory"><Input id={id} aria-label={label} value={value} maxLength={2048}
    disabled={disabled || picking} onChange={event => change(event.target.value)} />
    <UiActionButton actionKey="project.chooseDirectory" target={label} busy={picking}
      availability={disabled || demo ? { kind: 'disabled', reason: demo ? '演示模式不调用系统选择器。' : '请先处理当前操作。' } : { kind: 'enabled' }} onAction={pick} />
  </div>}</Field>
}

/** Sanitized Markdown text plus actual React-owned Mermaid figures. No Vue renderer/bridge. */
export function CoreMarkdown({ content, skin }: { content: string; skin: SkinDefinition }) {
  const [root, setRoot] = useState<HTMLDivElement | null>(null)
  const [frames, setFrames] = useState<{ host: HTMLElement; source: string }[]>([])
  const segments = useMemo(() => splitThinkingContent(content), [content])
  const html = useMemo(() => {
    const markdown = new MarkdownIt({ html: false, breaks: true, linkify: true, typographer: true })
    const fence = markdown.renderer.rules.fence!
    markdown.renderer.rules.fence = (tokens, i, options, env, renderer) => tokens[i]!.info.trim() === 'mermaid'
      ? `<figure data-core-mermaid="${markdown.utils.escapeHtml(tokens[i]!.content)}" aria-label="Mermaid 图示"></figure>` : fence(tokens, i, options, env, renderer)
    markdown.renderer.rules.link_open = (tokens, i, options, _env, renderer) => {
      if (!String(tokens[i]!.attrGet('href') ?? '').startsWith('#')) { tokens[i]!.attrSet('target', '_blank'); tokens[i]!.attrSet('rel', 'noopener noreferrer') }
      return renderer.renderToken(tokens, i, options)
    }
    return segments.map(segment => DOMPurify.sanitize(markdown.render(segment.content), { ADD_ATTR: ['target'], USE_PROFILES: { html: true } }))
  }, [segments])
  useEffect(() => {
    if (!root) return
    let current = true
    const jobs = [...root.querySelectorAll<HTMLElement>('figure[data-core-mermaid]')]
    const ready = (host: HTMLElement) => {
      if (!current) return
      host.textContent = ''
      setFrames(previous => [...previous.filter(frame => frame.host !== host), { host, source: host.dataset.coreMermaid ?? '' }])
    }
    setFrames([])
    if (typeof IntersectionObserver === 'undefined') jobs.forEach(ready)
    else {
      const observer = new IntersectionObserver(entries => entries.forEach(entry => {
        if (entry.isIntersecting) { observer.unobserve(entry.target); ready(entry.target as HTMLElement) }
      }), { rootMargin: '240px 0px' })
      jobs.forEach(host => { host.textContent = '流程图将在滚动到此处时加载…'; observer.observe(host) })
      return () => { current = false; observer.disconnect() }
    }
    return () => { current = false }
  }, [root, html])
  return <div ref={setRoot} className="core-markdown markdown-document">
    {segments.map((segment, i) => segment.type === 'thinking' ? <details key={i}><summary>思考过程</summary><div dangerouslySetInnerHTML={{ __html: html[i]! }} /></details>
      : <div key={i} dangerouslySetInnerHTML={{ __html: html[i]! }} />)}
    {frames.map((frame, i) => createPortal(<MermaidDiagram source={frame.source} skin={skin} />, frame.host, String(i)))}
  </div>
}
