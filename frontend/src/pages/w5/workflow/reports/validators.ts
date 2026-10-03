// Pure validators preserve the original specialist delivery contracts. No UI framework or API calls.
import { workflowReasonLabel } from '@/utils/displayLabels'
import { repositoryBranchLabel } from '@/components/workflow/repository'
import type { DocumentRequirementDetail, RequirementCodeReference } from '@/types/domain'

export function parseCommandReport(content: unknown, review = false) {
  const props = { content, review }
  const report = (() => {
    const value = props.content as Record<string, unknown> | null
    if (!value || value.version !== 1 || value.type !== 'COMMAND' || typeof value.valid !== 'boolean' || typeof value.passed !== 'boolean'
      || typeof value.timedOut !== 'boolean' || typeof value.cancelled !== 'boolean' || typeof value.outputTruncated !== 'boolean'
      || typeof value.output !== 'string' || typeof value.errorCode !== 'string' || typeof value.reportExcerpt !== 'boolean'
      || value.exitCode !== null && !Number.isInteger(value.exitCode)) return null
    return { valid: value.valid, passed: value.valid && value.passed, timedOut: value.timedOut, cancelled: value.cancelled,
      outputTruncated: value.outputTruncated, output: value.output, errorCode: value.errorCode, reportExcerpt: value.reportExcerpt, exitCode: value.exitCode }
  })()
  return report
}

export function parseVerificationReport(content: unknown, review = false) {
  const props = { content, review }
  const checks = (() => {
    const value = props.content as { version?: number; checks?: unknown }
    if (!value || value.version !== 1 || !Array.isArray(value.checks) || !value.checks.length) return null
    return value.checks.map((row: unknown) => {
      const item = row as Record<string, unknown> | null
      return { title: typeof item?.title === 'string' ? item.title : '交付物读取', path: typeof item?.path === 'string' ? item.path : '',
        state: item?.state === 'PASS' ? 'PASS' : item?.state === 'FAIL' ? 'FAIL' : 'ERROR', detail: typeof item?.detail === 'string' ? item.detail : '' }
    })
  })()
  return checks
}

export function parseReviewReport(content: unknown, review = false) {
  const props = { content, review }
  type Opinion = { perspective: 'REQUIREMENT' | 'RISK'; verdict: 'PASS' | 'BLOCKED'; reason: string }
  const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
  const opinion = (value: unknown): value is Opinion => object(value) && ['REQUIREMENT', 'RISK'].includes(String(value.perspective)) && ['PASS', 'BLOCKED'].includes(String(value.verdict)) && typeof value.reason === 'string' && !!value.reason.trim()
  const report = (() => {
    const value = props.content
    if (!object(value) || value.version !== 1) return null
    if (value.type === 'REVIEW' && opinion(value)) return { dual: false, passed: value.verdict === 'PASS', verified: null, opinions: [value] }
    if (value.type !== 'DUAL_REVIEW' || typeof value.passed !== 'boolean' || typeof value.verificationPassed !== 'boolean' || !Array.isArray(value.reviews) || value.reviews.length !== 2 || !value.reviews.every(opinion) || new Set(value.reviews.map(row => row.perspective)).size !== 2) return null
    const proven = value.verificationPassed && value.reviews.every(row => row.verdict === 'PASS')
    if (value.passed !== proven) return null
    return { dual: true, passed: value.passed, verified: value.verificationPassed, opinions: value.reviews }
  })()
  return report
}

export function parseRepositoryReport(content: unknown, review = false) {
  const props = { content, review }
  const report = (() => {
    const value = props.content as Record<string, unknown> | null
    if (!value || value.version !== 1 || value.type !== 'REPOSITORY_SOURCE' || typeof value.complete !== 'boolean' || typeof value.branchId !== 'string') return null
    const count = (name: string) => typeof value[name] === 'number' && Number.isSafeInteger(value[name]) && Number(value[name]) >= 0 ? Number(value[name]) : null
    const files = count('fileCount'), excluded = count('excludedCount')
    if (value.complete && (typeof value.commitSha !== 'string' || !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(value.commitSha) || typeof value.projectPrefix !== 'string' || files == null || files > 50000 || excluded == null || excluded > files)) return null
    return { complete: value.complete, branch: repositoryBranchLabel(value.branchId), commit: value.complete ? String(value.commitSha) : '', prefix: value.projectPrefix, files, excluded, code: typeof value.code === 'string' ? value.code : null }
  })()
  return report
}

export function parseSourceReport(content: unknown, review = false) {
  const props = { content, review }
  const report = (() => {
    const value = props.content as Record<string, unknown> | null
    if (!value || value.version !== 1 || value.type !== 'SOURCE_SNAPSHOT' || typeof value.complete !== 'boolean') return null
    const count = (name: string) => typeof value[name] === 'number' && Number.isSafeInteger(value[name]) && Number(value[name]) >= 0 ? Number(value[name]) : null
    if (value.complete && (!count('targetCount') || count('incompleteCount') !== 0 || count('fileCount') == null || count('excludedCount') == null)) return null
    const exclusions = (Array.isArray(value.exclusions) ? value.exclusions : []).flatMap(row => row && typeof row.path === 'string' && typeof row.reason === 'string' ? [{ path: row.path as string, reason: row.reason as string }] : [])
    return { complete: value.complete, path: typeof value.sourcePath === 'string' ? value.sourcePath : '', code: typeof value.code === 'string' ? value.code : null,
      targets: count('targetCount'), files: count('fileCount'), incomplete: count('incompleteCount'), excluded: count('excludedCount'), exclusions }
  })()
  return report
}

