import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, renameSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';
import { patchManifests, patchXyflowReact, sha256 } from '../patch-xyflow-react.mjs';

const frontend = fileURLToPath(new URL('../../frontend/', import.meta.url));
const script = new URL('../patch-xyflow-react.mjs', import.meta.url).href;
const patchedSource = file => file.replacements.reduce((source, { before, after }) => source.replace(before, after), file.content.toString('utf8'));
// Fixtures use the locked distribution, restoring the pinned preimage if npm's
// postinstall already patched it. No test writes to the real dependency tree.
const originals = patchManifests.map(manifest => {
  const packageRoot = join(frontend, 'node_modules', manifest.packageName);
  const metadata = readFileSync(join(packageRoot, 'package.json'));
  assert.equal(sha256(metadata), manifest.packageJsonSha256);
  return { manifest, metadata, files: manifest.files.map(file => {
    let content = readFileSync(join(packageRoot, file.path));
    if (sha256(content) === file.patchedSha256) content = Buffer.from([...file.replacements].reverse()
      .reduce((source, { before, after }) => source.replace(after, before), content.toString('utf8')));
    assert.equal(sha256(content), file.originalSha256, `${manifest.packageName}/${file.path} fixture preimage`);
    return { ...file, content };
  }) };
});

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'loopper-xyflow-patch-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  writeFileSync(join(root, 'package.json'), JSON.stringify({ dependencies: { '@xyflow/react': '12.12.0' } }));
  const lock = JSON.parse(readFileSync(join(frontend, 'package-lock.json'), 'utf8'));
  writeFileSync(join(root, 'package-lock.json'), JSON.stringify(lock));
  const files = [];
  for (const { manifest, metadata, files: sourceFiles } of originals) {
    const packageRoot = join(root, 'node_modules', manifest.packageName);
    mkdirSync(packageRoot, { recursive: true });
    writeFileSync(join(packageRoot, 'package.json'), metadata);
    for (const file of sourceFiles) {
      const path = join(packageRoot, file.path); mkdirSync(dirname(path), { recursive: true });
      writeFileSync(path, file.content); files.push({ ...file, packageName: manifest.packageName, path });
    }
  }
  return { root, files };
}

const snapshot = fixture => fixture.files.map(file => existsSync(file.path) ? sha256(readFileSync(file.path)) : 'missing');
function editJson(path, edit) {
  const value = JSON.parse(readFileSync(path, 'utf8')); edit(value); writeFileSync(path, JSON.stringify(value));
}
function rejectedUnchanged(view, pattern) {
  const before = snapshot(view);
  assert.throws(() => patchXyflowReact(view.root), pattern);
  assert.deepEqual(snapshot(view), before);
  assert.equal(readdirSync(join(view.root, 'node_modules'), { recursive: true }).some(path => path.includes('.loopper-resize-patch-')), false);
}

test('inspect validates the two packages and all six entries without writing; check rejects an unpatched install', t => {
  const view = fixture(t), before = snapshot(view);
  const result = patchXyflowReact(view.root, 'inspect');
  assert.equal(result.state, 'original'); assert.equal(result.changed, false); assert.equal(result.files.length, 6);
  assert.throws(() => patchXyflowReact(view.root, 'check'), /patch is not installed/);
  assert.deepEqual(snapshot(view), before);
});

test('apply produces exactly the pinned cleanup replacements and complete result hashes in all six entries', t => {
  const view = fixture(t), result = patchXyflowReact(view.root);
  assert.equal(result.state, 'patched'); assert.equal(result.changed, true);
  for (const file of view.files) {
    const actual = readFileSync(file.path);
    assert.equal(sha256(actual), file.patchedSha256);
    assert.equal(actual.toString('utf8'), patchedSource(file));
  }
  assert.equal(patchXyflowReact(view.root, 'check').state, 'patched');
});

test('an already-patched install is idempotent without rewriting files', t => {
  const view = fixture(t); patchXyflowReact(view.root);
  const times = view.files.map(file => statSync(file.path, { bigint: true }).mtimeNs), before = snapshot(view);
  assert.equal(patchXyflowReact(view.root).changed, false);
  assert.deepEqual(snapshot(view), before);
  assert.deepEqual(view.files.map(file => statSync(file.path, { bigint: true }).mtimeNs), times);
});

for (const { packageName } of patchManifests) {
  for (const field of ['version', 'resolved', 'integrity']) test(`${packageName}: lock ${field} mismatch rejects the entire bundle before writes`, t => {
    const view = fixture(t);
    editJson(join(view.root, 'package-lock.json'), lock => { lock.packages[`node_modules/${packageName}`][field] = 'unexpected'; });
    rejectedUnchanged(view, new RegExp(`lock ${field === 'resolved' ? 'resolved URL' : field === 'version' ? 'package version' : field}`));
  });
  test(`${packageName}: installed version mismatch rejects before writes`, t => {
    const view = fixture(t);
    editJson(join(view.root, 'node_modules', packageName, 'package.json'), metadata => { metadata.version = '0.0.0'; });
    rejectedUnchanged(view, /installed package version/);
  });
  test(`${packageName}: an additional JS export cannot silently escape patch coverage`, t => {
    const view = fixture(t);
    editJson(join(view.root, 'node_modules', packageName, 'package.json'), metadata => { metadata.exports['./new-runtime'] = './dist/new.cjs'; });
    rejectedUnchanged(view, /runtime entrypoints/);
  });
  test(`${packageName}: same-version metadata drift is rejected`, t => {
    const view = fixture(t);
    editJson(join(view.root, 'node_modules', packageName, 'package.json'), metadata => { metadata.description += ' drift'; });
    rejectedUnchanged(view, /package.json SHA256/);
  });
}

