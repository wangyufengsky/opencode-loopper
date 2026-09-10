<script setup lang="ts">
import { Icon } from '@iconify/vue'
import PageHeader from '@/components/PageHeader.vue'
import orbitArtwork from '@/assets/home-orbit.png'

const workspaceLinks = [
  { to: '/projects', icon: 'lucide:folder-kanban', title: '项目', description: '登记代码仓库，管理项目上下文。', step: '01', hint: '准备工作区', tone: 'blue' },
  { to: '/designer', icon: 'lucide:sparkles', title: '设计与执行规范', description: '从需求出发，梳理设计与验收标准。', step: '02', hint: '把想法变成方案', tone: 'violet' },
  { to: '/tasks', icon: 'lucide:orbit', title: '任务', description: '跟进执行过程，查看产物与评审。', step: '03', hint: '推进交付', tone: 'cyan' },
  { to: '/inbox', icon: 'lucide:inbox', title: '待处理中心', description: '集中处理问题、权限与待确认事项。', step: '04', hint: '处理关键决定', tone: 'blue' },
]
const moreLinks = [
  { to: '/designs', icon: 'lucide:history', title: '历史设计', description: '回到已有的讨论与方案' },
  { to: '/insights', icon: 'lucide:chart-no-axes-combined', title: '质量与用量', description: '查看验收结果与模型用量' },
  { to: '/automations', icon: 'lucide:workflow', title: '模板与自动化', description: '复用规范，配置自动化流程' },
]
const systemLinks = [
  { to: '/runtime', icon: 'lucide:cpu', title: '运行环境' },
  { to: '/tools', icon: 'lucide:wrench', title: '工具与 Skill' },
  { to: '/settings', icon: 'lucide:settings-2', title: '设置' },
]
</script>

<template>
  <PageHeader eyebrow="工作区" title="主页" />
  <main id="main-content" class="content home-content" tabindex="-1">
    <section class="home-hero" aria-labelledby="home-headline">
      <img class="home-artwork" :src="orbitArtwork" alt="" width="1536" height="1024" fetchpriority="high" />
      <div class="home-hero-copy">
        <p class="home-kicker"><span aria-hidden="true" /> 从需求到交付</p>
        <h2 id="home-headline">从一个想法，<br />到可验证的交付<span>。</span></h2>
        <p class="home-intro">将需求、设计、执行与验收，<br class="home-copy-break" />连接在同一个工作区。</p>
        <div class="home-actions">
          <RouterLink class="home-action home-action-primary" to="/designer">开始设计 <Icon icon="lucide:arrow-right" aria-hidden="true" /></RouterLink>
          <RouterLink class="home-action" to="/tasks"><Icon icon="lucide:orbit" aria-hidden="true" /> 查看任务</RouterLink>
        </div>
        <p class="home-hero-caption">OpenCode Loopper <span aria-hidden="true">/</span> 本地 AI 开发工作区</p>
      </div>
    </section>

    <section class="home-workspace" aria-labelledby="workspace-heading">
      <div class="home-section-heading"><h2 id="workspace-heading">工作区入口</h2><p>选择下一步，从这里继续</p></div>
      <div class="home-workspace-grid">
        <RouterLink v-for="item in workspaceLinks" :key="item.to" :to="item.to" :class="['home-workspace-link', `home-tone-${item.tone}`]" :aria-label="item.title">
          <div class="home-card-top"><span class="home-card-icon"><Icon :icon="item.icon" aria-hidden="true" /></span><span class="home-step" aria-hidden="true">{{ item.step }}</span></div>
          <h3>{{ item.title }}</h3>
          <p>{{ item.description }}</p>
          <div class="home-card-bottom"><span>{{ item.hint }}</span><Icon icon="lucide:arrow-up-right" aria-hidden="true" /></div>
        </RouterLink>
      </div>
    </section>

    <section class="home-more" aria-labelledby="more-heading">
      <div class="home-section-heading"><h2 id="more-heading">回顾与管理</h2></div>
      <div class="home-more-grid">
        <RouterLink v-for="item in moreLinks" :key="item.to" class="home-more-link" :to="item.to" :aria-label="item.title">
          <Icon class="home-more-icon" :icon="item.icon" aria-hidden="true" />
          <div><h3>{{ item.title }}</h3><p>{{ item.description }}</p></div>
          <Icon class="home-more-arrow" icon="lucide:arrow-up-right" aria-hidden="true" />
        </RouterLink>
      </div>
    </section>

    <footer class="home-footer">
      <p><Icon icon="lucide:orbit" aria-hidden="true" /> 让每一步都有据可循</p>
      <nav aria-label="主页系统入口"><RouterLink v-for="item in systemLinks" :key="item.to" :to="item.to"><Icon :icon="item.icon" aria-hidden="true" />{{ item.title }}<Icon icon="lucide:arrow-up-right" aria-hidden="true" /></RouterLink></nav>
    </footer>
  </main>