export function parseHistoryReport(content: unknown, review = false) {
  const props = { content, review }
  const report = (() => {
    const v = props.content as Record<string, unknown> | null
    if (!v || v.version !== 1 || v.type !== 'GIT_HISTORY' || typeof v.complete !== 'boolean' || typeof v.branchId !== 'string'
      || typeof v.startDate !== 'string' || typeof v.endDate !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(v.startDate) || !/^\d{4}-\d{2}-\d{2}$/.test(v.endDate)
      || v.endDate < v.startDate || v.timezone !== 'Asia/Shanghai') return null
    const count = (key: string) => typeof v[key] === 'number' && Number.isSafeInteger(v[key]) && Number(v[key]) >= 0 ? Number(v[key]) : null
    const commits = count('commitCount'), changes = count('changeCount'), excluded = count('excludedCount')
    if (v.complete && (typeof v.commitSha !== 'string' || !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(v.commitSha) || typeof v.projectPrefix !== 'string'
      || commits == null || commits > 100000 || changes == null || excluded == null || excluded > changes || commits === 0 && changes !== 0)) return null
    return { complete: v.complete, branch: repositoryBranchLabel(v.branchId), commit: v.complete ? String(v.commitSha) : '', prefix: v.projectPrefix,
      start: v.startDate, end: v.endDate, commits, changes, excluded, code: typeof v.code === 'string' ? v.code : null }
  })()
  return report
}

export function parseReviewSourceReport(content: unknown, review = false) {
  const props = { content, review }
  const report = (() => {
    const v = props.content as Record<string, unknown> | null
    if (!v || v.version !== 1 || v.type !== 'REVIEW_SOURCE' || typeof v.complete !== 'boolean' || typeof v.branchId !== 'string'
      || !['FULL', 'DATE_INCREMENTAL'].includes(String(v.mode)) || v.timezone !== 'Asia/Shanghai') return null
    const date = (value: unknown) => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)
    if (v.mode === 'FULL' ? v.startDate != null || v.endDate != null : !date(v.startDate) || !date(v.endDate) || String(v.endDate) < String(v.startDate)) return null
    const sha = (value: unknown) => typeof value === 'string' && /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(value)
    const count = (value: unknown) => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= 100000
    if (v.complete && (!sha(v.sourceSha) || !sha(v.targetSha) || (v.mode === 'FULL' ? v.baselineSha != null : !sha(v.baselineSha))
      || typeof v.projectPrefix !== 'string' || typeof v.capturedAt !== 'string' || !Number.isFinite(Date.parse(v.capturedAt))
      || typeof v.noChanges !== 'boolean' || typeof v.nonMonotonic !== 'boolean' || !count(v.unitCount) || !count(v.excludedCount)
      || Number(v.excludedCount) > Number(v.unitCount) || v.noChanges && v.unitCount !== 0)) return null
    return { complete: v.complete, mode: v.mode, branch: repositoryBranchLabel(v.branchId), start: v.startDate, end: v.endDate,
      source: String(v.sourceSha || ''), baseline: String(v.baselineSha || ''), target: String(v.targetSha || ''), prefix: v.projectPrefix,
      units: v.unitCount, excluded: v.excludedCount, noChanges: v.noChanges, nonMonotonic: v.nonMonotonic, code: typeof v.code === 'string' ? v.code : null }
  })()
  return report
}

export function parseDocumentReport(content: unknown, review = false) {
  const props = { content, review }
  const report = (() => {
    const value = props.content as Record<string, unknown> | null
    if (!value || value.version !== 1 || !['DESIGN_DOCUMENT', 'ASSESSMENT_DOCUMENT'].includes(String(value.type)) || typeof value.complete !== 'boolean') return null
    const assessment = value.type === 'ASSESSMENT_DOCUMENT'
    if (!value.complete) return { complete: false as const, code: typeof value.code === 'string' ? value.code : null }
    if (!['sourceCount', 'draftCount', 'reviewedCount', 'reviseCount', 'fileCount'].every(key => Number.isSafeInteger(value[key]) && Number(value[key]) >= 0)) return null
    const sourceCount = Number(value.sourceCount), draftCount = Number(value.draftCount), reviewedCount = Number(value.reviewedCount), reviseCount = Number(value.reviseCount), fileCount = Number(value.fileCount)
    if (!sourceCount || !draftCount || fileCount < (assessment ? 2 : 3) || reviewedCount + reviseCount > draftCount || !['REQUIRED', 'NONE'].includes(String(value.reviewPolicy))) return null
    if (value.reviewPolicy === 'REQUIRED' && reviewedCount !== draftCount) return null
    if (assessment && (!['requirementCount', 'findingCount'].every(key => Number.isSafeInteger(value[key]) && Number(value[key]) >= 0) || typeof value.allRequirementsSatisfied !== 'boolean' || value.requirementCount === 0 && value.allRequirementsSatisfied || value.testExecution !== 'NOT_RUN_STATIC_REVIEW')) return null
    if (assessment && (!Number.isSafeInteger(value.crossBatchReviewedCount) || Number(value.crossBatchReviewedCount) < 0 || Number(value.crossBatchReviewedCount) > reviewedCount || value.reviewPolicy === 'REQUIRED' && value.crossBatchReviewedCount !== draftCount)) return null
    return { complete: true as const, crossBatchReviewedCount: Number(value.crossBatchReviewedCount ?? reviewedCount), assessment, requirementCount: Number(value.requirementCount), findingCount: Number(value.findingCount), satisfied: value.allRequirementsSatisfied === true, sourceCount, draftCount, reviewedCount, reviseCount, fileCount, required: value.reviewPolicy === 'REQUIRED' }
  })()
  return report
}