test('frontend, lock-root, and transitive parent version pins are required', t => {
  for (const target of ['frontend', 'root', 'parent']) {
    const view = fixture(t);
    if (target === 'frontend') editJson(join(view.root, 'package.json'), metadata => { metadata.dependencies['@xyflow/react'] = '^12.12.0'; });
    else editJson(join(view.root, 'package-lock.json'), lock => {
      if (target === 'root') lock.packages[''].dependencies['@xyflow/react'] = '^12.12.0';
      else lock.packages['node_modules/@xyflow/react'].dependencies['@xyflow/system'] = '^0.0.83';
    });
    rejectedUnchanged(view, /dependency version/);
  }
});

test('a change outside the patch fragment in the final UMD entry rejects before earlier entries are touched', t => {
  const view = fixture(t), last = view.files.at(-1);
  writeFileSync(last.path, Buffer.concat([last.content, Buffer.from('\n// unrelated drift\n')]));
  rejectedUnchanged(view, /unrecognized complete-file SHA256/);
});

test('a missing final runtime entry prevents any partial application', t => {
  const view = fixture(t); rmSync(view.files.at(-1).path);
  rejectedUnchanged(view, /ENOENT/);
});

test('mixed original/patched entries fail closed rather than silently completing an unknown installation', t => {
  const view = fixture(t), first = view.files[0];
  writeFileSync(first.path, patchedSource(first));
  rejectedUnchanged(view, /mixed original\/patched/);
});

test('a dependency-tree symlink cannot patch another workspace', t => {
  const view = fixture(t), external = join(view.root, 'other-workspace-dependencies');
  renameSync(join(view.root, 'node_modules'), external);
  symlinkSync(external, join(view.root, 'node_modules'), process.platform === 'win32' ? 'junction' : 'dir');
  rejectedUnchanged(view, /node_modules must be local/);
});

test('a failed final replacement rolls earlier entries back and removes all staged files', t => {
  const view = fixture(t), before = snapshot(view);
  execFileSync(process.execPath, ['--input-type=module', '-e', `
    import fs from 'node:fs'; import assert from 'node:assert/strict'; import { syncBuiltinESMExports } from 'node:module';
    const original = fs.renameSync; let count = 0;
    fs.renameSync = (...args) => { if (++count === 6) throw new Error('controlled rename failure'); return original(...args); };
    syncBuiltinESMExports(); const { patchXyflowReact } = await import(${JSON.stringify(script)});
    assert.throws(() => patchXyflowReact(${JSON.stringify(view.root)}), /controlled rename failure/);
  `]);
  assert.deepEqual(snapshot(view), before);
  assert.equal(readdirSync(join(view.root, 'node_modules'), { recursive: true }).some(path => path.includes('.loopper-resize-patch-')), false);
});

test('all six patched ESM and CJS/UMD entries parse and load their public exports', t => {
  const view = fixture(t); patchXyflowReact(view.root);
  for (const file of view.files) execFileSync(process.execPath, ['--check', file.path], { stdio: 'pipe' });
  // Only ordinary dependencies are linked for the child process. The two
  // patched packages and every modified entry remain isolated fixture files.
  for (const name of ['react', 'react-dom', 'classcat', 'zustand', 'd3-drag', 'd3-interpolate', 'd3-selection', 'd3-transition', 'd3-zoom']) {
    symlinkSync(join(frontend, 'node_modules', name), join(view.root, 'node_modules', name), process.platform === 'win32' ? 'junction' : 'dir');
  }
  const entries = view.files.map(file => ({ path: file.path, exportName: file.packageName === '@xyflow/react' ? 'useReactFlow' : 'XYPanZoom' }));
  execFileSync(process.execPath, ['--input-type=module', '-e', `
    import assert from 'node:assert/strict'; import { createRequire } from 'node:module'; import { pathToFileURL } from 'node:url';
    const require = createRequire(pathToFileURL(${JSON.stringify(join(view.root, 'package.json'))}));
    for (const entry of ${JSON.stringify(entries)}) {
      const module = entry.path.includes('/umd/') ? require(entry.path) : await import(pathToFileURL(entry.path).href);
      assert.equal(typeof module[entry.exportName], 'function', entry.path);
    }
  `], { cwd: view.root, stdio: 'pipe' });
});

