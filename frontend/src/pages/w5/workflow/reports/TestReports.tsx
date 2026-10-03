import { workflowTestCoverageLabel } from '@/utils/displayLabels'
import * as v from './validators'
import { Badge, Code, Invalid, Limits, Lines, References, text, type ReportProps } from './ReportParts'
const categories = { NORMAL: '正常', BOUNDARY: '边界', ERROR: '异常', BRANCH: '关键分支' }
const frameworks: Record<string, string> = { junit: 'JUnit', testng: 'TestNG', jest: 'Jest', vitest: 'Vitest', pytest: 'pytest' }
export function WorkflowTestProfileReport({ content }: ReportProps) {
  const r = v.parseTestProfileReport(content); if (!r) return <Invalid label="测试配置" />
  return <section className="workflow-professional-report">{r.kind === 'profile' ? <><p>以下配置来自固定源码资料，测试尚未执行。</p>{r.modules.map(module => <details key={module.root + module.framework} open><summary>{module.root === '.' ? '项目根目录' : module.root} · {frameworks[module.framework]}</summary><dl><dt>测试源码目录</dt><dd><Lines values={module.testRoots} /></dd>{!!module.fixtureRoots.length && <><dt>测试夹具目录</dt><dd><Lines values={module.fixtureRoots} /></dd></>}<dt>原生测试命令</dt><dd><Code content={JSON.stringify(module.command, null, 2)} language="json" label="原生测试命令" /></dd></dl><details><summary>{module.sourcePaths.length} 个目标源码文件</summary><Lines values={module.sourcePaths} /></details></details>)}</> : r.kind === 'summary' ? <><Badge good>配置已识别</Badge><p>{r.moduleCount} 个模块，{r.sourceCount} 个目标源码文件。</p></> : <><Badge good={false}>配置未确定</Badge><p>{r.message}</p></>}</section>
}
export function WorkflowTestDesignReport({ content }: ReportProps) {
  const r = v.parseTestDesignReport(content); if (!r) return <Invalid label="单测场景交付" />
  return <section className="workflow-professional-report"><h3>{r.title}</h3><p>{r.summary}</p><p>{r.scenarios.length} 个测试场景 · 尚未执行测试</p>{r.scenarios.map((scenario, i) => <article key={scenario.key}><h4>{i + 1}. {scenario.title}</h4><p>{categories[scenario.category]} · {scenario.path}</p><strong>测试步骤</strong><ol>{scenario.steps.map((step, j) => <li key={j}>{step}</li>)}</ol><strong>预期结果</strong><p>{scenario.expected}</p><References values={scenario.references} /></article>)}<Limits values={r.limitations} /></section>
}
export function WorkflowTestScopeReport({ content }: ReportProps) {
  const r = v.parseTestScopeReport(content); if (!r) return <Invalid label="范围报告" />
  return <section className="workflow-professional-report"><Badge good={r.passed}>{r.passed ? '范围检查通过' : '范围检查未通过'}</Badge><p>{r.message}</p><p>仅检查文件修改范围和已有测试保护；实际测试结果请查看后续验证节点。</p>{!r.passed && <p>可在代码交付中检查固定成果，调整任务后重试；失败成果不能直接用于后续执行。</p>}</section>
}
export function WorkflowNativeTestReport({ content }: ReportProps) {
  const r = v.parseNativeTestReport(content); if (!r) return <Invalid label="原生测试报告" />
  return <section className="workflow-professional-report"><Badge good={r.passed}>{!r.valid ? '测试证据不完整' : r.passed ? '原生测试通过' : '原生测试未通过'}</Badge><p>测试模块：{r.module === '.' ? '项目根目录' : r.module} · {r.framework}</p>{!!r.batchCount && <p>在同一份固定代码上回归 {r.batchCount} 批场景，各批代码继承关系已核对。</p>}{r.inputUnchanged === true && <p>已核对固定源码、测试和配置。</p>}{r.valid ? <p>实际执行 {r.executed} 项：通过 {r.succeeded}，失败 {r.failed}，另有 {r.skipped} 项跳过。</p> : <p>未取得完整的测试执行证据，不能认定测试通过。</p>}<p>{r.message}</p><p>测试数量不表示每个设计场景已覆盖；场景覆盖与独立评审由对应节点确认。</p><details><summary>本次命令与报告文件</summary><Code content={JSON.stringify(r.command, null, 2)} language="json" label="本次原生测试命令" />{r.files.map(file => <p key={file.path}>{file.path}</p>)}{r.fileCount > r.files.length && <p>另有 {r.fileCount - r.files.length} 份报告，完整正文保存在执行记录中。</p>}</details></section>
}
export function WorkflowTestReviewReport({ content }: ReportProps) {
  const r = v.parseTestReviewReport(content); if (!r) return <Invalid label="场景复核报告" />
  return <section className="workflow-professional-report"><Badge good={r.passed}>{r.passed ? '复核通过' : '需要修订'}</Badge><p>{r.reason}</p><p>原生测试：{r.nativePassed ? '通过' : '未通过'}</p><p>覆盖判断来自独立评审；执行结果和固定版本由程序核对。</p>{r.rows.map((row, i) => <article key={row.key}><h4>{i + 1}. {row.title}</h4><p>{row.path}</p><strong>{workflowTestCoverageLabel(row.status)}</strong><p>{row.reason}</p>{!!row.tests.length && <ul>{row.tests.map(test => <li key={test.id}>{test.name} · {test.status === 'PASSED' ? '执行通过' : test.status === 'FAILED' ? '执行失败' : '未执行（跳过）'}<small> · {test.reportPath}</small></li>)}</ul>}<References values={row.references} label="测试代码依据" /></article>)}</section>
}
export function WorkflowTestSummaryReport({ content }: ReportProps) {
  const r = v.parseTestSummaryReport(content); if (!r) return <Invalid label="单测汇总报告" />
  return <section className="workflow-professional-report"><Badge good={!!r.passed}>{r.passed ? '单测汇总通过' : '单测汇总未通过'}</Badge><p>源码覆盖 {text(r.covered)} / {text(r.total)} 个文件。全部模块使用同一份最终代码。</p><p>完成策略：{r.policy}。</p>{r.modules.map(module => <article key={module.root}><strong>{module.root === '.' ? '项目根目录' : module.root}</strong><p>{module.passed ? '测试通过' : '测试未通过'} · 实际执行 {module.executed} 项，失败 {module.failed} 项，跳过 {module.skipped} 项。</p></article>)}<details><summary>查看 {r.batches.length} 批场景的复核情况</summary>{r.batches.map((batch, i) => <p key={i}>第 {i + 1} 批 · {batch.module === '.' ? '项目根目录' : batch.module} · {batch.count} 个场景 · {r.reviewRequired ? batch.satisfied ? '复核通过' : '复核条件未满足' : '按自定义策略不要求复核'}（{batch.opinions} 份意见）</p>)}</details></section>
}