export function parseDocumentPlanReport(content: unknown, review = false) {
  const props = { content, review }
  const report = (() => {
    const value = props.content as Record<string, unknown> | null
    if (!value || value.version !== 1 || value.type !== 'DOCUMENT_REVIEW_PLAN' || typeof value.complete !== 'boolean') return null
    if (!value.complete) return { complete: false as const, code: typeof value.code === 'string' ? value.code : null }
    if (!Number.isSafeInteger(value.sectionCount) || Number(value.sectionCount) < 1 || !Number.isSafeInteger(value.batchCount) || Number(value.batchCount) < 1 || Number(value.batchCount) > 62 || !Array.isArray(value.batches) || value.batches.length !== value.batchCount) return null
    const seen = new Set<string>(), batches: { ordinal: number; characters: number; sections: string[] }[] = []
    for (const [index, batch] of value.batches.entries()) {
      if (!batch || batch.ordinal !== index || !Number.isSafeInteger(batch.characters) || batch.characters < 0 || !Array.isArray(batch.sections) || !batch.sections.length || batch.sections.length > 256 || batch.sections.length > 1 && batch.characters > 48000) return null
      const sections: string[] = []
      for (const source of batch.sections) {
        if (!source || typeof source.fileId !== 'string' || !/^DOC-[1-9][0-9]?$/.test(source.fileId) || !Number.isSafeInteger(source.section) || source.section < 1) return null
        const id = `${source.fileId}:${source.section}`
        if (seen.has(id)) return null
        seen.add(id); sections.push(`原文 ${source.fileId.slice(4)} · 第 ${source.section} 章`)
      }
      batches.push({ ordinal: index, characters: batch.characters, sections })
    }
    if (seen.size !== value.sectionCount) return null
    return { complete: true as const, sectionCount: Number(value.sectionCount), batchCount: Number(value.batchCount), batches }
  })()
  return report
}

export function parseSourcePlanReport(content: unknown, review = false) {
  const props = { content, review }
  const report = (() => {
    const value = props.content as Record<string, unknown> | null
    if (!value || value.version !== 1 || !['SOURCE_DESIGN_PLAN', 'SOURCE_TEST_PLAN'].includes(String(value.type)) || typeof value.complete !== 'boolean') return null
    if (!value.complete) return { complete: false as const, code: typeof value.code === 'string' ? value.code : null }
    if (!Number.isSafeInteger(value.sourceCount) || Number(value.sourceCount) < 1 || !Number.isSafeInteger(value.batchCount) || Number(value.batchCount) < 1 || Number(value.batchCount) > 63 || !Array.isArray(value.batches) || value.batches.length !== value.batchCount) return null
    const paths = new Set<string>(), batches: { ordinal: number; title: string; paths: string[] }[] = []
    for (const [index, batch] of value.batches.entries()) {
      if (!batch || batch.ordinal !== index || typeof batch.title !== 'string' || !Array.isArray(batch.paths) || !batch.paths.length || batch.paths.length > 12) return null
      for (const path of batch.paths) { if (typeof path !== 'string' || !path || paths.has(path)) return null; paths.add(path) }
      batches.push({ ordinal: index, title: batch.title, paths: batch.paths })
    }
    if (paths.size !== value.sourceCount) return null
    return { complete: true as const, sourceCount: Number(value.sourceCount), batchCount: Number(value.batchCount), batches }
  })()
  return report
}

export function parseTestProfileReport(content: unknown, review = false) {
  const props = { content, review }
  type Module = { root: string; framework: string; sourcePaths: string[]; testRoots: string[]; fixtureRoots: string[]; command: string[] }
  const frameworks: Record<string, string> = { junit: 'JUnit', testng: 'TestNG', jest: 'Jest', vitest: 'Vitest', pytest: 'pytest' }
  const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(item => typeof item === 'string' && item.length > 0)
  const report = (() => {
    const value = props.content as Record<string, unknown> | null
    if (!value || value.version !== 1 || value.type !== 'SOURCE_TEST_PROFILE') return null
    if (value.complete === false) return { kind: 'failed' as const, message: typeof value.message === 'string' ? value.message : workflowReasonLabel(typeof value.code === 'string' ? value.code : null) }
    if (value.complete === true) {
      if (!Number.isSafeInteger(value.moduleCount) || Number(value.moduleCount) < 1 || !Number.isSafeInteger(value.sourceCount) || Number(value.sourceCount) < 1) return null
      return { kind: 'summary' as const, moduleCount: Number(value.moduleCount), sourceCount: Number(value.sourceCount) }
    }
    const profile = value.profile as { manifestSha256?: unknown; modules?: unknown } | null
    if (!profile || typeof profile.manifestSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(profile.manifestSha256) || !Array.isArray(profile.modules) || !profile.modules.length || profile.modules.length > 1024) return null
    const modules: Module[] = [], paths = new Set<string>()
    for (const module of profile.modules) {
      if (!module || typeof module.root !== 'string' || !module.root || typeof module.framework !== 'string' || !Object.prototype.hasOwnProperty.call(frameworks, module.framework)
        || !strings(module.sourcePaths) || !module.sourcePaths.length || !strings(module.testRoots) || !module.testRoots.length
        || !strings(module.fixtureRoots) || !strings(module.command) || !module.command.length) return null
      for (const path of module.sourcePaths) { if (paths.has(path)) return null; paths.add(path) }
      modules.push(module)
    }
    return { kind: 'profile' as const, modules }
  })()
  return report
}

export function parseTestDesignReport(content: unknown, review = false) {
  const props = { content, review }
  type Reference = { path: string; startLine: number; endLine: number; quote: string }
  type Scenario = { key: string; path: string; category: keyof typeof categories; title: string; steps: string[]; expected: string; references: Reference[] }
  const categories = { NORMAL: '正常', BOUNDARY: '边界', ERROR: '异常', BRANCH: '关键分支' }
  const text = (value: unknown): value is string => typeof value === 'string' && !!value.trim()
  const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(text)
  const report = (() => {
    const value = props.content as { version?: unknown; type?: unknown; design?: { title?: unknown; summary?: unknown; scenarios?: unknown; limitations?: unknown } } | null
    const design = value?.design
    if (value?.version !== 1 || value.type !== 'SOURCE_TEST_DESIGN' || !design || !text(design.title) || !text(design.summary) || !strings(design.limitations) || !Array.isArray(design.scenarios) || !design.scenarios.length) return null
    const keys = new Set<string>()
    for (const item of design.scenarios) {
      if (!item || !text(item.key) || keys.has(item.key) || !text(item.path) || !Object.prototype.hasOwnProperty.call(categories, item.category) || !text(item.title) || !strings(item.steps) || !item.steps.length || !text(item.expected) || !Array.isArray(item.references) || !item.references.length) return null
      if (!item.references.every((ref: Reference) => ref && ref.path === item.path && Number.isSafeInteger(ref.startLine) && ref.startLine >= 1 && Number.isSafeInteger(ref.endLine) && ref.endLine >= ref.startLine && text(ref.quote))) return null
      keys.add(item.key)
    }
    return { title: design.title, summary: design.summary, scenarios: design.scenarios as Scenario[], limitations: design.limitations }
  })()
  return report
}

