import { useState } from 'react'
import type { WorkflowCommandEvidence as Evidence, WorkflowCommandResult } from '@/types/domain'
import { UiActionButton } from '@/foundation/components'
import { workflowReasonLabel } from '@/utils/displayLabels'
import { Code, type ReportProps } from './ReportParts'
function ProcessResult({ result, preparation = false, label = '检查命令' }: { result: WorkflowCommandResult; preparation?: boolean; label?: string }) {
  const title = preparation ? '准备命令' : label
  return <><p>{title}{result.launched ? '已启动。' : '没有启动。'}{result.stopConfirmed ? '已取得停止证明。' : '停止尚未确认。'}</p>{result.exitCode !== null && <p>进程退出码：{result.exitCode}</p>}{result.timedOut && <p>{preparation ? '准备' : '命令'}超过执行时限。</p>}{result.cancelled && <p>{preparation ? '准备已取消。' : '本次检查已取消。'}</p>}{result.outputTruncated && <p>保存的输出不完整。</p>}{result.error && <p role="alert">{workflowReasonLabel(result.error)}</p>}{result.output && <Code content={result.output} label={preparation ? '准备步骤输出' : '已保存命令输出'} />}</>
}
export function WorkflowCommandEvidence({ evidence, terminal, repository, history, reviewSource }: { evidence: Evidence; terminal: boolean; repository?: boolean; history?: boolean; reviewSource?: boolean; skin?: ReportProps['skin'] }) {
  const [selected, setSelected] = useState<string | null>(null), [raw, setRaw] = useState(false), [command, setCommand] = useState(false)
  const steps = (evidence.request?.preparations || []).map((step, i) => ({ step, result: evidence.result?.preparations?.[i] })), native = evidence.nativeReport as { files?: unknown } | null
  const files = native && Array.isArray(native.files) && native.files.length <= 128 ? native.files.filter((f): f is { path: string; content: string } => !!f && typeof f.path === 'string' && typeof f.content === 'string') : []
  return <section className="workflow-professional-report workflow-command-evidence">{!!steps.length && <section aria-label="依赖准备结果"><h4>依赖准备</h4>{steps.map(({ step, result }, i) => <article key={i}><h5>准备步骤 {i + 1}</h5>{result ? <ProcessResult result={result} preparation /> : <p>{evidence.result || terminal ? '本步骤未执行。' : '尚未取得执行结果。'}</p>}<details><summary>查看准备命令和输出</summary><Code content={JSON.stringify(step.argv, null, 2)} language="json" label={`准备步骤 ${i + 1} 命令`} /></details></article>)}</section>}
    {evidence.request ? <><h4>{repository ? reviewSource ? '版本审查采集' : history ? 'Git 历史采集' : '分支代码采集' : '检查命令'}</h4>{repository ? <details onToggle={e => setCommand(e.currentTarget.open)}><summary>查看采集命令</summary>{command && <Code content={JSON.stringify(evidence.request.argv, null, 2)} language="json" label="采集命令" />}</details> : <Code content={JSON.stringify(evidence.request.argv, null, 2)} language="json" label="检查命令" />}<p>{steps.length ? '总执行时限（包含依赖准备）' : '执行时限'}：{evidence.request.timeoutSeconds} 秒</p></> : <p>本次尝试尚未准备执行命令。</p>}
    {evidence.result ? <ProcessResult result={evidence.result} label={repository ? history ? '历史采集命令' : '代码采集命令' : '检查命令'} /> : !terminal && <p>尚未取得完整执行结果；命令结束并确认停止后保存输出。</p>}
    {!!files.length && <section><h4>已保存的原生测试报告</h4>{files.map(file => <article key={file.path}><UiActionButton actionKey="ui.open" target={file.path} expanded={selected === file.path} onAction={() => setSelected(selected === file.path ? null : file.path)} /><p>{file.path}</p>{selected === file.path && <Code content={file.content} language={file.path.endsWith('.json') ? 'json' : 'plain'} label={file.path} />}</article>)}</section>}
    <details onToggle={e => setRaw(e.currentTarget.open)}><summary>原始执行记录</summary>{raw && <Code content={JSON.stringify(evidence, null, 2)} language="json" label="原始执行记录" />}</details>
  </section>
}
