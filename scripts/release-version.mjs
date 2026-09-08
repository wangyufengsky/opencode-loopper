import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

export const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const versionPattern = '(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]?)\\.(?:0|[1-9][0-9]?)';
export function parseVersion(value) {
  if (!new RegExp(`^${versionPattern}$`).test(value)) throw new Error(`Invalid release version: ${value}`);
  const parts = value.split('.').map(Number);
  if (!parts.every(Number.isSafeInteger)) throw new Error('Version exceeds safe integer range');
  return parts;
}
export function nextVersion(value) {
  let [major, minor, patch] = parseVersion(value);
  if (++patch > 99) { patch = 0; if (++minor > 99) { minor = 0; major++; } }
  const next = `${major}.${minor}.${patch}`;
  parseVersion(next);
  return next;
}
const refs = [
  ['pom.xml', /(<artifactId>opencode-loopper<\/artifactId>\s*<version>)([^<]+)(<\/version>)/g, 1],
  ['frontend/package.json', /(^  "version": ")([^"]+)(")/gm, 1],
  ['frontend/package-lock.json', /(^  "version": ")([^"]+)(")/gm, 1],
  ['frontend/package-lock.json', /(^    "": \{\s*"name": "[^"]+",\s*"version": ")([^"]+)(")/gm, 1],
  ['src/main/resources/application.yml', /(^        version: )([^\r\n]+)()/gm, 1],
  ['AGENTS.md', /(当前交付版本：`)([^`]+)(`)/g, 1],
  ['README.md', /(当前版本：`)([^`]+)(`)/g, 1],
  ...['README.md', 'scripts/start-linux.sh', 'scripts/start-windows.bat'].map(path =>
    [path, /(opencode-loopper-)([0-9]+\.[0-9]+\.[0-9]+)(\.jar)/g, null]),
];
export function currentVersion(root = projectRoot) {
  const pom = readFileSync(resolve(root, 'pom.xml'), 'utf8');
  const version = [...pom.matchAll(refs[0][1])][0]?.[2];
  parseVersion(version);
  return version;
}
// Validate every reference before any write. Only designated release fields change;
// dependency versions, historical prose and original line endings are preserved.
export function planVersion(root, next = currentVersion(root)) {
  const current = currentVersion(root);
  parseVersion(next);
  const changes = new Map();
  for (const [path, pattern, count] of refs) {
    const original = changes.get(path) ?? readFileSync(resolve(root, path), 'utf8');
    const matches = [...original.matchAll(pattern)];
    if (!matches.length || count !== null && matches.length !== count)
      throw new Error(`${path}: missing or ambiguous release reference`);
    for (const match of matches) if (match[2] !== current)
      throw new Error(`${path}: version drift (${match[2]} != ${current})`);
    changes.set(path, original.replace(pattern, (_all, before, _version, after) => `${before}${next}${after}`));
  }
  return changes;
}
export function prepareRelease(root, next) {
  const current = currentVersion(root);
  const oldParts = parseVersion(current), newParts = parseVersion(next);
  const first = newParts.findIndex((part, i) => part !== oldParts[i]);
  if (first < 0 || newParts[first] < oldParts[first]) throw new Error('Release version must increase');
  const changes = planVersion(root, next);
  if (existsSync(resolve(root, `target/opencode-loopper-${next}.jar`))) throw new Error('Version already has a local JAR');
  const tags = execFileSync('git', ['tag', '--list', `v${next}`], { cwd: root, encoding: 'utf8' }).trim();
  if (tags) throw new Error('Version already has a local tag');
  return changes;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [command = 'check', value, flag, ...extra] = process.argv.slice(2);
    if (extra.length) throw new Error('Unexpected arguments');
    if (command === 'check' && value === undefined) {
      planVersion(projectRoot); console.log(`Release references consistent: ${currentVersion()}`);
    } else if (command === 'next' && value === undefined) {
      console.log(nextVersion(currentVersion()));
    } else if (command === 'set' && value && (flag === undefined || flag === '--write')) {
      const changes = prepareRelease(projectRoot, value);
      for (const [path, content] of changes) {
        if (flag === '--write') writeFileSync(resolve(projectRoot, path), content);
        console.log(`${flag ? 'Updated' : 'Would update'} ${path}`);
      }
      console.log('Local checks only: verify remote tags and Releases before building.');
    } else throw new Error('Usage: release-version.mjs check | next | set <version> [--write]');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