export function parseTestScopeReport(content: unknown, review = false) {
  const props = { content, review }
  const report = (() => {
    const value = props.content as Record<string, unknown> | null
    if (!value || value.version !== 1 || value.type !== 'SOURCE_TEST_SCOPE' || typeof value.passed !== 'boolean' || value.testsExecuted !== false
      || typeof value.message !== 'string' || !value.message.trim()) return null
    return { passed: value.passed, message: value.message }
  })()
  return report
}

export function parseNativeTestReport(content: unknown, review = false) {
  const props = { content, review }
  const frameworks: Record<string, string> = { junit: 'JUnit', testng: 'TestNG', pytest: 'pytest', jest: 'Jest', vitest: 'Vitest' }
  const report = (() => {
    const v = props.content as Record<string, unknown> | null
    const c = v?.counts as Record<string, unknown> | undefined
    if (!v || ![1, 2].includes(Number(v.version)) || typeof v.version !== 'number' || v.type !== 'SOURCE_TEST_RUN' || typeof v.valid !== 'boolean' || typeof v.passed !== 'boolean'
      || typeof v.moduleRoot !== 'string' || typeof v.framework !== 'string' || !frameworks[v.framework] || typeof v.message !== 'string' || !c
      || !['total', 'passed', 'failed', 'skipped'].every(k => Number.isInteger(c[k]) && Number(c[k]) >= 0)
      || Number(c.total) !== Number(c.passed) + Number(c.failed) + Number(c.skipped) || !Array.isArray(v.command) || !v.command.every(a => typeof a === 'string')
      || !Array.isArray(v.files) || v.files.some(f => !f || typeof f.path !== 'string') || !Number.isInteger(v.fileCount) || Number(v.fileCount) < v.files.length
      || v.scenarioCoverageVerified !== false || v.exitCode !== null && !Number.isInteger(v.exitCode)
      || v.inputUnchanged !== undefined && v.inputUnchanged !== null && typeof v.inputUnchanged !== 'boolean') return null
    let batchCount = 0
    if (v.version === 2) {
      const batches = v.batches as Record<string, unknown>[] | undefined
      const writers = v.writerLineage
      if (!Array.isArray(writers) || !writers.length || writers.length > 256 || writers.some(w => typeof w !== 'string' || !w)
        || new Set(writers).size !== writers.length || writers[0] !== v.producerAttempt
        || !Array.isArray(batches) || !batches.length || batches.length > 61 || batches.some(b => !b
          || typeof b.inputName !== 'string' || !/^design(?:_[a-zA-Z0-9_]+)?$/.test(b.inputName)
          || typeof b.designAttempt !== 'string' || !b.designAttempt || typeof b.designSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(b.designSha256)
          || !writers.includes(b.writerAttempt) || !Number.isInteger(b.scenarioCount) || Number(b.scenarioCount) < 1 || Number(b.scenarioCount) > 64)
        || new Set(batches.map(b => b.inputName)).size !== batches.length || !batches.some(b => b.inputName === 'design')
        || new Set(batches.map(b => b.designAttempt)).size !== batches.length) return null
      batchCount = batches.length
    }
    const executed = Number(c.passed) + Number(c.failed)
    const valid = v.valid && executed > 0 && v.inputUnchanged !== false
    return { module: v.moduleRoot, framework: frameworks[v.framework], command: v.command, message: v.message,
      batchCount, inputUnchanged: v.inputUnchanged, valid, passed: valid && v.passed && Number(c.failed) === 0 && v.exitCode === 0, executed, succeeded: Number(c.passed), failed: Number(c.failed), skipped: Number(c.skipped), files: v.files as { path: string }[], fileCount: Number(v.fileCount) }
  })()
  return report
}

export function parseTestReviewReport(content: unknown, review = false) {
  const props = { content, review }
  type Case = { id: string; name: string; status: 'PASSED' | 'FAILED' | 'SKIPPED'; reportPath: string }
  type Reference = { path: string; startLine: number; endLine: number; quote: string }
  type Scenario = { key: string; title: string; path: string; status: string; reason: string; tests: Case[]; references: Reference[] }
  const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
  const text = (v: unknown): v is string => typeof v === 'string' && !!v.trim()
  const statuses = ['COVERED', 'MISSING', 'INSUFFICIENT', 'FAILED', 'NOT_EXECUTED', 'EVIDENCE_INCOMPLETE']
  const report = (() => {
    const value = props.content
    if (!object(value) || value.version !== 1 || value.type !== 'SOURCE_TEST_REVIEW' || !['PASS', 'REVISE'].includes(String(value.verdict)) || !text(value.reason) || typeof value.nativePassed !== 'boolean' || !Array.isArray(value.scenarios) || !value.scenarios.length) return null
    const keys = new Set<string>()
    for (const row of value.scenarios) {
      if (!object(row) || !text(row.key) || keys.has(row.key) || !text(row.title) || !text(row.path) || !text(row.reason) || !statuses.includes(String(row.status)) || !Array.isArray(row.tests) || !Array.isArray(row.references)) return null
      if (!row.tests.every(t => object(t) && text(t.id) && text(t.name) && text(t.reportPath) && ['PASSED', 'FAILED', 'SKIPPED'].includes(String(t.status)))) return null
      if (value.nativePassed && row.tests.some(t => t.status === 'FAILED')) return null
      if (!row.references.every(r => object(r) && text(r.path) && text(r.quote) && Number.isSafeInteger(r.startLine) && Number(r.startLine) >= 1 && Number.isSafeInteger(r.endLine) && Number(r.endLine) >= Number(r.startLine))) return null
      if (row.status === 'COVERED' && (row.assessment !== 'COVERED' || !row.tests.length || !row.references.length || !row.tests.every(t => t.status === 'PASSED') || value.inputUnchanged !== true)) return null
      keys.add(row.key)
    }
    if ((value.verdict === 'PASS') !== (value.nativePassed && value.scenarios.every(s => s.status === 'COVERED'))) return null
    return { passed: value.verdict === 'PASS', reason: value.reason, nativePassed: value.nativePassed, rows: value.scenarios as Scenario[] }
  })()
  return report
}