test('patched renderer hook disconnects its own observer after ref clearing and preserves exact resize removal', t => {
  const view = fixture(t); patchXyflowReact(view.root);
  const source = readFileSync(view.files[0].path, 'utf8'), start = source.indexOf('function useResizeHandler(domNode) {');
  const hook = source.slice(start, source.indexOf('\nconst containerStyle =', start));
  let cleanup, callback, added, removed, updates = 0, disconnects = 0;
  const targets = new Set(), node = { checkVisibility: () => true }, ref = { current: node };
  const window = { addEventListener: (type, fn) => { added = { type, fn }; }, removeEventListener: (type, fn) => { removed = { type, fn }; } };
  const context = { window, getDimensions: () => ({ width: 800, height: 600 }),
    useStoreApi: () => ({ setState: () => { updates++; }, getState: () => ({}) }),
    useEffect: setup => { cleanup = setup(); }, ResizeObserver: class {
      constructor(fn) { callback = fn; } observe(target) { targets.add(target); }
      disconnect() { disconnects++; targets.clear(); } unobserve() { assert.fail('ref-dependent unobserve must not be used'); }
    } };
  runInNewContext(`${hook}\nuseResizeHandler(ref);`, { ...context, ref });
  assert.equal(targets.has(node), true); ref.current = null; cleanup();
  assert.equal(disconnects, 1); assert.equal(targets.size, 0); assert.equal(added.type, 'resize');
  assert.equal(removed.type, added.type); assert.equal(removed.fn, added.fn);
  callback(); assert.equal(updates, 1, 'a late measurement cannot write through the cleared ref');
});

test('public panZoom destroy releases its own pane zoom listeners and extent; selection pause keeps both live', t => {
  const view = fixture(t); patchXyflowReact(view.root);
  const source = readFileSync(view.files[3].path, 'utf8'), start = source.indexOf('function XYPanZoom({');
  const implementation = source.slice(start, source.indexOf('\n/**\n * Used to determine the variant', start));
  let callback, disconnects = 0, extent, paused = 0; const targets = new Set();
  const listeners = new Map([['wheel.zoom', () => {}], ['mousedown.zoom', () => {}], ['click.foreign', () => {}]]);
  const foreign = listeners.get('click.foreign');
  const selection = { call() { return this; }, on(type, handler) {
    if (arguments.length === 1) return listeners.get(type);
    if (type === '.zoom' && handler === null) { for (const key of listeners.keys()) if (key.endsWith('.zoom')) listeners.delete(key); }
    else if (handler === null) listeners.delete(type);
    else listeners.set(type, handler);
    return this;
  }, property() { return { x: 0, y: 0, k: 1 }; } };
  const zoom = { extent(fn) { extent = fn; return this; }, scaleExtent() { return this; }, translateExtent() { return this; },
    wheelDelta() { return this; }, interpolate() { return this; }, transform() { return this; }, constrain() { return value => value; },
    clickDistance() { return this; }, filter() { return this; }, on(type, fn) { if (type === 'zoom' && fn === null) paused++; return this; } };
  const node = { getBoundingClientRect: () => ({ width: 800, height: 600 }) };
  const context = { node, zoom: () => zoom, select: () => selection, clamp: value => value, wheelDelta: () => 0,
    viewportToTransform: value => value, getD3Transition: value => value, interpolate: () => {}, interpolateZoom: () => {},
    isNumeric: value => typeof value === 'number', createFilter: () => () => false,
    ResizeObserver: class { constructor(fn) { callback = fn; } observe(target) { targets.add(target); } disconnect() { disconnects++; targets.clear(); } },
  };
  for (const name of ['createPanOnScrollHandler', 'createZoomOnScrollHandler', 'createPanZoomStartHandler', 'createPanZoomHandler', 'createPanZoomEndHandler']) context[name] = () => () => {};
  const instance = runInNewContext(`${implementation}\nXYPanZoom({ domNode: node, minZoom: .2, maxZoom: 2, translateExtent: [[0,0],[800,600]], viewport: {x:0,y:0,zoom:1} });`, context);
  instance.update({ userSelectionActive: true });
  assert.equal(paused, 1); assert.equal(disconnects, 0); assert.equal(targets.has(node), true);
  assert.equal(listeners.has('wheel.zoom'), true); assert.equal(listeners.has('mousedown.zoom'), true);
  callback([{ contentRect: { width: 390, height: 844 } }]);
  assert.equal(JSON.stringify(extent()), JSON.stringify([[0, 0], [390, 844]]));
  instance.update({ userSelectionActive: false }); assert.equal(disconnects, 0);
  assert.equal(listeners.has('wheel.zoom'), true); assert.equal(listeners.has('mousedown.zoom'), true);
  instance.destroy(); assert.equal(paused, 2); assert.equal(disconnects, 1); assert.equal(targets.size, 0);
  assert.equal(listeners.has('wheel.zoom'), false); assert.equal(listeners.has('mousedown.zoom'), false);
  assert.equal(listeners.get('click.foreign'), foreign, 'a foreign namespace remains owned by its original listener');
});
