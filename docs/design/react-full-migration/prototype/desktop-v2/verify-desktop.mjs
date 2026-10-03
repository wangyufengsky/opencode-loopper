// Independent desktop-only verification of a local HTML/mock design prototype.
// This never starts the product, calls an API, installs dependencies, or edits author sources.
import { strict as assert } from 'node:assert';
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createServer } from 'node:http';

const directory = dirname(fileURLToPath(import.meta.url));
const argument = (name, fallback) => {
  const index = process.argv.indexOf(name);
  return index < 0 ? fallback : process.argv[index + 1];
};
const toolsRoot = resolve(argument('--tools', resolve(directory, '../../../../../frontend')));
const port = Number(argument('--port', '41783'));
const rawDirectory = argument('--raw', process.env.DESKTOP_PROTOTYPE_EVIDENCE_DIR);
const { chromium } = await import(pathToFileURL(join(toolsRoot, 'node_modules/playwright/index.mjs')).href);
const sha256 = value => createHash('sha256').update(value).digest('hex');
const skins = ['spdb', 'tech-blue', 'github-white'];
const screens = ['home', 'list', 'form', 'task', 'settings'];
const dimensions = [{width: 1440, height: 960}, {width: 1280, height: 900}];
const sourceNames = ['index.html', 'styles.css', 'app.js', 'semantic-registry.json', 'semantic-registry.js', 'review-single.html', 'generate-single.mjs', 'verify-desktop.mjs'];
const sources = {};
for (const name of sourceNames) {
  try { sources[name] = sha256(await readFile(join(directory, name))); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
}
assert(sources['index.html'] && sources['styles.css'] && sources['app.js'], 'Author desktop-v2 sources must be frozen before running');

async function originalManifest() {
  const original = resolve(directory, '..');
  const result = {};
  for (const name of ['index.html', 'prototype.css', 'prototype.js', 'capture.mjs', 'evidence.json', 'review-single.html', 'bundle-single.mjs', 'verify-single.mjs', 'single-evidence.json']) {
    result[name] = sha256(await readFile(join(original, name)));
  }
  for (const name of (await readdir(join(original, 'screenshots'))).filter(name => name.endsWith('.png')).sort()) {
    result[`screenshots/${name}`] = sha256(await readFile(join(original, 'screenshots', name)));
  }
  return result;
}

const report = {
  kind: 'Non-author desktop design verification; HTML/mock, not product acceptance',
  scope: {dimensions, skins, screens, narrowScreens: 'OUT_OF_SCOPE', production: false},
  sources, originalBefore: await originalManifest(), checks: [], screenshots: [],
  requests: [], externalRequests: [], pageErrors: [], consoleErrors: [], executionComplete: false,
  nativeDialogs: [],
};
const allowedFiles = new Map([
  ['/', 'index.html'], ['/index.html', 'index.html'], ['/styles.css', 'styles.css'],
  ['/app.js', 'app.js'], ['/semantic-registry.js', 'semantic-registry.js'],
  ['/semantic-registry.json', 'semantic-registry.json'], ['/review-single.html', 'review-single.html'],
]);
const server = createServer(async (request, response) => {
  const name = allowedFiles.get(new URL(request.url, 'http://127.0.0.1').pathname);
  if (!name) { response.writeHead(404); response.end(); return; }
  try {
    response.setHeader('Content-Type', name.endsWith('.css') ? 'text/css' : name.endsWith('.js') ? 'text/javascript' : name.endsWith('.json') ? 'application/json' : 'text/html; charset=utf-8');
    response.end(await readFile(join(directory, name)));
  } catch { response.writeHead(404); response.end(); }
});
await new Promise((done, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', done); });
const origin = `http://127.0.0.1:${port}`;
report.previewOrigin = origin;
let browser, context;
try {
  browser = await chromium.launch({executablePath: process.env.PLAYWRIGHT_CHROME_EXECUTABLE || '/usr/bin/chromium', headless: true});
  report.browser = browser.version();
  context = await browser.newContext({viewport: dimensions[0], locale: 'zh-CN'});
  await context.route(/^https?:\/\//, route => {
    if (new URL(route.request().url()).origin === origin) return route.continue();
    report.externalRequests.push(route.request().url());
    return route.abort();
  });
  let pageIndex = 0;
  context.on('page', page => {
    const id = ++pageIndex;
    page.on('pageerror', error => report.pageErrors.push({page: id, message: error.message}));
    page.on('console', message => { if (message.type() === 'error') report.consoleErrors.push({page: id, message: message.text()}); });
    page.on('request', request => report.requests.push({page: id, url: request.url(), type: request.resourceType(), method: request.method()}));
  });
  const page = await context.newPage();
  page.setDefaultTimeout(6000);
  page.on('dialog', async dialog => {
    report.nativeDialogs.push({type: dialog.type(), message: dialog.message()});
    // Full document navigation is fixture reset between checks, never a business leave assertion.
    if (dialog.type() === 'beforeunload') await dialog.accept();
    else { await dialog.dismiss(); report.pageErrors.push({message: `Unexpected browser dialog ${dialog.type()}`}); }
  });

  async function check(name, action) {
    try { await action(); report.checks.push({name, result: 'pass'}); console.log(`PASS ${name}`); }
    catch (error) {
      report.checks.push({name, result: 'fail', message: error.message, stack: error.stack});
      console.error(`FAIL ${name}: ${error.message}`);
      if (rawDirectory) {
        await mkdir(rawDirectory, {recursive: true});
        await writeFile(join(rawDirectory, `failure-${report.checks.length}.html`), await page.content());
        await page.screenshot({path: join(rawDirectory, `failure-${report.checks.length}.png`), fullPage: false}).catch(() => {});
      }
    }
  }
  async function load(screen, skin = 'github-white', size = dimensions[0], scenario = 'clean', motion = 'no-preference', single = false) {
    await page.setViewportSize(size);
    await page.emulateMedia({reducedMotion: motion});
    await page.goto(`${origin}/${single ? 'review-single.html' : 'index.html'}?page=${screen}&skin=${skin}&scenario=${scenario}`);
    await page.locator('body[data-prototype="desktop-v2"] main#main-content').waitFor();
    await page.waitForFunction(() => window.LoopperDesktop?.ready === true);
    await page.evaluate(() => document.fonts.ready);
  }
  async function stableScreenshot(target = page) {
    await target.bringToFront();
    // These are page/state review images, not evidence of a deliberately scrolled interaction.
    await target.evaluate(() => window.scrollTo(0, 0));
    await target.evaluate(async () => {
      await document.fonts.ready;
      await Promise.all(document.getAnimations().filter(animation => {
        const timing = animation.effect?.getComputedTiming();
        return timing && Number.isFinite(timing.endTime);
      }).map(animation => animation.finished.catch(() => {})));
    });
    let previous;
    for (let attempt = 0; attempt < 12; attempt++) {
      await target.evaluate(() => new Promise(done => requestAnimationFrame(() => requestAnimationFrame(done))));
      const current = await target.screenshot({fullPage: false});
      if (previous && sha256(current) === sha256(previous)) return {buffer: current, samples: attempt + 1};
      previous = current;
    }
    throw new Error('Pixels did not stabilize across two consecutive exact screenshot hashes');
  }
  async function shot(name, state) {
    const {buffer, samples} = await stableScreenshot();
    await mkdir(join(directory, 'screenshots'), {recursive: true});
    await writeFile(join(directory, 'screenshots', name), buffer);
    const scroll = await page.evaluate(() => ({x: scrollX, y: scrollY}));
    assert.deepEqual(scroll, {x: 0, y: 0});
    report.screenshots.push({name, sha256: sha256(buffer), bytes: buffer.length, samples, viewport: page.viewportSize(), scroll, skin: await page.locator('html').getAttribute('data-skin'), state, simulated: true});
  }
  const snapshot = () => page.evaluate(() => window.LoopperDesktop.snapshot());
  const selected = () => page.locator('#main-content [data-select]').first();
  const action = name => page.locator(`[data-action="${name}"]`).filter({visible: true}).first();

  await check('local semantic registry: exact bundled Lucide source, complete known entries and immutable runtime projection', async () => {
    const registry = JSON.parse(await readFile(join(directory, 'semantic-registry.json'), 'utf8'));
    const iconFile = await readFile(join(toolsRoot, 'node_modules/@iconify-json/lucide/icons.json'));
    assert.equal(sha256(iconFile), registry.source.iconFileSha256);
    const lucide = JSON.parse(iconFile);
    for (const [name, glyph] of Object.entries(registry.icons)) {
      let canonical = name;
      const visited = new Set();
      while (!lucide.icons[canonical]) {
        assert(!visited.has(canonical), `Cyclic local icon alias ${canonical}`); visited.add(canonical);
        const alias = lucide.aliases?.[canonical];
        assert(alias && alias.parent, `Unknown local icon ${canonical}`);
        assert(!alias.rotate && !alias.hFlip && !alias.vFlip, `Transformed alias ${canonical} needs explicit verification, cannot silently use its parent body`);
        canonical = alias.parent;
      }
      assert.equal(glyph.sourceName, canonical);
      assert.equal(glyph.body, lucide.icons[canonical].body, `Registered icon ${name} must exactly equal the actual installed glyph resolved by its declared local alias`);
      assert.equal(glyph.width, lucide.icons[canonical].width ?? lucide.width);
      assert.equal(glyph.height, lucide.icons[canonical].height ?? lucide.height);
    }
    for (const [key, value] of Object.entries({...registry.objects, ...registry.actions})) {
      assert(value.label && value.name && registry.icons[value.icon], `Incomplete semantic entry ${key}`);
    }
    const labels = new Map();
    for (const value of Object.values({...registry.objects, ...registry.actions})) {
      if (labels.has(value.label)) assert.equal(labels.get(value.label), value.icon, `Identical label ${value.label} cannot silently change icons`);
      labels.set(value.label, value.icon);
    }
    assert.equal(registry.routes.length, 31);
    await load('home');
    const runtime = await page.evaluate(() => {
      const {icon, label, name, ...data} = window.LoopperUI;
      return {data, frozen: Object.isFrozen(window.LoopperUI) && Object.isFrozen(window.LoopperUI.objects) && Object.isFrozen(window.LoopperUI.actions)};
    });
    assert.deepEqual(runtime.data, registry);
    assert.equal(runtime.frozen, true);
    report.semantic = {objects: Object.keys(registry.objects).length, actions: Object.keys(registry.actions).length, icons: Object.keys(registry.icons).length, routes: registry.routes.length, localIconFileSha256: sha256(iconFile)};
  });

  for (const skin of skins) for (const screen of screens) for (const size of dimensions) {
    await load(screen, skin, size);
    await check(`${screen}/${skin}/${size.width}: default content, hidden context, explicit mock, desktop reachability`, async () => {
      assert.equal(await page.locator('#main-content h1').count(), 1);
      assert.equal(await page.locator('html').getAttribute('data-skin'), skin);
      assert.equal(await page.locator('#context-panel').isVisible(), false);
      assert.equal(await page.locator('#prototype-label').isVisible(), true);
      assert.match(await page.locator('#prototype-label').textContent(), /模拟|原型/);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
      assert.equal(await page.locator('nav [data-page]').count() >= 5, true);
      assert.equal(await page.locator('#status-strip').evaluate(element => !document.querySelector('#context-panel').contains(element)), true);
      assert.equal(await page.locator('#main-content').evaluate(element => element.getBoundingClientRect().width >= innerWidth * 0.65), true);
    });
    await check(`${screen}/${skin}/${size.width}: semantic DOM and decorative local icon consistency`, async () => {
      const violations = await page.evaluate(() => {
        const registry = window.LoopperUI;
        const problems = [];
        for (const element of document.querySelectorAll('[data-semantic]')) {
          const key = element.dataset.semantic;
          if (!registry.objects[key] && !registry.actions[key]) problems.push(`Unknown semantic ${key}`);
        }
        for (const svg of document.querySelectorAll('svg')) {
          const key = svg.dataset.semanticIcon;
          if (!key || !registry.objects[key] && !registry.actions[key]) { problems.push('SVG outside local semantic registry'); continue; }
          if (svg.getAttribute('aria-hidden') !== 'true' || svg.getAttribute('focusable') !== 'false' || svg.hasAttribute('tabindex')) problems.push(`Focusable decorative icon ${key}`);
          const expected = document.createElement('template'); expected.innerHTML = registry.icon(key);
          if (svg.innerHTML !== expected.content.querySelector('svg').innerHTML) problems.push(`Glyph changed ${key}`);
        }
        for (const button of document.querySelectorAll('button[data-semantic]')) {
          const value = registry.actions[button.dataset.semantic];
          if (!value) continue;
          const name = button.getAttribute('aria-label') || button.textContent.trim();
          if (name !== value.name && !name.startsWith(value.name + '：') && !name.startsWith(value.name + ' ')) problems.push(`Action accessible name ${button.dataset.semantic}: ${name}`);
          // Selectable object rows may show their subject glyph; ordinary actions use the action glyph.
          const icon = button.querySelector('svg');
          if (button.dataset.semantic !== 'selection.select' && icon && icon.dataset.semanticIcon !== button.dataset.semantic) problems.push(`Action glyph ${button.dataset.semantic}: ${icon.dataset.semanticIcon}`);
        }
        return {problems, icons: document.querySelectorAll('svg[data-semantic-icon]').length};
      });
      assert(violations.icons > 0);
      assert.deepEqual(violations.problems, []);
    });
    if (size.width === 1440) await shot(`${skin}-${screen}-default.png`, `${screen}, default, simulated data`);
    await check(`${screen}/${skin}/${size.width}: selection, repeated selection, presentation close and focus return`, async () => {
      const trigger = selected();
      assert.equal(await trigger.count(), 1, 'Each page has an actual selectable content object');
      await trigger.click();
      assert.equal(await page.locator('#context-panel').isVisible(), true);
      const firstState = await snapshot();
      await trigger.click();
      assert.equal(await page.locator('#context-panel').isVisible(), true);
      assert.equal((await snapshot()).selected, firstState.selected);
      assert.equal(await trigger.getAttribute('aria-pressed'), 'true');
      if (size.width === 1440 && skin === 'github-white') await shot(`${skin}-${screen}-selected.png`, `${screen}, selected context, simulated data`);
      await action('close').click();
      assert.equal(await page.locator('#context-panel').isVisible(), false);
      assert.equal(await trigger.evaluate(element => element === document.activeElement), true);
    });
  }

  for (const skin of skins) {
    for (const screen of screens) {
      await load(screen, skin);
      await check(`${screen}/${skin}: Enter/Space selection, Escape and explicit deselection retain trigger focus`, async () => {
        const trigger = selected();
        for (const key of ['Enter', 'Space']) {
          await trigger.focus();
          await page.keyboard.press(key);
          assert.equal(await trigger.getAttribute('aria-pressed'), 'true');
          assert.equal(await page.locator('#context-panel').isVisible(), true);
          await page.keyboard.press('Escape');
          assert.equal(await page.locator('#context-panel').isVisible(), false);
          assert.equal(await trigger.getAttribute('aria-pressed'), 'false');
          assert.equal(await trigger.evaluate(element => document.activeElement === element), true);
        }
        await trigger.click();
        await action('deselect').click();
        assert.equal(await page.locator('#context-panel').isVisible(), false);
        assert.equal(await trigger.getAttribute('aria-pressed'), 'false');
        assert.equal(await trigger.evaluate(element => document.activeElement === element), true);
      });
      await check(`${screen}/${skin}: non-modal context does not trap Tab, expand/collapse preserves selection`, async () => {
        const trigger = selected(); await trigger.click();
        const expand = action('expand');
        const width = await page.locator('#context-panel').evaluate(element => element.getBoundingClientRect().width);
        await expand.click();
        assert((await page.locator('#context-panel').evaluate(element => element.getBoundingClientRect().width)) > width);
        assert.equal(await trigger.getAttribute('aria-pressed'), 'true');
        await action('collapse').click();
        assert.equal(await page.locator('#context-panel').evaluate(element => element.getBoundingClientRect().width), width);
        await action('close').focus();
        let escaped = false;
        for (let index = 0; index < 30; index++) {
          await page.keyboard.press('Tab');
          if (!(await page.locator('#context-panel').evaluate(element => element.contains(document.activeElement)))) { escaped = true; break; }
        }
        assert.equal(escaped, true);
        await page.keyboard.press('Escape');
      });
    }
    await load('list', skin);
    await check(`${skin}: skip link and keyboard focus indicator`, async () => {
      await page.keyboard.press('Tab');
      assert.equal(await page.locator('#skip-content').evaluate(element => element === document.activeElement), true);
      await page.keyboard.press('Enter');
      assert.equal(await page.locator('#main-content').evaluate(element => element === document.activeElement), true);
      await page.keyboard.press('Tab');
      const indicator = await page.evaluate(() => {
        const style = getComputedStyle(document.activeElement);
        return {style: style.outlineStyle, width: parseFloat(style.outlineWidth)};
      });
      assert.notEqual(indicator.style, 'none'); assert(indicator.width >= 2);
    });
    await load('list', skin, dimensions[1], 'clean', 'reduce');
    await check(`${skin}: reduced motion disables non-essential transitions and leaves keyboard selection usable`, async () => {
      await selected().focus(); await page.keyboard.press('Enter');
      const animation = await page.locator('#context-panel').evaluate(element => ({animation: getComputedStyle(element).animationName, transition: getComputedStyle(element).transitionDuration}));
      assert.equal(animation.animation, 'none');
      assert(animation.transition.split(',').every(duration => parseFloat(duration) === 0));
      assert.equal(await action('close').isEnabled(), true);
      await page.keyboard.press('Escape');
      assert.equal(await page.locator('#context-panel').isVisible(), false);
      assert.equal(await selected().evaluate(element => element === document.activeElement), true);
    });
  }

  const semanticButton = key => page.locator(`button[data-semantic="${key}"]`).filter({visible: true}).first();
  const formControls = () => page.locator('#requirement-form input, #requirement-form textarea, #requirement-form select');
  const formValues = () => formControls().evaluateAll(elements => Object.fromEntries(elements.map(element => [element.name, element.value])));
  for (const skin of skins) {
    await load('home', skin);
    await check(`home/${skin}: search filters loaded objects only, clears and retains actual input focus without requests`, async () => {
      const requests = report.requests.length;
      assert.equal(await page.locator('#recent-projects [data-select]').count(), 2);
      assert.equal(await page.locator('#recent-conversations [data-select]').count(), 2);
      await page.locator('#home-search').fill('订单');
      assert.equal(await page.locator('#recent-projects [data-select]').count(), 1);
      assert.equal(await page.locator('#recent-conversations [data-select]').count(), 1);
      assert.equal(await page.locator('#home-search').evaluate(element => element === document.activeElement), true);
      await page.locator('[data-action="clearSearch"]').click();
      assert.equal(await page.locator('#home-search').inputValue(), '');
      assert.equal(await page.locator('#recent-projects [data-select]').count(), 2);
      assert.equal(await page.locator('#recent-conversations [data-select]').count(), 2);
      assert.equal(await page.locator('#home-search').evaluate(element => element === document.activeElement), true);
      assert.equal(report.requests.length, requests);
    });
    await load('list', skin);
    await check(`list/${skin}: search/status combine; context close retains local query/filter without writes`, async () => {
      await page.locator('#list-search').fill('退款');
      assert.equal(await page.locator('#task-rows [data-select]').count(), 1);
      await page.locator('#list-status').selectOption('success');
      assert.equal(await page.locator('#task-rows [data-select]').count(), 0);
      await page.locator('#list-status').selectOption('all');
      await selected().click(); await action('close').click();
      assert.equal(await page.locator('#list-search').inputValue(), '退款');
      assert.equal(await page.locator('#list-status').inputValue(), 'all');
      assert.equal(await page.locator('#task-rows [data-select]').count(), 1);
      assert.equal((await snapshot()).operation, null);
    });
    await load('form', skin);
    await check(`form/${skin}: exactly four real New fields, no upload in main or advanced context`, async () => {
      assert.deepEqual(await formControls().evaluateAll(elements => elements.map(element => element.name).sort()), ['objective', 'project', 'template', 'title']);
      await selected().click();
      assert.equal(await page.locator('input[type="file"]').count(), 0);
      assert.equal(await formControls().count(), 4);
      await action('close').click();
    });
    await check(`form/${skin}: real dirty input, theme/presentation retain draft; leave requires cancel or explicit discard`, async () => {
      await page.locator('#requirement-title').fill('保留真实输入的模拟需求');
      await page.locator('#requirement-objective').fill('这段目标来自真实控件修改，离开必须明确确认。');
      await page.locator('#requirement-form select[name="project"]').selectOption('project-console');
      await page.locator('#requirement-form select[name="template"]').selectOption('review-v2');
      const original = await formValues();
      assert.equal((await snapshot()).dirty, true);
      assert.equal(await page.locator('#status-strip [data-state="dirty"]').isVisible(), true);
      for (const next of skins) { await page.locator('#skin').selectOption(next); assert.deepEqual(await formValues(), original); }
      await page.locator('#skin').selectOption(skin);
      await selected().click(); await action('close').click();
      assert.deepEqual(await formValues(), original);
      const trigger = page.locator('nav [data-page="list"]');
      await trigger.click();
      assert.equal(await page.locator('#confirm-dialog').evaluate(element => element.open), true);
      assert.equal(await semanticButton('ui.stay').evaluate(element => document.activeElement === element), true);
      for (const key of ['Tab', 'Shift+Tab']) for (let index = 0; index < 15; index++) {
        await page.keyboard.press(key);
        assert.equal(await page.locator('#confirm-dialog').evaluate(element => element.contains(document.activeElement)), true);
      }
      if (skin === 'spdb') await shot('spdb-form-dirty-confirm.png', 'ordinary unsent draft, explicit leave decision, simulated');
      await page.keyboard.press('Escape');
      assert.equal(await page.locator('#confirm-dialog').evaluate(element => element.open), false);
      assert.equal(await trigger.evaluate(element => document.activeElement === element), true);
      assert.equal((await snapshot()).page, 'form');
      assert.deepEqual(await formValues(), original);
      await trigger.click(); await semanticButton('ui.discardChanges').click();
      assert.equal((await snapshot()).page, 'list'); assert.equal((await snapshot()).dirty, false);
    });
    for (const phase of ['sending', 'unknown']) {
      await load('form', skin, dimensions[0], phase);
      await check(`form/${skin}/${phase}: actual four-field lock, no silent leave/discard, close retains original owner/body/key`, async () => {
        assert.equal(await formControls().count(), 4);
        for (const control of await formControls().all()) assert.equal(await control.isDisabled(), true);
        assert.equal(await semanticButton('workflow.createRequirement').isDisabled(), true);
        const before = await snapshot();
        assert.equal(before.operation.phase, phase);
        assert.equal(before.operation.owner.page, 'form');
        assert(before.operation.key && before.operation.body);
        const values = await formValues();
        await selected().click(); await action('close').click();
        assert.deepEqual(await formValues(), values);
        await page.locator('nav [data-page="list"]').click();
        await page.locator('#scenario').selectOption('clean');
        const after = await snapshot();
        assert.equal(after.page, 'form');
        assert.deepEqual(after.operation, before.operation);
        assert.equal(await page.locator(`#status-strip [data-state="${phase}"]`).isVisible(), true);
        assert.equal(await semanticButton('ui.discardChanges').isVisible(), false);
        assert.equal(await semanticButton('receipt.retryOriginal').isDisabled(), phase === 'sending');
        if (skin === 'tech-blue' && phase === 'unknown') await shot('tech-blue-form-unknown.png', 'unknown create owner retained, locked four fields, simulated');
      });
    }
    await load('form', skin, dimensions[0], 'unknown');
    await check(`form/${skin}: explicit original retry, accepted handoff blocks navigation, no invented read GET/repeat create`, async () => {
      const before = await snapshot();
      await semanticButton('receipt.retryOriginal').click();
      const accepted = await snapshot();
      assert.equal(accepted.operation.phase, 'accepted-nav');
      assert.equal(accepted.operation.key, before.operation.key);
      assert.deepEqual(accepted.operation.body, before.operation.body);
      assert.equal(accepted.operation.mutations, 2); assert.equal(accepted.operation.reads, 0);
      assert(accepted.receipt && accepted.receipt.id);
      await page.locator('nav [data-page="list"]').click();
      assert.equal((await snapshot()).page, 'form');
      assert.equal((await snapshot()).operation.mutations, 2);
      assert.equal(await page.locator('#status-strip [data-state="recovery"]').isVisible(), true);
      await page.locator('#status-strip button[data-action="completeHandoff"]').click();
      const handed = await snapshot();
      assert.equal(handed.operation.phase, 'settled');
      assert.equal(handed.operation.navigationAttempts, 1);
      assert.equal(handed.operation.mutations, 2); assert.equal(handed.operation.reads, 0);
      assert.equal(await page.locator('#context-panel .route-location').textContent(), `/requirements/${accepted.receipt.id}`);
      if (skin === 'github-white') await shot('github-white-form-handoff.png', 'accepted original receipt target, no new create, simulated');
    });

    await load('settings', skin);
    await check(`settings/${skin}: two actual groups retain drafts across close/theme; explicit discard restores the whole owner`, async () => {
      const initial = (await snapshot()).settings;
      await page.locator('[data-select="setting-execution"]').click();
      await page.locator('#settings-form input[name="attempts"]').fill('4');
      await page.locator('#settings-form input[name="timeout"]').fill('45');
      await page.locator('[data-select="setting-models"]').click();
      await page.locator('#settings-form input[name="provider"]').fill('只在本页保留的模拟提供方');
      const changed = (await snapshot()).settings;
      assert.notDeepEqual(changed, initial);
      for (const next of skins) { await page.locator('#skin').selectOption(next); assert.deepEqual((await snapshot()).settings, changed); }
      await page.locator('#skin').selectOption(skin);
      await action('close').click();
      assert.deepEqual((await snapshot()).settings, changed);
      assert.equal(await page.locator('#status-strip [data-state="dirty"]').isVisible(), true);
      await page.locator('nav [data-page="list"]').click();
      assert.equal(await page.locator('#confirm-dialog').evaluate(element => element.open), true);
      await semanticButton('ui.stay').click();
      assert.deepEqual((await snapshot()).settings, changed);
      await page.locator('[data-select="setting-models"]').click();
      if (skin === 'spdb') await shot('spdb-settings-dirty.png', 'two settings groups edited, one whole settings owner, simulated');
      await semanticButton('ui.cancelEditing').click();
      await semanticButton('ui.discardChanges').click();
      assert.deepEqual((await snapshot()).settings, initial);
      assert.equal((await snapshot()).dirty, false);
    });
    await load('settings', skin);
    await check(`settings/${skin}: mock save commits whole two-group baseline; later discard preserves it`, async () => {
      await page.locator('[data-select="setting-execution"]').click();
      await page.locator('#settings-form input[name="attempts"]').fill('4');
      await page.locator('[data-select="setting-models"]').click();
      await page.locator('#settings-form input[name="provider"]').fill('已显式保存的模拟提供方');
      const saved = (await snapshot()).settings;
      await semanticButton('settings.save').click();
      assert.equal((await snapshot()).dirty, false);
      await page.locator('[data-select="setting-execution"]').click();
      await page.locator('#settings-form input[name="attempts"]').fill('5');
      await semanticButton('ui.cancelEditing').click(); await semanticButton('ui.discardChanges').click();
      assert.deepEqual((await snapshot()).settings, saved);
    });

    await load('task', skin, dimensions[0], 'waiting');
    await check(`task/${skin}: waiting question survives context close; answer focus and stop confirmation are distinct`, async () => {
      await selected().click(); await action('close').click();
      assert.equal(await page.locator('#status-strip [data-state="waiting"]').isVisible(), true);
      assert.equal(await page.locator('.task-question').isVisible(), true);
      await page.locator('[data-action="questionFocus"]').click();
      assert.equal(await page.locator('.task-question input').first().evaluate(element => element === document.activeElement), true);
      await page.locator('[data-action="taskActions"]').click();
      await semanticButton('task.cancel').click();
      assert.equal(await page.locator('#confirm-dialog').evaluate(element => element.open), true);
      await page.keyboard.press('Escape');
      assert.equal(await semanticButton('task.cancel').evaluate(element => element === document.activeElement), true);
      assert.equal((await snapshot()).waiting, true);
      assert.equal((await snapshot()).operation, null);
      if (skin === 'spdb') await shot('spdb-task-waiting.png', 'waiting question and explicit stop action, simulated');
    });
    await load('task', skin, dimensions[0], 'recovery');
    await check(`task/${skin}: accepted operation recovery only reads original owner and cannot double write`, async () => {
      const before = await snapshot();
      assert.equal(before.operation.phase, 'accepted-read');
      await page.locator('nav [data-page="list"]').click();
      assert.equal((await snapshot()).page, 'task');
      await semanticButton('receipt.readOriginal').click();
      const recovered = await snapshot();
      assert.equal(recovered.operation.phase, 'settled');
      assert.equal(recovered.operation.mutations, before.operation.mutations);
      assert.equal(recovered.operation.reads, before.operation.reads + 1);
      assert.equal(recovered.operation.key, before.operation.key);
      assert.deepEqual(recovered.operation.body, before.operation.body);
      if (skin === 'tech-blue') await shot('tech-blue-task-recovered.png', 'accepted owner readback simulation, zero additional writes');
    });
    await load('task', skin, dimensions[0], 'unknown');
    await check(`task/${skin}: answer without idempotency key recovers by original state read, no repeated answer POST`, async () => {
      const before = await snapshot();
      assert.equal(before.operation.owner.page, 'task');
      assert(before.operation.owner.sessionKey && before.operation.owner.questionId);
      assert.equal(Object.hasOwn(before.operation.body, 'requestKey'), false);
      assert.equal(await page.locator('.task-question input').first().isDisabled(), true);
      await selected().click(); await action('close').click();
      await page.locator('nav [data-page="list"]').click();
      assert.deepEqual((await snapshot()).operation, before.operation);
      assert.equal(await semanticButton('receipt.retryOriginal').isVisible(), false);
      await semanticButton('receipt.readOriginal').click();
      const restored = await snapshot();
      assert.equal(restored.operation.mutations, before.operation.mutations);
      assert.equal(restored.operation.reads, before.operation.reads + 1);
      assert.deepEqual(restored.operation.owner, before.operation.owner);
      assert.deepEqual(restored.operation.body, before.operation.body);
      assert.equal(restored.operation.phase, 'settled');
    });
    await load('task', skin, dimensions[0], 'conflict');
    await check(`task/${skin}: versioned conflict is independent task demo, preserves waiting question, no Settings CAS`, async () => {
      await page.locator('[data-action="conflict"]').click();
      assert.equal(await page.locator('#context-panel').getByText(/不是 Settings CAS/).isVisible(), true);
      assert.equal(await page.locator('.task-question').isVisible(), true);
      assert.equal((await snapshot()).waiting, true);
      if (skin === 'spdb') await shot('spdb-task-conflict.png', 'independent versioned task local-sync conflict, simulated');
      await page.keyboard.press('Escape');
      assert.equal(await page.locator('#status-strip [data-state="recovery"]').isVisible(), true);
      assert.equal((await snapshot()).conflict, true);
    });
  }
  for (const screen of ['home', 'list', 'settings']) {
    await load(screen, 'github-white', dimensions[0], 'unknown');
    await check(`${screen}: unsupported write scenario cannot manufacture Task owner/version in a read/settings page`, async () => {
      assert.equal((await snapshot()).page, screen);
      assert.equal((await snapshot()).operation, null);
      // Playwright retargets option.isDisabled() to its enabled select. Inspect the native option itself.
      assert.deepEqual(await page.locator('#scenario option[value="unknown"]').evaluate(element => ({disabled: element.disabled, selector: element.matches(':disabled')})), {disabled: true, selector: true});
      assert.equal(await page.locator('#status-strip [data-state="unknown"]').isVisible(), false);
      assert.equal(await page.locator('#feedback').isVisible(), true);
      assert.equal((await snapshot()).receipt, null);
      await page.locator('#scenario').focus();
      await page.keyboard.press('Home'); await page.keyboard.press('ArrowDown');
      if (screen === 'settings') {
        assert.equal(await page.locator('#scenario').inputValue(), 'dirty', 'Settings exposes a legitimate ordinary draft scenario');
        assert.equal((await snapshot()).dirty, true);
        await page.keyboard.press('ArrowDown');
      }
      assert.equal(await page.locator('#scenario').inputValue(), 'error', 'Real keyboard must skip disabled write scenarios');
      assert.equal((await snapshot()).operation, null);
    });
  }
  await load('list', 'tech-blue', dimensions[1], 'clean', 'reduce');
  await selected().click();
  await shot('tech-blue-list-reduced-motion-1280.png', '1280 desktop context with reduced motion, simulated');

  await check('single HTML: exact inline CSS/registry/app bytes, hash CSP, scripts after existing DOM', async () => {
    const [html, css, registry, app] = await Promise.all(['review-single.html', 'styles.css', 'semantic-registry.js', 'app.js'].map(name => readFile(join(directory, name), 'utf8')));
    assert.equal(html.match(/<style>([\s\S]*?)<\/style>/)[1], css);
    assert.deepEqual([...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(match => match[1]), [registry, app]);
    const base64 = value => createHash('sha256').update(value).digest('base64');
    const policy = html.match(/<meta http-equiv="Content-Security-Policy" content="([^"]*)">/)[1];
    assert(policy.includes(`script-src 'sha256-${base64(registry)}' 'sha256-${base64(app)}'`));
    for (const required of ["default-src 'none'", "connect-src 'none'", "font-src 'none'", "object-src 'none'", "base-uri 'none'", "form-action 'none'"]) assert(policy.includes(required));
    assert(!/<(?:script|link|img|iframe)\b[^>]*\b(?:src|href)=/i.test(html));
    assert(html.lastIndexOf('</main>') < html.indexOf(`<script>${registry}`), 'Inline app must execute after all body markup exists');
    assert(html.indexOf(`<script>${registry}`) < html.indexOf(`<script>${app}`), 'Local semantic registry must initialize before the app');
  });
  const reference = await context.newPage();
  const projection = () => ({
    main: document.querySelector('#main-content').innerHTML,
    navigation: document.querySelector('nav').innerHTML,
    context: document.querySelector('#context-panel').innerHTML,
    contextHidden: document.querySelector('#context-panel').hidden,
    status: document.querySelector('#status-strip').innerHTML,
    skin: document.documentElement.dataset.skin,
  });
  for (const skin of skins) for (const screen of screens) for (const size of dimensions) {
    await load(screen, skin, size, 'clean', 'no-preference', true);
    await check(`single/${screen}/${skin}/${size.width}: exact DOM and stable browser pixels equal local source`, async () => {
      await reference.setViewportSize(size);
      await reference.goto(`${origin}/index.html?page=${screen}&skin=${skin}&scenario=clean`);
      await reference.waitForFunction(() => window.LoopperDesktop?.ready === true);
      assert.deepEqual(await page.evaluate(projection), await reference.evaluate(projection));
      const source = await stableScreenshot(reference);
      const standalone = await stableScreenshot(page);
      assert.equal(sha256(standalone.buffer), sha256(source.buffer));
    });
  }
  await reference.close();
  await check('single HTML: document-only requests across all 30 desktop combinations', async () => {
    const singles = report.requests.filter(request => new URL(request.url).pathname === '/review-single.html');
    const singlePageIds = new Set(singles.map(request => request.page));
    // This page previously exercised the multi-file source. Bound by the first standalone document request.
    const first = report.requests.findIndex(request => new URL(request.url).pathname === '/review-single.html');
    const requests = report.requests.slice(first).filter(request => singlePageIds.has(request.page));
    assert.equal(singles.length, 30);
    assert(requests.every(request => request.type === 'document' && request.method === 'GET' && new URL(request.url).pathname === '/review-single.html'));
    report.singleDocumentRequests = requests;
  });
  await check('viewer-like HTML injection: all five pages × three skins render with zero requests', async () => {
    const injected = await context.newPage();
    const requests = [], errors = [];
    injected.on('request', request => requests.push(request.url()));
    injected.on('pageerror', error => errors.push(error.message));
    try {
      await injected.setContent(await readFile(join(directory, 'review-single.html'), 'utf8'));
      await injected.waitForFunction(() => window.LoopperDesktop?.ready === true);
      for (const skin of skins) for (const screen of screens) {
        await injected.locator('#skin').selectOption(skin);
        await injected.locator(`nav [data-page="${screen}"]`).click();
        assert.equal(await injected.locator('#main-content h1').count(), 1);
        assert.equal(await injected.locator('html').getAttribute('data-skin'), skin);
        assert.equal(await injected.locator('#context-panel').isVisible(), false);
        assert.equal(await injected.getByText(/模拟|原型/).first().isVisible(), true);
      }
      assert.deepEqual(requests, []);
      assert.deepEqual(errors, []);
      report.injectedPage = {requests, errors, combinations: 15};
    } finally { await injected.close(); }
  });

  // Interaction checks are kept separate from screenshot/matrix checks. Author APIs are read-only.
  // Additional exact public DOM contracts are checked below after the author sources freeze.
  await check('old prototype preservation: all original sources/evidence and exactly 29 screenshots unchanged', async () => {
    report.originalAfter = await originalManifest();
    assert.deepEqual(report.originalAfter, report.originalBefore);
    assert.equal(Object.keys(report.originalAfter).filter(name => name.startsWith('screenshots/')).length, 29);
  });
  await check('source freeze: author prototype, registry, single HTML and verifier did not change during the run', async () => {
    const after = {};
    for (const name of Object.keys(sources)) after[name] = sha256(await readFile(join(directory, name)));
    assert.deepEqual(after, sources);
    report.sourcesAfter = after;
  });
  await check('no external resources, API mutations, page errors, or console errors', async () => {
    assert.deepEqual(report.externalRequests, []);
    assert.deepEqual(report.pageErrors, []);
    assert.deepEqual(report.consoleErrors, []);
    assert(report.requests.every(request => request.method === 'GET' && new URL(request.url).origin === origin && allowedFiles.has(new URL(request.url).pathname)));
  });
  report.executionComplete = true;
} catch (error) {
  report.checks.push({name: 'runner completed all checks', result: 'fail', message: error.stack ?? error.message});
} finally {
  if (context) await context.close();
  if (browser) await browser.close();
  await new Promise(done => server.close(done));
  report.summary = {passed: report.checks.filter(check => check.result === 'pass').length, failed: report.checks.filter(check => check.result === 'fail').length, screenshots: report.screenshots.length, requests: report.requests.length};
  await writeFile(join(directory, 'evidence.json'), JSON.stringify(report, null, 2) + '\n');
  if (rawDirectory) { await mkdir(rawDirectory, {recursive: true}); await writeFile(join(rawDirectory, 'evidence.json'), JSON.stringify(report, null, 2) + '\n'); }
  console.log(JSON.stringify(report.summary));
  if (report.summary.failed || !report.executionComplete) process.exitCode = 1;
}