export function parseTestSummaryReport(content: unknown, review = false) {
  const props = { content, review }
  const policies: Record<string, string> = { NONE: '不要求复核', SINGLE: '每批一份独立复核', DUAL: '每批两位独立角色复核' }
  const report = (() => {
    const v = props.content as Record<string, unknown> | null
    if (!v || v.version !== 1 || v.type !== 'SOURCE_TEST_SUMMARY'
      || !['complete', 'passed', 'testPassed', 'reviewSatisfied'].every(k => typeof v[k] === 'boolean')
      || !['sourceCount', 'coveredSourceCount', 'moduleCount', 'batchCount', 'reviewedBatchCount'].every(k => Number.isSafeInteger(v[k]) && Number(v[k]) >= 0)
      || !Number(v.sourceCount) || !Number(v.moduleCount) || !Number(v.batchCount) || Number(v.coveredSourceCount) > Number(v.sourceCount)
      || typeof v.reviewPolicy !== 'string' || !policies[v.reviewPolicy] || Number(v.reviewedBatchCount) > Number(v.batchCount)
      || !Array.isArray(v.modules) || !v.modules.length || v.modules.length > Number(v.moduleCount)
      || !Array.isArray(v.batches) || v.batches.length !== v.batchCount || v.batches.length > 61 * 61) return null
    const modules: { root: string; passed: boolean; executed: number; failed: number; skipped: number }[] = []
    for (const m of v.modules) {
      const c = m?.counts
      if (!m || typeof m.root !== 'string' || !m.root || typeof m.passed !== 'boolean' || !c
        || !['total', 'passed', 'failed', 'skipped'].every(k => Number.isSafeInteger(c[k]) && c[k] >= 0)
        || c.total !== c.passed + c.failed + c.skipped || modules.some(item => item.root === m.root)) return null
      if (m.passed && (c.passed < 1 || c.failed !== 0)) return null
      modules.push({ root: m.root, passed: m.passed, executed: c.passed + c.failed, failed: c.failed, skipped: c.skipped })
    }
    const batches: { module: string; count: number; satisfied: boolean; opinions: number }[] = []
    for (const b of v.batches) {
      if (!b || !modules.some(m => m.root === b.moduleRoot) || !Number.isSafeInteger(b.scenarioCount) || b.scenarioCount < 1 || b.scenarioCount > 64
        || typeof b.reviewSatisfied !== 'boolean' || !Array.isArray(b.reviews) || b.reviews.length > 2) return null
      batches.push({ module: b.moduleRoot, count: b.scenarioCount, satisfied: b.reviewSatisfied, opinions: b.reviews.length })
    }
    if (v.complete && (v.coveredSourceCount !== v.sourceCount || modules.length !== v.moduleCount)
      || v.testPassed !== modules.every(m => m.passed) || v.reviewSatisfied !== batches.every(b => b.satisfied)
      || v.passed !== (v.complete && v.testPassed && v.reviewSatisfied)) return null
    return { passed: v.passed, covered: v.coveredSourceCount, total: v.sourceCount, policy: policies[v.reviewPolicy], reviewRequired: v.reviewPolicy !== 'NONE', modules, batches }
  })()
  return report
}

export function parseHistorySummary(content: unknown, review = false) {
  const props = { content, review }
  const report = (() => {
    const v = props.content as Record<string, unknown> | null
    if (!v || v.version !== 1 || !['HISTORY_PLAN', 'HISTORY_DOCUMENT'].includes(String(v.type)) || typeof v.complete !== 'boolean') return null
    const plan = v.type === 'HISTORY_PLAN'
    if (!v.complete) return { complete: false as const, plan, code: typeof v.code === 'string' ? v.code : null }
    const count = plan ? 'commitCount' : 'sourceCount'
    if (![count, 'unitCount', 'batchCount', 'contributorCount', ...(plan ? [] : ['fileCount', 'assessedContributorCount'])].every(key => Number.isSafeInteger(v[key]) && Number(v[key]) >= 0)) return null
    if (Number(v.batchCount) > 63 || Number(v[count]) > 100000 || !plan && (Number(v.fileCount) < 3 || Number(v.fileCount) > 10000 || Number(v.assessedContributorCount) > Number(v.contributorCount) || !['CODE_REVIEW', 'CONTRIBUTION_REPORT'].includes(String(v.reportKind)))) return null
    if (Number(v[count]) === 0 && (v.unitCount !== 0 || v.batchCount !== 0 || v.contributorCount !== 0)) return null
    return { complete: true as const, plan, commits: Number(v[count]), units: Number(v.unitCount), batches: Number(v.batchCount), people: Number(v.contributorCount), files: Number(v.fileCount), contribution: v.reportKind === 'CONTRIBUTION_REPORT' }
  })()
  return report
}