</template>

<style scoped>
.home-content { display: grid; gap: 28px; }
.home-hero { position: relative; isolation: isolate; overflow: hidden; min-height: 360px; border: 1px solid var(--color-border-default); border-radius: var(--radius-card); background: var(--color-bg-canvas); }
.home-artwork { position: absolute; z-index: -2; top: 50%; right: -4%; width: 64%; height: auto; transform: translateY(-50%); pointer-events: none; }
.home-hero::after { position: absolute; z-index: -1; inset: 0; background: linear-gradient(90deg, var(--color-bg-canvas) 12%, rgb(7 11 20 / 96%) 30%, rgb(7 11 20 / 40%) 52%, transparent 72%); content: ''; pointer-events: none; }
.home-hero-copy { position: relative; max-width: 640px; padding: 38px 40px 30px; }
.home-kicker { display: flex; align-items: center; gap: 9px; margin: 0 0 22px; color: var(--color-text-secondary); font-size: 11px; letter-spacing: .18em; }
.home-kicker span { width: 18px; height: 2px; background: var(--color-accent-cyan); }
.home-hero h2 { margin: 0; font-size: clamp(30px, 2.8vw, 44px); font-weight: 650; line-height: 1.45; letter-spacing: -.045em; }
.home-hero h2 span { color: var(--color-accent-cyan); }
.home-intro { margin: 16px 0 24px; color: var(--color-text-secondary); font-size: 13px; line-height: 1.85; }
.home-copy-break { display: none; }
.home-actions { display: flex; flex-wrap: wrap; gap: 12px; }
.home-action { display: inline-flex; align-items: center; justify-content: center; gap: 12px; min-height: 42px; padding: 10px 19px; border: 1px solid var(--color-border-default); border-radius: var(--radius-control); background: var(--color-bg-surface); color: var(--color-text-primary); font-size: 13px; font-weight: 600; transition: background .16s, border-color .16s; }
.home-action:hover { border-color: var(--color-text-secondary); background: var(--color-bg-elevated); }
.home-action-primary { border-color: var(--color-action-primary); background: var(--color-action-primary); color: #fff; }
.home-action-primary:hover { border-color: var(--color-accent-cyan); background: #2563eb; }
.home-hero-caption { display: flex; flex-wrap: wrap; gap: 10px; margin: 28px 0 0; color: var(--color-text-secondary); font-size: 10px; letter-spacing: .04em; }
.home-hero-caption span { color: var(--color-text-muted); }
.home-section-heading { display: flex; align-items: baseline; justify-content: space-between; flex-wrap: wrap; gap: 8px; margin-bottom: 14px; }
.home-section-heading h2 { margin: 0; font-size: 15px; font-weight: 650; }
.home-section-heading p { margin: 0; color: var(--color-text-secondary); font-size: 11px; }
.home-workspace-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.home-workspace-link { --home-accent: var(--color-action-primary); display: flex; flex-direction: column; min-width: 0; padding: 20px; border: 1px solid var(--color-border-default); border-radius: var(--radius-card); background: linear-gradient(145deg, var(--color-bg-elevated), var(--color-bg-surface)); transition: transform .16s, border-color .16s, background .16s; }
.home-tone-violet { --home-accent: var(--color-accent-ai); }
.home-tone-cyan { --home-accent: var(--color-accent-cyan); }
.home-workspace-link:hover { border-color: var(--home-accent); transform: translateY(-3px); }
.home-card-top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; }
.home-card-icon { display: grid; place-items: center; width: 36px; height: 36px; border: 1px solid color-mix(in srgb, var(--home-accent) 30%, transparent); border-radius: 9px; background: color-mix(in srgb, var(--home-accent) 8%, transparent); color: var(--home-accent); font-size: 21px; }
.home-step { color: var(--color-text-muted); font: 11px var(--font-code); letter-spacing: .1em; }
.home-workspace-link h3, .home-more-link h3 { margin: 0; font-size: 14px; font-weight: 600; overflow-wrap: anywhere; }
.home-workspace-link p { margin: 9px 0 22px; color: var(--color-text-secondary); font-size: 12px; line-height: 1.8; }
.home-card-bottom { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: auto; padding-top: 14px; border-top: 1px solid var(--color-border-default); color: var(--color-text-secondary); font-size: 11px; }
.home-card-bottom .iconify { color: var(--home-accent); font-size: 16px; }
.home-more-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.home-more-link { display: flex; align-items: center; gap: 14px; min-width: 0; padding: 20px; border: 1px solid var(--color-border-default); border-radius: var(--radius-card); background: var(--color-bg-surface); transition: border-color .16s, background .16s; }
.home-more-link:hover { border-color: var(--color-text-secondary); background: var(--color-bg-elevated); }
.home-more-link p { margin: 7px 0 0; color: var(--color-text-secondary); font-size: 11px; line-height: 1.6; }
.home-more-icon { flex: 0 0 auto; color: var(--color-text-secondary); font-size: 22px; }
.home-more-arrow { flex: 0 0 auto; margin-left: auto; color: var(--color-text-secondary); font-size: 16px; }
.home-footer { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 16px; padding-top: 2px; color: var(--color-text-secondary); font-size: 11px; }
.home-footer p, .home-footer a { display: inline-flex; align-items: center; gap: 8px; margin: 0; }
.home-footer nav { display: flex; flex-wrap: wrap; gap: 22px; }
.home-footer a { min-height: 36px; }
.home-footer a:hover { color: var(--color-accent-cyan); }
@media (max-width: 1250px) { .home-workspace-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .home-hero-copy { padding: 32px; } .home-more-link { gap: 10px; padding: 16px; } }
@media (max-width: 1000px) { .home-copy-break { display: block; } .home-hero-copy { padding: 28px; } .home-artwork { width: 72%; right: -12%; } }
@media (max-width: 640px) {
  .home-content { gap: 24px; }
  .home-hero { min-height: 0; }
  .home-hero-copy { max-width: none; padding: 28px 22px; }
  .home-hero h2 { font-size: clamp(26px, 6.7vw, 36px); }
  .home-artwork { width: 100%; top: 10px; right: -28%; opacity: .3; transform: none; }
  .home-hero::after { background: linear-gradient(90deg, var(--color-bg-canvas), rgb(7 11 20 / 45%)); }
  .home-workspace-grid, .home-more-grid { grid-template-columns: 1fr; }
  .home-card-top { margin-bottom: 14px; }
  .home-footer { align-items: flex-start; flex-direction: column; }
}
@media (prefers-reduced-motion: reduce) { .home-workspace-link, .home-more-link, .home-action { transition: none; } .home-workspace-link:hover { transform: none; } }
</style>
