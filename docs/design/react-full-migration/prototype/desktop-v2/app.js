/* Independent local mock. No API, model, production controller or framework is mounted here. */
(function desktopPrototype() {
  'use strict';
  const UI = window.LoopperUI;
  if (!UI || UI.schemaVersion !== 1) throw new Error('桌面原型缺少本地语义注册表');
  const $ = selector => document.querySelector(selector);
  const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
  const text = key => esc(UI.label(key)), name = (key, entity = '') => esc(UI.name(key) + (entity ? `：${entity}` : ''));
  const semantic = key => `data-semantic="${esc(key)}"`;
  const pages = { home: 'page.home', list: 'page.tasks', form: 'page.newRequirement', task: 'page.taskDetail', settings: 'page.settings' };
  const query = new URLSearchParams(location.search);
  const normalizePage = value => value === 'detail' ? 'task' : Object.hasOwn(pages, value) ? value : 'home';
  const skins = ['spdb', 'tech-blue', 'github-white'];
  const projects = [
    { id: 'project-orders', name: '订单服务', path: '/workspace/order-service', note: '最近读取的项目', type: 'object.project', route: '/projects' },
    { id: 'project-console', name: '开发者控制台', path: '/workspace/developer-console', note: '最近读取的项目', type: 'object.project', route: '/projects' },
  ];
  const conversations = [
    { id: 'conversation-auth', name: '登录状态在哪里校验？', project: '订单服务', note: '上次已读取的知识对话', type: 'object.conversation', route: '/knowledge/local-auth' },
    { id: 'conversation-events', name: '任务事件如何恢复？', project: '开发者控制台', note: '上次已读取的知识对话', type: 'object.conversation', route: '/knowledge/local-events' },
  ];
  const tasks = [
    { id: 'task-refund', title: '补充退款流程验证', project: '订单服务', state: 'waiting', time: '刚刚', note: '需要确认本次验证范围', type: 'object.task' },
    { id: 'task-access', title: '整理权限边界', project: '开发者控制台', state: 'success', time: '今天', note: '已保存验收证据', type: 'object.task' },
    { id: 'task-recovery', title: '恢复事件读取', project: '订单服务', state: 'recovery', time: '昨天', note: '恢复方案等待人工确认', type: 'object.task' },
    { id: 'task-guide', title: '更新本地开发说明', project: '开发者控制台', state: 'success', time: '昨天', note: '保留设计与历史版本', type: 'object.task' },
    { id: 'task-design', title: '核对会话隔离设计', project: '订单服务', state: 'readonly', time: '本周', note: '查看已冻结的设计记录', type: 'object.task' },
  ];
  const stages = [
    { id: 'stage-scope', title: '确认业务范围', note: '原始目标与约束已保存', state: 'success', type: 'object.stage' },
    { id: 'stage-change', title: '补充退款验证', note: '实现与校验记录可以按需查看', state: 'success', type: 'object.stage' },
    { id: 'stage-review', title: '核对边界案例', note: '等待你确认本次执行范围', state: 'waiting', type: 'object.stage' },
  ];
  const settingsGroups = [
    { id: 'setting-execution', semantic: 'settings.execution', description: '每阶段尝试次数、总尝试次数与等待上限。', type: 'object.settingsGroup' },
    { id: 'setting-models', semantic: 'settings.models', description: '本地已读取的提供方与默认模型。', type: 'object.settingsGroup' },
    { id: 'setting-privacy', semantic: 'settings.privacy', description: '凭据只显示状态；原型不会读取或发送秘密。', type: 'object.settingsGroup' },
  ];
  const initialForm = { title: '', objective: '', project: projects[0].id, template: 'delivery-v3' };
  const settingsDefaults = {
    'setting-execution': { attempts: '3', timeout: '30' },
    'setting-models': { provider: '本地示例提供方', model: '本地示例模型' },
    'setting-privacy': { localOnly: true },
  };
  const state = {
    page: normalizePage(query.get('page')), skin: skins.includes(query.get('skin')) ? query.get('skin') : 'github-white',
    selected: '', expanded: false, returnFocus: '', homeQuery: '', listQuery: '', listStatus: 'all',
    form: { ...initialForm }, files: [], formBaseline: { ...initialForm },
    settings: structuredClone(settingsDefaults), settingsBaseline: structuredClone(settingsDefaults),
    dirtyOwner: null, operation: null, receipts: [], operationSequence: 0,
    error: false, conflict: false, recovery: false, waiting: true, answer: 'current',
    scenario: 'clean', dialog: null, dialogTrigger: null, feedback: '',
  };
  const dialog = $('#confirm-dialog');
  const unresolved = () => !!state.operation && state.operation.phase !== 'settled';
  const formLocked = () => unresolved() && state.operation.owner.page === 'form';
  const objectById = id => [...projects, ...conversations, ...tasks, ...stages, ...settingsGroups].find(item => item.id === id);
  const routeByPath = path => UI.routes.find(route => route.path === path);
  const button = (key, action, options = {}) => `<button type="button" class="${options.iconOnly ? 'icon-button' : `button ${options.tone || ''}`}" ${semantic(key)} data-action="${esc(action)}" ${options.value ? `data-value="${esc(options.value)}"` : ''} aria-label="${name(key, options.entity)}" ${options.disabled ? 'disabled' : ''} ${options.extra?.includes('data-focus-id') ? '' : `data-focus-id="action-${esc(action)}-${esc(options.value || '')}"`} ${options.extra || ''}>${UI.icon(key)}${options.iconOnly ? '' : `<span>${text(key)}</span>`}</button>`;
  const badge = status => `<span class="state-badge" data-state="${esc(status)}" ${semantic(`status.${status}`)}>${text(`status.${status}`)}</span>`;
  function showFeedback(message) {
    state.feedback = message; $('#feedback').textContent = message; $('#feedback').hidden = !message;
  }
  function updateAddress() {
    const next = new URL(location.href); next.searchParams.set('page', state.page); next.searchParams.set('skin', state.skin); next.searchParams.delete('scenario');
    history.replaceState(null, '', next);
  }
  function dirty(page, key) {
    state.dirtyOwner = { page, key: page === 'settings' ? 'settings-page' : key }; renderStatus();
  }
  function restoreFocus(key = state.returnFocus) {
    const target = key ? document.querySelector(`[data-focus-id="${CSS.escape(key)}"]`) : null;
    (target || $('#main-content')).focus({ preventScroll: true });
  }
  function setSelection(id, trigger) {
    if (unresolved() && state.selected && state.selected !== id) { showFeedback('本地模拟：原操作尚未核对，先处理页面上方的恢复入口。'); return; }
    state.selected = id; state.expanded = false;
    if (trigger?.dataset.focusId) state.returnFocus = trigger.dataset.focusId;
    renderContext(); updateSelectionMarks(); $('#context-panel').focus({ preventScroll: true });
  }
  function closeContext() {
    // This is presentation-only. The page owner, its draft/File and operation are retained.
    state.selected = ''; state.expanded = false; renderContext(); updateSelectionMarks(); restoreFocus();
  }
  function updateSelectionMarks() {
    document.querySelectorAll('[data-select]').forEach(element => element.setAttribute('aria-pressed', String(element.dataset.select === state.selected)));
  }
  function requestNavigation(page, trigger) {
    const destination = normalizePage(page);
    if (destination === state.page) { closeContext(); return; }
    if (unresolved()) { renderStatus(); showFeedback('本地模拟：未确认操作禁止离开。面板可以收起，原操作仍由当前页面持有。'); $('#status-strip').querySelector('button')?.focus(); return; }
    if (state.dirtyOwner) { openDialog('leave', trigger, { destination }); return; }
    navigate(destination);
  }
  function navigate(page) {
    state.page = page; state.selected = ''; state.expanded = false; state.returnFocus = ''; state.feedback = ''; state.error = false; state.conflict = false; state.recovery = false;
    $('#feedback').hidden = true; updateAddress(); render(); $('#main-content').focus({ preventScroll: true });
  }
  function message(kind, copy, actionHtml = '') {
    const severity = kind === 'error' ? 'error' : ['unknown', 'waiting', 'recovery'].includes(kind) ? 'warning' : 'neutral';
    return `<div class="state-message" data-state="${kind}" data-severity="${severity}" role="${kind === 'error' || kind === 'unknown' ? 'alert' : 'status'}">${UI.icon(`status.${kind}`)}<div class="state-copy"><strong ${semantic(`status.${kind}`)}>${text(`status.${kind}`)}</strong><p>${esc(copy)}</p></div>${actionHtml}</div>`;
  }
  function renderStatus() {
    const parts = [];
    if (state.error) parts.push(message('error', '本地模拟：读取失败。当前草稿保留，可以重新读取。', button('ui.refresh', 'refresh')));
    if (state.conflict) parts.push(message('recovery', '本地模拟：任务局部修改与新版本冲突。原输入保留，查看差异后决定。', button('ui.open', 'conflict')));
    if (state.operation) {
      const phase = state.operation.phase;
      if (phase === 'sending') parts.push(message('sending', state.operation.owner.page === 'form' ? '本地模拟：创建请求仍在处理中。四字段与再次提交已锁定，不能离开。' : '本地模拟：回答仍在处理中。保留原任务、会话与问题，禁止再次提交或离开。', button('receipt.retryOriginal', 'recover', { disabled: true })));
      else if (phase === 'unknown') parts.push(message('unknown', state.operation.owner.page === 'form' ? '本地模拟：结果未知。显式重试原创建 POST 的同一 body / key；没有自动重发或换 key。' : '本地模拟：回答结果未知。保留原任务、会话与问题身份，只核对原状态；不盲重发没有幂等键的回答。', button(state.operation.owner.page === 'form' ? 'receipt.retryOriginal' : 'receipt.readOriginal', 'recover')));
      else if (phase === 'accepted-nav') parts.push(message('recovery', '本地模拟：确定回执已保留，恢复只打开原目标；不会再次创建。', button('ui.open', 'completeHandoff')));
      else if (phase === 'accepted-read') parts.push(message('recovery', '本地模拟：操作已接受，恢复只重新读取原对象；不会重复写入。', button('receipt.readOriginal', 'recover')));
    }
    if (state.dirtyOwner) parts.push(message('dirty', '本地草稿尚未保存。收起面板不会丢失；离开前需要明确决定。', button('ui.save', 'save', { disabled: unresolved() }) + button('ui.cancelEditing', 'cancelEditing', { disabled: unresolved() })));
    if (state.page === 'task' && state.waiting) parts.push(message('waiting', '当前模拟任务等待确认范围。详情可以收起，待处理问题持续可见。', button('task.answer', 'questionFocus')));
    if (state.recovery && !state.operation && !state.conflict) parts.push(message('recovery', '本地模拟：已有恢复记录可供查看，尚未执行新的任务。', button('task.openRecovery', 'recovery')));
    $('#status-strip').innerHTML = parts.join(''); $('#status-strip').hidden = parts.length === 0;
    document.body.dataset.operationPhase = state.operation?.phase || 'none';
    document.body.dataset.dirty = String(!!state.dirtyOwner);
    const cancelEditing = $('#requirement-form [data-action="cancelEditing"]');
    if (cancelEditing) cancelEditing.disabled = formLocked() || !state.dirtyOwner;
  }
  function heading(key, copy, action = '') {
    return `<header class="page-heading"><div><h1 ${semantic(key)}>${text(key)}</h1><p>${esc(copy)}</p></div>${action}</header>`;
  }
  function recent(item) {
    return `<button class="recent-item" ${semantic('selection.select')} data-select="${item.id}" data-focus-id="${item.id}" aria-label="${name('selection.select', item.name)}" aria-pressed="${state.selected === item.id}"><span class="object-mark">${UI.icon(item.type)}</span><span class="recent-copy"><strong>${esc(item.name)}</strong><small>${esc(item.project || item.path)}</small></span></button>`;
  }
  function home() {
    const q = state.homeQuery.toLocaleLowerCase();
    const matching = item => [item.name, item.project, item.path].filter(Boolean).some(value => value.toLocaleLowerCase().includes(q));
    const ps = projects.filter(matching), cs = conversations.filter(matching);
    return `<div class="page-body home-body" data-background><header class="home-title"><h1 ${semantic('page.home')}>${text('page.home')}</h1><p>从最近的项目与对话继续工作。</p></header><label class="search-field">${UI.icon('ui.search')}<span class="sr-only" ${semantic('field.search')}>${text('field.search')}</span><input id="home-search" name="localSearch" data-focus-id="home-search" ${semantic('ui.search')} aria-label="${name('ui.search')}" value="${esc(state.homeQuery)}" placeholder="${text('ui.search')} ${text('object.project')} / ${text('object.conversation')}" autocomplete="off">${button('ui.clearSearch', 'clearSearch', { iconOnly: true })}</label><p class="search-disclosure">仅筛选此原型已加载的项目与知识对话。不是全站搜索，也不是知识库原文检索。</p><section class="home-section"><div class="section-heading"><h2 ${semantic('section.recentProjects')}>${text('section.recentProjects')}</h2></div><div class="recent-grid" id="recent-projects">${ps.map(recent).join('') || '<p class="empty-result">当前已加载项目没有匹配项。</p>'}</div></section><section class="home-section"><div class="section-heading"><h2 ${semantic('section.recentConversations')}>${text('section.recentConversations')}</h2></div><div class="recent-grid" id="recent-conversations">${cs.map(recent).join('') || '<p class="empty-result">当前已加载对话没有匹配项。</p>'}</div></section><section class="home-section"><div class="section-heading"><h2 ${semantic('section.entries')}>${text('section.entries')}</h2></div><div class="entry-links">${routeEntry('nav.projects', '/projects')}${routeEntry('knowledge.newConversation', '/knowledge')}${button('workflow.newRequirement', 'page', { value: 'form', tone: 'subtle' })}</div></section></div>`;
  }
  function routeEntry(key, path) { return `<button class="entry-link" ${semantic(key)} data-action="route" data-value="${esc(path)}" data-focus-id="route-${esc(path)}" aria-label="${name(key)}">${UI.icon(key)}${text(key)}</button>`; }
  function list() {
    const rows = tasks.filter(row => (!state.listQuery || `${row.title} ${row.project}`.includes(state.listQuery)) && (state.listStatus === 'all' || state.listStatus === row.state));
    return `<div class="page-body" data-background>${heading('page.tasks', '已加载任务的本地视图。选择一行，再查看它的上下文。', button('workflow.newRequirement', 'page', { value: 'form', tone: 'primary' }))}<div class="list-tools"><label class="search-field">${UI.icon('ui.search')}<input id="list-search" name="taskSearch" aria-label="${name('ui.search')}" placeholder="${text('ui.search')} ${text('object.task')}" value="${esc(state.listQuery)}"></label><select id="list-status" name="status" ${semantic('field.status')} aria-label="${name('field.status')}"><option value="all">${text('field.status')} · 全部</option>${['waiting', 'success', 'recovery', 'readonly'].map(status => `<option value="${status}" ${state.listStatus === status ? 'selected' : ''}>${text(`status.${status}`)}</option>`).join('')}</select></div><div class="task-table"><div class="table-head" aria-hidden="true"><span>${text('field.title')}</span><span>${text('field.project')}</span><span>${text('field.status')}</span><span>${text('section.recent')}</span></div><div id="task-rows">${rows.map(row => `<button class="task-row" ${semantic('selection.select')} data-select="${row.id}" data-focus-id="${row.id}" aria-label="${name('selection.select', row.title)}" aria-pressed="${state.selected === row.id}"><span><strong class="row-title">${esc(row.title)}</strong><small class="row-subtitle">${esc(row.note)}</small></span><span class="row-project">${esc(row.project)}</span>${badge(row.state)}<span class="row-time">${esc(row.time)}</span></button>`).join('') || '<p class="empty-result">本页已加载任务没有匹配项。</p>'}</div></div></div>`;
  }
  function field(label, nameValue, value, extra = '') { return `<label class="field" ${semantic(label)}><span>${text(label)}</span><input name="${esc(nameValue)}" value="${esc(value)}" ${extra}></label>`; }
  function form() {
    const locked = formLocked() ? 'disabled' : '';
    return `<div class="page-body form-body" data-background>${heading('page.newRequirement', '先写清目标。更多上下文在需要时展开。', button('nav.back', 'back', { tone: 'subtle' }))}<form id="requirement-form"><section class="form-section"><h2 ${semantic('section.inputs')}>${text('section.inputs')}</h2>${field('field.title', 'title', state.form.title, `id="requirement-title" required maxlength="120" ${locked}`)}<label class="field" ${semantic('field.objective')}><span>${text('field.objective')}</span><textarea id="requirement-objective" name="objective" required ${locked}>${esc(state.form.objective)}</textarea></label></section><section class="form-section"><div class="field-pair"><label class="field" ${semantic('field.project')}><span>${text('field.project')}</span><select name="project" ${locked}>${projects.map(row => `<option value="${row.id}" ${state.form.project === row.id ? 'selected' : ''}>${esc(row.name)}</option>`).join('')}</select></label><label class="field" ${semantic('field.template')}><span>${text('field.template')}</span><select name="template" ${locked}><option value="delivery-v3">交付流程 · 版本 3</option><option value="review-v2" ${state.form.template === 'review-v2' ? 'selected' : ''}>审查流程 · 版本 2</option></select></label></div></section><div class="advanced-row"><span ${semantic('section.advanced')}>${text('section.advanced')}</span><button type="button" class="button subtle" ${semantic('ui.expand')} data-select="form-advanced" data-focus-id="form-advanced" aria-label="${name('ui.expand', UI.label('section.advanced'))}" aria-pressed="${state.selected === 'form-advanced'}" aria-expanded="${state.selected === 'form-advanced'}">${UI.icon('ui.expand')}${text('ui.expand')}</button></div><footer class="form-footer">${button('ui.cancelEditing', 'cancelEditing', { disabled: locked || !state.dirtyOwner })}<button class="button primary" type="submit" ${semantic('workflow.createRequirement')} aria-label="${name('workflow.createRequirement')}" ${locked}>${UI.icon('workflow.createRequirement')}${text('workflow.createRequirement')}</button></footer></form></div>`;
  }
  function task() {
    return `<div class="page-body task-body" data-background>${heading('page.taskDetail', '补充退款流程验证', button('ui.expand', 'taskActions', { tone: 'subtle' }))}<div class="task-intro"><span>${esc(projects[0].name)}</span>${badge(state.waiting ? 'waiting' : 'readonly')}</div>${state.waiting ? `<section class="task-question" aria-label="${name('task.answer')}"><h2>确认本次验证范围</h2><p>保留原退款行为，并补充异常重试的边界验证。当前任务等待你的回答。</p><label class="question-choice"><input type="radio" name="answer" value="current" ${state.answer === 'current' ? 'checked' : ''} ${unresolved() ? 'disabled' : ''}>只验证现有退款与失败重试</label><label class="question-choice"><input type="radio" name="answer" value="extended" ${state.answer === 'extended' ? 'checked' : ''} ${unresolved() ? 'disabled' : ''}>另补充重复请求的边界案例</label><div class="button-row">${button('task.answer', 'answer', { tone: 'primary', disabled: unresolved() })}</div></section>` : '<p class="search-disclosure">本地回执演示已完成；不据此宣称真实任务正在执行。</p>'}<section><div class="section-heading"><h2 ${semantic('object.stage')}>${text('object.stage')}</h2></div><div class="task-timeline">${stages.map((row, index) => `<button class="timeline-item" ${semantic('selection.select')} data-select="${row.id}" data-focus-id="${row.id}" aria-label="${name('selection.select', row.title)}" aria-pressed="${state.selected === row.id}"><span class="timeline-number">${index + 1}</span><div><h3>${esc(row.title)}</h3><p>${esc(row.note)}</p></div>${badge(row.state)}</button>`).join('')}</div></section></div>`;
  }
  function settings() {
    return `<div class="page-body settings-body" data-background>${heading('page.settings', '先选择分区。参数与高级设置在右侧按需打开。')}<section>${settingsGroups.map(group => `<button class="settings-group" ${semantic('selection.select')} data-select="${group.id}" data-focus-id="${group.id}" aria-label="${name('selection.select', UI.label(group.semantic))}" aria-pressed="${state.selected === group.id}"><span class="object-mark">${UI.icon(group.semantic)}</span><div><h2 ${semantic(group.semantic)}>${text(group.semantic)}</h2><p>${esc(group.description)}</p></div>${UI.icon('ui.expand')}</button>`).join('')}</section></div>`;
  }
  const renderers = { home, list, form, task, settings };
  function settingsFields(group) {
    const values = state.settings[group];
    if (group === 'setting-execution') return `${field('section.settingsLimits', 'attempts', values.attempts, 'type="number" min="1" max="20"')}${field('section.parameters', 'timeout', values.timeout, 'type="number" min="1" max="120"')}<p>示例值仅保存在本页内存，没有写入真实配置。</p>`;
    if (group === 'setting-models') return `${field('field.provider', 'provider', values.provider)}${field('field.model', 'model', values.model)}<p>来自本地示例目录，没有读取或调用真实模型。</p>`;
    return `<label class="check-field" ${semantic('field.localOnly')}><input type="checkbox" name="localOnly" ${values.localOnly ? 'checked' : ''}><span>${text('field.localOnly')}</span></label><p>这是原型范围提示，不是生产网络权限开关。凭据不进入该原型。</p>`;
  }
  function contextContents() {
    const item = objectById(state.selected);
    if (state.selected === 'navigation') return { title: UI.label('app.navigation'), type: 'app.navigation', body: `<section class="context-section"><p>以下为现有生产路由索引。五类代表页之外只说明去向，不伪装已迁移。</p>${UI.routes.filter(route => route.view && !route.path.includes(':')).map(route => `<button class="navigation-route" ${semantic(route.nav)} data-action="route" data-value="${esc(route.path)}" aria-label="${name(route.nav)}">${UI.icon(route.nav)}${text(route.nav)}</button>`).join('')}</section>` };
    if (state.selected.startsWith('route:')) {
      const path = state.selected.slice(6), route = routeByPath(path);
      return { title: route ? UI.label(route.nav) : UI.label('section.context'), type: 'section.context', body: `<section class="context-section"><p>入口保留。当前只展示独立设计原型，不进入真实业务页面。</p><div class="route-location">${esc(path)}</div><p>${esc(route?.behavior || '这是原操作回执的目标。原型不验证真实导航或后端协议。')}</p></section>` };
    }
    if (state.selected === 'form-advanced') return { title: UI.label('section.advanced'), type: 'object.requirement', body: `<section class="context-section"><h3 ${semantic('section.parameters')}>${text('section.parameters')}</h3><p>新建需求只有标题、目标、项目与流程四字段。进入规划画布后再调整节点与任务，确认计划后才开始执行。</p><p>这是呈现说明，没有新增附件、模型设置或业务写入口。</p></section>` };
    if (state.selected === 'task-actions') return { title: UI.label('section.advanced'), type: 'object.task', body: `<section class="context-section"><p>当前模拟任务等待输入，不提供开始执行、继续一轮或发布入口。</p><div class="button-row">${button('task.openDesign', 'route', { value: '/tasks/local-task/design' })}${button('task.openRecovery', 'recovery')}${button('task.cancel', 'taskCancel', { value: 'task', tone: 'danger', disabled: unresolved() })}</div></section>` };
    if (state.selected === 'conflict') return { title: UI.label('status.recovery'), type: 'object.task', body: `<section class="context-section"><h3>局部同步冲突 · 本地模拟</h3><p>这是独立的有版本任务局部修改示例，不能推导当前等待输入任务可发布，也不是 Settings CAS。</p><div class="conflict-copy">原输入：只验证现有失败重试。<br>新读取版本：增加了重复请求检查。</div>${button('receipt.readOriginal', 'resolveConflict')}</section>` };
    if (state.selected === 'recovery') return { title: UI.label('object.recovery'), type: 'object.task', body: `<section class="context-section"><p>恢复草稿只供查看。没有执行新任务，也没有新建身份或自动重发。</p>${button('task.openDesign', 'route', { value: '/tasks/local-task/design' })}</section>` };
    if (!item) return { title: UI.label('section.context'), type: 'section.context', body: '' };
    const title = item.title || item.name || UI.label(item.semantic);
    if (item.type === 'object.settingsGroup') return { title, type: item.type, body: `<form id="settings-form" data-group="${item.id}"><section class="context-section">${settingsFields(item.id)}</section><div class="button-row">${button('settings.save', 'save', { tone: 'primary', disabled: unresolved() })}${button('ui.cancelEditing', 'cancelEditing', { disabled: unresolved() })}</div></form>` };
    if (item.type === 'object.project' || item.type === 'object.conversation') return { title, type: item.type, body: `<section class="context-section"><dl class="context-meta"><dt>${text(item.type)}</dt><dd>${esc(title)}</dd><dt>${text('field.project')}</dt><dd>${esc(item.project || item.name)}</dd></dl><p>${esc(item.note)}</p><div class="button-row">${button(item.type === 'object.project' ? 'project.select' : 'ui.open', 'route', { value: item.route })}</div></section><section class="context-section"><p>来自已加载的模拟对象；正式 Recent 可消费 Projects 与 KnowledgeHistory 的既有读取，不新增全站聚合接口。</p></section>` };
    return { title, type: item.type, body: `<section class="context-section">${badge(item.state)}<p>${esc(item.note)}</p><dl class="context-meta"><dt>${text('field.project')}</dt><dd>${esc(item.project || projects[0].name)}</dd><dt>${text('field.scope')}</dt><dd>保留当前原始范围</dd></dl></section><section class="context-section"><h3 ${semantic('section.evidence')}>${text('section.evidence')}</h3><button class="evidence-link" ${semantic('ui.open')} data-action="evidence" data-focus-id="evidence-open" aria-label="${name('ui.open', '原始校验记录')}">${UI.icon('ui.open')}${text('ui.open')}<span>· 原始校验记录</span></button><button class="evidence-link" ${semantic('task.openDesign')} data-action="route" data-value="/tasks/local-task/design" data-focus-id="task-design" aria-label="${name('task.openDesign', title)}">${UI.icon('task.openDesign')}${text('task.openDesign')}</button><div class="button-row">${button('task.open', 'page', { value: 'task' })}${button('ui.delete', 'delete', { value: item.id, disabled: unresolved(), tone: 'danger' })}</div></section>` };
  }
  function renderContext() {
    const panel = $('#context-panel'), layout = $('#page-layout');
    panel.hidden = !state.selected; layout.classList.toggle('has-context', !!state.selected); layout.classList.toggle('context-expanded', !!state.selected && state.expanded);
    if (!state.selected) { panel.innerHTML = ''; return; }
    const context = contextContents();
    panel.setAttribute('aria-label', UI.name('app.context') + '：' + context.title);
    panel.innerHTML = `<header class="context-heading"><div><small ${semantic(context.type)}>${text(context.type)}</small><h2>${esc(context.title)}</h2></div><div class="context-controls">${button(state.expanded ? 'ui.collapse' : 'ui.expand', state.expanded ? 'collapse' : 'expand', { iconOnly: true, extra: `aria-expanded="${state.expanded}"` })}${button('ui.close', 'close', { iconOnly: true })}</div></header>${context.body}<div class="button-row">${button('selection.deselect', 'deselect', { tone: 'subtle' })}</div>`;
  }
  function render() {
    document.documentElement.dataset.skin = state.skin; document.body.dataset.page = state.page; $('#skin').value = state.skin; $('#scenario').value = state.scenario;
    const supported = supportedScenarios(state.page);
    for (const option of $('#scenario').options) option.disabled = !supported.includes(option.value);
    $('#scenario').title = state.page === 'form' ? '新建需求：四字段草稿与原创建身份恢复模拟' : state.page === 'task' ? '任务：回答状态核对与独立局部同步冲突模拟' : '此页仅支持本地读取状态；设置可演示普通草稿';
    $('#navigation').innerHTML = Object.entries(pages).map(([page, key]) => `<a class="nav-item" href="?page=${page}&skin=${state.skin}" data-page="${page}" data-focus-id="nav-${page}" ${semantic(key)} aria-label="${name(key)}" ${page === state.page ? 'aria-current="page"' : ''}>${UI.icon(key)}${text(key)}</a>`).join('');
    $('#main-content').innerHTML = renderers[state.page](); renderStatus(); renderContext(); updateSelectionMarks();
  }
  function renderFiltered() {
    // Preserve the actual input and its caret while filtering only already-loaded objects.
    const template = document.createElement('template'); template.innerHTML = state.page === 'home' ? home() : list();
    for (const id of state.page === 'home' ? ['recent-projects', 'recent-conversations'] : ['task-rows']) $(`#${id}`).innerHTML = template.content.querySelector(`#${id}`).innerHTML;
    updateSelectionMarks();
  }
  function openDialog(kind, trigger, detail = {}) {
    state.dialog = { kind, ...detail }; state.dialogTrigger = trigger || document.activeElement;
    $('#confirmation-title').textContent = UI.label(kind === 'delete' ? 'ui.delete' : kind === 'taskCancel' ? 'task.cancel' : 'ui.cancelEditing');
    const copy = kind === 'delete' ? '本地模拟：该操作需要明确确认。历史与原始文件不会由此原型删除。' : kind === 'taskCancel' ? '本地模拟：取消服务端任务与收起面板不同。真实取消仍需停止证明；此原型只演示确认，不伪造任务已停止。' : '离开会放弃尚未保存的本地草稿。选择留在当前页面将保留输入。';
    $('#confirmation-body').innerHTML = `<p>${copy}</p><div class="dialog-actions">${button('ui.stay', 'dialogClose')}${button(kind === 'delete' ? 'ui.delete' : kind === 'taskCancel' ? 'task.cancel' : 'ui.discardChanges', 'confirmDiscard', { tone: kind === 'delete' || kind === 'taskCancel' ? 'danger' : 'primary' })}</div>`;
    dialog.showModal(); $('#confirmation-body').querySelector('[data-semantic="ui.stay"]')?.focus({ preventScroll: true });
  }
  function closeDialog() { dialog.close(); }
  function discardOwner() {
    const owner = state.dirtyOwner;
    if (owner?.page === 'form') { state.form = { ...state.formBaseline }; state.files = []; }
    if (owner?.page === 'settings') state.settings = structuredClone(state.settingsBaseline);
    state.dirtyOwner = null;
  }
  function saveOwner() {
    if (unresolved()) { renderStatus(); return; }
    const owner = state.dirtyOwner;
    if (owner?.page === 'form') state.formBaseline = { ...state.form };
    if (owner?.page === 'settings') state.settingsBaseline = structuredClone(state.settings);
    state.dirtyOwner = null; renderStatus(); showFeedback('本地模拟：保存到本页内存，没有业务写请求。');
  }
  function captureOperation(page, phase = 'unknown') {
    if (unresolved()) return;
    const key = `desktop-mock-${++state.operationSequence}`;
    const body = page === 'form' ? { title: state.form.title, objective: state.form.objective, projectId: state.form.project, templateId: state.form.template, templateRevision: state.form.template === 'delivery-v3' ? 3 : 2, requestKey: key } : { answers: [[state.answer === 'current' ? '只验证现有退款与失败重试' : '另补充重复请求的边界案例']] };
    state.operation = { owner: { page, id: page === 'form' ? 'local-requirement' : 'local-task', ...(page === 'task' ? { sessionKey: 'local-session', questionId: 'local-question' } : {}) }, key, body: Object.freeze(body), files: Object.freeze([...state.files]), phase, mutations: 1, reads: 0, navigationAttempts: 0, receipt: null };
  }
  function recoverOperation() {
    const operation = state.operation;
    if (!operation || operation.phase === 'sending') return;
    if (operation.phase === 'unknown') {
      if (operation.owner.page === 'form') {
        operation.mutations++; operation.receipt = Object.freeze({ id: operation.owner.id, requestKey: operation.key }); operation.phase = 'accepted-nav';
      } else {
        operation.reads++; operation.phase = 'settled'; state.waiting = false;
      }
    } else if (operation.phase === 'accepted-read') {
      operation.reads++; operation.phase = 'settled'; state.receipts.push(operation.receipt); state.waiting = false; state.dirtyOwner = null;
    }
    render(); showFeedback('本地恢复模拟已更新；没有查询服务或发送请求，不能作为真实协议验证。');
  }
  function supportedScenarios(page) {
    return page === 'form' ? ['clean', 'dirty', 'sending', 'unknown', 'recovery', 'error'] : page === 'task' ? ['clean', 'sending', 'unknown', 'waiting', 'recovery', 'error', 'conflict'] : page === 'settings' ? ['clean', 'dirty', 'error'] : ['clean', 'error'];
  }
  function applyScenario(value) {
    if (!supportedScenarios(state.page).includes(value)) { showFeedback('此页不支持该模拟情境。没有创建其他业务域的操作或版本。'); return; }
    if (unresolved()) {
      if (state.operation.phase === 'sending' && value === 'unknown') { state.operation.phase = 'unknown'; state.scenario = value; return; }
      showFeedback('本地模拟：切换情境不能丢弃未确认操作，请先从原恢复入口处理。'); return;
    }
    state.scenario = value; state.error = false; state.conflict = false; state.recovery = false;
    if (value === 'dirty') {
      if (state.page === 'form') { state.form.title ||= '保留本地草稿'; state.form.objective ||= '原始目标与未提交内容'; dirty('form', 'form'); }
      else { state.settings['setting-execution'].attempts = '4'; dirty('settings', 'settings-page'); }
    } else if (['unknown', 'sending', 'recovery'].includes(value)) {
      if (state.page === 'form') { state.form.title ||= '保留原创建身份'; state.form.objective ||= '恢复原请求，不换 key'; }
      captureOperation(state.page, value === 'recovery' ? (state.page === 'form' ? 'accepted-nav' : 'accepted-read') : value);
      if (value === 'recovery') state.operation.receipt = Object.freeze({ id: state.operation.owner.id, ...(state.page === 'form' ? { requestKey: state.operation.key } : {}) });
    } else if (value === 'error') state.error = true;
    else if (value === 'conflict') state.conflict = true;
    else if (value === 'waiting') state.waiting = true;
  }
  document.addEventListener('click', event => {
    const target = event.target instanceof Element ? event.target.closest('button,a') : null;
    if (!target) { if (event.target instanceof Element && event.target.hasAttribute('data-background')) closeContext(); return; }
    if (target.matches(':disabled')) return;
    if (target.dataset.page) { event.preventDefault(); requestNavigation(target.dataset.page, target); return; }
    if (target.dataset.select) { setSelection(target.dataset.select, target); return; }
    const action = target.dataset.action, value = target.dataset.value;
    if (!action) return;
    if (action === 'page') requestNavigation(value, target);
    else if (action === 'back') requestNavigation('list', target);
    else if (action === 'close' || action === 'deselect') closeContext();
    else if (action === 'expand' || action === 'collapse') { state.expanded = action === 'expand'; renderContext(); $('#context-panel').focus({ preventScroll: true }); }
    else if (action === 'navigation') setSelection('navigation', target);
    else if (action === 'route') setSelection(`route:${value}`, target);
    else if (action === 'taskActions') setSelection('task-actions', target);
    else if (action === 'conflict') setSelection('conflict', target);
    else if (action === 'recovery') setSelection('recovery', target);
    else if (action === 'delete' || action === 'taskCancel') { if (!unresolved()) openDialog(action, target, { object: value }); }
    else if (action === 'cancelEditing') { if (!unresolved() && state.dirtyOwner) openDialog('discard', target); }
    else if (action === 'dialogClose') closeDialog();
    else if (action === 'confirmDiscard') {
      if (unresolved()) { closeDialog(); renderStatus(); return; }
      const intent = state.dialog;
      if (!['delete', 'taskCancel'].includes(intent?.kind)) discardOwner();
      closeDialog();
      if (intent?.kind === 'leave') navigate(intent.destination);
      else { render(); showFeedback(intent?.kind === 'delete' || intent?.kind === 'taskCancel' ? '本地模拟：明确确认已记录，没有删除业务数据或取消真实服务。' : '本地模拟：只放弃了该 owner 的未保存修改。'); }
    } else if (action === 'save') saveOwner();
    else if (action === 'recover') recoverOperation();
    else if (action === 'completeHandoff') {
      const operation = state.operation;
      if (operation?.phase !== 'accepted-nav') return;
      operation.navigationAttempts++; operation.phase = 'settled'; state.receipts.push(operation.receipt); state.dirtyOwner = null;
      render(); setSelection(`route:/requirements/${operation.receipt.id}`, target);
    } else if (action === 'refresh') { state.error = false; renderStatus(); showFeedback('本地模拟：读取错误已清除。未执行真实读取或业务写入。'); }
    else if (action === 'resolveConflict') { state.conflict = false; renderStatus(); showFeedback('本地模拟：保留原输入并标记重新读取，没有发布当前等待输入任务。'); }
    else if (action === 'answer') { captureOperation('task'); renderStatus(); render(); }
    else if (action === 'questionFocus') $('.task-question input')?.focus();
    else if (action === 'clearSearch') { state.homeQuery = ''; $('#home-search').value = ''; renderFiltered(); $('#home-search').focus(); }
    else if (action === 'evidence') showFeedback('本地示例：原始校验记录保留。原型没有读取真实日志或文件。');
  });
  document.addEventListener('input', event => {
    const input = event.target;
    if (!(input instanceof HTMLElement) || input.matches(':disabled')) return;
    if (input.id === 'home-search') { state.homeQuery = input.value; renderFiltered(); }
    else if (input.id === 'list-search') { state.listQuery = input.value; renderFiltered(); }
    else if (input.closest('#requirement-form') && ['title', 'objective'].includes(input.name) && !formLocked()) { state.form[input.name] = input.value; dirty('form', 'form'); }
    else if (input.closest('#settings-form')) {
      const group = input.closest('#settings-form').dataset.group;
      state.settings[group][input.name] = input.type === 'checkbox' ? input.checked : input.value; dirty('settings', group);
    }
  });
  document.addEventListener('change', event => {
    const input = event.target;
    if (!(input instanceof HTMLElement) || input.matches(':disabled')) return;
    if (input.id === 'skin') { state.skin = input.value; document.documentElement.dataset.skin = state.skin; updateAddress(); }
    else if (input.id === 'list-status') { state.listStatus = input.value; renderFiltered(); }
    else if (input.closest('#requirement-form') && ['project', 'template'].includes(input.name) && !formLocked()) { state.form[input.name] = input.value; dirty('form', 'form'); }
    else if (input.name === 'answer') state.answer = input.value;
    else if (input.id === 'scenario') { applyScenario(input.value); render(); }
  });
  document.addEventListener('submit', event => {
    event.preventDefault();
    if (event.target.id === 'requirement-form' && !formLocked()) { captureOperation('form'); render(); }
    if (event.target.id === 'settings-form') saveOwner();
  });
  document.addEventListener('keydown', event => {
    if (event.key !== 'Escape' || dialog.open) return;
    if (state.selected) { event.preventDefault(); closeContext(); }
    else if (state.feedback) { state.feedback = ''; $('#feedback').hidden = true; }
  });
  dialog.addEventListener('close', () => { const target = state.dialogTrigger; state.dialog = null; state.dialogTrigger = null; if (target?.isConnected) target.focus({ preventScroll: true }); else restoreFocus(); });
  dialog.addEventListener('keydown', event => {
    if (event.key !== 'Tab') return;
    const controls = [...dialog.querySelectorAll('button:not(:disabled),a[href],input:not(:disabled),select:not(:disabled),textarea:not(:disabled),[tabindex="0"]')].filter(element => element.getClientRects().length);
    const first = controls[0], last = controls.at(-1);
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  });
  window.addEventListener('beforeunload', event => { if (state.dirtyOwner || unresolved()) { event.preventDefault(); event.returnValue = ''; } });
  $('#brand-icon').innerHTML = UI.icon('nav.home'); $('#brand-name').textContent = 'Loopper';
  $('#navigation').setAttribute('aria-label', UI.name('app.navigation'));
  $('#navigation-index').innerHTML = UI.icon('app.navigation') + text('app.navigation'); $('#navigation-index').setAttribute('aria-label', UI.name('app.navigation')); $('#navigation-index').dataset.focusId = 'navigation-index';
  $('#workspace-name').textContent = UI.label('app.workspace'); $('#prototype-label').textContent = UI.label('app.prototype');
  $('#rail-note').textContent = UI.label('app.prototype'); $('#skin-label').textContent = UI.label('object.skin'); $('#skin').setAttribute('aria-label', UI.name('settings.changeSkin'));
  $('#skip-content').textContent = UI.label('app.skipContent'); $('#prototype-note').textContent = '独立本地模拟 · 不连接业务服务或模型';
  $('#scope-note').innerHTML = `<label ${semantic('app.scenarios')}>${text('app.scenarios')} <select id="scenario" name="scenario" aria-label="${name('app.scenarios')}" data-mock-control><option value="clean">${text('status.readonly')}</option>${['dirty', 'sending', 'unknown', 'waiting', 'recovery', 'error'].map(mode => `<option value="${mode}">${text(`status.${mode}`)}</option>`).join('')}<option value="conflict">局部同步冲突</option></select></label>`;
  $('#confirmation-close').innerHTML = UI.icon('ui.close'); $('#confirmation-close').setAttribute('aria-label', UI.name('ui.close')); $('#confirmation-close').dataset.semantic = 'ui.close';
  applyScenario(query.get('scenario') || 'clean'); render();
  window.LoopperDesktop = Object.freeze({ ready: true, snapshot() {
    const operation = state.operation;
    return structuredClone({ page: state.page, skin: state.skin, selected: state.selected, expanded: state.expanded, dirty: !!state.dirtyOwner, dirtyOwner: state.dirtyOwner, owner: operation?.owner || state.dirtyOwner || { page: state.page }, receipt: operation?.receipt || state.receipts.at(-1) || null, form: state.form, files: state.files.map(file => ({ name: file.name, size: file.size, type: file.type })), settings: state.settings, operation: operation ? { owner: operation.owner, key: operation.key, body: operation.body, phase: operation.phase, mutations: operation.mutations, reads: operation.reads, navigationAttempts: operation.navigationAttempts, files: operation.files.map(file => ({ name: file.name, size: file.size, type: file.type })) } : null, error: state.error, waiting: state.waiting, conflict: state.conflict, scenario: state.scenario });
  } });
})();