export function parseSnapshotSummary(content: unknown, review = false) {
  const props = { content, review }
  const report = (() => {
    const v = props.content as Record<string, unknown> | null
    if (!v || v.version !== 1 || !['SNAPSHOT_PLAN', 'SNAPSHOT_DOCUMENT'].includes(String(v.type)) || typeof v.complete !== 'boolean') return null
    const plan = v.type === 'SNAPSHOT_PLAN'
    if (!v.complete) return { complete: false as const, plan, code: typeof v.code === 'string' ? v.code : null }
    const units = plan ? 'unitCount' : 'sourceCount'
    if (![units, 'excludedCount', 'batchCount', ...(plan ? [] : ['draftCount', 'reviewedCount', 'candidateCount', 'supportedCount', 'fileCount'])].every(key => Number.isSafeInteger(v[key]) && Number(v[key]) >= 0)) return null
    if (Number(v.excludedCount) > Number(v[units]) || Number(v.batchCount) > 63 || plan && typeof v.conditionalReviews !== 'boolean') return null
    if (!plan && (v.draftCount !== v.batchCount || Number(v.reviewedCount) > Number(v.batchCount) || Number(v.supportedCount) > Number(v.candidateCount) || Number(v.fileCount) < 4 || Number(v.fileCount) > 10000 || !['REQUIRED', 'NONE'].includes(String(v.reviewPolicy)) || typeof v.targetSha !== 'string' || !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(v.targetSha))) return null
    return { complete: true as const, plan, units: Number(v[units]), excluded: Number(v.excludedCount), batches: Number(v.batchCount), reviews: Number(v.reviewedCount), candidates: Number(v.candidateCount), supported: Number(v.supportedCount), files: Number(v.fileCount), conditional: v.conditionalReviews, required: v.reviewPolicy === 'REQUIRED', target: String(v.targetSha) }
  })()
  return report
}

export function parseSnapshotReport(content: unknown, review = false) {
  const props = { content, review }
  type Reuse = { sourceRequirementId: string; sourceAttemptId: string; sourceTitle: string; sourceNodeTitle: string; sourceDeliverySha256: string }
  type Reference = { version: string; path: string; blob: string; startLine: number; endLine: number; quote: string }
  type Finding = { key: string; severity: string; title: string; trigger: string; behavior: string; recommendation: string; attribution: string; evidence: Reference[] }
  type Problem = { key: string; title: string }
  type Coverage = { unitId: string; conclusion: string; evidence: Reference[]; limitations: string[] }
  type Decision = { findingKey: string; verdict: string; reason: string; duplicateOf: string | null; evidence: Reference[] }
  const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
  const text = (v: unknown): v is string => typeof v === 'string' && v.trim().length > 0
  const strings = (v: unknown): v is string[] => Array.isArray(v) && v.length <= 64 && v.every(text)
  const integer = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0
  const sha = (v: unknown) => typeof v === 'string' && /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(v)
  const references = (v: unknown): v is Reference[] => Array.isArray(v) && v.length <= 64 && v.every(r => object(r) && sha(r.version) && sha(r.blob) && text(r.path) && integer(r.startLine) && r.startLine > 0 && integer(r.endLine) && r.endLine >= r.startLine && text(r.quote))
  const problems = (v: unknown): v is Problem[] => Array.isArray(v) && new Set(v.map(f => f?.key)).size === v.length && v.every(f => object(f) && text(f.key) && text(f.title))
  const findings = (v: unknown): v is Finding[] => Array.isArray(v) && new Set(v.map(f => f?.key)).size === v.length && v.every(f => object(f) && text(f.key) && text(f.title) && text(f.trigger) && text(f.behavior) && text(f.recommendation) && ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].includes(String(f.severity)) && ['CHANGE_RELATED', 'EXISTING', 'UNDETERMINED'].includes(String(f.attribution)) && references(f.evidence) && f.evidence.length > 0)
  const report = (() => {
    const v = props.content
    if (!object(v) || v.version !== 1 || !object(v.source) || v.source.version !== 1 || v.source.type !== 'REVIEW_SOURCE' || typeof v.source.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(v.source.sha256) || !object(v.claims) || !strings(v.claims.limitations)) return null
    const c = v.claims
    if (v.type === 'SNAPSHOT_ANALYSIS') {
      if (!integer(v.batchOrdinal) || !integer(v.batchCount) || v.batchOrdinal >= v.batchCount || !findings(c.findings) || !Array.isArray(c.coverage) || c.coverage.length < 1 || c.coverage.length > 64 || !Array.isArray(v.locations) || v.locations.length !== c.coverage.length || !Array.isArray(c.supplements) || c.supplements.length) return null
      const coverage = c.coverage as Coverage[], locations = v.locations as { unitId: string; path: string }[]
      if (!coverage.every(item => item && text(item.unitId) && text(item.conclusion) && references(item.evidence) && strings(item.limitations)) || !locations.every(item => item && text(item.unitId) && text(item.path)) || new Set(coverage.map(item => item.unitId)).size !== coverage.length || new Set(locations.map(item => item.unitId)).size !== locations.length || coverage.some(item => !locations.some(l => l.unitId === item.unitId))) return null
      const reuse = v.reuse == null ? null : v.reuse
      if (reuse !== null && (!object(reuse) || !text(reuse.sourceRequirementId) || !text(reuse.sourceAttemptId) || !text(reuse.sourceTitle) || !text(reuse.sourceNodeTitle) || typeof reuse.sourceDeliverySha256 !== 'string' || !/^[a-f0-9]{64}$/.test(reuse.sourceDeliverySha256) || c.findings.length || (c.limitations as string[]).length || coverage.some(item => item.evidence.length || item.limitations.length))) return null
      return { reuse: reuse as Reuse | null, kind: 'analysis' as const, batch: v.batchOrdinal + 1, total: v.batchCount, findings: c.findings, limitations: c.limitations as string[], coverage: coverage.map(item => ({ ...item, path: locations.find(l => l.unitId === item.unitId)!.path })) }
    }
    if (v.type !== 'SNAPSHOT_REVIEW' || !text(v.analysisAttempt) || !problems(v.findings) || !v.findings.length || !Array.isArray(c.checkedUnitIds) || !c.checkedUnitIds.every(text) || new Set(c.checkedUnitIds).size !== c.checkedUnitIds.length || !references(c.evidence) || !text(c.conclusion) || !Array.isArray(c.decisions) || c.decisions.length !== v.findings.length) return null
    const decisions = c.decisions as Decision[], known = v.findings
    if (!decisions.every(d => d && text(d.findingKey) && text(d.reason) && ['SUPPORTED', 'UNDETERMINED', 'DISMISSED', 'DUPLICATE'].includes(d.verdict) && references(d.evidence) && (d.verdict === 'UNDETERMINED' || d.evidence.length > 0) && (d.verdict === 'DUPLICATE' ? text(d.duplicateOf) && d.duplicateOf !== d.findingKey : d.duplicateOf == null)) || new Set(decisions.map(d => d.findingKey)).size !== known.length || known.some(f => !decisions.some(d => d.findingKey === f.key))) return null
    return { kind: 'review' as const, conclusion: c.conclusion, limitations: c.limitations as string[], decisions: decisions.map(d => ({ ...d, finding: known.find(f => f.key === d.findingKey)! })) }
  })()
  return report
}

