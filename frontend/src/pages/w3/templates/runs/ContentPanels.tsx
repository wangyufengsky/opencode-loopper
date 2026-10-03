import { useLayoutEffect, useMemo, useRef } from 'react'
import { UiActionButton, UiContextPanel } from '@/foundation/components'
import { SemanticIcon, semanticName } from '@/foundation/semanticRegistry'
import type { W2PageProps } from '@/pages/w2/shared/types'
import { sourceCoverageLabel } from '@/utils/displayLabels'
import { RichDocument } from '@/pages/w3/shared/RichDocument'
import { createArtifactsOwner, createCoverageOwner } from './contentOwners'
import type { RunController } from './runController'
import { ReadNotice, selectionTrigger, useRunOwner } from './parts'

export function SourceCoveragePanel({ id, revision, ready, props, parent }: { id: string; revision: string; ready: boolean; props: W2PageProps; parent?: RunController }) {
  const owner = useMemo(() => createCoverageOwner(id, ready), [id, revision, ready]), s = useRunOwner(props, owner, parent)
  return <section className="w3-card" aria-label="源码覆盖清单"><header><h2>源码覆盖清单</h2><UiActionButton actionKey="ui.refresh" target="覆盖清单" busy={s.loading} availability={ready ? { kind: 'enabled' } : { kind: 'disabled', reason: '源码冻结完成后可读取。' }} onAction={() => { void owner.load() }} /></header>
    <ReadNotice error={s.error} loading={s.loading} />{!ready && <p>源码冻结完成后显示逐文件处理结果。</p>}
    {s.rows.map(row => <article key={row.path}><header><strong>{row.path}</strong><span>{sourceCoverageLabel(row.status)}</span><UiActionButton actionKey={s.expanded[row.path] ? 'ui.collapse' : 'ui.expand'} target="处理依据" onAction={() => { void owner.details(row) }} /></header><p>{row.exclusion}</p>{s.expanded[row.path] && <ul>{(s.expanded[row.path] ?? []).map((text, index) => <li key={index}>{text}</li>)}</ul>}</article>)}
    {s.next && <UiActionButton actionKey="ui.loadMore" target="文件" busy={s.loading} onAction={() => { void owner.load(true) }} />}
  </section>
}
export function ArtifactsPanel({ kind, id, revision, completed, props, parent }: { kind: 'source' | 'document'; id: string; revision: number; completed: boolean; props: W2PageProps; parent?: RunController }) {
  const owner = useMemo(() => createArtifactsOwner(kind, id), [kind, id]), s = useRunOwner(props, owner, parent), trigger = useRef<HTMLElement | null>(null)
  const last = useRef(revision)
  // Metadata changes refresh the list without replacing the selected preview owner.
  useLayoutEffect(() => { if (last.current !== revision) { last.current = revision; if (owner.viewCount()) void owner.load() } }, [owner, revision])
  return <section className="w3-card" aria-label={kind === 'source' ? '详细设计文档' : '需求报告'}><header><h2>{kind === 'source' ? '详细设计文档' : '需求报告'}</h2>{completed && <UiActionButton actionKey="ui.download" target="整包" busy={s.downloading} onAction={() => { void owner.bundle() }} />}</header>
    <ReadNotice error={s.error} loading={s.loading} retry={() => { void owner.load() }} />{!s.reports.length && !s.loading && <p>尚未生成报告，已完成的结果与证据会持续保留。</p>}
    <ul className="w3-records">{s.reports.map(report => <li key={report.id}><button type="button" data-semantic="selection.select" aria-label={semanticName('selection.select', report.name)} aria-pressed={s.current?.name === report.name} onClick={() => { selectionTrigger(trigger); void owner.preview(report.name) }}><SemanticIcon semanticKey="object.document" />{report.name === 'summary.md' ? '总体报告' : report.name === 'matrix.json' ? '需求矩阵数据' : report.name === 'overview.md' ? '总览' : report.name === 'coverage.md' ? '源码覆盖清单' : report.name}</button></li>)}</ul>
    {s.next && <UiActionButton actionKey="ui.loadMore" target="报告" busy={s.loading} onAction={() => { void owner.load(true) }} />}
    <UiContextPanel open={!!s.current} title={s.current?.name ?? '报告预览'} returnFocus={trigger} onClose={owner.close}>{s.current && <>
      <UiActionButton actionKey="ui.download" target="当前文件" onAction={owner.downloadCurrent} />
      {s.current.name.endsWith('.md') ? <RichDocument content={s.current.content} skin={props.skin} onLink={(href, event) => { if (!/^(?:[a-z][a-z0-9+.-]*:|\/\/|#)/i.test(href)) { event.preventDefault(); owner.linked(href) } }} /> : <pre>{s.current.content}</pre>}
    </>}</UiContextPanel>
  </section>
}
