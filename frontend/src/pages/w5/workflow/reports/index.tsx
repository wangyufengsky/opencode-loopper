import type { WorkflowValue } from '@/types/domain'
import type { ReportProps } from './ReportParts'
import { Code, Markdown } from './ReportParts'
import { WorkflowKnowledgeReport } from './WorkflowKnowledgeReport'
import { WorkflowDocumentReviewReport, WorkflowSourceDesignReport } from './DesignReports'
import { WorkflowSnapshotReport, WorkflowHistoryAnalysisReport } from './AnalysisReports'
import { WorkflowCommandReport, WorkflowDocumentPlanReport, WorkflowDocumentReport, WorkflowHistoryReport, WorkflowHistorySummary, WorkflowRepositoryReport, WorkflowReviewReport, WorkflowReviewSourceReport, WorkflowSnapshotSummary, WorkflowSourcePlanReport, WorkflowSourceReport, WorkflowVerificationReport } from './BasicReports'
import { WorkflowNativeTestReport, WorkflowTestDesignReport, WorkflowTestProfileReport, WorkflowTestReviewReport, WorkflowTestScopeReport, WorkflowTestSummaryReport } from './TestReports'
export * from './BasicReports'
export * from './DesignReports'
export * from './AnalysisReports'
export * from './TestReports'
export * from './Previews'
export { WorkflowCommandEvidence } from './WorkflowCommandEvidence'
export { WorkflowKnowledgeReport } from './WorkflowKnowledgeReport'
export type { ReportProps } from './ReportParts'

/** This dispatcher mirrors NodeRun's specialist module/name selection; Files remain its caller's reader. */
export function WorkflowOutputReport({ moduleId, name, value, skin }: { moduleId?: string | null; name: string; value: WorkflowValue; skin?: ReportProps['skin'] }) {
  const props = { content: value.content, skin }, module = moduleId || ''
  if (value.kind === 'TEXT') return <Markdown content={String(value.content)} skin={skin} />
  if (module === 'knowledge.research' && name === 'evidence') return <WorkflowKnowledgeReport {...props} />
  if (['document.direct-review', 'document.direct-review-check'].includes(module) && ['assessment', 'review'].includes(name)) return <WorkflowDocumentReviewReport {...props} review={name === 'review'} />
  if (module === 'source.test-review' && name === 'review') return <WorkflowTestReviewReport {...props} />
  if (module === 'source.test-write' && name === 'scope') return <WorkflowTestScopeReport {...props} />
  if (module === 'source.test-design' && name === 'design') return <WorkflowTestDesignReport {...props} />
  if (['source.design', 'source.design-review'].includes(module) && ['design', 'review'].includes(name)) return <WorkflowSourceDesignReport {...props} review={name === 'review'} />
  if (value.kind === 'PLAN') return <p>在画布顶部的候选计划中查看完整变更、确认状态和应用记录。</p>
  if (module === 'system.source.test-profile' && ['profile', 'report'].includes(name)) return <WorkflowTestProfileReport {...props} />
  if (['system.source.design-plan', 'system.source.test-plan'].includes(module) && name === 'report') return <WorkflowSourcePlanReport {...props} />
  if (module === 'system.document.review-plan' && name === 'report') return <WorkflowDocumentPlanReport {...props} />
  if (['system.source.design-document', 'system.document.review-report'].includes(module) && name === 'report') return <WorkflowDocumentReport {...props} />
  if (['system.history.plan', 'system.history.report'].includes(module) && name === 'report') return <WorkflowHistorySummary {...props} />
  if (['system.snapshot.plan', 'system.snapshot.report'].includes(module) && name === 'report') return <WorkflowSnapshotSummary {...props} />
  if (['snapshot.analyze', 'snapshot.review'].includes(module) && name === 'analysis') return <WorkflowSnapshotReport {...props} />
  if (['history.review', 'history.contribution'].includes(module) && name === 'analysis') return <WorkflowHistoryAnalysisReport {...props} />
  const specialized = { 'system.review.snapshot': WorkflowReviewSourceReport, 'system.git.history': WorkflowHistoryReport, 'system.repository.snapshot': WorkflowRepositoryReport, 'system.source.snapshot': WorkflowSourceReport, 'system.verify.files': WorkflowVerificationReport, 'system.source.test-summary': WorkflowTestSummaryReport, 'system.source.test-run': WorkflowNativeTestReport, 'system.verify.command': WorkflowCommandReport }
  if (name === 'report' && module in specialized) { const Component = specialized[module as keyof typeof specialized]; return <Component {...props} /> }
  if (['review.requirement', 'review.risk', 'system.review.dual'].includes(module) && ['review', 'report'].includes(name)) return <WorkflowReviewReport {...props} />
  return <Code content={JSON.stringify(value.content, null, 2) ?? ''} language="json" label="原节点交付内容" />
}
