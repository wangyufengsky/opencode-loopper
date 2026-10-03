import { workflowReasonLabel } from '@/utils/displayLabels'
import * as validators from './validators'
import { Badge, Code, Invalid, Lines, text, type ReportProps } from './ReportParts'

export function WorkflowCommandReport({ content }: ReportProps) {
  const r = validators.parseCommandReport(content); if (!r) return <Invalid label="命令报告" />
  return <section className="workflow-professional-report workflow-command-report"><Badge good={r.passed}>{!r.valid ? '检查未完成' : r.passed ? '检查通过' : '检查未通过'}</Badge>{r.exitCode !== null && <p>进程退出码：{text(r.exitCode)}</p>}{r.timedOut && <p>命令超过执行时限。</p>}{r.cancelled && <p>本次检查已取消。</p>}{r.outputTruncated && <p>输出不完整，不能作为完整检查结果。</p>}{r.errorCode && <p role="alert">{workflowReasonLabel(r.errorCode)}</p>}{r.reportExcerpt && <p>仅展示输出摘要，完整已保存输出可在执行记录中查看。</p>}{r.output && <Code content={r.output} label="命令输出摘要" />}</section>
}
export function WorkflowVerificationReport({ content }: ReportProps) {
  const checks = validators.parseVerificationReport(content); if (!checks) return <Invalid label="检查报告" />
  return <section className="workflow-professional-report">{checks.map((check, i) => <article key={i}><strong>{check.title}</strong> <Badge good={check.state === 'PASS'}>{check.state === 'PASS' ? '通过' : check.state === 'FAIL' ? '未通过' : '无法检查'}</Badge>{check.path && <p>{check.path}</p>}{check.detail && <p>{check.detail}</p>}</article>)}</section>
}
export function WorkflowReviewReport({ content }: ReportProps) {
  const r = validators.parseReviewReport(content); if (!r) return <Invalid label="评审报告" />
  return <section className="workflow-professional-report"><h4>{r.dual ? '同批验收结果' : '独立评审意见'}</h4><Badge good={r.passed}>{r.dual ? r.passed ? '验收通过' : '验收未通过' : r.passed ? '评审通过' : '评审未通过'}</Badge>{r.dual && <p>程序验证：{r.verified ? '通过' : '未通过'}</p>}{r.opinions.map(row => <article key={row.perspective}><strong>{row.perspective === 'REQUIREMENT' ? '需求评审' : '风险评审'}</strong><p>{row.verdict === 'PASS' ? '通过' : '未通过'}</p><p>{row.reason}</p></article>)}</section>
}
export function WorkflowRepositoryReport({ content }: ReportProps) {
  const r = validators.parseRepositoryReport(content); if (!r) return <Invalid label="代码采集报告" />
  return <section className="workflow-professional-report"><Badge good={r.complete}>{r.complete ? '分支代码已固定' : '采集未完成'}</Badge><p>代码来源：{r.branch}</p>{r.commit && <p>固定提交：<code>{r.commit}</code></p>}{!!r.prefix && <p>项目目录：{text(r.prefix)}</p>}{r.complete && <p>清单 {r.files} 项 · 限制读取 {r.excluded} 项</p>}{!!r.excluded && <p>文件列表保留每项限制原因；受保护、符号链接和超限文件不提供正文。</p>}{r.code && <p role="alert">{workflowReasonLabel(r.code)}</p>}</section>
}
export function WorkflowSourceReport({ content }: ReportProps) {
  const r = validators.parseSourceReport(content); if (!r) return <Invalid label="源码采集报告" />
  return <section className="workflow-professional-report"><Badge good={r.complete}>{r.complete ? '源码已冻结' : '采集未完成'}</Badge>{r.path && <p>源码路径：{r.path}</p>}{r.targets !== null && <p>适用目标 {r.targets} 项 · 清单 {r.files} 项{r.incomplete ? ` · 未完整读取 ${r.incomplete} 项` : ''}</p>}{r.code && <p role="alert">{workflowReasonLabel(r.code)}</p>}{!!r.exclusions.length && <details open={!r.complete}><summary>排除与读取说明（{r.excluded} 项）</summary>{r.exclusions.map(row => <article key={row.path}><strong>{row.path}</strong><p>{row.reason}</p></article>)}{(r.excluded || 0) > r.exclusions.length && <p>这里只展示前 {r.exclusions.length} 项，完整清单保存在冻结记录中。</p>}</details>}</section>
}
export function WorkflowHistoryReport({ content }: ReportProps) {
  const r = validators.parseHistoryReport(content); if (!r) return <Invalid label="历史采集报告" />
  return <section className="workflow-professional-report"><Badge good={r.complete}>{r.complete ? 'Git 历史已固定' : '历史采集未完成'}</Badge><p>历史来源：{r.branch}</p><p>日期范围：{r.start} 至 {r.end}（北京时间，含结束日）</p>{r.commit && <p>固定提交：<code>{r.commit}</code></p>}{!!r.prefix && <p>项目目录：{text(r.prefix)}</p>}{r.complete && <><p>{r.commits} 个提交 · {r.changes} 项文件变更 · {r.excluded} 项排除计量</p>{r.commits === 0 && <p>所选范围没有提交，已保存完整的空范围记录。</p>}<p>固定资料按提交保存原始身份、差异和排除依据；敏感文件不包含正文。</p></>}{r.code && <p role="alert">{workflowReasonLabel(r.code)}</p>}</section>
}
export function WorkflowReviewSourceReport({ content }: ReportProps) {
  const r = validators.parseReviewSourceReport(content); if (!r) return <Invalid label="版本审查采集报告" />
  return <section className="workflow-professional-report"><Badge good={r.complete}>{r.complete ? '审查资料已固定' : '审查资料采集未完成'}</Badge><p>代码来源：{r.branch}</p>{r.mode === 'FULL' ? <p>审查范围：全面审查</p> : <p>日期范围：{text(r.start)} 至 {text(r.end)}（北京时间，含结束日）</p>}{r.complete && <><p>来源提交：<code>{r.source}</code></p>{r.baseline && <p>基线版本：<code>{r.baseline}</code></p>}<p>目标版本：<code>{r.target}</code></p>{!!r.prefix && <p>项目目录：{text(r.prefix)}</p>}<p>{text(r.units)} 个代码单元 · {text(r.excluded)} 项未纳入正文</p>{!!r.noChanges && <p>所选边界版本的代码树相同，无需新增代码分析。</p>}{!!r.nonMonotonic && <p>提交时间存在倒序，已按主线顺序选择边界版本。</p>}<p>版本与代码证据已保存，尚未生成审查结论；本节点不运行项目构建或测试。</p></>}{r.code && <p role="alert">{workflowReasonLabel(r.code)}</p>}</section>
}
export function WorkflowSourcePlanReport({ content }: ReportProps) {
  const r = validators.parseSourcePlanReport(content); if (!r) return <Invalid label="源码分批报告" />
  return <section className="workflow-professional-report"><Badge good={r.complete}>{r.complete ? '候选计划已生成' : '分批计划未生成'}</Badge>{r.complete ? <><p>{r.sourceCount} 个源码文件，分为 {r.batchCount} 批。</p><p>在候选计划中查看变更与确认状态。</p>{r.batches.map(batch => <details key={batch.ordinal}><summary>第 {batch.ordinal + 1} 批 · {batch.title} · {batch.paths.length} 个文件</summary><Lines values={batch.paths} /></details>)}</> : <p>{workflowReasonLabel(r.code)}</p>}</section>
}
export function WorkflowDocumentPlanReport({ content }: ReportProps) {
  const r = validators.parseDocumentPlanReport(content); if (!r) return <Invalid label="原文分批报告" />
  return <section className="workflow-professional-report"><Badge good={r.complete}>{r.complete ? '候选计划已生成' : '分批计划未生成'}</Badge>{r.complete ? <><p>{r.sectionCount} 个原文章节，分为 {r.batchCount} 批。</p><p>在候选计划中查看变更并确认，确认后再选择执行方式。</p>{r.batches.map(batch => <details key={batch.ordinal}><summary>第 {batch.ordinal + 1} 批 · {batch.sections.length} 章 · {batch.characters} 字符</summary><Lines values={batch.sections} /></details>)}</> : <p>{workflowReasonLabel(r.code)}</p>}</section>
}
export function WorkflowDocumentReport({ content }: ReportProps) {
  const r = validators.parseDocumentReport(content); if (!r) return <Invalid label="文档汇总报告" />
  return <section className="workflow-professional-report"><Badge good={r.complete}>{r.complete ? '文档已生成' : '文档未生成'}</Badge>{r.complete ? <>{r.assessment ? <><p>覆盖 {r.sourceCount} 个原文章节，汇总 {r.draftCount} 份评审稿，生成 {r.fileCount} 个报告文件。</p><p>{r.requirementCount} 条需求，{r.findingCount} 项问题。{!r.requirementCount ? '未提取可评审需求，不能据此认定全部满足。' : r.satisfied ? '全部条目均有符合需求的静态证据。' : '存在未完全满足或无法判断的需求。'}</p><p>本次未运行构建、测试或项目脚本。</p></> : <p>覆盖 {r.sourceCount} 个源码文件，汇总 {r.draftCount} 份设计稿，生成 {r.fileCount} 个 Markdown 文件。</p>}<p>独立复核通过 {r.reviewedCount} / {r.draftCount} 份{r.reviseCount ? `，${r.reviseCount} 份要求返修` : ''}。</p>{r.assessment && r.crossBatchReviewedCount < r.reviewedCount && <p>部分复核未读取全部批次稿件，跨批次核对尚未完整。</p>}{!r.required && <p>按自定义策略生成，未要求全部复核通过。</p>}</> : <p>{workflowReasonLabel(r.code)}</p>}</section>
}
export function WorkflowHistorySummary({ content }: ReportProps) {
  const r = validators.parseHistorySummary(content); if (!r) return <Invalid label="历史流程汇总" />
  return <section className="workflow-professional-report"><Badge good={r.complete}>{r.complete ? r.plan ? '候选计划已生成' : '完整报告已生成' : r.plan ? '候选计划未生成' : '报告未生成'}</Badge>{r.complete ? <><p>{r.commits} 个提交 · {r.units} 个证据片段 · {r.batches} 个审查批次</p>{r.plan ? <p>{r.people ? `另有 ${r.people} 个个人贡献评价节点。` : ''}在候选计划中查看变更并确认，再选择执行方式。</p> : <p>{r.files} 份报告文件{r.contribution ? `，包含 ${r.people} 位贡献者的事实与程序计分` : ''}。可预览主报告、跳转明细或下载整套报告。</p>}{!r.commits && <p>所选日期范围内无提交；保留完整报告说明和导航，无需模型分析。</p>}{!r.plan && <p>结论对应固定历史证据；未复核问题在当前版本中的存续状态，也未运行测试。</p>}</> : <p>{workflowReasonLabel(r.code)}</p>}</section>
}
export function WorkflowSnapshotSummary({ content }: ReportProps) {
  const r = validators.parseSnapshotSummary(content); if (!r) return <Invalid label="版本流程汇总" />
  return <section className="workflow-professional-report"><Badge good={r.complete}>{r.complete ? r.plan ? '候选计划已生成' : '完整报告已生成' : r.plan ? '候选计划未生成' : '报告未生成'}</Badge>{r.complete ? <><p>{r.units} 个证据片段 · {r.excluded} 个排除项 · {r.batches} 个分析批次</p>{r.plan ? <p>{r.conditional ? '仅有候选问题的批次进入独立复核。' : '本流程没有独立复核节点。'}在候选计划中查看变更并确认，再选择执行方式。</p> : <><p>{r.candidates} 个候选问题 · {r.supported} 个获独立支持 · {r.reviews} 批已复核</p><p>{r.required ? '有候选问题的批次均须完成独立复核。' : '不要求独立复核；未复核的问题仅作为候选保留。'}</p><p>{r.files} 份报告文件，可预览主报告或下载整套报告。</p><p>目标版本 <code>{r.target}</code></p></>}{!r.batches && <p>没有可审查变化或全部属于排除范围；报告保留范围及排除说明，无需模型分析。</p>}<p>无问题结论未经独立复核；静态审查未运行目标项目测试。</p></> : <p>{workflowReasonLabel(r.code)}</p>}</section>
}
