import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const frontend = new URL('../../frontend/', import.meta.url);
const require = createRequire(new URL('package.json', frontend));
const { JSDOM } = require('jsdom');

function environment(t) {
  const dom = new JSDOM('<!doctype html><body><div id="host"></div></body>', { pretendToBeVisual: true, url: 'http://localhost/' });
  const { window } = dom, observers = [], rows = [];
  const values = { window, document: window.document, navigator: window.navigator, Element: window.Element,
    HTMLElement: window.HTMLElement, SVGElement: window.SVGElement, Node: window.Node,
    getComputedStyle: window.getComputedStyle.bind(window), requestAnimationFrame: window.requestAnimationFrame.bind(window),
    cancelAnimationFrame: window.cancelAnimationFrame.bind(window), IS_REACT_ACT_ENVIRONMENT: true,
    ResizeObserver: class {
      constructor(callback) { this.callback = callback; this.targets = new Set(); observers.push(this); }
      observe(target) { this.targets.add(target); } unobserve(target) { this.targets.delete(target); }
      disconnect() { this.targets.clear(); }
    } };
  const saved = new Map(Object.keys(values).map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  for (const [key, value] of Object.entries(values)) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  t.after(() => { dom.window.close(); for (const [key, descriptor] of saved) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key]; } });
  Object.defineProperty(window.HTMLElement.prototype, 'clientWidth', { configurable: true, get: () => 800 });
  Object.defineProperty(window.HTMLElement.prototype, 'clientHeight', { configurable: true, get: () => 600 });
  window.HTMLElement.prototype.getBoundingClientRect = () => ({ x: 0, y: 0, left: 0, top: 0, width: 800, height: 600, right: 800, bottom: 600, toJSON() {} });
  const add = window.EventTarget.prototype.addEventListener, remove = window.EventTarget.prototype.removeEventListener;
  const capture = options => typeof options === 'boolean' ? options : !!options?.capture;
  const paneTarget = target => target instanceof window.Element && target.matches('.probe-pane, .react-flow__renderer');
  window.EventTarget.prototype.addEventListener = function(type, callback, options) {
    add.call(this, type, callback, options);
    if (paneTarget(this) && !rows.some(row => row.active && row.target === this && row.type === type && row.callback === callback && row.capture === capture(options))) rows.push({ target: this, type, callback, capture: capture(options), active: true });
  };
  window.EventTarget.prototype.removeEventListener = function(type, callback, options) {
    remove.call(this, type, callback, options);
    for (const row of rows) if (row.target === this && row.type === type && row.callback === callback && row.capture === capture(options)) row.active = false;
  };
  return { window, observers, listeners: pane => rows.filter(row => row.active && row.target === pane), pane() {
    const pane = window.document.createElement('div'); pane.className = 'probe-pane'; window.document.body.append(pane); return pane;
  } };
}

test('actual system ESM final destroy releases only its pane; selection pause and another instance stay live', async t => {
  const env = environment(t), { XYPanZoom } = await import(new URL('node_modules/@xyflow/system/dist/esm/index.mjs', frontend));
  const create = pane => XYPanZoom({ domNode: pane, minZoom: .2, maxZoom: 4, translateExtent: [[-Infinity, -Infinity], [Infinity, Infinity]], viewport: { x: 0, y: 0, zoom: 1 } });
  const a = env.pane(), b = env.pane(), first = create(a), second = create(b);
  const initial = env.listeners(a); assert(initial.some(row => row.type === 'wheel')); assert(initial.some(row => row.type === 'mousedown'));
  const ownObserver = env.observers.find(observer => observer.targets.has(a)); assert(ownObserver);
  let foreign = 0, other = 0; const sentinel = () => { foreign++; }, otherSentinel = () => { other++; };
  a.addEventListener('wheel', sentinel); b.addEventListener('wheel', otherSentinel);
  first.update({ userSelectionActive: true, zoomOnDoubleClick: true });
  // update intentionally replaces wheel.zoom's callback; it must preserve the
  // live namespace and the unchanged mousedown handler while pausing dispatch.
  assert.deepEqual(env.listeners(a).map(row => row.type).sort(), [...initial.map(row => row.type), 'wheel'].sort());
  assert(initial.find(row => row.type === 'mousedown').active); assert(ownObserver.targets.has(a));
  ownObserver.callback([{ contentRect: { width: 400, height: 200 } }]);
  first.update({ userSelectionActive: false, panOnDrag: false, panOnScroll: false, zoomOnScroll: false, zoomOnPinch: false, zoomOnDoubleClick: false });
  await first.scaleTo(1.5); assert.deepEqual(first.getViewport(), { x: -100, y: -50, zoom: 1.5 });
  const secondBefore = env.listeners(b).slice();
  first.destroy();
  // Synchronous first snapshot, with no subsequent event, tick or callback.
  assert.deepEqual(env.listeners(a).map(row => row.callback), [sentinel]); assert.equal(ownObserver.targets.size, 0);
  assert(secondBefore.every(row => row.active)); first.destroy(); assert(secondBefore.every(row => row.active));
  a.dispatchEvent(new env.window.WheelEvent('wheel')); b.dispatchEvent(new env.window.WheelEvent('wheel'));
  assert.equal(foreign, 1); assert.equal(other, 1); await second.scaleTo(1.2); assert.equal(second.getViewport().zoom, 1.2);
  second.destroy(); assert.deepEqual(env.listeners(b).map(row => row.callback), [otherSentinel]);
});

test('actual React UMD/CJS inline final root cleanup releases its pane listeners immediately', async t => {
  const env = environment(t), React = require('react'), { createRoot } = require('react-dom/client');
  const { ReactFlow, ReactFlowProvider } = require('@xyflow/react');
  const root = createRoot(env.window.document.getElementById('host'));
  await React.act(async () => root.render(React.createElement(ReactFlowProvider, null,
    React.createElement(ReactFlow, { nodes: [], edges: [], width: 800, height: 600, panOnDrag: false, panOnScroll: false,
      zoomOnScroll: false, zoomOnPinch: false, zoomOnDoubleClick: false, panActivationKeyCode: null, zoomActivationKeyCode: null }))));
  const pane = env.window.document.querySelector('.react-flow__renderer'); assert(pane);
  const before = env.listeners(pane); assert(before.some(row => row.type === 'wheel')); assert(before.some(row => row.type === 'mousedown'));
  const sentinel = () => {}; pane.addEventListener('wheel', sentinel);
  React.act(() => root.unmount());
  assert.equal(pane.isConnected, false); assert.deepEqual(env.listeners(pane).map(row => row.callback), [sentinel]);
  assert.equal(env.observers.flatMap(observer => [...observer.targets]).filter(target => !target.isConnected).length, 0);
});
