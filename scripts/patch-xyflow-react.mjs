import { createHash, randomUUID } from 'node:crypto';
import { readFileSync, realpathSync, renameSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const patchManifests = ['xyflow-react-12.12.0-resize-cleanup.json', 'xyflow-system-0.0.83-resize-cleanup.json']
  .map(file => JSON.parse(readFileSync(new URL(`./patches/${file}`, import.meta.url), 'utf8')));
export const sha256 = content => createHash('sha256').update(content).digest('hex');
const defaultFrontend = fileURLToPath(new URL('../frontend/', import.meta.url));
const fail = message => { throw new Error(`[xyflow-renderer-resize-cleanup] ${message}`); };
const equal = (actual, expected, label) => { if (actual !== expected) fail(`${label}: expected ${expected}, received ${actual}`); };
const json = file => JSON.parse(readFileSync(file, 'utf8'));

function runtimeEntries(metadata) {
  const entries = new Set();
  const collect = value => {
    if (typeof value === 'string' && /\.(?:mjs|cjs|js)$/.test(value)) entries.add(value.replace(/^\.\//, ''));
    else if (value && typeof value === 'object') for (const child of Object.values(value)) collect(child);
  };
  collect(metadata.main); collect(metadata.module); collect(metadata.exports);
  return [...entries].sort();
}

function plan(frontend) {
  const project = json(join(frontend, 'package.json')), lock = json(join(frontend, 'package-lock.json'));
  const dependencyRoot = join(frontend, 'node_modules');
  equal(realpathSync(dependencyRoot), dependencyRoot, 'node_modules must be local to this frontend');
  // Validate both packages and all six complete entrypoints before staging any file.
  const files = patchManifests.flatMap(expected => {
    const locked = lock.packages?.[`node_modules/${expected.packageName}`], label = expected.packageName;
    if (expected.parentPackage) {
      equal(lock.packages?.[`node_modules/${expected.parentPackage}`]?.dependencies?.[label], expected.version, `${label} parent dependency version`);
    } else {
      equal(project.dependencies?.[label], expected.version, `${label} frontend dependency version`);
      equal(lock.packages?.['']?.dependencies?.[label], expected.version, `${label} lock root dependency version`);
    }
    equal(locked?.version, expected.version, `${label} lock package version`);
    equal(locked?.resolved, expected.resolved, `${label} lock resolved URL`);
    equal(locked?.integrity, expected.integrity, `${label} lock integrity`);
    const packageRoot = join(dependencyRoot, label), metadataPath = join(packageRoot, 'package.json');
    equal(realpathSync(packageRoot), packageRoot, `${label} package must be local to this dependency tree`);
    equal(realpathSync(metadataPath), metadataPath, `${label} metadata must not be symlinked`);
    const metadataBytes = readFileSync(metadataPath);
    const metadata = JSON.parse(metadataBytes.toString('utf8'));
    equal(metadata.name, label, `${label} installed package name`);
    equal(metadata.version, expected.version, `${label} installed package version`);
    equal(JSON.stringify(runtimeEntries(metadata)), JSON.stringify(expected.files.map(file => file.path).sort()), `${label} runtime entrypoints`);
    equal(sha256(metadataBytes), expected.packageJsonSha256, `${label} installed package.json SHA256`);
    return expected.files.map(file => {
      const path = join(packageRoot, file.path);
      equal(realpathSync(path), path, `${label}/${file.path} must not be symlinked`);
      const original = readFileSync(path), hash = sha256(original);
      if (hash === file.patchedSha256) return { ...file, packageName: label, absolutePath: path, original, output: original, state: 'patched' };
      if (hash !== file.originalSha256) fail(`${label}/${file.path}: unrecognized complete-file SHA256 ${hash}`);
      let source = original.toString('utf8');
      for (const { before, after } of file.replacements) {
        if (source.split(before).length !== 2) fail(`${label}/${file.path}: each patch must match exactly once`);
        source = source.replace(before, after);
      }
      const output = Buffer.from(source, 'utf8');
      equal(sha256(output), file.patchedSha256, `${label}/${file.path} planned result SHA256`);
      return { ...file, packageName: label, absolutePath: path, original, output, state: 'original' };
    });
  });
  if (new Set(files.map(file => file.state)).size !== 1) fail('mixed original/patched entrypoints; reinstall the locked package before applying');
  return files;
}

function writePlan(files) {
  const suffix = `.loopper-resize-patch-${randomUUID()}`, staged = [], committed = [];
  try {
    for (const file of files) {
      const temporary = file.absolutePath + suffix;
      staged.push(temporary);
      writeFileSync(temporary, file.output, { flag: 'wx', mode: statSync(file.absolutePath).mode });
    }
    // Refuse a concurrent source change after validation, before the first rename.
    for (const file of files) if (!readFileSync(file.absolutePath).equals(file.original)) fail(`${file.packageName}/${file.path}: changed during patch preparation`);
    for (const file of files) {
      renameSync(file.absolutePath + suffix, file.absolutePath);
      committed.push(file);
    }
    for (const file of files) equal(sha256(readFileSync(file.absolutePath)), file.patchedSha256, `${file.packageName}/${file.path} written result SHA256`);
  } catch (error) {
    const failures = [error];
    for (const file of committed.reverse()) {
      try { writeFileSync(file.absolutePath, file.original); } catch (rollbackError) { failures.push(rollbackError); }
    }
    if (failures.length > 1) throw new AggregateError(failures, 'Dependency patch failed; rollback also failed. Reinstall the locked package.');
    throw error;
  } finally {
    for (const temporary of staged) {
      try { unlinkSync(temporary); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    }
  }
}

export function patchXyflowReact(frontend = defaultFrontend, mode = 'apply') {
  if (!['apply', 'check', 'inspect'].includes(mode)) fail(`unknown mode ${mode}; use apply, check, or inspect`);
  const files = plan(realpathSync(resolve(frontend))), state = files[0].state;
  if (mode === 'check' && state !== 'patched') fail('patch is not installed; run npm run patch:apply in frontend');
  if (mode === 'apply' && state === 'original') writePlan(files);
  return { packages: patchManifests.map(({ packageName, version }) => ({ packageName, version })),
    state: mode === 'apply' ? 'patched' : state, changed: mode === 'apply' && state === 'original',
    files: files.map(file => ({ packageName: file.packageName, path: file.path,
      sha256: mode === 'apply' ? file.patchedSha256 : sha256(file.original) })) };
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  try {
    if (process.argv.length > 3) fail('usage: node scripts/patch-xyflow-react.mjs [apply|check|inspect]');
    console.log(JSON.stringify(patchXyflowReact(defaultFrontend, process.argv[2] ?? 'apply'), null, 2));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
