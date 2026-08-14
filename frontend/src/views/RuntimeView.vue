<script setup lang="ts">
import { computed, ref } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { useTaskStore } from '@/stores/taskStore'
import { formatDateTime } from '@/utils/dateTime'

const store = useTaskStore()
const runtime = computed(() => store.runtime)
const startingRuntime = ref(false)

async function startRuntime() {
  startingRuntime.value = true
  try {
    const started = await store.startRuntime()
    if (started?.status === 'ONLINE') ElMessage.success('OpenCode 已启动并通过连接检查')
  } finally {
    startingRuntime.value = false
  }
}
</script>

<template>
  <PageHeader eyebrow="System / Runtime" title="OpenCode Runtime">
    <template #actions><el-button v-if="runtime?.managed" type="primary" :loading="runtime?.status === 'STARTING'" @click="store.restartRuntime"><Icon icon="lucide:rotate-cw" />重启 Runtime</el-button><el-button v-else-if="runtime?.startupFailure" class="start-runtime-button" type="primary" :loading="startingRuntime" @click="startRuntime"><Icon icon="lucide:play" />启动 OpenCode 并检查连接</el-button><el-button v-else type="primary" :loading="runtime?.status === 'STARTING'" @click="store.refreshRuntime"><Icon icon="lucide:rotate-cw" />重新检测</el-button></template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <section v-if="store.error" class="error-panel error-panel-task" role="alert" aria-live="assertive" style="margin-bottom:16px"><Icon class="error-panel-icon" icon="lucide:circle-alert" /><div><h3>Runtime 操作未完成</h3><p>{{ store.error }}</p></div></section>
    <section v-if="runtime?.startupFailure" class="error-panel error-panel-task runtime-startup-error" role="alert" aria-live="assertive"><Icon class="error-panel-icon" icon="lucide:server-off" /><div><h3>OpenCode 自动启动失败</h3><p>{{ runtime.startupFailure }}</p><p v-if="runtime.endpoint" class="mono">尝试地址：{{ runtime.endpoint }}</p></div></section>
    <section v-if="runtime" class="runtime-grid"><article class="card card-pad runtime-hero"><div class="runtime-orb"><Icon icon="lucide:cpu" width="34" /></div><p class="eyebrow">OPENCODE SERVICE</p><h2>{{ runtime.version ? `OpenCode ${runtime.version}` : '等待 Runtime' }}</h2><StatusBadge :status="runtime.status" /><div class="runtime-model"><span>ACTIVE MODEL</span><strong translate="no">{{ runtime.model ?? '未配置' }}</strong></div></article><article class="card card-pad"><div class="card-header"><div><p class="eyebrow">PROCESS BOUNDARY</p><h2 class="card-title">受管进程</h2></div><Icon icon="lucide:shield-check" color="var(--color-success)" width="20" aria-hidden="true" /></div><dl class="definition-list"><div class="loopper-version"><dt>OpenCode Loopper 版本</dt><dd class="mono" translate="no">{{ runtime.loopperVersion ?? '未知' }}</dd></div><div><dt>{{ runtime.startupFailure ? '尝试地址' : '监听地址' }}</dt><dd class="mono" translate="no">{{ runtime.endpoint ?? '—' }}</dd></div><div><dt>进程 PID</dt><dd class="mono">{{ runtime.pid ?? '—' }}</dd></div><div><dt>所有权</dt><dd>{{ runtime.managed ? 'Loopper 受管，启动时严格核验' : runtime.startupFailure ? '未建立受管进程' : '外部复用服务' }}</dd></div><div><dt>上次检查</dt><dd class="mono"><time :datetime="runtime.checkedAt">{{ formatDateTime(runtime.checkedAt) }}</time></dd></div></dl></article></section>
    <section v-if="runtime?.capabilities" class="card card-pad capability-card"><div class="card-header"><div><p class="eyebrow">NATIVE CAPABILITY DISCOVERY</p><h2 class="card-title">OpenCode 可复用能力</h2></div><Icon icon="lucide:blocks" color="var(--color-accent-cyan)" width="20" /></div><div class="capability-grid"><article><span>Agent 发现</span><strong>{{ runtime.capabilities.agentDiscovery }}</strong><small>{{ runtime.capabilities.agents.map(agent => agent.name).join(' · ') || '未发现 Agent' }}</small></article><article><span>原生 plan Agent</span><strong>{{ runtime.capabilities.nativePlanAgent ? 'AVAILABLE' : 'NOT USED' }}</strong><small>当前仅展示能力，不接管 Designer</small></article><article><span>JSON Schema 传输</span><strong>{{ runtime.capabilities.structuredOutputTransport }}</strong><small>当前模型：{{ runtime.capabilities.selectedModelStructuredOutput }}</small></article><article><span>默认响应模式</span><strong>{{ runtime.capabilities.defaultResponseMode }}</strong><small>受控失败时最多回退一次 marker</small></article></div><p v-if="runtime.capabilities.detail" class="capability-detail">{{ runtime.capabilities.detail }}</p></section>
    <section v-else class="card empty-state"><div><Icon icon="lucide:server-off" width="30" /><strong>Runtime 尚未读取</strong><p>启动后可在此检查版本、监听地址和模型配置。</p></div></section><section class="card card-pad" style="margin-top: 16px"><div class="card-header"><div><p class="eyebrow">SAFETY GUARDRAILS</p><h2 class="card-title">执行授权边界</h2></div></div><div class="guardrail-grid"><div><Icon icon="lucide:folder-lock" /><strong>执行目录内读写</strong><span>按 Task 模式授权</span></div><div><Icon icon="lucide:circle-help" /><strong>权限请求</strong><span>按 Task 策略审计</span></div><div><Icon icon="lucide:ban" /><strong>外部路径 / push</strong><span>永不自动批准</span></div></div></section>
  </main>
