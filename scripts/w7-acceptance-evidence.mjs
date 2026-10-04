/** W7 evidence accounting only: never changes source, assertions or application state. */
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const hash = value => createHash('sha256').update(value).digest('hex')
const json = path => JSON.parse(readFileSync(resolve(path), 'utf8'))
const save = (path, data) => writeFileSync(resolve(path), JSON.stringify(data, null, 2) + '\n')
const [mode, output, ...inputs] = process.argv.slice(2)
function sourceManifest() {
  const paths = [...new Set(execFileSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z', '--', 'frontend', 'scripts',
    'docs/design/react-full-migration/semantic-*.json', 'docs/design/react-full-migration/prototype/desktop-v2/semantic-registry.json',
    'docs/design/react-full-migration/evidence/w7/test-discovery-baseline.json', 'docs/design/react-full-migration/evidence/w7/desktop-additions.json'], { cwd: root, encoding: 'utf8' }).split('\0').filter(Boolean))]
    .filter(path => /\.(?:[cm]?[jt]sx?|html|json|css)$/.test(path)).sort()
  const files = Object.fromEntries(paths.map(path => [path, hash(readFileSync(resolve(root, path)))]))
  return { files, sourceSha256: hash(JSON.stringify(files)) }
}
if (mode === 'freeze') {
  save(output, { kind: 'w7-source-freeze', baseline: '1e9161e06c01b5aa76b6a2b19789ac8379423195', ...sourceManifest() })
} else if (mode === 'verify') {
  const expected = json(output), actual = sourceManifest()
  assert.deepEqual(actual, { files: expected.files, sourceSha256: expected.sourceSha256 }, 'Frozen source changed: final results cannot be combined across revisions')
  console.log(JSON.stringify({ ok: true, sourceSha256: actual.sourceSha256, files: Object.keys(actual.files).length }))
} else if (mode === 'browser') {
  const baseline = json(resolve(root, 'docs/design/react-full-migration/evidence/w7/test-discovery-baseline.json'))
  const rows = [], runs = []
  function visit(suite, label, testRoot) {
    for (const spec of suite.specs ?? []) for (const test of spec.tests) rows.push({
      file: relative(resolve(root, 'frontend/e2e'), resolve(testRoot, spec.file)).replaceAll('\\', '/'), title: spec.title,
      project: test.projectName, run: label, status: test.status,
      attempts: test.results.map(row => ({ status: row.status, retry: row.retry, duration: row.duration })),
    })
    for (const child of suite.suites ?? []) visit(child, label, testRoot)
  }
  for (const input of inputs) {
    const bytes = readFileSync(resolve(input)), data = JSON.parse(bytes), label = input.split('/').at(-1)
    assert.equal(data.errors?.length ?? 0, 0, `${label}: runner/collection errors`)
    runs.push({ label, sha256: hash(bytes), stats: data.stats })
    for (const suite of data.suites) visit(suite, label, data.config.rootDir)
  }
  const key = row => `${row.file} :: ${row.title}`
  const expected = new Map(), actual = new Map()
  for (const row of baseline.cases.filter(row => row.scope === 'DESKTOP')) expected.set(key(row), (expected.get(key(row)) ?? 0) + 1)
  const additions = json(resolve(root, 'docs/design/react-full-migration/evidence/w7/desktop-additions.json')).cases
  const allowedAddedKeys = new Set(additions.map(key))
  for (const row of rows) actual.set(key(row), (actual.get(key(row)) ?? 0) + 1)
  const missing = [...expected].filter(([key, count]) => (actual.get(key) ?? 0) < count)
  const added = rows.filter(row => !expected.has(key(row)))
  const excess = [...actual].filter(([key, count]) => expected.has(key) && count > expected.get(key))
  const failed = rows.filter(row => row.status !== 'expected' || row.attempts.length !== 1 || row.attempts[0].status !== 'passed' || row.attempts[0].retry !== 0)
  const outOfScope = baseline.cases.filter(row => row.scope === 'OUT_OF_SCOPE_NARROW')
  assert.equal(rows.some(row => outOfScope.some(omit => key(omit) === key(row))), false, 'Canceled narrow tests must not be counted as desktop passes')
  const unexpectedAdditions = added.filter(row => !allowedAddedKeys.has(key(row)))
  const missingAdditions = additions.filter(row => actual.get(key(row)) !== 1)
  const result = { ok: missing.length === 0 && excess.length === 0 && failed.length === 0 && unexpectedAdditions.length === 0 && missingAdditions.length === 0,
    baselineRegistered: baseline.cases.length, baselineUnique: new Set(baseline.cases.map(key)).size,
    outOfScope: outOfScope.length, desktopBaseline: [...expected.values()].reduce((a, b) => a + b, 0),
    finalRegistered: rows.length, finalUnique: actual.size, duplicateRegistrationExtras: rows.length - actual.size,
    executionAttempts: rows.reduce((sum, row) => sum + row.attempts.length, 0), retries: rows.reduce((sum, row) => sum + row.attempts.filter(attempt => attempt.retry > 0).length, 0),
    missing, excess, failed, added, unexpectedAdditions, missingAdditions, runs, cases: rows, excluded: outOfScope }
  save(output, result)
  console.log(JSON.stringify({ ok: result.ok, registered: rows.length, unique: actual.size, attempts: result.executionAttempts, missing: missing.length, excess: excess.length, failed: failed.length, added: added.length, outOfScope: outOfScope.length }))
  assert.equal(result.ok, true, 'W7 frozen desktop matrix is not fully green')
} else throw new Error('Usage: w7-acceptance-evidence.mjs freeze|verify <manifest>; browser <output> <final-playwright-json...>')