export function parseHistoryAnalysisReport(content: unknown, review = false) {
  const props = { content, review }
  type Finding = { severity: string; side: string; line: number; title: string; detail: string; recommendation: string }
  type Review = { unitId: string; summary: string; findings: Finding[]; limitations: string[] }
  type Location = { unitId: string; commitSha: string; path: string }
  type Dimension = { level: number; reason: string; evidenceIds: string[] }
  const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
  const strings = (v: unknown): v is string[] => Array.isArray(v) && v.every(s => typeof s === 'string')
  const integer = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0
  const text = (v: unknown): v is string => typeof v === 'string' && v.trim().length > 0
  const report = (() => {
    const v = props.content
    if (!object(v) || v.version !== 1 || !object(v.source) || v.source.version !== 1 || v.source.type !== 'GIT_HISTORY' || !text(v.source.sha256) || !/^[a-f0-9]{64}$/.test(v.source.sha256)) return null
    if (v.type === 'HISTORY_REVIEW') {
      if (!integer(v.batchOrdinal) || !integer(v.batchCount) || v.batchOrdinal >= v.batchCount || !Array.isArray(v.reviews) || v.reviews.length < 1 || v.reviews.length > 12 || !Array.isArray(v.locations) || v.locations.length !== v.reviews.length) return null
      const locations = v.locations as Location[], reviews = v.reviews as Review[]
      if (!locations.every(l => l && text(l.unitId) && typeof l.path === 'string' && typeof l.commitSha === 'string' && /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(l.commitSha)) || new Set(locations.map(l => l.unitId)).size !== reviews.length) return null
      if (!reviews.every(r => r && text(r.unitId) && text(r.summary) && strings(r.limitations) && r.limitations.length <= 32 && Array.isArray(r.findings) && r.findings.length <= 32
        && r.findings.every(f => f && ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].includes(f.severity) && ['BEFORE', 'AFTER'].includes(f.side) && integer(f.line) && f.line > 0 && text(f.title) && text(f.detail) && text(f.recommendation)))
        || new Set(reviews.map(r => r.unitId)).size !== reviews.length || reviews.some(r => !locations.some(l => l.unitId === r.unitId))) return null
      return { kind: 'review' as const, batch: v.batchOrdinal + 1, total: v.batchCount, reviews: reviews.map(r => ({ ...r, location: locations.find(l => l.unitId === r.unitId)! })) }
    }
    if (v.type !== 'HISTORY_CONTRIBUTION' || !object(v.person) || !object(v.person.author) || !object(v.assessment) || !strings(v.reviewAttempts) || v.reviewAttempts.length === 0) return null
    const person = v.person, author = v.person.author, assessment = v.assessment
    if (!text(author.name) || !text(author.identity) || typeof author.email !== 'string' || typeof author.robot !== 'boolean' || author.identity !== assessment.identity || !text(assessment.summary)
      || !strings(person.commits) || !strings(person.evidenceIds) || ![person.rawLines, person.effectiveLines].every(n => typeof n === 'number' && Number.isFinite(n) && n >= 0)) return null
    const titles = [['value', '代码价值'], ['difficulty', '必要技术难度'], ['quality', '质量与验证证据'], ['maintenance', '工程维护']] as const
    if (!titles.every(([key]) => { const d = assessment[key]; return object(d) && integer(d.level) && d.level <= 4 && text(d.reason) && strings(d.evidenceIds) && d.evidenceIds.length > 0 && d.evidenceIds.every(id => (person.evidenceIds as string[]).includes(id)) })) return null
    return { kind: 'contribution' as const, name: author.name, email: author.email, robot: author.robot, commits: person.commits.length,
      raw: Number(person.rawLines).toLocaleString('zh-CN', { maximumFractionDigits: 2 }), effective: Number(person.effectiveLines).toLocaleString('zh-CN', { maximumFractionDigits: 2 }), summary: assessment.summary,
      dimensions: titles.map(([key, title]) => ({ title, ...assessment[key] as Dimension })) }
  })()
  return report
}

