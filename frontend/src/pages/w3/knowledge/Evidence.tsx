import { useEffect, useState } from 'react'
import { UiActionButton } from '@/foundation/components'
import type { KnowledgeCitation, KnowledgeContent, KnowledgeRange } from '@/types/domain'
import type { SkinDefinition } from '@/themes/types'
import { RichDocument } from '@/pages/w3/shared/RichDocument'
import { ReadOnlyCode } from '@/pages/w3/shared/ReadOnlyCode'

const cell = (value: unknown) => value == null ? 'NULL' : typeof value === 'object' ? JSON.stringify(value) : String(value)
const gitLabels: Record<string,string> = { sha:'提交',commit:'提交',author:'作者',authorEmail:'作者邮箱',authorDate:'作者时间',authoredAt:'作者时间',committer:'提交者',committerEmail:'提交者邮箱',committerDate:'提交时间',committedAt:'提交时间',subject:'说明',name:'名称',email:'邮箱',commits:'提交数',count:'数量' }
export function KnowledgeEvidence({ body, citation, focusRange, skin }: {body:KnowledgeContent;citation?:KnowledgeCitation;focusRange?:KnowledgeRange;skin:SkinDefinition}) {
  const [formatted,setFormatted] = useState(false), [raw,setRaw] = useState(false)
  useEffect(() => { setFormatted(false); setRaw(false) }, [body,focusRange])
  const columns = Array.isArray(body.columns) ? body.columns.map(c => typeof c === 'string' ? c : String((c as {name:string}).name)) : Array.isArray(body.items) && body.items.length ? Object.keys(body.items[0] as object) : []
  const rows: unknown[][] = Array.isArray(body.rows) ? body.rows as unknown[][] : Array.isArray(body.items) ? (body.items as Record<string,unknown>[]).map(row => columns.map(c => row[c])) : []
  const first = body.startLine || 1, last = body.endLine || first + (body.text || '').split('\n').length - 1
  const range = focusRange || (citation ? {unit:body.kind === 'DATABASE' ? 'R' : 'L',first:body.kind === 'DATABASE' ? 1 : first,last:body.kind === 'DATABASE' ? rows.length : last} : undefined)
  const lines = range?.unit === 'L' ? Array.from({length:Math.max(0,Math.min(last,range.last)-Math.max(first,range.first)+1)},(_,i) => Math.max(first,range.first)-first+i+1) : []
  const absoluteLines=lines.map(line => line+first-1)
  const json = JSON.stringify(body,null,2), language = body.name?.endsWith('.java') ? 'java' : body.name?.endsWith('.json') ? 'json' : 'plain'
  return <section className="knowledge-evidence"><h3>{citation?.name || body.name}</h3><p>{citation?.location || body.location}</p><p>{citation?.createdAt} <span className="knowledge-hash" title={body.sha256}>{body.sha256?.slice(0,12)}</span> {body.versionLabel}</p>
    {!!body.changeNotice && <p role="status">{String(body.changeNotice)}</p>}{body.limitations?.filter(text => text !== '仅提供可提取内容，不还原版式、动画或图片中的文字；不执行公式或抓取外部资源').map(text => <p key={text}>{text}</p>)}
    {body.kind === 'DATABASE' || !body.text && body.kind === 'GIT' ? <>
      {(body.truncated || body.incomplete) && <p>结果未覆盖全部资料，仅展示本次采集范围</p>}{!!body.notice && <p>{String(body.notice)}</p>}{body.sql && <details><summary>查询语句</summary><pre>{body.sql}</pre></details>}
      {columns.length > 0 && <div className="knowledge-result-table"><table><thead><tr><th>行</th>{columns.map(c => <th key={c}>{body.kind === 'GIT' ? gitLabels[c] || c : c}</th>)}</tr></thead><tbody>{rows.map((row,i) => <tr key={i} className={range?.unit === 'R' && i+1>=range.first && i+1<=range.last ? 'is-highlighted' : ''}><th>{i+1}</th>{row.map((value,j) => <td key={j}>{cell(value)}</td>)}</tr>)}</tbody></table></div>}
      {!rows.length && body.kind === 'DATABASE' && <p>没有返回结果行</p>}<UiActionButton actionKey="knowledge.rawResult" expanded={raw} onAction={() => setRaw(!raw)} />{(raw || !columns.length) && <ReadOnlyCode content={json} language="json" label="数据库或 Git 读取结果" />}
    </> : body.text ? <><div className="knowledge-evidence-toolbar"><span>{body.kind === 'DOCUMENT' ? String(body.lineBasis || '解析文本行号') : '原文行号'} {first}–{last}</span>{body.kind === 'DOCUMENT' && <UiActionButton actionKey={formatted ? 'knowledge.rawView' : 'knowledge.readingView'} onAction={() => setFormatted(!formatted)} />}</div>
      {formatted ? <RichDocument content={body.text} skin={skin} allowImages={false} highlightLines={lines} /> : <ReadOnlyCode content={body.text} language={language} firstLineNumber={first} highlightLines={absoluteLines} lineWrapping label="引用原文片段" />}
      {Array.isArray(body.authors) && <details><summary>最后修改记录</summary>{(body.authors as Record<string,unknown>[]).map((a,i) => <p key={i}>{cell(a.number)} · {cell(a.author)} · {cell(a.commit).slice(0,12)}</p>)}</details>}
    </> : <><p>这是采集时保存的目录或资料概览，不代表当前文件状态。</p>{(body.incomplete || body.nextCursor) && <p>结果未覆盖全部资料，仅展示本次采集范围</p>}<ReadOnlyCode content={json} language="json" label="保存的资料概览" /></>}
  </section>
}
