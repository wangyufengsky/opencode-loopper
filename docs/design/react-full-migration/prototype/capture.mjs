// Design-only browser evidence. Uses an already-installed official Playwright.
// Never starts or imports the product, never calls an API, never installs dependencies.
import { strict as assert } from 'node:assert';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createServer } from 'node:http';

const directory = dirname(fileURLToPath(import.meta.url));
const toolsFlag = process.argv.indexOf('--tools');
const toolsRoot = toolsFlag < 0 ? resolve(directory, '../../../../frontend') : resolve(process.argv[toolsFlag + 1]);
const { chromium } = await import(pathToFileURL(join(toolsRoot, 'node_modules/playwright/index.mjs')).href);
const output = join(directory, 'screenshots');
await mkdir(output, { recursive: true });
const hash = data => createHash('sha256').update(data).digest('hex');
const sources = {};
for (const name of ['index.html', 'prototype.css', 'prototype.js', 'capture.mjs']) sources[name] = hash(await readFile(join(directory, name)));
const report = {kind:'Independent design prototype, simulated data; not product acceptance',baseline:'a3c692d38925206883f2b0a1255479108cfd439e',sources,checks:[],screenshots:[],externalRequests:0,pageErrors:[]};
// Normal loopback preview: serve only these three prototype files, never a workspace directory.
const allowedFiles={'/':'index.html','/index.html':'index.html','/prototype.css':'prototype.css','/prototype.js':'prototype.js'};
const server=createServer(async(req,res)=>{const name=allowedFiles[new URL(req.url,'http://localhost').pathname];if(!name){res.writeHead(404);res.end();return;}try{res.setHeader('Content-Type',name.endsWith('.css')?'text/css':name.endsWith('.js')?'text/javascript':'text/html; charset=utf-8');res.end(await readFile(join(directory,name)));}catch{res.writeHead(500);res.end();}});
await new Promise((done,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',done);});
const previewOrigin=`http://127.0.0.1:${server.address().port}`;
let browser;
try{browser=await chromium.launch({executablePath:process.env.PLAYWRIGHT_CHROME_EXECUTABLE || '/usr/bin/chromium',headless:true});}catch(error){await new Promise(done=>server.close(done));throw error;}
report.browser = browser.version();
const context = await browser.newContext({viewport:{width:1440,height:960},locale:'zh-CN'});
await context.route(/^https?:\/\//, route => {if(new URL(route.request().url()).origin===previewOrigin)return route.continue();report.externalRequests++;return route.abort();});
const page = await context.newPage();
page.on('pageerror', error => report.pageErrors.push(error.message));
async function check(name, action){try{await action();report.checks.push({name,result:'pass'});}catch(error){report.checks.push({name,result:'fail',message:error.message});}}
async function load(screen,skin='github-white',width=1440,motion='no-preference'){
  await page.setViewportSize({width,height:width<640?844:960});
  await page.emulateMedia({reducedMotion:motion});
  await page.goto(previewOrigin+`/?page=${screen}&skin=${skin}`);
  await page.locator('main h1').waitFor();
  await page.evaluate(()=>document.fonts.ready);
}
async function shot(name,state){await page.evaluate(async()=>{await Promise.all(document.getAnimations().map(a=>a.finished.catch(()=>{})));});const fullPage=!(await page.locator('dialog').evaluate(d=>d.open));await page.screenshot({path:join(output,name),fullPage});const data=await readFile(join(output,name));report.screenshots.push({file:`screenshots/${name}`,sha256:hash(data),bytes:data.length,viewport:page.viewportSize(),fullPage,skin:await page.locator('html').getAttribute('data-skin'),state});}

try{
  const skins=['spdb','tech-blue','github-white'], screens=['home','list','form','detail','settings'];
  // All 45 combinations get actual DOM/viewport checks; keep a curated 29-image index.
  for(const skin of skins)for(const screen of screens)for(const width of [1440,390,320]){
    await load(screen,skin,width);
    await check(`${screen}/${skin}/${width}: visible mock label, one heading, no page horizontal overflow`,async()=>{
      assert.equal(await page.locator('main h1').count(),1);
      assert.equal(await page.locator('.prototype-label').isVisible(),true);
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    });
    if(width===1440 || width===390&&skin==='github-white' || width===390&&((skin==='spdb'&&screen==='home')||(skin==='tech-blue'&&['form','detail'].includes(screen))) || width===320&&skin==='github-white'&&['list','settings'].includes(screen)){
      await shot(`${skin}-${screen}-${width}.png`,`${screen}, default, simulated`);
    }
  }
  await load('list');
  await check('keyboard: skip link focuses main',async()=>{await page.keyboard.press('Tab');assert.equal(await page.locator('.skip-link').evaluate(e=>e===document.activeElement),true);await page.keyboard.press('Enter');assert.equal(await page.locator('main').evaluate(e=>e===document.activeElement),true);});
  await check('list: search preserves explicit result count',async()=>{await page.locator('#search').fill('退款');assert.equal(await page.locator('#result-count').textContent(),'1 项模拟任务');await page.locator('#search').fill('');});
  await check('list: state filter, recovery action discoverable',async()=>{await page.locator('[data-filter="待处理"]').click();assert.equal(await page.locator('#result-count').textContent(),'3 项模拟任务');await page.locator('[data-filter="全部"]').click();await page.locator('[data-row="2"]').click();assert.equal(await page.locator('dialog').getByRole('button',{name:'恢复任务',exact:true}).isVisible(),true);await page.keyboard.press('Escape');});
  await check('drawer: 24 Tab and Shift-Tab stay inside; Escape returns trigger',async()=>{
    await page.locator('[data-open="filters"]').click();
    for(const key of ['Tab','Shift+Tab'])for(let i=0;i<24;i++){await page.keyboard.press(key);assert.equal(await page.locator('dialog').evaluate(d=>d.contains(document.activeElement)),true);}
    await page.keyboard.press('Escape');assert.equal(await page.locator('[data-open="filters"]').evaluate(e=>e===document.activeElement),true);
  });
  await page.locator('[data-open="filters"]').click();
  await check('default motion: drawer has purposeful 180ms entry, controls immediately usable',async()=>{const s=await page.locator('dialog').evaluate(e=>({animation:getComputedStyle(e).animationName,duration:getComputedStyle(e).animationDuration}));assert.equal(s.animation,'panel-in');assert.equal(s.duration,'0.18s');assert.equal(await page.locator('dialog').getByRole('button',{name:'关闭面板',exact:true}).isEnabled(),true);});
  await shot('github-white-list-filters.png','filter drawer open, simulated');await page.keyboard.press('Escape');
  await check('drawer: ten repeated open/close cycles retain one dialog and correct focus',async()=>{for(let i=0;i<10;i++){await page.locator('[data-open="filters"]').click();await page.keyboard.press('Escape');assert.equal(await page.locator('dialog[open]').count(),0);}assert.equal(await page.locator('dialog').count(),1);assert.equal(await page.locator('[data-open="filters"]').evaluate(e=>e===document.activeElement),true);});
  await check('list: search and status combine; applied project filter persists on reopen',async()=>{await page.locator('#search').fill('退款');await page.locator('[data-filter="已完成"]').click();assert.equal(await page.locator('#result-count').textContent(),'0 项模拟任务');await page.locator('[data-filter="全部"]').click();assert.equal(await page.locator('#result-count').textContent(),'1 项模拟任务');await page.locator('#search').fill('');await page.locator('[data-open="filters"]').click();await page.locator('dialog select').first().selectOption({label:'订单服务'});await page.locator('[data-apply]').click();assert.equal(await page.locator('#result-count').textContent(),'2 项模拟任务');await page.locator('[data-open="filters"]').click();assert.equal(await page.locator('dialog select').first().inputValue(),'订单服务');await page.keyboard.press('Escape');});
  await load('form','tech-blue');
  await check('theme: draft and field values survive three skin changes',async()=>{await page.locator('[name="title"]').fill('原型草稿保留检查');for(const s of skins){await page.locator('#skin').selectOption(s);assert.equal(await page.locator('[name="title"]').inputValue(),'原型草稿保留检查');}await page.locator('#skin').selectOption('tech-blue');});
  await check('mock dirty: navigating to tasks offers explicit cancel without losing input',async()=>{await page.locator('#navigation [data-page="list"]').click();assert.equal(await page.locator('main h1').textContent(),'新建需求');assert.equal(await page.locator('[name="title"]').inputValue(),'原型草稿保留检查');assert.equal(await page.locator('[data-discard]').isVisible(),true);await page.keyboard.press('Escape');});
  await check('mock unknown: close panel then attempt navigation; original draft remains',async()=>{await page.locator('[data-open="unknown"]').click();await page.keyboard.press('Escape');await page.locator('#navigation [data-page="list"]').click();assert.equal(await page.locator('#dialog-title').textContent(),'结果尚未确认');assert.equal(await page.locator('[name="title"]').inputValue(),'原型草稿保留检查');});
  await shot('tech-blue-form-unknown.png','unknown receipt and guarded draft, simulated');
  await page.locator('[data-resolve]').click();
  await check('mock unknown: query clears only demo unknown, never issues request',async()=>{assert.equal(await page.locator('#form-receipt').textContent(),'');assert.equal(await page.locator('[name="title"]').inputValue(),'原型草稿保留检查');});
  await check('mock dirty: explicit discard navigates only after unknown is resolved',async()=>{await page.locator('#navigation [data-page="list"]').click();await page.locator('[data-discard]').click();assert.equal(await page.locator('main h1').textContent(),'任务');});
  await load('detail','spdb',390);
  await check('detail: answer focus and secondary recovery/stop stay accessible',async()=>{await page.locator('[data-focus="answer"]').first().click();assert.equal(await page.locator('#answer').evaluate(e=>e===document.activeElement),true);await page.locator('.summary summary').click();assert.equal(await page.locator('.summary [data-open="recovery"]').isVisible(),true);await page.locator('.summary [data-open="stop"]').click();assert.equal(await page.locator('dialog [autofocus]').evaluate(e=>e===document.activeElement),true);await page.keyboard.press('Escape');});
  await load('detail','spdb');
  await check('task versioned resolution: explicit independent 409 demo retains editable solution draft',async()=>{await page.locator('[data-open="conflict"]').click();assert.equal(await page.locator('#dialog-title').textContent(),'冲突解决稿版本已变化');await page.locator('#conflict-draft').fill('模拟解决稿仍在；不自动覆盖或重复提交');assert.equal(await page.locator('#conflict-draft').inputValue(),'模拟解决稿仍在；不自动覆盖或重复提交');assert.equal(await page.locator('dialog').getByText(/不代表当前等待回答任务具备发布权限/).isVisible(),true);});
  await shot('spdb-detail-conflict.png','independent versioned local-sync solution 409 presentation, simulated');await page.keyboard.press('Escape');
  await load('settings','spdb');
  await check('settings: advanced disclosure keeps input and does not invent CAS API fields',async()=>{await page.locator('.disclosure summary').click();await page.locator('input[type="number"]').first().fill('45');assert.equal(await page.locator('input[type="number"]').first().inputValue(),'45');assert.equal(await page.locator('#settings-form').getByText('原设置保存协议，不新增版本字段').isVisible(),true);});
  await check('settings: public switch toggles with keyboard Space',async()=>{await page.locator('[data-setting="执行限制"]').click();await page.locator('[role="switch"]').focus();await page.keyboard.press('Space');assert.equal(await page.locator('[role="switch"]').getAttribute('aria-checked'),'true');});
  await check('settings: switching sections retains demo field and disclosure draft',async()=>{await page.locator('[data-setting="模型与会话"]').click();assert.equal(await page.locator('input[type="number"]').first().inputValue(),'45');assert.equal(await page.locator('.disclosure').getAttribute('open'),'');await page.locator('[data-setting="执行限制"]').click();assert.equal(await page.locator('[role="switch"]').getAttribute('aria-checked'),'true');});
  await load('home','github-white',390);
  await check('mobile navigation: management group retains all 16 destinations and closes to trigger',async()=>{await page.locator('.mobile-menu').click();assert.equal(await page.locator('dialog .nav-item').count(),16);await page.locator('dialog summary').click();assert.equal(await page.locator('dialog [data-page="settings"]').isVisible(),true);await page.keyboard.press('Escape');assert.equal(await page.locator('.mobile-menu').evaluate(e=>e===document.activeElement),true);});
  await load('list','tech-blue',390,'reduce');await page.locator('[data-open="filters"]').click();
  await check('reduced motion: drawer opens without transition or animation, keyboard remains usable',async()=>{const s=await page.locator('dialog').evaluate(e=>({animation:getComputedStyle(e).animationName,transition:getComputedStyle(e).transitionDuration}));assert.equal(s.animation,'none');assert.equal(s.transition,'0s');await page.keyboard.press('Tab');assert.equal(await page.locator('dialog').evaluate(d=>d.contains(document.activeElement)),true);});
  await shot('tech-blue-list-reduced-motion.png','reduced-motion drawer, simulated');
  await check('isolated prototype: zero external requests and page errors',async()=>{assert.equal(report.externalRequests,0);assert.deepEqual(report.pageErrors,[]);});
}finally{
  report.summary={passed:report.checks.filter(x=>x.result==='pass').length,failed:report.checks.filter(x=>x.result==='fail').length,screenshots:report.screenshots.length};
  await context.close();await browser.close();await new Promise(done=>server.close(done));
  await writeFile(join(directory,'evidence.json'),JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify(report.summary));
  if(report.summary.failed)process.exitCode=1;
}