export function parseSourceDesignReport(content: unknown, review = false) {
  const props = { content, review }
  type Reference = { path: string; startLine: number; endLine: number; quote: string }
  type Section = { key: string; title: string; markdown: string; paths: string[]; references: Reference[] }
  type Issue = { sectionKey: string; detail: string; recommendation: string }
  const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(item => typeof item === 'string')
  function references(value: unknown): value is Reference[] {
    return Array.isArray(value) && value.length > 0 && value.every(item => item && typeof item.path === 'string' && Number.isSafeInteger(item.startLine) && item.startLine >= 1 && Number.isSafeInteger(item.endLine) && item.endLine >= item.startLine && typeof item.quote === 'string')
  }
  const design = (() => {
    if (props.review) return null
    const value = props.content as { title?: unknown; summary?: unknown; sections?: unknown; limitations?: unknown } | null
    if (!value || typeof value.title !== 'string' || typeof value.summary !== 'string' || !strings(value.limitations) || !Array.isArray(value.sections) || !value.sections.length) return null
    if (!value.sections.every(item => item && typeof item.key === 'string' && typeof item.title === 'string' && typeof item.markdown === 'string' && strings(item.paths) && references(item.references))) return null
    return { title: value.title, summary: value.summary, sections: value.sections as Section[], limitations: value.limitations }
  })()
  const opinion = (() => {
    if (!props.review) return null
    const value = props.content as { verdict?: unknown; reason?: unknown; checkedPaths?: unknown; references?: unknown; issues?: unknown } | null
    if (!value || (value.verdict !== 'PASS' && value.verdict !== 'REVISE') || typeof value.reason !== 'string' || !strings(value.checkedPaths) || !value.checkedPaths.length || !references(value.references) || !Array.isArray(value.issues)) return null
    if ((value.verdict === 'PASS') !== (value.issues.length === 0) || !value.issues.every(item => item && typeof item.sectionKey === 'string' && typeof item.detail === 'string' && typeof item.recommendation === 'string')) return null
    return { verdict: value.verdict, reason: value.reason, paths: value.checkedPaths, references: value.references, issues: value.issues as Issue[] }
  })()
  const citations = (() => opinion?.references || design?.sections.flatMap(section => section.references) || [])()
  return { design, opinion, citations }
}

export function parseDocumentReviewReport(content: unknown, review = false) {
  const props = { content, review }
  type Source = { fileId: string; section: number }
  type Entry = { title: string; statement: string; sources: Source[]; issues: string[]; assessment: NonNullable<DocumentRequirementDetail['assessment']> }
  type Finding = { key: string; kind: string; severity: string; title: string; trigger: string; impact: string; recommendation: string; evidence: RequirementCodeReference[] }
  type Skipped = { source: Source; reason: string }
  type Correction = { requirementKey: string | null; findingKey: string | null; source: Source | null; detail: string }
  const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(item => typeof item === 'string')
  const source = (value: unknown): value is Source => !!value && typeof value === 'object' && 'fileId' in value && typeof value.fileId === 'string' && /^DOC-[1-9][0-9]?$/.test(value.fileId) && 'section' in value && Number.isSafeInteger(value.section) && Number(value.section) > 0
  function references(value: unknown): value is RequirementCodeReference[] {
    return Array.isArray(value) && value.every(item => item && typeof item.path === 'string' && Number.isSafeInteger(item.startLine) && item.startLine > 0 && Number.isSafeInteger(item.endLine) && item.endLine >= item.startLine && typeof item.quote === 'string')
  }
  const assessment = (() => {
    if (props.review) return null
    const value = props.content as { entries?: unknown; findings?: unknown; skippedSections?: unknown; limitations?: unknown } | null
    if (!value || !Array.isArray(value.entries) || !Array.isArray(value.findings) || !Array.isArray(value.skippedSections) || !strings(value.limitations)) return null
    if (!value.entries.every(item => item && typeof item.title === 'string' && typeof item.statement === 'string' && Array.isArray(item.sources) && item.sources.length && item.sources.every(source) && strings(item.issues) && item.assessment && ['SATISFIED', 'PARTIAL', 'INCORRECT', 'NOT_IMPLEMENTED', 'UNDETERMINED'].includes(item.assessment.conclusion) && typeof item.assessment.rationale === 'string' && references(item.assessment.evidence) && strings(item.assessment.checkedPaths) && strings(item.assessment.limitations) && typeof item.assessment.testSourceCoverage === 'string' && (!item.issues.length || item.assessment.conclusion === 'UNDETERMINED') && (item.assessment.conclusion === 'UNDETERMINED' || item.assessment.evidence.length))) return null
    if (!value.findings.every(item => item && typeof item.key === 'string' && ['DEFECT', 'VALIDATION_GAP', 'SUGGESTION'].includes(item.kind) && ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'].includes(item.severity) && typeof item.title === 'string' && typeof item.trigger === 'string' && typeof item.impact === 'string' && typeof item.recommendation === 'string' && references(item.evidence))) return null
    if (!value.skippedSections.every(item => item && source(item.source) && typeof item.reason === 'string')) return null
    return { entries: value.entries as Entry[], findings: value.findings as Finding[], skipped: value.skippedSections as Skipped[], limitations: value.limitations }
  })()
  const opinion = (() => {
    if (!props.review) return null
    const value = props.content as { approved?: unknown; reviewedRequirementKeys?: unknown; reviewedFindingKeys?: unknown; checkedSections?: unknown; corrections?: unknown } | null
    if (!value || typeof value.approved !== 'boolean' || !strings(value.reviewedRequirementKeys) || !strings(value.reviewedFindingKeys) || !Array.isArray(value.checkedSections) || !value.checkedSections.length || !value.checkedSections.every(source) || !Array.isArray(value.corrections) || value.approved !== (value.corrections.length === 0)) return null
    if (!value.corrections.every(item => item && typeof item.detail === 'string' && (typeof item.requirementKey === 'string' || typeof item.findingKey === 'string' || source(item.source)) && (!item.source || source(item.source)))) return null
    return { approved: value.approved, requirements: value.reviewedRequirementKeys.length, findings: value.reviewedFindingKeys.length, sections: value.checkedSections as Source[], corrections: value.corrections as Correction[] }
  })()
  return { assessment, opinion }
}