</template>

<style scoped>
.runtime-startup-error { margin-bottom: 16px; }.runtime-startup-error .mono { margin-top: 6px; }.runtime-grid { display: grid; grid-template-columns: minmax(340px, .9fr) minmax(380px, 1.1fr); gap: 16px; }.runtime-hero { min-height: 275px; overflow: hidden; background: radial-gradient(circle at 65% 22%, rgb(34 211 238 / 16%), transparent 35%), linear-gradient(145deg, #121c30, #0d1424); }.runtime-orb { display: grid; place-items: center; width: 64px; height: 64px; margin-bottom: 22px; border: 1px solid rgb(34 211 238 / 42%); border-radius: 50%; color: var(--color-accent-cyan); box-shadow: var(--shadow-glow); }.runtime-hero h2 { margin: 0 0 13px; font-size: 22px; }.runtime-model { display: flex; flex-direction: column; gap: 5px; margin-top: 23px; }.runtime-model span { color: var(--color-text-muted); font-family: var(--font-code); font-size: 10px; }.runtime-model strong { font-family: var(--font-code); font-size: 12px; font-weight: 500; }.definition-list { margin: 8px 0 0; }.definition-list div { display: flex; justify-content: space-between; gap: 16px; padding: 14px 0; border-bottom: 1px solid var(--color-border-default); }.definition-list dt { color: var(--color-text-secondary); font-size: 12px; }.definition-list dd { margin: 0; color: var(--color-text-primary); font-size: 12px; text-align: right; }.guardrail-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }.guardrail-grid div { display: flex; flex-direction: column; gap: 7px; padding: 15px; border: 1px solid var(--color-border-default); border-radius: var(--radius-control); }.guardrail-grid svg { color: var(--color-accent-cyan); }.guardrail-grid strong { font-size: 12px; }.guardrail-grid span { color: var(--color-text-secondary); font-size: 11px; }
.capability-card { margin-top: 16px; }.capability-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-top: 14px; }.capability-grid article { display: grid; gap: 7px; min-width: 0; padding: 13px; border: 1px solid var(--color-border-default); border-radius: var(--radius-control); background: rgb(7 12 22 / 45%); }.capability-grid span { color: var(--color-text-muted); font-size: 9px; }.capability-grid strong { color: var(--color-accent-cyan); font: 700 10px var(--font-code); }.capability-grid small,.capability-detail { color: var(--color-text-secondary); font-size: 9px; line-height: 1.5; overflow-wrap: anywhere; }.capability-detail { margin: 12px 0 0; }
@media (max-width: 900px) { .runtime-grid { grid-template-columns: 1fr; }.capability-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .guardrail-grid,.capability-grid { grid-template-columns: 1fr; }.definition-list div { align-items: flex-start; flex-direction: column; }.definition-list dd { max-width: 100%; overflow-wrap: anywhere; text-align: left; } }
</style>
