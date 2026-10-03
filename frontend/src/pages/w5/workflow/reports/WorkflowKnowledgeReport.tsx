import { useLayoutEffect, useState } from 'react'
import { knowledgeBody, knowledgeBundle } from '@/components/workflow/knowledgeBundle'
import { knowledgeToolLabel } from '@/utils/displayLabels'
import { KnowledgeEvidence } from '@/pages/w3/knowledge/Evidence'
import { resolveSkin } from '@/themes/registry'
import { semanticName } from '@/foundation/semanticRegistry'
import { Lines, type ReportProps } from './ReportParts'
export function WorkflowKnowledgeReport({ content, skin }: ReportProps) {
  const [selected, setSelected] = useState<number | null>(null), bundle = knowledgeBundle(content), entry = selected == null ? null : bundle?.entries[selected], body = entry ? knowledgeBody(entry.content, knowledgeToolLabel(entry.toolName)) : null
  useLayoutEffect(() => setSelected(null), [content])
  if (!bundle) return <p>来源证据格式不完整，请查看原节点交付记录。</p>
  return <section className="workflow-professional-report" aria-label="交付的来源证据"><p>已选择 {bundle.entries.length} 项资料随交付保存。</p>{!bundle.entries.length && <p>本次没有交付来源证据，请结合检索结论与局限判断下一步。</p>}<Lines values={bundle.limitations} />{!!bundle.entries.length && <p>检索结果与目录记录仅作线索，不能代替完整原文。</p>}<ol>{bundle.entries.map((item, i) => { const title = String(item.content.name || item.content.path || knowledgeToolLabel(item.toolName)); return <li key={item.reference}><button type="button" aria-label={semanticName('selection.select', title)} aria-pressed={selected === i} onClick={() => setSelected(selected === i ? null : i)}>{title}<small>{knowledgeToolLabel(item.toolName)} · {new Date(item.createdAt).toLocaleString('zh-CN')}</small></button></li> })}</ol>{body && <KnowledgeEvidence body={body} skin={skin ?? resolveSkin(undefined)} />}</section>
}
