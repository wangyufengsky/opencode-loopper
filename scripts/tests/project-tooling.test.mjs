import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { execFileSync } from 'node:child_process';
import { nextVersion, parseVersion, planVersion, prepareRelease, projectRoot } from '../release-version.mjs';
import { checkInstructions } from '../check-project.mjs';

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'loopper-tooling-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const files = ['pom.xml', 'frontend/package.json', 'frontend/package-lock.json',
    'src/main/resources/application.yml', 'README.md', 'AGENTS.md', 'scripts/start-linux.sh', 'scripts/start-windows.bat'];
  for (const path of files) {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), readFileSync(join(projectRoot, path)));
  }
  execFileSync('git', ['init', '-q', root]);
  return root;
}

test('version carry and validation preserve the project 0–99 convention', () => {
  assert.equal(nextVersion('0.3.81'), '0.3.82');
  assert.equal(nextVersion('0.3.99'), '0.4.0');
  assert.equal(nextVersion('0.99.99'), '1.0.0');
  for (const value of ['0.3.100', '0.100.0', '01.2.3', '1.2.3-SNAPSHOT', '1.2', '9007199254740992.0.0'])
    assert.throws(() => parseVersion(value));
});

test('planning updates all release fields without writing or rewriting history and dependencies', t => {
  const root = fixture(t);
  const oldReadme = readFileSync(join(root, 'README.md'), 'utf8');
  writeFileSync(join(root, 'README.md'), `${oldReadme}\n历史：0.3.81 retained.\n`);
  const bat = readFileSync(join(root, 'scripts/start-windows.bat'), 'utf8').replace(/\r?\n/g, '\r\n');
  writeFileSync(join(root, 'scripts/start-windows.bat'), bat);
  const changes = prepareRelease(root, '9.99.99');
  assert.ok(changes.get('README.md').includes('历史：0.3.81 retained.'));
  assert.equal(changes.get('scripts/start-windows.bat').split('\r\n').length, bat.split('\r\n').length);
  const before = JSON.parse(readFileSync(join(root, 'frontend/package-lock.json'), 'utf8'));
  const after = JSON.parse(changes.get('frontend/package-lock.json'));
  for (const key of Object.keys(before.packages).filter(key => key)) assert.deepEqual(after.packages[key], before.packages[key]);
  assert.equal(after.version, '9.99.99');
  assert.equal(after.packages[''].version, '9.99.99');
  assert.ok(!readFileSync(join(root, 'pom.xml'), 'utf8').includes('<version>9.99.99</version>'));
  for (const [path, content] of changes) writeFileSync(join(root, path), content);
  assert.doesNotThrow(() => planVersion(root));
  assert.throws(() => prepareRelease(root, '9.99.99'), /must increase/);
  assert.throws(() => prepareRelease(root, '0.0.1'), /must increase/);
});

for (const [ending, newline] of [['LF', '\n'], ['CRLF', '\r\n']]) {
  test(`drift, absent fields and local version collisions stop a release (${ending})`, t => {
    const root = fixture(t);
    const path = join(root, 'frontend/package.json');
    const original = readFileSync(path, 'utf8').replace(/\r?\n/g, newline);
    writeFileSync(path, original.replace(/"version": "[^"]+"/, '"version": "0.0.0"'));
    assert.throws(() => prepareRelease(root, '9.99.99'), /version drift/);
    const missingVersion = original.replace(/  "version": "[^"]+",\r?\n/, '');
    assert.equal(Object.hasOwn(JSON.parse(missingVersion), 'version'), false);
    writeFileSync(path, missingVersion);
    assert.throws(() => planVersion(root), /missing or ambiguous/);
    writeFileSync(path, original);
    mkdirSync(join(root, 'target'));
    writeFileSync(join(root, 'target/opencode-loopper-9.99.99.jar'), 'occupied');
    assert.throws(() => prepareRelease(root, '9.99.99'), /local JAR/);
    rmSync(join(root, 'target/opencode-loopper-9.99.99.jar'));
    execFileSync('git', ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '--allow-empty', '-qm', 'fixture'], { cwd: root });
    execFileSync('git', ['tag', 'v9.99.99'], { cwd: root });
    assert.throws(() => prepareRelease(root, '9.99.99'), /local tag/);
  });
}

test('instruction size and routed file links fail closed', t => {
  const root = mkdtempSync(join(tmpdir(), 'loopper-docs-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  for (const path of ['AGENTS.md', 'src/AGENTS.md', 'frontend/AGENTS.md']) {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), '# Guidance\n');
  }
  assert.equal(checkInstructions(root), 3);
  writeFileSync(join(root, 'AGENTS.md'), '[missing](missing.md)');
  assert.throws(() => checkInstructions(root), /missing link target/);
  writeFileSync(join(root, 'AGENTS.md'), '中'.repeat(6000));
  assert.throws(() => checkInstructions(root), /exceeds/);
});
