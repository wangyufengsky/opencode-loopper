import type { ReactNode } from 'react'
import type { SkinDefinition } from '@/themes/types'
import { resolveSkin } from '@/themes/registry'
import { RichDocument } from '@/pages/w3/shared/RichDocument'
import { ReadOnlyCode } from '@/pages/w3/shared/ReadOnlyCode'

export interface ReportProps { content: unknown; skin?: SkinDefinition; review?: boolean }
export const text = (value: unknown) => value == null ? '' : String(value)
export function Invalid({ label = '专业交付物' }: { label?: string }) { return <p role="alert">{label}格式无法读取，请重新读取本次交付物。</p> }
export function Badge({ good, children }: { good: boolean; children: ReactNode }) { return <strong className={`workflow-report-status ${good ? 'is-pass' : 'is-failed'}`} data-report-status={good ? 'PASS' : 'FAILED'}>{children}</strong> }
export function Lines({ values }: { values: readonly string[] }) { return values.length ? <ul>{values.map((value, index) => <li key={index}>{value}</li>)}</ul> : null }
export function Limits({ values }: { values: readonly string[] }) { return values.length ? <details><summary>局限与待确认事项</summary><Lines values={values} /></details> : null }
export function Markdown({ content, skin }: { content: string; skin?: SkinDefinition }) { return <RichDocument content={content} skin={skin ?? resolveSkin(undefined)} allowImages={false} /> }
export function Code({ content, label = '已保存正文', language = 'plain' }: { content: string; label?: string; language?: 'java' | 'json' | 'plain' }) { return <ReadOnlyCode content={content} language={language} label={label} /> }
export interface Reference { path: string; startLine: number; endLine: number; quote: string; version?: string }
export function References({ values, label = '源码依据' }: { values: readonly Reference[]; label?: string }) { return values.length ? <details><summary>{label}（{values.length} 条）</summary>{values.map((ref, i) => <article key={i}><strong>{ref.path} · 第 {ref.startLine}–{ref.endLine} 行</strong>{ref.version && <p>版本：<code>{ref.version}</code></p>}<Code content={ref.quote} label={`${ref.path} 引用片段`} /></article>)}</details> : null }
