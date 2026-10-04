import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'

const baseline = JSON.parse(readFileSync(new URL('../../docs/design/react-full-migration/evidence/w6/baseline.json', import.meta.url), 'utf8'))
function fixture() {
  return { success: true, numTotalTests: baseline.totalTests, numFailedTests: 0, numPendingTests: 0, numRuntimeErrorTestSuites: 0,
    testResults: baseline.tests.map(suite => ({ name: `/fixture/${suite.path}`, status: 'passed',
      assertionResults: suite.assertions.map(item => ({ fullName: item.fullName, status: 'passed' })) })) }
}
function check(result) {
  const dir = mkdtempSync(join(tmpdir(), 'loopper-w6-mapping-'))
  try {
    const input = join(dir, 'input.json'), output = join(dir, 'output.json')
    writeFileSync(input, JSON.stringify(result))
    const run = spawnSync(process.execPath, [new URL('../verify-w6-test-mapping.mjs', import.meta.url).pathname, input, output], { encoding: 'utf8' })
    assert.equal(run.error, undefined)
    return { status: run.status, result: JSON.parse(readFileSync(output, 'utf8')) }
  } finally { rmSync(dir, { recursive: true, force: true }) }
}
test('W6 mapping retains every original duplicate assertion occurrence', () => {
  assert.equal(check(fixture()).status, 0)
  const missing = fixture(); missing.testResults[0].assertionResults.pop(); missing.numTotalTests--
  const run = check(missing); assert.equal(run.status, 1); assert.equal(run.result.ok, false); assert.equal(run.result.missing.length, 1)
})
test('W6 mapping rejects an empty failed collection suite even when assertions all pass', () => {
  const broken = fixture()
  broken.testResults.push({ name: '/fixture/frontend/src/collection-error.spec.ts', status: 'failed', assertionResults: [] })
  const run = check(broken); assert.equal(run.status, 1); assert.equal(run.result.failed.length, 0); assert.equal(run.result.suiteFailures.length, 1)
})
test('W6 mapping rejects skipped assertions and unsuccessful runtime summaries', () => {
  const skipped = fixture(); skipped.testResults[0].assertionResults[0].status = 'pending'
  assert.equal(check(skipped).status, 1)
  const unsuccessful = fixture(); unsuccessful.success = false; unsuccessful.numRuntimeErrorTestSuites = 1
  assert.equal(check(unsuccessful).status, 1)
})
